# HighTac Platform 2.0.0 Test Checklist

This is the repository-level release checklist for the `internal_lan` 2.0.0
candidate and expected Git tag `v2.0.0`. Record final results in
`docs/releases/v2.0.0-evidence.json`, then regenerate the release report. Do not
commit databases, ProgramData, installer logs, passwords, tokens, PFX files, or
device logs.

## Automated Tests

Use Temurin Java 21 for every Android command:

```powershell
$env:JAVA_HOME = 'C:\Users\ooo\.jdks\jdk-21.0.11+10'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

Run the backend and contract suites:

```powershell
.\server\.venv\Scripts\python.exe -m pytest .\server\tests
.\contracts\.venv\Scripts\python.exe .\contracts\validate_contracts.py
```

Run the Web matrix:

```powershell
Set-Location .\web
npm test
npm run build
npm run test:e2e
Set-Location ..
```

Run installer checks from Windows PowerShell 5.1:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\installer\tests\Test-InstallerSkeleton.ps1

powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\installer\tests\Test-ReleaseReporting.ps1

powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\installer\tests\Test-ReleaseArtifacts.ps1 `
  -Root .\installer\build\payload `
  -LegacyPasswordFile .\tools\mqtt\runtime\config\passwordfile
```

The installer skeleton suite must include the historical launcher regression:
dotenv MQTT host, port, and password values remain non-empty after the
`HIGHTAC_*` namespace regex. This protects the fixed install/upgrade failure that
previously ended with deployment configuration exit code 1.

## Android Release Artifact

- [ ] Build the final non-debuggable 2.0.0 APK with the approved Android signing
      identity and production package ID.
- [ ] Verify `versionName`, `versionCode`, package ID, debuggable flag, and signer.
- [ ] Scan the final APK and confirm it contains no OpenAI, JianDaoYun, MQTT,
      administrator, or other deployable secrets.
- [ ] Revoke and rotate every provider key that appeared in an older APK; store
      replacement keys only in the protected backend `HIGHTAC_*` environment.
- [ ] Calculate SHA-256 from the exact APK distributed to phones.
- [ ] Install that exact APK on the OnePlus PLF110 test phone.

## Internal LAN Installer Artifact

The first release profile is `internal_lan`. No public Authenticode certificate
has been purchased. An unsigned Windows installer is therefore an allowed,
non-blocking known state only when all of these are true:

- [ ] Distribution target is `internal_lan`.
- [ ] Classification is `internal_lan_unsigned`.
- [ ] Authenticode status is `NotSigned`.
- [ ] The metadata notice says the package is for private LAN deployment and is
      never public distribution.
- [ ] SHA-256 and `.sha256` match the final installer bytes.

For `public_distribution`, all four signing checks are mandatory: status
`Valid`, approved signer thumbprint, Code Signing EKU, and trusted timestamp.
The optional signing pipeline remains available, but no certificate is generated
or committed by this repository.

## Real Phone And Light Strip

Sanitized production audit evidence already proves one real closed loop on
2026-07-16 UTC:

- Device: HighTac Phone 01 / OnePlus PLF110
- Station: `90A9F7301427`
- Light strip: `AD1000158A2A`
- Product: `45121-AAA-FC-T`
- LIGHT_ON command: `36f9c347-b233-42f0-b6cb-ec0cf0624eb5`, `CONFIRMED`,
  `08:24:07.279Z` to `08:24:10.639Z`
- LIGHT_OFF command: `681e9718-1c10-448f-8e8c-a6976289dc84`, `CONFIRMED`,
  `08:41:11.115Z` to `08:41:13.272Z`

The final packaged 2.0.0 APK still needs its own network smoke:

- [ ] Connect the phone to `Durova-5G` and reach `192.168.1.105:8088`.
- [ ] Verify device enrollment/approval, `last_seen`, and central binding sync.
- [ ] Issue LIGHT_ON through the final APK and record a confirmed acknowledgement.
- [ ] Issue LIGHT_OFF and verify the final off state.
- [ ] Record only sanitized facts and command IDs in release evidence.

## Windows Upgrade Evidence

Approved host:

- [ ] Successfully upgrade a reviewed 1.0.0 installation to 2.0.0.
- [ ] Verify both HighTac services, API readiness, MQTT 1884, and broker controls.
- [ ] Verify database, bindings, configuration, backups, and audit history remain.
- [ ] Record sanitized facts; do not commit host logs or ProgramData.

Windows Sandbox:

- [ ] Fresh-install `HighTacPlatform-1.0.0-x64.exe`.
- [ ] Upgrade with `HighTacPlatform-2.0.0-x64.exe`.
- [ ] Exercise service recovery, Web broker controls, keep-data reinstall, and
      explicit delete-data uninstall.
- [ ] Preserve only the sanitized Sandbox rehearsal JSON as external evidence.

## Release Report

- [ ] Report names product version `2.0.0`, target `internal_lan`, and expected
      tag `v2.0.0`.
- [ ] Report points to a clean HEAD carrying tag `v2.0.0`.
- [ ] Report contains exact APK and installer SHA-256 values.
- [ ] Every required automated/manual item is `passed`.
- [ ] `ReleaseReady` is true for the selected distribution target.

Only the final field-scale acceptance with 10 real Android phones and 2000 real
light strips is user-owned and non-gating here. All other checks above remain the
release team's responsibility.

## Failure Evidence

For a failed manual test, retain outside Git:

- phone model and Android version;
- exact operation steps and UTC timestamp;
- relevant command ID, station ID, and light-strip ID;
- filtered `adb logcat` or Windows service diagnostics;
- screenshot when useful.
