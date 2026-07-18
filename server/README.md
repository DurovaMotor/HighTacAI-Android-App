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

## Mobile third-party proxies

Approved Android devices call third-party services through the platform with
their HighTac device bearer token. Configure the upstream integrations only in
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
