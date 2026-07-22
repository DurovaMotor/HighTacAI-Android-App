"""Run the self-contained MQTT first-release capacity acceptance checks."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path


def main() -> int:
    server_dir = Path(__file__).resolve().parents[1]
    tests = [
        "tests/test_mqtt.py::"
        "test_mqtt_callback_is_nonblocking_with_bounded_backpressure",
        "tests/test_mqtt.py::test_mqtt_worker_drains_messages_in_batches",
        "tests/test_mqtt_capacity.py::test_first_release_mqtt_capacity_envelope",
    ]
    return subprocess.call(
        [sys.executable, "-m", "pytest", "-q", "-s", *tests],
        cwd=server_dir,
    )


if __name__ == "__main__":
    raise SystemExit(main())
