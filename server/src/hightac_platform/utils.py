from __future__ import annotations

import hashlib
import hmac
import json
import re
import secrets
import time
import uuid
from datetime import UTC, datetime
from typing import Any


STATION_ID_RE = re.compile(r"^90A9F[0-9A-F]{7}$")
TAG_ID_RE = re.compile(r"^AD1[0-9A-F]{9}$")


def utc_ms() -> int:
    return time.time_ns() // 1_000_000


def ms_to_datetime(value: int | None) -> datetime | None:
    if value is None:
        return None
    return datetime.fromtimestamp(value / 1000, tz=UTC)


def new_id() -> str:
    return str(uuid.uuid4())


def new_secret(bytes_count: int = 32) -> str:
    return secrets.token_urlsafe(bytes_count)


def hash_token(token: str, purpose: str) -> str:
    material = f"{purpose}\0{token}".encode("utf-8")
    return hashlib.sha256(material).hexdigest()


def verify_token(token: str, expected_hash: str, purpose: str) -> bool:
    return hmac.compare_digest(hash_token(token, purpose), expected_hash)


def android_fingerprint(android_id: str, signing_digest: str, namespace: str) -> str:
    normalized_android_id = android_id.strip()
    normalized_signing_digest = signing_digest.replace(":", "").strip().upper()
    material = f"{namespace}\0{normalized_android_id}\0{normalized_signing_digest}"
    return hashlib.sha256(material.encode("utf-8")).hexdigest()


def normalize_product_code(value: str) -> str:
    normalized = value.strip().upper()
    if not normalized:
        raise ValueError("Product code is required.")
    if len(normalized) > 128:
        raise ValueError("Product code is too long.")
    return normalized


def normalize_station_id(value: str) -> str:
    normalized = value.strip().upper()
    if not STATION_ID_RE.fullmatch(normalized):
        raise ValueError("Station ID must match ^90A9F[0-9A-F]{7}$.")
    return normalized


def normalize_tag_id(value: str) -> str:
    normalized = value.strip().upper()
    if re.fullmatch(r"[0-9A-F]{9}", normalized):
        normalized = f"AD1{normalized}"
    if not TAG_ID_RE.fullmatch(normalized):
        raise ValueError("Tag ID must match ^AD1[0-9A-F]{9}$.")
    return normalized


def stable_json_hash(value: Any) -> str:
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True)
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()
