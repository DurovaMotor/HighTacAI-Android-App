from fastapi import APIRouter

from hightac_platform.api import (
    admin,
    auth,
    commands,
    devices,
    inventory,
    migrations,
    mobile_proxy,
    websocket,
)


api_router = APIRouter(prefix="/api/v1")
api_router.include_router(admin.router)
api_router.include_router(auth.router)
api_router.include_router(devices.router)
api_router.include_router(inventory.router)
api_router.include_router(migrations.router)
api_router.include_router(commands.router)
api_router.include_router(mobile_proxy.router)
api_router.include_router(websocket.router)
