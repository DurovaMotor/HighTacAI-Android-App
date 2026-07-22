# HighTac AI

HighTac AI is a single repository for the Android parts assistant and the
local-network sound-and-light finding platform. The platform keeps products,
light-strip bindings, station state, commands, Android devices, and audit logs
in one Windows-hosted backend so every phone sees the same data.

The coordinated platform release version is `2.0.0`; its expected immutable Git
tag is `v2.0.0`. Version `1.0.0` is retained only as the Windows upgrade-test
baseline.

Related WeChat Mini Program repository:
[DurovaMotor/HighTacAI-MiniProgram](https://github.com/DurovaMotor/HighTacAI-MiniProgram).

## Repository Layout

- `app/`: Kotlin/Jetpack Compose Android application.
- `contracts/`: canonical OpenAPI, WebSocket, and eStation MQTT contracts.
- `server/`: FastAPI, SQLite WAL, MQTT bridge, and Windows service control.
- `web/`: React/Vite administrator console.
- `installer/`: Windows service, firewall, packaging, and installer scripts.
- `tools/mqtt/`: local Mosquitto development helpers.
- `docs/`: architecture, ADRs, UI system, and field operations notes.

## Features

- Chinese advisor chat for motorcycle parts, models, and maintenance questions.
- Image attachment support for visual parts consultation.
- Reasoning-depth selector for OpenAI Responses API requests.
- Price lookup mode with multi-field filters for code, Chinese name, English name, model, and brand.
- JianDaoYun-backed product and price search with incremental loading feedback.
- Full-screen HighTac AI splash image shown at app launch.
- Central product-to-light-strip bindings for multiple Android phones.
- eStation base-station heartbeat, light command, acknowledgement, and battery status.
- NVIDIA-inspired administrator console for MQTT, stations, tags, bindings, logs, and backups.
- Windows Mosquitto and API service packaging with local-network-only firewall rules.

## Tech Stack

- Kotlin
- Jetpack Compose
- Android Gradle Plugin
- OkHttp
- Coil Compose
- OpenAI Responses API
- JianDaoYun API
- FastAPI and SQLAlchemy
- SQLite WAL and Alembic
- React, TypeScript, Vite, and Ant Design
- Eclipse Mosquitto and Paho MQTT

## Required Toolchains

- Temurin OpenJDK 21.0.11 LTS at `C:\Users\ooo\.jdks\jdk-21.0.11+10`.
- Android SDK.
- Python 3.12 for the platform server.
- Node.js/npm for the Web console.
- Eclipse Mosquitto for the local broker.

Before Android commands in PowerShell:

```powershell
$env:JAVA_HOME = 'C:\Users\ooo\.jdks\jdk-21.0.11+10'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

## Android Setup

Android never receives OpenAI or JianDaoYun API keys. The current build only
needs these non-secret JianDaoYun identifiers in `local.properties`:

```properties
JIANDAOYUN_APP_ID=your_jiandaoyun_app_id
JIANDAOYUN_ENTRY_ID=your_jiandaoyun_entry_id
```

They are public routing identifiers, not credentials. Keep them configurable and
do not commit `local.properties`. Android calls the HighTac backend proxy; it
must not call OpenAI or JianDaoYun with provider credentials.

Provider credentials belong only in the protected backend runtime environment:

```text
HIGHTAC_OPENAI_API_KEY
HIGHTAC_OPENAI_BASE_URL
HIGHTAC_OPENAI_MODEL
HIGHTAC_JIANDAOYUN_API_KEY
HIGHTAC_JIANDAOYUN_APP_ID
HIGHTAC_JIANDAOYUN_ENTRY_ID
HIGHTAC_JIANDAOYUN_BASE_URL
```

For an installed Windows platform these values are rendered into the ACL-locked
`C:\ProgramData\HighTac\Platform\config\.env`; never commit that file or print
its secrets in logs. Any OpenAI or JianDaoYun key that was embedded in an older
APK must be revoked and rotated at the provider before 2.0.0 deployment. Put
only the replacement keys in the protected backend, then verify the final APK
secret scan is clean.

## Build

```powershell
.\gradlew.bat :app:assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The debug build uses the `.next` application ID suffix.

## Tests

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

See `TEST_CHECKLIST.md` for manual device testing steps and logcat collection tips.

## Local Platform

The current field network is `Durova-5G`. The Windows host has static address
`192.168.1.105/24`; Android phones and the eStation base station must be on the
same LAN.

| Service | Address |
| --- | --- |
| Web/API | `http://192.168.1.105:8088` |
| MQTT broker | `192.168.1.105:1884` |
| Vite development UI | `http://192.168.1.105:5173` |

Start the project broker from the repository root. The command prompts for the
site password when it is not supplied, keeping it out of shell history:

```powershell
.\tools\mqtt\start-mosquitto.ps1 -Mode Native -Port 1884 -Username hightac_mqtt
```

Create the server environment once, then run the API with MQTT credentials in
process environment variables. Do not store the password in tracked files:

```powershell
Set-Location .\server
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[dev]"

$env:HIGHTAC_MQTT_USERNAME = 'hightac_mqtt'
$secure = Read-Host 'MQTT password' -AsSecureString
$credential = [pscredential]::new('mqtt', $secure)
$env:HIGHTAC_MQTT_PASSWORD = $credential.GetNetworkCredential().Password
$env:HIGHTAC_BROKER_MODE = 'subprocess'
$brokerConfig = (Resolve-Path '..\tools\mqtt\runtime\config\mosquitto.conf').Path
$env:HIGHTAC_BROKER_COMMAND = @(
    'C:\Program Files\Mosquitto\mosquitto.exe', '-c', $brokerConfig
) | ConvertTo-Json -Compress
.\.venv\Scripts\hightac-platform.exe serve
```

The `subprocess` mode lets the Web console start, stop, and restart the
repository-owned broker tracked by `tools/mqtt/runtime/mosquitto.pid`. It never
controls the unrelated system Mosquitto listener on `1883`.

Run the Web console in another PowerShell:

```powershell
Set-Location .\web
npm ci
npm run dev -- --host 0.0.0.0
```

The first administrator is `Adam`; the initial password must be changed after
the first login. Android does not use an account: the App enrolls a protected
device identity and receives a one-time device token automatically, without an
administrator approval step. Revoked tokens remain invalid; a fresh enrollment
creates a separately revocable installation.

When a local VPN sets `HTTP_PROXY`/`HTTPS_PROXY`, bypass it for LAN diagnostics:

```powershell
curl.exe --noproxy '*' http://192.168.1.105:8088/api/v1/health/ready
```

Do not disable the VPN or change its adapters while Codex is running. The
`--noproxy` option affects only that diagnostic request.

## Platform Verification

```powershell
.\contracts\.venv\Scripts\python.exe .\contracts\validate_contracts.py

Set-Location .\server
.\.venv\Scripts\python.exe -m pytest

Set-Location ..\web
npm test
npm run build
npm run test:e2e

Set-Location ..
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\installer\tests\Test-InstallerSkeleton.ps1
```

## Release Packaging

The one-command Windows pipeline defaults to platform version `2.0.0`:

```powershell
.\installer\packaging\Build-Release.ps1
```

The default `internal_lan` profile matches the near-zero-cost private-LAN
deployment. Without a public code-signing certificate it produces an explicitly
marked `internal_lan_unsigned` package; this is allowed for internal-LAN 2.0.0
and can never be reported as a signed public package. Selecting
`-DistributionTarget public_distribution` makes a valid timestamped
Authenticode signature mandatory. Both profiles require the final release APK
and reports bound to `v2.0.0` and both artifact SHA-256 values. Certificates,
PFX files, passwords, databases, and runtime logs are never committed.

Release commands, signing inputs, and the `1.0.0` to `2.0.0` Windows Sandbox
rehearsal are documented in `installer/README.md`. The authoritative evidence
template and gate checklist are:

- `docs/releases/v2.0.0-evidence.json`
- `docs/releases/v2.0.0-release-checklist.md`

Only the final 10-phone/2000-light-strip field-scale acceptance belongs to the
user. The release team still owns the final packaged-APK network smoke and the
Windows Sandbox install/upgrade/uninstall rehearsal.

Architecture and implementation decisions are documented in
`docs/hightac-web-platform-architecture-plan.md` and
`docs/hightac-web-ui-design-system.md`. Day-to-day startup, enrollment, backup,
and troubleshooting steps are in `docs/hightac-platform-operations.md`.
