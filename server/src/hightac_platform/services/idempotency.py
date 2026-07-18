from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from hightac_platform.auth.context import Actor
from hightac_platform.db.models import IdempotencyRecord
from hightac_platform.domain.errors import IdempotencyConflictError
from hightac_platform.utils import stable_json_hash


@dataclass(frozen=True, slots=True)
class IdempotencyReplay:
    status_code: int
    payload: dict[str, Any]


def replay(
    session: Session,
    actor: Actor,
    *,
    method: str,
    route: str,
    key: str,
    request_payload: Any,
) -> IdempotencyReplay | None:
    record = session.scalar(
        select(IdempotencyRecord).where(
            IdempotencyRecord.actor_type == actor.actor_type.value,
            IdempotencyRecord.actor_id == actor.actor_id,
            IdempotencyRecord.method == method.upper(),
            IdempotencyRecord.route == route,
            IdempotencyRecord.idempotency_key == key,
        )
    )
    if record is None:
        return None
    if record.request_hash != stable_json_hash(request_payload):
        raise IdempotencyConflictError(
            "The idempotency key belongs to a different request."
        )
    return IdempotencyReplay(record.response_status, json.loads(record.response_json))


def store(
    session: Session,
    actor: Actor,
    *,
    method: str,
    route: str,
    key: str,
    request_payload: Any,
    status_code: int,
    response_payload: dict[str, Any],
) -> None:
    record = IdempotencyRecord(
        actor_type=actor.actor_type.value,
        actor_id=actor.actor_id,
        method=method.upper(),
        route=route,
        idempotency_key=key,
        request_hash=stable_json_hash(request_payload),
        response_status=status_code,
        response_json=json.dumps(
            response_payload, sort_keys=True, separators=(",", ":"), ensure_ascii=True
        ),
    )
    session.add(record)
    try:
        session.commit()
    except IntegrityError as exc:
        session.rollback()
        existing = replay(
            session,
            actor,
            method=method,
            route=route,
            key=key,
            request_payload=request_payload,
        )
        if existing is None:
            raise IdempotencyConflictError(
                "The idempotent operation could not be recorded."
            ) from exc
