from fastapi import APIRouter, Request, Response, status

from hightac_platform.api.dependencies import (
    ActorDependency,
    IdempotencyKeyDependency,
    SessionDependency,
    WriteActorDependency,
    get_runtime,
)
from hightac_platform.api.schemas import AllOffRequest, LightCommandRequest
from hightac_platform.api.serialization import command_payload
from hightac_platform.db.models import Command
from hightac_platform.domain.enums import CommandAction
from hightac_platform.domain.errors import NotFoundError
from hightac_platform.services.idempotency import replay, store


router = APIRouter(tags=["commands"])


@router.post("/light-commands", status_code=status.HTTP_202_ACCEPTED)
def create_light_command(
    body: LightCommandRequest,
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
        route="/light-commands",
        key=idempotency_key,
        request_payload=request_payload,
    )
    if previous:
        response.status_code = previous.status_code
        return previous.payload
    command, _created = get_runtime(request).command_service.create(
        session,
        actor,
        action=body.action,
        color=body.color,
        product_code=body.product_code,
        tag_ids=body.tag_ids,
        station_id=None,
        idempotency_key=f"light:{idempotency_key}",
    )
    payload = command_payload(session, command)
    store(
        session,
        actor,
        method="POST",
        route="/light-commands",
        key=idempotency_key,
        request_payload=request_payload,
        status_code=202,
        response_payload=payload,
    )
    response.headers["Location"] = f"/api/v1/light-commands/{command.id}"
    return payload


@router.get("/light-commands/{id}")
def get_light_command(
    id: str,
    session: SessionDependency,
    _actor: ActorDependency,
) -> dict[str, object]:
    command = session.get(Command, id)
    if command is None:
        raise NotFoundError("Command was not found.")
    return command_payload(session, command)


@router.post(
    "/stations/{station_id}/all-off",
    status_code=status.HTTP_202_ACCEPTED,
)
def all_off(
    station_id: str,
    request: Request,
    response: Response,
    session: SessionDependency,
    actor: WriteActorDependency,
    idempotency_key: IdempotencyKeyDependency,
    body: AllOffRequest | None = None,
) -> dict[str, object]:
    request_payload = {
        "station_id": station_id,
        "confirmation": body.confirmation if body else None,
    }
    previous = replay(
        session,
        actor,
        method="POST",
        route="/stations/{station_id}/all-off",
        key=idempotency_key,
        request_payload=request_payload,
    )
    if previous:
        response.status_code = previous.status_code
        return previous.payload
    command, _created = get_runtime(request).command_service.create(
        session,
        actor,
        action=CommandAction.LIGHT_OFF,
        color=None,
        product_code=None,
        tag_ids=None,
        station_id=station_id,
        idempotency_key=f"all-off:{idempotency_key}",
    )
    payload = command_payload(session, command)
    store(
        session,
        actor,
        method="POST",
        route="/stations/{station_id}/all-off",
        key=idempotency_key,
        request_payload=request_payload,
        status_code=202,
        response_payload=payload,
    )
    response.headers["Location"] = f"/api/v1/light-commands/{command.id}"
    return payload
