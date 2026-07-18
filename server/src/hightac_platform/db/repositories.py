from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Generic, TypeVar

from sqlalchemy import Select, func, select
from sqlalchemy.orm import Session

from hightac_platform.db.models import (
    AdminSession,
    AdminUser,
    AndroidDevice,
    Binding,
    Command,
    CommandItem,
    DeviceEnrollment,
    LightTag,
    Product,
    Site,
    Station,
)


T = TypeVar("T")


@dataclass(slots=True)
class PageResult(Generic[T]):
    items: list[T]
    page: int
    page_size: int
    total: int


def paginate(session: Session, statement: Select[Any], page: int, page_size: int) -> PageResult[Any]:
    count_statement = select(func.count()).select_from(statement.order_by(None).subquery())
    total = session.scalar(count_statement) or 0
    items = list(session.scalars(statement.offset((page - 1) * page_size).limit(page_size)))
    return PageResult(items=items, page=page, page_size=page_size, total=total)


class AdminRepository:
    def __init__(self, session: Session) -> None:
        self.session = session

    def by_username(self, username: str) -> AdminUser | None:
        return self.session.scalar(
            select(AdminUser).where(func.lower(AdminUser.username) == username.strip().lower())
        )

    def by_session_hash(self, token_hash: str) -> tuple[AdminSession, AdminUser] | None:
        row = self.session.execute(
            select(AdminSession, AdminUser)
            .join(AdminUser, AdminUser.id == AdminSession.admin_user_id)
            .where(AdminSession.token_hash == token_hash)
        ).one_or_none()
        return row if row is None else (row[0], row[1])


class DeviceRepository:
    def __init__(self, session: Session) -> None:
        self.session = session

    def by_token_hash(self, token_hash: str) -> AndroidDevice | None:
        return self.session.scalar(select(AndroidDevice).where(AndroidDevice.token_hash == token_hash))

    def enrollment(self, enrollment_id: str) -> DeviceEnrollment | None:
        return self.session.get(DeviceEnrollment, enrollment_id)

    def enrollment_by_idempotency_hash(
        self, idempotency_hash: str
    ) -> DeviceEnrollment | None:
        return self.session.scalar(
            select(DeviceEnrollment).where(
                DeviceEnrollment.challenge_hash == idempotency_hash
            )
        )

    def reusable_enrollment(
        self, fingerprint_hash: str, install_secret_hash: str, now_ms: int
    ) -> DeviceEnrollment | None:
        return self.session.scalar(
            select(DeviceEnrollment)
            .where(
                DeviceEnrollment.fingerprint_hash == fingerprint_hash,
                DeviceEnrollment.install_secret_hash == install_secret_hash,
                DeviceEnrollment.status.in_(["PENDING", "APPROVED"]),
                DeviceEnrollment.expires_at_ms > now_ms,
            )
            .order_by(DeviceEnrollment.created_at_ms.desc())
        )


class CatalogRepository:
    def __init__(self, session: Session) -> None:
        self.session = session

    def default_site(self) -> Site | None:
        return self.session.scalar(select(Site).order_by(Site.created_at_ms).limit(1))

    def product_by_code(self, product_code: str) -> Product | None:
        return self.session.scalar(select(Product).where(Product.product_code == product_code))

    def active_binding_for_tag(self, tag_id: str) -> Binding | None:
        return self.session.scalar(
            select(Binding).where(Binding.tag_id == tag_id, Binding.is_active.is_(True))
        )

    def binding_idempotency(
        self, actor_type: str, actor_id: str, idempotency_key: str
    ) -> Binding | None:
        return self.session.scalar(
            select(Binding).where(
                Binding.actor_type == actor_type,
                Binding.actor_id == actor_id,
                Binding.idempotency_key == idempotency_key,
            )
        )

    def tag(self, tag_id: str) -> LightTag | None:
        return self.session.get(LightTag, tag_id)

    def station(self, station_id: str) -> Station | None:
        return self.session.get(Station, station_id)


class CommandRepository:
    def __init__(self, session: Session) -> None:
        self.session = session

    def by_idempotency(self, actor_type: str, actor_id: str, key: str) -> Command | None:
        return self.session.scalar(
            select(Command).where(
                Command.actor_type == actor_type,
                Command.actor_id == actor_id,
                Command.idempotency_key == key,
            )
        )

    def items(self, command_id: str) -> list[CommandItem]:
        return list(
            self.session.scalars(
                select(CommandItem)
                .where(CommandItem.command_id == command_id)
                .order_by(CommandItem.batch_no, CommandItem.tag_id)
            )
        )
