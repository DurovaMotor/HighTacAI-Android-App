from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Header, Query, Request, Response, status
from sqlalchemy import asc, desc, select

from hightac_platform.api.dependencies import (
    AdminDependency,
    AdminWriteDependency,
    IdempotencyKeyDependency,
    PaginationDependency,
    SessionDependency,
    get_runtime,
)
from hightac_platform.api.schemas import (
    ConfirmationRequest,
    DeviceApproveRequest,
    DeviceRenameRequest,
    EnrollmentCreateRequest,
)
from hightac_platform.api.serialization import device_payload, page_payload, timestamp
from hightac_platform.db.models import AndroidDevice
from hightac_platform.db.repositories import paginate
from hightac_platform.domain.enums import DeviceStatus
from hightac_platform.domain.errors import BadRequestError, NotFoundError
from hightac_platform.services.idempotency import replay, store


router = APIRouter(tags=["android-devices"])


@router.post("/device-enrollments", status_code=status.HTTP_202_ACCEPTED)
def create_enrollment(
    body: EnrollmentCreateRequest,
    request: Request,
    response: Response,
    session: SessionDependency,
    idempotency_key: IdempotencyKeyDependency,
) -> dict[str, object]:
    result = get_runtime(request).device_service.create_enrollment(
        session,
        fingerprint_hash=body.fingerprint_hash,
        installation_key_hash=body.installation_key_hash,
        manufacturer=body.manufacturer,
        model=body.model,
        app_version=body.app_version,
        idempotency_key=idempotency_key,
    )
    response.headers["Location"] = f"/api/v1/device-enrollments/{result.enrollment_id}"
    response.headers["Cache-Control"] = "no-store"
    return {
        "id": result.enrollment_id,
        "status": result.status,
        "poll_secret": result.poll_secret,
        "device_id": result.device_id,
        "device_token": result.device_token,
        "expires_at": timestamp(result.expires_at_ms),
        "poll_after_seconds": result.poll_after_seconds,
    }


@router.get("/device-enrollments/{id}")
def poll_enrollment(
    id: str,
    request: Request,
    response: Response,
    session: SessionDependency,
    poll_secret: Annotated[str, Header(alias="X-Enrollment-Secret")],
) -> dict[str, object | None]:
    result = get_runtime(request).device_service.poll_enrollment(
        session,
        enrollment_id=id,
        poll_secret=poll_secret,
    )
    response.headers["Cache-Control"] = "no-store"
    return {
        "id": result.enrollment_id,
        "status": result.status,
        "display_name": result.display_name,
        "device_id": result.device_id,
        "device_token": result.device_token,
        "expires_at": timestamp(result.expires_at_ms),
    }


@router.get("/android-devices")
def list_devices(
    session: SessionDependency,
    _actor: AdminDependency,
    pagination: PaginationDependency,
    device_status: Annotated[
        DeviceStatus | None, Query(alias="status")
    ] = None,
    q: Annotated[str | None, Query(min_length=1, max_length=256)] = None,
) -> dict[str, object]:
    statement = select(AndroidDevice)
    if device_status:
        statement = statement.where(AndroidDevice.status == device_status.value)
    if q:
        pattern = f"%{q.strip()}%"
        statement = statement.where(
            AndroidDevice.display_name.ilike(pattern)
            | AndroidDevice.manufacturer.ilike(pattern)
            | AndroidDevice.model.ilike(pattern)
        )
    sort_map = {
        "last_seen_at": AndroidDevice.last_seen_at_ms,
        "status": AndroidDevice.status,
        "display_name": AndroidDevice.display_name,
    }
    sort_name = (pagination.sort or "-last_seen_at").strip()
    descending = sort_name.startswith("-")
    column = sort_map.get(sort_name.lstrip("-"), AndroidDevice.last_seen_at_ms)
    statement = statement.order_by(desc(column) if descending else asc(column))
    return page_payload(
        paginate(session, statement, pagination.page, pagination.page_size),
        device_payload,
    )


@router.post("/android-devices/{id}/approve")
def approve_device(
    id: str,
    body: DeviceApproveRequest,
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
        route="/android-devices/{id}/approve",
        key=idempotency_key,
        request_payload=request_payload,
    )
    if previous:
        response.status_code = previous.status_code
        return previous.payload
    existing = session.get(AndroidDevice, id)
    if existing is None:
        raise NotFoundError("Android device was not found.")
    previous_status = existing.status
    device = get_runtime(request).device_service.approve(
        session, actor, id, body.display_name
    )
    payload = device_payload(device)
    store(
        session,
        actor,
        method="POST",
        route="/android-devices/{id}/approve",
        key=idempotency_key,
        request_payload=request_payload,
        status_code=200,
        response_payload=payload,
    )
    _publish_device_event(
        request, device, previous_status, "approved_by_admin"
    )
    return payload


@router.post("/android-devices/{id}/revoke")
def revoke_device(
    id: str,
    body: ConfirmationRequest,
    request: Request,
    response: Response,
    session: SessionDependency,
    actor: AdminWriteDependency,
    idempotency_key: IdempotencyKeyDependency,
) -> dict[str, object]:
    if body.confirmation != "REVOKE DEVICE":
        raise BadRequestError(
            "Device revocation confirmation must be exactly REVOKE DEVICE."
        )
    request_payload = {"id": id, **body.model_dump(mode="json")}
    previous = replay(
        session,
        actor,
        method="POST",
        route="/android-devices/{id}/revoke",
        key=idempotency_key,
        request_payload=request_payload,
    )
    if previous:
        response.status_code = previous.status_code
        return previous.payload
    existing = session.get(AndroidDevice, id)
    if existing is None:
        raise NotFoundError("Android device was not found.")
    previous_status = existing.status
    device = get_runtime(request).device_service.revoke(session, actor, id)
    payload = device_payload(device)
    store(
        session,
        actor,
        method="POST",
        route="/android-devices/{id}/revoke",
        key=idempotency_key,
        request_payload=request_payload,
        status_code=200,
        response_payload=payload,
    )
    _publish_device_event(request, device, previous_status, "revoked_by_admin")
    return payload


@router.post("/android-devices/{id}/rename")
def rename_device(
    id: str,
    body: DeviceRenameRequest,
    request: Request,
    session: SessionDependency,
    actor: AdminWriteDependency,
) -> dict[str, object]:
    device = get_runtime(request).device_service.rename(
        session, actor, id, body.display_name
    )
    return device_payload(device)


def _publish_device_event(
    request: Request,
    device: AndroidDevice,
    previous_status: str,
    reason: str,
) -> None:
    get_runtime(request).event_bus.publish_threadsafe(
        "device.status_changed",
        device.id,
        {
            "device_id": device.id,
            "display_name": device.display_name,
            "previous_status": previous_status,
            "current_status": device.status,
            "manufacturer": device.manufacturer or "Unknown",
            "model": device.model or "Unknown",
            "app_version": device.app_version or "Unknown",
            "reason": reason,
        },
    )
