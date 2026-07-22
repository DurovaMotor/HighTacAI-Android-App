from __future__ import annotations

from fastapi import APIRouter, Request, Response

from hightac_platform.api.dependencies import (
    DeviceDependency,
    IdempotencyKeyDependency,
    SessionDependency,
    get_runtime,
)
from hightac_platform.api.schemas import (
    AndroidBindingMigrationCommitRequest,
    AndroidBindingMigrationPreviewRequest,
)
from hightac_platform.api.serialization import (
    binding_event_snapshot,
    migration_preview_payload,
    timestamp,
)


router = APIRouter(tags=["migrations"])


@router.post("/migrations/android-bindings/preview")
def preview_android_bindings(
    body: AndroidBindingMigrationPreviewRequest,
    request: Request,
    response: Response,
    session: SessionDependency,
    actor: DeviceDependency,
) -> dict[str, object]:
    preview = get_runtime(request).migration_service.preview(
        session,
        actor,
        body.records,
    )
    response.headers["Cache-Control"] = "no-store"
    return migration_preview_payload(preview)


@router.post("/migrations/android-bindings/commit")
def commit_android_bindings(
    body: AndroidBindingMigrationCommitRequest,
    request: Request,
    response: Response,
    session: SessionDependency,
    actor: DeviceDependency,
    idempotency_key: IdempotencyKeyDependency,
) -> dict[str, object]:
    result = get_runtime(request).migration_service.commit(
        session,
        actor,
        body.records,
        preview_token=body.preview_token,
        selected_duplicate_keys=body.selected_duplicate_keys,
        idempotency_key=idempotency_key,
    )
    response.headers["Cache-Control"] = "no-store"
    if not result.replayed:
        event_bus = get_runtime(request).event_bus
        for binding in result.created_bindings:
            event_bus.publish_threadsafe(
                "binding.created",
                binding.id,
                {
                    "binding": binding_event_snapshot(session, binding),
                    "bound_at": timestamp(binding.bound_at_ms),
                },
            )
    return result.payload
