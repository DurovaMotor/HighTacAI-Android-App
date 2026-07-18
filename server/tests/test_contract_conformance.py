from __future__ import annotations

from pathlib import Path

import yaml
from jsonschema import Draft202012Validator, FormatChecker

from conftest import seed_online_station_and_tags

ROOT = Path(__file__).resolve().parents[2]
OPENAPI = yaml.safe_load(
    (ROOT / "contracts" / "openapi.yaml").read_text(encoding="utf-8")
)
HTTP_METHODS = {"get", "post", "put", "patch", "delete"}


def _validate(schema_name: str, payload: object) -> None:
    schema = {
        "$ref": f"#/components/schemas/{schema_name}",
        "components": OPENAPI["components"],
    }
    Draft202012Validator(
        schema, format_checker=FormatChecker()
    ).validate(payload)


def test_fastapi_route_inventory_matches_canonical_openapi(harness) -> None:
    generated = harness.app.openapi()
    actual = {
        (path.removeprefix("/api/v1"), method)
        for path, operations in generated["paths"].items()
        if path.startswith("/api/v1")
        for method in operations
        if method in HTTP_METHODS
    }
    expected = {
        (path, method)
        for path, operations in OPENAPI["paths"].items()
        for method in operations
        if method in HTTP_METHODS
    }
    assert actual == expected
    assert len(actual) == 63


def test_core_read_responses_validate_against_contract(harness) -> None:
    live = harness.client.get("/api/v1/health/live")
    ready = harness.client.get("/api/v1/health/ready")
    _validate("Liveness", live.json())
    _validate("Readiness", ready.json())

    login = harness.raw_login()
    _validate("LoginResponse", login.json())
    csrf = login.json()["csrf_token"]
    changed = harness.client.post(
        "/api/v1/auth/change-password",
        headers={"X-CSRF-Token": csrf},
        json={
            "current_password": "Adam",
            "new_password": "Test-password-123!",
        },
    )
    assert changed.status_code == 204
    harness.password = "Test-password-123!"
    me = harness.client.get("/api/v1/auth/me")
    _validate("AdminUser", me.json())

    endpoints = [
        ("/api/v1/dashboard/summary", "DashboardSummary"),
        ("/api/v1/dashboard/trends", "DashboardTrends"),
        ("/api/v1/broker/status", "BrokerStatus"),
        ("/api/v1/stations", "StationPage"),
        ("/api/v1/tags", "TagPage"),
        ("/api/v1/products", "ProductPage"),
        ("/api/v1/bindings", "BindingPage"),
        ("/api/v1/android-devices", "AndroidDevicePage"),
        ("/api/v1/operation-logs", "OperationLogPage"),
        ("/api/v1/settings/site", "SiteSettings"),
        ("/api/v1/settings/network", "NetworkSettings"),
        ("/api/v1/backups", "BackupPage"),
    ]
    for path, schema_name in endpoints:
        response = harness.client.get(path)
        assert response.status_code == 200, (path, response.text)
        _validate(schema_name, response.json())


def test_contract_error_envelope_is_strict(harness) -> None:
    response = harness.client.get("/api/v1/products")
    assert response.status_code == 401
    _validate("ErrorEnvelope", response.json())
    assert response.json()["error"]["details"] == []


def test_supported_page_sizes_are_parsed_from_query_strings(harness) -> None:
    harness.login()

    for page_size in (20, 50, 100):
        response = harness.client.get(
            f"/api/v1/stations?page=1&page_size={page_size}"
        )
        assert response.status_code == 200, response.text
        assert response.json()["pagination"]["page_size"] == page_size

    rejected = harness.client.get("/api/v1/stations?page=1&page_size=25")
    assert rejected.status_code == 422
    _validate("ErrorEnvelope", rejected.json())


def test_supported_broker_log_lengths_are_parsed_from_query_strings(harness) -> None:
    harness.login()

    for line_count in (50, 200, 500):
        response = harness.client.get(f"/api/v1/broker/logs?lines={line_count}")
        assert response.status_code == 200, response.text
        assert response.json()["requested_lines"] == line_count

    rejected = harness.client.get("/api/v1/broker/logs?lines=100")
    assert rejected.status_code == 422
    _validate("ErrorEnvelope", rejected.json())


def test_request_boundaries_and_filter_enums_match_contract(
    harness,
) -> None:
    too_long_login = harness.client.post(
        "/api/v1/auth/login",
        json={"username": "U" * 65, "password": "Adam"},
    )
    assert too_long_login.status_code == 422

    auth = harness.login()
    product_name = "P" * 256
    accepted = harness.client.post(
        "/api/v1/products",
        headers=harness.mutation_headers(auth),
        json={
            "product_code": "BOUNDARY-PRODUCT",
            "product_name": product_name,
        },
    )
    assert accepted.status_code == 201, accepted.text
    assert accepted.json()["product_name"] == product_name

    rejected = harness.client.post(
        "/api/v1/products",
        headers=harness.mutation_headers(auth),
        json={
            "product_code": "TOO-LONG-NAME",
            "product_name": "P" * 257,
        },
    )
    assert rejected.status_code == 422

    for path in (
        "/api/v1/stations?status=online",
        "/api/v1/products?source=WEB",
        "/api/v1/bindings?source=mobile",
        "/api/v1/android-devices?status=active",
        "/api/v1/operation-logs?actor_type=user",
        "/api/v1/backups?status=COMPLETE",
    ):
        response = harness.client.get(path)
        assert response.status_code == 422, (path, response.text)
        _validate("ErrorEnvelope", response.json())


def test_nonempty_inventory_and_command_dtos_match_contract(
    harness,
) -> None:
    tag_id = seed_online_station_and_tags(harness, 1)[0]
    auth = harness.login()
    station = harness.client.get("/api/v1/stations/90A9F1234567")
    checklist = harness.client.get(
        "/api/v1/stations/90A9F1234567/connection-checklist"
    )
    tag = harness.client.get(f"/api/v1/tags/{tag_id}")
    _validate("Station", station.json())
    _validate("ConnectionChecklist", checklist.json())
    _validate("TagDetail", tag.json())

    binding = harness.client.post(
        "/api/v1/bindings",
        headers=harness.mutation_headers(auth),
        json={
            "product_code": "CONTRACT-PART",
            "product_name": "Contract part",
            "tag_id": tag_id,
            "station_id": "90A9F1234567",
        },
    )
    assert binding.status_code == 201, binding.text
    _validate("Binding", binding.json())

    product = harness.client.get(
        f"/api/v1/products/{binding.json()['product_id']}"
    )
    _validate("ProductDetail", product.json())
    binding_page = harness.client.get("/api/v1/bindings")
    _validate("BindingPage", binding_page.json())

    command = harness.client.post(
        "/api/v1/light-commands",
        headers=harness.mutation_headers(auth),
        json={
            "action": "LIGHT_ON",
            "tag_ids": [tag_id],
            "color": "RED",
        },
    )
    assert command.status_code == 202, command.text
    _validate("LightCommand", command.json())
    command_detail = harness.client.get(
        f"/api/v1/light-commands/{command.json()['id']}"
    )
    _validate("LightCommand", command_detail.json())
