from __future__ import annotations

import hashlib
import os
import re
import sqlite3
from contextlib import closing
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path

from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from hightac_platform.auth.context import Actor
from hightac_platform.config import Settings
from hightac_platform.db.migrations import inspect_migration_state
from hightac_platform.db.models import BackupRecord, OperationLog
from hightac_platform.db.session import Database
from hightac_platform.domain.enums import BackupStatus
from hightac_platform.domain.errors import (
    ConflictError,
    NotFoundError,
    ValidationError,
)
from hightac_platform.services.audit import append_operation_log
from hightac_platform.services.lifecycle import (
    InProcessRestartCoordinator,
    PlatformLifecycleCoordinator,
)
from hightac_platform.utils import new_id, utc_ms


BACKUP_FILENAME_RE = re.compile(
    r"^hightac-(?:manual|automatic|pre-restore)-\d{8}T\d{6}Z-schema-[A-Za-z0-9_.-]+-[a-f0-9]{8}\.db$"
)
INTERRUPTED_OPERATION_FAILURE_REASON = (
    "Operation was interrupted before the current platform process started."
)
INTERRUPTED_OPERATION_EVENT = "backup.interrupted"


@dataclass(frozen=True, slots=True)
class RestoreResult:
    operation_id: str
    backup_id: str
    status: str
    pre_restore_backup_id: str
    requested_at_ms: int


@dataclass(frozen=True, slots=True)
class RetentionResult:
    backup_records_removed: int
    operation_logs_removed: int


@dataclass(frozen=True, slots=True)
class _BackupMetadata:
    id: str
    filename: str
    file_size: int
    sha256: str
    schema_version: str | None
    kind: str
    created_at_ms: int
    completed_at_ms: int

    @classmethod
    def from_record(cls, record: BackupRecord) -> _BackupMetadata:
        if (
            record.status != BackupStatus.SUCCEEDED.value
            or record.file_size is None
            or record.sha256 is None
            or record.completed_at_ms is None
        ):
            raise ConflictError("Backup metadata is not complete.")
        return cls(
            id=record.id,
            filename=record.filename,
            file_size=record.file_size,
            sha256=record.sha256,
            schema_version=record.schema_version,
            kind=record.kind,
            created_at_ms=record.created_at_ms,
            completed_at_ms=record.completed_at_ms,
        )


class BackupService:
    def __init__(
        self,
        settings: Settings,
        database: Database,
        lifecycle: PlatformLifecycleCoordinator | None = None,
    ) -> None:
        self.settings = settings
        self.database = database
        self.lifecycle = lifecycle or PlatformLifecycleCoordinator(
            InProcessRestartCoordinator()
        )

    def create(
        self,
        session: Session,
        actor: Actor | None,
        kind: str = "MANUAL",
    ) -> BackupRecord:
        with self.lifecycle.serialized_operation():
            return self._create_locked(session, actor, kind)

    def recover_interrupted_operations(self, session: Session) -> int:
        """Fail RUNNING records left behind by an interrupted process."""
        with self.lifecycle.serialized_operation():
            records = list(
                session.scalars(
                    select(BackupRecord)
                    .where(BackupRecord.status == BackupStatus.RUNNING.value)
                    .order_by(BackupRecord.created_at_ms, BackupRecord.id)
                )
            )
            completed_at_ms = utc_ms()
            for record in records:
                record.file_size = None
                record.sha256 = None
                record.status = BackupStatus.FAILED.value
                record.error_message = INTERRUPTED_OPERATION_FAILURE_REASON
                record.completed_at_ms = completed_at_ms
                append_operation_log(
                    session,
                    event_type=INTERRUPTED_OPERATION_EVENT,
                    actor=None,
                    result_summary={
                        "backup_id": record.id,
                        "kind": record.kind,
                        "previous_status": BackupStatus.RUNNING.value,
                        "status": BackupStatus.FAILED.value,
                    },
                    failure_reason=INTERRUPTED_OPERATION_FAILURE_REASON,
                )
            if records:
                session.commit()
            return len(records)

    def _create_locked(
        self,
        session: Session,
        actor: Actor | None,
        kind: str,
    ) -> BackupRecord:
        self._require_sqlite()
        migration = inspect_migration_state(self.database.engine, self.settings)
        if not migration.at_head or not migration.current:
            raise ConflictError(
                "Database schema is not at the migration head "
                f"(current={migration.current or 'none'}, "
                f"head={migration.head or 'none'})."
            )

        normalized_kind = kind.strip().upper()
        if normalized_kind not in {"MANUAL", "AUTOMATIC", "PRE_RESTORE"}:
            raise ValidationError("Backup kind is invalid.")
        kind_slug = normalized_kind.lower().replace("_", "-")
        now = datetime.now(UTC)
        suffix = new_id().replace("-", "")[:8]
        filename = (
            f"hightac-{kind_slug}-{now.strftime('%Y%m%dT%H%M%SZ')}-"
            f"schema-{migration.current}-{suffix}.db"
        )
        if not BACKUP_FILENAME_RE.fullmatch(filename):
            raise ValidationError("Backup kind is invalid.")

        record = BackupRecord(
            filename=filename,
            schema_version=migration.current,
            kind=normalized_kind,
            status=BackupStatus.RUNNING.value,
        )
        session.add(record)
        session.commit()
        destination = self._path_for(record)
        temporary = destination.with_suffix(".tmp")
        published = False
        try:
            source_path = self._database_path()
            _copy_sqlite_database(source_path, temporary)
            _validate_sqlite(temporary, expected_schema=migration.current)
            file_size = temporary.stat().st_size
            digest = _sha256(temporary)

            record.file_size = file_size
            record.sha256 = digest
            record.status = BackupStatus.SUCCEEDED.value
            record.error_message = None
            record.completed_at_ms = utc_ms()
            append_operation_log(
                session,
                event_type="backup.created",
                actor=actor,
                result_summary={"backup_id": record.id, "kind": record.kind},
            )
            session.commit()

            temporary.replace(destination)
            published = True
            return record
        except Exception as exc:
            temporary.unlink(missing_ok=True)
            if published:
                destination.unlink(missing_ok=True)
            self._mark_failed(session, record.id, exc)
            raise

    def restore(
        self,
        session: Session,
        actor: Actor,
        backup_id: str,
        confirmation: str,
        expected_sha256: str,
    ) -> RestoreResult:
        if confirmation != "RESTORE BACKUP":
            raise ValidationError(
                "Restore confirmation must be exactly RESTORE BACKUP."
            )
        self._require_sqlite()
        with self.lifecycle.serialized_operation():
            return self._restore_locked(
                session,
                actor,
                backup_id,
                expected_sha256,
            )

    def _restore_locked(
        self,
        session: Session,
        actor: Actor,
        backup_id: str,
        expected_sha256: str,
    ) -> RestoreResult:
        record = session.get(BackupRecord, backup_id)
        if record is None:
            raise NotFoundError("Backup record was not found.")
        target_metadata = _BackupMetadata.from_record(record)
        if expected_sha256 != target_metadata.sha256:
            raise ConflictError(
                "The expected backup checksum does not match the backup record."
            )

        source_path = self._path_for(record)
        if (
            not source_path.is_file()
            or source_path.stat().st_size != target_metadata.file_size
            or _sha256(source_path) != target_metadata.sha256
        ):
            raise ConflictError("Backup file is missing or failed its checksum.")
        _validate_sqlite(
            source_path,
            expected_schema=target_metadata.schema_version,
        )

        requested_at_ms = utc_ms()
        destination_path = self._database_path()
        restore_token = new_id().replace("-", "")
        restore_stage = destination_path.with_name(
            f".{destination_path.name}.{restore_token}.restore"
        )
        rollback_stage = destination_path.with_name(
            f".{destination_path.name}.{restore_token}.rollback"
        )
        pre_restore_metadata: _BackupMetadata | None = None
        replaced = False
        try:
            _copy_sqlite_database(source_path, restore_stage)
            _validate_sqlite(
                restore_stage,
                expected_schema=target_metadata.schema_version,
            )
            with self.lifecycle.restore_quiescence() as quiescence:
                pre_restore = self._create_locked(
                    session,
                    actor,
                    kind="PRE_RESTORE",
                )
                pre_restore_metadata = _BackupMetadata.from_record(pre_restore)

                _copy_sqlite_database(destination_path, rollback_stage)
                _validate_sqlite(
                    rollback_stage,
                    expected_schema=pre_restore_metadata.schema_version,
                )

                session.close()
                self.database.dispose()
                try:
                    _replace_database_file(restore_stage, destination_path)
                    replaced = True
                    self._repair_restored_database(
                        target_metadata,
                        pre_restore_metadata,
                        actor,
                    )
                    _validate_sqlite(
                        destination_path,
                        expected_schema=target_metadata.schema_version,
                    )
                except Exception:
                    if replaced:
                        self.database.dispose()
                        _replace_database_file(
                            rollback_stage,
                            destination_path,
                        )
                        _validate_sqlite(
                            destination_path,
                            expected_schema=pre_restore_metadata.schema_version,
                        )
                    raise

                quiescence.request_restart()
        finally:
            restore_stage.unlink(missing_ok=True)
            rollback_stage.unlink(missing_ok=True)

        assert pre_restore_metadata is not None
        return RestoreResult(
            operation_id=new_id(),
            backup_id=target_metadata.id,
            status="RESTARTING",
            pre_restore_backup_id=pre_restore_metadata.id,
            requested_at_ms=requested_at_ms,
        )

    def cleanup_retention(
        self,
        session: Session,
        *,
        now_ms: int | None = None,
    ) -> int:
        with self.lifecycle.serialized_operation():
            removed = self._cleanup_backup_retention_locked(
                session,
                now_ms=now_ms,
            )
            session.commit()
            return removed

    def cleanup_lifecycle_retention(
        self,
        session: Session,
        *,
        now_ms: int | None = None,
    ) -> RetentionResult:
        with self.lifecycle.serialized_operation():
            backup_records_removed = self._cleanup_backup_retention_locked(
                session,
                now_ms=now_ms,
            )
            operation_logs_removed = self._cleanup_operation_logs_locked(
                session,
                now_ms=now_ms,
            )
            session.commit()
            return RetentionResult(
                backup_records_removed=backup_records_removed,
                operation_logs_removed=operation_logs_removed,
            )

    def _cleanup_backup_retention_locked(
        self,
        session: Session,
        *,
        now_ms: int | None,
    ) -> int:
        effective_now_ms = now_ms if now_ms is not None else utc_ms()
        cutoff = effective_now_ms - (
            self.settings.backup_retention_days * 86_400_000
        )
        records = list(
            session.scalars(
                select(BackupRecord).where(
                    BackupRecord.created_at_ms < cutoff
                )
            )
        )
        removed = 0
        for record in records:
            try:
                path = self._path_for(record)
            except ValidationError:
                continue
            path.unlink(missing_ok=True)
            session.delete(record)
            removed += 1

        known_filenames = set(session.scalars(select(BackupRecord.filename)))
        root = self.settings.backups_dir.resolve()
        root.mkdir(parents=True, exist_ok=True)
        for path in root.glob("hightac-*.db"):
            if (
                BACKUP_FILENAME_RE.fullmatch(path.name)
                and path.name not in known_filenames
            ):
                path.unlink(missing_ok=True)
        return removed

    def _cleanup_operation_logs_locked(
        self,
        session: Session,
        *,
        now_ms: int | None,
    ) -> int:
        effective_now_ms = now_ms if now_ms is not None else utc_ms()
        cutoff = effective_now_ms - (
            self.settings.operation_log_retention_days * 86_400_000
        )
        result = session.execute(
            delete(OperationLog).where(OperationLog.created_at_ms < cutoff)
        )
        return max(result.rowcount or 0, 0)

    def _repair_restored_database(
        self,
        target: _BackupMetadata,
        pre_restore: _BackupMetadata,
        actor: Actor,
    ) -> None:
        with self.database.session_factory() as repair_session:
            _upsert_successful_backup(repair_session, target)
            _upsert_successful_backup(repair_session, pre_restore)
            append_operation_log(
                repair_session,
                event_type="backup.restored",
                actor=actor,
                result_summary={
                    "backup_id": target.id,
                    "pre_restore_backup_id": pre_restore.id,
                    "restart_required": True,
                },
            )
            repair_session.commit()

    def _mark_failed(
        self,
        session: Session,
        record_id: str,
        exc: Exception,
    ) -> None:
        try:
            session.rollback()
            record = session.get(BackupRecord, record_id)
            if record is None:
                return
            record.file_size = None
            record.sha256 = None
            record.status = BackupStatus.FAILED.value
            record.error_message = str(exc)[:500]
            record.completed_at_ms = utc_ms()
            session.commit()
        except Exception:
            session.rollback()

    def _path_for(self, record: BackupRecord) -> Path:
        if not BACKUP_FILENAME_RE.fullmatch(record.filename):
            raise ValidationError("Backup filename is not recognized.")
        root = self.settings.backups_dir.resolve()
        path = (root / record.filename).resolve()
        if path.parent != root:
            raise ValidationError("Backup path escaped the configured directory.")
        root.mkdir(parents=True, exist_ok=True)
        return path

    def _database_path(self) -> Path:
        database_name = self.database.engine.url.database
        if not database_name:
            raise ValidationError("The SQLite database path is not configured.")
        return Path(str(database_name)).resolve()

    def _require_sqlite(self) -> None:
        if self.database.engine.url.get_backend_name() != "sqlite":
            raise ValidationError(
                "Backup service currently supports SQLite only."
            )


def _upsert_successful_backup(
    session: Session,
    metadata: _BackupMetadata,
) -> None:
    record = session.get(BackupRecord, metadata.id)
    if record is None:
        record = BackupRecord(id=metadata.id, filename=metadata.filename)
        session.add(record)
    record.filename = metadata.filename
    record.file_size = metadata.file_size
    record.sha256 = metadata.sha256
    record.schema_version = metadata.schema_version
    record.kind = metadata.kind
    record.status = BackupStatus.SUCCEEDED.value
    record.error_message = None
    record.created_at_ms = metadata.created_at_ms
    record.completed_at_ms = metadata.completed_at_ms


def _copy_sqlite_database(source_path: Path, destination_path: Path) -> None:
    destination_path.parent.mkdir(parents=True, exist_ok=True)
    destination_path.unlink(missing_ok=True)
    with closing(
        sqlite3.connect(_read_only_sqlite_uri(source_path), uri=True)
    ) as source, closing(sqlite3.connect(destination_path)) as target:
        source.backup(target)
    with destination_path.open("r+b") as handle:
        handle.flush()
        os.fsync(handle.fileno())


def _replace_database_file(staged_path: Path, destination_path: Path) -> None:
    if not staged_path.is_file():
        raise OSError("The staged restore database is missing.")
    for suffix in ("-wal", "-shm", "-journal"):
        Path(f"{destination_path}{suffix}").unlink(missing_ok=True)
    staged_path.replace(destination_path)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _validate_sqlite(path: Path, expected_schema: str | None) -> None:
    with closing(
        sqlite3.connect(_read_only_sqlite_uri(path), uri=True)
    ) as connection:
        result = connection.execute("PRAGMA integrity_check").fetchone()
        if result is None or result[0] != "ok":
            raise ConflictError("SQLite integrity check failed.")
        foreign_key_violation = connection.execute(
            "PRAGMA foreign_key_check"
        ).fetchone()
        if foreign_key_violation is not None:
            raise ConflictError("SQLite foreign key check failed.")
        version = connection.execute(
            "SELECT version_num FROM alembic_version"
        ).fetchone()
        if expected_schema and (
            version is None or version[0] != expected_schema
        ):
            raise ConflictError(
                "Backup schema version does not match its record."
            )


def _read_only_sqlite_uri(path: Path) -> str:
    return f"file:{path.resolve().as_posix()}?mode=ro"
