from __future__ import annotations

import math
import uuid
from typing import Any, Callable

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from hightac_platform.db.models import (
    AdminUser,
    AndroidDevice,
    BackupRecord,
    Binding,
    Command,
    CommandItem,
    ImportJob,
    ImportJobRow,
    LightTag,
    OperationLog,
    Product,
    Site,
    Station,
)
from hightac_platform.db.repositories import CommandRepository, PageResult
from hightac_platform.domain.enums import CommandItemStatus
from hightac_platform.utils import ms_to_datetime, utc_ms


TAG_ONLINE_WINDOW_MS = 5 * 60 * 1000


def timestamp(value: int | None) -> str | None:
    converted = ms_to_datetime(value)
    return converted.isoformat().replace("+00:00", "Z") if converted else None


def page_payload(
    page: PageResult[Any], serializer: Callable[[Any], dict[str, Any]]
) -> dict[str, Any]:
    return {
        "items": [serializer(item) for item in page.items],
        "pagination": {
            "page": page.page,
            "page_size": page.page_size,
            "total_items": page.total,
            "total_pages": math.ceil(page.total / page.page_size) if page.total else 0,
        },
    }


def site_settings_payload(
    site: Site, low_battery_threshold: int
) -> dict[str, Any]:
    return {
        "id": site.id,
        "name": site.name,
        "address": site.address,
        "notes": site.notes,
        "low_battery_threshold": low_battery_threshold,
        "command_timeout_seconds": 10,
        "updated_at": timestamp(site.updated_at_ms),
    }


def station_payload(
    station: Station, *, broker_connected: bool
) -> dict[str, Any]:
    return {
        "station_id": station.station_id,
        "site_id": station.site_id,
        "alias": station.alias,
        "status": station.status,
        "mac": station.mac,
        "firmware_version": station.firmware_version,
        "server_address": station.server_address,
        "heartbeat_seconds": station.heartbeat_seconds,
        "last_heartbeat_at": timestamp(station.last_heartbeat_at_ms),
        "total_count": station.total_count,
        "send_count": station.send_count,
        "broker_connected": broker_connected,
        "created_at": timestamp(station.created_at_ms),
        "updated_at": timestamp(station.updated_at_ms),
    }


def tag_payload(
    session: Session,
    tag: LightTag,
    *,
    low_battery_threshold: int,
    now_ms: int | None = None,
) -> dict[str, Any]:
    now = now_ms or utc_ms()
    binding_id = session.scalar(
        select(Binding.id).where(
            Binding.tag_id == tag.tag_id, Binding.is_active.is_(True)
        )
    )
    online = bool(
        tag.last_seen_at_ms is not None
        and now - tag.last_seen_at_ms <= TAG_ONLINE_WINDOW_MS
    )
    return {
        "tag_id": tag.tag_id,
        "site_id": tag.site_id,
        "station_id": tag.station_id,
        "registered_at": timestamp(tag.registered_at_ms),
        "first_seen_at": timestamp(tag.first_seen_at_ms),
        "last_seen_at": timestamp(tag.last_seen_at_ms),
        "online": online,
        "battery_raw": tag.battery_raw,
        "battery_voltage": (
            tag.battery_voltage_mv / 1000
            if tag.battery_voltage_mv is not None
            else None
        ),
        "battery_level": tag.battery_level,
        "low_battery": bool(
            tag.battery_level is not None
            and tag.battery_level <= low_battery_threshold
        ),
        "firmware_version": tag.firmware_version,
        "group_no": tag.group_no,
        "last_result_type": tag.last_result_type,
        "is_abnormal": tag.is_abnormal,
        "abnormal_reason": tag.abnormal_reason,
        "active_binding_id": binding_id,
    }


def tag_detail_payload(
    session: Session,
    tag: LightTag,
    *,
    low_battery_threshold: int,
) -> dict[str, Any]:
    active_binding = session.scalar(
        select(Binding).where(
            Binding.tag_id == tag.tag_id, Binding.is_active.is_(True)
        )
    )
    recent_history = []
    occurred_at_ms = tag.last_seen_at_ms or tag.updated_at_ms
    if occurred_at_ms:
        history_id = str(
            uuid.uuid5(
                uuid.NAMESPACE_URL, f"hightac:tag:{tag.tag_id}:{occurred_at_ms}"
            )
        )
        recent_history.append(
            {
                "id": history_id,
                "occurred_at": timestamp(occurred_at_ms),
                "online": bool(
                    tag.last_seen_at_ms
                    and utc_ms() - tag.last_seen_at_ms <= TAG_ONLINE_WINDOW_MS
                ),
                "battery_raw": tag.battery_raw,
                "battery_level": tag.battery_level,
                "result_type": tag.last_result_type,
                "is_abnormal": tag.is_abnormal,
                "abnormal_reason": tag.abnormal_reason,
            }
        )
    return {
        "tag": tag_payload(
            session, tag, low_battery_threshold=low_battery_threshold
        ),
        "active_binding": (
            binding_payload(session, active_binding) if active_binding else None
        ),
        "recent_history": recent_history,
    }


def product_payload(session: Session, product: Product) -> dict[str, Any]:
    active_binding_count = session.scalar(
        select(func.count())
        .select_from(Binding)
        .where(
            Binding.product_id == product.id, Binding.is_active.is_(True)
        )
    )
    source = "EXCEL" if product.source == "EXCEL" else "BINDING"
    return {
        "id": product.id,
        "product_code": product.product_code,
        "product_name": product.product_name,
        "source": source,
        "is_active": product.is_active,
        "active_binding_count": active_binding_count or 0,
        "created_at": timestamp(product.created_at_ms),
        "updated_at": timestamp(product.updated_at_ms),
    }


def product_detail_payload(
    session: Session, product: Product
) -> dict[str, Any]:
    bindings = list(
        session.scalars(
            select(Binding)
            .where(
                Binding.product_id == product.id, Binding.is_active.is_(True)
            )
            .order_by(Binding.tag_id)
        )
    )
    return {
        "product": product_payload(session, product),
        "active_bindings": [
            binding_payload(session, binding) for binding in bindings
        ],
    }


def binding_payload(session: Session, binding: Binding) -> dict[str, Any]:
    product = session.get(Product, binding.product_id)
    tag = session.get(LightTag, binding.tag_id)
    station_id = binding.station_id or (tag.station_id if tag else None)
    if product is None or station_id is None:
        raise RuntimeError("Binding references incomplete inventory data.")
    return {
        "id": binding.id,
        "product_id": binding.product_id,
        "product_code": product.product_code,
        "product_name": product.product_name,
        "tag_id": binding.tag_id,
        "site_id": binding.site_id,
        "station_id": station_id,
        "source": binding.source,
        "actor_type": binding.actor_type,
        "actor_id": binding.actor_id,
        "actor_display_name": _actor_display_name(session, binding),
        "bound_at": timestamp(binding.bound_at_ms),
        "unbound_at": timestamp(binding.unbound_at_ms),
        "is_active": binding.is_active,
    }


def binding_event_snapshot(
    session: Session, binding: Binding
) -> dict[str, Any]:
    payload = binding_payload(session, binding)
    return {
        "binding_id": payload["id"],
        "product_id": payload["product_id"],
        "product_code": payload["product_code"],
        "product_name": payload["product_name"],
        "tag_id": payload["tag_id"],
        "site_id": payload["site_id"],
        "station_id": payload["station_id"],
        "source": payload["source"],
        "actor_type": payload["actor_type"],
        "actor_id": payload["actor_id"],
    }


def migration_preview_payload(preview: Any) -> dict[str, Any]:
    counts = {
        "MIGRATABLE": 0,
        "IDENTICAL": 0,
        "DUPLICATE_LEGACY_TAG": 0,
        "TAG_BOUND_TO_DIFFERENT_PRODUCT": 0,
        "STATION_MISMATCH": 0,
        "STATION_NOT_FOUND": 0,
    }
    records = []
    for item in preview.items:
        counts[item.classification.value] += 1
        records.append(item.digest_payload())
    return {
        "preview_token": preview.token,
        "summary": {
            "total_records": len(records),
            "migratable": counts["MIGRATABLE"],
            "identical": counts["IDENTICAL"],
            "duplicate_legacy_tag": counts["DUPLICATE_LEGACY_TAG"],
            "tag_bound_to_different_product": counts[
                "TAG_BOUND_TO_DIFFERENT_PRODUCT"
            ],
            "station_mismatch": counts["STATION_MISMATCH"],
            "station_not_found": counts["STATION_NOT_FOUND"],
        },
        "records": records,
    }


def migration_commit_payload(
    session: Session,
    *,
    committed_at_ms: int,
    records: list[dict[str, str | None]],
    created_products: int,
    created_tags: int,
    created_bindings: list[Binding],
) -> dict[str, Any]:
    outcomes = [record["outcome"] for record in records]
    return {
        "committed_at": timestamp(committed_at_ms),
        "summary": {
            "total_records": len(records),
            "migrated_records": outcomes.count("MIGRATED"),
            "identical_records": outcomes.count("IDENTICAL"),
            "skipped_records": outcomes.count("SKIPPED"),
            "created_products": created_products,
            "created_tags": created_tags,
            "created_bindings": len(created_bindings),
        },
        "records": records,
        "bindings": [
            binding_payload(session, binding) for binding in created_bindings
        ],
    }


def command_payload(
    session: Session,
    command: Command,
    items: list[CommandItem] | None = None,
) -> dict[str, Any]:
    resolved_items = (
        items if items is not None else CommandRepository(session).items(command.id)
    )
    product = (
        session.scalar(
            select(Product).where(Product.product_code == command.target_value)
        )
        if command.target_type == "PRODUCT"
        else None
    )
    statuses = [item.status for item in resolved_items]
    published_values = [
        item.first_published_at_ms
        for item in resolved_items
        if item.first_published_at_ms is not None
    ]
    return {
        "id": command.id,
        "action": (
            "LIGHT_OFF" if command.action == "ALL_OFF" else command.action
        ),
        "product_id": product.id if product else None,
        "product_code": product.product_code if product else None,
        "requested_color": command.color,
        "status": command.status,
        "target_count": len(resolved_items),
        "confirmed_count": statuses.count(CommandItemStatus.CONFIRMED.value),
        "unconfirmed_count": statuses.count(
            CommandItemStatus.UNCONFIRMED.value
        ),
        "failed_count": statuses.count(CommandItemStatus.FAILED.value),
        "created_at": timestamp(command.created_at_ms),
        "published_at": (
            timestamp(min(published_values)) if published_values else None
        ),
        "completed_at": timestamp(command.completed_at_ms),
        "items": [command_item_payload(item) for item in resolved_items],
    }


def command_item_payload(item: CommandItem) -> dict[str, Any]:
    attempts = (
        0
        if item.first_published_at_ms is None
        else min(2, item.retry_count + 1)
    )
    return {
        "id": item.id,
        "tag_id": item.tag_id,
        "station_id": item.station_id,
        "status": item.status,
        "publish_attempts": attempts,
        "published_at": timestamp(item.first_published_at_ms),
        "confirmed_at": timestamp(item.confirmed_at_ms),
        "last_result_type": item.result_type,
        "correlation": (
            "HEURISTIC_STATE_MATCH"
            if item.status == CommandItemStatus.CONFIRMED.value
            else "NONE"
        ),
        "failure_code": _command_failure_code(item),
    }


def command_event_payload(
    session: Session,
    command: Command,
    *,
    previous_status: str,
    item: CommandItem | None = None,
    previous_item_status: str | None = None,
) -> dict[str, Any]:
    payload = command_payload(session, command)
    item_update = None
    if item is not None:
        item_update = {
            "item_id": item.id,
            "tag_id": item.tag_id,
            "station_id": item.station_id,
            "previous_status": previous_item_status or item.status,
            "current_status": item.status,
            "correlation": (
                "HEURISTIC_STATE_MATCH"
                if item.status == CommandItemStatus.CONFIRMED.value
                else "NONE"
            ),
            "result_type": item.result_type,
        }
    return {
        "command_id": command.id,
        "action": payload["action"],
        "previous_status": previous_status,
        "current_status": command.status,
        "target_count": payload["target_count"],
        "confirmed_count": payload["confirmed_count"],
        "unconfirmed_count": payload["unconfirmed_count"],
        "failed_count": payload["failed_count"],
        "item": item_update,
    }


def device_payload(
    device: AndroidDevice, *, now_ms: int | None = None
) -> dict[str, Any]:
    now = now_ms or utc_ms()
    return {
        "id": device.id,
        "display_name": device.display_name,
        "manufacturer": device.manufacturer or "Unknown",
        "model": device.model or "Unknown",
        "app_version": device.app_version or "Unknown",
        "status": device.status,
        "online": bool(
            device.status == "APPROVED"
            and device.last_seen_at_ms is not None
            and now - device.last_seen_at_ms <= TAG_ONLINE_WINDOW_MS
        ),
        "first_seen_at": timestamp(device.first_seen_at_ms),
        "last_seen_at": timestamp(device.last_seen_at_ms),
        "approved_at": timestamp(device.approved_at_ms),
        "revoked_at": timestamp(device.revoked_at_ms),
    }


def operation_log_payload(log: OperationLog) -> dict[str, Any]:
    return {
        "id": log.id,
        "event_type": log.event_type,
        "site_id": log.site_id,
        "station_id": log.station_id,
        "tag_id": log.tag_id,
        "product_id": log.product_id,
        "command_id": log.command_id,
        "actor_type": log.actor_type,
        "actor_id": log.actor_id or "system",
        "actor_display_name": log.actor_name or "System",
        "request_summary": (log.request_summary or "")[:2048],
        "result_summary": (log.result_summary or "")[:2048],
        "client_ip": log.client_ip,
        "device_model": log.device_model,
        "failure_code": (
            "OPERATION_FAILED" if log.failure_reason else None
        ),
        "occurred_at": timestamp(log.created_at_ms),
    }


def import_job_payload(
    job: ImportJob, rows: list[ImportJobRow]
) -> dict[str, Any]:
    errors = [import_error_payload(row) for row in rows if not row.is_valid]
    status_map = {
        "PREVIEWED": "READY",
        "INVALID": "INVALID",
        "COMMITTED": "COMMITTED",
    }
    return {
        "id": job.id,
        "filename": job.filename,
        "status": status_map.get(job.status, "FAILED"),
        "total_rows": job.row_count,
        "valid_rows": job.valid_count,
        "error_rows": job.error_count,
        "errors": errors[:100],
        "errors_truncated": len(errors) > 100,
        "created_at": timestamp(job.created_at_ms),
        "validated_at": timestamp(job.created_at_ms),
        "committed_at": timestamp(job.committed_at_ms),
    }


def backup_payload(record: BackupRecord) -> dict[str, Any]:
    reason_map = {
        "AUTOMATIC": "SCHEDULED",
        "SCHEDULED": "SCHEDULED",
        "PRE_RESTORE": "PRE_RESTORE",
        "MANUAL": "MANUAL",
    }
    return {
        "id": record.id,
        "filename": record.filename,
        "reason": reason_map.get(record.kind, "MANUAL"),
        "status": record.status,
        "size_bytes": record.file_size,
        "sha256": record.sha256,
        "schema_version": record.schema_version,
        "created_at": timestamp(record.created_at_ms),
        "completed_at": timestamp(record.completed_at_ms),
        "error_code": "BACKUP_FAILED" if record.error_message else None,
    }


def _actor_display_name(session: Session, binding: Binding) -> str:
    if binding.actor_type == "ADMIN":
        actor = session.get(AdminUser, binding.actor_id)
        return actor.username if actor else binding.actor_id
    if binding.actor_type == "ANDROID":
        device = session.get(AndroidDevice, binding.actor_id)
        if device:
            return (
                device.display_name
                or " ".join(
                    value
                    for value in (device.manufacturer, device.model)
                    if value
                )
                or binding.actor_id
            )
    return "System" if binding.actor_type == "SYSTEM" else binding.actor_id


def _command_failure_code(item: CommandItem) -> str | None:
    if not item.error_message:
        return None
    if item.status == CommandItemStatus.UNCONFIRMED.value:
        return "ACK_TIMEOUT"
    if item.status == CommandItemStatus.SUPERSEDED.value:
        return "SUPERSEDED"
    if item.status == CommandItemStatus.FAILED.value:
        return "MQTT_PUBLISH_FAILED"
    return "COMMAND_ITEM_FAILED"


def import_error_payload(row: ImportJobRow) -> dict[str, Any]:
    message = row.error_message or "The row is invalid."
    code = (
        "DUPLICATE_PRODUCT_CODE"
        if "duplicate" in message.lower()
        else "INVALID_PRODUCT_CODE"
    )
    return {
        "row_number": row.row_number,
        "field": "product_code",
        "code": code,
        "message": message,
        "value": row.product_code,
    }
