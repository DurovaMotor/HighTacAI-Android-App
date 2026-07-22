import ast
import contextlib
import io
import sys
import unittest
from pathlib import Path


TOOL_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOL_DIR))

import hightac_mqtt_server_gui as gui  # noqa: E402


class RecordingSocket:
    def __init__(self) -> None:
        self.sent = []

    def sendall(self, data: bytes) -> None:
        self.sent.append(data)


class CredentialTests(unittest.TestCase):
    def test_credentials_are_fresh_and_nonempty(self) -> None:
        credentials = {gui.generate_credentials() for _ in range(32)}

        self.assertEqual(32, len(credentials))
        for username, password in credentials:
            self.assertTrue(username.startswith(gui.CREDENTIAL_USERNAME_PREFIX))
            self.assertGreaterEqual(len(username), len(gui.CREDENTIAL_USERNAME_PREFIX) + 12)
            self.assertGreaterEqual(len(password), 32)

    def test_source_has_no_literal_default_username_or_password(self) -> None:
        source = (TOOL_DIR / "hightac_mqtt_server_gui.py").read_text(encoding="utf-8")
        tree = ast.parse(source)
        literal_credentials = []

        for node in tree.body:
            if not isinstance(node, (ast.Assign, ast.AnnAssign)):
                continue
            targets = node.targets if isinstance(node, ast.Assign) else [node.target]
            value = node.value
            for target in targets:
                if (
                    isinstance(target, ast.Name)
                    and target.id.endswith(("USERNAME", "PASSWORD"))
                    and isinstance(value, ast.Constant)
                    and isinstance(value.value, str)
                ):
                    literal_credentials.append(target.id)

        self.assertEqual([], literal_credentials)
        self.assertNotIn("DEFAULT_USERNAME", source)
        self.assertNotIn("DEFAULT_PASSWORD", source)

    def test_spec_has_no_credential_literals(self) -> None:
        spec = (TOOL_DIR / "HighTacMqttServerSetup.spec").read_text(encoding="utf-8").lower()

        self.assertNotIn("username", spec)
        self.assertNotIn("password", spec)


class LoggingTests(unittest.TestCase):
    def test_broker_redacts_configured_credentials(self) -> None:
        username, password = gui.generate_credentials()
        messages = []
        broker = gui.SimpleMqttBroker(
            gui.BrokerConfig(host="127.0.0.1", port=0, username=username, password=password),
            messages.append,
        )

        broker._emit_log(f"client={username} submitted={password}")

        output = "\n".join(messages)
        self.assertNotIn(username, output)
        self.assertNotIn(password, output)
        self.assertEqual(2, output.count("[redacted]"))

    def test_publish_log_contains_metadata_not_payload(self) -> None:
        username, password = gui.generate_credentials()
        messages = []
        broker = gui.SimpleMqttBroker(
            gui.BrokerConfig(host="127.0.0.1", port=0, username=username, password=password),
            messages.append,
        )
        broker._forward_publish = lambda _topic, _message: None
        client = gui.BrokerClient(
            sock=RecordingSocket(),
            address=("127.0.0.1", 12345),
            client_id="test-client",
            connected=True,
        )
        payload_secret = b"sensitive-payload-value"

        broker._handle_publish(client, 0x30, gui.encode_utf8("test/topic") + payload_secret)

        output = "\n".join(messages)
        self.assertNotIn(payload_secret.decode("ascii"), output)
        self.assertIn(f"{len(payload_secret)} bytes", output)


class CommandLineTests(unittest.TestCase):
    def test_version_flag(self) -> None:
        output = io.StringIO()

        with contextlib.redirect_stdout(output):
            exit_code = gui.main(["--version"])

        self.assertEqual(0, exit_code)
        self.assertIn(gui.APP_VERSION, output.getvalue())

    def test_smoke_flag(self) -> None:
        self.assertEqual(0, gui.main(["--smoke-test"]))


if __name__ == "__main__":
    unittest.main()
