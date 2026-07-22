# HighTac Platform Server

Python 3.12 FastAPI backend for the HighTac local-network management platform.

## Development

```powershell
cd server
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[dev]"
.\.venv\Scripts\hightac-platform.exe migrate
.\.venv\Scripts\hightac-platform.exe serve
```

The API listens on `http://0.0.0.0:8088` by default. Runtime databases, logs, and
backups are written below `server/runtime/`, which is ignored by Git.

Configuration uses `HIGHTAC_` environment variables. In particular, set
`HIGHTAC_MQTT_USERNAME` and `HIGHTAC_MQTT_PASSWORD` at runtime when the broker
requires authentication. Do not place credentials in tracked files.

First-run administrator creation is controlled by `HIGHTAC_BOOTSTRAP_ADMIN`.
When enabled, set `HIGHTAC_BOOTSTRAP_ADMIN_USERNAME` and provide
`HIGHTAC_BOOTSTRAP_ADMIN_PASSWORD` through the process environment or deployment
secret store. Production configuration is rejected when bootstrap is enabled
without an explicit password. The password is consumed only to create its hash
and is excluded from settings serialization.

The default broker endpoint is `127.0.0.1:1884`. Broker supervision defaults to
read-only unmanaged detection; choose `subprocess` or `windows_service` explicitly
before enabling start/stop controls.

## Android zero-registration access

Android application operations on the trusted LAN do not require device
enrollment, administrator approval, or a bearer token. Tokenless reads, binding
and tag writes, light commands, Android binding migration, mobile proxies, and
the realtime event stream use an anonymous Android actor. Apps send a stable,
random `X-Android-Installation-Id` UUID so different phones receive separate
audit and idempotency attribution without registration. The header is non-secret
and is never treated as authentication; omitting it uses the fixed fallback actor
`Anonymous Android app` (`00000000-0000-0000-0000-000000000000`).

Valid device tokens issued by older releases remain accepted for per-device
attribution, but are optional. Invalid, expired, or revoked legacy bearer tokens
fall back to the anonymous Android actor. Enrollment endpoints and stored device
records remain only for backward compatibility; normal App startup must not
depend on them.

Administrator-only endpoints still require an administrator session. Browser
writes presenting an administrator cookie must also pass CSRF and initial
password-change checks, and cannot fall through to anonymous Android access.
Anonymous Android access never grants administrator sessions, broker controls,
configuration changes, product administration, backups, or operation-log
access.

Zero-registration and mobile-proxy routes are intended only for the trusted
site LAN or an authenticated VPN. Do not expose the API port directly to the
public Internet. Network firewalls and VPN access control are the security
boundary for these tokenless Android capabilities.

## Mobile third-party proxies

Android devices call third-party services through the platform without a device
token. Configure the upstream integrations only in
the protected runtime environment:

- `HIGHTAC_OPENAI_API_KEY`, `HIGHTAC_OPENAI_BASE_URL`, and
  `HIGHTAC_OPENAI_MODEL`
- `HIGHTAC_JIANDAOYUN_API_KEY`, `HIGHTAC_JIANDAOYUN_APP_ID`,
  `HIGHTAC_JIANDAOYUN_ENTRY_ID`, and `HIGHTAC_JIANDAOYUN_BASE_URL`

Empty or missing API keys are valid startup configuration. Calls to an
unconfigured integration return a provider-specific `503` response. The OpenAI
proxy fixes the model server-side and disables streaming. The JianDaoYun proxy
accepts only entry list, widget list, and data list operations; it overwrites
client-supplied application and form identifiers. API keys are excluded from
settings serialization and must never be stored in the Android package or Git.

## Tests

```powershell
.\.venv\Scripts\python.exe -m pytest
```

Tests use temporary migrated SQLite databases and do not write schema through
application startup helpers outside Alembic.
