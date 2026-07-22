# ADR 0005: Treat eStation command acknowledgement as heuristic correlation

- Status: Accepted
- Date: 2026-07-16

## Context

HighTac assigns a UUID to every command, but the current eStation task payload
has no command ID and result payloads do not echo one. A result may include a
station ID, tag ID, color/state, result type, and optional sequence value. The
sequence is station protocol data and is not a reliable HighTac correlation
identifier. MQTT publication uses QoS 0 and does not prove RF delivery or tag
execution.

## Decision

Use bounded, explicitly heuristic acknowledgement matching:

- Match only the same station and tag.
- Consider results arriving within 10 seconds of the latest effective pending
  item publication.
- Prefer a result whose reported color/state matches the expected action.
- Permit only one latest effective pending command per tag. A newer command
  supersedes the older item using last-write-wins semantics.
- Mark MQTT publication as `PUBLISHED`, never `CONFIRMED`.
- If no matching result arrives after two seconds, retry the idempotent light or
  clear packet once. Do not exceed two publish attempts.
- At 10 seconds, mark unmatched items `UNCONFIRMED`. A command is `CONFIRMED`
  only when every item is confirmed, and `PARTIALLY_CONFIRMED` when only some
  are confirmed.
- A late or ambiguous result may update tag telemetry but must not confirm a
  superseded item or an expired command window.
- Persist the correlation mode (`HEURISTIC_STATION_TAG_TIME` or
  `HEURISTIC_STATE_MATCH`) for diagnosis and expose it in command details.

## Consequences

The UI can honestly distinguish accepted, published, confirmed, partially
confirmed, and unconfirmed work. It must not use wording such as "executed"
solely because publish succeeded.

Exactly-once execution and perfect attribution are impossible with the current
firmware. A retry can repeat an already executed idempotent light/clear action,
and closely timed external traffic can produce an ambiguous result. The
single-latest-pending rule limits false matching but reduces command queueing
for one tag.

## Alternatives considered

- Treating MQTT publish as success was rejected because it measures transport
  handoff, not station or tag execution.
- Matching the optional sequence as a HighTac command ID was rejected because
  the protocol does not guarantee that relationship.
- Waiting indefinitely was rejected because it leaves clients and audit state
  unresolved. Future firmware should echo a backend-generated correlation ID,
  at which point this ADR can be superseded.
