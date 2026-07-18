from sqlalchemy import select

from hightac_platform.db.models import AndroidDevice, DeviceEnrollment
from hightac_platform.domain.enums import DeviceStatus, EnrollmentStatus
from hightac_platform.utils import hash_token

from conftest import seed_online_station_and_tags

FINGERPRINT_HASH = "a" * 64
INSTALLATION_KEY_HASH = "b" * 64
ENROLLMENT_KEY = "2ec5fd45-0e4d-4be4-a6bd-3517118b64df"


def test_hashed_device_enrollment_one_time_token_and_authorization(
    harness,
) -> None:
    enrollment_body = {
        "fingerprint_hash": FINGERPRINT_HASH,
        "installation_key_hash": INSTALLATION_KEY_HASH,
        "manufacturer": "Test",
        "model": "Phone",
        "app_version": "1.0",
    }
    created = harness.client.post(
        "/api/v1/device-enrollments",
        headers={"Idempotency-Key": ENROLLMENT_KEY},
        json=enrollment_body,
    )
    assert created.status_code == 202, created.text
    credentials = created.json()
    repeated_create = harness.client.post(
        "/api/v1/device-enrollments",
        headers={"Idempotency-Key": ENROLLMENT_KEY},
        json=enrollment_body,
    )
    assert repeated_create.status_code == 202
    assert repeated_create.json() == credentials

    with harness.runtime.database.session_factory() as session:
        enrollment = session.get(DeviceEnrollment, credentials["id"])
        device = session.scalar(select(AndroidDevice))
        assert enrollment.install_secret_hash == INSTALLATION_KEY_HASH
        assert enrollment.poll_secret_hash != credentials["poll_secret"]
        assert device.fingerprint_hash == FINGERPRINT_HASH
        device_id = device.id

    admin_headers = harness.login()
    approved = harness.client.post(
        f"/api/v1/android-devices/{device_id}/approve",
        headers=harness.mutation_headers(admin_headers),
        json={"display_name": "Warehouse phone"},
    )
    assert approved.status_code == 200
    poll_headers = {"X-Enrollment-Secret": credentials["poll_secret"]}
    polled = harness.client.get(
        f"/api/v1/device-enrollments/{credentials['id']}",
        headers=poll_headers,
    )
    assert polled.status_code == 200
    assert polled.headers["Cache-Control"] == "no-store"
    assert polled.json()["status"] == "APPROVED"
    token = polled.json()["device_token"]
    assert token
    repeated = harness.client.get(
        f"/api/v1/device-enrollments/{credentials['id']}",
        headers=poll_headers,
    )
    assert repeated.json()["device_token"] is None
    assert repeated.json()["status"] == "APPROVED"

    with harness.runtime.database.session_factory() as session:
        device = session.get(AndroidDevice, device_id)
        first_token_hash = device.token_hash
        assert first_token_hash == hash_token(token, "device-token")
        assert first_token_hash != token

    harness.client.cookies.delete(harness.settings.session_cookie_name)
    bearer = {"Authorization": f"Bearer {token}"}
    assert (
        harness.client.get("/api/v1/products", headers=bearer).status_code
        == 200
    )
    assert (
        harness.client.get(
            "/api/v1/broker/status", headers=bearer
        ).status_code
        == 200
    )
    forbidden_admin_write = harness.client.post(
        "/api/v1/products",
        headers=harness.mutation_headers(bearer),
        json={"product_code": "ANDROID-CANNOT-CREATE"},
    )
    assert forbidden_admin_write.status_code == 401

    tag_id = seed_online_station_and_tags(harness, 1)[0]
    android_binding = harness.client.post(
        "/api/v1/bindings",
        headers=harness.mutation_headers(bearer),
        json={
            "product_code": "ANDROID-PART",
            "tag_id": tag_id,
            "station_id": "90A9F1234567",
        },
    )
    assert android_binding.status_code == 201
    android_unbind = harness.client.delete(
        f"/api/v1/bindings/{android_binding.json()['id']}",
        headers=harness.mutation_headers(bearer),
    )
    assert android_unbind.status_code == 200
    assert android_unbind.json()["is_active"] is False

    re_enrollment_body = {
        **enrollment_body,
        "installation_key_hash": "e" * 64,
    }
    re_enrolled = harness.client.post(
        "/api/v1/device-enrollments",
        headers={"Idempotency-Key": "39a79756-934d-438c-9d19-ffb615551e58"},
        json=re_enrollment_body,
    )
    assert re_enrolled.status_code == 202
    re_enrollment = re_enrolled.json()
    pending_poll = harness.client.get(
        f"/api/v1/device-enrollments/{re_enrollment['id']}",
        headers={"X-Enrollment-Secret": re_enrollment["poll_secret"]},
    )
    assert pending_poll.status_code == 200
    assert pending_poll.json()["status"] == EnrollmentStatus.PENDING.value
    assert pending_poll.json()["device_token"] is None

    with harness.runtime.database.session_factory() as session:
        assert session.get(AndroidDevice, device_id).token_hash == first_token_hash
        new_enrollment = session.get(DeviceEnrollment, re_enrollment["id"])
        assert new_enrollment.android_device_id != device_id
        replacement_device_id = new_enrollment.android_device_id
        replacement_device = session.get(AndroidDevice, replacement_device_id)
        assert replacement_device.status == DeviceStatus.PENDING.value
        assert replacement_device.token_hash is None
        assert session.query(AndroidDevice).count() == 2

    # Merely requesting another enrollment cannot revoke or replace the
    # already-approved installation's bearer token.
    assert harness.client.get("/api/v1/products", headers=bearer).status_code == 200

    replacement_approved = harness.client.post(
        f"/api/v1/android-devices/{replacement_device_id}/approve",
        headers=harness.mutation_headers(harness.login()),
        json={"display_name": "Reinstalled warehouse phone"},
    )
    assert replacement_approved.status_code == 200
    replacement_poll = harness.client.get(
        f"/api/v1/device-enrollments/{re_enrollment['id']}",
        headers={"X-Enrollment-Secret": re_enrollment["poll_secret"]},
    )
    replacement_token = replacement_poll.json()["device_token"]
    assert replacement_token
    replacement_bearer = {"Authorization": f"Bearer {replacement_token}"}
    assert harness.client.get(
        "/api/v1/products", headers=replacement_bearer
    ).status_code == 200
    assert harness.client.get("/api/v1/products", headers=bearer).status_code == 200

    revoke_admin = harness.login()
    revoked = harness.client.post(
        f"/api/v1/android-devices/{device_id}/revoke",
        headers=harness.mutation_headers(revoke_admin),
        json={"confirmation": "REVOKE DEVICE"},
    )
    assert revoked.status_code == 200
    assert (
        harness.client.get("/api/v1/products", headers=bearer).status_code
        == 401
    )
    assert harness.client.get(
        "/api/v1/products", headers=replacement_bearer
    ).status_code == 200


def test_enrollment_rejects_raw_android_identity(harness) -> None:
    response = harness.client.post(
        "/api/v1/device-enrollments",
        headers={"Idempotency-Key": ENROLLMENT_KEY},
        json={
            "android_id": "raw-id",
            "signing_digest": "raw-digest",
            "install_secret": "raw-secret",
            "manufacturer": "Test",
            "model": "Phone",
            "app_version": "1.0",
        },
    )
    assert response.status_code == 422
    fields = {
        detail["field"] for detail in response.json()["error"]["details"]
    }
    assert {"fingerprint_hash", "installation_key_hash"} <= fields


def test_legacy_token_issued_enrollment_maps_to_approved(harness) -> None:
    created = harness.client.post(
        "/api/v1/device-enrollments",
        headers={
            "Idempotency-Key": "a54e8497-3580-4c99-8fb4-2fdaf858f02b"
        },
        json={
            "fingerprint_hash": "c" * 64,
            "installation_key_hash": "d" * 64,
            "manufacturer": "M" * 128,
            "model": "N" * 128,
            "app_version": "1.0",
        },
    )
    assert created.status_code == 202, created.text
    with harness.runtime.database.session_factory() as session:
        enrollment = session.get(
            DeviceEnrollment, created.json()["id"]
        )
        device_id = enrollment.android_device_id

    approved = harness.client.post(
        f"/api/v1/android-devices/{device_id}/approve",
        headers=harness.mutation_headers(harness.login()),
        json={"display_name": "D" * 128},
    )
    assert approved.status_code == 200, approved.text
    assert approved.json()["display_name"] == "D" * 128
    assert approved.json()["manufacturer"] == "M" * 128
    assert approved.json()["model"] == "N" * 128

    with harness.runtime.database.session_factory() as session:
        enrollment = session.get(
            DeviceEnrollment, created.json()["id"]
        )
        enrollment.status = EnrollmentStatus.TOKEN_ISSUED.value
        session.commit()

    polled = harness.client.get(
        f"/api/v1/device-enrollments/{created.json()['id']}",
        headers={
            "X-Enrollment-Secret": created.json()["poll_secret"]
        },
    )
    assert polled.status_code == 200
    assert polled.headers["Cache-Control"] == "no-store"
    assert polled.json()["status"] == "APPROVED"
    assert polled.json()["device_token"] is None
