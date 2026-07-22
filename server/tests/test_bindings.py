from sqlalchemy import func, select

from hightac_platform.db.models import Binding

from conftest import seed_online_station_and_tags


STATION_ID = "90A9F1234567"


def test_one_product_many_tags_and_one_active_product_per_tag(
    harness,
) -> None:
    tag_ids = seed_online_station_and_tags(harness, 3)
    auth = harness.login()
    first_headers = harness.mutation_headers(
        auth, "2ec5fd45-0e4d-4be4-a6bd-3517118b64df"
    )
    first_body = {
        "product_code": " part-a ",
        "product_name": "Part A",
        "tag_id": tag_ids[0],
        "station_id": STATION_ID,
    }
    first = harness.client.post(
        "/api/v1/bindings", headers=first_headers, json=first_body
    )
    assert first.status_code == 201, first.text
    assert first.json()["product_code"] == "PART-A"

    repeated = harness.client.post(
        "/api/v1/bindings", headers=first_headers, json=first_body
    )
    assert repeated.status_code == 201
    assert repeated.json() == first.json()

    second = harness.client.post(
        "/api/v1/bindings",
        headers=harness.mutation_headers(auth),
        json={
            "product_code": "PART-A",
            "tag_id": tag_ids[1],
            "station_id": STATION_ID,
        },
    )
    assert second.status_code == 201
    assert second.json()["product_id"] == first.json()["product_id"]

    conflict = harness.client.post(
        "/api/v1/bindings",
        headers=harness.mutation_headers(auth),
        json={
            "product_code": "PART-B",
            "tag_id": tag_ids[0],
            "station_id": STATION_ID,
        },
    )
    assert conflict.status_code == 409

    reused_key_different_request = harness.client.post(
        "/api/v1/bindings",
        headers=first_headers,
        json={
            "product_code": "PART-A",
            "tag_id": tag_ids[2],
            "station_id": STATION_ID,
        },
    )
    assert reused_key_different_request.status_code == 409
    assert (
        reused_key_different_request.json()["error"]["code"]
        == "IDEMPOTENCY_KEY_REUSED"
    )

    with harness.runtime.database.session_factory() as session:
        active = session.scalar(
            select(func.count())
            .select_from(Binding)
            .where(Binding.is_active.is_(True))
        )
        assert active == 2


def test_unbind_is_soft_idempotent_and_rebind_is_contract_shaped(
    harness,
) -> None:
    tag_id = seed_online_station_and_tags(harness, 1)[0]
    auth = harness.login()
    created = harness.client.post(
        "/api/v1/bindings",
        headers=harness.mutation_headers(auth),
        json={
            "product_code": "P1",
            "tag_id": tag_id,
            "station_id": STATION_ID,
        },
    ).json()

    missing_confirmation = harness.client.delete(
        f"/api/v1/bindings/{created['id']}",
        headers=harness.mutation_headers(auth),
    )
    assert missing_confirmation.status_code == 403

    delete_headers = harness.mutation_headers(auth)
    delete_headers["confirmation"] = "UNBIND"
    deleted = harness.client.delete(
        f"/api/v1/bindings/{created['id']}",
        headers=delete_headers,
    )
    assert deleted.status_code == 200
    assert deleted.json()["is_active"] is False
    repeated = harness.client.delete(
        f"/api/v1/bindings/{created['id']}",
        headers=delete_headers,
    )
    assert repeated.status_code == 200
    assert repeated.json() == deleted.json()

    rebound_source = harness.client.post(
        "/api/v1/bindings",
        headers=harness.mutation_headers(auth),
        json={
            "product_code": "P1",
            "tag_id": tag_id,
            "station_id": STATION_ID,
        },
    ).json()
    rebound = harness.client.post(
        f"/api/v1/bindings/{rebound_source['id']}/rebind",
        headers=harness.mutation_headers(auth),
        json={
            "product_code": "P2",
            "product_name": "Replacement",
            "expected_tag_id": tag_id,
        },
    )
    assert rebound.status_code == 200, rebound.text
    assert set(rebound.json()) == {
        "removed_binding",
        "created_binding",
    }
    assert rebound.json()["removed_binding"]["is_active"] is False
    assert rebound.json()["created_binding"]["product_code"] == "P2"
