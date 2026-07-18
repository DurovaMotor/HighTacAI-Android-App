# Third-Party Notices

This HighTac Platform package includes the following externally supplied
components. Upstream license texts are included where the supplied distribution
provides them; factual provenance records do not replace vendor license terms.

## Eclipse Mosquitto __MOSQUITTO_VERSION__

- Project: https://mosquitto.org/
- Distribution: official 64-bit Windows build
- Licenses supplied by the distribution: Eclipse Public License 2.0 and
  Eclipse Distribution License 1.0
- Runtime role: MQTT broker on the HighTac-owned service and port only

## WinSW __WINSW_VERSION__

- Project: https://github.com/winsw/winsw
- License: MIT License
- Runtime role: Windows service wrapper for `HighTacPlatform` and
  `HighTacMqttBroker`

## Microsoft Visual C++ Redistributable __VC_REDIST_VERSION__

- Distribution: reviewed official x64 redistributable supplied at build time
- Runtime role: native runtime dependency for packaged Windows components
- Signed component provenance: `Microsoft-VC-Runtime-Notice.txt`
- Governing Microsoft terms are not reproduced or invented by this project

## Build provenance

- HighTac application version: __APP_VERSION__
- Payload checksums: `SHA256SUMS.txt`

These notes do not grant rights or replace applicable vendor license terms.
