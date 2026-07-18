from __future__ import annotations

from datetime import datetime, timedelta, timezone

from sqlalchemy import select

from hightac_platform.db.models import BackupRecord, OperationLog
from hightac_platform.services.lifecycle_scheduler import next_daily_run
from hightac_platform.utils import utc_ms


def test_next_daily_run_uses_local_0200() -> None:
    local_timezone = timezone(timedelta(hours=8))

    before = datetime(2026, 7, 16, 1, 59, 30, tzinfo=local_timezone)
    assert next_daily_run(before) == datetime(
        2026,
        7,
        16,
        2,
        0,
        tzinfo=local_timezone,
    )

    after = datetime(2026, 7, 16, 2, 0, tzinfo=local_timezone)
    assert next_daily_run(after) == datetime(
        2026,
        7,
        17,
        2,
        0,
        tzinfo=local_timezone,
    )


def test_lifecycle_cycle_creates_backup_and_cleans_both_retention_sets(
    harness,
) -> None:
    auth = harness.login()
    old_backup_response = harness.client.post(
        "/api/v1/backups",
        headers=harness.mutation_headers(auth),
    )
    assert old_backup_response.status_code == 202
    old_backup = old_backup_response.json()
    old_backup_path = (
        harness.settings.backups_dir / old_backup["filename"]
    )

    now = utc_ms()
    two_days_ago = now - 2 * 86_400_000
    harness.settings.backup_retention_days = 1
    harness.settings.operation_log_retention_days = 1
    with harness.runtime.database.session_factory() as session:
        record = session.get(BackupRecord, old_backup["id"])
        assert record is not None
        record.created_at_ms = two_days_ago
        session.add(
            OperationLog(
                event_type="test.old",
                actor_type="SYSTEM",
                actor_name="system",
                created_at_ms=two_days_ago,
            )
        )
        session.add(
            OperationLog(
                event_type="test.recent",
                actor_type="SYSTEM",
                actor_name="system",
                created_at_ms=now,
            )
        )
        session.commit()

    result = harness.runtime.lifecycle_scheduler.run_once(now_ms=now)

    assert result.backup_id is not None
    assert result.retention.backup_records_removed == 1
    assert result.retention.operation_logs_removed == 1
    assert not old_backup_path.exists()
    with harness.runtime.database.session_factory() as session:
        assert session.get(BackupRecord, old_backup["id"]) is None
        automatic = session.get(BackupRecord, result.backup_id)
        assert automatic is not None
        assert automatic.kind == "AUTOMATIC"
        event_types = set(
            session.scalars(
                select(OperationLog.event_type).where(
                    OperationLog.event_type.in_(
                        ["test.old", "test.recent"]
                    )
                )
            )
        )
        assert event_types == {"test.recent"}
