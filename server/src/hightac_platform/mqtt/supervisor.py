from __future__ import annotations

import csv
import os
import re
import socket
import subprocess
import threading
import time
from abc import ABC, abstractmethod
from collections import deque
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import TextIO

from hightac_platform.config import Settings
from hightac_platform.utils import utc_ms


_CREDENTIAL_ASSIGNMENT_RE = re.compile(
    r"""(?ix)
    (?P<prefix>["']?(?:password|passwd|token|secret)["']?\s*[:=]\s*)
    (?:"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[^\s,;]+)
    """
)
_CREDENTIAL_FLAG_RE = re.compile(
    r"""(?ix)
    (?P<prefix>--(?:password|passwd|token|secret)\s+)
    (?:"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[^\s,;]+)
    """
)
_URL_CREDENTIAL_RE = re.compile(r"(?i)(://[^:/@\s]+:)[^@\s]+(@)")


class BrokerControlError(RuntimeError):
    pass


@dataclass(slots=True)
class BrokerStatus:
    state: str
    mode: str
    service_name: str | None
    tcp_reachable: bool
    mqtt_connected: bool
    process_id: int | None = None
    externally_managed: bool = False
    detail: str | None = None
    observed_at_ms: int = 0

    def as_dict(self) -> dict[str, object]:
        return asdict(self)


class BrokerSupervisor(ABC):
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self._mqtt_connected = False

    def set_mqtt_connected(self, connected: bool) -> None:
        self._mqtt_connected = connected

    @abstractmethod
    def status(self) -> BrokerStatus: ...

    @abstractmethod
    def start(self) -> BrokerStatus: ...

    @abstractmethod
    def stop(self) -> BrokerStatus: ...

    def restart(self) -> BrokerStatus:
        self.stop()
        return self.start()

    def tail_logs(self, lines: int) -> list[str]:
        if lines not in {50, 200, 500}:
            raise ValueError("Log line count must be 50, 200, or 500.")
        return tail_redacted(self.settings.broker_log_path, lines)

    def config_summary(self) -> dict[str, object]:
        return {
            "mode": self.settings.broker_mode,
            "service_name": self.settings.broker_service_name,
            "host": self.settings.mqtt_host,
            "port": self.settings.mqtt_port,
            "username": _redact_username(self.settings.mqtt_username),
            "password_configured": self.settings.mqtt_password is not None,
        }


class UnmanagedBrokerSupervisor(BrokerSupervisor):
    def status(self) -> BrokerStatus:
        reachable = tcp_probe(self.settings.mqtt_host, self.settings.mqtt_port)
        pid = read_live_pid(self.settings.broker_pid_path) or listener_process_id(
            self.settings.mqtt_port
        )
        return BrokerStatus(
            state="RUNNING_EXTERNAL" if reachable else "STOPPED",
            mode="unmanaged",
            service_name=None,
            tcp_reachable=reachable,
            mqtt_connected=self._mqtt_connected,
            process_id=pid,
            externally_managed=reachable,
            detail="Broker is detected but platform control is disabled." if reachable else None,
            observed_at_ms=utc_ms(),
        )

    def start(self) -> BrokerStatus:
        status = self.status()
        if status.tcp_reachable:
            return status
        raise BrokerControlError("Broker control is disabled in unmanaged mode.")

    def stop(self) -> BrokerStatus:
        raise BrokerControlError("Broker control is disabled in unmanaged mode.")


class SubprocessBrokerSupervisor(BrokerSupervisor):
    def __init__(self, settings: Settings) -> None:
        super().__init__(settings)
        self._process: subprocess.Popen[bytes] | None = None
        self._log_handle: TextIO | None = None
        self._lock = threading.Lock()

    def status(self) -> BrokerStatus:
        process = self._process
        owned_pid = process.pid if process and process.poll() is None else None
        tracked_pid = read_live_pid(self.settings.broker_pid_path)
        detected_pid = tracked_pid or listener_process_id(self.settings.mqtt_port)
        pid = owned_pid or detected_pid
        reachable = tcp_probe(self.settings.mqtt_host, self.settings.mqtt_port)
        external = owned_pid is None and (tracked_pid is not None or reachable)
        if reachable:
            state = "RUNNING_EXTERNAL" if external else "RUNNING"
        elif pid is not None:
            state = "FAILED"
        else:
            state = "STOPPED"
        return BrokerStatus(
            state=state,
            mode="subprocess",
            service_name=None,
            tcp_reachable=reachable,
            mqtt_connected=self._mqtt_connected,
            process_id=pid,
            externally_managed=external,
            observed_at_ms=utc_ms(),
        )

    def start(self) -> BrokerStatus:
        with self._lock:
            current = self.status()
            if current.tcp_reachable:
                return current
            if not self.settings.broker_command:
                raise BrokerControlError("No broker subprocess command is configured.")
            log_path = self.settings.broker_log_path
            if log_path:
                log_path.parent.mkdir(parents=True, exist_ok=True)
                self._log_handle = log_path.open("a", encoding="utf-8")
            self._process = subprocess.Popen(
                self.settings.broker_command,
                stdout=self._log_handle or subprocess.DEVNULL,
                stderr=subprocess.STDOUT,
                stdin=subprocess.DEVNULL,
                creationflags=(subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0),
            )
            if self.settings.broker_pid_path:
                self.settings.broker_pid_path.parent.mkdir(parents=True, exist_ok=True)
                self.settings.broker_pid_path.write_text(str(self._process.pid), encoding="ascii")
            return self._wait_for_tcp(True)

    def stop(self) -> BrokerStatus:
        with self._lock:
            process = self._process
            if process and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=self.settings.broker_control_timeout_seconds)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
                self._process = None
                self._close_log()
                _remove_pid_file(self.settings.broker_pid_path)
                return self._wait_for_tcp(False)
            tracked_pid = read_live_pid(self.settings.broker_pid_path)
            if tracked_pid is None:
                if not tcp_probe(self.settings.mqtt_host, self.settings.mqtt_port):
                    return self.status()
                raise BrokerControlError("Listening broker is not tracked by the configured PID file.")
            terminate_validated_mosquitto(tracked_pid)
            _remove_pid_file(self.settings.broker_pid_path)
            return self._wait_for_tcp(False)

    def _wait_for_tcp(self, expected: bool) -> BrokerStatus:
        deadline = time.monotonic() + self.settings.broker_control_timeout_seconds
        while time.monotonic() < deadline:
            if tcp_probe(self.settings.mqtt_host, self.settings.mqtt_port) is expected:
                return self.status()
            time.sleep(0.1)
        status = self.status()
        raise BrokerControlError(
            f"Broker did not become {'reachable' if expected else 'stopped'} within the timeout; "
            f"current state is {status.state}."
        )

    def _close_log(self) -> None:
        if self._log_handle:
            self._log_handle.close()
            self._log_handle = None


def create_broker_supervisor(settings: Settings) -> BrokerSupervisor:
    if settings.broker_mode == "subprocess":
        return SubprocessBrokerSupervisor(settings)
    if settings.broker_mode == "windows_service":
        from hightac_platform.mqtt.windows_scm import WindowsScmBrokerSupervisor

        return WindowsScmBrokerSupervisor(settings)
    return UnmanagedBrokerSupervisor(settings)


def tcp_probe(host: str, port: int, timeout: float = 0.3) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def read_live_pid(path: Path | None) -> int | None:
    if path is None or not path.is_file():
        return None
    try:
        pid = int(path.read_text(encoding="ascii").splitlines()[0].strip())
        os.kill(pid, 0)
        return pid
    except (OSError, ValueError, IndexError):
        return None


def listener_process_id(port: int) -> int | None:
    if os.name != "nt":
        return None
    try:
        result = subprocess.run(
            ["netstat", "-ano", "-p", "tcp"],
            capture_output=True,
            text=True,
            check=False,
            creationflags=subprocess.CREATE_NO_WINDOW,
        )
    except OSError:
        return None
    for line in result.stdout.splitlines():
        parts = line.split()
        if len(parts) < 5 or parts[0].upper() != "TCP" or parts[3].upper() != "LISTENING":
            continue
        if parts[1].rsplit(":", 1)[-1] != str(port):
            continue
        try:
            return int(parts[4])
        except ValueError:
            continue
    return None


def terminate_validated_mosquitto(pid: int) -> None:
    if os.name == "nt":
        result = subprocess.run(
            ["tasklist", "/FI", f"PID eq {pid}", "/FO", "CSV", "/NH"],
            capture_output=True,
            text=True,
            check=False,
            creationflags=subprocess.CREATE_NO_WINDOW,
        )
        rows = list(csv.reader(result.stdout.splitlines()))
        image_name = rows[0][0].lower() if rows and rows[0] else ""
        if image_name != "mosquitto.exe":
            raise BrokerControlError("Tracked PID does not belong to mosquitto.exe.")
        completed = subprocess.run(
            ["taskkill", "/PID", str(pid), "/T", "/F"],
            capture_output=True,
            text=True,
            check=False,
            creationflags=subprocess.CREATE_NO_WINDOW,
        )
        if completed.returncode != 0:
            raise BrokerControlError("Windows could not stop the tracked Mosquitto process.")
        return
    executable = Path(f"/proc/{pid}/exe")
    try:
        process_name = executable.resolve().name.lower()
    except OSError as exc:
        raise BrokerControlError("Could not validate the tracked broker process.") from exc
    if process_name != "mosquitto":
        raise BrokerControlError("Tracked PID does not belong to Mosquitto.")
    os.kill(pid, 15)


def tail_redacted(path: Path | None, lines: int) -> list[str]:
    if path is None or not path.is_file():
        return []
    with path.open("r", encoding="utf-8", errors="replace") as handle:
        result = deque(handle, maxlen=lines)
    return [_redact_log_line(line.rstrip("\r\n")) for line in result]


def _redact_log_line(line: str) -> str:
    line = _CREDENTIAL_ASSIGNMENT_RE.sub(
        lambda match: f"{match.group('prefix')}[REDACTED]",
        line,
    )
    line = _CREDENTIAL_FLAG_RE.sub(
        lambda match: f"{match.group('prefix')}[REDACTED]",
        line,
    )
    line = _URL_CREDENTIAL_RE.sub(
        lambda match: f"{match.group(1)}[REDACTED]{match.group(2)}",
        line,
    )
    return re.sub(r"(?i)(username\s*[:=]\s*)(\S{2})\S+", r"\1\2***", line)


def _redact_username(username: str | None) -> str | None:
    if not username:
        return None
    return username[:2] + "***"


def _remove_pid_file(path: Path | None) -> None:
    if path and path.is_file():
        path.unlink(missing_ok=True)
