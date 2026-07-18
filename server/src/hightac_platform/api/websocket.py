from __future__ import annotations

import asyncio

from fastapi import APIRouter, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse

from hightac_platform.domain.errors import UnauthorizedError
from hightac_platform.events import system_notice
from hightac_platform.logging import request_id_context


router = APIRouter(tags=["events"])


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
    try:
        with runtime.database.session_factory() as session:
            authorization = websocket.headers.get("authorization")
            if authorization:
                scheme, _, token = authorization.partition(" ")
                if scheme.lower() != "bearer" or not token:
                    raise UnauthorizedError("Invalid WebSocket authorization header.")
                runtime.device_service.authenticate_token(session, token)
            else:
                cookie = websocket.cookies.get(runtime.settings.session_cookie_name)
                if not cookie:
                    raise UnauthorizedError("WebSocket authentication is required.")
                runtime.auth_service.authenticate_session(session, cookie)
    except UnauthorizedError:
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
