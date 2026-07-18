# ADR 0001: Central backend is the source of truth

- Status: Accepted
- Date: 2026-07-16

## Context

The existing Android application stores bindings locally and talks directly to
MQTT. With multiple phones, those copies can diverge, commands cannot be
audited centrally, and concurrent attempts can bind one tag to different
products. The target site has about 2,000 tags and up to 10 Android clients,
plus a browser administration console.

## Decision

The HighTac Windows backend is the only authoritative store for sites,
stations, tags, products, bindings, commands, devices, and operation records.

- Browser and Android writes go through `/api/v1` transactions.
- The backend enforces one active binding per tag and retains binding history.
- Retry-safe writes use actor-scoped idempotency keys.
- WebSocket events are emitted only after the database transaction commits.
- WebSocket events are notifications, not an event store. A reconnecting client
  refreshes its REST snapshot.
- Android may keep a read-only cache with a last-synchronized timestamp. It
  does not accept offline binding or command writes.
- Only the backend connects to MQTT and owns command scheduling, retries,
  acknowledgement matching, and audit records.

## Consequences

All clients converge on the same binding and command state, and concurrent
writes can be resolved with database constraints rather than phone clocks.
Auditing and device revocation become enforceable in one place.

The Windows platform is now a required dependency for writes. During an API or
network outage, Android can show stale cached data but must disable binding,
unbinding, light, and clear actions. Existing local bindings require an
explicit, conflict-aware migration; they cannot silently overwrite backend
data.

## Alternatives considered

- Multi-master phone storage was rejected because conflict resolution and
  auditability would be unreliable.
- Direct MQTT fallback from Android was rejected because it would bypass
  identity, authorization, idempotency, command state, and audit controls.
