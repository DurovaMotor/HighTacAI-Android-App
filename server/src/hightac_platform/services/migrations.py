from __future__ import annotations

import base64
import binascii
import hashlib
import hmac
import json
from collections import Counter
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from typing import Protocol

from sqlalchemy import select, text
from sqlalchemy.orm import Session

from hightac_platform.api.serialization import migration_commit_payload
from hightac_platform.auth.context import Actor
from hightac_platform.db.models import (
    Binding,
    IdempotencyRecord,
    LightTag,
    Product,
    Station,
)
from hightac_platform.domain.enums import (
    AndroidBindingMigrationClassification as Classification,
    AndroidBindingMigrationOutcome as Outcome,
    BindingSource,
    ProductSource,
)
from hightac_platform.domain.errors import (
    IdempotencyConflictError,
    InvalidMigrationSelectionError,
    MigrationPreviewMismatchError,
    MigrationPreviewStaleError,
)
from hightac_platform.services.audit import append_operation_log
from hightac_platform.utils import (
    new_id,
    normalize_product_code,
    normalize_station_id,
    normalize_tag_id,
    stable_json_hash,
    utc_ms,
)


MIGRATION_COMMIT_ROUTE = "/migrations/android-bindings/commit"
TOKEN_VERSION = 1
TOKEN_CONTEXT = b"hightac:android-binding-migration-preview:v1\0"


class MigrationRecordInput(Protocol):
    client_record_key: str
    product_code: str
    product_name: str | None
    tag_id: str
    station_id: str


@dataclass(frozen=True, slots=True)
class MigrationRecord:
    client_record_key: str
    product_code: str
    product_name: str | None
    tag_id: str
    station_id: str

    def payload(self) -> dict[str, str | None]:
        return {
            "client_record_key": self.client_record_key,
            "product_code": self.product_code,
            "product_name": self.product_name,
            "tag_id": self.tag_id,
            "station_id": self.station_id,
        }


@dataclass(frozen=True, slots=True)
class MigrationPreviewItem:
    record: MigrationRecord
    classification: Classification
    authoritative_binding_id: str | None = None
    authoritative_product_code: str | None = None
    authoritative_station_id: str | None = None

    def digest_payload(self) -> dict[str, str | None]:
        return {
            **self.record.payload(),
            "classification": self.classification.value,
            "authoritative_binding_id": self.authoritative_binding_id,
            "authoritative_product_code": self.authoritative_product_code,
            "authoritative_station_id": self.authoritative_station_id,
        }


@dataclass(slots=True)
class MigrationEvaluation:
    records_hash: str
    state_hash: str
    preview_hash: str
    items: tuple[MigrationPreviewItem, ...]
    stations: dict[str, Station]
    tags: dict[str, LightTag]
    products: dict[str, Product]
    active_bindings: dict[str, Binding]


@dataclass(frozen=True, slots=True)
class MigrationPreview:
    token: str
    items: tuple[MigrationPreviewItem, ...]


@dataclass(frozen=True, slots=True)
class MigrationCommitResult:
    payload: dict[str, object]
    created_bindings: tuple[Binding, ...]
    replayed: bool


class AndroidBindingMigrationService:
    def __init__(self, signing_key_provider: Callable[[], bytes]) -> None:
        self._signing_key_provider = signing_key_provider

    def preview(
        self,
        session: Session,
        actor: Actor,
        records: Sequence[MigrationRecordInput],
    ) -> MigrationPreview:
        normalized = _normalize_records(records)
        evaluation = self._evaluate(session, normalized)
        token = self._encode_token(
            {
                "version": TOKEN_VERSION,
                "device_id": actor.actor_id,
                "records_hash": evaluation.records_hash,
                "state_hash": evaluation.state_hash,
                "preview_hash": evaluation.preview_hash,
            }
        )
        return MigrationPreview(token=token, items=evaluation.items)

    def commit(
        self,
        session: Session,
        actor: Actor,
        records: Sequence[MigrationRecordInput],
        *,
        preview_token: str,
        selected_duplicate_keys: Sequence[str],
        idempotency_key: str,
    ) -> MigrationCommitResult:
        normalized = _normalize_records(records)
        request_payload = {
            "records": [record.payload() for record in normalized],
            "preview_token": preview_token,
            "selected_duplicate_keys": list(selected_duplicate_keys),
        }
        request_hash = stable_json_hash(request_payload)

        session.rollback()
        try:
            self._begin_immediate(session)
            replayed = self._idempotency_replay(
                session,
                actor,
                key=idempotency_key,
                request_hash=request_hash,
            )
            if replayed is not None:
                session.rollback()
                return MigrationCommitResult(
                    payload=replayed,
                    created_bindings=(),
                    replayed=True,
                )

            claims = self._decode_token(preview_token)
            if claims["device_id"] != actor.actor_id or not hmac.compare_digest(
                claims["records_hash"],
                stable_json_hash([record.payload() for record in normalized]),
            ):
                raise MigrationPreviewMismatchError(
                    "The preview token does not match this device and record set."
                )

            evaluation = self._evaluate(session, normalized)
            if not hmac.compare_digest(
                claims["state_hash"], evaluation.state_hash
            ) or not hmac.compare_digest(
                claims["preview_hash"], evaluation.preview_hash
            ):
                raise MigrationPreviewStaleError(
                    "Authoritative binding state changed after the preview."
                )

            selected = self._validate_selection(
                evaluation.items,
                selected_duplicate_keys,
            )
            result = self._stage_commit(
                session,
                actor,
                evaluation,
                selected,
                idempotency_key=idempotency_key,
                request_hash=request_hash,
            )
            self._commit_transaction(session)
            return result
        except Exception:
            session.rollback()
            raise

    def _evaluate(
        self,
        session: Session,
        records: tuple[MigrationRecord, ...],
    ) -> MigrationEvaluation:
        station_ids = sorted({record.station_id for record in records})
        tag_ids = sorted({record.tag_id for record in records})
        product_codes = sorted({record.product_code for record in records})

        stations = {
            station.station_id: station
            for station in session.scalars(
                select(Station).where(Station.station_id.in_(station_ids))
            )
        }
        tags = {
            tag.tag_id: tag
            for tag in session.scalars(
                select(LightTag).where(LightTag.tag_id.in_(tag_ids))
            )
        }
        products = {
            product.product_code: product
            for product in session.scalars(
                select(Product).where(Product.product_code.in_(product_codes))
            )
        }
        binding_rows = session.execute(
            select(Binding, Product)
            .join(Product, Product.id == Binding.product_id)
            .where(
                Binding.tag_id.in_(tag_ids),
                Binding.is_active.is_(True),
            )
        ).all()
        active_bindings = {binding.tag_id: binding for binding, _ in binding_rows}
        binding_products = {
            binding.tag_id: product for binding, product in binding_rows
        }
        duplicate_tags = {
            tag_id
            for tag_id, count in Counter(record.tag_id for record in records).items()
            if count > 1
        }

        items = tuple(
            self._classify(
                record,
                duplicate_tags=duplicate_tags,
                stations=stations,
                tags=tags,
                active_bindings=active_bindings,
                binding_products=binding_products,
            )
            for record in records
        )
        state_payload = {
            "stations": [
                {
                    "station_id": station_id,
                    "site_id": stations[station_id].site_id,
                }
                if station_id in stations
                else {"station_id": station_id, "missing": True}
                for station_id in station_ids
            ],
            "tags": [
                {
                    "tag_id": tag_id,
                    "site_id": tags[tag_id].site_id,
                    "station_id": tags[tag_id].station_id,
                }
                if tag_id in tags
                else {"tag_id": tag_id, "missing": True}
                for tag_id in tag_ids
            ],
            "products": [
                {
                    "product_code": product_code,
                    "id": products[product_code].id,
                    "is_active": products[product_code].is_active,
                }
                if product_code in products
                else {"product_code": product_code, "missing": True}
                for product_code in product_codes
            ],
            "active_bindings": [
                {
                    "tag_id": tag_id,
                    "id": active_bindings[tag_id].id,
                    "product_id": active_bindings[tag_id].product_id,
                    "product_code": binding_products[tag_id].product_code,
                    "site_id": active_bindings[tag_id].site_id,
                    "station_id": active_bindings[tag_id].station_id,
                }
                if tag_id in active_bindings
                else {"tag_id": tag_id, "missing": True}
                for tag_id in tag_ids
            ],
        }
        records_hash = stable_json_hash([record.payload() for record in records])
        preview_hash = stable_json_hash([item.digest_payload() for item in items])
        return MigrationEvaluation(
            records_hash=records_hash,
            state_hash=stable_json_hash(state_payload),
            preview_hash=preview_hash,
            items=items,
            stations=stations,
            tags=tags,
            products=products,
            active_bindings=active_bindings,
        )

    @staticmethod
    def _classify(
        record: MigrationRecord,
        *,
        duplicate_tags: set[str],
        stations: dict[str, Station],
        tags: dict[str, LightTag],
        active_bindings: dict[str, Binding],
        binding_products: dict[str, Product],
    ) -> MigrationPreviewItem:
        station = stations.get(record.station_id)
        if station is None:
            return MigrationPreviewItem(
                record,
                Classification.STATION_NOT_FOUND,
            )

        tag = tags.get(record.tag_id)
        binding = active_bindings.get(record.tag_id)
        authoritative_station_id = (
            binding.station_id if binding and binding.station_id else None
        ) or (tag.station_id if tag else None)
        if binding is not None:
            bound_product = binding_products[record.tag_id]
            if bound_product.product_code != record.product_code:
                return MigrationPreviewItem(
                    record,
                    Classification.TAG_BOUND_TO_DIFFERENT_PRODUCT,
                    authoritative_binding_id=binding.id,
                    authoritative_product_code=bound_product.product_code,
                    authoritative_station_id=authoritative_station_id,
                )
            if (
                authoritative_station_id != record.station_id
                or binding.site_id != station.site_id
                or tag is None
                or tag.station_id != record.station_id
                or tag.site_id != station.site_id
            ):
                return MigrationPreviewItem(
                    record,
                    Classification.STATION_MISMATCH,
                    authoritative_binding_id=binding.id,
                    authoritative_product_code=bound_product.product_code,
                    authoritative_station_id=authoritative_station_id,
                )
            return MigrationPreviewItem(
                record,
                Classification.IDENTICAL,
                authoritative_binding_id=binding.id,
                authoritative_product_code=bound_product.product_code,
                authoritative_station_id=authoritative_station_id,
            )

        if tag is not None and (
            tag.station_id != record.station_id or tag.site_id != station.site_id
        ):
            return MigrationPreviewItem(
                record,
                Classification.STATION_MISMATCH,
                authoritative_station_id=tag.station_id,
            )
        if record.tag_id in duplicate_tags:
            return MigrationPreviewItem(
                record,
                Classification.DUPLICATE_LEGACY_TAG,
            )
        return MigrationPreviewItem(record, Classification.MIGRATABLE)

    @staticmethod
    def _validate_selection(
        items: tuple[MigrationPreviewItem, ...],
        selected_duplicate_keys: Sequence[str],
    ) -> set[str]:
        by_key = {item.record.client_record_key: item for item in items}
        selected = set(selected_duplicate_keys)
        selected_tags: set[str] = set()
        for key in selected_duplicate_keys:
            item = by_key.get(key)
            if (
                item is None
                or item.classification is not Classification.DUPLICATE_LEGACY_TAG
            ):
                raise InvalidMigrationSelectionError(
                    "Every selected key must identify a duplicate legacy tag record.",
                    details=[
                        {
                            "field": "selected_duplicate_keys",
                            "code": "NOT_A_DUPLICATE_RECORD",
                            "message": f"{key} is not selectable.",
                        }
                    ],
                )
            if item.record.tag_id in selected_tags:
                raise InvalidMigrationSelectionError(
                    "Select at most one legacy record for each duplicate tag.",
                    details=[
                        {
                            "field": "selected_duplicate_keys",
                            "code": "MULTIPLE_RECORDS_FOR_TAG",
                            "message": (
                                f"Multiple records selected for {item.record.tag_id}."
                            ),
                        }
                    ],
                )
            selected_tags.add(item.record.tag_id)
        return selected

    def _stage_commit(
        self,
        session: Session,
        actor: Actor,
        evaluation: MigrationEvaluation,
        selected: set[str],
        *,
        idempotency_key: str,
        request_hash: str,
    ) -> MigrationCommitResult:
        eligible = [
            item
            for item in evaluation.items
            if item.classification is Classification.MIGRATABLE
            or (
                item.classification is Classification.DUPLICATE_LEGACY_TAG
                and item.record.client_record_key in selected
            )
        ]
        now = utc_ms()
        products = dict(evaluation.products)
        tags = dict(evaluation.tags)
        created_products: list[Product] = []
        created_tags: list[LightTag] = []
        created_bindings: list[Binding] = []
        binding_by_key: dict[str, Binding] = {}

        for item in eligible:
            record = item.record
            station = evaluation.stations[record.station_id]
            product = products.get(record.product_code)
            if product is None:
                product = Product(
                    id=new_id(),
                    product_code=record.product_code,
                    product_name=record.product_name,
                    source=ProductSource.BINDING.value,
                    created_at_ms=now,
                    updated_at_ms=now,
                )
                products[record.product_code] = product
                created_products.append(product)
                session.add(product)
                append_operation_log(
                    session,
                    event_type="product.created",
                    actor=actor,
                    product_id=product.id,
                    request_summary={
                        "product_code": record.product_code,
                        "source": BindingSource.MIGRATION.value,
                    },
                )
            elif product in created_products and (
                product.product_name is None and record.product_name is not None
            ):
                product.product_name = record.product_name

            tag = tags.get(record.tag_id)
            if tag is None:
                tag = LightTag(
                    tag_id=record.tag_id,
                    site_id=station.site_id,
                    station_id=station.station_id,
                    registered_at_ms=now,
                    created_at_ms=now,
                    updated_at_ms=now,
                )
                tags[record.tag_id] = tag
                created_tags.append(tag)
                session.add(tag)
                append_operation_log(
                    session,
                    event_type="tag.registered",
                    actor=actor,
                    site_id=tag.site_id,
                    station_id=tag.station_id,
                    tag_id=tag.tag_id,
                    request_summary={"source": BindingSource.MIGRATION.value},
                )

        # These models carry foreign-key IDs without ORM relationships, so
        # make the staged inventory visible before staging dependent bindings.
        session.flush()

        for item in eligible:
            record = item.record
            station = evaluation.stations[record.station_id]
            product = products[record.product_code]
            tag = tags[record.tag_id]
            binding = Binding(
                id=new_id(),
                product_id=product.id,
                tag_id=tag.tag_id,
                site_id=station.site_id,
                station_id=station.station_id,
                source=BindingSource.MIGRATION.value,
                actor_type=actor.actor_type.value,
                actor_id=actor.actor_id,
                request_hash=stable_json_hash(record.payload()),
                bound_at_ms=now,
            )
            session.add(binding)
            created_bindings.append(binding)
            binding_by_key[record.client_record_key] = binding
            append_operation_log(
                session,
                event_type="binding.created",
                actor=actor,
                site_id=binding.site_id,
                station_id=binding.station_id,
                tag_id=binding.tag_id,
                product_id=binding.product_id,
                request_summary={
                    "client_record_key": record.client_record_key,
                    "source": BindingSource.MIGRATION.value,
                },
            )

        result_records: list[dict[str, str | None]] = []
        for item in evaluation.items:
            created = binding_by_key.get(item.record.client_record_key)
            identical = evaluation.active_bindings.get(item.record.tag_id)
            if created is not None:
                outcome = Outcome.MIGRATED
                binding_id = created.id
            elif item.classification is Classification.IDENTICAL:
                outcome = Outcome.IDENTICAL
                binding_id = identical.id if identical else None
            else:
                outcome = Outcome.SKIPPED
                binding_id = None
            result_records.append(
                {
                    "client_record_key": item.record.client_record_key,
                    "classification": item.classification.value,
                    "outcome": outcome.value,
                    "binding_id": binding_id,
                }
            )

        append_operation_log(
            session,
            event_type="android_bindings.migrated",
            actor=actor,
            request_summary={
                "total_records": len(evaluation.items),
                "selected_duplicate_records": len(selected),
            },
            result_summary={
                "created_products": len(created_products),
                "created_tags": len(created_tags),
                "created_bindings": len(created_bindings),
            },
        )
        session.flush()

        payload = migration_commit_payload(
            session,
            committed_at_ms=now,
            records=result_records,
            created_products=len(created_products),
            created_tags=len(created_tags),
            created_bindings=created_bindings,
        )
        session.add(
            IdempotencyRecord(
                id=new_id(),
                actor_type=actor.actor_type.value,
                actor_id=actor.actor_id,
                method="POST",
                route=MIGRATION_COMMIT_ROUTE,
                idempotency_key=idempotency_key,
                request_hash=request_hash,
                response_status=200,
                response_json=json.dumps(
                    payload,
                    sort_keys=True,
                    separators=(",", ":"),
                    ensure_ascii=True,
                ),
                created_at_ms=now,
            )
        )
        session.flush()
        return MigrationCommitResult(
            payload=payload,
            created_bindings=tuple(created_bindings),
            replayed=False,
        )

    @staticmethod
    def _begin_immediate(session: Session) -> None:
        if session.get_bind().dialect.name != "sqlite":
            raise RuntimeError("Android binding migration requires SQLite.")
        session.execute(text("BEGIN IMMEDIATE"))

    @staticmethod
    def _idempotency_replay(
        session: Session,
        actor: Actor,
        *,
        key: str,
        request_hash: str,
    ) -> dict[str, object] | None:
        record = session.scalar(
            select(IdempotencyRecord).where(
                IdempotencyRecord.actor_type == actor.actor_type.value,
                IdempotencyRecord.actor_id == actor.actor_id,
                IdempotencyRecord.method == "POST",
                IdempotencyRecord.route == MIGRATION_COMMIT_ROUTE,
                IdempotencyRecord.idempotency_key == key,
            )
        )
        if record is None:
            return None
        if record.request_hash != request_hash:
            raise IdempotencyConflictError(
                "The idempotency key belongs to a different request."
            )
        payload = json.loads(record.response_json)
        if not isinstance(payload, dict):
            raise RuntimeError("Stored migration response is invalid.")
        return payload

    def _encode_token(self, claims: dict[str, object]) -> str:
        encoded = _base64url(
            json.dumps(
                claims,
                sort_keys=True,
                separators=(",", ":"),
                ensure_ascii=True,
            ).encode("ascii")
        )
        signature = hmac.new(
            self._signing_key_provider(),
            TOKEN_CONTEXT + encoded.encode("ascii"),
            hashlib.sha256,
        ).digest()
        return f"{encoded}.{_base64url(signature)}"

    def _decode_token(self, token: str) -> dict[str, str]:
        try:
            encoded, supplied_signature = token.split(".", 1)
            expected_signature = _base64url(
                hmac.new(
                    self._signing_key_provider(),
                    TOKEN_CONTEXT + encoded.encode("ascii"),
                    hashlib.sha256,
                ).digest()
            )
            if not hmac.compare_digest(supplied_signature, expected_signature):
                raise ValueError("invalid signature")
            claims = json.loads(_decode_base64url(encoded))
            required = {
                "version",
                "device_id",
                "records_hash",
                "state_hash",
                "preview_hash",
            }
            if set(claims) != required or claims["version"] != TOKEN_VERSION:
                raise ValueError("invalid claims")
            for name in required - {"version"}:
                if not isinstance(claims[name], str) or not claims[name]:
                    raise ValueError("invalid claim value")
            for name in ("records_hash", "state_hash", "preview_hash"):
                if len(claims[name]) != 64:
                    raise ValueError("invalid digest")
        except (
            ValueError,
            TypeError,
            KeyError,
            UnicodeDecodeError,
            binascii.Error,
            json.JSONDecodeError,
        ) as exc:
            raise MigrationPreviewMismatchError(
                "The preview token is invalid or does not match this request."
            ) from exc
        return claims

    @staticmethod
    def _commit_transaction(session: Session) -> None:
        session.commit()


def _normalize_records(
    records: Sequence[MigrationRecordInput],
) -> tuple[MigrationRecord, ...]:
    normalized = tuple(
        MigrationRecord(
            client_record_key=record.client_record_key.strip(),
            product_code=normalize_product_code(record.product_code),
            product_name=(record.product_name or "").strip() or None,
            tag_id=normalize_tag_id(record.tag_id),
            station_id=normalize_station_id(record.station_id),
        )
        for record in records
    )
    keys = [record.client_record_key for record in normalized]
    if len(keys) != len(set(keys)):
        raise ValueError("client_record_key values must be unique.")
    return normalized


def _base64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def _decode_base64url(value: str) -> str:
    padding = "=" * (-len(value) % 4)
    return base64.urlsafe_b64decode(value + padding).decode("ascii")
