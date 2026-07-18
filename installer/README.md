# HighTac Windows production deployment

This directory owns the Windows x64 packaging and deployment path for HighTac
Platform. It targets Windows 10 1809 / Windows Server 2019 or newer and does not
check vendor binaries, generated installers, runtime data, or secrets into Git.

The coordinated platform release version is `2.0.0`, with expected Git tag
`v2.0.0`. Version `1.0.0` remains supported as the upgrade rehearsal baseline.

The future real 10-phone/2000-tag field acceptance is outside this package gate.

## Owned resources

HighTac manages exactly these resources:

| Resource | Value |
| --- | --- |
| Platform service | `HighTacPlatform` |
| MQTT broker service | `HighTacMqttBroker` |
| Web/API listener | TCP `8088` by default |
| MQTT listener | TCP `1884` |
| Runtime data | `C:\ProgramData\HighTac\Platform` |
| Application files | `C:\Program Files\HighTac\Platform` |

The scripts never stop, reconfigure, or remove the unrelated Windows service
named `mosquitto`, and never manage port `1883`. A fresh install fails if another
process owns the selected Web port or `1884`.

Both HighTac services use Automatic start, depend on Windows `Tcpip`, run as
LocalSystem, and configure three escalating restart recovery actions. Upgrade and
uninstall stop platform before broker; install starts broker before platform.

`HighTacPlatform` intentionally does not declare an SCM dependency on
`HighTacMqttBroker`. Windows refuses to stop a service while a running dependent
service exists, which would break the Web broker Stop action. Instead, the
platform WinSW wrapper runs `Start-HighTacPlatformService.ps1`. That launcher:

1. validates the fixed Program Files and ProgramData roots;
2. rejects a linked or broadly readable `.env` file;
3. parses only quoted `HIGHTAC_*` values into the child-process environment;
4. starts and waits for the owned broker service and local MQTT listener;
5. starts `HighTacPlatform.exe serve` and propagates its exit code to WinSW.

The backend controls only `HighTacMqttBroker` through SCM. A database restore
exits the packaged backend with a failure code; WinSW/SCM then performs a real
service-process restart.

## Installed layout

Immutable application payload:

```text
C:\Program Files\HighTac\Platform\
  server\                       PyInstaller one-folder backend and Web dist
  mosquitto\                    curated Mosquitto files plus signed app-local VC++ runtime DLLs
  service\                      renamed WinSW wrappers and XML
  dependencies\                 signed VC_redist.x64.exe
  tools\                        deployment, launcher, and health scripts
  templates\                    placeholder-only runtime templates
  licenses\                     upstream licenses and factual provenance
  BUILD-MANIFEST.json
  SHA256SUMS.txt
```

Protected state, preserved on upgrade and by default on uninstall:

```text
C:\ProgramData\HighTac\Platform\
  config\.env                   backend runtime secret; never shown to users
  config\platform.yaml          non-secret deployment manifest
  config\install-state.json     non-secret installer state
  config\station-mqtt-credentials.txt
  db\hightac.db
  backups\
  logs\
  secrets\
  mqtt\mosquitto.conf
  mqtt\passwordfile             Mosquitto hashes only
  mqtt\aclfile
  mqtt\data\
  run\
```

ProgramData is restricted to LocalSystem and local Administrators. On a fresh
install, the installer keeps the initial Web username `Adam`, generates an
independent 32-character cryptographically random one-time password, and writes
it only to protected `.env` and the protected operator file. The server creates
that account with `must_change_password=true`. The operator file contains the
app/admin endpoint, one-time initial login, and station provisioning values, but
never the platform MQTT password. Change the initial admin password at first
login, record the station secret in an approved password manager, and then delete
the operator file. Do not place that file in tickets, chat, source control, build
output, or logs.

When upgrading an older managed installation, the installer adds these bootstrap
environment keys only when absent and uses a newly generated random value for a
missing bootstrap password. Existing environment values and the database
account/password are not overwritten; the completed-bootstrap database marker
prevents recreating the administrator.

## Integration credential operations

Run `Set-HighTacIntegrationCredentials.ps1` from an elevated PowerShell when
rotating the OpenAI or JianDaoYun mobile-proxy settings. The installed copy is
under `C:\Program Files\HighTac\Platform\tools`. It writes only the existing
`C:\ProgramData\HighTac\Platform\config\.env`; an alternate `DataRoot`, a linked
path, a broadly readable ACL, a malformed dotenv file, or a duplicate variable
is rejected before any credential write.

Prompt for API keys as `SecureString` values. Never put plaintext keys in a
command line, `.ps1` file, shell history, transcript, ticket, or installer log:

```powershell
$credentialTool = Join-Path $env:ProgramFiles `
  'HighTac\Platform\tools\Set-HighTacIntegrationCredentials.ps1'
$openAiKey = Read-Host 'OpenAI API key' -AsSecureString
$jianDaoYunKey = Read-Host 'JianDaoYun API key' -AsSecureString
try {
  & $credentialTool `
    -OpenAiApiKey $openAiKey `
    -OpenAiBaseUrl 'https://api.openai.com/v1' `
    -OpenAiModel 'gpt-5.5' `
    -JianDaoYunApiKey $jianDaoYunKey `
    -JianDaoYunAppId 'APP_ID' `
    -JianDaoYunEntryId 'ENTRY_ID' `
    -JianDaoYunBaseUrl 'https://api.jiandaoyun.com/api' `
    -RestartService
}
finally {
  $openAiKey.Dispose()
  $jianDaoYunKey.Dispose()
}
```

Supply only the provider fields being changed. `-ClearOpenAiApiKey` and
`-ClearJianDaoYunApiKey` explicitly write an empty quoted value; a clear switch
cannot be combined with the corresponding key parameter. For example:

```powershell
& $credentialTool -ClearOpenAiApiKey -RestartService
```

Without `-RestartService`, the script leaves the `HighTacPlatform` service state
unchanged and the new settings load on its next start. With the switch, the
script atomically commits the file first, then stops and starts only
`HighTacPlatform`; it never restarts `HighTacMqttBroker`. `-WhatIf` previews the
target/action without writing or changing a service. `-Force` suppresses the
high-impact confirmation and should be reserved for controlled automation.

`-UseProcessEnvironment` is available for managed automation. It reads the
`HIGHTAC_OPENAI_*` and `HIGHTAC_JIANDAOYUN_*` process variables, with the
supported unprefixed provider aliases as fallback. Process environment values
are plaintext, so inject them only into the short-lived elevated process and
remove them immediately afterward. Interactive `SecureString` input is the
preferred operator path.

The writer builds a complete UTF-8-without-BOM file beside `.env`, flushes and
restricts that temporary file to LocalSystem and Administrators, and performs a
same-directory write-through atomic replacement. It does not create a backup,
returns setting names/status only, and removes a temporary file on failure. A
failure before replacement leaves the original `.env` intact. If replacement
succeeds but the requested service start fails, the new settings remain in
place; diagnose the service and run `Start-Service HighTacPlatform` after the
cause is corrected.

The ProgramData `.env` is runtime secret state, not a deployment artifact.
Repository ignore rules cover `.env` and `.env.*`, but operators must still
never copy, stage, commit, attach, or paste that file. Keep it out of release
payloads and release evidence as well.

## MQTT credential modes

### New station

A normal fresh install generates independent random 32-character platform and
station MQTT passwords. It hashes both with the bundled official
`mosquitto_passwd -U`, stores the platform plaintext only in protected `.env`,
and writes the station value once to the protected operator file.

The generated station username is `estation_<STATION_SN>`. Station SN input must
exactly match `^90A9F[0-9A-F]{7}$`; lowercase input is rejected.

### Existing hightac_mqtt station

Use the explicit legacy import when the deployed station already uses the
unrecoverable password in an existing Mosquitto password file. In the wizard,
select **Existing station credential** and choose the reviewed password file.

For the current development machine the source is:

```text
tools\mqtt\runtime\config\passwordfile
```

The import path:

- requires exactly one account named `hightac_mqtt`;
- requires a modern Mosquitto `$7$` hash and never displays it;
- creates a new independent random `hightac_backend` password;
- appends the reviewed station hash to the new managed password file;
- renders a new least-privilege station ACL for the configured strict SN;
- does not import the broad legacy ACL;
- records `legacy_hash_import` and that the password is not reprintable;
- never stages or packages the source password file.

The station remains configured with its existing password. The operator output
states that the password was preserved but cannot be recovered or printed.

Command-line fresh install can pass:

```text
/STATIONID=90A9F1234567 /LEGACYPASSWORDFILE=C:\reviewed\passwordfile
```

Legacy import is rejected once a managed ProgramData credential set exists.
Upgrades and reinstalls always preserve that managed set.

## Build inputs

`Build-Release.ps1 -ProbeOnly` discovers local inputs without downloading:

- `server\.venv\Scripts\python.exe` with Python 3.12 and PyInstaller;
- Node.js/npm plus the checked-in Web lockfile;
- per-machine or per-user Inno Setup 6, including
  `%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe`;
- official Mosquitto under `%ProgramFiles%\Mosquitto`;
- ignored `installer\build\vendor\...\WinSW-x64.exe` and its actual license;
- a signed Microsoft `VC_redist.x64.exe` in the Windows Package Cache; the
  matching signed app-local runtime DLLs are taken from the packaged backend
  and copied beside Mosquitto for clean-Windows startup.

No product EULA is invented. `-ProductLicenseFile` is optional and creates an
installer license page only when the release owner supplies approved terms.
Mosquitto and WinSW ship their actual upstream license texts. The VC++ record
contains exact version, SHA-256, Authenticode status/signer, and official links;
it explicitly is not a license grant. The release owner remains responsible for
distribution rights.

Probe from Windows PowerShell 5.1 or PowerShell 7:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\installer\packaging\Build-Release.ps1 `
  -AppVersion 2.0.0 `
  -ProbeOnly
```

The Python probe is deliberately quote-free so native argument handling is the
same in both PowerShell generations.

## One-command build

For a reproducible release, allow `npm ci`:

```powershell
.\installer\packaging\Build-Release.ps1
```

For an offline/local rehearsal using the already installed locked Web modules:

```powershell
.\installer\packaging\Build-Release.ps1 `
  -AppVersion 2.0.0 `
  -SkipNpmCi
```

Both `Build-Release.ps1` and `Build-Installer.ps1` default to `2.0.0`. Passing
`-AppVersion 1.0.0` remains valid when reproducing the upgrade baseline.

The pipeline builds Vite assets, builds the Python one-folder application,
stages only curated Mosquitto/WinSW/VC++ files, writes component/checksum
manifests, scans for runtime data and rendered secrets, and compiles Inno Setup.
Outputs stay under ignored `installer\build`:

```text
installer\build\web-dist\
installer\build\pyinstaller\dist\HighTacPlatform\
installer\build\payload\
installer\build\installer\HighTacPlatform-<version>-x64.exe
installer\build\installer\HighTacPlatform-<version>-x64.exe.sha256
installer\build\installer\HighTacPlatform-<version>-x64.exe.release.json
installer\build\installer\HighTacPlatform-<version>-release-report.json
installer\build\installer\HighTacPlatform-<version>-release-report.md
```

## Distribution target and Authenticode signing

The default target is `internal_lan`, matching the first-version private-LAN,
near-zero-cost deployment. Authenticode is optional for that target. If no
certificate is supplied, metadata uses `internal_lan_unsigned` and the visible
notice `INTERNAL LAN UNSIGNED PACKAGE - permitted only for private LAN
deployment; never public distribution.` This is a known, non-blocking state for
the internal-LAN 2.0.0 release, not a signed public package.

`public_distribution` automatically makes Authenticode a hard gate. It cannot
build successfully without a valid code-signing certificate and timestamp. The
`-RequireSignedInstaller` switch remains available to require signing for an
internal build or CI policy as well.

Preferred automation uses a code-signing certificate already installed in the
Windows certificate store:

```powershell
.\installer\packaging\Build-Release.ps1 `
  -DistributionTarget public_distribution `
  -SigningCertificateThumbprint 'YOUR_CODE_SIGNING_CERTIFICATE_THUMBPRINT' `
  -SigningCertificateStore CurrentUser `
  -RequireSignedInstaller `
  -AndroidApkPath .\app\build\outputs\apk\release\app-release.apk
```

A release owner may instead provide a PFX outside the repository. Prompt for
the password as a `SecureString`; never put it in the command line, environment,
tracked files, logs, or the release report:

```powershell
$pfxPassword = Read-Host 'Code-signing PFX password' -AsSecureString
.\installer\packaging\Build-Release.ps1 `
  -DistributionTarget public_distribution `
  -SigningPfxPath 'C:\Secure\HighTac-Code-Signing.pfx' `
  -SigningPfxPassword $pfxPassword `
  -RequireSignedInstaller `
  -AndroidApkPath .\app\build\outputs\apk\release\app-release.apk
```

The certificate must have a private key, be within its validity period, and
explicitly contain the Code Signing EKU. The default timestamp service is
`http://timestamp.digicert.com`; override it with `-TimestampServer` only with
an approved RFC 3161/Authenticode timestamp service. After signing, the build
requires live Authenticode status `Valid`, verifies the signer thumbprint and
timestamp, then calculates SHA-256 from the signed bytes. No certificate or PFX
is generated or copied into the payload.

## Release report

`Build-Release.ps1` calls `New-ReleaseReport.ps1` after the installer has its
final signed or unsigned bytes and checksum. For `2.0.0`, it automatically loads
`docs\releases\v2.0.0-evidence.json`. The JSON and Markdown reports bind:

- expected tag `v2.0.0`, current commit, tags at HEAD, and clean-worktree state;
- exact APK and final installer SHA-256 values;
- `internal_lan` or `public_distribution`, plus live Authenticode status and
  signed/unsigned classification;
- automated test matrix, real-phone/real-light-strip smoke, and Sandbox status;
- the external user-owned 10-phone/2000-light-strip scale acceptance boundary.

`ReleaseReady` is true only when the expected tag points at the clean HEAD, both
artifacts are present, and every release gate in the evidence file is `passed`.
For `public_distribution`, a valid timestamped signature is additionally
mandatory. For `internal_lan`, `internal_lan_unsigned` is allowed and remains
explicit in every report. The 10-phone/2000-light-strip scale exercise is the
only non-gating user-owned item. See
`docs\releases\v2.0.0-release-checklist.md` before tagging.

## Install, upgrade, and uninstall

The interactive installer requires elevation. Fresh install collects a site
name, strict uppercase station SN, optional adapter alias, Web port, and optional
legacy password file. Except in explicit Sandbox rehearsal mode, it requires a
connected static IPv4 adapter on the Windows Private profile with a gateway.
Firewall rules are inbound TCP, Private profile, and `LocalSubnet` only.

A managed upgrade is detected from preserved ProgramData (`install-state.json`
or the complete managed environment/password-file pair). It verifies the staged
payload, stops only owned services, replaces Program Files content, refreshes
service definitions, reuses the managed configuration, DB, broker persistence,
logs, and backups, then runs health checks.

If managed ProgramData does not exist but the previous application directory
contains `server\runtime`, setup keeps the fresh-configuration pages and copies
only `db`, `backups`, `logs`, and `secrets` into ProgramData before first start.
The source tree is retained. Symbolic links, junctions, unreadable files, or a
different file at the same ProgramData path stop installation instead of being
followed or overwritten; an identical prior copy is accepted for safe retry.

The uninstaller defaults to keeping ProgramData. Interactive delete requires an
explicit Yes. Silent uninstall also keeps data unless `/DELETEDATA=1` is passed.
Both paths remove owned services and exact firewall rules before Inno removes
application files.

## Static and artifact tests

These tests do not install services:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\installer\tests\Test-InstallerSkeleton.ps1

powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\installer\tests\Test-IntegrationCredentials.ps1

powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\installer\tests\Test-ReleaseReporting.ps1

powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\installer\tests\Test-ReleaseArtifacts.ps1 `
  -Root .\installer\build\payload `
  -LegacyPasswordFile .\tools\mqtt\runtime\config\passwordfile
```

The skeleton suite includes a behavioral regression for the historical install
and upgrade failure where `Start-HighTacPlatformService.ps1` reused PowerShell
`$Matches` after a namespace regex and decoded empty `.env` values. It now tests
real sample MQTT host, port, and password values in addition to checking source
ordering. Host installer logs and ProgramData logs remain ignored artifacts and
must not be committed.

## Isolated Windows Sandbox rehearsal

The rehearsal covers fresh install, service recovery configuration, Web broker
stop/start, restore-triggered process restart, stop/start reboot simulation,
upgrade preservation, keep-data uninstall/reinstall, and delete-data uninstall.
It refuses to run unless the account is exactly `WDAGUtilityAccount` and the
dedicated Sandbox mapping exists. The repository and live legacy password file
are never mapped.

Prepare and launch after building baseline and candidate installers:

```powershell
.\installer\rehearsal\Invoke-HighTacWindowsSandbox.ps1 `
  -BaselineInstaller .\installer\build\installer\HighTacPlatform-1.0.0-x64.exe `
  -CandidateInstaller .\installer\build\installer\HighTacPlatform-2.0.0-x64.exe `
  -Launch
```

Launching an already enabled Windows Sandbox does not require host service
installation. Enabling the feature does require an elevated PowerShell and a
reboot:

```powershell
Enable-WindowsOptionalFeature -Online `
  -FeatureName Containers-DisposableClientVM `
  -All
```

The sanitized result is written to
`installer\build\rehearsal-output\HighTac-Sandbox-Rehearsal.json`. No service or
firewall action from this workflow runs on the host.

The release evidence also has a separate `windows_host_upgrade_rehearsal` item.
Record a successful approved-host `1.0.0` to `2.0.0` upgrade, service health, and
data preservation as sanitized facts. The historical failed `install-host.log`
and `upgrade-host.log` runs are diagnosis evidence only and do not satisfy this
gate; neither those logs nor ProgramData may be committed.
