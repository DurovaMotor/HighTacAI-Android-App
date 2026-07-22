# ADR 0007: Keep the Android Room cache disposable and endpoint-scoped

- Status: Accepted
- Date: 2026-07-17

## Context

Android operators need the last synchronized product, active-binding, and tag
snapshot when the HighTac Platform is temporarily unreachable. The central
backend remains authoritative, and restoring a cache on another installation
or replaying rows from a previously selected server can present unrelated site
data. Refresh pagination and confirmed writes can also overlap, which can make
a late snapshot replace a newer confirmed result unless mutations are ordered.

## Decision

Android uses a Room database named `hightac-platform-read-cache.db` as a
read-only offline presentation cache.

- Every product, active binding, tag, and synchronization metadata row is
  scoped by the canonical API endpoint.
- Credentials, commands and events, readiness, Broker status, and stations are
  never stored in this database.
- A complete refresh is collected in memory first. Room replaces all three
  datasets and their timestamps in one transaction only after every requested
  page and live prerequisite succeeds. A complete empty response is a valid
  replacement. Any failed or inconsistent pagination leaves the prior
  transaction untouched.
- Dataset-only refreshes use the same complete-then-replace rule. Confirmed
  binding and tag writes update only their cached dataset after the server
  responds successfully.
- Refreshes and confirmed-write cache mutations share one repository mutex.
  Endpoint generations are checked again after network and database work so a
  late response cannot publish into a newer endpoint session.
- Startup hydration is always marked stale. Cached rows remain displayable but
  cannot open binding, command, or scanning controls; write access still
  requires a currently verified API and approved endpoint-scoped device.
- Changing the endpoint clears all in-memory platform state immediately,
  disconnects realtime transport, and hydrates only the newly selected
  endpoint. Existing device and enrollment credentials are invalidated when
  their stored canonical endpoint does not match, requiring safe enrollment
  for the new server.

The Room database, WAL, and shared-memory files are excluded from cloud backup
and device-to-device transfer. The cache is reconstructible, may be stale, and
is tied to both an endpoint and an installation authorization context; backing
it up provides no recovery value and can disclose operational data on a new
device. Room schema JSON is committed under `app/schemas` for migration review.

## Consequences

Operators can inspect stale products, bindings, and tag state during an outage
without creating an offline source of truth. Endpoint switches cannot briefly
show the previous site's rows, and restored app data cannot silently import an
old cache or credential into a different installation.

Refreshes hold a repository mutation lock while network pagination completes,
which favors consistency over concurrent refresh throughput. The cache is
recreated from the backend after app-data loss or device migration.

## Alternatives considered

- Backing up the cache was rejected because it is disposable operational data
  and its endpoint and authorization context cannot be trusted after restore.
- Caching stations, Broker readiness, or commands was rejected because stale
  copies could be mistaken for current write authorization or command state.
- Updating rows page by page was rejected because failures would expose a
  partial snapshot and valid empty snapshots could not be distinguished from
  interrupted pagination.
