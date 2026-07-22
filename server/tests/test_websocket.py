import json
from pathlib import Path
from uuid import uuid4

from jsonschema import Draft202012Validator, FormatChecker
from sqlalchemy import select

from hightac_platform.db.models import AndroidDevice
from hightac_platform.events import build_event

from conftest import seed_online_station_and_tags
ROOT = Path(__file__).resolve().parents[2]
EVENT_SCHEMA = json.loads(
    (ROOT / "contracts" / "events.schema.json").read_text(encoding="utf-8")
)
EVENT_VALIDATOR = Draft202012Validator(
    EVENT_SCHEMA, format_checker=FormatChecker()
)


def test_event_builder_covers_every_contract_event_type() -> None:
    for example in EVENT_SCHEMA["examples"]:
        event = build_event(
            example["event_type"],
            example["entity_id"],
            example["payload"],
        )
        EVENT_VALIDATOR.validate(event)


def test_anonymous_websocket_only_emits_app_safe_schema_valid_events(
    harness,
) -> None:
    with harness.client.websocket_connect(
        "/api/v1/ws/events"
    ) as websocket:
        notice = websocket.receive_json()
        EVENT_VALIDATOR.validate(notice)
        assert notice["event_type"] == "system.notice"
        harness.runtime.event_bus.publish_threadsafe(
            "system.notice",
            "HighTacPlatform",
            {
                "severity": "WARNING",
                "code": "TEST_NOTICE",
                "message": "Contract validation notice.",
                "resource_type": None,
                "resource_id": None,
            },
        )
        event = websocket.receive_json()
        EVENT_VALIDATOR.validate(event)
        assert event["payload"]["code"] == "TEST_NOTICE"

        harness.runtime.event_bus.publish_threadsafe(
            "device.status_changed",
            "private-device-id",
            {
                "device_id": "private-device-id",
                "display_name": "Private phone",
                "previous_status": "PENDING",
                "current_status": "APPROVED",
                "manufacturer": "Private",
                "model": "Phone",
                "app_version": "1.0",
                "reason": "admin_only_test",
            },
        )
        harness.runtime.event_bus.publish_threadsafe(
            "system.notice",
            "HighTacPlatform",
            {
                "severity": "INFO",
                "code": "AFTER_PRIVATE_EVENT",
                "message": "Anonymous stream remains filtered.",
                "resource_type": None,
                "resource_id": None,
            },
        )
        filtered = websocket.receive_json()
        assert filtered["event_type"] == "system.notice"
        assert filtered["payload"]["code"] == "AFTER_PRIVATE_EVENT"


def test_admin_websocket_receives_device_status_events(harness) -> None:
    harness.login()
    with harness.client.websocket_connect("/api/v1/ws/events") as websocket:
        websocket.receive_json()
        harness.runtime.event_bus.publish_threadsafe(
            "device.status_changed",
            "admin-visible-device",
            {
                "device_id": "admin-visible-device",
                "display_name": None,
                "previous_status": "PENDING",
                "current_status": "APPROVED",
                "manufacturer": "Test",
                "model": "Phone",
                "app_version": "1.0",
                "reason": "admin_visibility_test",
            },
        )
        event = websocket.receive_json()
        assert event["event_type"] == "device.status_changed"


def test_non_upgrade_events_get_returns_contract_426(harness) -> None:
    harness.login()
    response = harness.client.get("/api/v1/ws/events")
    assert response.status_code == 426
    assert response.headers["Upgrade"] == "websocket"
    assert (
        response.json()["error"]["code"]
        == "WEBSOCKET_UPGRADE_REQUIRED"
    )


def test_real_domain_publishers_emit_contract_payloads(
    harness, monkeypatch
) -> None:
    captured: list[dict[str, object]] = []

    def capture(
        event_type: str, entity_id: str, payload: dict[str, object]
    ) -> None:
        event = build_event(event_type, entity_id, payload)
        EVENT_VALIDATOR.validate(event)
        captured.append(event)

    monkeypatch.setattr(
        harness.runtime.event_bus, "publish_threadsafe", capture
    )
    tag_id = seed_online_station_and_tags(harness, 1)[0]
    auth = harness.login()
    binding = harness.client.post(
        "/api/v1/bindings",
        headers=harness.mutation_headers(auth),
        json={
            "product_code": "EVENT-PART",
            "tag_id": tag_id,
            "station_id": "90A9F1234567",
        },
    )
    assert binding.status_code == 201
    command = harness.client.post(
        "/api/v1/light-commands",
        headers=harness.mutation_headers(auth),
        json={
            "action": "LIGHT_ON",
            "tag_ids": [tag_id],
            "color": "RED",
        },
    )
    assert command.status_code == 202

    heartbeat = json.dumps(
        {
            "ID": "90A9F1234567",
            "MAC": "AA:BB:CC:DD:EE:FF",
            "Alias": "Dock",
            "ServerAddress": "192.168.1.105:1884",
            "Heartbeat": 20,
            "AppVersion": "1.6.7",
            "TotalCount": 1,
            "SendCount": 1,
        }
    ).encode()
    assert harness.runtime.telemetry_service.handle_message(
        "/estation/90A9F1234567/heartbeat", heartbeat
    )
    result = json.dumps(
        {
            "ID": "90A9F1234567",
            "Results": [
                {
                    "TagID": tag_id,
                    "ResultType": 254,
                    "Battery": 29,
                    "Colors": [
                        {"R": True, "G": False, "B": False}
                    ],
                }
            ],
        }
    ).encode()
    assert harness.runtime.telemetry_service.handle_message(
        "/estation/90A9F1234567/result", result
    )

    enrollment = harness.client.post(
        "/api/v1/device-enrollments",
        headers={"Idempotency-Key": str(uuid4())},
        json={
            "fingerprint_hash": "c" * 64,
            "installation_key_hash": "d" * 64,
            "manufacturer": "Test",
            "model": "Phone",
            "app_version": "1.0",
        },
    )
    assert enrollment.status_code == 202
    with harness.runtime.database.session_factory() as session:
        device_id = session.scalar(select(AndroidDevice.id))
    approved = harness.client.post(
        f"/api/v1/android-devices/{device_id}/approve",
        headers=harness.mutation_headers(auth),
        json={"display_name": "Event phone"},
    )
    assert approved.status_code == 200

    event_types = {event["event_type"] for event in captured}
    assert {
        "binding.created",
        "command.status_changed",
        "station.heartbeat",
        "tag.status_changed",
        "device.status_changed",
    } <= event_types
