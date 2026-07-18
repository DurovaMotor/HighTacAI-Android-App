from __future__ import annotations

import csv
from datetime import UTC, datetime
from enum import IntEnum
from io import BytesIO, StringIO
from typing import Annotated, Literal

from fastapi import APIRouter, BackgroundTasks, Query, Request, Response, status
from fastapi.responses import StreamingResponse
from openpyxl import Workbook
from sqlalchemy import asc, desc, exists, func, or_, select, text

from hightac_platform import __version__
from hightac_platform.api.dependencies import (
    ActorDependency,
    AdminDependency,
    AdminWriteDependency,
    IdempotencyKeyDependency,
    PaginationDependency,
    SessionDependency,
    get_runtime,
)
from hightac_platform.api.schemas import (
    ConfirmationRequest,
    NetworkSettingsPatchRequest,
    RestoreBackupRequest,
    SiteSettingsPatchRequest,
)
from hightac_platform.api.serialization import (
    backup_payload,
    operation_log_payload,
    page_payload,
    site_settings_payload,
    timestamp,
)
from hightac_platform.db.migrations import inspect_migration_state
from hightac_platform.db.models import (
    AndroidDevice,
    BackupRecord,
    Binding,
    Command,
    LightTag,
    OperationLog,
    Product,
    Site,
    Station,
)
from hightac_platform.db.repositories import paginate
from hightac_platform.domain.enums import (
    ActorType,
    CommandStatus,
    DeviceStatus,
    StationStatus,
)
from hightac_platform.domain.errors import (
    BadRequestError,
    ConflictError,
    InsufficientStorageError,
    NotFoundError,
    ServiceUnavailableError,
)
from hightac_platform.mqtt.supervisor import BrokerControlError
from hightac_platform.services.audit import append_operation_log
from hightac_platform.services.idempotency import replay, store
from hightac_platform.utils import new_id, utc_ms


router = APIRouter(tags=["administration"])


class BrokerLogLines(IntEnum):
    FIFTY = 50
    TWO_HUNDRED = 200
    FIVE_HUNDRED = 500


@router.get("/health/live")
def health_live() -> dict[str, object]:
    return {
        "status": "alive",
        "service": "HighTacPlatform",
        "version": __version__,
        "checked_at": timestamp(utc_ms()),
    }


@router.get("/health/ready")
def health_ready(
    request: Request, response: Response, session: SessionDependency
) -> dict[str, object]:
    runtime = get_runtime(request)
    checked_at = timestamp(utc_ms())
    database_status = "READY"
    database_message = "SQLite is reachable."
    try:
        session.execute(text("SELECT 1"))
    except Exception:
        database_status = "NOT_READY"
        database_message = "SQLite is not reachable."

    try:
        migration = inspect_migration_state(
            runtime.database.engine, runtime.settings
        )
        migration_status = "READY" if migration.at_head else "NOT_READY"
        migration_message = (
            f"current={migration.current or 'none'}, "
            f"head={migration.head or 'none'}"
        )
    except Exception:
        migration_status = "NOT_READY"
        migration_message = "Migration state could not be inspected."

    if not runtime.settings.mqtt_enabled:
        mqtt_status = "READY"
        mqtt_message = "MQTT bridge is disabled and not required."
    elif runtime.publisher.connected:
        mqtt_status = "READY"
        mqtt_message = "MQTT bridge is connected."
    elif runtime.settings.mqtt_required_for_ready:
        mqtt_status = "NOT_READY"
        mqtt_message = "MQTT bridge is required but disconnected."
    else:
        mqtt_status = "DEGRADED"
        mqtt_message = "MQTT bridge is disconnected but optional for readiness."

    checks = [
        _readiness_check(
            "database", database_status, database_message, checked_at
        ),
        _readiness_check(
            "migrations", migration_status, migration_message, checked_at
        ),
        _readiness_check(
            "mqtt_bridge", mqtt_status, mqtt_message, checked_at
        ),
    ]
    if any(item["status"] == "NOT_READY" for item in checks):
        overall = "NOT_READY"
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
    elif any(item["status"] == "DEGRADED" for item in checks):
        overall = "DEGRADED"
    else:
        overall = "READY"
    return {"status": overall, "checks": checks, "checked_at": checked_at}


@router.get("/dashboard/summary")
def dashboard_summary(
    request: Request,
    session: SessionDependency,
    _actor: ActorDependency,
) -> dict[str, object]:
    runtime = get_runtime(request)
    runtime.inventory_service.refresh_station_statuses(session)
    now = utc_ms()
    cutoff_24h = now - 86_400_000
    station_counts = {
        "total": _count(session, Station),
        "online": _count(
            session, Station, Station.status == StationStatus.ONLINE.value
        ),
        "stale": _count(
            session, Station, Station.status == StationStatus.STALE.value
        ),
        "offline": _count(
            session, Station, Station.status == StationStatus.OFFLINE.value
        ),
        "unknown": _count(
            session, Station, Station.status == StationStatus.UNKNOWN.value
        ),
    }
    threshold = runtime.settings_service.low_battery_percent(session)
    tag_counts = {
        "total": _count(session, LightTag),
        "discovered_24h": _count(
            session, LightTag, LightTag.first_seen_at_ms >= cutoff_24h
        ),
        "low_battery": _count(
            session,
            LightTag,
            LightTag.battery_level.is_not(None),
            LightTag.battery_level <= threshold,
        ),
        "abnormal": _count(
            session, LightTag, LightTag.is_abnormal.is_(True)
        ),
        "unbound": session.scalar(
            select(func.count())
            .select_from(LightTag)
            .where(
                ~exists(
                    select(Binding.id).where(
                        Binding.tag_id == LightTag.tag_id,
                        Binding.is_active.is_(True),
                    )
                )
            )
        )
        or 0,
    }
    command_total = _count(
        session, Command, Command.created_at_ms >= cutoff_24h
    )
    command_counts = {
        "total": command_total,
        "confirmed": _count(
            session,
            Command,
            Command.created_at_ms >= cutoff_24h,
            Command.status == CommandStatus.CONFIRMED.value,
        ),
        "partially_confirmed": _count(
            session,
            Command,
            Command.created_at_ms >= cutoff_24h,
            Command.status == CommandStatus.PARTIALLY_CONFIRMED.value,
        ),
        "unconfirmed": _count(
            session,
            Command,
            Command.created_at_ms >= cutoff_24h,
            Command.status == CommandStatus.UNCONFIRMED.value,
        ),
        "failed": _count(
            session,
            Command,
            Command.created_at_ms >= cutoff_24h,
            Command.status == CommandStatus.FAILED.value,
        ),
        "confirmation_rate": 0.0,
    }
    if command_total:
        command_counts["confirmation_rate"] = round(
            (
                command_counts["confirmed"]
                + command_counts["partially_confirmed"]
            )
            / command_total,
            4,
        )
    recent_operations = list(
        session.scalars(
            select(OperationLog)
            .order_by(OperationLog.created_at_ms.desc())
            .limit(10)
        )
    )
    return {
        "api_status": "READY",
        "broker": _broker_status(runtime),
        "station_counts": station_counts,
        "tag_counts": tag_counts,
        "product_counts": {
            "total": _count(
                session, Product, Product.is_active.is_(True)
            ),
            "active_bindings": _count(
                session, Binding, Binding.is_active.is_(True)
            ),
        },
        "command_counts_24h": command_counts,
        "device_counts": {
            "pending": _count(
                session,
                AndroidDevice,
                AndroidDevice.status == DeviceStatus.PENDING.value,
            ),
            "approved": _count(
                session,
                AndroidDevice,
                AndroidDevice.status == DeviceStatus.APPROVED.value,
            ),
            "revoked": _count(
                session,
                AndroidDevice,
                AndroidDevice.status == DeviceStatus.REVOKED.value,
            ),
            "online": _count(
                session,
                AndroidDevice,
                AndroidDevice.status == DeviceStatus.APPROVED.value,
                AndroidDevice.last_seen_at_ms >= now - 300_000,
            ),
        },
        "recent_incidents": _recent_incidents(session),
        "recent_operations": [
            operation_log_payload(item) for item in recent_operations
        ],
        "generated_at": timestamp(now),
    }


@router.get("/dashboard/trends")
def dashboard_trends(
    session: SessionDependency,
    _actor: AdminDependency,
    trend_range: Annotated[
        Literal["24h", "7d"], Query(alias="range")
    ] = "24h",
) -> dict[str, object]:
    now = utc_ms()
    bucket_seconds = 3_600 if trend_range == "24h" else 86_400
    bucket_ms = bucket_seconds * 1000
    bucket_count = 24 if trend_range == "24h" else 7
    end_bucket = (now // bucket_ms) * bucket_ms
    start_bucket = end_bucket - (bucket_count - 1) * bucket_ms
    points = {
        start_bucket + index * bucket_ms: {
            "commands": 0,
            "confirmed": 0,
            "tags_discovered": 0,
            "incidents": 0,
        }
        for index in range(bucket_count)
    }
    for created_at_ms, command_status in session.execute(
        select(Command.created_at_ms, Command.status).where(
            Command.created_at_ms >= start_bucket
        )
    ):
        bucket = (created_at_ms // bucket_ms) * bucket_ms
        if bucket in points:
            points[bucket]["commands"] += 1
            if command_status == CommandStatus.CONFIRMED.value:
                points[bucket]["confirmed"] += 1
    for (first_seen_at_ms,) in session.execute(
        select(LightTag.first_seen_at_ms).where(
            LightTag.first_seen_at_ms >= start_bucket
        )
    ):
        if first_seen_at_ms is None:
            continue
        bucket = (first_seen_at_ms // bucket_ms) * bucket_ms
        if bucket in points:
            points[bucket]["tags_discovered"] += 1
    for (occurred_at_ms,) in session.execute(
        select(OperationLog.created_at_ms).where(
            OperationLog.created_at_ms >= start_bucket,
            OperationLog.failure_reason.is_not(None),
        )
    ):
        bucket = (occurred_at_ms // bucket_ms) * bucket_ms
        if bucket in points:
            points[bucket]["incidents"] += 1
    return {
        "range": trend_range,
        "bucket_seconds": bucket_seconds,
        "points": [
            {"bucket_start": timestamp(bucket), **counts}
            for bucket, counts in sorted(points.items())
        ],
        "generated_at": timestamp(now),
    }


@router.get("/broker/status")
def broker_status(
    request: Request, _actor: ActorDependency
) -> dict[str, object]:
    return _broker_status(get_runtime(request))


@router.post("/broker/start", status_code=status.HTTP_202_ACCEPTED)
def broker_start(
    request: Request,
    response: Response,
    background_tasks: BackgroundTasks,
    session: SessionDependency,
    actor: AdminWriteDependency,
    idempotency_key: IdempotencyKeyDependency,
) -> dict[str, object]:
    return _queue_broker_action(
        request,
        response,
        background_tasks,
        session,
        actor,
        idempotency_key,
        "start",
        {},
    )


@router.post("/broker/stop", status_code=status.HTTP_202_ACCEPTED)
def broker_stop(
    body: ConfirmationRequest,
    request: Request,
    response: Response,
    background_tasks: BackgroundTasks,
    session: SessionDependency,
    actor: AdminWriteDependency,
    idempotency_key: IdempotencyKeyDependency,
) -> dict[str, object]:
    if body.confirmation != "STOP BROKER":
        raise BadRequestError(
            "Broker stop confirmation must be exactly STOP BROKER."
        )
    return _queue_broker_action(
        request,
        response,
        background_tasks,
        session,
        actor,
        idempotency_key,
        "stop",
        body.model_dump(mode="json"),
    )


@router.post("/broker/restart", status_code=status.HTTP_202_ACCEPTED)
def broker_restart(
    request: Request,
    response: Response,
    background_tasks: BackgroundTasks,
    session: SessionDependency,
    actor: AdminWriteDependency,
    idempotency_key: IdempotencyKeyDependency,
) -> dict[str, object]:
    return _queue_broker_action(
        request,
        response,
        background_tasks,
        session,
        actor,
        idempotency_key,
        "restart",
        {},
    )


@router.get("/broker/logs")
def broker_logs(
    request: Request,
    _actor: AdminDependency,
    lines: BrokerLogLines = BrokerLogLines.TWO_HUNDRED,
) -> dict[str, object]:
    requested_lines = int(lines)
    log_lines = get_runtime(request).broker_supervisor.tail_logs(requested_lines)
    return {
        "service_name": "HighTacMqttBroker",
        "requested_lines": requested_lines,
        "returned_lines": len(log_lines),
        "lines": [line[:4096] for line in log_lines],
        "truncated": len(log_lines) == requested_lines,
        "read_at": timestamp(utc_ms()),
    }


@router.get("/broker/config-summary")
def broker_config_summary(
    request: Request,
    session: SessionDependency,
    _actor: AdminDependency,
) -> dict[str, object]:
    settings = get_runtime(request).settings
    return {
        "service_name": "HighTacMqttBroker",
        "listener_host": settings.mqtt_host,
        "listener_port": settings.mqtt_port,
        "tls_enabled": False,
        "anonymous_enabled": False,
        "persistence_enabled": True,
        "backend_username": settings.mqtt_username or "not-configured",
        "station_account_count": _count(session, Station),
    }


@router.get("/operation-logs")
def list_operation_logs(
    session: SessionDependency,
    _actor: AdminDependency,
    pagination: PaginationDependency,
    event_type: Annotated[
        str | None, Query(max_length=128)
    ] = None,
    actor_type: ActorType | None = None,
    actor_id: Annotated[str | None, Query(max_length=128)] = None,
    entity_type: Annotated[
        str | None, Query(max_length=64)
    ] = None,
    entity_id: Annotated[str | None, Query(max_length=128)] = None,
    occurred_from: datetime | None = None,
    occurred_to: datetime | None = None,
) -> dict[str, object]:
    statement = _operation_log_query(
        event_type=event_type,
        actor_type=actor_type,
        actor_id=actor_id,
        entity_type=entity_type,
        entity_id=entity_id,
        occurred_from=occurred_from,
        occurred_to=occurred_to,
    )
    statement = _sort_logs(statement, pagination.sort)
    return page_payload(
        paginate(session, statement, pagination.page, pagination.page_size),
        operation_log_payload,
    )


@router.get("/operation-logs/export")
def export_operation_logs(
    session: SessionDependency,
    _actor: AdminDependency,
    export_format: Annotated[
        Literal["csv", "xlsx"], Query(alias="format")
    ] = "csv",
    event_type: Annotated[
        str | None, Query(max_length=128)
    ] = None,
    actor_type: ActorType | None = None,
    occurred_from: datetime | None = None,
    occurred_to: datetime | None = None,
) -> StreamingResponse:
    records = list(
        session.scalars(
            _operation_log_query(
                event_type=event_type,
                actor_type=actor_type,
                occurred_from=occurred_from,
                occurred_to=occurred_to,
            )
            .order_by(desc(OperationLog.created_at_ms))
            .limit(50_000)
        )
    )
    headers = [
        "id",
        "event_type",
        "actor_type",
        "actor_display_name",
        "station_id",
        "tag_id",
        "product_id",
        "command_id",
        "failure_code",
        "occurred_at",
    ]
    rows = [
        [operation_log_payload(record).get(key) for key in headers]
        for record in records
    ]
    if export_format == "xlsx":
        workbook = Workbook(write_only=True)
        worksheet = workbook.create_sheet("Operation Logs")
        worksheet.append(headers)
        for row in rows:
            worksheet.append([_excel_safe(value) for value in row])
        stream = BytesIO()
        workbook.save(stream)
        media_type = (
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        )
        filename = "hightac-operation-logs.xlsx"
        content = stream.getvalue()
    else:
        stream_text = StringIO(newline="")
        writer = csv.writer(stream_text)
        writer.writerow(headers)
        writer.writerows(
            [[_excel_safe(value) for value in row] for row in rows]
        )
        media_type = "text/csv; charset=utf-8"
        filename = "hightac-operation-logs.csv"
        content = ("\ufeff" + stream_text.getvalue()).encode("utf-8")
    return StreamingResponse(
        iter([content]),
        media_type=media_type,
        headers={
            "Content-Disposition": f'attachment; filename="{filename}"'
        },
    )


@router.get("/settings/site")
def get_site_settings(
    request: Request,
    session: SessionDependency,
    _actor: AdminDependency,
) -> dict[str, object]:
    site = _default_site(session)
    threshold = get_runtime(request).settings_service.low_battery_percent(
        session
    )
    return site_settings_payload(site, threshold)


@router.patch("/settings/site")
def patch_site_settings(
    body: SiteSettingsPatchRequest,
    request: Request,
    session: SessionDependency,
    actor: AdminWriteDependency,
) -> dict[str, object]:
    site = _default_site(session)
    fields = body.model_fields_set
    if "name" in fields and body.name is not None:
        site.name = body.name
    if "address" in fields:
        site.address = body.address
    if "notes" in fields:
        site.notes = body.notes
    site.updated_at_ms = utc_ms()
    runtime = get_runtime(request)
    if "low_battery_threshold" in fields:
        runtime.settings_service.set(
            session,
            "low_battery_percent",
            body.low_battery_threshold,
            actor.actor_id,
        )
    append_operation_log(
        session,
        event_type="settings.site_updated",
        actor=actor,
        site_id=site.id,
    )
    session.commit()
    return site_settings_payload(
        site, runtime.settings_service.low_battery_percent(session)
    )


@router.get("/settings/network")
def get_network_settings(
    request: Request,
    session: SessionDependency,
    _actor: AdminDependency,
) -> dict[str, object]:
    return _network_settings_payload(request, session)


@router.patch("/settings/network")
def patch_network_settings(
    body: NetworkSettingsPatchRequest,
    request: Request,
    session: SessionDependency,
    _actor: AdminWriteDependency,
) -> dict[str, object]:
    runtime = get_runtime(request)
    effective_values = {
        "api_bind_address": runtime.settings.host,
        "api_port": runtime.settings.port,
        "mqtt_host": runtime.settings.mqtt_host,
        "mqtt_port": runtime.settings.mqtt_port,
        "mqtt_tls_enabled": False,
    }

    if body.mqtt_tls_enabled is True:
        raise ConflictError(
            "MQTT TLS is not supported by this LAN plaintext release. "
            "Keep mqtt_tls_enabled false."
        )

    changed_fields = [
        field
        for field in body.model_fields_set
        if getattr(body, field) != effective_values[field]
    ]
    if changed_fields:
        raise ConflictError(
            "Network endpoints are deployment-managed and cannot be changed "
            "at runtime. Reconfigure the installer or service startup values "
            "and restart the platform."
        )

    return _network_settings_payload(request, session)


@router.get("/backups")
def list_backups(
    session: SessionDependency,
    _actor: AdminDependency,
    pagination: PaginationDependency,
    backup_status: Annotated[
        Literal["RUNNING", "SUCCEEDED", "FAILED", "DELETED"] | None,
        Query(alias="status"),
    ] = None,
) -> dict[str, object]:
    statement = select(BackupRecord)
    if backup_status:
        statement = statement.where(BackupRecord.status == backup_status)
    sort_value = (pagination.sort or "-created_at").strip()
    column = (
        BackupRecord.status
        if sort_value.lstrip("-") == "status"
        else BackupRecord.created_at_ms
    )
    statement = statement.order_by(
        desc(column) if sort_value.startswith("-") else asc(column)
    )
    return page_payload(
        paginate(session, statement, pagination.page, pagination.page_size),
        backup_payload,
    )


@router.post("/backups", status_code=status.HTTP_202_ACCEPTED)
def create_backup(
    request: Request,
    response: Response,
    session: SessionDependency,
    actor: AdminWriteDependency,
    idempotency_key: IdempotencyKeyDependency,
) -> dict[str, object]:
    previous = replay(
        session,
        actor,
        method="POST",
        route="/backups",
        key=idempotency_key,
        request_payload={},
    )
    if previous:
        response.status_code = previous.status_code
        return previous.payload
    try:
        record = get_runtime(request).backup_service.create(session, actor)
    except OSError as exc:
        raise InsufficientStorageError(
            "The platform data volume could not create the backup."
        ) from exc
    payload = backup_payload(record)
    store(
        session,
        actor,
        method="POST",
        route="/backups",
        key=idempotency_key,
        request_payload={},
        status_code=202,
        response_payload=payload,
    )
    response.headers["Location"] = f"/api/v1/backups/{record.id}"
    return payload


@router.post(
    "/backups/{id}/restore", status_code=status.HTTP_202_ACCEPTED
)
def restore_backup(
    id: str,
    body: RestoreBackupRequest,
    request: Request,
    response: Response,
    session: SessionDependency,
    actor: AdminWriteDependency,
    idempotency_key: IdempotencyKeyDependency,
) -> dict[str, object]:
    request_payload = {"id": id, **body.model_dump(mode="json")}
    previous = replay(
        session,
        actor,
        method="POST",
        route="/backups/{id}/restore",
        key=idempotency_key,
        request_payload=request_payload,
    )
    if previous:
        response.status_code = previous.status_code
        return previous.payload
    try:
        result = get_runtime(request).backup_service.restore(
            session,
            actor,
            id,
            body.confirmation,
            body.expected_sha256,
        )
    except OSError as exc:
        raise InsufficientStorageError(
            "The platform data volume could not restore the backup."
        ) from exc
    payload = {
        "id": result.operation_id,
        "backup_id": result.backup_id,
        "status": result.status,
        "pre_restore_backup_id": result.pre_restore_backup_id,
        "requested_at": timestamp(result.requested_at_ms),
    }
    store(
        session,
        actor,
        method="POST",
        route="/backups/{id}/restore",
        key=idempotency_key,
        request_payload=request_payload,
        status_code=202,
        response_payload=payload,
    )
    return payload


def _queue_broker_action(
    request: Request,
    response: Response,
    background_tasks: BackgroundTasks,
    session: object,
    actor: object,
    idempotency_key: str,
    action: str,
    request_payload: dict[str, object],
) -> dict[str, object]:
    runtime = get_runtime(request)
    if runtime.settings.broker_mode == "unmanaged":
        raise ServiceUnavailableError(
            "Broker service control is disabled in unmanaged mode."
        )
    previous = replay(
        session,
        actor,
        method="POST",
        route=f"/broker/{action}",
        key=idempotency_key,
        request_payload=request_payload,
    )
    if previous:
        response.status_code = previous.status_code
        return previous.payload
    requested_at_ms = utc_ms()
    operation = {
        "id": new_id(),
        "action": action.upper(),
        "status": "ACCEPTED",
        "requested_at": timestamp(requested_at_ms),
    }
    before = _broker_status(runtime)
    append_operation_log(
        session,
        event_type=f"broker.{action}_requested",
        actor=actor,
        result_summary={"operation_id": operation["id"]},
    )
    session.commit()
    store(
        session,
        actor,
        method="POST",
        route=f"/broker/{action}",
        key=idempotency_key,
        request_payload=request_payload,
        status_code=202,
        response_payload=operation,
    )
    response.headers["Location"] = (
        f"/api/v1/broker/status?operation_id={operation['id']}"
    )
    background_tasks.add_task(
        _perform_broker_action, runtime, action, before
    )
    return operation


def _perform_broker_action(
    runtime: object, action: str, before: dict[str, object]
) -> None:
    reason = None
    try:
        getattr(runtime.broker_supervisor, action)()
    except BrokerControlError as exc:
        reason = str(exc)[:512]
    after = _broker_status(runtime)
    runtime.event_bus.publish_threadsafe(
        "broker.status_changed",
        "HighTacMqttBroker",
        {
            "previous_status": before["service_state"],
            "current_status": (
                "FAILED" if reason else after["service_state"]
            ),
            "windows_service_running": (
                after["service_state"] == "RUNNING"
                and runtime.settings.broker_mode == "windows_service"
            ),
            "tcp_reachable": after["tcp_reachable"],
            "mqtt_connected": after["mqtt_connected"],
            "subscriptions_ready": after["subscriptions_ready"],
            "endpoint": after["endpoint"],
            "reason": reason,
        },
    )


def _broker_status(runtime: object) -> dict[str, object]:
    runtime.broker_supervisor.set_mqtt_connected(runtime.publisher.connected)
    observed = runtime.broker_supervisor.status()
    state_map = {
        "RUNNING_EXTERNAL": "RUNNING",
        "STOPPED": "STOPPED",
        "STARTING": "STARTING",
        "RUNNING": "RUNNING",
        "STOPPING": "STOPPING",
        "FAILED": "FAILED",
    }
    return {
        "service_name": "HighTacMqttBroker",
        "service_state": state_map.get(observed.state, "UNKNOWN"),
        "endpoint": (
            f"{runtime.settings.mqtt_host}:{runtime.settings.mqtt_port}"
        ),
        "tcp_reachable": observed.tcp_reachable,
        "mqtt_connected": runtime.publisher.connected,
        "subscriptions_ready": runtime.publisher.connected,
        "started_at": None,
        "uptime_seconds": None,
        "checked_at": timestamp(observed.observed_at_ms or utc_ms()),
    }


def _count(
    session: object, model: object, *conditions: object
) -> int:
    statement = select(func.count()).select_from(model)
    if conditions:
        statement = statement.where(*conditions)
    return session.scalar(statement) or 0


def _operation_log_query(
    *,
    event_type: str | None = None,
    actor_type: str | None = None,
    actor_id: str | None = None,
    entity_type: str | None = None,
    entity_id: str | None = None,
    occurred_from: datetime | None = None,
    occurred_to: datetime | None = None,
) -> object:
    statement = select(OperationLog)
    if event_type:
        statement = statement.where(OperationLog.event_type == event_type)
    if actor_type:
        statement = statement.where(
            OperationLog.actor_type == actor_type.upper()
        )
    if actor_id:
        statement = statement.where(OperationLog.actor_id == actor_id)
    entity_columns = {
        "site": OperationLog.site_id,
        "station": OperationLog.station_id,
        "tag": OperationLog.tag_id,
        "product": OperationLog.product_id,
        "command": OperationLog.command_id,
        "device": OperationLog.actor_id,
    }
    if entity_id and entity_type and entity_type.lower() in entity_columns:
        statement = statement.where(
            entity_columns[entity_type.lower()] == entity_id
        )
    elif entity_id:
        statement = statement.where(
            or_(
                OperationLog.site_id == entity_id,
                OperationLog.station_id == entity_id,
                OperationLog.tag_id == entity_id,
                OperationLog.product_id == entity_id,
                OperationLog.command_id == entity_id,
            )
        )
    if occurred_from:
        statement = statement.where(
            OperationLog.created_at_ms >= _datetime_ms(occurred_from)
        )
    if occurred_to:
        statement = statement.where(
            OperationLog.created_at_ms <= _datetime_ms(occurred_to)
        )
    return statement


def _sort_logs(statement: object, sort_value: str | None) -> object:
    value = (sort_value or "-occurred_at").split(",", 1)[0].strip()
    columns = {
        "occurred_at": OperationLog.created_at_ms,
        "event_type": OperationLog.event_type,
        "actor_type": OperationLog.actor_type,
    }
    column = columns.get(value.lstrip("-"), OperationLog.created_at_ms)
    return statement.order_by(
        desc(column) if value.startswith("-") else asc(column)
    )


def _network_settings_payload(
    request: Request, _session: object
) -> dict[str, object]:
    runtime = get_runtime(request)
    settings = runtime.settings
    return {
        "api_bind_address": settings.host,
        "api_port": settings.port,
        "mqtt_host": settings.mqtt_host,
        "mqtt_port": settings.mqtt_port,
        "mqtt_tls_enabled": False,
        "restart_required": False,
        "updated_at": timestamp(utc_ms()),
    }


def _default_site(session: object) -> Site:
    site = session.scalar(
        select(Site).order_by(Site.created_at_ms).limit(1)
    )
    if site is None:
        raise NotFoundError("Site was not found.")
    return site


def _recent_incidents(session: object) -> list[dict[str, object]]:
    records = list(
        session.scalars(
            select(OperationLog)
            .where(OperationLog.failure_reason.is_not(None))
            .order_by(OperationLog.created_at_ms.desc())
            .limit(10)
        )
    )
    return [
        {
            "id": record.id,
            "severity": "ERROR",
            "code": "OPERATION_FAILED",
            "message": (record.failure_reason or "Operation failed.")[:1024],
            "entity_type": _log_entity_type(record),
            "entity_id": _log_entity_id(record),
            "occurred_at": timestamp(record.created_at_ms),
        }
        for record in records
    ]


def _log_entity_type(record: OperationLog) -> str:
    if record.command_id:
        return "command"
    if record.product_id:
        return "product"
    if record.tag_id:
        return "tag"
    if record.station_id:
        return "station"
    return "system"


def _log_entity_id(record: OperationLog) -> str | None:
    return (
        record.command_id
        or record.product_id
        or record.tag_id
        or record.station_id
    )


def _readiness_check(
    name: str, check_status: str, message: str, checked_at: str | None
) -> dict[str, object]:
    return {
        "name": name,
        "status": check_status,
        "message": message,
        "checked_at": checked_at,
    }


def _datetime_ms(value: datetime) -> int:
    normalized = value if value.tzinfo else value.replace(tzinfo=UTC)
    return int(normalized.timestamp() * 1000)


def _excel_safe(value: object) -> object:
    if isinstance(value, str) and value.startswith(("=", "+", "-", "@")):
        return "'" + value
    return value
