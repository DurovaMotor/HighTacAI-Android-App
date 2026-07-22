from __future__ import annotations

import json
from typing import Any

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, Response

from hightac_platform.api.dependencies import (
    MobileProxyDeviceDependency,
    get_runtime,
)
from hightac_platform.domain.errors import (
    BadRequestError,
    PayloadTooLargeError,
    UnsupportedMediaTypeError,
    ValidationError,
)


router = APIRouter(prefix="/mobile", tags=["Mobile proxy"])


@router.post("/openai/responses", deprecated=True)
async def proxy_openai_responses(
    request: Request,
    _actor: MobileProxyDeviceDependency,
) -> JSONResponse:
    runtime = get_runtime(request)
    payload = await _read_json_object(
        request,
        max_bytes=runtime.settings.mobile_proxy_max_request_bytes,
    )
    result = await runtime.mobile_proxy_service.forward_openai_responses(payload)
    return JSONResponse(
        status_code=result.status_code,
        content=result.payload,
        headers={"Cache-Control": "no-store"},
    )


@router.post("/jiandaoyun/v5/app/entry/list", deprecated=True)
async def proxy_jiandaoyun_entry_list(
    request: Request,
    _actor: MobileProxyDeviceDependency,
) -> JSONResponse:
    return await _proxy_jiandaoyun("v5/app/entry/list", request)


@router.post("/jiandaoyun/v5/app/entry/widget/list", deprecated=True)
async def proxy_jiandaoyun_widget_list(
    request: Request,
    _actor: MobileProxyDeviceDependency,
) -> JSONResponse:
    return await _proxy_jiandaoyun("v5/app/entry/widget/list", request)


@router.post("/jiandaoyun/v5/app/entry/data/list", deprecated=True)
async def proxy_jiandaoyun_data_list(
    request: Request,
    _actor: MobileProxyDeviceDependency,
) -> JSONResponse:
    return await _proxy_jiandaoyun("v5/app/entry/data/list", request)


@router.get("/media/{token}", name="get_mobile_media", response_class=Response)
async def get_mobile_media(token: str, request: Request) -> Response:
    result = await get_runtime(request).mobile_proxy_service.fetch_media(token)
    return Response(
        content=result.content,
        media_type=result.media_type,
        headers={
            "Cache-Control": "private, no-store",
            "Content-Security-Policy": "sandbox; default-src 'none'",
            "X-Content-Type-Options": "nosniff",
        },
    )


@router.post("/jiandaoyun/{path:path}", include_in_schema=False)
async def reject_unknown_jiandaoyun_path(
    path: str,
    request: Request,
    _actor: MobileProxyDeviceDependency,
) -> JSONResponse:
    return await _proxy_jiandaoyun(path, request)


async def _proxy_jiandaoyun(path: str, request: Request) -> JSONResponse:
    runtime = get_runtime(request)
    payload = await _read_json_object(
        request,
        max_bytes=runtime.settings.mobile_proxy_max_request_bytes,
    )
    result = await runtime.mobile_proxy_service.forward_jiandaoyun(
        path,
        payload,
        media_url_builder=lambda token: str(
            request.url_for("get_mobile_media", token=token)
        ),
    )
    return JSONResponse(
        status_code=result.status_code,
        content=result.payload,
        headers={"Cache-Control": "no-store"},
    )


async def _read_json_object(request: Request, *, max_bytes: int) -> dict[str, Any]:
    content_type = request.headers.get("Content-Type", "")
    media_type = content_type.partition(";")[0].strip().lower()
    if media_type != "application/json" and not media_type.endswith("+json"):
        raise UnsupportedMediaTypeError(
            "Mobile proxy requests must use application/json."
        )

    content_length = request.headers.get("Content-Length")
    if content_length:
        try:
            declared_size = int(content_length)
        except ValueError as exc:
            raise BadRequestError("Content-Length is invalid.") from exc
        if declared_size > max_bytes:
            raise PayloadTooLargeError(
                f"Mobile proxy request bodies are limited to {max_bytes} bytes."
            )

    chunks: list[bytes] = []
    received = 0
    async for chunk in request.stream():
        received += len(chunk)
        if received > max_bytes:
            raise PayloadTooLargeError(
                f"Mobile proxy request bodies are limited to {max_bytes} bytes."
            )
        chunks.append(chunk)
    raw_body = b"".join(chunks)
    if not raw_body:
        raise BadRequestError("A JSON request body is required.")
    try:
        payload = json.loads(raw_body)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise BadRequestError("The request body is not valid JSON.") from exc
    if not isinstance(payload, dict):
        raise ValidationError(
            "The request body must be a JSON object.",
            details=[
                {
                    "field": None,
                    "code": "OBJECT_REQUIRED",
                    "message": "The top-level JSON value must be an object.",
                }
            ],
        )
    return payload
