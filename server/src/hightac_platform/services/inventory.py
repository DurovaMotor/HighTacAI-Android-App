from __future__ import annotations

from dataclasses import dataclass

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from hightac_platform.auth.context import Actor
from hightac_platform.db.models import Binding, LightTag, Product, Site, Station
from hightac_platform.db.repositories import CatalogRepository
from hightac_platform.domain.enums import (
    ActorType,
    BindingSource,
    ProductSource,
    StationStatus,
)
from hightac_platform.domain.errors import ConflictError, NotFoundError, ValidationError
from hightac_platform.services.audit import append_operation_log
from hightac_platform.utils import (
    normalize_product_code,
    normalize_station_id,
    normalize_tag_id,
    stable_json_hash,
    utc_ms,
)


@dataclass(slots=True)
class BindingResult:
    binding: Binding
    created: bool


@dataclass(slots=True)
class RebindResult:
    removed_binding: Binding
    created_binding: Binding
    created: bool


class InventoryService:
    def create_station(
        self,
        session: Session,
        actor: Actor,
        *,
        station_id: str,
        alias: str | None,
        site_id: str | None,
    ) -> Station:
        normalized_id = normalize_station_id(station_id)
        if session.get(Station, normalized_id):
            raise ConflictError("Station already exists.")
        site = session.get(Site, site_id) if site_id else None
        if site is None:
            raise NotFoundError("Site was not found.")
        station = Station(
            station_id=normalized_id,
            site_id=site.id,
            alias=_clean(alias, 128),
        )
        session.add(station)
        append_operation_log(
            session,
            event_type="station.created",
            actor=actor,
            site_id=site.id,
            station_id=station.station_id,
        )
        session.commit()
        return station

    def update_station_alias(
        self, session: Session, actor: Actor, station_id: str, alias: str | None
    ) -> Station:
        station = session.get(Station, normalize_station_id(station_id))
        if station is None:
            raise NotFoundError("Station was not found.")
        station.alias = _clean(alias, 128)
        station.updated_at_ms = utc_ms()
        append_operation_log(
            session,
            event_type="station.updated",
            actor=actor,
            station_id=station.station_id,
        )
        session.commit()
        return station

    def register_tag(
        self,
        session: Session,
        actor: Actor,
        *,
        tag_id: str,
        station_id: str,
    ) -> LightTag:
        normalized_tag = normalize_tag_id(tag_id)
        existing = session.get(LightTag, normalized_tag)
        if existing:
            raise ConflictError("Tag is already registered.")
        station = session.get(Station, normalize_station_id(station_id))
        if station is None:
            raise NotFoundError("Station was not found.")
        site = session.get(Site, station.site_id)
        if site is None:
            raise NotFoundError("Site was not found.")
        tag = LightTag(
            tag_id=normalized_tag,
            site_id=site.id,
            station_id=station.station_id,
        )
        session.add(tag)
        append_operation_log(
            session,
            event_type="tag.registered",
            actor=actor,
            site_id=site.id,
            station_id=tag.station_id,
            tag_id=tag.tag_id,
        )
        session.commit()
        return tag

    def create_product(
        self,
        session: Session,
        actor: Actor,
        *,
        product_code: str,
        product_name: str | None,
        source: ProductSource = ProductSource.BINDING,
    ) -> Product:
        normalized_code = normalize_product_code(product_code)
        existing = CatalogRepository(session).product_by_code(normalized_code)
        if existing:
            raise ConflictError("Product code already exists.")
        product = Product(
            product_code=normalized_code,
            product_name=_clean(product_name, 256),
            source=source.value,
        )
        session.add(product)
        session.flush()
        append_operation_log(
            session,
            event_type="product.created",
            actor=actor,
            product_id=product.id,
            request_summary={"product_code": normalized_code},
        )
        session.commit()
        return product

    def update_product(
        self,
        session: Session,
        actor: Actor,
        product_id: str,
        *,
        product_name: str | None,
        is_active: bool | None,
    ) -> Product:
        product = session.get(Product, product_id)
        if product is None:
            raise NotFoundError("Product was not found.")
        if product_name is not None:
            product.product_name = _clean(product_name, 256)
        if is_active is not None:
            product.is_active = is_active
        product.updated_at_ms = utc_ms()
        append_operation_log(
            session, event_type="product.updated", actor=actor, product_id=product.id
        )
        session.commit()
        return product

    def bind(
        self,
        session: Session,
        actor: Actor,
        *,
        product_code: str,
        product_name: str | None,
        tag_id: str,
        station_id: str,
        idempotency_key: str | None,
    ) -> BindingResult:
        normalized_code = normalize_product_code(product_code)
        normalized_tag = normalize_tag_id(tag_id)
        normalized_station = normalize_station_id(station_id)
        key = _idempotency_key(idempotency_key)
        request_hash = stable_json_hash(
            {
                "product_code": normalized_code,
                "tag_id": normalized_tag,
                "station_id": normalized_station,
            }
        )
        repository = CatalogRepository(session)
        if key:
            previous = repository.binding_idempotency(actor.actor_type.value, actor.actor_id, key)
            if previous:
                if previous.request_hash != request_hash:
                    raise ConflictError("Idempotency key was already used for another binding request.")
                return BindingResult(previous, created=False)

        tag = repository.tag(normalized_tag)
        if tag is None:
            raise NotFoundError("Tag was not found. Register it before binding.")
        station = repository.station(normalized_station)
        if station is None:
            raise NotFoundError("Station was not found.")
        active = repository.active_binding_for_tag(normalized_tag)
        product = repository.product_by_code(normalized_code)
        if active:
            raise ConflictError("Tag is already bound. Use the explicit rebind endpoint.")
        if product is None:
            product = Product(
                product_code=normalized_code,
                product_name=_clean(product_name, 256),
                source=ProductSource.BINDING.value,
            )
            session.add(product)
            session.flush()
        elif product_name and not product.product_name:
            product.product_name = _clean(product_name, 256)

        binding = Binding(
            product_id=product.id,
            tag_id=tag.tag_id,
            site_id=tag.site_id,
            station_id=station.station_id,
            source=_binding_source(actor).value,
            actor_type=actor.actor_type.value,
            actor_id=actor.actor_id,
            idempotency_key=key,
            request_hash=request_hash,
        )
        session.add(binding)
        append_operation_log(
            session,
            event_type="binding.created",
            actor=actor,
            site_id=binding.site_id,
            station_id=binding.station_id,
            tag_id=binding.tag_id,
            product_id=binding.product_id,
        )
        try:
            session.commit()
        except IntegrityError as exc:
            session.rollback()
            raise ConflictError("Tag is already actively bound.") from exc
        return BindingResult(binding, created=True)

    def unbind(self, session: Session, actor: Actor, binding_id: str) -> BindingResult:
        binding = session.get(Binding, binding_id)
        if binding is None:
            raise NotFoundError("Binding was not found.")
        if not binding.is_active:
            return BindingResult(binding, created=False)
        binding.is_active = False
        binding.unbound_at_ms = utc_ms()
        append_operation_log(
            session,
            event_type="binding.removed",
            actor=actor,
            site_id=binding.site_id,
            station_id=binding.station_id,
            tag_id=binding.tag_id,
            product_id=binding.product_id,
        )
        session.commit()
        return BindingResult(binding, created=False)

    def rebind(
        self,
        session: Session,
        actor: Actor,
        binding_id: str,
        *,
        product_code: str,
        product_name: str | None,
        expected_tag_id: str,
        idempotency_key: str | None,
    ) -> RebindResult:
        old_binding = session.get(Binding, binding_id)
        if old_binding is None:
            raise NotFoundError("Binding was not found.")
        if old_binding.tag_id != normalize_tag_id(expected_tag_id):
            raise ConflictError("The active binding tag does not match expected_tag_id.")
        if old_binding.station_id is None:
            raise ConflictError("The active binding has no station and cannot be rebound.")
        if not old_binding.is_active:
            if idempotency_key:
                existing = CatalogRepository(session).binding_idempotency(
                    actor.actor_type.value, actor.actor_id, idempotency_key
                )
                if existing:
                    return RebindResult(old_binding, existing, created=False)
            raise ConflictError("Only an active binding can be rebound.")

        normalized_code = normalize_product_code(product_code)
        product = CatalogRepository(session).product_by_code(normalized_code)
        if product and product.id == old_binding.product_id:
            raise ConflictError("The replacement product is already bound to this tag.")
        old_binding.is_active = False
        old_binding.unbound_at_ms = utc_ms()
        session.flush()
        try:
            result = self.bind(
                session,
                actor,
                product_code=normalized_code,
                product_name=product_name,
                tag_id=old_binding.tag_id,
                station_id=old_binding.station_id,
                idempotency_key=idempotency_key,
            )
        except Exception:
            session.rollback()
            raise
        return RebindResult(old_binding, result.binding, result.created)

    def refresh_station_statuses(self, session: Session, now_ms: int | None = None) -> int:
        now = now_ms or utc_ms()
        changed = 0
        for station in session.scalars(select(Station)):
            if station.last_heartbeat_at_ms is None:
                expected = StationStatus.UNKNOWN.value
            else:
                threshold_ms = max(60_000, station.heartbeat_seconds * 3_000)
                expected = (
                    StationStatus.ONLINE.value
                    if now - station.last_heartbeat_at_ms <= threshold_ms
                    else StationStatus.OFFLINE.value
                )
            if station.status != expected:
                station.status = expected
                station.updated_at_ms = now
                changed += 1
        if changed:
            session.commit()
        return changed


def _binding_source(actor: Actor) -> BindingSource:
    if actor.actor_type is ActorType.ANDROID:
        return BindingSource.ANDROID
    return BindingSource.WEB


def _idempotency_key(value: str | None) -> str | None:
    cleaned = (value or "").strip()
    if len(cleaned) > 128:
        raise ValidationError("Idempotency key is too long.")
    return cleaned or None


def _clean(value: str | None, limit: int) -> str | None:
    cleaned = (value or "").strip()
    return cleaned[:limit] or None
