from __future__ import annotations

from collections.abc import AsyncIterator, Callable, Iterator
from dataclasses import dataclass, field
import json
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient
from pydantic import SecretStr

from conftest import FakePublisher
from hightac_platform.config import Settings
from hightac_platform.db.models import AndroidDevice
from hightac_platform.domain.enums import DeviceStatus
from hightac_platform.main import create_app
from hightac_platform.utils import hash_token


DEVICE_TOKEN = "test-only-device-token"
OPENAI_KEY = "test-only-openai-placeholder"
JIANDAOYUN_KEY = "test-only-jiandaoyun-placeholder"


@dataclass(slots=True)
class UpstreamStub:
    responder: Callable[[httpx.Request], httpx.Response] = field(
        default=lambda request: httpx.Response(
            200,
            json={"ok": True},
            request=request,
        )
    )
    requests: list[httpx.Request] = field(default_factory=list)

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        return self.responder(request)


@dataclass(slots=True)
class ChunkedStream(httpx.AsyncByteStream):
    chunks: tuple[bytes, ...]

    async def __aiter__(self) -> AsyncIterator[bytes]:
        for chunk in self.chunks:
            yield chunk

    async def aclose(self) -> None:
        return None


@dataclass(slots=True)
class ProxyHarness:
    client: TestClient
    app: object
    settings: Settings
    upstream: UpstreamStub
    device_id: str

    @property
    def bearer(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {DEVICE_TOKEN}"}

    def last_seen(self) -> int:
        with self.app.state.runtime.database.session_factory() as session:
            device = session.get(AndroidDevice, self.device_id)
            assert device is not None
            return device.last_seen_at_ms


@pytest.fixture()
def proxy_harness(tmp_path: Path) -> Iterator[ProxyHarness]:
    settings = Settings(
        environment="test",
        data_dir=tmp_path,
        database_url=f"sqlite:///{(tmp_path / 'proxy-test.db').as_posix()}",
        bootstrap_admin_username="Adam",
        bootstrap_admin_password=SecretStr("Adam"),
        mqtt_enabled=False,
        mqtt_required_for_ready=False,
        command_scheduler_enabled=False,
        lifecycle_scheduler_enabled=False,
        log_to_file=False,
        openai_api_key=SecretStr(OPENAI_KEY),
        openai_base_url="https://openai-compatible.example",
        openai_model="server-controlled-model",
        jiandaoyun_api_key=SecretStr(JIANDAOYUN_KEY),
        jiandaoyun_app_id="server-app-id",
        jiandaoyun_entry_id="server-entry-id",
        jiandaoyun_base_url="https://jiandaoyun.example/api",
        _env_file=None,
    )
    upstream = UpstreamStub()
    app = create_app(
        settings,
        publisher=FakePublisher(),
        mobile_proxy_transport=httpx.MockTransport(upstream),
    )
    with TestClient(app) as client:
        with app.state.runtime.database.session_factory() as session:
            device = AndroidDevice(
                fingerprint_hash="f" * 64,
                manufacturer="Test",
                model="Phone",
                app_version="2.0.0",
                status=DeviceStatus.APPROVED.value,
                token_hash=hash_token(DEVICE_TOKEN, "device-token"),
                last_seen_at_ms=1,
            )
            session.add(device)
            session.commit()
            device_id = device.id
        yield ProxyHarness(client, app, settings, upstream, device_id)


def test_openai_proxy_forces_server_configuration_and_redacts_secret(
    proxy_harness: ProxyHarness,
) -> None:
    proxy_harness.upstream.responder = lambda request: httpx.Response(
        200,
        json={
            "id": "response-1",
            "output": [],
            "diagnostic": f"Bearer {OPENAI_KEY}",
        },
        request=request,
    )
    before = proxy_harness.last_seen()

    response = proxy_harness.client.post(
        "/api/v1/mobile/openai/responses",
        headers=proxy_harness.bearer,
        json={
            "model": "client-selected-model",
            "stream": True,
            "input": [{"role": "user", "content": "hello"}],
            "max_output_tokens": 800,
        },
    )

    assert response.status_code == 200, response.text
    assert response.headers["Cache-Control"] == "no-store"
    assert OPENAI_KEY not in response.text
    assert response.json()["diagnostic"] == "Bearer [REDACTED]"
    assert proxy_harness.last_seen() > before

    assert len(proxy_harness.upstream.requests) == 1
    request = proxy_harness.upstream.requests[0]
    assert str(request.url) == "https://openai-compatible.example/v1/responses"
    assert request.headers["Authorization"] == f"Bearer {OPENAI_KEY}"
    assert DEVICE_TOKEN not in request.headers["Authorization"]
    assert request.headers["Origin"] == "https://openai-compatible.example"
    payload = json.loads(request.content)
    assert payload["model"] == "server-controlled-model"
    assert payload["stream"] is False
    assert payload["input"][0]["content"] == "hello"
    assert OPENAI_KEY not in request.content.decode("utf-8")


def test_mobile_proxy_enforces_success_response_contract(
    proxy_harness: ProxyHarness,
) -> None:
    proxy_harness.upstream.responder = lambda request: httpx.Response(
        201,
        json={"ok": True},
        request=request,
    )
    normalized = proxy_harness.client.post(
        "/api/v1/mobile/openai/responses",
        headers=proxy_harness.bearer,
        json={"input": "hello"},
    )
    assert normalized.status_code == 200
    assert normalized.json() == {"ok": True}

    proxy_harness.upstream.responder = lambda request: httpx.Response(
        200,
        json=["not", "an", "object"],
        request=request,
    )
    rejected = proxy_harness.client.post(
        "/api/v1/mobile/openai/responses",
        headers=proxy_harness.bearer,
        json={"input": "hello"},
    )
    assert rejected.status_code == 502
    assert rejected.json()["error"]["code"] == "UPSTREAM_INVALID_RESPONSE"


@pytest.mark.parametrize(
    ("path", "expects_entry_id"),
    [
        ("v5/app/entry/list", False),
        ("v5/app/entry/widget/list", True),
        ("v5/app/entry/data/list", True),
    ],
)
def test_jiandaoyun_proxy_allows_only_catalog_operations_and_injects_ids(
    proxy_harness: ProxyHarness,
    path: str,
    expects_entry_id: bool,
) -> None:
    response = proxy_harness.client.post(
        f"/api/v1/mobile/jiandaoyun/{path}",
        headers=proxy_harness.bearer,
        json={
            "app_id": "client-app-id",
            "entry_id": "client-entry-id",
            "limit": 100,
        },
    )

    assert response.status_code == 200, response.text
    request = proxy_harness.upstream.requests[-1]
    assert request.url.path == f"/api/{path}"
    assert request.headers["Authorization"] == f"Bearer {JIANDAOYUN_KEY}"
    payload = json.loads(request.content)
    assert payload["app_id"] == "server-app-id"
    assert payload["limit"] == 100
    if expects_entry_id:
        assert payload["entry_id"] == "server-entry-id"
    else:
        assert "entry_id" not in payload


def test_jiandaoyun_proxy_accepts_identifier_free_mobile_payload(
    proxy_harness: ProxyHarness,
) -> None:
    response = proxy_harness.client.post(
        "/api/v1/mobile/jiandaoyun/v5/app/entry/data/list",
        headers=proxy_harness.bearer,
        json={"limit": 100},
    )

    assert response.status_code == 200, response.text
    request = proxy_harness.upstream.requests[-1]
    payload = json.loads(request.content)
    assert payload["app_id"] == "server-app-id"
    assert payload["entry_id"] == "server-entry-id"
    assert payload["limit"] == 100


def test_jiandaoyun_recursively_rewrites_media_and_public_get_succeeds(
    proxy_harness: ProxyHarness,
) -> None:
    first_source = (
        "https://files.jiandaoyun.com/attachments/product-a.png?download=1"
    )
    second_source = "https://files.jiandaoyun.com:443/attachments/product-b.webp"
    unrelated = "https://cdn.example.test/product-c.png"
    lookalike = "https://files.jiandaoyun.com.evil.test/product-d.png"
    subdomain = "https://cdn.files.jiandaoyun.com/product-e.png"
    image_bytes = b"\x89PNG\r\n\x1a\nproxy-image"

    def respond(request: httpx.Request) -> httpx.Response:
        if request.method == "POST":
            return httpx.Response(
                200,
                json={
                    "data": [
                        {
                            "image": first_source,
                            "nested": {"gallery": [second_source, unrelated]},
                            "lookalike": lookalike,
                            "subdomain": subdomain,
                        }
                    ]
                },
                request=request,
            )
        return httpx.Response(
            200,
            headers={
                "Content-Type": "image/png",
                "Set-Cookie": (
                    "upstream_session=secret; "
                    "Domain=files.jiandaoyun.com; Path=/"
                ),
            },
            content=image_bytes,
            request=request,
        )

    proxy_harness.upstream.responder = respond
    proxied = proxy_harness.client.post(
        "/api/v1/mobile/jiandaoyun/v5/app/entry/data/list",
        headers=proxy_harness.bearer,
        json={"limit": 10},
    )

    assert proxied.status_code == 200, proxied.text
    record = proxied.json()["data"][0]
    first_media_url = record["image"]
    second_media_url = record["nested"]["gallery"][0]
    assert first_media_url.startswith(
        "http://testserver/api/v1/mobile/media/"
    )
    assert second_media_url.startswith(
        "http://testserver/api/v1/mobile/media/"
    )
    assert record["nested"]["gallery"][1] == unrelated
    assert record["lookalike"] == lookalike
    assert record["subdomain"] == subdomain
    assert first_source not in proxied.text
    assert second_source not in proxied.text

    media = proxy_harness.client.get(first_media_url)

    assert media.status_code == 200, media.text
    assert media.content == image_bytes
    assert media.headers["Content-Type"] == "image/png"
    assert media.headers["Cache-Control"] == "private, no-store"
    assert media.headers["X-Content-Type-Options"] == "nosniff"

    second_media = proxy_harness.client.get(second_media_url)
    assert second_media.status_code == 200
    assert len(proxy_harness.upstream.requests) == 3
    first_media_request, second_media_request = proxy_harness.upstream.requests[-2:]
    assert first_media_request.method == "GET"
    assert str(first_media_request.url) == first_source
    assert second_media_request.url == httpx.URL(second_source)
    for media_request in (first_media_request, second_media_request):
        assert "Authorization" not in media_request.headers
        assert "Cookie" not in media_request.headers


@pytest.mark.parametrize(
    "source_url",
    [
        "http://files.jiandaoyun.com/insecure.png",
        "https://user@files.jiandaoyun.com/credentialed.png",
        "https://files.jiandaoyun.com:444/non-default-port.png",
        "https://files.jiandaoyun.com/fragment.png#section",
    ],
)
def test_jiandaoyun_rejects_disallowed_direct_media_origin_variants(
    proxy_harness: ProxyHarness,
    source_url: str,
) -> None:
    proxy_harness.upstream.responder = lambda request: httpx.Response(
        200,
        json={"image": source_url},
        request=request,
    )

    response = proxy_harness.client.post(
        "/api/v1/mobile/jiandaoyun/v5/app/entry/data/list",
        headers=proxy_harness.bearer,
        json={"limit": 10},
    )

    assert response.status_code == 502
    assert response.json()["error"]["code"] == "UPSTREAM_INVALID_RESPONSE"
    assert source_url not in response.text


def test_mobile_media_rejects_tampered_token_without_upstream_call(
    proxy_harness: ProxyHarness,
) -> None:
    media_url = _signed_media_url(proxy_harness)
    token = media_url.rsplit("/", 1)[-1]
    payload, signature = token.split(".", 1)
    replacement = "A" if signature[0] != "A" else "B"
    tampered = f"{payload}.{replacement}{signature[1:]}"

    response = proxy_harness.client.get(
        f"/api/v1/mobile/media/{tampered}"
    )

    assert response.status_code == 404
    assert response.json()["error"]["code"] == "MEDIA_TOKEN_INVALID"
    assert len(proxy_harness.upstream.requests) == 1


def test_mobile_media_rejects_expired_token_without_upstream_call(
    proxy_harness: ProxyHarness,
) -> None:
    now = [2_000_000_000.0]
    proxy_harness.app.state.runtime.mobile_proxy_service._clock = lambda: now[0]
    media_url = _signed_media_url(proxy_harness)
    now[0] += proxy_harness.settings.mobile_media_token_ttl_seconds

    response = proxy_harness.client.get(media_url)

    assert response.status_code == 410
    assert response.json()["error"]["code"] == "MEDIA_TOKEN_EXPIRED"
    assert len(proxy_harness.upstream.requests) == 1


def test_mobile_media_does_not_follow_redirects_or_leak_locations(
    proxy_harness: ProxyHarness,
) -> None:
    source_url = "https://files.jiandaoyun.com/redirecting.png"
    media_url = _signed_media_url(proxy_harness, source_url)
    redirect_location = "https://attacker.example.test/stolen.png"
    proxy_harness.upstream.responder = lambda request: httpx.Response(
        302,
        headers={"Location": redirect_location},
        request=request,
    )

    response = proxy_harness.client.get(media_url)

    assert response.status_code == 502
    assert response.json()["error"]["code"] == "MEDIA_REDIRECT_NOT_ALLOWED"
    assert source_url not in response.text
    assert redirect_location not in response.text
    assert len(proxy_harness.upstream.requests) == 2


def test_mobile_media_rejects_non_image_content(
    proxy_harness: ProxyHarness,
) -> None:
    media_url = _signed_media_url(proxy_harness)
    proxy_harness.upstream.responder = lambda request: httpx.Response(
        200,
        headers={"Content-Type": "text/html"},
        content=b"not an image",
        request=request,
    )

    response = proxy_harness.client.get(media_url)

    assert response.status_code == 415
    assert response.json()["error"]["code"] == "MEDIA_TYPE_NOT_ALLOWED"


def test_mobile_media_enforces_streamed_size_limit_without_leaking_source(
    proxy_harness: ProxyHarness,
) -> None:
    source_url = "https://files.jiandaoyun.com/oversized.png?secret=opaque"
    media_url = _signed_media_url(proxy_harness, source_url)
    proxy_harness.settings.mobile_media_max_bytes = 5
    proxy_harness.upstream.responder = lambda request: httpx.Response(
        200,
        headers={"Content-Type": "image/png"},
        stream=ChunkedStream((b"123", b"456")),
        request=request,
    )

    response = proxy_harness.client.get(media_url)

    assert response.status_code == 413
    assert response.json()["error"]["code"] == "MEDIA_TOO_LARGE"
    assert source_url not in response.text
    assert JIANDAOYUN_KEY not in response.text


def test_mobile_media_maps_timeout_without_leaking_source(
    proxy_harness: ProxyHarness,
) -> None:
    source_url = "https://files.jiandaoyun.com/slow.png?secret=opaque"
    media_url = _signed_media_url(proxy_harness, source_url)

    def timeout(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("test media timeout", request=request)

    proxy_harness.upstream.responder = timeout
    response = proxy_harness.client.get(media_url)

    assert response.status_code == 504
    assert response.json()["error"]["code"] == "MEDIA_UPSTREAM_TIMEOUT"
    assert source_url not in response.text
    assert JIANDAOYUN_KEY not in response.text


def test_jiandaoyun_proxy_rejects_non_allowlisted_path_without_upstream_call(
    proxy_harness: ProxyHarness,
) -> None:
    response = proxy_harness.client.post(
        "/api/v1/mobile/jiandaoyun/v5/app/entry/data/create",
        headers=proxy_harness.bearer,
        json={"data": {}},
    )

    assert response.status_code == 404
    assert response.json()["error"]["code"] == "MOBILE_PROXY_PATH_NOT_ALLOWED"
    assert proxy_harness.upstream.requests == []


def test_mobile_proxy_requires_device_bearer_and_refreshes_last_seen_on_503(
    proxy_harness: ProxyHarness,
) -> None:
    missing_bearer = proxy_harness.client.post(
        "/api/v1/mobile/openai/responses",
        json={"input": "hello"},
    )
    assert missing_bearer.status_code == 401

    proxy_harness.settings.openai_api_key = None
    before = proxy_harness.last_seen()
    unconfigured = proxy_harness.client.post(
        "/api/v1/mobile/openai/responses",
        headers=proxy_harness.bearer,
        json={"input": "hello"},
    )

    assert unconfigured.status_code == 503
    assert unconfigured.json()["error"]["code"] == "OPENAI_NOT_CONFIGURED"
    assert proxy_harness.last_seen() > before
    assert proxy_harness.upstream.requests == []


def test_jiandaoyun_proxy_reports_missing_runtime_configuration(
    proxy_harness: ProxyHarness,
) -> None:
    proxy_harness.settings.jiandaoyun_app_id = None
    response = proxy_harness.client.post(
        "/api/v1/mobile/jiandaoyun/v5/app/entry/list",
        headers=proxy_harness.bearer,
        json={"limit": 100},
    )
    assert response.status_code == 503
    assert response.json()["error"]["code"] == "JIANDAOYUN_NOT_CONFIGURED"

    proxy_harness.settings.jiandaoyun_app_id = "server-app-id"
    proxy_harness.settings.jiandaoyun_entry_id = None
    response = proxy_harness.client.post(
        "/api/v1/mobile/jiandaoyun/v5/app/entry/data/list",
        headers=proxy_harness.bearer,
        json={"limit": 100},
    )
    assert response.status_code == 503
    assert response.json()["error"]["code"] == "JIANDAOYUN_NOT_CONFIGURED"


def test_mobile_proxy_enforces_json_object_and_request_size(
    proxy_harness: ProxyHarness,
) -> None:
    unsupported = proxy_harness.client.post(
        "/api/v1/mobile/openai/responses",
        headers={**proxy_harness.bearer, "Content-Type": "text/plain"},
        content="hello",
    )
    assert unsupported.status_code == 415

    not_an_object = proxy_harness.client.post(
        "/api/v1/mobile/openai/responses",
        headers=proxy_harness.bearer,
        json=["hello"],
    )
    assert not_an_object.status_code == 422
    assert not_an_object.json()["error"]["details"][0]["code"] == "OBJECT_REQUIRED"

    proxy_harness.settings.mobile_proxy_max_request_bytes = 1024
    too_large = proxy_harness.client.post(
        "/api/v1/mobile/openai/responses",
        headers=proxy_harness.bearer,
        json={"input": "x" * 2048},
    )
    assert too_large.status_code == 413
    assert too_large.json()["error"]["code"] == "PAYLOAD_TOO_LARGE"
    assert proxy_harness.upstream.requests == []


@pytest.mark.parametrize(
    ("upstream_status", "expected_status", "expected_code"),
    [
        (400, 400, "UPSTREAM_REJECTED_REQUEST"),
        (401, 502, "UPSTREAM_AUTHENTICATION_FAILED"),
        (429, 429, "UPSTREAM_RATE_LIMITED"),
        (503, 503, "UPSTREAM_UNAVAILABLE"),
    ],
)
def test_mobile_proxy_maps_upstream_errors_without_returning_upstream_body(
    proxy_harness: ProxyHarness,
    upstream_status: int,
    expected_status: int,
    expected_code: str,
) -> None:
    proxy_harness.upstream.responder = lambda request: httpx.Response(
        upstream_status,
        json={"error": f"upstream echoed {OPENAI_KEY}"},
        request=request,
    )

    response = proxy_harness.client.post(
        "/api/v1/mobile/openai/responses",
        headers=proxy_harness.bearer,
        json={"input": "hello"},
    )

    assert response.status_code == expected_status
    assert response.json()["error"]["code"] == expected_code
    assert OPENAI_KEY not in response.text
    assert "upstream echoed" not in response.text


def test_mobile_proxy_maps_transport_timeout(proxy_harness: ProxyHarness) -> None:
    def timeout(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("test timeout", request=request)

    proxy_harness.upstream.responder = timeout
    response = proxy_harness.client.post(
        "/api/v1/mobile/openai/responses",
        headers=proxy_harness.bearer,
        json={"input": "hello"},
    )

    assert response.status_code == 504
    assert response.json()["error"]["code"] == "UPSTREAM_TIMEOUT"


def _signed_media_url(
    proxy_harness: ProxyHarness,
    source_url: str = "https://files.jiandaoyun.com/product.png?token=opaque",
) -> str:
    proxy_harness.upstream.responder = lambda request: httpx.Response(
        200,
        json={"image": source_url},
        request=request,
    )
    response = proxy_harness.client.post(
        "/api/v1/mobile/jiandaoyun/v5/app/entry/data/list",
        headers=proxy_harness.bearer,
        json={"limit": 10},
    )
    assert response.status_code == 200, response.text
    media_url = response.json()["image"]
    assert media_url.startswith("http://testserver/api/v1/mobile/media/")
    return media_url
