from __future__ import annotations

import asyncio
import base64
from collections.abc import Callable
from dataclasses import dataclass
import hashlib
import hmac
import json
import re
import time
from typing import Any
from urllib.parse import urlsplit, urlunsplit

import httpx

from hightac_platform import __version__
from hightac_platform.config import Settings
from hightac_platform.domain.errors import (
    DomainError,
    GoneError,
    NotFoundError,
    ServiceUnavailableError,
)


JIANDAOYUN_ALLOWED_PATHS = frozenset(
    {
        "/v5/app/entry/list",
        "/v5/app/entry/widget/list",
        "/v5/app/entry/data/list",
    }
)
JIANDAOYUN_MEDIA_HOST = "files.jiandaoyun.com"
MOBILE_MEDIA_SIGNING_PURPOSE = "mobile-media-token-v1"

_MEDIA_TOKEN_CONTEXT = b"hightac-mobile-media-token-v1\0"
_MEDIA_TOKEN_MAX_CHARS = 8192
_MEDIA_URL_MAX_CHARS = 4096
_IMAGE_MEDIA_TYPE_RE = re.compile(r"^image/[A-Za-z0-9!#$&^_.+\-]+$")


@dataclass(frozen=True, slots=True)
class MobileProxyResult:
    status_code: int
    payload: Any


@dataclass(frozen=True, slots=True)
class MobileMediaResult:
    content: bytes
    media_type: str


class UpstreamProxyError(DomainError):
    code = "UPSTREAM_ERROR"

    def __init__(
        self,
        message: str,
        *,
        status_code: int,
        code: str,
    ) -> None:
        super().__init__(message, code=code)
        self.status_code = status_code


class MobileProxyService:
    def __init__(
        self,
        settings: Settings,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
        media_signing_key_provider: Callable[[], bytes] | None = None,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self.settings = settings
        self._media_signing_key_provider = media_signing_key_provider
        self._media_signing_key: bytes | None = None
        self._clock = clock
        self._client = httpx.AsyncClient(
            transport=transport,
            follow_redirects=False,
            limits=httpx.Limits(
                max_connections=20,
                max_keepalive_connections=10,
            ),
            headers={"User-Agent": f"HighTacPlatform/{__version__}"},
        )
        self._media_client = httpx.AsyncClient(
            transport=transport,
            follow_redirects=False,
            limits=httpx.Limits(
                max_connections=20,
                max_keepalive_connections=10,
            ),
            headers={"User-Agent": f"HighTacPlatform/{__version__}"},
        )

    async def close(self) -> None:
        await self._client.aclose()
        await self._media_client.aclose()

    async def forward_openai_responses(
        self,
        request_payload: dict[str, Any],
    ) -> MobileProxyResult:
        api_key = self._required_secret(
            self.settings.openai_api_key,
            provider="OpenAI",
        )
        payload = dict(request_payload)
        payload["model"] = self.settings.openai_model
        payload["stream"] = False
        base_url = self.settings.openai_base_url
        parsed_base = urlsplit(base_url)
        origin = urlunsplit((parsed_base.scheme, parsed_base.netloc, "", "", ""))
        return await self._post_json(
            provider="OpenAI",
            url=_openai_responses_url(base_url),
            payload=payload,
            api_key=api_key,
            timeout_seconds=self.settings.openai_timeout_seconds,
            extra_headers={
                "Origin": origin,
                "Referer": f"{base_url}/",
            },
        )

    async def forward_jiandaoyun(
        self,
        path: str,
        request_payload: dict[str, Any],
        *,
        media_url_builder: Callable[[str], str] | None = None,
    ) -> MobileProxyResult:
        normalized_path = f"/{path.strip().lstrip('/')}"
        if normalized_path not in JIANDAOYUN_ALLOWED_PATHS:
            raise UpstreamProxyError(
                "The requested JianDaoYun operation is not allowed.",
                status_code=404,
                code="MOBILE_PROXY_PATH_NOT_ALLOWED",
            )
        api_key = self._required_secret(
            self.settings.jiandaoyun_api_key,
            provider="JianDaoYun",
        )
        app_id = (self.settings.jiandaoyun_app_id or "").strip()
        if not app_id:
            raise ServiceUnavailableError(
                "JianDaoYun integration is not configured.",
                code="JIANDAOYUN_NOT_CONFIGURED",
            )

        payload = dict(request_payload)
        payload["app_id"] = app_id
        if normalized_path == "/v5/app/entry/list":
            payload.pop("entry_id", None)
        else:
            entry_id = (self.settings.jiandaoyun_entry_id or "").strip()
            if not entry_id:
                raise ServiceUnavailableError(
                    "JianDaoYun integration is not configured.",
                    code="JIANDAOYUN_NOT_CONFIGURED",
                )
            payload["entry_id"] = entry_id

        result = await self._post_json(
            provider="JianDaoYun",
            url=_jiandaoyun_url(
                self.settings.jiandaoyun_base_url,
                normalized_path,
            ),
            payload=payload,
            api_key=api_key,
            timeout_seconds=self.settings.jiandaoyun_timeout_seconds,
        )
        if media_url_builder is None:
            return result
        try:
            rewritten = self._rewrite_media_urls(
                result.payload,
                media_url_builder=media_url_builder,
            )
        except RecursionError as exc:
            raise UpstreamProxyError(
                "JianDaoYun returned an invalid media structure.",
                status_code=502,
                code="UPSTREAM_INVALID_RESPONSE",
            ) from exc
        return MobileProxyResult(status_code=result.status_code, payload=rewritten)

    async def fetch_media(self, token: str) -> MobileMediaResult:
        source_url = self._decode_media_token(token)
        timeout_seconds = self.settings.mobile_media_timeout_seconds
        timeout = httpx.Timeout(
            timeout_seconds,
            connect=min(5.0, timeout_seconds),
        )
        upstream_request = httpx.Request(
            "GET",
            source_url,
            headers={
                "Accept": "image/*",
                "Accept-Encoding": "identity",
                "User-Agent": f"HighTacPlatform/{__version__}",
            },
            extensions={"timeout": timeout.as_dict()},
        )
        try:
            async with asyncio.timeout(timeout_seconds):
                response = await self._media_client.send(
                    upstream_request,
                    stream=True,
                    auth=None,
                    follow_redirects=False,
                )
                try:
                    if not 200 <= response.status_code < 300:
                        if 300 <= response.status_code < 400:
                            raise UpstreamProxyError(
                                "The media source returned a redirect.",
                                status_code=502,
                                code="MEDIA_REDIRECT_NOT_ALLOWED",
                            )
                        raise UpstreamProxyError(
                            "The media source is unavailable.",
                            status_code=502,
                            code="MEDIA_UPSTREAM_ERROR",
                        )

                    media_type = _image_media_type(
                        response.headers.get("Content-Type", "")
                    )
                    if media_type is None:
                        raise UpstreamProxyError(
                            "The media response is not an allowed image.",
                            status_code=415,
                            code="MEDIA_TYPE_NOT_ALLOWED",
                        )

                    maximum_bytes = self.settings.mobile_media_max_bytes
                    declared_length = _content_length(response.headers)
                    if (
                        declared_length is not None
                        and declared_length > maximum_bytes
                    ):
                        raise _media_too_large()

                    content = bytearray()
                    async for chunk in response.aiter_bytes(chunk_size=64 * 1024):
                        if len(content) + len(chunk) > maximum_bytes:
                            raise _media_too_large()
                        content.extend(chunk)
                finally:
                    await response.aclose()
        except UpstreamProxyError:
            raise
        except (TimeoutError, httpx.TimeoutException) as exc:
            raise UpstreamProxyError(
                "The media source did not respond before the timeout.",
                status_code=504,
                code="MEDIA_UPSTREAM_TIMEOUT",
            ) from exc
        except httpx.RequestError as exc:
            raise UpstreamProxyError(
                "The media source is unavailable.",
                status_code=502,
                code="MEDIA_UPSTREAM_UNAVAILABLE",
            ) from exc

        return MobileMediaResult(content=bytes(content), media_type=media_type)

    def _rewrite_media_urls(
        self,
        value: Any,
        *,
        media_url_builder: Callable[[str], str],
    ) -> Any:
        if isinstance(value, dict):
            return {
                key: self._rewrite_media_urls(
                    child,
                    media_url_builder=media_url_builder,
                )
                for key, child in value.items()
            }
        if isinstance(value, list):
            return [
                self._rewrite_media_urls(
                    child,
                    media_url_builder=media_url_builder,
                )
                for child in value
            ]
        if not isinstance(value, str):
            return value

        classification = _classify_media_url(value)
        if classification == "other":
            return value
        if classification == "invalid":
            raise UpstreamProxyError(
                "JianDaoYun returned an invalid media URL.",
                status_code=502,
                code="UPSTREAM_INVALID_RESPONSE",
            )
        return media_url_builder(self._encode_media_token(value))

    def _encode_media_token(self, source_url: str) -> str:
        if len(source_url) > _MEDIA_URL_MAX_CHARS:
            raise UpstreamProxyError(
                "JianDaoYun returned an invalid media URL.",
                status_code=502,
                code="UPSTREAM_INVALID_RESPONSE",
            )
        claims = {
            "exp": int(self._clock()) + self.settings.mobile_media_token_ttl_seconds,
            "url": source_url,
            "v": 1,
        }
        payload = json.dumps(
            claims,
            ensure_ascii=True,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("ascii")
        signature = hmac.new(
            self._get_media_signing_key(),
            _MEDIA_TOKEN_CONTEXT + payload,
            hashlib.sha256,
        ).digest()
        return f"{_base64url_encode(payload)}.{_base64url_encode(signature)}"

    def _decode_media_token(self, token: str) -> str:
        if len(token) > _MEDIA_TOKEN_MAX_CHARS or token.count(".") != 1:
            raise _invalid_media_token()
        encoded_payload, encoded_signature = token.split(".", 1)
        try:
            payload = _base64url_decode(encoded_payload)
            supplied_signature = _base64url_decode(encoded_signature)
        except (ValueError, TypeError):
            raise _invalid_media_token() from None
        if len(supplied_signature) != hashlib.sha256().digest_size:
            raise _invalid_media_token()
        expected_signature = hmac.new(
            self._get_media_signing_key(),
            _MEDIA_TOKEN_CONTEXT + payload,
            hashlib.sha256,
        ).digest()
        if not hmac.compare_digest(supplied_signature, expected_signature):
            raise _invalid_media_token()

        try:
            claims = json.loads(payload.decode("ascii"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            raise _invalid_media_token() from None
        if (
            not isinstance(claims, dict)
            or set(claims) != {"exp", "url", "v"}
            or claims.get("v") != 1
            or type(claims.get("exp")) is not int
            or not isinstance(claims.get("url"), str)
        ):
            raise _invalid_media_token()
        if claims["exp"] <= int(self._clock()):
            raise GoneError(
                "The media link has expired.",
                code="MEDIA_TOKEN_EXPIRED",
            )
        source_url = claims["url"]
        if (
            len(source_url) > _MEDIA_URL_MAX_CHARS
            or _classify_media_url(source_url) != "allowed"
        ):
            raise _invalid_media_token()
        return source_url

    def _get_media_signing_key(self) -> bytes:
        if self._media_signing_key is not None:
            return self._media_signing_key
        if self._media_signing_key_provider is None:
            raise RuntimeError("Mobile media signing key is not configured.")
        signing_key = self._media_signing_key_provider()
        if not isinstance(signing_key, bytes) or len(signing_key) < 32:
            raise RuntimeError("Mobile media signing key is invalid.")
        self._media_signing_key = signing_key
        return signing_key

    async def _post_json(
        self,
        *,
        provider: str,
        url: str,
        payload: dict[str, Any],
        api_key: str,
        timeout_seconds: float,
        extra_headers: dict[str, str] | None = None,
    ) -> MobileProxyResult:
        headers = {
            "Authorization": f"Bearer {api_key}",
            "Accept": "application/json",
            "Content-Type": "application/json",
            **(extra_headers or {}),
        }
        timeout = httpx.Timeout(
            timeout_seconds,
            connect=min(10.0, timeout_seconds),
        )
        try:
            response = await self._client.post(
                url,
                json=payload,
                headers=headers,
                timeout=timeout,
            )
        except httpx.TimeoutException as exc:
            raise UpstreamProxyError(
                f"{provider} did not respond before the timeout.",
                status_code=504,
                code="UPSTREAM_TIMEOUT",
            ) from exc
        except httpx.RequestError as exc:
            raise UpstreamProxyError(
                f"{provider} is unavailable.",
                status_code=502,
                code="UPSTREAM_UNAVAILABLE",
            ) from exc

        if not 200 <= response.status_code < 300:
            status_code, code = _map_upstream_status(response.status_code)
            raise UpstreamProxyError(
                f"{provider} rejected the proxied request.",
                status_code=status_code,
                code=code,
            )
        try:
            response_payload = response.json()
        except ValueError as exc:
            raise UpstreamProxyError(
                f"{provider} returned an invalid JSON response.",
                status_code=502,
                code="UPSTREAM_INVALID_RESPONSE",
            ) from exc
        if not isinstance(response_payload, dict):
            raise UpstreamProxyError(
                f"{provider} returned a JSON response that is not an object.",
                status_code=502,
                code="UPSTREAM_INVALID_RESPONSE",
            )
        return MobileProxyResult(
            status_code=200,
            payload=_redact_secret(response_payload, api_key),
        )

    @staticmethod
    def _required_secret(secret: Any, *, provider: str) -> str:
        value = secret.get_secret_value().strip() if secret is not None else ""
        if not value:
            raise ServiceUnavailableError(
                f"{provider} integration is not configured.",
                code=f"{provider.upper()}_NOT_CONFIGURED",
            )
        return value


def _openai_responses_url(base_url: str) -> str:
    normalized = base_url.rstrip("/")
    lowered = normalized.lower()
    if lowered.endswith("/responses"):
        return normalized
    if lowered.endswith("/v1"):
        return f"{normalized}/responses"
    return f"{normalized}/v1/responses"


def _jiandaoyun_url(base_url: str, path: str) -> str:
    normalized = base_url.rstrip("/")
    if not normalized.lower().endswith("/api"):
        normalized = f"{normalized}/api"
    return f"{normalized}{path}"


def _map_upstream_status(status_code: int) -> tuple[int, str]:
    if status_code in {401, 403}:
        return 502, "UPSTREAM_AUTHENTICATION_FAILED"
    if status_code == 408:
        return 504, "UPSTREAM_TIMEOUT"
    if status_code == 429:
        return 429, "UPSTREAM_RATE_LIMITED"
    if status_code == 503:
        return 503, "UPSTREAM_UNAVAILABLE"
    if status_code == 504:
        return 504, "UPSTREAM_TIMEOUT"
    if status_code >= 500:
        return 502, "UPSTREAM_UNAVAILABLE"
    if status_code in {400, 404, 409, 413, 422}:
        return status_code, "UPSTREAM_REJECTED_REQUEST"
    return 502, "UPSTREAM_ERROR"


def _redact_secret(value: Any, secret: str) -> Any:
    if isinstance(value, dict):
        return {
            key.replace(secret, "[REDACTED]") if secret else key: _redact_secret(
                child,
                secret,
            )
            for key, child in value.items()
        }
    if isinstance(value, list):
        return [_redact_secret(child, secret) for child in value]
    if isinstance(value, str) and secret:
        return value.replace(secret, "[REDACTED]")
    return value


def _classify_media_url(value: str) -> str:
    try:
        parsed = urlsplit(value)
        hostname = parsed.hostname
        port = parsed.port
    except ValueError:
        return "invalid" if _looks_like_jiandaoyun_media_url(value) else "other"
    if hostname is None or hostname.lower() != JIANDAOYUN_MEDIA_HOST:
        return "other"
    if (
        parsed.scheme.lower() != "https"
        or parsed.username is not None
        or parsed.password is not None
        or port not in {None, 443}
        or bool(parsed.fragment)
    ):
        return "invalid"
    return "allowed"


def _looks_like_jiandaoyun_media_url(value: str) -> bool:
    lowered = value.lower()
    return lowered.startswith(
        (f"https://{JIANDAOYUN_MEDIA_HOST}", f"http://{JIANDAOYUN_MEDIA_HOST}")
    )


def _image_media_type(content_type: str) -> str | None:
    media_type = content_type.partition(";")[0].strip().lower()
    if _IMAGE_MEDIA_TYPE_RE.fullmatch(media_type) is None:
        return None
    return media_type


def _content_length(headers: httpx.Headers) -> int | None:
    raw_value = headers.get("Content-Length")
    if raw_value is None:
        return None
    try:
        value = int(raw_value)
    except ValueError as exc:
        raise UpstreamProxyError(
            "The media source returned an invalid response.",
            status_code=502,
            code="MEDIA_UPSTREAM_INVALID_RESPONSE",
        ) from exc
    if value < 0:
        raise UpstreamProxyError(
            "The media source returned an invalid response.",
            status_code=502,
            code="MEDIA_UPSTREAM_INVALID_RESPONSE",
        )
    return value


def _media_too_large() -> UpstreamProxyError:
    return UpstreamProxyError(
        "The media response exceeds the configured size limit.",
        status_code=413,
        code="MEDIA_TOO_LARGE",
    )


def _invalid_media_token() -> NotFoundError:
    return NotFoundError(
        "The media link is invalid.",
        code="MEDIA_TOKEN_INVALID",
    )


def _base64url_encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def _base64url_decode(value: str) -> bytes:
    if not value or not value.isascii():
        raise ValueError("Invalid base64url value")
    padding = "=" * (-len(value) % 4)
    decoded = base64.b64decode(
        value + padding,
        altchars=b"-_",
        validate=True,
    )
    if _base64url_encode(decoded) != value:
        raise ValueError("Non-canonical base64url value")
    return decoded
