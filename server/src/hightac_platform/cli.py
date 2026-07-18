from __future__ import annotations

import argparse

import uvicorn

from hightac_platform.config import Settings
from hightac_platform.db.migrations import upgrade_database
from hightac_platform.db.session import Database
from hightac_platform.logging import configure_logging
from hightac_platform.services.bootstrap import bootstrap_first_run


def main() -> None:
    parser = argparse.ArgumentParser(prog="hightac-platform")
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("migrate", help="Apply Alembic migrations and first-run bootstrap.")
    serve_parser = subparsers.add_parser("serve", help="Run the FastAPI server.")
    serve_parser.add_argument("--reload", action="store_true")
    args = parser.parse_args()

    settings = Settings()
    settings.ensure_runtime_directories()
    configure_logging(settings)
    if args.command == "migrate":
        upgrade_database(settings)
        database = Database(settings)
        try:
            if settings.bootstrap_admin:
                bootstrap_first_run(database.session_factory, settings)
        finally:
            database.dispose()
        return
    uvicorn.run(
        "hightac_platform.main:app",
        host=settings.host,
        port=settings.port,
        reload=args.reload,
    )


if __name__ == "__main__":
    main()
