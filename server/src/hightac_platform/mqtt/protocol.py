from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any, Literal

from hightac_platform.domain.enums import CommandAction, LightColor
from hightac_platform.utils import normalize_station_id, normalize_tag_id


MAX_MQTT_PAYLOAD_BYTES = 512 * 1024
MAX_RESULTS_PER_PAYLOAD = 2000
MAX_TAGS_PER_PACKET = 20
TASK_RESULT_COMMUNICATION = 0xFE

COLOR_RGB: dict[LightColor, tuple[bool, bool, bool]] = {
    LightColor.RED: (True, False, False),
    LightColor.GREEN: (False, True, False),
    LightColor.BLUE: (False, False, True),
    LightColor.CYAN: (False, True, True),
    LightColor.PINK: (True, False, True),
}


class ProtocolError(ValueError):
    pass


@dataclass(frozen=True, slots=True)
class ParsedTopic:
    station_id: str
    kind: Literal["heartbeat", "result"]


@dataclass(frozen=True, slots=True)
class Heartbeat:
    station_id: str
    mac: str | None
    alias: str | None
    server_address: str | None
    heartbeat_seconds: int
    firmware_version: str | None
    total_count: int
    send_count: int


@dataclass(frozen=True, slots=True)
class ResultItem:
    tag_id: str
    version: str | None
    result_type: int
    rf_power_send: int | None
    rf_power_recv: int | None
    battery: int | None
    colors: tuple[tuple[bool, bool, bool], ...]
    group: int | None
    sequence: int | None

    @property
    def battery_voltage_mv(self) -> int | None:
        return None if self.battery is None else self.battery * 100

    @property
    def battery_level(self) -> int | None:
        if self.battery is None:
            return None
        voltage = self.battery / 10
        if voltage >= 3.0:
            return 100
        if voltage >= 2.9:
            return 90
        if voltage >= 2.8:
            return 80
        if voltage >= 2.7:
            return 60
        if voltage >= 2.6:
            return 30
        if voltage >= 2.5:
            return 10
        return 0


@dataclass(frozen=True, slots=True)
class TaskResult:
    station_id: str
    total_count: int
    send_count: int
    results: tuple[ResultItem, ...]
    skipped_items: int = 0


def parse_topic(topic: str) -> ParsedTopic:
    parts = topic.split("/")
    if len(parts) != 4 or parts[0] or parts[1] != "estation":
        raise ProtocolError("Unexpected MQTT topic.")
    try:
        station_id = normalize_station_id(parts[2])
    except ValueError as exc:
        raise ProtocolError(str(exc)) from exc
    if station_id != parts[2] or parts[3] not in {"heartbeat", "result"}:
        raise ProtocolError("Unexpected MQTT topic.")
    return ParsedTopic(station_id, parts[3])  # type: ignore[arg-type]


def parse_heartbeat(payload: bytes | str, expected_station_id: str) -> Heartbeat:
    data = _json_object(payload)
    station_id = _station_id(data, expected_station_id)
    heartbeat_seconds = _int(data.get("Heartbeat"), default=20)
    if not 1 <= heartbeat_seconds <= 3600:
        raise ProtocolError("Heartbeat interval is outside the supported range.")
    return Heartbeat(
        station_id=station_id,
        mac=_text(data.get("MAC"), 64),
        alias=_text(data.get("Alias"), 160),
        server_address=_text(data.get("ServerAddress"), 255),
        heartbeat_seconds=heartbeat_seconds,
        firmware_version=_text(data.get("AppVersion"), 64),
        total_count=max(0, _int(data.get("TotalCount"), default=0)),
        send_count=max(0, _int(data.get("SendCount"), default=0)),
    )


def parse_result(payload: bytes | str, expected_station_id: str) -> TaskResult:
    data = _json_object(payload)
    station_id = _station_id(data, expected_station_id)
    raw_results = data.get("Results", [])
    if not isinstance(raw_results, list):
        raise ProtocolError("Results must be an array.")
    if len(raw_results) > MAX_RESULTS_PER_PAYLOAD:
        raise ProtocolError("Result payload contains too many items.")
    results: list[ResultItem] = []
    skipped = 0
    for raw_item in raw_results:
        try:
            results.append(_result_item(raw_item))
        except (ProtocolError, TypeError, ValueError):
            skipped += 1
    return TaskResult(
        station_id=station_id,
        total_count=max(0, _int(data.get("TotalCount"), default=0)),
        send_count=max(0, _int(data.get("SendCount"), default=0)),
        results=tuple(results),
        skipped_items=skipped,
    )


def encode_task(
    action: CommandAction,
    color: LightColor | None,
    tag_ids: list[str] | tuple[str, ...],
) -> bytes:
    if not 1 <= len(tag_ids) <= MAX_TAGS_PER_PACKET:
        raise ProtocolError("MQTT task packet must contain 1 to 20 tags.")
    normalized_tags = [normalize_tag_id(tag_id) for tag_id in tag_ids]
    is_on = action is CommandAction.LIGHT_ON
    if is_on and color is None:
        raise ProtocolError("Light-on commands require a color.")
    rgb = COLOR_RGB[color] if is_on and color else (False, False, False)
    payload = {
        "Time": 1 if is_on else 0,
        "Items": [
            {
                "TagID": tag_id,
                "Beep": is_on,
                "Colors": [{"R": rgb[0], "G": rgb[1], "B": rgb[2]}],
                "Flashing": True if is_on else None,
            }
            for tag_id in normalized_tags
        ],
    }
    return json.dumps(payload, separators=(",", ":"), ensure_ascii=True).encode("utf-8")


def result_matches_command(
    item: ResultItem, action: CommandAction, color: LightColor | None
) -> bool:
    expected = COLOR_RGB[color] if action is CommandAction.LIGHT_ON and color else (False, False, False)
    return expected in item.colors


def _json_object(payload: bytes | str) -> dict[str, Any]:
    raw = payload.encode("utf-8") if isinstance(payload, str) else payload
    if len(raw) > MAX_MQTT_PAYLOAD_BYTES:
        raise ProtocolError("MQTT payload is too large.")
    try:
        decoded = raw.decode("utf-8")
        value = json.loads(decoded)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ProtocolError("MQTT payload is not valid UTF-8 JSON.") from exc
    if not isinstance(value, dict):
        raise ProtocolError("MQTT payload must be a JSON object.")
    return value


def _station_id(data: dict[str, Any], expected_station_id: str) -> str:
    raw_id = data.get("ID")
    if not isinstance(raw_id, str):
        raise ProtocolError("Station ID is missing.")
    try:
        station_id = normalize_station_id(raw_id)
        expected = normalize_station_id(expected_station_id)
    except ValueError as exc:
        raise ProtocolError(str(exc)) from exc
    if station_id != raw_id or station_id != expected:
        raise ProtocolError("Payload station ID does not match the MQTT topic.")
    return station_id


def _result_item(value: Any) -> ResultItem:
    if not isinstance(value, dict):
        raise ProtocolError("Result item must be an object.")
    raw_tag_id = value.get("TagID")
    if not isinstance(raw_tag_id, str):
        raise ProtocolError("Result item has no tag ID.")
    tag_id = normalize_tag_id(raw_tag_id)
    if tag_id != raw_tag_id:
        raise ProtocolError("Tag ID is not normalized.")
    colors: list[tuple[bool, bool, bool]] = []
    raw_colors = value.get("Colors", [])
    if isinstance(raw_colors, list):
        for color in raw_colors[:10]:
            if isinstance(color, dict):
                colors.append(
                    (color.get("R") is True, color.get("G") is True, color.get("B") is True)
                )
    send = _optional_int(value.get("RfPowerSend"))
    recv = _optional_int(value.get("RfPowerRecv"))
    return ResultItem(
        tag_id=tag_id,
        version=_text(value.get("Version"), 64),
        result_type=_int(value.get("ResultType"), default=0),
        rf_power_send=None if send == -256 else send,
        rf_power_recv=None if recv == -256 else recv,
        battery=_optional_int(value.get("Battery")),
        colors=tuple(colors),
        group=_optional_int(value.get("Group")),
        sequence=_optional_int(value.get("Sequence")),
    )


def _text(value: Any, limit: int) -> str | None:
    if not isinstance(value, str):
        return None
    cleaned = value.strip()
    return cleaned[:limit] or None


def _int(value: Any, *, default: int) -> int:
    if value is None:
        return default
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ProtocolError("Expected an integer value.")
    return int(value)


def _optional_int(value: Any) -> int | None:
    return None if value is None else _int(value, default=0)
