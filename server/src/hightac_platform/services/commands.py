from __future__ import annotations

import asyncio
import json
import logging
from collections import Counter, defaultdict
from collections.abc import Sequence
from dataclasses import dataclass
from typing import Protocol

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session, sessionmaker

from hightac_platform.api.serialization import command_event_payload
from hightac_platform.auth.context import Actor
from hightac_platform.db.models import Binding, Command, CommandItem, LightTag, Product, Station
from hightac_platform.db.repositories import CommandRepository
from hightac_platform.domain.enums import (
    CommandAction,
    CommandItemStatus,
    CommandStatus,
    LightColor,
    StationStatus,
)
from hightac_platform.domain.errors import ConflictError, NotFoundError, ServiceUnavailableError, ValidationError
from hightac_platform.events import EventBus
from hightac_platform.mqtt.protocol import (
    MAX_TAGS_PER_PACKET,
    ResultItem,
    encode_task,
    result_matches_command,
)
from hightac_platform.services.audit import append_operation_log
from hightac_platform.utils import (
    normalize_product_code,
    normalize_station_id,
    normalize_tag_id,
    stable_json_hash,
    utc_ms,
)


logger = logging.getLogger(__name__)
ACTIVE_ITEM_STATUSES = [CommandItemStatus.PENDING.value, CommandItemStatus.PUBLISHED.value]
TERMINAL_ITEM_STATUSES = {
    CommandItemStatus.CONFIRMED.value,
    CommandItemStatus.UNCONFIRMED.value,
    CommandItemStatus.FAILED.value,
    CommandItemStatus.SUPERSEDED.value,
}
BULK_QUERY_CHUNK_SIZE = 500


class CommandPublisher(Protocol):
    @property
    def connected(self) -> bool: ...

    def publish(self, topic: str, payload: bytes) -> bool: ...


class UnavailablePublisher:
    connected = False

    def publish(self, topic: str, payload: bytes) -> bool:
        return False


@dataclass(frozen=True, slots=True)
class CommandConfirmation:
    item: CommandItem
    action: str
    previous_command_status: str
    current_command_status: str
    previous_item_status: str
    target_count: int
    confirmed_count: int
    unconfirmed_count: int
    failed_count: int


def command_confirmation_event_payload(
    confirmation: CommandConfirmation,
) -> dict[str, object]:
    item = confirmation.item
    return {
        "command_id": item.command_id,
        "action": (
            "LIGHT_OFF"
            if confirmation.action == CommandAction.ALL_OFF.value
            else confirmation.action
        ),
        "previous_status": confirmation.previous_command_status,
        "current_status": confirmation.current_command_status,
        "target_count": confirmation.target_count,
        "confirmed_count": confirmation.confirmed_count,
        "unconfirmed_count": confirmation.unconfirmed_count,
        "failed_count": confirmation.failed_count,
        "item": {
            "item_id": item.id,
            "tag_id": item.tag_id,
            "station_id": item.station_id,
            "previous_status": confirmation.previous_item_status,
            "current_status": item.status,
            "correlation": "HEURISTIC_STATE_MATCH",
            "result_type": item.result_type,
        },
    }


class CommandService:
    def __init__(self, publisher: CommandPublisher, event_bus: EventBus) -> None:
        self.publisher = publisher
        self.event_bus = event_bus

    def create(
        self,
        session: Session,
        actor: Actor,
        *,
        action: CommandAction,
        color: LightColor | None,
        product_code: str | None,
        tag_ids: Sequence[str] | None,
        station_id: str | None,
        idempotency_key: str,
        now_ms: int | None = None,
    ) -> tuple[Command, bool]:
        now = now_ms or utc_ms()
        key = idempotency_key.strip()
        if not key or len(key) > 128:
            raise ValidationError("A valid idempotency key is required.")
        if action is CommandAction.LIGHT_ON and color is None:
            raise ValidationError("Light-on commands require a supported color.")
        if action is not CommandAction.LIGHT_ON:
            color = None
        targets, target_type, target_value = self._resolve_targets(
            session,
            action=action,
            product_code=product_code,
            tag_ids=tag_ids,
            station_id=station_id,
        )
        request_hash = stable_json_hash(
            {
                "action": action.value,
                "color": color.value if color else None,
                "targets": [(tag.tag_id, tag.station_id) for tag in targets],
            }
        )
        repository = CommandRepository(session)
        previous = repository.by_idempotency(actor.actor_type.value, actor.actor_id, key)
        if previous:
            if previous.request_hash != request_hash:
                raise ConflictError("Idempotency key was already used for another command.")
            return previous, False
        if not self.publisher.connected:
            raise ServiceUnavailableError(
                "The MQTT broker is not available; no command was created.",
                code="MQTT_UNAVAILABLE",
            )

        station_ids = {tag.station_id for tag in targets}
        stations = {
            station.station_id: station
            for station in session.scalars(select(Station).where(Station.station_id.in_(station_ids)))
        }
        unavailable = [
            station_id_value
            for station_id_value in station_ids
            if station_id_value is None
            or station_id_value not in stations
            or not _station_is_online(stations[station_id_value], now)
        ]
        if unavailable:
            raise ServiceUnavailableError("One or more target stations are not online.")

        command = Command(
            action=action.value,
            color=color.value if color else None,
            target_type=target_type,
            target_value=target_value,
            actor_type=actor.actor_type.value,
            actor_id=actor.actor_id,
            idempotency_key=key,
            request_hash=request_hash,
            created_at_ms=now,
            updated_at_ms=now,
        )
        session.add(command)
        try:
            session.flush()
        except IntegrityError as exc:
            session.rollback()
            previous = CommandRepository(session).by_idempotency(
                actor.actor_type.value, actor.actor_id, key
            )
            if previous and previous.request_hash == request_hash:
                return previous, False
            raise ConflictError("Command could not be created because of a concurrent write.") from exc

        tag_ids_set = [tag.tag_id for tag in targets]
        old_items = list(
            session.scalars(
                select(CommandItem).where(
                    CommandItem.tag_id.in_(tag_ids_set), CommandItem.status.in_(ACTIVE_ITEM_STATUSES)
                )
            )
        )
        old_command_ids: set[str] = set()
        for item in old_items:
            item.status = CommandItemStatus.SUPERSEDED.value
            item.error_message = "Superseded by a newer command for this tag."
            old_command_ids.add(item.command_id)

        grouped: dict[str, list[LightTag]] = defaultdict(list)
        for tag in targets:
            if tag.station_id is not None:
                grouped[tag.station_id].append(tag)
        for target_station_id, station_tags in sorted(grouped.items()):
            for index, tag in enumerate(sorted(station_tags, key=lambda value: value.tag_id)):
                session.add(
                    CommandItem(
                        command_id=command.id,
                        station_id=target_station_id,
                        tag_id=tag.tag_id,
                        action=action.value,
                        color=color.value if color else None,
                        batch_no=index // MAX_TAGS_PER_PACKET,
                        created_at_ms=now,
                        deadline_at_ms=now + 10_000,
                    )
                )
        session.flush()
        for old_command_id in old_command_ids:
            aggregate_command_status(session, old_command_id, now)
        append_operation_log(
            session,
            event_type="command.created",
            actor=actor,
            command_id=command.id,
            request_summary={
                "action": action.value,
                "color": color.value if color else None,
                "target_count": len(targets),
            },
        )
        try:
            session.commit()
        except IntegrityError as exc:
            session.rollback()
            previous = CommandRepository(session).by_idempotency(
                actor.actor_type.value, actor.actor_id, key
            )
            if previous and previous.request_hash == request_hash:
                return previous, False
            raise ConflictError("Command could not be created because of a concurrent write.") from exc
        self.dispatch(session, command.id, now_ms=now)
        return command, True

    def dispatch(self, session: Session, command_id: str, now_ms: int | None = None) -> Command:
        now = now_ms or utc_ms()
        command = session.get(Command, command_id)
        if command is None:
            raise NotFoundError("Command was not found.")
        previous_status = command.status
        pending_items = list(
            session.scalars(
                select(CommandItem)
                .where(
                    CommandItem.command_id == command_id,
                    CommandItem.status == CommandItemStatus.PENDING.value,
                )
                .order_by(CommandItem.station_id, CommandItem.batch_no, CommandItem.tag_id)
            )
        )
        for packet in _packet_groups(pending_items):
            published = self._publish_packet(packet)
            for item in packet:
                if published:
                    item.status = CommandItemStatus.PUBLISHED.value
                    item.first_published_at_ms = now
                    item.last_published_at_ms = now
                else:
                    item.status = CommandItemStatus.FAILED.value
                    item.error_message = "MQTT publish was not accepted."
        session.flush()
        aggregate_command_status(session, command.id, now)
        session.commit()
        self.event_bus.publish_threadsafe(
            "command.status_changed",
            command.id,
            command_event_payload(
                session, command, previous_status=previous_status
            ),
        )
        return command

    def tick(self, session: Session, now_ms: int | None = None) -> int:
        now = now_ms or utc_ms()
        affected: set[str] = set()
        previous_statuses: dict[str, str] = {}
        expired = list(
            session.scalars(
                select(CommandItem).where(
                    CommandItem.status == CommandItemStatus.PUBLISHED.value,
                    CommandItem.deadline_at_ms <= now,
                )
            )
        )
        for item in expired:
            command = session.get(Command, item.command_id)
            if command is not None:
                previous_statuses.setdefault(command.id, command.status)
            item.status = CommandItemStatus.UNCONFIRMED.value
            item.error_message = "No matching station result arrived within 10 seconds."
            affected.add(item.command_id)

        retry_items = list(
            session.scalars(
                select(CommandItem).where(
                    CommandItem.status == CommandItemStatus.PUBLISHED.value,
                    CommandItem.retry_count == 0,
                    CommandItem.first_published_at_ms <= now - 2_000,
                    CommandItem.deadline_at_ms > now,
                )
            )
        )
        grouped: dict[tuple[str, str, str, str | None], list[CommandItem]] = defaultdict(list)
        for item in retry_items:
            command = session.get(Command, item.command_id)
            if command is not None:
                previous_statuses.setdefault(command.id, command.status)
            grouped[(item.command_id, item.station_id, item.action, item.color)].append(item)
        for items in grouped.values():
            for packet in _chunks(
                sorted(items, key=lambda value: value.tag_id),
                MAX_TAGS_PER_PACKET,
            ):
                self._publish_packet(packet)
                for item in packet:
                    item.retry_count = 1
                    item.last_published_at_ms = now
                    affected.add(item.command_id)
        session.flush()
        for command_id in affected:
            aggregate_command_status(session, command_id, now)
        if affected:
            session.commit()
            for command_id in affected:
                command = session.get(Command, command_id)
                if command is None:
                    continue
                self.event_bus.publish_threadsafe(
                    "command.status_changed",
                    command_id,
                    command_event_payload(
                        session,
                        command,
                        previous_status=previous_statuses.get(
                            command_id, command.status
                        ),
                    ),
                )
        return len(affected)

    def confirm_result(
        self, session: Session, station_id: str, item: ResultItem, now_ms: int | None = None
    ) -> CommandConfirmation | None:
        confirmations = self.confirm_results(
            session, station_id, [item], now_ms=now_ms
        )
        return confirmations[0] if confirmations else None

    def confirm_results(
        self,
        session: Session,
        station_id: str,
        items: Sequence[ResultItem],
        now_ms: int | None = None,
    ) -> list[CommandConfirmation]:
        if not items:
            return []
        now = now_ms or utc_ms()
        candidates: dict[str, CommandItem] = {}
        tag_ids = sorted({item.tag_id for item in items})
        for tag_id_chunk in _value_chunks(tag_ids):
            statement = (
                select(CommandItem)
                .where(
                    CommandItem.station_id == station_id,
                    CommandItem.tag_id.in_(tag_id_chunk),
                    CommandItem.status == CommandItemStatus.PUBLISHED.value,
                    CommandItem.first_published_at_ms <= now,
                    CommandItem.deadline_at_ms >= now,
                )
                .order_by(
                    CommandItem.tag_id,
                    CommandItem.created_at_ms.desc(),
                )
            )
            for command_item in session.scalars(statement):
                candidates.setdefault(command_item.tag_id, command_item)

        matched: list[tuple[CommandItem, ResultItem]] = []
        matched_item_ids: set[str] = set()
        for result_item in items:
            command_item = candidates.get(result_item.tag_id)
            if command_item is None or command_item.id in matched_item_ids:
                continue
            action = CommandAction(command_item.action)
            color = (
                LightColor(command_item.color)
                if command_item.color
                else None
            )
            if result_matches_command(result_item, action, color):
                matched.append((command_item, result_item))
                matched_item_ids.add(command_item.id)
        if not matched:
            return []

        command_ids = sorted(
            {command_item.command_id for command_item, _item in matched}
        )
        commands: dict[str, Command] = {}
        command_items: dict[str, list[CommandItem]] = defaultdict(list)
        for command_id_chunk in _value_chunks(command_ids):
            for command in session.scalars(
                select(Command).where(Command.id.in_(command_id_chunk))
            ):
                commands[command.id] = command
            for command_item in session.scalars(
                select(CommandItem).where(
                    CommandItem.command_id.in_(command_id_chunk)
                )
            ):
                command_items[command_item.command_id].append(command_item)

        status_counts = {
            command_id: Counter(
                command_item.status
                for command_item in command_items[command_id]
            )
            for command_id in command_ids
        }
        confirmations: list[CommandConfirmation] = []
        for command_item, result_item in matched:
            command = commands.get(command_item.command_id)
            previous_item_status = command_item.status
            command_item.status = CommandItemStatus.CONFIRMED.value
            command_item.confirmed_at_ms = now
            command_item.result_type = result_item.result_type
            command_item.result_summary = json.dumps(
                {
                    "battery": result_item.battery,
                    "sequence": result_item.sequence,
                },
                separators=(",", ":"),
            )
            if command is None:
                continue
            counts = status_counts[command.id]
            counts[previous_item_status] -= 1
            counts[CommandItemStatus.CONFIRMED.value] += 1
            target_count = sum(counts.values())
            previous_command_status = command.status
            current_command_status = _command_status_from_counts(
                counts, target_count
            )
            command.status = current_command_status
            command.updated_at_ms = now
            if _all_items_terminal(counts, target_count):
                command.completed_at_ms = now
            confirmations.append(
                CommandConfirmation(
                    item=command_item,
                    action=command.action,
                    previous_command_status=previous_command_status,
                    current_command_status=current_command_status,
                    previous_item_status=previous_item_status,
                    target_count=target_count,
                    confirmed_count=counts[
                        CommandItemStatus.CONFIRMED.value
                    ],
                    unconfirmed_count=counts[
                        CommandItemStatus.UNCONFIRMED.value
                    ],
                    failed_count=counts[CommandItemStatus.FAILED.value],
                )
            )
        session.flush()
        return confirmations

    def _publish_packet(self, items: Sequence[CommandItem]) -> bool:
        if not items:
            return True
        first = items[0]
        action = CommandAction(first.action)
        color = LightColor(first.color) if first.color else None
        payload = encode_task(action, color, [item.tag_id for item in items])
        topic = f"/estation/{first.station_id}/task"
        try:
            return self.publisher.publish(topic, payload)
        except Exception:
            logger.exception(
                "mqtt_command_publish_failed",
                extra={"event": "mqtt.command_publish_failed", "station_id": first.station_id},
            )
            return False

    def _resolve_targets(
        self,
        session: Session,
        *,
        action: CommandAction,
        product_code: str | None,
        tag_ids: Sequence[str] | None,
        station_id: str | None,
    ) -> tuple[list[LightTag], str, str]:
        selectors = sum(bool(value) for value in (product_code, tag_ids, station_id))
        if selectors != 1:
            raise ValidationError("Specify exactly one of product_code, tag_ids, or station_id.")
        if station_id:
            if action is not CommandAction.LIGHT_OFF:
                raise ValidationError(
                    "station_id is only valid for station light-off commands."
                )
            normalized_station = normalize_station_id(station_id)
            tags = list(
                session.scalars(
                    select(LightTag)
                    .join(Binding, Binding.tag_id == LightTag.tag_id)
                    .where(
                        LightTag.station_id == normalized_station,
                        Binding.is_active.is_(True),
                    )
                )
            )
            target_type, target_value = "STATION", normalized_station
        elif product_code:
            normalized_code = normalize_product_code(product_code)
            product = session.scalar(select(Product).where(Product.product_code == normalized_code))
            if product is None:
                raise NotFoundError("Product was not found.")
            tags = list(
                session.scalars(
                    select(LightTag)
                    .join(Binding, Binding.tag_id == LightTag.tag_id)
                    .where(Binding.product_id == product.id, Binding.is_active.is_(True))
                )
            )
            target_type, target_value = "PRODUCT", normalized_code
        else:
            normalized_tags = sorted({normalize_tag_id(value) for value in tag_ids or []})
            tags = list(session.scalars(select(LightTag).where(LightTag.tag_id.in_(normalized_tags))))
            if len(tags) != len(normalized_tags):
                raise NotFoundError("One or more tags were not found.")
            target_type, target_value = "TAGS", ",".join(normalized_tags)
        if not tags:
            raise ValidationError("Command target has no tags.")
        return tags, target_type, target_value


class CommandScheduler:
    def __init__(
        self, session_factory: sessionmaker[Session], service: CommandService, interval_seconds: float = 0.5
    ) -> None:
        self.session_factory = session_factory
        self.service = service
        self.interval_seconds = interval_seconds
        self._task: asyncio.Task[None] | None = None

    def start(self) -> None:
        if self._task is None:
            self._task = asyncio.create_task(self._run(), name="hightac-command-scheduler")

    async def stop(self) -> None:
        if self._task is None:
            return
        self._task.cancel()
        try:
            await self._task
        except asyncio.CancelledError:
            pass
        self._task = None

    async def _run(self) -> None:
        while True:
            await asyncio.sleep(self.interval_seconds)
            try:
                with self.session_factory() as session:
                    await asyncio.to_thread(self.service.tick, session)
            except Exception:
                logger.exception("command_scheduler_tick_failed", extra={"event": "command.scheduler_failed"})


def aggregate_command_status(session: Session, command_id: str, now_ms: int | None = None) -> str:
    command = session.get(Command, command_id)
    if command is None:
        return CommandStatus.FAILED.value
    statuses = [
        status
        for (status,) in session.execute(
            select(CommandItem.status).where(CommandItem.command_id == command_id)
        )
    ]
    counts = Counter(statuses)
    status = _command_status_from_counts(counts, len(statuses))
    command.status = status
    command.updated_at_ms = now_ms or utc_ms()
    if _all_items_terminal(counts, len(statuses)):
        command.completed_at_ms = command.updated_at_ms
    return status


def _command_status_from_counts(
    counts: Counter[str], target_count: int
) -> str:
    confirmed = counts[CommandItemStatus.CONFIRMED.value]
    unconfirmed = counts[CommandItemStatus.UNCONFIRMED.value]
    failed = counts[CommandItemStatus.FAILED.value]
    superseded = counts[CommandItemStatus.SUPERSEDED.value]
    pending = counts[CommandItemStatus.PENDING.value]
    published = counts[CommandItemStatus.PUBLISHED.value]
    if target_count == 0:
        return CommandStatus.FAILED.value
    if confirmed == target_count:
        return CommandStatus.CONFIRMED.value
    if superseded == target_count:
        return CommandStatus.SUPERSEDED.value
    if pending or published:
        return (
            CommandStatus.PUBLISHED.value
            if published
            else CommandStatus.ACCEPTED.value
        )
    if confirmed:
        return CommandStatus.PARTIALLY_CONFIRMED.value
    if unconfirmed == target_count:
        return CommandStatus.UNCONFIRMED.value
    if failed == target_count:
        return CommandStatus.FAILED.value
    if _all_items_terminal(counts, target_count):
        return CommandStatus.UNCONFIRMED.value
    return CommandStatus.FAILED.value


def _all_items_terminal(
    counts: Counter[str], target_count: int
) -> bool:
    return (
        sum(counts[status] for status in TERMINAL_ITEM_STATUSES)
        == target_count
    )


def _packet_groups(items: Sequence[CommandItem]) -> list[list[CommandItem]]:
    grouped: dict[tuple[str, int], list[CommandItem]] = defaultdict(list)
    for item in items:
        grouped[(item.station_id, item.batch_no)].append(item)
    return [sorted(group, key=lambda item: item.tag_id) for _, group in sorted(grouped.items())]


def _chunks(items: Sequence[CommandItem], size: int) -> list[list[CommandItem]]:
    return [list(items[index : index + size]) for index in range(0, len(items), size)]


def _value_chunks(
    values: Sequence[str], size: int = BULK_QUERY_CHUNK_SIZE
) -> list[list[str]]:
    return [
        list(values[index : index + size])
        for index in range(0, len(values), size)
    ]


def _station_is_online(station: Station, now_ms: int) -> bool:
    if station.status != StationStatus.ONLINE.value or station.last_heartbeat_at_ms is None:
        return False
    threshold_ms = max(60_000, station.heartbeat_seconds * 3_000)
    return now_ms - station.last_heartbeat_at_ms <= threshold_ms
