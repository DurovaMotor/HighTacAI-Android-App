from __future__ import annotations

import asyncio
from uuid import UUID

from fastapi import APIRouter, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse

from hightac_platform.domain.errors import UnauthorizedError
from hightac_platform.events import system_notice
from hightac_platform.logging import request_id_context


router = APIRouter(tags=["events"])

ANDROID_EVENT_TYPES = frozenset({
    "broker.status_changed",
    "station.status_changed",
    "station.heartbeat",
    "tag.status_changed",
    "binding.created",
    "binding.removed",
    "command.status_changed",
    "system.notice",
})


@router.get("/ws/events", status_code=426)
def websocket_upgrade_required(_request: Request) -> JSONResponse:
    return JSONResponse(
        status_code=426,
        headers={"Upgrade": "websocket"},
        content={
            "error": {
                "code": "WEBSOCKET_UPGRADE_REQUIRED",
                "message": "A WebSocket upgrade request is required.",
                "details": [],
                "request_id": request_id_context.get(),
            }
        },
    )


@router.websocket("/ws/events")
async def websocket_events(websocket: WebSocket) -> None:
    runtime = websocket.app.state.runtime
    is_admin = False
    try:
        with runtime.database.session_factory() as session:
            cookie = websocket.cookies.get(runtime.settings.session_cookie_name)
            authorization = websocket.headers.get("authorization")
            if cookie:
                runtime.auth_service.authenticate_session(session, cookie)
                is_admin = True
            elif authorization:
                scheme, _, token = authorization.partition(" ")
                if scheme.lower() != "bearer" or not token:
                    raise UnauthorizedError("Invalid WebSocket authorization header.")
                try:
                    runtime.device_service.authenticate_token(session, token)
                except UnauthorizedError:
                    # Legacy, expired, or revoked device credentials are not
                    # required in zero-registration mode.
                    pass
            installation_id = websocket.headers.get("x-android-installation-id")
            if not is_admin and installation_id:
                UUID(installation_id)
    except (UnauthorizedError, ValueError):
        await websocket.close(code=4401)
        return

    await websocket.accept()
    subscription = runtime.event_bus.subscribe()
    try:
        await websocket.send_json(
            system_notice(
                severity="INFO",
                code="SNAPSHOT_REQUIRED",
                message="Fetch REST snapshots after connecting or reconnecting.",
            )
        )
        while True:
            try:
                event = await asyncio.wait_for(subscription.queue.get(), timeout=30)
                if (
                    not is_admin
                    and event.get("event_type") not in ANDROID_EVENT_TYPES
                ):
                    continue
                await websocket.send_json(event)
            except TimeoutError:
                await websocket.send_json(
                    system_notice(
                        severity="INFO",
                        code="KEEPALIVE",
                        message="The event stream is active.",
                    )
                )
    except WebSocketDisconnect:
        pass
    finally:
        runtime.event_bus.unsubscribe(subscription)
