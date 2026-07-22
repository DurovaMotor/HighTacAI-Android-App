from __future__ import annotations

import hashlib
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from uuid import uuid4

import pytest
import yaml
from jsonschema import Draft202012Validator, FormatChecker
from sqlalchemy import func, select

from hightac_platform.db.models import (
    AndroidDevice,
    Binding,
    IdempotencyRecord,
    LightTag,
    OperationLog,
    Product,
    Site,
    Station,
)
from hightac_platform.domain.enums import (
    BindingSource,
    DeviceStatus,
    ProductSource,
)
from hightac_platform.utils import hash_token, new_id


STATION_A = "90A9F1234567"
STATION_B = "90A9F7654321"
ROOT = Path(__file__).resolve().parents[2]
OPENAPI = yaml.safe_load(
    (ROOT / "contracts" / "openapi.yaml").read_text(encoding="utf-8")
)


def test_preview_classifies_every_case_without_writes(harness) -> None:
    _seed_stations(harness)
    token, device_id = _seed_device(harness, "classification")
    _seed_binding(
        harness,
        product_code="IDENTICAL",
        tag_id="AD1000000002",
        station_id=STATION_A,
    )
    _seed_binding(
        harness,
        product_code="BOUND-ELSEWHERE",
        tag_id="AD1000000004",
        station_id=STATION_A,
    )
    _seed_tag(harness, "AD1000000005", STATION_B)
    body = {
        "records": [
            _record("migratable", " new-part ", "000000001", STATION_A.lower()),
            _record("identical", "identical", "AD1000000002", STATION_A),
            _record("duplicate-a", "DUPLICATE-A", "AD1000000003", STATION_A),
            _record("duplicate-b", "DUPLICATE-B", "AD1000000003", STATION_A),
            _record("bound", "OTHER-PART", "AD1000000004", STATION_A),
            _record("station-mismatch", "PART-5", "AD1000000005", STATION_A),
            _record(
                "station-not-found",
                "PART-6",
                "AD1000000006",
                "90A9F0000000",
            ),
        ]
    }
    before = _table_counts(harness)
    before_last_seen = _device_last_seen(harness, device_id)

    response = harness.client.post(
        "/api/v1/migrations/android-bindings/preview",
        headers=_bearer(token),
        json=body,
    )

    assert response.status_code == 200, response.text
    assert response.headers["Cache-Control"] == "no-store"
    payload = response.json()
    _validate_contract("AndroidBindingMigrationPreview", payload)
    assert payload["preview_token"]
    assert payload["summary"] == {
        "total_records": 7,
        "migratable": 1,
        "identical": 1,
        "duplicate_legacy_tag": 2,
        "tag_bound_to_different_product": 1,
        "station_mismatch": 1,
        "station_not_found": 1,
    }
    by_key = {record["client_record_key"]: record for record in payload["records"]}
    assert by_key["migratable"]["classification"] == "MIGRATABLE"
    assert by_key["migratable"]["product_code"] == "NEW-PART"
    assert by_key["migratable"]["tag_id"] == "AD1000000001"
    assert by_key["migratable"]["station_id"] == STATION_A
    assert by_key["identical"]["classification"] == "IDENTICAL"
    assert by_key["duplicate-a"]["classification"] == "DUPLICATE_LEGACY_TAG"
    assert by_key["duplicate-b"]["classification"] == "DUPLICATE_LEGACY_TAG"
    assert by_key["bound"]["classification"] == "TAG_BOUND_TO_DIFFERENT_PRODUCT"
    assert by_key["bound"]["authoritative_product_code"] == "BOUND-ELSEWHERE"
    assert by_key["station-mismatch"]["classification"] == "STATION_MISMATCH"
    assert by_key["station-mismatch"]["authoritative_station_id"] == STATION_B
    assert by_key["station-not-found"]["classification"] == "STATION_NOT_FOUND"
    assert _table_counts(harness) == before
    assert _device_last_seen(harness, device_id) == before_last_seen


def test_preview_enforces_record_bounds_and_supports_two_thousand(harness) -> None:
    _seed_stations(harness)
    token, _ = _seed_device(harness, "boundaries")
    records = [
        _record(
            f"record-{index}",
            f"BOUNDARY-{index}",
            f"AD1{index:09X}",
            STATION_A,
        )
        for index in range(2000)
    ]
    before = _table_counts(harness)

    accepted = harness.client.post(
        "/api/v1/migrations/android-bindings/preview",
        headers=_bearer(token),
        json={"records": records},
    )
    assert accepted.status_code == 200, accepted.text
    assert accepted.json()["summary"]["total_records"] == 2000
    assert accepted.json()["summary"]["migratable"] == 2000
    assert _table_counts(harness) == before

    for rejected_records in (
        [],
        records
        + [
            _record(
                "record-2000",
                "BOUNDARY-2000",
                "AD10000007D0",
                STATION_A,
            )
        ],
        [
            _record("same-key", "ONE", "AD10000007D1", STATION_A),
            _record("same-key", "TWO", "AD10000007D2", STATION_A),
        ],
    ):
        rejected = harness.client.post(
            "/api/v1/migrations/android-bindings/preview",
            headers=_bearer(token),
            json={"records": rejected_records},
        )
        assert rejected.status_code == 422
        assert rejected.json()["error"]["code"] == "VALIDATION_ERROR"
    assert _table_counts(harness) == before


def test_commit_is_atomic_replayable_and_rejects_key_body_mismatch(
    harness,
    monkeypatch,
) -> None:
    _seed_stations(harness)
    token, device_id = _seed_device(harness, "replay")
    _seed_binding(
        harness,
        product_code="ALREADY-THERE",
        tag_id="AD1000000013",
        station_id=STATION_A,
    )
    records = [
        _record("auto", "AUTO-PART", "AD1000000010", STATION_A),
        _record("choice-a", "CHOICE-A", "AD1000000011", STATION_A),
        _record("choice-b", "CHOICE-B", "AD1000000011", STATION_A),
        _record(
            "identical",
            "ALREADY-THERE",
            "AD1000000013",
            STATION_A,
        ),
    ]
    preview = _preview(harness, token, records)
    body = {
        "records": records,
        "preview_token": preview["preview_token"],
        "selected_duplicate_keys": ["choice-b"],
    }
    key = str(uuid4())
    events: list[tuple[str, str, dict[str, object]]] = []
    monkeypatch.setattr(
        harness.runtime.event_bus,
        "publish_threadsafe",
        lambda event_type, entity_id, payload: events.append(
            (event_type, entity_id, payload)
        ),
    )

    committed = harness.client.post(
        "/api/v1/migrations/android-bindings/commit",
        headers={**_bearer(token), "Idempotency-Key": key},
        json=body,
    )

    assert committed.status_code == 200, committed.text
    assert committed.headers["Cache-Control"] == "no-store"
    payload = committed.json()
    _validate_contract("AndroidBindingMigrationCommit", payload)
    assert payload["summary"] == {
        "total_records": 4,
        "migrated_records": 2,
        "identical_records": 1,
        "skipped_records": 1,
        "created_products": 2,
        "created_tags": 2,
        "created_bindings": 2,
    }
    outcomes = {item["client_record_key"]: item for item in payload["records"]}
    assert outcomes["auto"]["outcome"] == "MIGRATED"
    assert outcomes["choice-a"]["outcome"] == "SKIPPED"
    assert outcomes["choice-b"]["outcome"] == "MIGRATED"
    assert outcomes["identical"]["outcome"] == "IDENTICAL"
    assert {binding["source"] for binding in payload["bindings"]} == {
        BindingSource.MIGRATION.value
    }
    assert len(events) == 2

    after_first = _table_counts(harness)
    replayed = harness.client.post(
        "/api/v1/migrations/android-bindings/commit",
        headers={**_bearer(token), "Idempotency-Key": key},
        json=body,
    )
    assert replayed.status_code == 200, replayed.text
    assert replayed.json() == payload
    assert _table_counts(harness) == after_first
    assert len(events) == 2

    mismatched = harness.client.post(
        "/api/v1/migrations/android-bindings/commit",
        headers={**_bearer(token), "Idempotency-Key": key},
        json={**body, "selected_duplicate_keys": ["choice-a"]},
    )
    assert mismatched.status_code == 409
    assert mismatched.json()["error"]["code"] == "IDEMPOTENCY_KEY_REUSED"
    assert _table_counts(harness) == after_first
    assert len(events) == 2

    with harness.runtime.database.session_factory() as session:
        migrated = list(
            session.scalars(
                select(Binding).where(Binding.source == BindingSource.MIGRATION.value)
            )
        )
        assert len(migrated) == 2
        assert {binding.actor_id for binding in migrated} == {device_id}
        assert (
            session.scalar(select(Product).where(Product.product_code == "CHOICE-A"))
            is None
        )


def test_commit_rejects_token_input_mismatch_and_stale_state_before_writes(
    harness,
) -> None:
    _seed_stations(harness)
    token, _ = _seed_device(harness, "stale")
    records = [_record("stale-record", "STALE-PART", "AD1000000020", STATION_A)]
    preview = _preview(harness, token, records)

    mismatched = harness.client.post(
        "/api/v1/migrations/android-bindings/commit",
        headers={**_bearer(token), "Idempotency-Key": str(uuid4())},
        json={
            "records": [
                _record(
                    "stale-record",
                    "CHANGED-PART",
                    "AD1000000020",
                    STATION_A,
                )
            ],
            "preview_token": preview["preview_token"],
            "selected_duplicate_keys": [],
        },
    )
    assert mismatched.status_code == 409
    assert mismatched.json()["error"]["code"] == "MIGRATION_PREVIEW_MISMATCH"

    malformed = harness.client.post(
        "/api/v1/migrations/android-bindings/commit",
        headers={**_bearer(token), "Idempotency-Key": str(uuid4())},
        json={
            "records": records,
            "preview_token": f"{'a' * 33}.{'b' * 43}",
            "selected_duplicate_keys": [],
        },
    )
    assert malformed.status_code == 409
    assert malformed.json()["error"]["code"] == "MIGRATION_PREVIEW_MISMATCH"

    other_token, _ = _seed_device(harness, "wrong-device")
    wrong_device = harness.client.post(
        "/api/v1/migrations/android-bindings/commit",
        headers={**_bearer(other_token), "Idempotency-Key": str(uuid4())},
        json={
            "records": records,
            "preview_token": preview["preview_token"],
            "selected_duplicate_keys": [],
        },
    )
    assert wrong_device.status_code == 409
    assert wrong_device.json()["error"]["code"] == "MIGRATION_PREVIEW_MISMATCH"

    _seed_tag(harness, "AD1000000020", STATION_A)
    before = _table_counts(harness)
    stale = harness.client.post(
        "/api/v1/migrations/android-bindings/commit",
        headers={**_bearer(token), "Idempotency-Key": str(uuid4())},
        json={
            "records": records,
            "preview_token": preview["preview_token"],
            "selected_duplicate_keys": [],
        },
    )
    assert stale.status_code == 409
    assert stale.json()["error"]["code"] == "MIGRATION_PREVIEW_STALE"
    assert _table_counts(harness) == before
    with harness.runtime.database.session_factory() as session:
        assert (
            session.scalar(select(Product).where(Product.product_code == "STALE-PART"))
            is None
        )
        assert (
            session.scalar(select(Binding).where(Binding.tag_id == "AD1000000020"))
            is None
        )


def test_commit_rejects_invalid_duplicate_selections_without_writes(
    harness,
) -> None:
    _seed_stations(harness)
    token, _ = _seed_device(harness, "selection")
    records = [
        _record("duplicate-a", "DUP-A", "AD1000000030", STATION_A),
        _record("duplicate-b", "DUP-B", "AD1000000030", STATION_A),
        _record("ordinary", "ORDINARY", "AD1000000031", STATION_A),
    ]
    preview = _preview(harness, token, records)
    before = _table_counts(harness)

    not_duplicate = _commit(
        harness,
        token,
        records,
        preview["preview_token"],
        ["ordinary"],
    )
    assert not_duplicate.status_code == 422
    assert not_duplicate.json()["error"]["code"] == "INVALID_DUPLICATE_SELECTION"
    assert _table_counts(harness) == before

    multiple = _commit(
        harness,
        token,
        records,
        preview["preview_token"],
        ["duplicate-a", "duplicate-b"],
    )
    assert multiple.status_code == 422
    assert multiple.json()["error"]["code"] == "INVALID_DUPLICATE_SELECTION"
    assert _table_counts(harness) == before


def test_injected_failure_rolls_back_inventory_audits_and_replay_record(
    harness,
    monkeypatch,
) -> None:
    _seed_stations(harness)
    token, _ = _seed_device(harness, "rollback")
    records = [_record("rollback", "ROLLBACK-PART", "AD1000000040", STATION_A)]
    preview = _preview(harness, token, records)
    before = _table_counts(harness)
    events: list[object] = []
    monkeypatch.setattr(
        harness.runtime.event_bus,
        "publish_threadsafe",
        lambda *args: events.append(args),
    )

    def fail_commit(_session) -> None:
        raise RuntimeError("injected commit failure")

    monkeypatch.setattr(
        harness.runtime.migration_service,
        "_commit_transaction",
        fail_commit,
    )
    with pytest.raises(RuntimeError, match="injected commit failure"):
        _commit(
            harness,
            token,
            records,
            preview["preview_token"],
            [],
        )

    assert _table_counts(harness) == before
    assert events == []


def test_two_devices_racing_for_one_tag_get_complete_winner_and_stale_loser(
    harness,
    monkeypatch,
) -> None:
    _seed_stations(harness)
    first_token, first_device = _seed_device(harness, "race-first")
    second_token, second_device = _seed_device(harness, "race-second")
    tag_id = "AD1000000050"
    first_records = [_record("first", "RACE-FIRST", tag_id, STATION_A)]
    second_records = [_record("second", "RACE-SECOND", tag_id, STATION_A)]
    first_preview = _preview(harness, first_token, first_records)
    second_preview = _preview(harness, second_token, second_records)
    barrier = threading.Barrier(2)
    event_lock = threading.Lock()
    events: list[tuple[str, str]] = []

    def capture_event(event_type, entity_id, _payload) -> None:
        with event_lock:
            events.append((event_type, entity_id))

    monkeypatch.setattr(
        harness.runtime.event_bus,
        "publish_threadsafe",
        capture_event,
    )

    def race(
        token: str,
        records: list[dict[str, object]],
        preview_token: str,
    ):
        barrier.wait(timeout=5)
        return _commit(harness, token, records, preview_token, [])

    with ThreadPoolExecutor(max_workers=2) as executor:
        first_future = executor.submit(
            race,
            first_token,
            first_records,
            first_preview["preview_token"],
        )
        second_future = executor.submit(
            race,
            second_token,
            second_records,
            second_preview["preview_token"],
        )
        responses = [first_future.result(), second_future.result()]

    assert sorted(response.status_code for response in responses) == [200, 409]
    winner_index = next(
        index for index, response in enumerate(responses) if response.status_code == 200
    )
    loser = next(response for response in responses if response.status_code == 409)
    assert loser.json()["error"]["code"] == "MIGRATION_PREVIEW_STALE"
    winner_code = ["RACE-FIRST", "RACE-SECOND"][winner_index]
    loser_code = ["RACE-FIRST", "RACE-SECOND"][1 - winner_index]
    winner_device = [first_device, second_device][winner_index]

    with harness.runtime.database.session_factory() as session:
        binding = session.scalar(select(Binding).where(Binding.tag_id == tag_id))
        assert binding is not None
        assert binding.source == BindingSource.MIGRATION.value
        assert binding.actor_id == winner_device
        assert session.get(LightTag, tag_id) is not None
        product = session.get(Product, binding.product_id)
        assert product.product_code == winner_code
        assert (
            session.scalar(select(Product).where(Product.product_code == loser_code))
            is None
        )
        assert (
            session.scalar(
                select(func.count())
                .select_from(IdempotencyRecord)
                .where(IdempotencyRecord.route == "/migrations/android-bindings/commit")
            )
            == 1
        )
    assert len(events) == 1
    assert events[0][0] == "binding.created"


def _seed_stations(harness) -> None:
    with harness.runtime.database.session_factory() as session:
        site = session.scalar(select(Site).order_by(Site.created_at_ms).limit(1))
        assert site is not None
        for station_id in (STATION_A, STATION_B):
            if session.get(Station, station_id) is None:
                session.add(Station(station_id=station_id, site_id=site.id))
        session.commit()


def _seed_device(harness, name: str) -> tuple[str, str]:
    raw_token = f"migration-token-{name}"
    device = AndroidDevice(
        id=new_id(),
        fingerprint_hash=hashlib.sha256(name.encode("ascii")).hexdigest(),
        token_hash=hash_token(raw_token, "device-token"),
        display_name=name,
        manufacturer="Test",
        model="Phone",
        app_version="1.0",
        status=DeviceStatus.APPROVED.value,
        last_seen_at_ms=123456789,
    )
    with harness.runtime.database.session_factory() as session:
        session.add(device)
        session.commit()
    return raw_token, device.id


def _seed_tag(harness, tag_id: str, station_id: str) -> LightTag:
    with harness.runtime.database.session_factory() as session:
        existing = session.get(LightTag, tag_id)
        if existing is not None:
            return existing
        station = session.get(Station, station_id)
        assert station is not None
        tag = LightTag(
            tag_id=tag_id,
            site_id=station.site_id,
            station_id=station.station_id,
        )
        session.add(tag)
        session.commit()
        return tag


def _seed_binding(
    harness,
    *,
    product_code: str,
    tag_id: str,
    station_id: str,
) -> Binding:
    _seed_tag(harness, tag_id, station_id)
    with harness.runtime.database.session_factory() as session:
        station = session.get(Station, station_id)
        tag = session.get(LightTag, tag_id)
        product = session.scalar(
            select(Product).where(Product.product_code == product_code)
        )
        if product is None:
            product = Product(
                id=new_id(),
                product_code=product_code,
                product_name=product_code.title(),
                source=ProductSource.BINDING.value,
            )
            session.add(product)
            session.flush()
        binding = Binding(
            id=new_id(),
            product_id=product.id,
            tag_id=tag.tag_id,
            site_id=station.site_id,
            station_id=station.station_id,
            source=BindingSource.WEB.value,
            actor_type="SYSTEM",
            actor_id="test",
        )
        session.add(binding)
        session.commit()
        return binding


def _record(
    key: str,
    product_code: str,
    tag_id: str,
    station_id: str,
) -> dict[str, object]:
    return {
        "client_record_key": key,
        "product_code": product_code,
        "product_name": f"{product_code.strip()} name",
        "tag_id": tag_id,
        "station_id": station_id,
    }


def _preview(harness, token: str, records: list[dict[str, object]]) -> dict:
    response = harness.client.post(
        "/api/v1/migrations/android-bindings/preview",
        headers=_bearer(token),
        json={"records": records},
    )
    assert response.status_code == 200, response.text
    return response.json()


def _commit(
    harness,
    token: str,
    records: list[dict[str, object]],
    preview_token: str,
    selected_duplicate_keys: list[str],
):
    return harness.client.post(
        "/api/v1/migrations/android-bindings/commit",
        headers={
            **_bearer(token),
            "Idempotency-Key": str(uuid4()),
        },
        json={
            "records": records,
            "preview_token": preview_token,
            "selected_duplicate_keys": selected_duplicate_keys,
        },
    )


def _bearer(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _table_counts(harness) -> dict[str, int]:
    tables = (
        Product,
        LightTag,
        Binding,
        OperationLog,
        IdempotencyRecord,
        AndroidDevice,
    )
    with harness.runtime.database.session_factory() as session:
        return {
            table.__tablename__: session.scalar(select(func.count()).select_from(table))
            or 0
            for table in tables
        }


def _device_last_seen(harness, device_id: str) -> int:
    with harness.runtime.database.session_factory() as session:
        return session.get(AndroidDevice, device_id).last_seen_at_ms


def _validate_contract(schema_name: str, payload: object) -> None:
    Draft202012Validator(
        {
            "$ref": f"#/components/schemas/{schema_name}",
            "components": OPENAPI["components"],
        },
        format_checker=FormatChecker(),
    ).validate(payload)
