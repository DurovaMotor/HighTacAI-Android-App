from __future__ import annotations

import json
from typing import Any

from sqlalchemy.orm import Session

from hightac_platform.auth.context import Actor
from hightac_platform.db.models import OperationLog
from hightac_platform.domain.enums import ActorType


def append_operation_log(
    session: Session,
    *,
    event_type: str,
    actor: Actor | None,
    request_summary: dict[str, Any] | None = None,
    result_summary: dict[str, Any] | None = None,
    site_id: str | None = None,
    station_id: str | None = None,
    tag_id: str | None = None,
    product_id: str | None = None,
    command_id: str | None = None,
    client_ip: str | None = None,
    failure_reason: str | None = None,
) -> OperationLog:
    log = OperationLog(
        event_type=event_type,
        site_id=site_id,
        station_id=station_id,
        tag_id=tag_id,
        product_id=product_id,
        command_id=command_id,
        actor_type=(actor.actor_type.value if actor else ActorType.SYSTEM.value),
        actor_id=actor.actor_id if actor else None,
        actor_name=actor.display_name if actor else "system",
        request_summary=_json_summary(request_summary),
        result_summary=_json_summary(result_summary),
        client_ip=client_ip,
        device_model=actor.device_model if actor else None,
        failure_reason=failure_reason,
    )
    session.add(log)
    return log


def _json_summary(value: dict[str, Any] | None) -> str | None:
    if value is None:
        return None
    return json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
