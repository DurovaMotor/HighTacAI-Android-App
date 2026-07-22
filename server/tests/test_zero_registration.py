from uuid import uuid4

from sqlalchemy import select

from hightac_platform.auth.context import (
    ANONYMOUS_ANDROID_ACTOR_ID,
)
from hightac_platform.db.models import (
    AndroidDevice,
    Binding,
    DeviceEnrollment,
    OperationLog,
)

from conftest import seed_online_station_and_tags


def test_tokenless_android_can_read_write_and_control_lights_without_registration(
    harness,
) -> None:
    tag_id = seed_online_station_and_tags(harness, 1)[0]
    installation_id = str(uuid4())
    headers = {
        "X-Android-Installation-Id": installation_id,
        "Idempotency-Key": str(uuid4()),
    }

    for path in (
        "/api/v1/broker/status",
        "/api/v1/stations",
        "/api/v1/tags",
        "/api/v1/products",
        "/api/v1/bindings",
    ):
        response = harness.client.get(
            path,
            headers={"X-Android-Installation-Id": installation_id},
        )
        assert response.status_code == 200, (path, response.text)

    binding = harness.client.post(
        "/api/v1/bindings",
        headers=headers,
        json={
            "product_code": "ZERO-REGISTRATION-PART",
            "tag_id": tag_id,
            "station_id": "90A9F1234567",
        },
    )
    assert binding.status_code == 201, binding.text

    command = harness.client.post(
        "/api/v1/light-commands",
        headers={
            "X-Android-Installation-Id": installation_id,
            "Idempotency-Key": str(uuid4()),
        },
        json={
            "action": "LIGHT_ON",
            "tag_ids": [tag_id],
            "color": "GREEN",
        },
    )
    assert command.status_code == 202, command.text

    with harness.runtime.database.session_factory() as session:
        stored = session.get(Binding, binding.json()["id"])
        assert stored.actor_type == "ANDROID"
        assert stored.actor_id == installation_id
        audit = session.scalar(
            select(OperationLog)
            .where(
                OperationLog.event_type == "binding.created",
                OperationLog.actor_id == installation_id,
            )
            .order_by(OperationLog.created_at_ms.desc())
        )
        assert audit is not None
        assert audit.actor_name == f"Android installation {installation_id}"
        assert session.query(AndroidDevice).count() == 0
        assert session.query(DeviceEnrollment).count() == 0


def test_missing_installation_id_uses_fixed_anonymous_android_actor(harness) -> None:
    tag_id = seed_online_station_and_tags(harness, 1)[0]
    created = harness.client.post(
        "/api/v1/bindings",
        headers={"Idempotency-Key": str(uuid4())},
        json={
            "product_code": "FIXED-ANONYMOUS-PART",
            "tag_id": tag_id,
            "station_id": "90A9F1234567",
        },
    )
    assert created.status_code == 201, created.text
    with harness.runtime.database.session_factory() as session:
        binding = session.get(Binding, created.json()["id"])
        assert binding.actor_type == "ANDROID"
        assert binding.actor_id == ANONYMOUS_ANDROID_ACTOR_ID


def test_installation_header_isolates_idempotency_between_tokenless_phones(
    harness,
) -> None:
    first_tag, second_tag = seed_online_station_and_tags(harness, 2)
    shared_key = str(uuid4())
    requests = (
        (str(uuid4()), first_tag, "PHONE-ONE-PART"),
        (str(uuid4()), second_tag, "PHONE-TWO-PART"),
    )
    responses = [
        harness.client.post(
            "/api/v1/bindings",
            headers={
                "X-Android-Installation-Id": installation_id,
                "Idempotency-Key": shared_key,
            },
            json={
                "product_code": product_code,
                "tag_id": tag_id,
                "station_id": "90A9F1234567",
            },
        )
        for installation_id, tag_id, product_code in requests
    ]
    assert [response.status_code for response in responses] == [201, 201]


def test_admin_views_stay_private_and_admin_cookie_cannot_bypass_csrf(harness) -> None:
    for path in (
        "/api/v1/dashboard/summary",
        "/api/v1/dashboard/trends",
        "/api/v1/tags/low-battery",
        "/api/v1/tags/abnormal",
        "/api/v1/android-devices",
        "/api/v1/operation-logs",
        "/api/v1/settings/site",
        "/api/v1/backups",
    ):
        response = harness.client.get(path)
        assert response.status_code == 401, (path, response.text)

    admin_write = harness.client.post(
        "/api/v1/products",
        headers={"Idempotency-Key": str(uuid4())},
        json={"product_code": "ADMIN-ONLY-PRODUCT"},
    )
    assert admin_write.status_code == 401

    # Cookie presence always selects the administrator path. A bad cookie
    # cannot downgrade the same browser request to anonymous Android access.
    harness.client.cookies.set(
        harness.settings.session_cookie_name,
        "invalid-administrator-session",
    )
    assert harness.client.get("/api/v1/products").status_code == 401
    harness.client.cookies.delete(harness.settings.session_cookie_name)
    assert harness.client.get("/api/v1/products").status_code == 200

    tag_id = seed_online_station_and_tags(harness, 1)[0]
    harness.login()
    no_csrf = harness.client.post(
        "/api/v1/bindings",
        headers={"Idempotency-Key": str(uuid4())},
        json={
            "product_code": "MUST-NOT-BYPASS-CSRF",
            "tag_id": tag_id,
            "station_id": "90A9F1234567",
        },
    )
    assert no_csrf.status_code == 403


def test_revoked_legacy_token_falls_back_to_anonymous_android_actor(harness) -> None:
    response = harness.client.get(
        "/api/v1/products",
        headers={
            "Authorization": "Bearer revoked-or-unknown-token",
            "X-Android-Installation-Id": str(uuid4()),
        },
    )
    assert response.status_code == 200


def test_android_binding_migration_needs_no_device_or_enrollment(harness) -> None:
    seed_online_station_and_tags(harness, 1)
    installation_id = str(uuid4())
    records = [
        {
            "client_record_key": "anonymous-migration",
            "product_code": "ANONYMOUS-MIGRATION",
            "product_name": "Anonymous migration",
            "tag_id": "AD1000000001",
            "station_id": "90A9F1234567",
        }
    ]
    identity_header = {"X-Android-Installation-Id": installation_id}
    preview = harness.client.post(
        "/api/v1/migrations/android-bindings/preview",
        headers=identity_header,
        json={"records": records},
    )
    assert preview.status_code == 200, preview.text
    committed = harness.client.post(
        "/api/v1/migrations/android-bindings/commit",
        headers={**identity_header, "Idempotency-Key": str(uuid4())},
        json={
            "records": records,
            "preview_token": preview.json()["preview_token"],
            "selected_duplicate_keys": [],
        },
    )
    assert committed.status_code == 200, committed.text
    assert committed.json()["summary"]["migrated_records"] == 1
    with harness.runtime.database.session_factory() as session:
        assert session.query(AndroidDevice).count() == 0
        assert session.query(DeviceEnrollment).count() == 0
