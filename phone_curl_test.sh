#!/usr/bin/env sh

: "${OPENAI_API_KEY:?Set OPENAI_API_KEY before running this script}"

curl -sS --http1.1 -X POST 'https://trancloud.net/v1/responses' \
  -H "Authorization: Bearer ${OPENAI_API_KEY}" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -H 'User-Agent: Mozilla/5.0 (Linux; Android 15; HighTac AI) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36' \
  -H 'Origin: https://trancloud.net' \
  -H 'Referer: https://trancloud.net/' \
  --data-binary '@/data/local/tmp/hightac_text_req.json'
printf '\nHTTP_STATUS:'
curl -sS -o /dev/null -w '%{http_code}\n' --http1.1 -X POST 'https://trancloud.net/v1/responses' \
  -H "Authorization: Bearer ${OPENAI_API_KEY}" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -H 'User-Agent: Mozilla/5.0 (Linux; Android 15; HighTac AI) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36' \
  -H 'Origin: https://trancloud.net' \
  -H 'Referer: https://trancloud.net/' \
  --data-binary '@/data/local/tmp/hightac_text_req.json'
