from __future__ import annotations

import csv
import hashlib
from io import StringIO
from typing import Annotated, Literal

from fastapi import APIRouter, File, Header, Query, Request, Response, UploadFile, status
from fastapi.responses import StreamingResponse
from sqlalchemy import asc, desc, exists, or_, select

from hightac_platform.api.dependencies import (
    ActorDependency,
    AdminDependency,
    AdminWriteDependency,
    IdempotencyKeyDependency,
    PaginationDependency,
    SessionDependency,
    WriteActorDependency,
    get_runtime,
)
from hightac_platform.api.schemas import (
    BindingCreateRequest,
    ProductCreateRequest,
    ProductPatchRequest,
    RebindRequest,
    StationCreateRequest,
    StationPatchRequest,
    TagRegisterRequest,
)
from hightac_platform.api.serialization import (
    binding_event_snapshot,
    binding_payload,
    import_error_payload,
    import_job_payload,
    page_payload,
    product_detail_payload,
    product_payload,
    station_payload,
    tag_detail_payload,
    tag_payload,
    timestamp,
)
from hightac_platform.db.models import (
    Binding,
    ImportJob,
    ImportJobRow,
    LightTag,
    Product,
    Station,
)
from hightac_platform.db.repositories import paginate
from hightac_platform.domain.enums import BindingSource, StationStatus
from hightac_platform.domain.errors import (
    BadRequestError,
    ConflictError,
    ForbiddenError,
    NotFoundError,
    PayloadTooLargeError,
    UnsupportedMediaTypeError,
)
from hightac_platform.services.idempotency import replay, store
from hightac_platform.services.imports import MAX_IMPORT_BYTES
from hightac_platform.utils import (
    normalize_product_code,
    normalize_station_id,
    normalize_tag_id,
    utc_ms,
)


router = APIRouter(tags=["inventory"])
XLSX_MEDIA_TYPE = (
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
)


@router.get("/stations")
def list_stations(
    request: Request,
    session: SessionDependency,
    _actor: ActorDependency,
    pagination: PaginationDependency,
    station_status: Annotated[
        StationStatus | None, Query(alias="status")
    ] = None,
    site_id: str | None = None,
    q: Annotated[str | None, Query(min_length=1, max_length=256)] = None,
) -> dict[str, object]:
    runtime = get_runtime(request)
    runtime.inventory_service.refresh_station_statuses(session)
    statement = select(Station)
    if station_status:
        statement = statement.where(Station.status == station_status.value)
    if site_id:
        statement = statement.where(Station.site_id == site_id)
    if q:
        pattern = f"%{q.strip()}%"
        statement = statement.where(
            Station.station_id.ilike(pattern) | Station.alias.ilike(pattern)
        )
    statement = _sort(
        statement,
        pagination.sort,
        {
            "station_id": Station.station_id,
            "alias": Station.alias,
            "status": Station.status,
            "last_heartbeat_at": Station.last_heartbeat_at_ms,
        },
        "station_id",
    )
    return page_payload(
        paginate(session, statement, pagination.page, pagination.page_size),
        lambda station: station_payload(
            station, broker_connected=runtime.publisher.connected
        ),
    )


@router.post("/stations", status_code=status.HTTP_201_CREATED)
def create_station(
    body: StationCreateRequest,
    request: Request,
    response: Response,
    session: SessionDependency,
    actor: AdminWriteDependency,
    idempotency_key: IdempotencyKeyDependency,
) -> dict[str, object]:
    request_payload = body.model_dump(mode="json")
    previous = replay(
        session,
        actor,
        method="POST",
        route="/stations",
        key=idempotency_key,
        request_payload=request_payload,
    )
    if previous:
        response.status_code = previous.status_code
        return previous.payload
    runtime = get_runtime(request)
    station = runtime.inventory_service.create_station(
        session,
        actor,
        station_id=body.station_id,
        alias=body.alias,
        site_id=body.site_id,
    )
    payload = station_payload(
        station, broker_connected=runtime.publisher.connected
    )
    store(
        session,
        actor,
        method="POST",
        route="/stations",
        key=idempotency_key,
        request_payload=request_payload,
        status_code=201,
        response_payload=payload,
    )
    response.headers["Location"] = f"/api/v1/stations/{station.station_id}"
    return payload


@router.get("/stations/{station_id}/connection-checklist")
def station_connection_checklist(
    station_id: str,
    request: Request,
    session: SessionDependency,
    _actor: AdminDependency,
) -> dict[str, object]:
    normalized = normalize_station_id(station_id)
    station = session.get(Station, normalized)
    if station is None:
        raise NotFoundError("Station was not found.")
    settings = get_runtime(request).settings
    endpoint = f"{settings.mqtt_host}:{settings.mqtt_port}"
    heartbeat_ready = station.last_heartbeat_at_ms is not None
    return {
        "station_id": station.station_id,
        "items": [
            _check("station_id", "Station SN", station.station_id, "READY"),
            _check("broker_endpoint", "Broker endpoint", endpoint, "READY"),
            _check(
                "credential_slot",
                "Station credential",
                "Installer-managed station account",
                "READY",
                sensitive=True,
            ),
            _check(
                "task_topic",
                "Task topic",
                f"/estation/{station.station_id}/task",
                "READY",
            ),
            _check(
                "heartbeat_topic",
                "Heartbeat topic",
                f"/estation/{station.station_id}/heartbeat",
                "READY",
            ),
            _check(
                "result_topic",
                "Result topic",
                f"/estation/{station.station_id}/result",
                "READY",
            ),
            _check("tls_mode", "MQTT TLS", "Disabled", "READY"),
            _check(
                "last_heartbeat",
                "Last heartbeat",
                timestamp(station.last_heartbeat_at_ms) or "Not received",
                "READY" if heartbeat_ready else "ACTION_REQUIRED",
            ),
        ],
        "generated_at": timestamp(utc_ms()),
    }


@router.get("/stations/{station_id}")
def get_station(
    station_id: str,
    request: Request,
    session: SessionDependency,
    _actor: ActorDependency,
) -> dict[str, object]:
    runtime = get_runtime(request)
    runtime.inventory_service.refresh_station_statuses(session)
    station = session.get(Station, normalize_station_id(station_id))
    if station is None:
        raise NotFoundError("Station was not found.")
    return station_payload(
        station, broker_connected=runtime.publisher.connected
    )


@router.patch("/stations/{station_id}")
def patch_station(
    station_id: str,
    body: StationPatchRequest,
    request: Request,
    session: SessionDependency,
    actor: AdminWriteDependency,
) -> dict[str, object]:
    runtime = get_runtime(request)
    station = runtime.inventory_service.update_station_alias(
        session, actor, station_id, body.alias
    )
    return station_payload(
        station, broker_connected=runtime.publisher.connected
    )


@router.get("/tags/low-battery")
def low_battery_tags(
    request: Request,
    session: SessionDependency,
    _actor: ActorDependency,
    pagination: PaginationDependency,
    station_id: str | None = None,
) -> dict[str, object]:
    threshold = get_runtime(request).settings_service.low_battery_percent(session)
    statement = select(LightTag).where(
        LightTag.battery_level.is_not(None),
        LightTag.battery_level <= threshold,
    )
    if station_id:
        statement = statement.where(
            LightTag.station_id == normalize_station_id(station_id)
        )
    statement = _sort(
        statement,
        pagination.sort,
        {
            "battery_level": LightTag.battery_level,
            "tag_id": LightTag.tag_id,
            "last_seen_at": LightTag.last_seen_at_ms,
        },
        "battery_level",
    )
    return page_payload(
        paginate(session, statement, pagination.page, pagination.page_size),
        lambda tag: tag_payload(
            session, tag, low_battery_threshold=threshold
        ),
    )


@router.get("/tags/abnormal")
def abnormal_tags(
    request: Request,
    session: SessionDependency,
    _actor: ActorDependency,
    pagination: PaginationDependency,
    station_id: str | None = None,
) -> dict[str, object]:
    statement = select(LightTag).where(LightTag.is_abnormal.is_(True))
    if station_id:
        statement = statement.where(
            LightTag.station_id == normalize_station_id(station_id)
        )
    statement = _sort(
        statement,
        pagination.sort,
        {
            "tag_id": LightTag.tag_id,
            "last_seen_at": LightTag.last_seen_at_ms,
        },
        "tag_id",
    )
    threshold = get_runtime(request).settings_service.low_battery_percent(session)
    return page_payload(
        paginate(session, statement, pagination.page, pagination.page_size),
        lambda tag: tag_payload(
            session, tag, low_battery_threshold=threshold
        ),
    )


@router.get("/tags")
def list_tags(
    request: Request,
    session: SessionDependency,
    _actor: ActorDependency,
    pagination: PaginationDependency,
    station_id: str | None = None,
    site_id: str | None = None,
    online: bool | None = None,
    low_battery: bool | None = None,
    abnormal: bool | None = None,
    bound: bool | None = None,
    q: Annotated[str | None, Query(min_length=1, max_length=256)] = None,
) -> dict[str, object]:
    runtime = get_runtime(request)
    threshold = runtime.settings_service.low_battery_percent(session)
    statement = select(LightTag)
    if station_id:
        statement = statement.where(
            LightTag.station_id == normalize_station_id(station_id)
        )
    if site_id:
        statement = statement.where(LightTag.site_id == site_id)
    if online is not None:
        cutoff = utc_ms() - 5 * 60 * 1000
        condition = LightTag.last_seen_at_ms >= cutoff
        statement = statement.where(
            condition
            if online
            else or_(
                LightTag.last_seen_at_ms.is_(None),
                LightTag.last_seen_at_ms < cutoff,
            )
        )
    if low_battery is not None:
        condition = (
            LightTag.battery_level.is_not(None)
            & (LightTag.battery_level <= threshold)
        )
        statement = statement.where(condition if low_battery else ~condition)
    if abnormal is not None:
        statement = statement.where(LightTag.is_abnormal.is_(abnormal))
    if bound is not None:
        active_binding = exists(
            select(Binding.id).where(
                Binding.tag_id == LightTag.tag_id,
                Binding.is_active.is_(True),
            )
        )
        statement = statement.where(active_binding if bound else ~active_binding)
    if q:
        statement = statement.where(
            LightTag.tag_id.ilike(f"%{q.strip()}%")
        )
    statement = _sort(
        statement,
        pagination.sort,
        {
            "tag_id": LightTag.tag_id,
            "battery_level": LightTag.battery_level,
            "last_seen_at": LightTag.last_seen_at_ms,
            "station_id": LightTag.station_id,
        },
        "tag_id",
    )
    return page_payload(
        paginate(session, statement, pagination.page, pagination.page_size),
        lambda tag: tag_payload(
            session, tag, low_battery_threshold=threshold
        ),
    )


@router.post("/tags/register", status_code=status.HTTP_201_CREATED)
def register_tag(
    body: TagRegisterRequest,
    request: Request,
    response: Response,
    session: SessionDependency,
    actor: WriteActorDependency,
    idempotency_key: IdempotencyKeyDependency,
) -> dict[str, object]:
    request_payload = body.model_dump(mode="json")
    previous = replay(
        session,
        actor,
        method="POST",
        route="/tags/register",
        key=idempotency_key,
        request_payload=request_payload,
    )
    if previous:
        response.status_code = previous.status_code
        return previous.payload
    runtime = get_runtime(request)
    tag = runtime.inventory_service.register_tag(
        session,
        actor,
        tag_id=body.tag_id,
        station_id=body.station_id,
    )
    threshold = runtime.settings_service.low_battery_percent(session)
    payload = tag_payload(
        session, tag, low_battery_threshold=threshold
    )
    store(
        session,
        actor,
        method="POST",
        route="/tags/register",
        key=idempotency_key,
        request_payload=request_payload,
        status_code=201,
        response_payload=payload,
    )
    response.headers["Location"] = f"/api/v1/tags/{tag.tag_id}"
    return payload


@router.get("/tags/{tag_id}")
def get_tag(
    tag_id: str,
    request: Request,
    session: SessionDependency,
    _actor: ActorDependency,
) -> dict[str, object]:
    tag = session.get(LightTag, normalize_tag_id(tag_id))
    if tag is None:
        raise NotFoundError("Tag was not found.")
    threshold = get_runtime(request).settings_service.low_battery_percent(session)
    return tag_detail_payload(
        session, tag, low_battery_threshold=threshold
    )


@router.get("/products/import-template")
def product_import_template(
    request: Request, _actor: AdminDependency
) -> StreamingResponse:
    content = get_runtime(request).import_service.template()
    return StreamingResponse(
        iter([content]),
        media_type=XLSX_MEDIA_TYPE,
        headers={
            "Content-Disposition": 'attachment; filename="hightac-product-import.xlsx"'
        },
    )


@router.post("/products/imports", status_code=status.HTTP_202_ACCEPTED)
async def preview_product_import(
    request: Request,
    response: Response,
    session: SessionDependency,
    actor: AdminWriteDependency,
    idempotency_key: IdempotencyKeyDependency,
    file: Annotated[UploadFile, File()],
) -> dict[str, object]:
    if file.content_type != XLSX_MEDIA_TYPE:
        raise UnsupportedMediaTypeError(
            "Only .xlsx product workbooks are supported."
        )
    content = await file.read(MAX_IMPORT_BYTES + 1)
    if len(content) > MAX_IMPORT_BYTES:
        raise PayloadTooLargeError("Product workbook exceeds the 5 MB limit.")
    request_payload = {
        "filename": file.filename or "products.xlsx",
        "content_type": file.content_type,
        "sha256": hashlib.sha256(content).hexdigest(),
    }
    previous = replay(
        session,
        actor,
        method="POST",
        route="/products/imports",
        key=idempotency_key,
        request_payload=request_payload,
    )
    if previous:
        response.status_code = previous.status_code
        return previous.payload
    try:
        job = get_runtime(request).import_service.preview(
            session,
            actor,
            filename=file.filename or "products.xlsx",
            content=content,
        )
    except ValueError as exc:
        raise BadRequestError(str(exc)) from exc
    rows = _import_rows(session, job.id)
    payload = import_job_payload(job, rows)
    store(
        session,
        actor,
        method="POST",
        route="/products/imports",
        key=idempotency_key,
        request_payload=request_payload,
        status_code=202,
        response_payload=payload,
    )
    response.headers["Location"] = f"/api/v1/products/imports/{job.id}"
    return payload


@router.get("/products/imports/{id}")
def get_product_import(
    id: str,
    session: SessionDependency,
    _actor: AdminDependency,
) -> dict[str, object]:
    job = session.get(ImportJob, id)
    if job is None:
        raise NotFoundError("Import job was not found.")
    return import_job_payload(job, _import_rows(session, job.id))


@router.get("/products/imports/{id}/errors")
def download_product_import_errors(
    id: str,
    session: SessionDependency,
    _actor: AdminDependency,
) -> StreamingResponse:
    job = session.get(ImportJob, id)
    if job is None:
        raise NotFoundError("Import job was not found.")
    errors = [row for row in _import_rows(session, id) if not row.is_valid]
    if not errors:
        raise ConflictError("Import job has no validation errors.")
    stream = StringIO(newline="")
    writer = csv.writer(stream)
    writer.writerow(["row_number", "field", "code", "message", "value"])
    for row in errors:
        item = import_error_payload(row)
        writer.writerow(
            [
                item["row_number"],
                item["field"],
                item["code"],
                item["message"],
                item["value"],
            ]
        )
    content = ("\ufeff" + stream.getvalue()).encode("utf-8")
    return StreamingResponse(
        iter([content]),
        media_type="text/csv; charset=utf-8",
        headers={
            "Content-Disposition": f'attachment; filename="import-{id}-errors.csv"'
        },
    )


@router.post(
    "/products/imports/{id}/commit",
    status_code=status.HTTP_202_ACCEPTED,
)
def commit_product_import(
    id: str,
    request: Request,
    response: Response,
    session: SessionDependency,
    actor: AdminWriteDependency,
    idempotency_key: IdempotencyKeyDependency,
) -> dict[str, object]:
    request_payload = {"id": id}
    previous = replay(
        session,
        actor,
        method="POST",
        route="/products/imports/{id}/commit",
        key=idempotency_key,
        request_payload=request_payload,
    )
    if previous:
        response.status_code = previous.status_code
        return previous.payload
    job = get_runtime(request).import_service.commit(session, actor, id)
    payload = import_job_payload(job, _import_rows(session, job.id))
    store(
        session,
        actor,
        method="POST",
        route="/products/imports/{id}/commit",
        key=idempotency_key,
        request_payload=request_payload,
        status_code=202,
        response_payload=payload,
    )
    return payload


@router.get("/products")
def list_products(
    session: SessionDependency,
    _actor: ActorDependency,
    pagination: PaginationDependency,
    q: Annotated[str | None, Query(min_length=1, max_length=256)] = None,
    product_code: Annotated[
        str | None, Query(max_length=128)
    ] = None,
    product_name: Annotated[
        str | None, Query(max_length=256)
    ] = None,
    tag_id: str | None = None,
    source: Literal["BINDING", "EXCEL"] | None = None,
    is_active: bool | None = None,
) -> dict[str, object]:
    statement = select(Product)
    if q:
        pattern = f"%{q.strip()}%"
        statement = statement.where(
            Product.product_code.ilike(pattern)
            | Product.product_name.ilike(pattern)
        )
    if product_code:
        statement = statement.where(
            Product.product_code.ilike(f"%{product_code.strip()}%")
        )
    if product_name:
        statement = statement.where(
            Product.product_name.ilike(f"%{product_name.strip()}%")
        )
    if tag_id:
        normalized_tag = normalize_tag_id(tag_id)
        statement = statement.where(
            exists(
                select(Binding.id).where(
                    Binding.product_id == Product.id,
                    Binding.tag_id == normalized_tag,
                    Binding.is_active.is_(True),
                )
            )
        )
    if is_active is not None:
        statement = statement.where(Product.is_active.is_(is_active))
    if source:
        statement = statement.where(
            Product.source.in_(["BINDING", "WEB"])
            if source == "BINDING"
            else Product.source == source
        )
    statement = _sort(
        statement,
        pagination.sort,
        {
            "product_code": Product.product_code,
            "product_name": Product.product_name,
            "created_at": Product.created_at_ms,
            "updated_at": Product.updated_at_ms,
        },
        "product_code",
    )
    return page_payload(
        paginate(session, statement, pagination.page, pagination.page_size),
        lambda product: product_payload(session, product),
    )


@router.post("/products", status_code=status.HTTP_201_CREATED)
def create_product(
    body: ProductCreateRequest,
    request: Request,
    response: Response,
    session: SessionDependency,
    actor: AdminWriteDependency,
    idempotency_key: IdempotencyKeyDependency,
) -> dict[str, object]:
    request_payload = body.model_dump(mode="json")
    previous = replay(
        session,
        actor,
        method="POST",
        route="/products",
        key=idempotency_key,
        request_payload=request_payload,
    )
    if previous:
        response.status_code = previous.status_code
        return previous.payload
    product = get_runtime(request).inventory_service.create_product(
        session,
        actor,
        product_code=body.product_code,
        product_name=body.product_name,
    )
    payload = product_payload(session, product)
    store(
        session,
        actor,
        method="POST",
        route="/products",
        key=idempotency_key,
        request_payload=request_payload,
        status_code=201,
        response_payload=payload,
    )
    response.headers["Location"] = f"/api/v1/products/{product.id}"
    return payload


@router.get("/products/{id}")
def get_product(
    id: str,
    session: SessionDependency,
    _actor: ActorDependency,
) -> dict[str, object]:
    product = session.get(Product, id)
    if product is None:
        raise NotFoundError("Product was not found.")
    return product_detail_payload(session, product)


@router.patch("/products/{id}")
def patch_product(
    id: str,
    body: ProductPatchRequest,
    request: Request,
    session: SessionDependency,
    actor: AdminWriteDependency,
) -> dict[str, object]:
    product = get_runtime(request).inventory_service.update_product(
        session,
        actor,
        id,
        product_name=body.product_name,
        is_active=body.is_active,
    )
    return product_payload(session, product)


@router.get("/bindings")
def list_bindings(
    session: SessionDependency,
    _actor: ActorDependency,
    pagination: PaginationDependency,
    product_id: str | None = None,
    product_code: Annotated[
        str | None, Query(max_length=128)
    ] = None,
    tag_id: str | None = None,
    station_id: str | None = None,
    source: BindingSource | None = None,
    is_active: bool | None = True,
) -> dict[str, object]:
    statement = select(Binding).join(Product, Product.id == Binding.product_id)
    if product_id:
        statement = statement.where(Binding.product_id == product_id)
    if product_code:
        statement = statement.where(
            Product.product_code == normalize_product_code(product_code)
        )
    if tag_id:
        statement = statement.where(
            Binding.tag_id == normalize_tag_id(tag_id)
        )
    if station_id:
        statement = statement.where(
            Binding.station_id == normalize_station_id(station_id)
        )
    if source:
        statement = statement.where(Binding.source == source.value)
    if is_active is not None:
        statement = statement.where(Binding.is_active.is_(is_active))
    statement = _sort(
        statement,
        pagination.sort or "-bound_at",
        {"bound_at": Binding.bound_at_ms, "tag_id": Binding.tag_id},
        "bound_at",
    )
    return page_payload(
        paginate(session, statement, pagination.page, pagination.page_size),
        lambda binding: binding_payload(session, binding),
    )


@router.post("/bindings", status_code=status.HTTP_201_CREATED)
def create_binding(
    body: BindingCreateRequest,
    request: Request,
    response: Response,
    session: SessionDependency,
    actor: WriteActorDependency,
    idempotency_key: IdempotencyKeyDependency,
) -> dict[str, object]:
    request_payload = body.model_dump(mode="json")
    previous = replay(
        session,
        actor,
        method="POST",
        route="/bindings",
        key=idempotency_key,
        request_payload=request_payload,
    )
    if previous:
        response.status_code = previous.status_code
        return previous.payload
    result = get_runtime(request).inventory_service.bind(
        session,
        actor,
        product_code=body.product_code,
        product_name=body.product_name,
        tag_id=body.tag_id,
        station_id=body.station_id,
        idempotency_key=f"create:{idempotency_key}",
    )
    payload = binding_payload(session, result.binding)
    store(
        session,
        actor,
        method="POST",
        route="/bindings",
        key=idempotency_key,
        request_payload=request_payload,
        status_code=201,
        response_payload=payload,
    )
    response.headers["Location"] = f"/api/v1/bindings/{result.binding.id}"
    get_runtime(request).event_bus.publish_threadsafe(
        "binding.created",
        result.binding.id,
        {
            "binding": binding_event_snapshot(session, result.binding),
            "bound_at": timestamp(result.binding.bound_at_ms),
        },
    )
    return payload


@router.delete("/bindings/{id}")
def delete_binding(
    id: str,
    request: Request,
    response: Response,
    session: SessionDependency,
    actor: WriteActorDependency,
    idempotency_key: IdempotencyKeyDependency,
    confirmation: Annotated[str | None, Header(alias="confirmation")] = None,
) -> dict[str, object]:
    if actor.is_admin and confirmation != "UNBIND":
        raise ForbiddenError(
            "Administrator unbind requests require confirmation: UNBIND."
        )
    request_payload = {"id": id}
    previous = replay(
        session,
        actor,
        method="DELETE",
        route="/bindings/{id}",
        key=idempotency_key,
        request_payload=request_payload,
    )
    if previous:
        response.status_code = previous.status_code
        return previous.payload
    result = get_runtime(request).inventory_service.unbind(session, actor, id)
    payload = binding_payload(session, result.binding)
    store(
        session,
        actor,
        method="DELETE",
        route="/bindings/{id}",
        key=idempotency_key,
        request_payload=request_payload,
        status_code=200,
        response_payload=payload,
    )
    get_runtime(request).event_bus.publish_threadsafe(
        "binding.removed",
        result.binding.id,
        {
            "binding": binding_event_snapshot(session, result.binding),
            "unbound_at": timestamp(result.binding.unbound_at_ms),
            "replacement_binding_id": None,
        },
    )
    return payload


@router.post("/bindings/{id}/rebind")
def rebind(
    id: str,
    body: RebindRequest,
    request: Request,
    response: Response,
    session: SessionDependency,
    actor: WriteActorDependency,
    idempotency_key: IdempotencyKeyDependency,
) -> dict[str, object]:
    request_payload = {"id": id, **body.model_dump(mode="json")}
    previous = replay(
        session,
        actor,
        method="POST",
        route="/bindings/{id}/rebind",
        key=idempotency_key,
        request_payload=request_payload,
    )
    if previous:
        response.status_code = previous.status_code
        return previous.payload
    result = get_runtime(request).inventory_service.rebind(
        session,
        actor,
        id,
        product_code=body.product_code,
        product_name=body.product_name,
        expected_tag_id=body.expected_tag_id,
        idempotency_key=f"rebind:{idempotency_key}",
    )
    payload = {
        "removed_binding": binding_payload(
            session, result.removed_binding
        ),
        "created_binding": binding_payload(
            session, result.created_binding
        ),
    }
    store(
        session,
        actor,
        method="POST",
        route="/bindings/{id}/rebind",
        key=idempotency_key,
        request_payload=request_payload,
        status_code=200,
        response_payload=payload,
    )
    runtime = get_runtime(request)
    runtime.event_bus.publish_threadsafe(
        "binding.removed",
        result.removed_binding.id,
        {
            "binding": binding_event_snapshot(
                session, result.removed_binding
            ),
            "unbound_at": timestamp(result.removed_binding.unbound_at_ms),
            "replacement_binding_id": result.created_binding.id,
        },
    )
    runtime.event_bus.publish_threadsafe(
        "binding.created",
        result.created_binding.id,
        {
            "binding": binding_event_snapshot(
                session, result.created_binding
            ),
            "bound_at": timestamp(result.created_binding.bound_at_ms),
        },
    )
    return payload


def _import_rows(session: object, job_id: str) -> list[ImportJobRow]:
    return list(
        session.scalars(
            select(ImportJobRow)
            .where(ImportJobRow.job_id == job_id)
            .order_by(ImportJobRow.row_number)
        )
    )


def _sort(
    statement: object,
    sort_value: str | None,
    mapping: dict[str, object],
    default: str,
) -> object:
    value = (sort_value or default).split(",", 1)[0].strip()
    descending = value.startswith("-")
    column = mapping.get(value.lstrip("-"), mapping[default])
    return statement.order_by(desc(column) if descending else asc(column))


def _check(
    key: str,
    label: str,
    value: str,
    item_status: str,
    *,
    sensitive: bool = False,
) -> dict[str, object]:
    return {
        "key": key,
        "label": label,
        "value": value,
        "sensitive": sensitive,
        "status": item_status,
    }
