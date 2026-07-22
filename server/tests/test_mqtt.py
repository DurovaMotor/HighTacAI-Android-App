import json
import threading
from time import perf_counter
from types import SimpleNamespace

from sqlalchemy import func, select

from hightac_platform.db.models import LightTag, Station
from hightac_platform.mqtt import bridge as bridge_module
from hightac_platform.mqtt.bridge import MqttBridge

from conftest import seed_online_station_and_tags


STATION_ID = "90A9F1234567"
TAG_ID = "AD100000048F"


class BlockingTelemetry:
    def __init__(self) -> None:
        self.started = threading.Event()
        self.release = threading.Event()

    def handle_messages(
        self, messages: list[tuple[str, bytes]]
    ) -> int:
        self.started.set()
        assert self.release.wait(timeout=5)
        return len(messages)


class RecordingTelemetry:
    def __init__(self) -> None:
        self.batch_sizes: list[int] = []

    def handle_messages(
        self, messages: list[tuple[str, bytes]]
    ) -> int:
        self.batch_sizes.append(len(messages))
        return len(messages)


def _message(index: int = 0) -> SimpleNamespace:
    return SimpleNamespace(
        topic=f"/estation/{STATION_ID}/result",
        payload=f'{{"sequence":{index}}}'.encode(),
    )


def test_mqtt_callback_is_nonblocking_with_bounded_backpressure(
    harness, monkeypatch
) -> None:
    telemetry = BlockingTelemetry()
    bridge = MqttBridge(
        harness.settings,
        telemetry,  # type: ignore[arg-type]
        harness.runtime.event_bus,
        queue_capacity=1,
        batch_size=1,
    )
    callback_done = threading.Event()
    callback_elapsed_ms: list[float] = []
    warnings: list[dict[str, object]] = []

    def capture_warning(
        _message: str, *, extra: dict[str, object]
    ) -> None:
        warnings.append(extra)

    monkeypatch.setattr(bridge_module.logger, "warning", capture_warning)
    bridge._start_worker()

    def invoke_callback() -> None:
        started = perf_counter()
        bridge._on_message(None, None, _message())  # type: ignore[arg-type]
        callback_elapsed_ms.append((perf_counter() - started) * 1000)
        callback_done.set()

    callback_thread = threading.Thread(target=invoke_callback)
    try:
        callback_thread.start()
        assert callback_done.wait(timeout=0.5)
        assert telemetry.started.wait(timeout=1)
        bridge._on_message(None, None, _message(1))  # type: ignore[arg-type]
        bridge._on_message(None, None, _message(2))  # type: ignore[arg-type]
        stats = bridge.ingress_stats
        assert stats.enqueued_messages == 2
        assert stats.dropped_messages == 1
        assert stats.queue_depth == 1
        assert any(
            warning["event"] == "mqtt.ingress_queue_full"
            for warning in warnings
        )
    finally:
        telemetry.release.set()
        callback_thread.join(timeout=1)
        bridge.stop()

    stats = bridge.ingress_stats
    assert stats.processed_messages == 2
    assert stats.failed_messages == 0
    assert stats.queue_depth == 0
    print(
        "mqtt_callback_enqueue_ms="
        f"{callback_elapsed_ms[0]:.3f}"
    )


def test_mqtt_worker_drains_messages_in_batches(harness) -> None:
    telemetry = RecordingTelemetry()
    bridge = MqttBridge(
        harness.settings,
        telemetry,  # type: ignore[arg-type]
        harness.runtime.event_bus,
        queue_capacity=8,
        batch_size=4,
    )
    for index in range(5):
        bridge._on_message(None, None, _message(index))  # type: ignore[arg-type]

    bridge._start_worker()
    bridge.stop()

    assert telemetry.batch_sizes == [4, 1]
    assert bridge.ingress_stats.processed_messages == 5


def test_bad_mqtt_payloads_are_rejected_without_database_pollution(harness) -> None:
    telemetry = harness.runtime.telemetry_service
    assert telemetry.handle_message("not/a/topic", b"{}") is False
    assert telemetry.handle_message(f"/estation/{STATION_ID}/heartbeat", b"not-json") is False
    mismatch = json.dumps({"ID": "90A9F7654321", "Heartbeat": 20}).encode()
    assert telemetry.handle_message(f"/estation/{STATION_ID}/heartbeat", mismatch) is False
    malformed_result = json.dumps(
        {"ID": STATION_ID, "Results": [{"TagID": "bad"}, "not-an-object"]}
    ).encode()
    assert telemetry.handle_message(f"/estation/{STATION_ID}/result", malformed_result) is True

    with harness.runtime.database.session_factory() as session:
        assert session.scalar(select(func.count()).select_from(LightTag)) == 0


def test_valid_heartbeat_and_result_update_station_and_tag(harness) -> None:
    telemetry = harness.runtime.telemetry_service
    heartbeat = json.dumps(
        {
            "ID": STATION_ID,
            "MAC": "AA:BB:CC:DD:EE:FF",
            "Alias": "Dock A",
            "ServerAddress": "192.168.1.105:1884",
            "Parameters": ["must", "not", "be", "stored"],
            "Heartbeat": 20,
            "AppVersion": "1.6.7",
            "TotalCount": 2,
            "SendCount": 1,
        }
    ).encode()
    assert telemetry.handle_message(f"/estation/{STATION_ID}/heartbeat", heartbeat)
    result = json.dumps(
        {
            "ID": STATION_ID,
            "TotalCount": 1,
            "SendCount": 0,
            "Results": [
                {
                    "TagID": TAG_ID,
                    "Version": "1.6.7",
                    "ResultType": 254,
                    "RfPowerSend": -256,
                    "RfPowerRecv": -70,
                    "Battery": 29,
                    "Colors": [{"R": True, "G": False, "B": False}],
                    "Group": 10,
                    "Sequence": 7,
                }
            ],
        }
    ).encode()
    assert telemetry.handle_message(f"/estation/{STATION_ID}/result", result)

    with harness.runtime.database.session_factory() as session:
        station = session.get(Station, STATION_ID)
        tag = session.get(LightTag, TAG_ID)
        assert station.status == "ONLINE"
        assert station.server_address == "192.168.1.105:1884"
        assert tag.station_id == STATION_ID
        assert tag.battery_raw == 29
        assert tag.battery_voltage_mv == 2900
        assert tag.battery_level == 90


def test_matching_result_confirms_latest_command(harness) -> None:
    tag_id = seed_online_station_and_tags(harness, 1)[0]
    headers = harness.mutation_headers(harness.login())
    command = harness.client.post(
        "/api/v1/light-commands",
        headers=headers,
        json={
            "action": "LIGHT_ON",
            "tag_ids": [tag_id],
            "color": "RED",
        },
    )
    assert command.status_code == 202
    payload = json.dumps(
        {
            "ID": STATION_ID,
            "Results": [
                {
                    "TagID": tag_id,
                    "ResultType": 254,
                    "Colors": [{"R": True, "G": False, "B": False}],
                }
            ],
        }
    ).encode()
    assert harness.runtime.telemetry_service.handle_message(
        f"/estation/{STATION_ID}/result", payload
    )
    detail = harness.client.get(f"/api/v1/light-commands/{command.json()['id']}")
    assert detail.status_code == 200
    assert detail.json()["status"] == "CONFIRMED"
    assert detail.json()["items"][0]["status"] == "CONFIRMED"


def test_bulk_acknowledgement_preserves_progressive_command_events(
    harness, monkeypatch
) -> None:
    tag_ids = seed_online_station_and_tags(harness, 3)
    headers = harness.mutation_headers(harness.login())
    command = harness.client.post(
        "/api/v1/light-commands",
        headers=headers,
        json={
            "action": "LIGHT_ON",
            "tag_ids": tag_ids,
            "color": "RED",
        },
    )
    assert command.status_code == 202
    captured: list[tuple[str, str, dict[str, object]]] = []
    monkeypatch.setattr(
        harness.runtime.event_bus,
        "publish_threadsafe",
        lambda event_type, entity_id, payload: captured.append(
            (event_type, entity_id, payload)
        ),
    )
    payload = json.dumps(
        {
            "ID": STATION_ID,
            "Results": [
                {
                    "TagID": tag_id,
                    "ResultType": 254,
                    "Colors": [
                        {"R": True, "G": False, "B": False}
                    ],
                }
                for tag_id in tag_ids
            ],
        }
    ).encode()

    assert harness.runtime.telemetry_service.handle_message(
        f"/estation/{STATION_ID}/result", payload
    )

    command_events = [
        event_payload
        for event_type, _entity_id, event_payload in captured
        if event_type == "command.status_changed"
    ]
    assert [
        event_payload["previous_status"]
        for event_payload in command_events
    ] == ["PUBLISHED", "PUBLISHED", "PUBLISHED"]
    assert [
        event_payload["current_status"]
        for event_payload in command_events
    ] == ["PUBLISHED", "PUBLISHED", "CONFIRMED"]
    assert [
        event_payload["confirmed_count"]
        for event_payload in command_events
    ] == [1, 2, 3]
