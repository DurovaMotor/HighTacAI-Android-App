from __future__ import annotations

import hashlib
import json
import sqlite3
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select

from conftest import FakePublisher
from hightac_platform.config import Settings
from hightac_platform.db.migrations import upgrade_database
from hightac_platform.db.models import BackupRecord, OperationLog, Product
from hightac_platform.db.session import Database
from hightac_platform.domain.enums import BackupStatus
from hightac_platform.domain.errors import ConflictError
from hightac_platform.main import create_app
from hightac_platform.services import backups as backups_module


def _digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _startup_recovery_settings(tmp_path: Path) -> Settings:
    return Settings(
        environment="test",
        data_dir=tmp_path,
        database_url=f"sqlite:///{(tmp_path / 'startup-recovery.db').as_posix()}",
        bootstrap_admin=False,
        mqtt_enabled=False,
        mqtt_required_for_ready=False,
        command_scheduler_enabled=False,
        lifecycle_scheduler_enabled=False,
        log_to_file=False,
    )


def _seed_backup_records(
    settings: Settings,
    *records: BackupRecord,
) -> tuple[str, ...]:
    upgrade_database(settings)
    database = Database(settings)
    try:
        with database.session_factory() as session:
            session.add_all(records)
            session.commit()
            return tuple(record.id for record in records)
    finally:
        database.dispose()


def test_startup_recovers_interrupted_backup_and_restore_records(
    tmp_path: Path,
) -> None:
    settings = _startup_recovery_settings(tmp_path)
    stale_records = (
        BackupRecord(
            filename=("hightac-manual-20260716T010203Z-schema-0003-deadbeef.db"),
            kind="MANUAL",
            status=BackupStatus.RUNNING.value,
            created_at_ms=1,
        ),
        BackupRecord(
            filename=("hightac-pre-restore-20260716T020304Z-schema-0003-acde1234.db"),
            kind="PRE_RESTORE",
            status=BackupStatus.RUNNING.value,
            created_at_ms=2,
        ),
    )
    stale_ids = _seed_backup_records(settings, *stale_records)

    app = create_app(settings, publisher=FakePublisher())
    with TestClient(app):
        with app.state.runtime.database.session_factory() as session:
            recovered = [
                session.get(BackupRecord, record_id) for record_id in stale_ids
            ]
            logs = list(
                session.scalars(
                    select(OperationLog)
                    .where(
                        OperationLog.event_type
                        == backups_module.INTERRUPTED_OPERATION_EVENT
                    )
                    .order_by(OperationLog.created_at_ms, OperationLog.id)
                )
            )

    assert all(record is not None for record in recovered)
    for record in recovered:
        assert record is not None
        assert record.status == BackupStatus.FAILED.value
        assert (
            record.error_message == backups_module.INTERRUPTED_OPERATION_FAILURE_REASON
        )
        assert record.completed_at_ms is not None
        assert record.file_size is None
        assert record.sha256 is None
        assert record.filename not in record.error_message
        assert str(settings.data_dir) not in record.error_message

    assert len(logs) == len(stale_records)
    assert {json.loads(log.result_summary or "")["backup_id"] for log in logs} == set(
        stale_ids
    )
    for log in logs:
        assert log.actor_type == "SYSTEM"
        assert log.failure_reason == backups_module.INTERRUPTED_OPERATION_FAILURE_REASON
        assert str(settings.data_dir) not in (log.failure_reason or "")


def test_startup_recovery_leaves_completed_backup_and_database_unchanged(
    tmp_path: Path,
) -> None:
    settings = _startup_recovery_settings(tmp_path)
    settings.ensure_runtime_directories()
    backup_path = settings.backups_dir / (
        "hightac-manual-20260715T010203Z-schema-0003-feedface.db"
    )
    backup_contents = b"completed backup remains untouched"
    backup_path.write_bytes(backup_contents)
    completed = BackupRecord(
        filename=backup_path.name,
        file_size=len(backup_contents),
        sha256=_digest(backup_path),
        schema_version="0003",
        kind="MANUAL",
        status=BackupStatus.SUCCEEDED.value,
        created_at_ms=10,
        completed_at_ms=20,
    )
    (completed_id,) = _seed_backup_records(settings, completed)
    database = Database(settings)
    try:
        with database.session_factory() as session:
            session.add(
                Product(
                    product_code="RECOVERY-SENTINEL",
                    product_name="Must survive startup recovery",
                    source="WEB",
                )
            )
            session.commit()
    finally:
        database.dispose()

    expected_metadata = (
        completed.filename,
        completed.file_size,
        completed.sha256,
        completed.schema_version,
        completed.kind,
        completed.status,
        completed.error_message,
        completed.created_at_ms,
        completed.completed_at_ms,
    )
    app = create_app(settings, publisher=FakePublisher())
    with TestClient(app):
        with app.state.runtime.database.session_factory() as session:
            observed = session.get(BackupRecord, completed_id)
            sentinel = session.scalar(
                select(Product).where(Product.product_code == "RECOVERY-SENTINEL")
            )
            recovery_logs = list(
                session.scalars(
                    select(OperationLog).where(
                        OperationLog.event_type
                        == backups_module.INTERRUPTED_OPERATION_EVENT
                    )
                )
            )

    assert observed is not None
    assert (
        observed.filename,
        observed.file_size,
        observed.sha256,
        observed.schema_version,
        observed.kind,
        observed.status,
        observed.error_message,
        observed.created_at_ms,
        observed.completed_at_ms,
    ) == expected_metadata
    assert backup_path.read_bytes() == backup_contents
    assert sentinel is not None
    assert recovery_logs == []


def test_startup_recovery_is_idempotent_across_repeated_starts(
    tmp_path: Path,
) -> None:
    settings = _startup_recovery_settings(tmp_path)
    (stale_id,) = _seed_backup_records(
        settings,
        BackupRecord(
            filename=("hightac-automatic-20260716T030405Z-schema-0003-1234abcd.db"),
            kind="AUTOMATIC",
            status=BackupStatus.RUNNING.value,
            created_at_ms=1,
        ),
    )

    first_app = create_app(settings, publisher=FakePublisher())
    with TestClient(first_app):
        with first_app.state.runtime.database.session_factory() as session:
            first_record = session.get(BackupRecord, stale_id)
            assert first_record is not None
            first_completed_at_ms = first_record.completed_at_ms

    second_app = create_app(settings, publisher=FakePublisher())
    with TestClient(second_app):
        with second_app.state.runtime.database.session_factory() as session:
            second_record = session.get(BackupRecord, stale_id)
            audit_logs = list(
                session.scalars(
                    select(OperationLog).where(
                        OperationLog.event_type
                        == backups_module.INTERRUPTED_OPERATION_EVENT
                    )
                )
            )

    assert second_record is not None
    assert second_record.status == BackupStatus.FAILED.value
    assert second_record.completed_at_ms == first_completed_at_ms
    assert len(audit_logs) == 1


def test_success_metadata_is_committed_before_backup_is_published(
    harness,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    auth = harness.login()
    original_replace = Path.replace
    observed: dict[str, object] = {}

    def replace_after_metadata_commit(
        temporary: Path,
        destination: Path,
    ) -> Path:
        if (
            temporary.suffix == ".tmp"
            and destination.parent == harness.settings.backups_dir
        ):
            with harness.runtime.database.session_factory() as session:
                record = session.scalar(
                    select(BackupRecord).where(
                        BackupRecord.filename == destination.name
                    )
                )
                assert record is not None
                observed.update(
                    status=record.status,
                    sha256=record.sha256,
                    file_size=record.file_size,
                )
            assert observed["sha256"] == _digest(temporary)
            assert observed["file_size"] == temporary.stat().st_size
        return original_replace(temporary, destination)

    monkeypatch.setattr(Path, "replace", replace_after_metadata_commit)
    response = harness.client.post(
        "/api/v1/backups",
        headers=harness.mutation_headers(auth),
    )

    assert response.status_code == 202, response.text
    assert observed["status"] == BackupStatus.SUCCEEDED.value
    backup_path = harness.settings.backups_dir / response.json()["filename"]
    assert response.json()["sha256"] == _digest(backup_path)
    assert response.json()["size_bytes"] == backup_path.stat().st_size


def test_restore_repairs_backup_metadata_and_preserves_sqlite_integrity(
    harness,
) -> None:
    auth = harness.login()
    created = harness.client.post(
        "/api/v1/backups",
        headers=harness.mutation_headers(auth),
    )
    assert created.status_code == 202, created.text
    target = created.json()

    with harness.runtime.database.session_factory() as session:
        session.add(
            Product(
                product_code="POST-BACKUP",
                product_name="Should be rolled back",
                source="WEB",
            )
        )
        session.commit()

    restored = harness.client.post(
        f"/api/v1/backups/{target['id']}/restore",
        headers=harness.mutation_headers(auth),
        json={
            "confirmation": "RESTORE BACKUP",
            "expected_sha256": target["sha256"],
        },
    )
    assert restored.status_code == 202, restored.text
    assert restored.json()["status"] == "RESTARTING"

    pre_restore_id = restored.json()["pre_restore_backup_id"]
    with harness.runtime.database.session_factory() as session:
        assert session.scalar(
            select(Product).where(Product.product_code == "POST-BACKUP")
        ) is None
        target_record = session.get(BackupRecord, target["id"])
        pre_restore_record = session.get(BackupRecord, pre_restore_id)
        assert target_record is not None
        assert pre_restore_record is not None
        for record in (target_record, pre_restore_record):
            assert record.status == BackupStatus.SUCCEEDED.value
            assert record.sha256 is not None
            assert record.file_size is not None
            path = harness.settings.backups_dir / record.filename
            assert path.stat().st_size == record.file_size
            assert _digest(path) == record.sha256

    database_path = Path(
        str(harness.runtime.database.engine.url.database)
    ).resolve()
    with sqlite3.connect(database_path) as connection:
        assert connection.execute("PRAGMA integrity_check").fetchone() == (
            "ok",
        )
        assert connection.execute("PRAGMA foreign_key_check").fetchall() == []
    with harness.runtime.database.engine.connect() as connection:
        assert connection.exec_driver_sql("PRAGMA foreign_keys").scalar() == 1
    assert harness.runtime.restart_coordinator.request_count == 1


def test_restore_rejects_while_another_backup_is_running(
    harness,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    auth = harness.login()
    target_response = harness.client.post(
        "/api/v1/backups",
        headers=harness.mutation_headers(auth),
    )
    assert target_response.status_code == 202
    target = target_response.json()

    cookie = harness.client.cookies.get(
        harness.settings.session_cookie_name
    )
    assert cookie is not None
    with harness.runtime.database.session_factory() as session:
        actor = harness.runtime.auth_service.authenticate_session(
            session,
            cookie,
        )

    copy_started = threading.Event()
    allow_copy = threading.Event()
    original_copy = backups_module._copy_sqlite_database

    def blocking_copy(source: Path, destination: Path) -> None:
        if destination.suffix == ".tmp":
            copy_started.set()
            assert allow_copy.wait(timeout=5)
        original_copy(source, destination)

    monkeypatch.setattr(
        backups_module,
        "_copy_sqlite_database",
        blocking_copy,
    )

    def create_backup() -> str:
        with harness.runtime.database.session_factory() as session:
            return harness.runtime.backup_service.create(
                session,
                actor,
            ).id

    with ThreadPoolExecutor(max_workers=1) as executor:
        future = executor.submit(create_backup)
        assert copy_started.wait(timeout=5)
        with harness.runtime.database.session_factory() as session:
            with pytest.raises(ConflictError, match="already running"):
                harness.runtime.backup_service.restore(
                    session,
                    actor,
                    target["id"],
                    "RESTORE BACKUP",
                    target["sha256"],
                )
        allow_copy.set()
        assert future.result(timeout=10)


def test_recovery_does_not_reclassify_active_in_process_backup(
    harness,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    harness.login()
    cookie = harness.client.cookies.get(harness.settings.session_cookie_name)
    assert cookie is not None
    with harness.runtime.database.session_factory() as session:
        actor = harness.runtime.auth_service.authenticate_session(session, cookie)

    copy_started = threading.Event()
    allow_copy = threading.Event()
    original_copy = backups_module._copy_sqlite_database

    def blocking_copy(source: Path, destination: Path) -> None:
        if destination.suffix == ".tmp":
            copy_started.set()
            assert allow_copy.wait(timeout=5)
        original_copy(source, destination)

    monkeypatch.setattr(backups_module, "_copy_sqlite_database", blocking_copy)

    def create_backup() -> str:
        with harness.runtime.database.session_factory() as session:
            return harness.runtime.backup_service.create(session, actor).id

    with ThreadPoolExecutor(max_workers=1) as executor:
        future = executor.submit(create_backup)
        assert copy_started.wait(timeout=5)
        try:
            with harness.runtime.database.session_factory() as session:
                running = session.scalar(
                    select(BackupRecord).where(
                        BackupRecord.status == BackupStatus.RUNNING.value
                    )
                )
                assert running is not None
                running_id = running.id
                with pytest.raises(ConflictError, match="already running"):
                    harness.runtime.backup_service.recover_interrupted_operations(
                        session
                    )
                session.refresh(running)
                assert running.status == BackupStatus.RUNNING.value
        finally:
            allow_copy.set()

        assert future.result(timeout=10) == running_id

    with harness.runtime.database.session_factory() as session:
        completed = session.get(BackupRecord, running_id)
        assert completed is not None
        assert completed.status == BackupStatus.SUCCEEDED.value
        assert completed.error_message is None
