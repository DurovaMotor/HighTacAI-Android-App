from __future__ import annotations

import asyncio
from dataclasses import dataclass
from typing import Any

from hightac_platform.utils import ms_to_datetime, new_id, utc_ms


ENTITY_TYPES = {
    "broker.status_changed": "broker",
    "station.status_changed": "station",
    "station.heartbeat": "station",
    "tag.status_changed": "tag",
    "binding.created": "binding",
    "binding.removed": "binding",
    "command.status_changed": "command",
    "device.status_changed": "device",
    "system.notice": "system",
}


@dataclass(slots=True)
class EventSubscription:
    queue: asyncio.Queue[dict[str, Any]]


class EventBus:
    def __init__(self, queue_size: int = 200) -> None:
        self.queue_size = queue_size
        self._subscribers: set[asyncio.Queue[dict[str, Any]]] = set()
        self._loop: asyncio.AbstractEventLoop | None = None

    def start(self) -> None:
        self._loop = asyncio.get_running_loop()

    def subscribe(self) -> EventSubscription:
        queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue(maxsize=self.queue_size)
        self._subscribers.add(queue)
        return EventSubscription(queue)

    def unsubscribe(self, subscription: EventSubscription) -> None:
        self._subscribers.discard(subscription.queue)

    async def publish(
        self, event_type: str, entity_id: str, payload: dict[str, Any]
    ) -> None:
        event = build_event(event_type, entity_id, payload)
        for queue in tuple(self._subscribers):
            if queue.full():
                try:
                    queue.get_nowait()
                except asyncio.QueueEmpty:
                    pass
            queue.put_nowait(event)

    def publish_threadsafe(
        self, event_type: str, entity_id: str, payload: dict[str, Any]
    ) -> None:
        if self._loop is None or self._loop.is_closed():
            return
        asyncio.run_coroutine_threadsafe(
            self.publish(event_type, entity_id, payload), self._loop
        )

    @property
    def subscriber_count(self) -> int:
        return len(self._subscribers)


def build_event(
    event_type: str, entity_id: str, payload: dict[str, Any]
) -> dict[str, Any]:
    entity_type = ENTITY_TYPES.get(event_type)
    if entity_type is None:
        raise ValueError(f"Unsupported event type: {event_type}")
    if not entity_id:
        raise ValueError("Event entity_id is required.")
    occurred_at = ms_to_datetime(utc_ms())
    assert occurred_at is not None
    return {
        "event_id": new_id(),
        "schema_version": 1,
        "event_type": event_type,
        "occurred_at": occurred_at.isoformat().replace("+00:00", "Z"),
        "entity_type": entity_type,
        "entity_id": entity_id,
        "payload": payload,
    }


def system_notice(
    *,
    severity: str,
    code: str,
    message: str,
    resource_type: str | None = None,
    resource_id: str | None = None,
) -> dict[str, Any]:
    return build_event(
        "system.notice",
        "HighTacPlatform",
        {
            "severity": severity,
            "code": code,
            "message": message,
            "resource_type": resource_type,
            "resource_id": resource_id,
        },
    )
