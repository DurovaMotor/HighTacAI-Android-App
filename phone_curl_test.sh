#!/usr/bin/env sh

: "${HIGHTAC_DEVICE_TOKEN:?Set the approved Android device token before running this test}"

api_base="${HIGHTAC_PLATFORM_API_BASE:-http://192.168.1.105:8088/api/v1}"
request_file="${HIGHTAC_REQUEST_FILE:-/data/local/tmp/hightac_text_req.json}"
endpoint="${api_base%/}/mobile/openai/responses"

curl -sS --http1.1 -X POST "$endpoint" \
  -H "Authorization: Bearer ${HIGHTAC_DEVICE_TOKEN}" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  --data-binary "@$request_file"
printf '\nHTTP_STATUS:'
curl -sS -o /dev/null -w '%{http_code}\n' --http1.1 -X POST "$endpoint" \
  -H "Authorization: Bearer ${HIGHTAC_DEVICE_TOKEN}" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  --data-binary "@$request_file"
