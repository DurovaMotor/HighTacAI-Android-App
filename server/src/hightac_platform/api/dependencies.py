from __future__ import annotations

from collections.abc import Iterator
from dataclasses import dataclass
from enum import IntEnum
from typing import Annotated, Any
from uuid import UUID

from fastapi import Depends, Header, Query, Request
from sqlalchemy.orm import Session

from hightac_platform.auth.context import Actor
from hightac_platform.domain.errors import (
    PasswordChangeRequiredError,
    UnauthorizedError,
)


@dataclass(frozen=True, slots=True)
class Pagination:
    page: int
    page_size: int
    sort: str | None


class PageSize(IntEnum):
    TWENTY = 20
    FIFTY = 50
    ONE_HUNDRED = 100


def get_runtime(request: Request) -> Any:
    return request.app.state.runtime


def get_session(request: Request) -> Iterator[Session]:
    runtime = get_runtime(request)
    with runtime.database.session_factory() as session:
        yield session


def pagination(
    page: Annotated[int, Query(ge=1)] = 1,
    page_size: PageSize = PageSize.TWENTY,
    sort: Annotated[str | None, Query(max_length=256)] = None,
) -> Pagination:
    return Pagination(page, int(page_size), sort)


def require_admin(
    request: Request,
    session: Annotated[Session, Depends(get_session)],
) -> Actor:
    runtime = get_runtime(request)
    token = request.cookies.get(runtime.settings.session_cookie_name)
    if not token:
        raise UnauthorizedError("Administrator authentication is required.")
    return runtime.auth_service.authenticate_session(session, token)


def require_actor(
    request: Request,
    session: Annotated[Session, Depends(get_session)],
    authorization: Annotated[str | None, Header()] = None,
) -> Actor:
    runtime = get_runtime(request)
    if authorization:
        scheme, _, token = authorization.partition(" ")
        if scheme.lower() != "bearer" or not token:
            raise UnauthorizedError("Authorization header must use Bearer authentication.")
        return runtime.device_service.authenticate_token(session, token)
    cookie = request.cookies.get(runtime.settings.session_cookie_name)
    if cookie:
        return runtime.auth_service.authenticate_session(session, cookie)
    raise UnauthorizedError("Authentication is required.")


def require_admin_csrf(
    request: Request,
    actor: Annotated[Actor, Depends(require_admin)],
    csrf_token: Annotated[str | None, Header(alias="X-CSRF-Token")] = None,
) -> Actor:
    runtime = get_runtime(request)
    runtime.auth_service.verify_csrf(actor, csrf_token)
    return actor


def require_admin_write(
    actor: Annotated[Actor, Depends(require_admin_csrf)],
) -> Actor:
    if actor.must_change_password:
        raise PasswordChangeRequiredError(
            "The initial administrator password must be changed before management writes."
        )
    return actor


def require_write_actor(
    request: Request,
    actor: Annotated[Actor, Depends(require_actor)],
    csrf_token: Annotated[str | None, Header(alias="X-CSRF-Token")] = None,
) -> Actor:
    if actor.is_admin:
        get_runtime(request).auth_service.verify_csrf(actor, csrf_token)
        if actor.must_change_password:
            raise PasswordChangeRequiredError(
                "The initial administrator password must be changed before management writes."
            )
    return actor


def require_device(
    request: Request,
    session: Annotated[Session, Depends(get_session)],
    authorization: Annotated[str | None, Header()] = None,
) -> Actor:
    if not authorization:
        raise UnauthorizedError("Android bearer authentication is required.")
    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer" or not token:
        raise UnauthorizedError(
            "Authorization header must use Bearer authentication."
        )
    return get_runtime(request).device_service.authenticate_token(
        session,
        token,
        update_last_seen=False,
    )


def require_mobile_proxy_device(
    request: Request,
    session: Annotated[Session, Depends(get_session)],
    authorization: Annotated[str | None, Header()] = None,
) -> Actor:
    if not authorization:
        raise UnauthorizedError("Android bearer authentication is required.")
    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer" or not token:
        raise UnauthorizedError(
            "Authorization header must use Bearer authentication."
        )
    return get_runtime(request).device_service.authenticate_token(
        session,
        token,
        update_last_seen=True,
    )


def require_idempotency_key(
    value: Annotated[UUID, Header(alias="Idempotency-Key")],
) -> str:
    return str(value)


SessionDependency = Annotated[Session, Depends(get_session)]
AdminDependency = Annotated[Actor, Depends(require_admin)]
AdminCsrfDependency = Annotated[Actor, Depends(require_admin_csrf)]
AdminWriteDependency = Annotated[Actor, Depends(require_admin_write)]
ActorDependency = Annotated[Actor, Depends(require_actor)]
DeviceDependency = Annotated[Actor, Depends(require_device)]
MobileProxyDeviceDependency = Annotated[
    Actor,
    Depends(require_mobile_proxy_device),
]
WriteActorDependency = Annotated[Actor, Depends(require_write_actor)]
PaginationDependency = Annotated[Pagination, Depends(pagination)]
IdempotencyKeyDependency = Annotated[str, Depends(require_idempotency_key)]
