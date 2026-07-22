from __future__ import annotations

import threading
from collections import defaultdict, deque
from dataclasses import dataclass

from sqlalchemy import update
from sqlalchemy.orm import Session

from hightac_platform.auth.context import Actor
from hightac_platform.auth.security import (
    hash_password,
    password_needs_rehash,
    verify_dummy_password,
    verify_password,
)
from hightac_platform.config import Settings
from hightac_platform.db.models import AdminSession, AdminUser
from hightac_platform.db.repositories import AdminRepository
from hightac_platform.domain.enums import ActorType
from hightac_platform.domain.errors import (
    ForbiddenError,
    TooManyRequestsError,
    UnauthorizedError,
    ValidationError,
)
from hightac_platform.services.audit import append_operation_log
from hightac_platform.utils import hash_token, new_secret, utc_ms, verify_token


@dataclass(slots=True)
class LoginResult:
    session_token: str
    csrf_token: str
    actor: Actor
    must_change_password: bool
    session_expires_at_ms: int


class LoginRateLimiter:
    def __init__(self, settings: Settings) -> None:
        self.limit = settings.login_rate_limit_attempts
        self.window_ms = settings.login_rate_limit_window_seconds * 1000
        self._attempts: dict[str, deque[int]] = defaultdict(deque)
        self._lock = threading.Lock()

    def check(self, key: str, now_ms: int) -> None:
        with self._lock:
            attempts = self._attempts[key]
            while attempts and attempts[0] <= now_ms - self.window_ms:
                attempts.popleft()
            if len(attempts) >= self.limit:
                raise TooManyRequestsError("Too many login attempts. Try again later.")

    def failed(self, key: str, now_ms: int) -> None:
        with self._lock:
            self._attempts[key].append(now_ms)

    def succeeded(self, key: str) -> None:
        with self._lock:
            self._attempts.pop(key, None)


class AuthService:
    def __init__(self, settings: Settings, rate_limiter: LoginRateLimiter) -> None:
        self.settings = settings
        self.rate_limiter = rate_limiter

    def login(
        self,
        session: Session,
        *,
        username: str,
        password: str,
        client_ip: str | None,
        user_agent: str | None,
    ) -> LoginResult:
        now = utc_ms()
        rate_key = f"{client_ip or 'unknown'}:{username.strip().lower()}"
        self.rate_limiter.check(rate_key, now)
        repository = AdminRepository(session)
        user = repository.by_username(username)
        if user is None:
            verify_dummy_password(password)
        if user is None or not user.is_active or not verify_password(password, user.password_hash):
            self.rate_limiter.failed(rate_key, now)
            raise UnauthorizedError("Invalid username or password.")

        if password_needs_rehash(user.password_hash):
            user.password_hash = hash_password(password)
        user.last_login_at_ms = now
        session_token = new_secret()
        csrf_token = new_secret()
        admin_session = AdminSession(
            admin_user_id=user.id,
            token_hash=hash_token(session_token, "admin-session"),
            csrf_token_hash=hash_token(csrf_token, "csrf"),
            expires_at_ms=now + self.settings.session_ttl_seconds * 1000,
            client_ip=client_ip,
            user_agent=(user_agent or "")[:512] or None,
        )
        session.add(admin_session)
        append_operation_log(
            session,
            event_type="auth.login",
            actor=Actor(ActorType.ADMIN, user.id, user.username),
            result_summary={"success": True},
            client_ip=client_ip,
        )
        session.commit()
        self.rate_limiter.succeeded(rate_key)
        return LoginResult(
            session_token=session_token,
            csrf_token=csrf_token,
            actor=Actor(
                ActorType.ADMIN,
                user.id,
                user.username,
                session_id=admin_session.id,
                csrf_token_hash=admin_session.csrf_token_hash,
                must_change_password=user.must_change_password,
            ),
            must_change_password=user.must_change_password,
            session_expires_at_ms=admin_session.expires_at_ms,
        )

    def authenticate_session(self, session: Session, raw_token: str) -> Actor:
        now = utc_ms()
        row = AdminRepository(session).by_session_hash(hash_token(raw_token, "admin-session"))
        if row is None:
            raise UnauthorizedError("Authentication required.")
        admin_session, user = row
        if (
            admin_session.revoked_at_ms is not None
            or admin_session.expires_at_ms <= now
            or not user.is_active
        ):
            raise UnauthorizedError("Session has expired or was revoked.")
        admin_session.last_seen_at_ms = now
        session.commit()
        return Actor(
            ActorType.ADMIN,
            user.id,
            user.username,
            session_id=admin_session.id,
            csrf_token_hash=admin_session.csrf_token_hash,
            must_change_password=user.must_change_password,
        )

    def rotate_csrf(self, session: Session, actor: Actor) -> str:
        admin_session = session.get(AdminSession, actor.session_id)
        if admin_session is None or admin_session.revoked_at_ms is not None:
            raise UnauthorizedError("Authentication required.")
        csrf_token = new_secret()
        admin_session.csrf_token_hash = hash_token(csrf_token, "csrf")
        session.commit()
        return csrf_token

    def verify_csrf(self, actor: Actor, csrf_token: str | None) -> None:
        if not actor.csrf_token_hash or not csrf_token:
            raise ForbiddenError("A valid CSRF token is required.")
        if not verify_token(csrf_token, actor.csrf_token_hash, "csrf"):
            raise ForbiddenError("A valid CSRF token is required.")

    def logout(self, session: Session, actor: Actor) -> None:
        admin_session = session.get(AdminSession, actor.session_id)
        if admin_session and admin_session.revoked_at_ms is None:
            admin_session.revoked_at_ms = utc_ms()
        append_operation_log(session, event_type="auth.logout", actor=actor)
        session.commit()

    def change_password(
        self, session: Session, actor: Actor, current_password: str, new_password: str
    ) -> None:
        if len(new_password) < 10:
            raise ValidationError("New password must be at least 10 characters.")
        user = session.get(AdminUser, actor.actor_id)
        if user is None or not verify_password(current_password, user.password_hash):
            raise UnauthorizedError("Current password is incorrect.")
        user.password_hash = hash_password(new_password)
        user.must_change_password = False
        session.execute(
            update(AdminSession)
            .where(AdminSession.admin_user_id == user.id, AdminSession.id != actor.session_id)
            .values(revoked_at_ms=utc_ms())
        )
        append_operation_log(session, event_type="auth.password_changed", actor=actor)
        session.commit()
