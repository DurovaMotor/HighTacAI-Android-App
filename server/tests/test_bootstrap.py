from __future__ import annotations

from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from pydantic import SecretStr, ValidationError
from sqlalchemy import select

from conftest import FakePublisher
from hightac_platform.auth.security import verify_password
from hightac_platform.config import Settings
from hightac_platform.db.models import AdminUser
from hightac_platform.main import create_app


def test_app_startup_uses_configured_bootstrap_credentials(tmp_path: Path) -> None:
    password = "configured test bootstrap password"
    settings = Settings(
        environment="test",
        data_dir=tmp_path,
        database_url=f"sqlite:///{(tmp_path / 'bootstrap.db').as_posix()}",
        bootstrap_admin_username="release-admin",
        bootstrap_admin_password=SecretStr(password),
        mqtt_enabled=False,
        command_scheduler_enabled=False,
        lifecycle_scheduler_enabled=False,
        log_to_file=False,
    )

    app = create_app(settings, publisher=FakePublisher())
    with TestClient(app) as client:
        response = client.post(
            "/api/v1/auth/login",
            json={"username": "release-admin", "password": password},
        )
        assert response.status_code == 200

        with app.state.runtime.database.session_factory() as session:
            user = session.scalar(select(AdminUser))
            assert user is not None
            assert user.username == "release-admin"
            assert verify_password(password, user.password_hash)


def test_production_bootstrap_requires_explicit_password(monkeypatch) -> None:
    monkeypatch.delenv("HIGHTAC_BOOTSTRAP_ADMIN_PASSWORD", raising=False)

    with pytest.raises(
        ValidationError,
        match="HIGHTAC_BOOTSTRAP_ADMIN_PASSWORD",
    ):
        Settings(
            environment="production",
            bootstrap_admin=True,
            bootstrap_admin_password=None,
            _env_file=None,
        )

    settings = Settings(
        environment="production",
        bootstrap_admin=False,
        bootstrap_admin_password=None,
        _env_file=None,
    )
    assert settings.bootstrap_admin is False


def test_bootstrap_password_is_secret_and_not_serialized() -> None:
    raw_password = "do-not-serialize-this-bootstrap-password"
    settings = Settings(
        environment="production",
        bootstrap_admin_password=SecretStr(raw_password),
        _env_file=None,
    )

    assert raw_password not in repr(settings)
    assert "bootstrap_admin_password" not in settings.model_dump()
    assert "bootstrap_admin_password" not in settings.model_dump_json()
    assert raw_password not in settings.model_dump_json()
