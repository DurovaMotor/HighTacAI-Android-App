from __future__ import annotations

import asyncio
import logging
import os
import threading
import time
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from typing import Protocol

from hightac_platform.domain.errors import ConflictError
from hightac_platform.services.commands import CommandScheduler
from hightac_platform.services.telemetry import TelemetryService


logger = logging.getLogger(__name__)


class RestartCoordinator(Protocol):
    def request_restart(self) -> bool:
        """Request a process restart and return whether termination is scheduled."""


class InProcessRestartCoordinator:
    """Records restart requests without terminating the current process."""

    def __init__(self) -> None:
        self.request_count = 0

    def request_restart(self) -> bool:
        self.request_count += 1
        return False


class ProcessRestartCoordinator:
    """Terminates with a failure code so the Windows service wrapper restarts us."""

    def __init__(
        self,
        *,
        delay_seconds: float = 1.0,
        exit_code: int = 75,
        exit_process: Callable[[int], object] | None = None,
    ) -> None:
        self.delay_seconds = delay_seconds
        self.exit_code = exit_code
        self._exit_process = exit_process or os._exit
        self._lock = threading.Lock()
        self._requested = False

    def request_restart(self) -> bool:
        with self._lock:
            if self._requested:
                return True
            self._requested = True
            thread = threading.Thread(
                target=self._terminate_after_delay,
                name="hightac-service-restart",
                daemon=True,
            )
            thread.start()
        return True

    def _terminate_after_delay(self) -> None:
        time.sleep(self.delay_seconds)
        logger.critical(
            "platform_restart_exit",
            extra={"event": "platform.restart_exit", "exit_code": self.exit_code},
        )
        self._exit_process(self.exit_code)


class RestoreQuiescence:
    def __init__(
        self,
        restart_coordinator: RestartCoordinator,
    ) -> None:
        self._restart_coordinator = restart_coordinator
        self.restart_will_terminate = False
        self._restart_requested = False

    def request_restart(self) -> None:
        if self._restart_requested:
            return
        self._restart_requested = True
        self.restart_will_terminate = bool(
            self._restart_coordinator.request_restart()
        )


class PlatformLifecycleCoordinator:
    def __init__(
        self,
        restart_coordinator: RestartCoordinator,
        *,
        quiesce_timeout_seconds: float = 15.0,
    ) -> None:
        self.restart_coordinator = restart_coordinator
        self.quiesce_timeout_seconds = quiesce_timeout_seconds
        self._operation_lock = threading.Lock()
        self._condition = threading.Condition()
        self._active_work = 0
        self._quiescing = False
        self._stop_background: Callable[[], None] | None = None
        self._resume_background: Callable[[], None] | None = None

    @property
    def quiescing(self) -> bool:
        with self._condition:
            return self._quiescing

    def set_background_controls(
        self,
        *,
        stop: Callable[[], None] | None,
        resume: Callable[[], None] | None,
    ) -> None:
        self._stop_background = stop
        self._resume_background = resume

    @contextmanager
    def serialized_operation(self) -> Iterator[None]:
        with self._condition:
            if self._quiescing:
                raise ConflictError(
                    "The platform is quiescing for database restore."
                )
        if not self._operation_lock.acquire(blocking=False):
            raise ConflictError(
                "Another backup, restore, or retention operation is already running."
            )
        try:
            yield
        finally:
            self._operation_lock.release()

    def try_enter_work(self) -> bool:
        with self._condition:
            if self._quiescing:
                return False
            self._active_work += 1
            return True

    def leave_work(self) -> None:
        with self._condition:
            if self._active_work <= 0:
                raise RuntimeError("Lifecycle work accounting became unbalanced.")
            self._active_work -= 1
            if self._active_work == 0:
                self._condition.notify_all()

    @contextmanager
    def restore_quiescence(self) -> Iterator[RestoreQuiescence]:
        self._begin_quiescence()
        stop_attempted = False
        lease = RestoreQuiescence(self.restart_coordinator)
        try:
            if self._stop_background is not None:
                stop_attempted = True
                self._stop_background()
            yield lease
        except BaseException:
            self._finish_quiescence()
            if stop_attempted:
                self._resume_background_safely()
            raise
        else:
            if not lease.restart_will_terminate:
                self._finish_quiescence()
                if stop_attempted:
                    self._resume_background_safely()

    def _begin_quiescence(self) -> None:
        deadline = time.monotonic() + self.quiesce_timeout_seconds
        with self._condition:
            if self._quiescing:
                raise ConflictError("The platform is already quiescing for restore.")
            self._quiescing = True
            while self._active_work:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    self._quiescing = False
                    self._condition.notify_all()
                    raise ConflictError(
                        "The platform could not quiesce active database work in time."
                    )
                self._condition.wait(timeout=remaining)

    def _finish_quiescence(self) -> None:
        with self._condition:
            self._quiescing = False
            self._condition.notify_all()

    def _resume_background_safely(self) -> None:
        if self._resume_background is None:
            return
        try:
            self._resume_background()
        except Exception:
            logger.exception(
                "platform_background_resume_failed",
                extra={"event": "platform.background_resume_failed"},
            )


class LifecycleCommandScheduler(CommandScheduler):
    def __init__(
        self,
        *args: object,
        lifecycle: PlatformLifecycleCoordinator,
        **kwargs: object,
    ) -> None:
        super().__init__(*args, **kwargs)
        self.lifecycle = lifecycle

    async def _run(self) -> None:
        while True:
            await asyncio.sleep(self.interval_seconds)
            if not self.lifecycle.try_enter_work():
                continue
            worker: asyncio.Task[None] | None = None
            try:
                worker = asyncio.create_task(
                    asyncio.to_thread(self._tick),
                    name="hightac-command-scheduler-tick",
                )
                await asyncio.shield(worker)
            except asyncio.CancelledError:
                if worker is not None:
                    try:
                        await worker
                    except Exception:
                        logger.exception(
                            "command_scheduler_tick_failed_during_stop",
                            extra={
                                "event": "command.scheduler_failed_during_stop"
                            },
                        )
                raise
            except Exception:
                logger.exception(
                    "command_scheduler_tick_failed",
                    extra={"event": "command.scheduler_failed"},
                )
            finally:
                self.lifecycle.leave_work()

    def _tick(self) -> None:
        with self.session_factory() as session:
            self.service.tick(session)


class LifecycleTelemetryService(TelemetryService):
    def __init__(
        self,
        *args: object,
        lifecycle: PlatformLifecycleCoordinator,
        **kwargs: object,
    ) -> None:
        super().__init__(*args, **kwargs)
        self.lifecycle = lifecycle

    def handle_message(self, topic: str, payload: bytes) -> bool:
        if not self.lifecycle.try_enter_work():
            logger.warning(
                "mqtt_payload_rejected_during_restore",
                extra={
                    "event": "mqtt.payload_rejected_during_restore",
                    "topic": topic[:255],
                },
            )
            return False
        try:
            return super().handle_message(topic, payload)
        finally:
            self.lifecycle.leave_work()
