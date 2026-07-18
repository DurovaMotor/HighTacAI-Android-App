# HighTac Local MQTT Tools

This folder contains Windows-oriented Mosquitto helper scripts for the local HighTac LAN broker.

- `start-mosquitto.ps1`: creates runtime config, password file and ACL, then starts Mosquitto through native Windows binaries or Docker.
- `stop-mosquitto.ps1`: stops the native process recorded in `runtime/mosquitto.pid` and/or the Docker container.
- `status-mqtt.ps1`: read-only diagnostics for tools, LAN IPs, project port 1884, Docker container state and firewall rule hints.
- `test-mqtt-smoke.ps1`: publishes and subscribes to one `/estation/{ID}/heartbeat` smoke-test message.
- `config/*.example`: checked-in config samples. Runtime config is generated under `runtime/config/`.

Do not commit files generated under `runtime/`; they may contain local paths, broker state or password hashes.

Current site preset: connect the broker computer, Android phones, and eStation base station to `Durova-5G`, then use `192.168.1.105:1884`. Start the native project broker from the repository root with:

```powershell
.\tools\mqtt\start-mosquitto.ps1 -Mode Native -Username hightac_mqtt
```

The script prompts for the MQTT password when `-Password` is omitted. Do not put the
site password in shell history, documentation, screenshots, or Git-tracked files.

The scripts now default to project port `1884`. Docker still maps that host port to Mosquitto's internal port `1883`.
