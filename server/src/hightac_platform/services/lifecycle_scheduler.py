from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass
from datetime import datetime, time, timedelta
from typing import Callable

from sqlalchemy.orm import Session, sessionmaker

from hightac_platform.domain.errors import ConflictError
from hightac_platform.services.backups import BackupService, RetentionResult
from hightac_platform.services.lifecycle import PlatformLifecycleCoordinator


logger = logging.getLogger(__name__)
DAILY_BACKUP_LOCAL_TIME = time(hour=2)


@dataclass(frozen=True, slots=True)
class LifecycleRunResult:
    backup_id: str | None
    retention: RetentionResult


def next_daily_run(
    now: datetime,
    scheduled_time: time = DAILY_BACKUP_LOCAL_TIME,
) -> datetime:
    if now.tzinfo is None:
        now = now.astimezone()
    candidate = now.replace(
        hour=scheduled_time.hour,
        minute=scheduled_time.minute,
        second=scheduled_time.second,
        microsecond=0,
    )
    if candidate <= now:
        candidate += timedelta(days=1)
    return candidate


def seconds_until_next_run(
    now: datetime,
    scheduled_time: time = DAILY_BACKUP_LOCAL_TIME,
) -> float:
    next_run = next_daily_run(now, scheduled_time)
    return max(0.0, next_run.timestamp() - now.timestamp())


class LifecycleScheduler:
    def __init__(
        self,
        session_factory: sessionmaker[Session],
        backup_service: BackupService,
        lifecycle: PlatformLifecycleCoordinator,
        *,
        scheduled_time: time = DAILY_BACKUP_LOCAL_TIME,
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        self.session_factory = session_factory
        self.backup_service = backup_service
        self.lifecycle = lifecycle
        self.scheduled_time = scheduled_time
        self.clock = clock or (lambda: datetime.now().astimezone())
        self._task: asyncio.Task[None] | None = None

    def start(self) -> None:
        if self._task is None:
            self._task = asyncio.create_task(
                self._run(),
                name="hightac-lifecycle-scheduler",
            )

    async def stop(self) -> None:
        if self._task is None:
            return
        self._task.cancel()
        try:
            await self._task
        except asyncio.CancelledError:
            pass
        self._task = None

    def run_once(self, *, now_ms: int | None = None) -> LifecycleRunResult:
        if not self.lifecycle.try_enter_work():
            raise ConflictError("Lifecycle maintenance is paused for database restore.")
        backup_id: str | None = None
        backup_error: Exception | None = None
        try:
            try:
                with self.session_factory() as session:
                    backup_id = self.backup_service.create(
                        session,
                        actor=None,
                        kind="AUTOMATIC",
                    ).id
            except Exception as exc:
                backup_error = exc

            with self.session_factory() as session:
                retention = self.backup_service.cleanup_lifecycle_retention(
                    session,
                    now_ms=now_ms,
                )
        finally:
            self.lifecycle.leave_work()
        if backup_error is not None:
            raise backup_error
        return LifecycleRunResult(backup_id=backup_id, retention=retention)

    async def _run(self) -> None:
        while True:
            await asyncio.sleep(
                seconds_until_next_run(self.clock(), self.scheduled_time)
            )
            worker = asyncio.create_task(
                asyncio.to_thread(self.run_once),
                name="hightac-lifecycle-cycle",
            )
            try:
                await asyncio.shield(worker)
            except asyncio.CancelledError:
                try:
                    await worker
                except Exception:
                    logger.exception(
                        "lifecycle_scheduler_cycle_failed_during_stop",
                        extra={
                            "event": "lifecycle.scheduler_failed_during_stop"
                        },
                    )
                raise
            except Exception:
                logger.exception(
                    "lifecycle_scheduler_cycle_failed",
                    extra={"event": "lifecycle.scheduler_failed"},
                )
