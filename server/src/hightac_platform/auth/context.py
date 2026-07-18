from __future__ import annotations

from dataclasses import dataclass

from hightac_platform.domain.enums import ActorType


@dataclass(frozen=True, slots=True)
class Actor:
    actor_type: ActorType
    actor_id: str
    display_name: str
    session_id: str | None = None
    csrf_token_hash: str | None = None
    device_model: str | None = None
    must_change_password: bool = False

    @property
    def is_admin(self) -> bool:
        return self.actor_type is ActorType.ADMIN
