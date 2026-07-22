from sqlalchemy import delete, func, select

from hightac_platform.auth.security import hash_password, verify_password
from hightac_platform.db.models import AdminSession, AdminUser
from hightac_platform.services.bootstrap import bootstrap_first_run
from hightac_platform.utils import hash_token


def test_argon2id_password_hashing() -> None:
    password_hash = hash_password("a strong password")

    assert password_hash.startswith("$argon2id$")
    assert "a strong password" not in password_hash
    assert verify_password("a strong password", password_hash)
    assert not verify_password("wrong password", password_hash)


def test_initial_password_blocks_management_writes_and_me_recovers_csrf(
    harness,
) -> None:
    login = harness.raw_login()
    assert login.status_code == 200
    body = login.json()
    assert body["user"]["must_change_password"] is True
    assert body["session_expires_at"].endswith("Z")
    csrf = body["csrf_token"]

    blocked = harness.client.post(
        "/api/v1/backups",
        headers=harness.mutation_headers({"X-CSRF-Token": csrf}),
    )
    assert blocked.status_code == 403
    assert blocked.json()["error"]["code"] == "PASSWORD_CHANGE_REQUIRED"
    assert blocked.json()["error"]["details"] == []

    me = harness.client.get("/api/v1/auth/me")
    assert me.status_code == 200
    recovered_csrf = me.headers["X-CSRF-Token"]
    assert recovered_csrf != csrf

    old_csrf = harness.client.post(
        "/api/v1/auth/change-password",
        headers={"X-CSRF-Token": csrf},
        json={
            "current_password": "Adam",
            "new_password": "A-stronger-password-123",
        },
    )
    assert old_csrf.status_code == 403
    changed = harness.client.post(
        "/api/v1/auth/change-password",
        headers={"X-CSRF-Token": recovered_csrf},
        json={
            "current_password": "Adam",
            "new_password": "A-stronger-password-123",
        },
    )
    assert changed.status_code == 204
    harness.password = "A-stronger-password-123"

    allowed = harness.client.post(
        "/api/v1/backups",
        headers=harness.mutation_headers(
            {"X-CSRF-Token": recovered_csrf}
        ),
    )
    assert allowed.status_code == 202, allowed.text


def test_admin_session_cookie_hash_and_password_change(harness) -> None:
    with harness.runtime.database.session_factory() as session:
        user = session.scalar(
            select(AdminUser).where(AdminUser.username == "Adam")
        )
        assert user is not None
        assert user.password_hash != "Adam"
        assert user.password_hash.startswith("$argon2id$")

    login = harness.raw_login()
    assert login.status_code == 200
    csrf = login.json()["csrf_token"]
    cookie = harness.client.cookies.get(harness.settings.session_cookie_name)
    assert cookie
    with harness.runtime.database.session_factory() as session:
        stored = session.scalar(
            select(AdminSession).order_by(
                AdminSession.created_at_ms.desc()
            )
        )
        assert stored is not None
        assert stored.token_hash != cookie
        assert stored.token_hash == hash_token(cookie, "admin-session")

    rejected = harness.client.post(
        "/api/v1/products",
        headers={"Idempotency-Key": "2ec5fd45-0e4d-4be4-a6bd-3517118b64df"},
        json={"product_code": "NO-CSRF"},
    )
    assert rejected.status_code == 403

    changed = harness.client.post(
        "/api/v1/auth/change-password",
        headers={"X-CSRF-Token": csrf},
        json={
            "current_password": "Adam",
            "new_password": "A-stronger-password-123",
        },
    )
    assert changed.status_code == 204
    harness.password = "A-stronger-password-123"
    logout = harness.client.post(
        "/api/v1/auth/logout", headers={"X-CSRF-Token": csrf}
    )
    assert logout.status_code == 204
    assert (
        harness.client.post(
            "/api/v1/auth/login",
            json={"username": "Adam", "password": "Adam"},
        ).status_code
        == 401
    )
    assert harness.raw_login().status_code == 200


def test_default_admin_is_not_recreated_after_first_run(harness) -> None:
    with harness.runtime.database.session_factory() as session:
        session.execute(delete(AdminUser))
        session.commit()
    bootstrap_first_run(
        harness.runtime.database.session_factory, harness.settings
    )
    with harness.runtime.database.session_factory() as session:
        assert (
            session.scalar(select(func.count()).select_from(AdminUser)) == 0
        )
