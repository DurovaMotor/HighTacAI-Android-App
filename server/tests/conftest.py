from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from pydantic import SecretStr
from sqlalchemy import select

from hightac_platform.config import Settings
from hightac_platform.db.models import LightTag, Site, Station
from hightac_platform.domain.enums import StationStatus
from hightac_platform.main import create_app


@dataclass(slots=True)
class FakePublisher:
    connected: bool = True
    messages: list[tuple[str, bytes]] = field(default_factory=list)

    def publish(self, topic: str, payload: bytes) -> bool:
        self.messages.append((topic, payload))
        return self.connected


@dataclass(slots=True)
class Harness:
    client: TestClient
    app: object
    publisher: FakePublisher
    settings: Settings
    password: str = "Adam"

    @property
    def runtime(self) -> object:
        return self.app.state.runtime

    def raw_login(self) -> object:
        return self.client.post(
            "/api/v1/auth/login",
            json={"username": "Adam", "password": self.password},
        )

    def login(self) -> dict[str, str]:
        response = self.raw_login()
        assert response.status_code == 200, response.text
        csrf = response.json()["csrf_token"]
        if response.json()["user"]["must_change_password"]:
            replacement = "Test-password-123!"
            changed = self.client.post(
                "/api/v1/auth/change-password",
                headers={"X-CSRF-Token": csrf},
                json={
                    "current_password": self.password,
                    "new_password": replacement,
                },
            )
            assert changed.status_code == 204, changed.text
            self.password = replacement
        return {"X-CSRF-Token": csrf}

    def mutation_headers(
        self, auth_headers: dict[str, str] | None = None, key: str | None = None
    ) -> dict[str, str]:
        return {
            **(auth_headers or {}),
            "Idempotency-Key": key or str(uuid4()),
        }


@pytest.fixture()
def harness(tmp_path: Path) -> Harness:
    settings = Settings(
        environment="test",
        data_dir=tmp_path,
        database_url=f"sqlite:///{(tmp_path / 'hightac-test.db').as_posix()}",
        bootstrap_admin_username="Adam",
        bootstrap_admin_password=SecretStr("Adam"),
        mqtt_enabled=False,
        mqtt_required_for_ready=False,
        command_scheduler_enabled=False,
        log_to_file=False,
        broker_pid_path=tmp_path / "mosquitto.pid",
        broker_log_path=tmp_path / "mosquitto.log",
    )
    publisher = FakePublisher()
    app = create_app(settings, publisher=publisher)
    with TestClient(app) as client:
        yield Harness(client=client, app=app, publisher=publisher, settings=settings)


def seed_online_station_and_tags(
    harness: Harness, count: int, station_id: str = "90A9F1234567"
) -> list[str]:
    tag_ids = [f"AD1{index:09X}" for index in range(count)]
    with harness.runtime.database.session_factory() as session:
        site = session.scalar(select(Site).order_by(Site.created_at_ms).limit(1))
        assert site is not None
        station = session.get(Station, station_id)
        if station is None:
            station = Station(
                station_id=station_id,
                site_id=site.id,
                status=StationStatus.ONLINE.value,
                last_heartbeat_at_ms=2_000_000_000_000,
            )
            session.add(station)
        else:
            station.status = StationStatus.ONLINE.value
            station.last_heartbeat_at_ms = 2_000_000_000_000
        session.flush()
        existing_tag_ids = set(
            session.scalars(
                select(LightTag.tag_id).where(
                    LightTag.tag_id.in_(tag_ids)
                )
            )
        )
        session.add_all(
            LightTag(
                tag_id=tag_id,
                site_id=site.id,
                station_id=station_id,
            )
            for tag_id in tag_ids
            if tag_id not in existing_tag_ids
        )
        session.commit()
    return tag_ids
