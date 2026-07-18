# MQTT Capacity Acceptance

This acceptance run is local and self-contained. It uses the server's existing
pytest and SQLite dependencies, creates a temporary database, and does not
require a live MQTT broker or a running API process.

From `server`, run:

```powershell
.\.venv\Scripts\python.exe .\acceptance\mqtt_capacity.py
```

The run verifies:

- the MQTT callback returns while telemetry processing is blocked;
- the ingress queue is bounded and reports a structured overflow warning;
- queued messages are drained in batches;
- 2,000 bound tags produce exactly 100 MQTT command packets of 20 tags;
- one 2,000-item acknowledgement uses at most 12 selects and 20 total SQL
  statements while retaining command/item status semantics; and
- 10 concurrent clients can each read the full 2,000-item command snapshot,
  plus paged tag and binding snapshots.

The script prints setup, command creation, telemetry, SQL statement, and
concurrent-client timings from the local machine. Timing guards are deliberately
generous (10 seconds for telemetry and 15 seconds per read client); packet
counts, SQL bounds, statuses, and response sizes are deterministic regression
checks.
