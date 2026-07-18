#!/usr/bin/env python3
"""Validate HighTac API, WebSocket, and eStation fixture contracts."""

from __future__ import annotations

import copy
import json
import re
import sys
from pathlib import Path
from typing import Any, Dict, Iterable, List, Set, Tuple

try:
    import yaml
    from jsonschema import Draft202012Validator, FormatChecker
    from openapi_spec_validator import validate_spec
except ImportError as exc:
    print(f"Missing validation dependency: {exc}", file=sys.stderr)
    print(
        f"Install with: {sys.executable} -m pip install -r "
        "contracts/validation-requirements.txt",
        file=sys.stderr,
    )
    raise SystemExit(2)


CONTRACTS_DIR = Path(__file__).resolve().parent
FIXTURES_DIR = CONTRACTS_DIR / "mqtt-fixtures"
HTTP_METHODS = {"get", "post", "put", "patch", "delete", "options", "head", "trace"}
STATION_ID_RE = re.compile(r"^90A9F[0-9A-F]{7}$")
TAG_ID_RE = re.compile(r"^AD1[0-9A-F]{9}$")

EXPECTED_OPERATIONS: Dict[str, Set[str]] = {
    "/auth/login": {"post"},
    "/auth/logout": {"post"},
    "/auth/me": {"get"},
    "/auth/change-password": {"post"},
    "/health/live": {"get"},
    "/health/ready": {"get"},
    "/dashboard/summary": {"get"},
    "/dashboard/trends": {"get"},
    "/broker/status": {"get"},
    "/broker/start": {"post"},
    "/broker/stop": {"post"},
    "/broker/restart": {"post"},
    "/broker/logs": {"get"},
    "/broker/config-summary": {"get"},
    "/stations": {"get", "post"},
    "/stations/{station_id}": {"get", "patch"},
    "/stations/{station_id}/connection-checklist": {"get"},
    "/tags": {"get"},
    "/tags/{tag_id}": {"get"},
    "/tags/register": {"post"},
    "/tags/low-battery": {"get"},
    "/tags/abnormal": {"get"},
    "/products": {"get", "post"},
    "/products/{id}": {"get", "patch"},
    "/products/import-template": {"get"},
    "/products/imports": {"post"},
    "/products/imports/{id}": {"get"},
    "/products/imports/{id}/errors": {"get"},
    "/products/imports/{id}/commit": {"post"},
    "/bindings": {"get", "post"},
    "/bindings/{id}": {"delete"},
    "/bindings/{id}/rebind": {"post"},
    "/migrations/android-bindings/preview": {"post"},
    "/migrations/android-bindings/commit": {"post"},
    "/light-commands": {"post"},
    "/light-commands/{id}": {"get"},
    "/stations/{station_id}/all-off": {"post"},
    "/mobile/openai/responses": {"post"},
    "/mobile/jiandaoyun/v5/app/entry/list": {"post"},
    "/mobile/jiandaoyun/v5/app/entry/widget/list": {"post"},
    "/mobile/jiandaoyun/v5/app/entry/data/list": {"post"},
    "/mobile/media/{token}": {"get"},
    "/device-enrollments": {"post"},
    "/device-enrollments/{id}": {"get"},
    "/android-devices": {"get"},
    "/android-devices/{id}/approve": {"post"},
    "/android-devices/{id}/revoke": {"post"},
    "/android-devices/{id}/rename": {"post"},
    "/operation-logs": {"get"},
    "/operation-logs/export": {"get"},
    "/settings/site": {"get", "patch"},
    "/settings/network": {"get", "patch"},
    "/backups": {"get", "post"},
    "/backups/{id}/restore": {"post"},
    "/ws/events": {"get"},
}

PAGINATED_OPERATION_IDS = {
    "listStations",
    "listTags",
    "listLowBatteryTags",
    "listAbnormalTags",
    "listProducts",
    "listBindings",
    "listAndroidDevices",
    "listOperationLogs",
    "listBackups",
}

IDEMPOTENT_OPERATION_IDS = {
    "startBroker",
    "stopBroker",
    "restartBroker",
    "createStation",
    "registerTag",
    "createProduct",
    "createProductImport",
    "commitProductImport",
    "createBinding",
    "removeBinding",
    "rebindTag",
    "commitAndroidBindingMigration",
    "createLightCommand",
    "clearStation",
    "createDeviceEnrollment",
    "approveAndroidDevice",
    "revokeAndroidDevice",
    "createBackup",
    "restoreBackup",
}

PUBLIC_OPERATION_IDS = {
    "loginAdmin",
    "getLiveness",
    "getReadiness",
    "createDeviceEnrollment",
    "getMobileMedia",
}

ANDROID_ONLY_OPERATION_IDS = {
    "previewAndroidBindingMigration",
    "commitAndroidBindingMigration",
    "proxyMobileOpenAiResponses",
    "proxyMobileJianDaoYunEntryList",
    "proxyMobileJianDaoYunWidgetList",
    "proxyMobileJianDaoYunDataList",
}

NO_HEADER_AUTH_OPERATION_IDS = {"getMobileMedia"}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def iter_operations(spec: Dict[str, Any]) -> Iterable[Tuple[str, str, Dict[str, Any]]]:
    for path, path_item in spec["paths"].items():
        for method, operation in path_item.items():
            if method in HTTP_METHODS:
                yield path, method, operation


def parameter_refs(path_item: Dict[str, Any], operation: Dict[str, Any]) -> Set[str]:
    refs: Set[str] = set()
    for parameter in path_item.get("parameters", []) + operation.get("parameters", []):
        if "$ref" in parameter:
            refs.add(parameter["$ref"])
    return refs


def validate_openapi() -> int:
    path = CONTRACTS_DIR / "openapi.yaml"
    spec = yaml.safe_load(path.read_text(encoding="utf-8"))
    require(isinstance(spec, dict), "OpenAPI root must be an object")
    require(spec.get("openapi") == "3.1.0", "OpenAPI version must be 3.1.0")
    validate_spec(spec)

    actual = {
        route: {method for method in path_item if method in HTTP_METHODS}
        for route, path_item in spec["paths"].items()
    }
    require(actual == EXPECTED_OPERATIONS, "OpenAPI route/method inventory does not match")

    operation_ids: List[str] = []
    for route, method, operation in iter_operations(spec):
        operation_id = operation.get("operationId")
        require(isinstance(operation_id, str), f"{method.upper()} {route} lacks operationId")
        operation_ids.append(operation_id)
        require(operation.get("responses"), f"{operation_id} lacks responses")

        if operation_id not in PUBLIC_OPERATION_IDS:
            require(operation.get("security"), f"{operation_id} must declare authentication")

        if operation_id in ANDROID_ONLY_OPERATION_IDS:
            require(
                operation.get("security") == [{"AndroidBearer": []}],
                f"{operation_id} must require only AndroidBearer",
            )

        if operation_id in NO_HEADER_AUTH_OPERATION_IDS:
            require(
                operation.get("security") == [],
                f"{operation_id} must use only its signed path token",
            )

        for alternative in operation.get("security", []):
            if "AdminSession" in alternative and method in {"post", "put", "patch", "delete"}:
                require(
                    "CsrfToken" in alternative,
                    f"{operation_id} admin write must require CsrfToken with AdminSession",
                )

        refs = parameter_refs(spec["paths"][route], operation)
        if operation_id in PAGINATED_OPERATION_IDS:
            for name in ("Page", "PageSize", "Sort"):
                require(
                    f"#/components/parameters/{name}" in refs,
                    f"{operation_id} must include {name}",
                )
        if operation_id in IDEMPOTENT_OPERATION_IDS:
            require(
                "#/components/parameters/IdempotencyKey" in refs,
                f"{operation_id} must require Idempotency-Key",
            )

    require(len(operation_ids) == len(set(operation_ids)), "operationId values must be unique")
    require(len(operation_ids) == 63, "OpenAPI must define exactly 63 operations")
    require(set(IDEMPOTENT_OPERATION_IDS).issubset(operation_ids), "unknown idempotent operation")
    require(
        ANDROID_ONLY_OPERATION_IDS.issubset(operation_ids),
        "unknown Android-only operation",
    )
    return len(operation_ids)


def validate_event_schema() -> int:
    schema = read_json(CONTRACTS_DIR / "events.schema.json")
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    examples = schema.get("examples", [])
    require(len(examples) == 9, "events.schema.json must include all nine event examples")

    event_types: Set[str] = set()
    for index, example in enumerate(examples):
        errors = sorted(validator.iter_errors(example), key=lambda error: list(error.path))
        require(not errors, f"event example {index} is invalid: {errors[0].message if errors else ''}")
        event_types.add(example["event_type"])

    require(len(event_types) == 9, "event examples must cover nine unique event types")

    extra_envelope = copy.deepcopy(examples[0])
    extra_envelope["unexpected"] = True
    require(not validator.is_valid(extra_envelope), "event envelope must reject unknown fields")

    extra_payload = copy.deepcopy(examples[0])
    extra_payload["payload"]["unexpected"] = True
    require(not validator.is_valid(extra_payload), "event payload must reject unknown fields")

    unknown_type = copy.deepcopy(examples[0])
    unknown_type["event_type"] = "unknown.event"
    require(not validator.is_valid(unknown_type), "unknown event types must be rejected")
    return len(examples)


def walk_values(value: Any) -> Iterable[Tuple[str, Any]]:
    if isinstance(value, dict):
        for key, child in value.items():
            yield key, child
            yield from walk_values(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk_values(child)


def assert_no_credential_fields(payload: Any, fixture_id: str) -> None:
    forbidden = {"password", "token", "secret", "credential", "authorization"}
    for key, _ in walk_values(payload):
        require(key.lower() not in forbidden, f"{fixture_id} contains credential field {key}")


def validate_heartbeat(payload: Dict[str, Any]) -> None:
    require(STATION_ID_RE.fullmatch(payload.get("ID", "")) is not None, "invalid heartbeat ID")
    require(isinstance(payload.get("Heartbeat"), int) and payload["Heartbeat"] > 0, "invalid heartbeat interval")
    parameters = payload.get("Parameters")
    require(
        isinstance(parameters, list) and len(parameters) == 2,
        "heartbeat Parameters must contain exactly username and redacted password slots",
    )
    require(
        isinstance(parameters[0], str) and bool(parameters[0].strip()),
        "heartbeat username slot must be non-empty",
    )
    require(parameters[1] == "<redacted>", "heartbeat credential slot must be redacted")


def validate_result(payload: Dict[str, Any]) -> None:
    require(STATION_ID_RE.fullmatch(payload.get("ID", "")) is not None, "invalid result ID")
    results = payload.get("Results")
    require(isinstance(results, list) and results, "result fixture must contain an acknowledgement")
    item = results[0]
    require(TAG_ID_RE.fullmatch(item.get("TagID", "")) is not None, "invalid result tag ID")
    require(item.get("ResultType") == 254, "result fixture must preserve communication result type")
    require(item.get("RfPowerSend") == -256, "result fixture must preserve no-RF sentinel")
    require("CommandID" not in item and "CommandID" not in payload, "eStation result must not invent a command ID")


def validate_task(payload: Dict[str, Any], clear: bool) -> None:
    require(payload.get("Time") == (0 if clear else 1), "task Time does not match fixture kind")
    items = payload.get("Items")
    require(isinstance(items, list) and 1 <= len(items) <= 20, "fixture task batch must contain 1..20 items")
    for item in items:
        require(TAG_ID_RE.fullmatch(item.get("TagID", "")) is not None, "invalid task tag ID")
        require("CommandID" not in item, "eStation task must not invent a command ID")
        colors = item.get("Colors")
        require(isinstance(colors, list) and colors, "task must contain Colors")
        require(all(set(color) == {"R", "G", "B"} for color in colors), "RGB object shape changed")
        if clear:
            require(item.get("Beep") is False, "clear task must disable beep")
            require(item.get("Flashing") is None, "clear task must encode Flashing as null")
            require(all(not any(color.values()) for color in colors), "clear task must disable RGB")
        else:
            require(item.get("Beep") is True, "light task must enable beep")
            require(item.get("Flashing") is True, "light task must enable flashing")
            require(any(any(color.values()) for color in colors), "light task must enable a color")


def validate_mqtt_fixtures() -> int:
    manifest_schema = read_json(FIXTURES_DIR / "manifest.schema.json")
    manifest = read_json(FIXTURES_DIR / "manifest.json")
    Draft202012Validator.check_schema(manifest_schema)
    validator = Draft202012Validator(manifest_schema, format_checker=FormatChecker())
    errors = sorted(validator.iter_errors(manifest), key=lambda error: list(error.path))
    require(not errors, f"MQTT manifest is invalid: {errors[0].message if errors else ''}")

    fixtures = manifest["fixtures"]
    ids = [entry["id"] for entry in fixtures]
    files = [entry["file"] for entry in fixtures]
    require(len(ids) == len(set(ids)), "MQTT fixture IDs must be unique")
    require(len(files) == len(set(files)), "MQTT fixture files must be unique")

    listed = set(files)
    on_disk = {
        path.name
        for path in FIXTURES_DIR.iterdir()
        if path.suffix in {".json", ".txt"} and path.name not in {"manifest.json", "manifest.schema.json"}
    }
    require(listed == on_disk, "manifest and MQTT fixture files differ")

    validators = {
        "heartbeat": validate_heartbeat,
        "result_ack": validate_result,
        "light_command": lambda payload: validate_task(payload, clear=False),
        "clear_command": lambda payload: validate_task(payload, clear=True),
    }

    for entry in fixtures:
        payload_path = FIXTURES_DIR / entry["file"]
        raw = payload_path.read_text(encoding="utf-8")
        if not entry["valid_json"]:
            try:
                json.loads(raw)
            except json.JSONDecodeError:
                continue
            raise AssertionError(f"{entry['id']} is marked malformed but parses as JSON")

        payload = json.loads(raw)
        require(isinstance(payload, dict), f"{entry['id']} payload must be an object")
        assert_no_credential_fields(payload, entry["id"])
        validators[entry["payload_kind"]](payload)

        topic_parts = entry["topic"].split("/")
        require(topic_parts[2] == manifest["station_id"], f"{entry['id']} topic station mismatch")
        if entry["direction"] == "station_to_backend":
            require(payload["ID"] == topic_parts[2], f"{entry['id']} payload station mismatch")

    return len(fixtures)


def main() -> int:
    operations = validate_openapi()
    events = validate_event_schema()
    fixtures = validate_mqtt_fixtures()
    print(f"OpenAPI: valid ({operations} operations)")
    print(f"WebSocket schema: valid ({events} event examples)")
    print(f"MQTT fixtures: valid ({fixtures} payloads, including malformed rejection)")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as exc:
        print(f"Contract validation failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
