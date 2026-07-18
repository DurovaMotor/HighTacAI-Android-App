from __future__ import annotations

import os
import re
import subprocess
import time

from hightac_platform.mqtt.supervisor import (
    BrokerControlError,
    BrokerStatus,
    BrokerSupervisor,
    tcp_probe,
)
from hightac_platform.utils import utc_ms


class WindowsScmBrokerSupervisor(BrokerSupervisor):
    """Fixed-service Windows SCM adapter with no import-time pywin32 dependency."""

    def status(self) -> BrokerStatus:
        service_state, detail = self._query_service()
        reachable = tcp_probe(self.settings.mqtt_host, self.settings.mqtt_port)
        external = reachable and service_state != "RUNNING"
        state = "RUNNING_EXTERNAL" if external else service_state
        return BrokerStatus(
            state=state,
            mode="windows_service",
            service_name=self.settings.broker_service_name,
            tcp_reachable=reachable,
            mqtt_connected=self._mqtt_connected,
            externally_managed=external,
            detail=detail,
            observed_at_ms=utc_ms(),
        )

    def start(self) -> BrokerStatus:
        current = self.status()
        if current.tcp_reachable:
            return current
        if current.state == "RUNNING":
            return current
        self._sc("start")
        return self._wait_for("RUNNING")

    def stop(self) -> BrokerStatus:
        current = self.status()
        if current.state == "RUNNING_EXTERNAL":
            raise BrokerControlError(
                "Port is served by a non-service broker; SCM will not stop that process."
            )
        if current.state == "STOPPED":
            return current
        self._sc("stop")
        return self._wait_for("STOPPED")

    def _query_service(self) -> tuple[str, str | None]:
        self._require_windows()
        result = subprocess.run(
            ["sc.exe", "query", self.settings.broker_service_name],
            capture_output=True,
            text=True,
            check=False,
            creationflags=subprocess.CREATE_NO_WINDOW,
        )
        if result.returncode != 0:
            return "FAILED", "Configured Windows service was not found or could not be queried."
        match = re.search(r"STATE\s*:\s*\d+\s+(\w+)", result.stdout)
        if not match:
            return "FAILED", "Windows SCM returned an unrecognized service state."
        state = match.group(1).upper()
        mapping = {
            "STOPPED": "STOPPED",
            "START_PENDING": "STARTING",
            "RUNNING": "RUNNING",
            "STOP_PENDING": "STOPPING",
            "PAUSED": "FAILED",
            "PAUSE_PENDING": "FAILED",
            "CONTINUE_PENDING": "STARTING",
        }
        return mapping.get(state, "FAILED"), None

    def _sc(self, operation: str) -> None:
        self._require_windows()
        result = subprocess.run(
            ["sc.exe", operation, self.settings.broker_service_name],
            capture_output=True,
            text=True,
            check=False,
            creationflags=subprocess.CREATE_NO_WINDOW,
        )
        output = f"{result.stdout}\n{result.stderr}".upper()
        idempotent = operation == "start" and "ALREADY RUNNING" in output
        idempotent = idempotent or (operation == "stop" and "SERVICE_NOT_ACTIVE" in output)
        if result.returncode != 0 and not idempotent:
            raise BrokerControlError(f"Windows SCM could not {operation} the broker service.")

    def _wait_for(self, expected: str) -> BrokerStatus:
        deadline = time.monotonic() + self.settings.broker_control_timeout_seconds
        while time.monotonic() < deadline:
            status = self.status()
            if status.state == expected:
                return status
            time.sleep(0.2)
        raise BrokerControlError(f"Broker service did not reach {expected} within the timeout.")

    @staticmethod
    def _require_windows() -> None:
        if os.name != "nt":
            raise BrokerControlError("Windows SCM control is only available on Windows.")
