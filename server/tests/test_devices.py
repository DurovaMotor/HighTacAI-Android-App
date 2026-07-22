from uuid import uuid4

from hightac_platform.db.models import AndroidDevice, DeviceEnrollment
from hightac_platform.domain.enums import DeviceStatus, EnrollmentStatus
from hightac_platform.utils import hash_token

from conftest import seed_online_station_and_tags

FINGERPRINT_HASH = "a" * 64
INSTALLATION_KEY_HASH = "b" * 64
ENROLLMENT_KEY = "2ec5fd45-0e4d-4be4-a6bd-3517118b64df"


def _enrollment_body(
    *,
    fingerprint_hash: str = FINGERPRINT_HASH,
    installation_key_hash: str = INSTALLATION_KEY_HASH,
) -> dict[str, str]:
    return {
        "fingerprint_hash": fingerprint_hash,
        "installation_key_hash": installation_key_hash,
        "manufacturer": "Test",
        "model": "Phone",
        "app_version": "1.0",
    }


def _create_enrollment(harness, *, key: str = ENROLLMENT_KEY, **body_overrides):
    body = {**_enrollment_body(), **body_overrides}
    return harness.client.post(
        "/api/v1/device-enrollments",
        headers={"Idempotency-Key": key},
        json=body,
    )


def test_enrollment_automatically_approves_and_authorizes_all_mobile_features(
    harness,
) -> None:
    created = _create_enrollment(harness)
    assert created.status_code == 202, created.text
    assert created.headers["Cache-Control"] == "no-store"
    credentials = created.json()
    assert credentials["status"] == EnrollmentStatus.APPROVED.value
    assert credentials["device_id"]
    token = credentials["device_token"]
    assert token

    # An idempotent retry must not disclose the one-time token again.
    repeated_create = _create_enrollment(harness)
    assert repeated_create.status_code == 202
    assert repeated_create.json() == {
        **credentials,
        "device_token": None,
    }

    with harness.runtime.database.session_factory() as session:
        enrollment = session.get(DeviceEnrollment, credentials["id"])
        device = session.get(AndroidDevice, credentials["device_id"])
        assert enrollment.install_secret_hash == INSTALLATION_KEY_HASH
        assert enrollment.poll_secret_hash != credentials["poll_secret"]
        assert enrollment.status == EnrollmentStatus.APPROVED.value
        assert enrollment.approved_at_ms is not None
        assert enrollment.token_issued_at_ms is not None
        assert device.fingerprint_hash == FINGERPRINT_HASH
        assert device.status == DeviceStatus.APPROVED.value
        assert device.approved_at_ms is not None
        assert device.token_hash == hash_token(token, "device-token")
        assert device.token_hash != token

    poll_headers = {"X-Enrollment-Secret": credentials["poll_secret"]}
    polled = harness.client.get(
        f"/api/v1/device-enrollments/{credentials['id']}",
        headers=poll_headers,
    )
    assert polled.status_code == 200
    assert polled.headers["Cache-Control"] == "no-store"
    assert polled.json()["status"] == EnrollmentStatus.APPROVED.value
    assert polled.json()["device_token"] is None

    harness.client.cookies.delete(harness.settings.session_cookie_name)
    bearer = {"Authorization": f"Bearer {token}"}
    assert harness.client.get("/api/v1/products", headers=bearer).status_code == 200
    assert harness.client.get(
        "/api/v1/broker/status", headers=bearer
    ).status_code == 200

    # Automatic enrollment grants Android application permissions, not admin
    # account privileges or access to protected administrator mutations.
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


def test_poll_automatically_upgrades_historical_pending_enrollment(harness) -> None:
    created = _create_enrollment(harness)
    assert created.status_code == 202, created.text
    credentials = created.json()

    # Simulate a database upgraded from the manual-approval release.
    with harness.runtime.database.session_factory() as session:
        enrollment = session.get(DeviceEnrollment, credentials["id"])
        device = session.get(AndroidDevice, credentials["device_id"])
        enrollment.status = EnrollmentStatus.PENDING.value
        enrollment.approved_at_ms = None
        enrollment.token_issued_at_ms = None
        device.status = DeviceStatus.PENDING.value
        device.approved_at_ms = None
        device.token_hash = None
        session.commit()

    wrong_secret = harness.client.get(
        f"/api/v1/device-enrollments/{credentials['id']}",
        headers={"X-Enrollment-Secret": "x" * 43},
    )
    assert wrong_secret.status_code == 401
    with harness.runtime.database.session_factory() as session:
        assert session.get(
            DeviceEnrollment, credentials["id"]
        ).status == EnrollmentStatus.PENDING.value

    polled = harness.client.get(
        f"/api/v1/device-enrollments/{credentials['id']}",
        headers={"X-Enrollment-Secret": credentials["poll_secret"]},
    )
    assert polled.status_code == 200, polled.text
    assert polled.json()["status"] == EnrollmentStatus.APPROVED.value
    replacement_token = polled.json()["device_token"]
    assert replacement_token

    with harness.runtime.database.session_factory() as session:
        enrollment = session.get(DeviceEnrollment, credentials["id"])
        device = session.get(AndroidDevice, credentials["device_id"])
        assert enrollment.status == EnrollmentStatus.APPROVED.value
        assert enrollment.approved_at_ms is not None
        assert enrollment.token_issued_at_ms is not None
        assert device.status == DeviceStatus.APPROVED.value
        assert device.approved_at_ms is not None
        assert device.token_hash == hash_token(replacement_token, "device-token")

    bearer = {"Authorization": f"Bearer {replacement_token}"}
    assert harness.client.get("/api/v1/products", headers=bearer).status_code == 200


def test_fresh_challenge_recovers_lost_token_response_without_revoking_old_token(
    harness,
) -> None:
    created = _create_enrollment(harness)
    assert created.status_code == 202, created.text
    first = created.json()
    first_bearer = {"Authorization": f"Bearer {first['device_token']}"}

    # A retry after a lost response confirms the enrollment but deliberately
    # does not disclose the one-time credential again.
    replay = _create_enrollment(harness)
    assert replay.status_code == 202
    assert replay.json()["status"] == EnrollmentStatus.APPROVED.value
    assert replay.json()["device_token"] is None

    # The client recovers by discarding the exhausted idempotency key and
    # submitting a fresh challenge. Existing installations and tokens are not
    # silently replaced by enrollment creation.
    recovered = _create_enrollment(harness, key=str(uuid4()))
    assert recovered.status_code == 202, recovered.text
    second = recovered.json()
    assert second["status"] == EnrollmentStatus.APPROVED.value
    assert second["device_id"] != first["device_id"]
    assert second["device_token"]
    second_bearer = {"Authorization": f"Bearer {second['device_token']}"}
    assert harness.client.get(
        "/api/v1/products", headers=first_bearer
    ).status_code == 200
    assert harness.client.get(
        "/api/v1/products", headers=second_bearer
    ).status_code == 200


def test_expired_idempotent_replay_requires_a_fresh_challenge(harness) -> None:
    created = _create_enrollment(harness)
    assert created.status_code == 202, created.text
    credentials = created.json()

    with harness.runtime.database.session_factory() as session:
        enrollment = session.get(DeviceEnrollment, credentials["id"])
        enrollment.expires_at_ms = 0
        session.commit()

    replay = _create_enrollment(harness)
    assert replay.status_code == 202
    assert replay.json()["status"] == EnrollmentStatus.EXPIRED.value
    assert replay.json()["device_id"] is None
    assert replay.json()["device_token"] is None

    recovered = _create_enrollment(harness, key=str(uuid4()))
    assert recovered.status_code == 202
    assert recovered.json()["status"] == EnrollmentStatus.APPROVED.value
    assert recovered.json()["device_token"]


def test_revoked_enrollment_stays_rejected_but_fresh_enrollment_is_authorized(
    harness,
) -> None:
    created = _create_enrollment(harness)
    assert created.status_code == 202, created.text
    credentials = created.json()
    old_bearer = {"Authorization": f"Bearer {credentials['device_token']}"}

    revoked = harness.client.post(
        f"/api/v1/android-devices/{credentials['device_id']}/revoke",
        headers=harness.mutation_headers(harness.login()),
        json={"confirmation": "REVOKE DEVICE"},
    )
    assert revoked.status_code == 200
    harness.client.cookies.delete(harness.settings.session_cookie_name)
    assert harness.client.get(
        "/api/v1/products", headers=old_bearer
    ).status_code == 200
    assert harness.client.get(
        "/api/v1/android-devices", headers=old_bearer
    ).status_code == 401

    # Possession of a revoked enrollment secret cannot silently restore the
    # revoked token or device record.
    revoked_poll = harness.client.get(
        f"/api/v1/device-enrollments/{credentials['id']}",
        headers={"X-Enrollment-Secret": credentials["poll_secret"]},
    )
    assert revoked_poll.status_code == 200
    assert revoked_poll.json()["status"] == "REJECTED"
    assert revoked_poll.json()["device_token"] is None
    revoked_replay = _create_enrollment(harness)
    assert revoked_replay.status_code == 202
    assert revoked_replay.json()["status"] == "REJECTED"
    assert revoked_replay.json()["device_token"] is None

    # A fresh enrollment challenge represents a new installation and needs no
    # administrator action, even when an older device record was revoked.
    replacement = _create_enrollment(harness, key=str(uuid4()))
    assert replacement.status_code == 202, replacement.text
    replacement_credentials = replacement.json()
    assert replacement_credentials["status"] == EnrollmentStatus.APPROVED.value
    assert replacement_credentials["device_id"] != credentials["device_id"]
    assert replacement_credentials["device_token"]
    replacement_bearer = {
        "Authorization": f"Bearer {replacement_credentials['device_token']}"
    }
    assert harness.client.get(
        "/api/v1/products", headers=replacement_bearer
    ).status_code == 200
    assert harness.client.get(
        "/api/v1/products", headers=old_bearer
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
    created = _create_enrollment(
        harness,
        key="a54e8497-3580-4c99-8fb4-2fdaf858f02b",
        fingerprint_hash="c" * 64,
        installation_key_hash="d" * 64,
        manufacturer="M" * 128,
        model="N" * 128,
    )
    assert created.status_code == 202, created.text
    credentials = created.json()
    token = credentials["device_token"]

    with harness.runtime.database.session_factory() as session:
        enrollment = session.get(DeviceEnrollment, credentials["id"])
        enrollment.status = EnrollmentStatus.TOKEN_ISSUED.value
        session.commit()

    polled = harness.client.get(
        f"/api/v1/device-enrollments/{credentials['id']}",
        headers={"X-Enrollment-Secret": credentials["poll_secret"]},
    )
    assert polled.status_code == 200
    assert polled.headers["Cache-Control"] == "no-store"
    assert polled.json()["status"] == EnrollmentStatus.APPROVED.value
    assert polled.json()["device_token"] is None
    assert harness.client.get(
        "/api/v1/products",
        headers={"Authorization": f"Bearer {token}"},
    ).status_code == 200
