from __future__ import annotations

import json
from uuid import uuid4

import pytest
from pydantic import SecretStr

from hightac_platform.config import Settings
from hightac_platform.db.models import AppSetting


def test_mqtt_password_is_excluded_from_settings_serialization(tmp_path) -> None:
    raw_value = uuid4().hex
    settings = Settings(
        environment="test",
        data_dir=tmp_path,
        mqtt_password=SecretStr(raw_value),
        log_to_file=False,
        _env_file=None,
    )

    serialized = settings.model_dump_json()
    assert "mqtt_password" not in settings.model_dump()
    assert "mqtt_password" not in json.loads(serialized)
    assert raw_value not in repr(settings)
    assert raw_value not in serialized


def test_mobile_proxy_secrets_are_optional_and_excluded(tmp_path) -> None:
    openai_value = "test-only-openai-placeholder"
    jiandaoyun_value = "test-only-jiandaoyun-placeholder"
    settings = Settings(
        environment="test",
        data_dir=tmp_path,
        openai_api_key=SecretStr(openai_value),
        jiandaoyun_api_key=SecretStr(jiandaoyun_value),
        log_to_file=False,
        _env_file=None,
    )

    serialized = settings.model_dump_json()
    assert "openai_api_key" not in settings.model_dump()
    assert "jiandaoyun_api_key" not in settings.model_dump()
    assert openai_value not in repr(settings)
    assert jiandaoyun_value not in repr(settings)
    assert openai_value not in serialized
    assert jiandaoyun_value not in serialized

    empty = Settings(
        environment="test",
        data_dir=tmp_path,
        openai_api_key="",
        jiandaoyun_api_key="  ",
        jiandaoyun_app_id="",
        jiandaoyun_entry_id=" ",
        log_to_file=False,
        _env_file=None,
    )
    assert empty.openai_api_key is None
    assert empty.jiandaoyun_api_key is None
    assert empty.jiandaoyun_app_id is None
    assert empty.jiandaoyun_entry_id is None


def test_network_settings_report_effective_runtime_values(harness) -> None:
    harness.login()
    stored_values = {
        "network.api_bind_address": "203.0.113.10",
        "network.api_port": 6553,
        "network.mqtt_host": "mqtt.example.invalid",
        "network.mqtt_port": 8883,
        "network.mqtt_tls_enabled": True,
        "network.restart_required": True,
    }
    with harness.runtime.database.session_factory() as session:
        for key, value in stored_values.items():
            harness.runtime.settings_service.set(session, key, value, "admin-test")
        session.commit()

    response = harness.client.get("/api/v1/settings/network")

    assert response.status_code == 200
    assert response.json() == {
        "api_bind_address": harness.settings.host,
        "api_port": harness.settings.port,
        "mqtt_host": harness.settings.mqtt_host,
        "mqtt_port": harness.settings.mqtt_port,
        "mqtt_tls_enabled": False,
        "restart_required": False,
        "updated_at": response.json()["updated_at"],
    }


@pytest.mark.parametrize(
    "patch",
    [
        {"api_bind_address": "127.0.0.2"},
        {"api_port": 9088},
        {"mqtt_host": "192.0.2.10"},
        {"mqtt_port": 1883},
    ],
)
def test_network_settings_reject_runtime_endpoint_changes(
    harness, patch: dict[str, object]
) -> None:
    headers = harness.login()

    response = harness.client.patch(
        "/api/v1/settings/network", headers=headers, json=patch
    )

    assert response.status_code == 409
    assert response.json()["error"]["code"] == "CONFLICT"
    assert "deployment-managed" in response.json()["error"]["message"]
    with harness.runtime.database.session_factory() as session:
        assert session.get(AppSetting, "network.restart_required") is None
        assert all(
            session.get(AppSetting, f"network.{field}") is None for field in patch
        )


def test_network_settings_reject_tls_enablement_without_persisting(
    harness,
) -> None:
    headers = harness.login()

    response = harness.client.patch(
        "/api/v1/settings/network",
        headers=headers,
        json={"mqtt_tls_enabled": True},
    )

    assert response.status_code == 409
    assert response.json()["error"]["code"] == "CONFLICT"
    assert "TLS is not supported" in response.json()["error"]["message"]
    with harness.runtime.database.session_factory() as session:
        assert session.get(AppSetting, "network.mqtt_tls_enabled") is None
        assert session.get(AppSetting, "network.restart_required") is None


def test_network_settings_accept_an_effective_value_as_a_no_op(harness) -> None:
    headers = harness.login()

    response = harness.client.patch(
        "/api/v1/settings/network",
        headers=headers,
        json={
            "api_bind_address": harness.settings.host,
            "api_port": harness.settings.port,
            "mqtt_host": harness.settings.mqtt_host,
            "mqtt_port": harness.settings.mqtt_port,
            "mqtt_tls_enabled": False,
        },
    )

    assert response.status_code == 200
    assert response.json()["mqtt_tls_enabled"] is False
    assert response.json()["restart_required"] is False
    with harness.runtime.database.session_factory() as session:
        assert all(
            session.get(AppSetting, key) is None
            for key in (
                "network.api_bind_address",
                "network.api_port",
                "network.mqtt_host",
                "network.mqtt_port",
                "network.mqtt_tls_enabled",
                "network.restart_required",
            )
        )


def test_site_settings_remain_live_editable(harness) -> None:
    headers = harness.login()

    response = harness.client.patch(
        "/api/v1/settings/site",
        headers=headers,
        json={
            "name": "HighTac 测试站点",
            "address": "A 区",
            "notes": "在线设置",
            "low_battery_threshold": 60,
        },
    )

    assert response.status_code == 200
    assert response.json()["name"] == "HighTac 测试站点"
    assert response.json()["address"] == "A 区"
    assert response.json()["notes"] == "在线设置"
    assert response.json()["low_battery_threshold"] == 60
