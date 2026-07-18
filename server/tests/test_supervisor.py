from uuid import uuid4

import pytest

from hightac_platform.config import Settings
from hightac_platform.mqtt import supervisor as supervisor_module
from hightac_platform.mqtt.supervisor import BrokerControlError, UnmanagedBrokerSupervisor
from hightac_platform.mqtt.windows_scm import WindowsScmBrokerSupervisor


def test_unmanaged_supervisor_detects_external_listener_without_controlling_it(
    tmp_path, monkeypatch
) -> None:
    settings = Settings(
        environment="test",
        data_dir=tmp_path,
        mqtt_enabled=False,
        log_to_file=False,
        broker_pid_path=tmp_path / "missing.pid",
    )
    monkeypatch.setattr(supervisor_module, "tcp_probe", lambda *_args, **_kwargs: True)
    monkeypatch.setattr(supervisor_module, "listener_process_id", lambda _port: 4242)
    supervisor = UnmanagedBrokerSupervisor(settings)

    status = supervisor.status()
    assert status.state == "RUNNING_EXTERNAL"
    assert status.process_id == 4242
    assert supervisor.start().state == "RUNNING_EXTERNAL"
    with pytest.raises(BrokerControlError):
        supervisor.stop()


def test_windows_scm_adapter_import_has_no_platform_only_dependency(tmp_path) -> None:
    settings = Settings(
        environment="test", data_dir=tmp_path, mqtt_enabled=False, log_to_file=False
    )
    supervisor = WindowsScmBrokerSupervisor(settings)
    assert supervisor.settings.broker_service_name == "HighTacMqttBroker"


def test_broker_log_redaction_covers_structured_and_url_credentials() -> None:
    raw_value = uuid4().hex
    lines = [
        f"password={raw_value}",
        f'{{"password":"{raw_value}"}}',
        f"passwd='{raw_value} with spaces'",
        f"--password {raw_value}",
        f"mqtt://station:{raw_value}@broker.local:1884",
    ]

    redacted = [supervisor_module._redact_log_line(line) for line in lines]

    assert all(raw_value not in line for line in redacted)
    assert all("[REDACTED]" in line for line in redacted)
    assert "with spaces" not in redacted[2]
