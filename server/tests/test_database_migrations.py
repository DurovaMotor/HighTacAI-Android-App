from __future__ import annotations

from pathlib import Path

from alembic import command
from sqlalchemy import text

from hightac_platform.config import Settings
from hightac_platform.db.migrations import (
    alembic_config,
    inspect_migration_state,
    upgrade_database,
)
from hightac_platform.db.session import Database


def test_alembic_round_trip_matches_models_and_database_invariants(
    tmp_path: Path,
) -> None:
    settings = Settings(
        environment="test",
        data_dir=tmp_path,
        bootstrap_admin=False,
        log_to_file=False,
        _env_file=None,
    )
    config = alembic_config(settings)

    upgrade_database(settings)
    command.check(config)
    command.downgrade(config, "base")
    command.upgrade(config, "head")
    command.check(config)

    database = Database(settings)
    try:
        state = inspect_migration_state(database.engine, settings)
        assert state.current == state.head == "0003"
        assert state.at_head is True
        with database.engine.connect() as connection:
            assert connection.exec_driver_sql("PRAGMA integrity_check").scalar() == "ok"
            assert connection.exec_driver_sql("PRAGMA foreign_key_check").all() == []
            assert connection.exec_driver_sql("PRAGMA foreign_keys").scalar() == 1
            assert connection.exec_driver_sql("PRAGMA journal_mode").scalar() == "wal"
            assert connection.execute(
                text("SELECT count(*) FROM bindings WHERE is_active = 1")
            ).scalar() == 0
    finally:
        database.dispose()
