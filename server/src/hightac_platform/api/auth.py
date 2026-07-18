from fastapi import APIRouter, Request, Response, status

from hightac_platform.api.dependencies import (
    AdminCsrfDependency,
    AdminDependency,
    SessionDependency,
    get_runtime,
)
from hightac_platform.api.schemas import ChangePasswordRequest, LoginRequest
from hightac_platform.api.serialization import timestamp
from hightac_platform.db.models import AdminUser
from hightac_platform.domain.errors import NotFoundError


router = APIRouter(prefix="/auth", tags=["auth"])


@router.post("/login")
def login(
    body: LoginRequest,
    request: Request,
    response: Response,
    session: SessionDependency,
) -> dict[str, object]:
    runtime = get_runtime(request)
    result = runtime.auth_service.login(
        session,
        username=body.username,
        password=body.password,
        client_ip=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )
    response.set_cookie(
        runtime.settings.session_cookie_name,
        result.session_token,
        max_age=runtime.settings.session_ttl_seconds,
        httponly=True,
        secure=runtime.settings.session_cookie_secure,
        samesite="strict",
        path="/api/v1",
    )
    response.headers["X-CSRF-Token"] = result.csrf_token
    user = session.get(AdminUser, result.actor.actor_id)
    if user is None:
        raise NotFoundError("Administrator was not found.")
    return {
        "user": {
            "id": user.id,
            "username": user.username,
            "must_change_password": user.must_change_password,
            "is_active": user.is_active,
            "created_at": timestamp(user.created_at_ms),
            "last_login_at": timestamp(user.last_login_at_ms),
        },
        "csrf_token": result.csrf_token,
        "session_expires_at": timestamp(result.session_expires_at_ms),
    }


@router.post("/logout", status_code=status.HTTP_204_NO_CONTENT)
def logout(
    request: Request,
    response: Response,
    session: SessionDependency,
    actor: AdminCsrfDependency,
) -> None:
    runtime = get_runtime(request)
    runtime.auth_service.logout(session, actor)
    response.delete_cookie(
        runtime.settings.session_cookie_name,
        path="/api/v1",
        secure=runtime.settings.session_cookie_secure,
        httponly=True,
        samesite="strict",
    )


@router.get("/me")
def me(
    response: Response,
    request: Request,
    actor: AdminDependency,
    session: SessionDependency,
) -> dict[str, object]:
    user = session.get(AdminUser, actor.actor_id)
    if user is None:
        raise NotFoundError("Administrator was not found.")
    response.headers["X-CSRF-Token"] = get_runtime(request).auth_service.rotate_csrf(
        session, actor
    )
    return {
        "id": user.id,
        "username": user.username,
        "must_change_password": user.must_change_password,
        "is_active": user.is_active,
        "created_at": timestamp(user.created_at_ms),
        "last_login_at": timestamp(user.last_login_at_ms),
    }


@router.post("/change-password", status_code=status.HTTP_204_NO_CONTENT)
def change_password(
    body: ChangePasswordRequest,
    request: Request,
    session: SessionDependency,
    actor: AdminCsrfDependency,
) -> None:
    get_runtime(request).auth_service.change_password(
        session, actor, body.current_password, body.new_password
    )
