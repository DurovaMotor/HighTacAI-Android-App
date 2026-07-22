from __future__ import annotations

from dataclasses import dataclass
from uuid import UUID

from hightac_platform.domain.enums import ActorType


ANONYMOUS_ANDROID_ACTOR_ID = "00000000-0000-0000-0000-000000000000"


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


ANONYMOUS_ANDROID_ACTOR = Actor(
    actor_type=ActorType.ANDROID,
    actor_id=ANONYMOUS_ANDROID_ACTOR_ID,
    display_name="Anonymous Android app",
    device_model="Unregistered Android device",
)


def anonymous_android_actor(installation_id: UUID | None) -> Actor:
    if installation_id is None or str(installation_id) == ANONYMOUS_ANDROID_ACTOR_ID:
        return ANONYMOUS_ANDROID_ACTOR
    normalized = str(installation_id)
    return Actor(
        actor_type=ActorType.ANDROID,
        actor_id=normalized,
        display_name=f"Android installation {normalized}",
        device_model="Unregistered Android device",
    )
