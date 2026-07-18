from __future__ import annotations

import logging
from collections.abc import Sequence

from sqlalchemy import select
from sqlalchemy.orm import Session, sessionmaker

from hightac_platform.api.serialization import timestamp
from hightac_platform.db.models import LightTag, Site, Station
from hightac_platform.db.repositories import CatalogRepository
from hightac_platform.domain.enums import StationStatus
from hightac_platform.events import EventBus
from hightac_platform.mqtt.protocol import ProtocolError, parse_heartbeat, parse_result, parse_topic
from hightac_platform.services.commands import (
    CommandService,
    command_confirmation_event_payload,
)
from hightac_platform.utils import utc_ms


logger = logging.getLogger(__name__)
BULK_QUERY_CHUNK_SIZE = 500


class TelemetryService:
    def __init__(
        self,
        session_factory: sessionmaker[Session],
        event_bus: EventBus,
        command_service: CommandService,
    ) -> None:
        self.session_factory = session_factory
        self.event_bus = event_bus
        self.command_service = command_service

    def handle_messages(
        self, messages: Sequence[tuple[str, bytes]]
    ) -> int:
        return sum(
            self.handle_message(topic, payload)
            for topic, payload in messages
        )

    def handle_message(self, topic: str, payload: bytes) -> bool:
        try:
            parsed_topic = parse_topic(topic)
            if parsed_topic.kind == "heartbeat":
                heartbeat = parse_heartbeat(payload, parsed_topic.station_id)
                self._store_heartbeat(heartbeat)
            else:
                result = parse_result(payload, parsed_topic.station_id)
                self._store_result(result)
            return True
        except ProtocolError as exc:
            logger.warning(
                "mqtt_payload_rejected",
                extra={
                    "event": "mqtt.payload_rejected",
                    "topic": topic[:255],
                    "reason": str(exc),
                    "payload_size": len(payload),
                },
            )
            return False
        except Exception:
            logger.exception(
                "mqtt_payload_processing_failed",
                extra={
                    "event": "mqtt.payload_processing_failed",
                    "topic": topic[:255],
                    "payload_size": len(payload),
                },
            )
            return False

    def _store_heartbeat(self, heartbeat: object) -> None:
        from hightac_platform.mqtt.protocol import Heartbeat

        assert isinstance(heartbeat, Heartbeat)
        now = utc_ms()
        with self.session_factory() as session:
            station = session.get(Station, heartbeat.station_id)
            if station is None:
                site = _default_site(session)
                station = Station(station_id=heartbeat.station_id, site_id=site.id)
                session.add(station)
            station.status = StationStatus.ONLINE.value
            station.mac = heartbeat.mac
            if not station.alias and heartbeat.alias:
                station.alias = heartbeat.alias
            station.server_address = heartbeat.server_address
            station.heartbeat_seconds = heartbeat.heartbeat_seconds
            station.firmware_version = heartbeat.firmware_version
            station.total_count = heartbeat.total_count
            station.send_count = heartbeat.send_count
            station.last_heartbeat_at_ms = now
            station.updated_at_ms = now
            session.commit()
        self.event_bus.publish_threadsafe(
            "station.heartbeat",
            heartbeat.station_id,
            {
                "station_id": heartbeat.station_id,
                "status": StationStatus.ONLINE.value,
                "mac": heartbeat.mac or "",
                "alias": heartbeat.alias,
                "server_address": heartbeat.server_address or "",
                "heartbeat_seconds": heartbeat.heartbeat_seconds,
                "firmware_version": heartbeat.firmware_version or "",
                "total_count": heartbeat.total_count,
                "send_count": heartbeat.send_count,
            },
        )

    def _store_result(self, result: object) -> None:
        from hightac_platform.mqtt.protocol import TaskResult

        assert isinstance(result, TaskResult)
        now = utc_ms()
        changed_tags: list[dict[str, object]] = []
        with self.session_factory() as session:
            station = session.get(Station, result.station_id)
            if station is None:
                site = _default_site(session)
                station = Station(station_id=result.station_id, site_id=site.id)
                session.add(station)
                session.flush()
            station.status = StationStatus.ONLINE.value
            station.total_count = result.total_count
            station.send_count = result.send_count
            station.last_heartbeat_at_ms = now
            station.updated_at_ms = now
            tags = _tags_by_id(
                session, {item.tag_id for item in result.results}
            )
            for item in result.results:
                tag = tags.get(item.tag_id)
                if tag is None:
                    tag = LightTag(
                        tag_id=item.tag_id,
                        site_id=station.site_id,
                        station_id=station.station_id,
                        first_seen_at_ms=now,
                    )
                    session.add(tag)
                    tags[item.tag_id] = tag
                elif tag.first_seen_at_ms is None:
                    tag.first_seen_at_ms = now
                tag.site_id = station.site_id
                tag.station_id = station.station_id
                tag.last_seen_at_ms = now
                tag.battery_raw = item.battery
                tag.battery_voltage_mv = item.battery_voltage_mv
                tag.battery_level = item.battery_level
                tag.firmware_version = item.version
                tag.group_no = item.group
                tag.last_result_type = item.result_type
                tag.updated_at_ms = now
                changed_tags.append(
                    {
                        "tag_id": tag.tag_id,
                        "station_id": tag.station_id,
                        "online": True,
                        "last_seen_at": timestamp(tag.last_seen_at_ms),
                        "battery_raw": tag.battery_raw,
                        "battery_voltage": (
                            tag.battery_voltage_mv / 1000
                            if tag.battery_voltage_mv is not None
                            else None
                        ),
                        "battery_level": tag.battery_level,
                        "low_battery": bool(
                            tag.battery_level is not None
                            and tag.battery_level <= 30
                        ),
                        "is_abnormal": tag.is_abnormal,
                        "abnormal_reason": tag.abnormal_reason,
                        "last_result_type": tag.last_result_type,
                    }
                )
            confirmations = self.command_service.confirm_results(
                session,
                station.station_id,
                result.results,
                now_ms=now,
            )
            changed_commands = [
                (
                    confirmation.item.command_id,
                    command_confirmation_event_payload(confirmation),
                )
                for confirmation in confirmations
            ]
            session.commit()
        for tag_event in changed_tags:
            self.event_bus.publish_threadsafe(
                "tag.status_changed", str(tag_event["tag_id"]), tag_event
            )
        for command_id, payload in changed_commands:
            self.event_bus.publish_threadsafe(
                "command.status_changed", command_id, payload
            )
        if result.skipped_items:
            logger.warning(
                "mqtt_result_items_skipped",
                extra={
                    "event": "mqtt.result_items_skipped",
                    "station_id": result.station_id,
                    "skipped_items": result.skipped_items,
                },
            )


def _default_site(session: Session) -> Site:
    site = CatalogRepository(session).default_site()
    if site is None:
        raise RuntimeError("Default site has not been bootstrapped.")
    return site


def _tags_by_id(
    session: Session, tag_ids: set[str]
) -> dict[str, LightTag]:
    tags: dict[str, LightTag] = {}
    ordered_ids = sorted(tag_ids)
    for index in range(0, len(ordered_ids), BULK_QUERY_CHUNK_SIZE):
        chunk = ordered_ids[index : index + BULK_QUERY_CHUNK_SIZE]
        for tag in session.scalars(
            select(LightTag).where(LightTag.tag_id.in_(chunk))
        ):
            tags[tag.tag_id] = tag
    return tags
