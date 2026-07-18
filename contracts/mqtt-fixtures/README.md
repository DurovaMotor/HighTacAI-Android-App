# eStation MQTT fixtures

These payloads preserve the field names and values exercised by the Kotlin
protocol tests. They are inert examples: no runtime username, password, token,
PID, log, database, or site data belongs in this directory.

The station and tag identifiers are synthetic values already used by protocol
tests; they do not identify installed site hardware.

`manifest.json` is the index. For every payload it records the MQTT direction,
exact topic, QoS, retain flag, expected parser outcome, and acknowledgement
correlation behavior. `manifest.schema.json` is a strict Draft 2020-12 schema
for that index.

## Wire semantics

- Station telemetry is published to `/estation/{SN}/heartbeat` and
  `/estation/{SN}/result`.
- Backend commands are published to `/estation/{SN}/task` with QoS 0 and
  `retain=false`.
- A five-second light command uses `Time=1`, `Beep=true`, and
  `Flashing=true`. Colors use boolean `R`, `G`, and `B` channels.
- A clear command uses `Time=0`, `Beep=false`, all RGB channels false, and a
  JSON `null` value for `Flashing`.
- `RfPowerSend=-256` means RF power was not reported and is normalized to
  null by the Kotlin parser.
- A result payload contains no HighTac command ID. The backend may correlate
  it only to the newest pending command for the same station and tag inside a
  ten-second window, preferring a matching color/state. MQTT publish alone is
  never an execution acknowledgement.
- `malformed-result.txt` is deliberately truncated. Consumers must reject it
  without writing telemetry or terminating the bridge process.

Use `python contracts/validate_contracts.py` from the repository root to check
the manifest, valid payloads, malformed rejection, and command invariants.
