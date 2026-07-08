# HighTac Local MQTT Tools

This folder contains Windows-oriented Mosquitto helper scripts for the local HighTac LAN broker.

- `start-mosquitto.ps1`: creates runtime config, password file and ACL, then starts Mosquitto through native Windows binaries or Docker.
- `stop-mosquitto.ps1`: stops the native process recorded in `runtime/mosquitto.pid` and/or the Docker container.
- `status-mqtt.ps1`: read-only diagnostics for tools, LAN IPs, port 1883, Docker container state and firewall rule hints.
- `test-mqtt-smoke.ps1`: publishes and subscribes to one `/estation/{ID}/heartbeat` smoke-test message.
- `config/*.example`: checked-in config samples. Runtime config is generated under `runtime/config/`.

Do not commit files generated under `runtime/`; they may contain local paths, broker state or password hashes.
