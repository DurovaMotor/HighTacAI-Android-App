from __future__ import annotations

import base64
import hashlib
import hmac
import os
import threading
from dataclasses import dataclass

from sqlalchemy.orm import Session

from hightac_platform.auth.context import Actor
from hightac_platform.config import Settings
from hightac_platform.db.models import AndroidDevice, DeviceEnrollment
from hightac_platform.db.repositories import DeviceRepository
from hightac_platform.domain.enums import ActorType, DeviceStatus, EnrollmentStatus
from hightac_platform.domain.errors import (
    ConflictError,
    GoneError,
    IdempotencyConflictError,
    NotFoundError,
    UnauthorizedError,
)
from hightac_platform.services.audit import append_operation_log
from hightac_platform.utils import hash_token, new_secret, utc_ms, verify_token


@dataclass(slots=True)
class EnrollmentCredentials:
    enrollment_id: str
    poll_secret: str
    status: str
    expires_at_ms: int
    device_id: str | None = None
    device_token: str | None = None
    poll_after_seconds: int = 5


@dataclass(slots=True)
class EnrollmentPollResult:
    enrollment_id: str
    status: str
    display_name: str | None
    device_id: str | None
    expires_at_ms: int
    device_token: str | None = None


class DeviceService:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self._key_lock = threading.Lock()
        self._enrollment_key: bytes | None = None

    def create_enrollment(
        self,
        session: Session,
        *,
        fingerprint_hash: str,
        installation_key_hash: str,
        manufacturer: str,
        model: str,
        app_version: str,
        idempotency_key: str,
    ) -> EnrollmentCredentials:
        now = utc_ms()
        fingerprint_hash = fingerprint_hash.strip()
        installation_key_hash = installation_key_hash.strip()
        poll_secret = _derive_poll_secret(
            self._enrollment_signing_key(),
            idempotency_key,
            fingerprint_hash,
            installation_key_hash,
        )
        repository = DeviceRepository(session)
        idempotency_hash = hash_token(idempotency_key, "enrollment-idempotency")
        replay = repository.enrollment_by_idempotency_hash(idempotency_hash)
        if replay is not None:
            if (
                replay.fingerprint_hash != fingerprint_hash
                or replay.install_secret_hash != installation_key_hash
                or replay.manufacturer != _clean(manufacturer, 128)
                or replay.model != _clean(model, 128)
                or replay.app_version != _clean(app_version, 64)
            ):
                raise IdempotencyConflictError(
                    "The idempotency key belongs to a different enrollment request."
                )
            device = session.get(AndroidDevice, replay.android_device_id)
            if device is None:
                raise ConflictError("Enrollment has no Android device.")
            status, raw_token = self._authorize_enrollment(
                replay,
                device,
                now=now,
            )
            session.commit()
            return EnrollmentCredentials(
                enrollment_id=replay.id,
                poll_secret=poll_secret,
                status=status,
                expires_at_ms=replay.expires_at_ms,
                device_id=(
                    device.id
                    if status == EnrollmentStatus.APPROVED.value
                    else None
                ),
                device_token=raw_token,
            )

        # A fingerprint is an audit signal, not an authorization identity.
        # Every fresh enrollment challenge represents a distinct installation
        # without changing an existing device or token. Revoking one device
        # therefore does not permanently ban a later, fresh installation.
        device = AndroidDevice(
            fingerprint_hash=fingerprint_hash,
            manufacturer=_clean(manufacturer, 128),
            model=_clean(model, 128),
            app_version=_clean(app_version, 64),
            status=DeviceStatus.APPROVED.value,
            approved_at_ms=now,
        )
        session.add(device)
        session.flush()

        enrollment = DeviceEnrollment(
            fingerprint_hash=fingerprint_hash,
            install_secret_hash=installation_key_hash,
            poll_secret_hash=hash_token(poll_secret, "enrollment-poll"),
            challenge_hash=idempotency_hash,
            android_device_id=device.id,
            manufacturer=_clean(manufacturer, 128),
            model=_clean(model, 128),
            app_version=_clean(app_version, 64),
            status=EnrollmentStatus.APPROVED.value,
            expires_at_ms=now + self.settings.enrollment_ttl_seconds * 1000,
            approved_at_ms=now,
        )
        session.add(enrollment)
        raw_token = self._issue_device_token(device, enrollment, now=now)
        device.last_seen_at_ms = now
        session.commit()
        return EnrollmentCredentials(
            enrollment_id=enrollment.id,
            poll_secret=poll_secret,
            status=EnrollmentStatus.APPROVED.value,
            expires_at_ms=enrollment.expires_at_ms,
            device_id=device.id,
            device_token=raw_token,
        )

    def poll_enrollment(
        self,
        session: Session,
        *,
        enrollment_id: str,
        poll_secret: str,
    ) -> EnrollmentPollResult:
        now = utc_ms()
        enrollment = DeviceRepository(session).enrollment(enrollment_id)
        if enrollment is None:
            raise NotFoundError("Enrollment was not found.")
        if not verify_token(
            poll_secret, enrollment.poll_secret_hash, "enrollment-poll"
        ):
            raise UnauthorizedError("Enrollment secret is invalid.")
        if enrollment.expires_at_ms <= now:
            enrollment.status = EnrollmentStatus.EXPIRED.value
            session.commit()
            raise GoneError("Enrollment has expired.")

        device = session.get(AndroidDevice, enrollment.android_device_id)
        display_name = device.display_name if device else None
        if (
            enrollment.status == EnrollmentStatus.REVOKED.value
            or (device is not None and device.status == DeviceStatus.REVOKED.value)
        ):
            if enrollment.status != EnrollmentStatus.REVOKED.value:
                enrollment.status = EnrollmentStatus.REVOKED.value
                session.commit()
            return EnrollmentPollResult(
                enrollment.id,
                "REJECTED",
                display_name,
                enrollment.android_device_id,
                enrollment.expires_at_ms,
            )
        if enrollment.status == EnrollmentStatus.EXPIRED.value:
            raise GoneError("Enrollment has expired.")
        if enrollment.status == EnrollmentStatus.TOKEN_ISSUED.value:
            if device is None or device.status != DeviceStatus.APPROVED.value:
                raise ConflictError("Approved enrollment has no approved device.")
            return EnrollmentPollResult(
                enrollment.id,
                EnrollmentStatus.APPROVED.value,
                device.display_name,
                device.id,
                enrollment.expires_at_ms,
            )
        if device is None:
            raise ConflictError("Enrollment has no Android device.")
        status, raw_token = self._authorize_enrollment(
            enrollment,
            device,
            now=now,
        )
        session.commit()
        if status == "REJECTED":
            return EnrollmentPollResult(
                enrollment.id,
                status,
                device.display_name,
                device.id,
                enrollment.expires_at_ms,
            )
        if status == EnrollmentStatus.EXPIRED.value:
            raise GoneError("Enrollment has expired.")
        if status != EnrollmentStatus.APPROVED.value:
            raise ConflictError("Enrollment could not be automatically authorized.")
        if raw_token is None:
            return EnrollmentPollResult(
                enrollment.id,
                EnrollmentStatus.APPROVED.value,
                device.display_name,
                device.id,
                enrollment.expires_at_ms,
            )
        return EnrollmentPollResult(
            enrollment.id,
            EnrollmentStatus.APPROVED.value,
            device.display_name,
            device.id,
            enrollment.expires_at_ms,
            device_token=raw_token,
        )

    def _authorize_enrollment(
        self,
        enrollment: DeviceEnrollment,
        device: AndroidDevice,
        *,
        now: int,
    ) -> tuple[str, str | None]:
        """Advance a valid enrollment without requiring an administrator."""
        if enrollment.expires_at_ms <= now:
            enrollment.status = EnrollmentStatus.EXPIRED.value
            return EnrollmentStatus.EXPIRED.value, None
        # Revocation remains an explicit security boundary for the enrollment
        # and token that were revoked. A fresh enrollment creates a separate
        # device record and is automatically authorized.
        if (
            enrollment.status == EnrollmentStatus.REVOKED.value
            or device.status == DeviceStatus.REVOKED.value
        ):
            enrollment.status = EnrollmentStatus.REVOKED.value
            return "REJECTED", None
        if enrollment.status == EnrollmentStatus.TOKEN_ISSUED.value:
            if device.status != DeviceStatus.APPROVED.value:
                raise ConflictError("Approved enrollment has no approved device.")
            return EnrollmentStatus.APPROVED.value, None
        if enrollment.status not in {
            EnrollmentStatus.PENDING.value,
            EnrollmentStatus.APPROVED.value,
        }:
            raise ConflictError("Enrollment has an invalid authorization state.")

        if device.status != DeviceStatus.APPROVED.value:
            device.status = DeviceStatus.APPROVED.value
            device.approved_at_ms = now
            device.revoked_at_ms = None
        if enrollment.status != EnrollmentStatus.APPROVED.value:
            enrollment.status = EnrollmentStatus.APPROVED.value
            enrollment.approved_at_ms = now
        if enrollment.token_issued_at_ms is not None:
            return EnrollmentStatus.APPROVED.value, None
        return (
            EnrollmentStatus.APPROVED.value,
            self._issue_device_token(device, enrollment, now=now),
        )

    @staticmethod
    def _issue_device_token(
        device: AndroidDevice,
        enrollment: DeviceEnrollment,
        *,
        now: int,
    ) -> str:
        raw_token = new_secret(48)
        device.token_hash = hash_token(raw_token, "device-token")
        device.last_seen_at_ms = now
        enrollment.token_issued_at_ms = now
        return raw_token

    def authenticate_token(
        self,
        session: Session,
        raw_token: str,
        *,
        update_last_seen: bool = True,
    ) -> Actor:
        device = DeviceRepository(session).by_token_hash(hash_token(raw_token, "device-token"))
        if device is None or device.status != DeviceStatus.APPROVED.value:
            raise UnauthorizedError("Device token is invalid or revoked.")
        if update_last_seen:
            device.last_seen_at_ms = utc_ms()
            session.commit()
        model = " ".join(value for value in (device.manufacturer, device.model) if value) or None
        return Actor(
            ActorType.ANDROID,
            device.id,
            device.display_name or model or "Android device",
            device_model=model,
        )

    def derive_signing_key(self, purpose: str) -> bytes:
        normalized = purpose.strip()
        if not normalized:
            raise ValueError("Signing-key purpose is required.")
        return hmac.new(
            self._enrollment_signing_key(),
            normalized.encode("ascii"),
            hashlib.sha256,
        ).digest()

    def approve(self, session: Session, actor: Actor, device_id: str, display_name: str) -> AndroidDevice:
        device = session.get(AndroidDevice, device_id)
        if device is None:
            raise NotFoundError("Android device was not found.")
        now = utc_ms()
        device.display_name = _clean(display_name, 128)
        device.status = DeviceStatus.APPROVED.value
        device.approved_at_ms = now
        device.revoked_at_ms = None
        enrollments = session.query(DeviceEnrollment).filter(
            DeviceEnrollment.android_device_id == device.id,
            DeviceEnrollment.status == EnrollmentStatus.PENDING.value,
            DeviceEnrollment.expires_at_ms > now,
        )
        for enrollment in enrollments:
            enrollment.status = EnrollmentStatus.APPROVED.value
            enrollment.approved_at_ms = now
        append_operation_log(
            session,
            event_type="device.approved",
            actor=actor,
            result_summary={"device_id": device.id, "display_name": device.display_name},
        )
        session.commit()
        return device

    def _enrollment_signing_key(self) -> bytes:
        if self._enrollment_key is not None:
            return self._enrollment_key
        with self._key_lock:
            if self._enrollment_key is not None:
                return self._enrollment_key
            path = self.settings.device_enrollment_key_path
            path.parent.mkdir(parents=True, exist_ok=True)
            try:
                encoded = path.read_text(encoding="ascii").strip()
            except FileNotFoundError:
                encoded = new_secret(48)
                try:
                    descriptor = os.open(
                        path,
                        os.O_WRONLY | os.O_CREAT | os.O_EXCL,
                        0o600,
                    )
                except FileExistsError:
                    encoded = path.read_text(encoding="ascii").strip()
                else:
                    with os.fdopen(
                        descriptor, "w", encoding="ascii"
                    ) as handle:
                        handle.write(encoded)
            if len(encoded) < 32:
                raise RuntimeError(
                    "Device enrollment signing key is invalid."
                )
            self._enrollment_key = encoded.encode("ascii")
            return self._enrollment_key

    def revoke(self, session: Session, actor: Actor, device_id: str) -> AndroidDevice:
        device = session.get(AndroidDevice, device_id)
        if device is None:
            raise NotFoundError("Android device was not found.")
        device.status = DeviceStatus.REVOKED.value
        device.token_hash = None
        device.revoked_at_ms = utc_ms()
        for enrollment in session.query(DeviceEnrollment).filter(
            DeviceEnrollment.android_device_id == device.id,
            DeviceEnrollment.status.in_([
                EnrollmentStatus.PENDING.value,
                EnrollmentStatus.APPROVED.value,
                EnrollmentStatus.TOKEN_ISSUED.value,
            ]),
        ):
            enrollment.status = EnrollmentStatus.REVOKED.value
        append_operation_log(session, event_type="device.revoked", actor=actor)
        session.commit()
        return device

    def rename(self, session: Session, actor: Actor, device_id: str, display_name: str) -> AndroidDevice:
        device = session.get(AndroidDevice, device_id)
        if device is None:
            raise NotFoundError("Android device was not found.")
        device.display_name = _clean(display_name, 128)
        append_operation_log(session, event_type="device.renamed", actor=actor)
        session.commit()
        return device


def _clean(value: str | None, limit: int) -> str | None:
    cleaned = (value or "").strip()
    return cleaned[:limit] or None


def _derive_poll_secret(
    signing_key: bytes,
    idempotency_key: str,
    fingerprint_hash: str,
    installation_key_hash: str,
) -> str:
    material = (
        f"{idempotency_key}\0{fingerprint_hash}\0{installation_key_hash}"
    ).encode("ascii")
    digest = hmac.new(signing_key, material, hashlib.sha256).digest()
    return base64.urlsafe_b64encode(digest).rstrip(b"=").decode("ascii")
