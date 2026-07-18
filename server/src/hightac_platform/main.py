from __future__ import annotations

import asyncio
import logging
import time
from uuid import UUID
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass
from typing import Any

import httpx
from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

from hightac_platform.api.router import api_router
from hightac_platform import __version__
from hightac_platform.config import Settings, get_settings
from hightac_platform.db.migrations import upgrade_database
from hightac_platform.db.session import Database
from hightac_platform.domain.errors import DomainError
from hightac_platform.events import EventBus
from hightac_platform.logging import configure_logging, request_id_context
from hightac_platform.mqtt.bridge import MqttBridge
from hightac_platform.mqtt.supervisor import BrokerSupervisor, create_broker_supervisor
from hightac_platform.services.auth import AuthService, LoginRateLimiter
from hightac_platform.services.backups import BackupService
from hightac_platform.services.bootstrap import bootstrap_first_run
from hightac_platform.services.commands import (
    CommandPublisher,
    CommandScheduler,
    CommandService,
    UnavailablePublisher,
)
from hightac_platform.services.devices import DeviceService
from hightac_platform.services.imports import ProductImportService
from hightac_platform.services.inventory import InventoryService
from hightac_platform.services.migrations import AndroidBindingMigrationService
from hightac_platform.services.mobile_proxy import (
    MOBILE_MEDIA_SIGNING_PURPOSE,
    MobileProxyService,
)
from hightac_platform.services.lifecycle import (
    InProcessRestartCoordinator,
    LifecycleCommandScheduler,
    LifecycleTelemetryService,
    PlatformLifecycleCoordinator,
    ProcessRestartCoordinator,
    RestartCoordinator,
)
from hightac_platform.services.lifecycle_scheduler import LifecycleScheduler
from hightac_platform.services.settings import AppSettingsService
from hightac_platform.services.telemetry import TelemetryService
from hightac_platform.utils import new_id


logger = logging.getLogger(__name__)


@dataclass(slots=True)
class Runtime:
    settings: Settings
    database: Database
    event_bus: EventBus
    auth_service: AuthService
    device_service: DeviceService
    inventory_service: InventoryService
    migration_service: AndroidBindingMigrationService
    settings_service: AppSettingsService
    command_service: CommandService
    import_service: ProductImportService
    backup_service: BackupService
    telemetry_service: TelemetryService
    command_scheduler: CommandScheduler
    publisher: CommandPublisher
    mqtt_bridge: MqttBridge | None
    broker_supervisor: BrokerSupervisor
    lifecycle: PlatformLifecycleCoordinator
    lifecycle_scheduler: LifecycleScheduler
    restart_coordinator: RestartCoordinator
    mobile_proxy_service: MobileProxyService


def create_app(
    settings: Settings | None = None,
    *,
    publisher: CommandPublisher | None = None,
    broker_supervisor: BrokerSupervisor | None = None,
    restart_coordinator: RestartCoordinator | None = None,
    mobile_proxy_transport: httpx.AsyncBaseTransport | None = None,
) -> FastAPI:
    resolved_settings = settings or get_settings()
    database = Database(resolved_settings)
    event_bus = EventBus()
    actual_restart_coordinator = restart_coordinator or (
        InProcessRestartCoordinator()
        if resolved_settings.environment == "test"
        else ProcessRestartCoordinator(
            delay_seconds=resolved_settings.service_restart_delay_seconds
        )
    )
    lifecycle = PlatformLifecycleCoordinator(
        actual_restart_coordinator,
        quiesce_timeout_seconds=(
            resolved_settings.restore_quiesce_timeout_seconds
        ),
    )
    command_service = CommandService(publisher or UnavailablePublisher(), event_bus)
    telemetry_service = LifecycleTelemetryService(
        database.session_factory,
        event_bus,
        command_service,
        lifecycle=lifecycle,
    )
    mqtt_bridge = None
    actual_publisher = publisher
    if actual_publisher is None:
        mqtt_bridge = MqttBridge(resolved_settings, telemetry_service, event_bus)
        actual_publisher = mqtt_bridge
        command_service.publisher = mqtt_bridge
    supervisor = broker_supervisor or create_broker_supervisor(resolved_settings)
    backup_service = BackupService(
        resolved_settings,
        database,
        lifecycle=lifecycle,
    )
    command_scheduler = LifecycleCommandScheduler(
        database.session_factory,
        command_service,
        lifecycle=lifecycle,
    )
    lifecycle_scheduler = LifecycleScheduler(
        database.session_factory,
        backup_service,
        lifecycle,
    )
    device_service = DeviceService(resolved_settings)
    mobile_proxy_service = MobileProxyService(
        resolved_settings,
        transport=mobile_proxy_transport,
        media_signing_key_provider=lambda: device_service.derive_signing_key(
            MOBILE_MEDIA_SIGNING_PURPOSE
        ),
    )
    runtime = Runtime(
        settings=resolved_settings,
        database=database,
        event_bus=event_bus,
        auth_service=AuthService(resolved_settings, LoginRateLimiter(resolved_settings)),
        device_service=device_service,
        inventory_service=InventoryService(),
        migration_service=AndroidBindingMigrationService(
            lambda: device_service.derive_signing_key(
                "android-binding-migration-preview-v1"
            )
        ),
        settings_service=AppSettingsService(resolved_settings),
        command_service=command_service,
        import_service=ProductImportService(),
        backup_service=backup_service,
        telemetry_service=telemetry_service,
        command_scheduler=command_scheduler,
        publisher=actual_publisher,
        mqtt_bridge=mqtt_bridge,
        broker_supervisor=supervisor,
        lifecycle=lifecycle,
        lifecycle_scheduler=lifecycle_scheduler,
        restart_coordinator=actual_restart_coordinator,
        mobile_proxy_service=mobile_proxy_service,
    )

    @asynccontextmanager
    async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
        resolved_settings.ensure_runtime_directories()
        configure_logging(resolved_settings)
        if resolved_settings.auto_migrate:
            upgrade_database(resolved_settings)
        with database.session_factory() as session:
            recovered_operation_count = backup_service.recover_interrupted_operations(
                session
            )
        if recovered_operation_count:
            logger.warning(
                "backup_interrupted_operations_recovered",
                extra={
                    "event": "backup.interrupted_operations_recovered",
                    "count": recovered_operation_count,
                },
            )
        if resolved_settings.bootstrap_admin:
            bootstrap_first_run(database.session_factory, resolved_settings)
        event_loop = asyncio.get_running_loop()
        lifecycle.set_background_controls(
            stop=lambda: _stop_background_writers(runtime, event_loop),
            resume=lambda: _resume_background_writers(runtime, event_loop),
        )
        event_bus.start()
        if resolved_settings.command_scheduler_enabled:
            runtime.command_scheduler.start()
        if mqtt_bridge:
            mqtt_bridge.start()
        if resolved_settings.lifecycle_scheduler_enabled:
            runtime.lifecycle_scheduler.start()
        logger.info("platform_started", extra={"event": "platform.started"})
        try:
            yield
        finally:
            if resolved_settings.lifecycle_scheduler_enabled:
                await runtime.lifecycle_scheduler.stop()
            if mqtt_bridge:
                mqtt_bridge.stop()
            if resolved_settings.command_scheduler_enabled:
                await runtime.command_scheduler.stop()
            await runtime.mobile_proxy_service.close()
            lifecycle.set_background_controls(stop=None, resume=None)
            database.dispose()
            logger.info("platform_stopped", extra={"event": "platform.stopped"})

    app = FastAPI(
        title="HighTac Platform API",
        version=__version__,
        docs_url="/api/docs",
        openapi_url="/api/openapi.json",
        lifespan=lifespan,
    )
    app.state.runtime = runtime
    app.include_router(api_router)
    _mount_optional_spa(app, resolved_settings)

    @app.middleware("http")
    async def lifecycle_request_gate(request: Request, call_next: Any) -> Any:
        if _is_lifecycle_operation_request(request):
            return await call_next(request)
        if not lifecycle.try_enter_work():
            return JSONResponse(
                status_code=503,
                content={
                    "error": {
                        "code": "SERVICE_RESTARTING",
                        "message": (
                            "The platform is quiescing for database restore."
                        ),
                        "details": [],
                        "request_id": request_id_context.get(),
                    }
                },
                headers={"Retry-After": "5"},
            )
        try:
            return await call_next(request)
        finally:
            lifecycle.leave_work()

    @app.middleware("http")
    async def request_context(request: Request, call_next: Any) -> Any:
        supplied = request.headers.get("X-Request-ID", "")
        try:
            request_id = str(UUID(supplied))
        except (ValueError, AttributeError):
            request_id = new_id()
        token = request_id_context.set(request_id)
        started = time.perf_counter()
        try:
            response = await call_next(request)
        finally:
            duration_ms = round((time.perf_counter() - started) * 1000, 2)
            logger.info(
                "http_request",
                extra={
                    "event": "http.request",
                    "method": request.method,
                    "path": _safe_request_log_path(request.url.path),
                    "duration_ms": duration_ms,
                },
            )
            request_id_context.reset(token)
        response.headers["X-Request-ID"] = request_id
        return response

    @app.exception_handler(DomainError)
    async def domain_error_handler(request: Request, exc: DomainError) -> JSONResponse:
        return JSONResponse(
            status_code=exc.status_code,
            content={
                "error": {
                    "code": exc.code,
                    "message": exc.message,
                    "details": exc.details,
                    "request_id": request_id_context.get(),
                }
            },
            headers=_error_headers(exc.status_code),
        )

    @app.exception_handler(RequestValidationError)
    async def request_validation_error_handler(
        _request: Request, exc: RequestValidationError
    ) -> JSONResponse:
        details = []
        for error in exc.errors():
            location = [str(part) for part in error.get("loc", ()) if part not in {"body"}]
            raw_code = str(error.get("type", "INVALID_VALUE")).upper().replace(".", "_")
            code = "".join(character if character.isalnum() or character == "_" else "_" for character in raw_code)
            details.append(
                {
                    "field": ".".join(location) or None,
                    "code": code[:64],
                    "message": str(error.get("msg", "Invalid value."))[:1024],
                }
            )
        return JSONResponse(
            status_code=422,
            content={
                "error": {
                    "code": "VALIDATION_ERROR",
                    "message": "One or more values are invalid.",
                    "details": details[:100],
                    "request_id": request_id_context.get(),
                }
            },
        )

    @app.exception_handler(ValueError)
    async def value_error_handler(
        _request: Request, exc: ValueError
    ) -> JSONResponse:
        return JSONResponse(
            status_code=422,
            content={
                "error": {
                    "code": "VALIDATION_ERROR",
                    "message": "One or more values are invalid.",
                    "details": [
                        {
                            "field": None,
                            "code": "INVALID_VALUE",
                            "message": str(exc)[:1024],
                        }
                    ],
                    "request_id": request_id_context.get(),
                }
            },
        )

    return app


def _stop_background_writers(
    runtime: Runtime,
    event_loop: asyncio.AbstractEventLoop,
) -> None:
    if runtime.mqtt_bridge:
        runtime.mqtt_bridge.stop()
    if runtime.settings.command_scheduler_enabled:
        future = asyncio.run_coroutine_threadsafe(
            runtime.command_scheduler.stop(),
            event_loop,
        )
        future.result(
            timeout=runtime.settings.restore_quiesce_timeout_seconds
        )


def _resume_background_writers(
    runtime: Runtime,
    event_loop: asyncio.AbstractEventLoop,
) -> None:
    if runtime.settings.command_scheduler_enabled:
        event_loop.call_soon_threadsafe(runtime.command_scheduler.start)
    if runtime.mqtt_bridge:
        runtime.mqtt_bridge.start()


def _is_lifecycle_operation_request(request: Request) -> bool:
    if request.method != "POST":
        return False
    path = request.url.path.rstrip("/")
    return path == "/api/v1/backups" or (
        path.startswith("/api/v1/backups/")
        and path.endswith("/restore")
    )


def _safe_request_log_path(path: str) -> str:
    media_prefix = "/api/v1/mobile/media/"
    if path.startswith(media_prefix):
        return f"{media_prefix}[REDACTED]"
    return path


def _error_headers(status_code: int) -> dict[str, str]:
    if status_code == 429:
        return {"Retry-After": "60"}
    if status_code == 503:
        return {"Retry-After": "5"}
    return {}


def _mount_optional_spa(app: FastAPI, settings: Settings) -> None:
    if not settings.serve_web_spa or settings.web_dist_dir is None:
        return
    root = settings.web_dist_dir.resolve()
    index = root / "index.html"
    if not index.is_file():
        logger.warning(
            "web_spa_not_mounted",
            extra={"event": "web.spa_missing", "web_dist_dir": str(root)},
        )
        return
    assets = root / "assets"
    if assets.is_dir():
        app.mount(
            "/assets",
            StaticFiles(directory=assets),
            name="web-assets",
        )

    @app.get("/{full_path:path}", include_in_schema=False)
    async def serve_spa(full_path: str) -> FileResponse:
        if full_path.startswith(("api/", "assets/")):
            raise HTTPException(status_code=404)
        requested = (root / full_path).resolve()
        if requested.is_file() and requested.is_relative_to(root):
            return FileResponse(requested)
        return FileResponse(index)


app = create_app()
