from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from alembic import command
from alembic.config import Config
from alembic.migration import MigrationContext
from alembic.script import ScriptDirectory
from sqlalchemy import Engine

from hightac_platform.config import PACKAGE_ROOT, SERVER_ROOT, Settings


@dataclass(frozen=True, slots=True)
class MigrationState:
    current: str | None
    head: str | None
    at_head: bool


def alembic_config(settings: Settings) -> Config:
    ini_path = SERVER_ROOT / "alembic.ini"
    config = Config(str(ini_path)) if ini_path.is_file() else Config()
    config.set_main_option("script_location", str(migrations_path()))
    config.set_main_option("sqlalchemy.url", settings.effective_database_url.replace("%", "%%"))
    return config


def upgrade_database(settings: Settings) -> None:
    settings.ensure_runtime_directories()
    command.upgrade(alembic_config(settings), "head")


def migration_status(engine: Engine, settings: Settings) -> tuple[str | None, str | None, bool]:
    state = inspect_migration_state(engine, settings)
    return state.current, state.head, state.at_head


def inspect_migration_state(
    engine: Engine, settings: Settings
) -> MigrationState:
    config = alembic_config(settings)
    script = ScriptDirectory.from_config(config)
    expected_heads = tuple(sorted(script.get_heads()))
    with engine.connect() as connection:
        current_heads = tuple(
            sorted(MigrationContext.configure(connection).get_current_heads())
        )
    current = ",".join(current_heads) or None
    head = ",".join(expected_heads) or None
    return MigrationState(
        current=current,
        head=head,
        at_head=bool(expected_heads) and current_heads == expected_heads,
    )


def migrations_path() -> Path:
    source_path = SERVER_ROOT / "migrations"
    return source_path if source_path.is_dir() else PACKAGE_ROOT / "_migrations"
