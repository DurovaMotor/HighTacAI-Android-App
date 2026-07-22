# ADR 0003: Run Mosquitto as a Windows service

- Status: Accepted
- Date: 2026-07-16

## Context

eStation uses MQTT for heartbeat, result, and task traffic. The repository has
a small handwritten broker for development demonstrations, but production
needs protocol correctness, persistence, authentication, ACLs, logging,
automatic startup, and predictable recovery on a Windows field host.

## Decision

Use the official Mosquitto Windows distribution as the separately managed
`HighTacMqttBroker` service. `HighTacPlatform` remains a distinct service and
connects through Eclipse Paho.

- Mosquitto starts with Windows and restarts after abnormal exit.
- Anonymous access is disabled. Password and ACL files are generated at
  installation time under `C:\ProgramData\HighTac\Platform\mqtt`.
- The backend account can access the required `/estation/#` and limited
  `$SYS/#` topics. Each station account is restricted to its own topics.
- The initial listener is TCP port 1884 without TLS, limited to the trusted LAN
  and Windows Private firewall profile. TLS remains a future deployment option.
- The API controls only the fixed service name and exposes no arbitrary command,
  path, or service-name parameter.
- Status combines Windows SCM state, a loopback TCP probe, and the Paho
  connection/subscription state. Broker failure does not stop the web/API
  service.
- Logs are bounded, path-fixed, and credential-redacted.

## Consequences

MQTT behavior is delegated to a maintained broker and can be tested against the
same product used on site. Broker and API outages are distinguishable, and the
web console remains available for diagnosis when MQTT is stopped.

The installer must manage two services, firewall rules, Mosquitto licensing,
configuration upgrades, and ACL/password generation. Plain MQTT is acceptable
only on the constrained first-version LAN; deployments beyond that boundary
require TLS or a trusted VPN.

## Alternatives considered

- Extending the handwritten broker was rejected because MQTT edge cases,
  persistence, ACLs, and service recovery are not product differentiators.
- Embedding Mosquitto in the API process was rejected because independent
  lifecycle and fault isolation are operationally useful.
