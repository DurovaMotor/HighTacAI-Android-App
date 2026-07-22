# ADR 0002: Use SQLite in WAL mode

- Status: Accepted
- Date: 2026-07-16

## Context

The first deployment is one Windows host, one site, one station, about 2,000
tags, and roughly 10 concurrent clients. The platform needs transactional
binding constraints, audit history, online backups, and straightforward field
installation. A separate database service would add installation, credential,
backup, and recovery work without a matching scale requirement.

## Decision

Use SQLite 3 through SQLAlchemy 2 and Alembic, configured as follows:

- `journal_mode=WAL`
- `foreign_keys=ON` on every connection
- A bounded `busy_timeout`
- One backend process owning normal writes
- Short transactions with no MQTT, filesystem, or network waits inside them
- A partial unique index for `bindings.tag_id WHERE is_active = 1`
- UTC epoch milliseconds in storage and RFC 3339 UTC timestamps at the API
- SQLite Online Backup API for scheduled, manual, and pre-restore backups

Schema changes are Alembic migrations. Startup code must not perform ad hoc
table changes. Runtime database, WAL, shared-memory, backup, and checksum files
live under `C:\ProgramData\HighTac\Platform` and are never committed to Git.

## Consequences

WAL allows readers to continue during ordinary writes and is sufficient for
the expected local workload. Operational recovery remains a file-based process
with consistent online backups.

SQLite still permits only one writer at a time. Long transactions, multiple
uncoordinated writer processes, or copying the live database file directly can
cause lock pressure or inconsistent recovery. The service must expose WAL and
backup health, test restore procedures, and keep command-side work outside the
database transaction.

## Alternatives considered

- PostgreSQL was deferred because its service lifecycle and administration cost
  are unnecessary at the initial scale.
- SQLite rollback-journal mode was rejected because it blocks readers more
  readily during writes.
- Raw file copies of a live database were rejected in favor of the Online
  Backup API.
