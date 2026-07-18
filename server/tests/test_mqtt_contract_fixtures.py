from __future__ import annotations

import json
from pathlib import Path

import pytest

from hightac_platform.domain.enums import CommandAction, LightColor
from hightac_platform.mqtt.protocol import (
    ProtocolError,
    encode_task,
    parse_heartbeat,
    parse_result,
    parse_topic,
)


ROOT = Path(__file__).resolve().parents[2]
FIXTURES_DIR = ROOT / "contracts" / "mqtt-fixtures"
MANIFEST = json.loads(
    (FIXTURES_DIR / "manifest.json").read_text(encoding="utf-8")
)
FIXTURES = {entry["id"]: entry for entry in MANIFEST["fixtures"]}


def _raw_fixture(fixture_id: str) -> bytes:
    entry = FIXTURES[fixture_id]
    return (FIXTURES_DIR / entry["file"]).read_bytes()


def _json_fixture(fixture_id: str) -> dict[str, object]:
    return json.loads(_raw_fixture(fixture_id))


def test_station_fixtures_are_accepted_by_backend_protocol() -> None:
    heartbeat_entry = FIXTURES["station-heartbeat"]
    heartbeat_topic = parse_topic(heartbeat_entry["topic"])
    heartbeat = parse_heartbeat(
        _raw_fixture("station-heartbeat"),
        heartbeat_topic.station_id,
    )
    assert heartbeat_topic.kind == "heartbeat"
    assert heartbeat.station_id == MANIFEST["station_id"]
    assert heartbeat.heartbeat_seconds == 20
    assert heartbeat.total_count == 3
    assert heartbeat.send_count == 1

    result_entry = FIXTURES["station-result-ack"]
    result_topic = parse_topic(result_entry["topic"])
    result = parse_result(
        _raw_fixture("station-result-ack"),
        result_topic.station_id,
    )
    assert result_topic.kind == "result"
    assert result.station_id == MANIFEST["station_id"]
    assert result.skipped_items == 0
    assert len(result.results) == 1
    assert result.results[0].tag_id == "AD100000048F"
    assert result.results[0].result_type == 254
    assert result.results[0].rf_power_send is None


@pytest.mark.parametrize(
    ("fixture_id", "action", "color"),
    [
        ("backend-light-command", CommandAction.LIGHT_ON, LightColor.RED),
        ("backend-clear-command", CommandAction.LIGHT_OFF, None),
    ],
)
def test_backend_command_fixtures_match_protocol_encoder(
    fixture_id: str,
    action: CommandAction,
    color: LightColor | None,
) -> None:
    expected = _json_fixture(fixture_id)
    tag_ids = [item["TagID"] for item in expected["Items"]]

    encoded = json.loads(encode_task(action, color, tag_ids))

    assert encoded == expected
    assert FIXTURES[fixture_id]["topic"] == (
        f"/estation/{MANIFEST['station_id']}/task"
    )


def test_malformed_result_fixture_is_rejected_by_backend_protocol() -> None:
    entry = FIXTURES["malformed-result"]
    topic = parse_topic(entry["topic"])

    with pytest.raises(ProtocolError, match="valid UTF-8 JSON"):
        parse_result(_raw_fixture("malformed-result"), topic.station_id)
