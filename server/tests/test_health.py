from sqlalchemy import text

from hightac_platform.db.migrations import inspect_migration_state


REQUEST_ID = "6fbf7f94-79c2-481d-8c84-1d4f2816a4bf"


def test_health_and_backup_share_migration_head_state(harness) -> None:
    live = harness.client.get(
        "/api/v1/health/live", headers={"X-Request-ID": REQUEST_ID}
    )
    assert live.status_code == 200
    assert live.headers["X-Request-ID"] == REQUEST_ID
    assert live.json()["status"] == "alive"
    assert live.json()["service"] == "HighTacPlatform"

    ready = harness.client.get("/api/v1/health/ready")
    assert ready.status_code == 200
    body = ready.json()
    assert body["status"] == "READY"
    assert [check["name"] for check in body["checks"]] == [
        "database",
        "migrations",
        "mqtt_bridge",
    ]
    migration_check = next(
        item for item in body["checks"] if item["name"] == "migrations"
    )
    assert migration_check["status"] == "READY"
    assert "current=0003" in migration_check["message"]
    assert "head=0003" in migration_check["message"]

    state = inspect_migration_state(
        harness.runtime.database.engine, harness.settings
    )
    assert state.current == state.head == "0003"
    assert state.at_head is True

    auth = harness.login()
    backup = harness.client.post(
        "/api/v1/backups",
        headers=harness.mutation_headers(auth),
    )
    assert backup.status_code == 202, backup.text
    assert backup.json()["status"] == "SUCCEEDED"
    assert (
        harness.settings.backups_dir / backup.json()["filename"]
    ).is_file()


def test_health_and_backup_both_reject_database_behind_head(harness) -> None:
    auth = harness.login()
    with harness.runtime.database.engine.begin() as connection:
        connection.execute(
            text("UPDATE alembic_version SET version_num = '0001'")
        )
    ready = harness.client.get("/api/v1/health/ready")
    assert ready.status_code == 503
    migration_check = next(
        item
        for item in ready.json()["checks"]
        if item["name"] == "migrations"
    )
    assert migration_check["status"] == "NOT_READY"

    backup = harness.client.post(
        "/api/v1/backups",
        headers=harness.mutation_headers(auth),
    )
    assert backup.status_code == 409
    assert "current=0001" in backup.json()["error"]["message"]
    assert "head=0003" in backup.json()["error"]["message"]
