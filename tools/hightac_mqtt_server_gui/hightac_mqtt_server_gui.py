# -*- coding: utf-8 -*-
"""HighTac MQTT server setup GUI for Windows.

This app embeds a small MQTT 3.1.1 broker so another Windows computer can be
used as the local HighTac/eStation broker without installing Mosquitto.
"""

from __future__ import annotations

import ipaddress
import queue
import secrets
import socket
import struct
import subprocess
import sys
import threading
import time
import tkinter as tk
from dataclasses import dataclass, field
from tkinter import messagebox, ttk
from typing import Callable, Dict, Iterable, List, Optional, Sequence, Tuple


APP_VERSION = "1.1.0"
APP_TITLE = "HighTac MQTT服务器一键配置"
DEFAULT_STATION_ID = "90A9F7301427"
CURRENT_SITE_BROKER_HOST = "192.168.1.105"
DEFAULT_PORT = 1884
CREDENTIAL_USERNAME_PREFIX = "hightac_"
CREDENTIAL_USERNAME_TOKEN_BYTES = 6
CREDENTIAL_PASSWORD_TOKEN_BYTES = 24
FIREWALL_RULE_PREFIX = "HighTac MQTT Broker TCP"


class MqttProtocolError(Exception):
    pass


def generate_credentials() -> Tuple[str, str]:
    """Return a fresh username and password for one GUI process."""
    username = CREDENTIAL_USERNAME_PREFIX + secrets.token_hex(CREDENTIAL_USERNAME_TOKEN_BYTES)
    password = secrets.token_urlsafe(CREDENTIAL_PASSWORD_TOKEN_BYTES)
    return username, password


def redact_sensitive_values(message: str, sensitive_values: Iterable[str]) -> str:
    redacted = message
    for value in sensitive_values:
        if value:
            redacted = redacted.replace(value, "[redacted]")
    return redacted


def encode_remaining_length(length: int) -> bytes:
    if length < 0:
        raise ValueError("remaining length must be positive")
    encoded = bytearray()
    while True:
        digit = length % 128
        length //= 128
        if length > 0:
            digit |= 0x80
        encoded.append(digit)
        if length == 0:
            return bytes(encoded)


def read_exact(sock: socket.socket, size: int) -> bytes:
    chunks = bytearray()
    while len(chunks) < size:
        chunk = sock.recv(size - len(chunks))
        if not chunk:
            raise ConnectionError("socket closed")
        chunks.extend(chunk)
    return bytes(chunks)


def read_remaining_length(sock: socket.socket) -> int:
    multiplier = 1
    value = 0
    for _ in range(4):
        encoded_byte = read_exact(sock, 1)[0]
        value += (encoded_byte & 0x7F) * multiplier
        if (encoded_byte & 0x80) == 0:
            return value
        multiplier *= 128
    raise MqttProtocolError("malformed remaining length")


def read_utf8(data: bytes, offset: int) -> Tuple[str, int]:
    if offset + 2 > len(data):
        raise MqttProtocolError("missing string length")
    length = struct.unpack_from("!H", data, offset)[0]
    offset += 2
    if offset + length > len(data):
        raise MqttProtocolError("truncated string")
    return data[offset : offset + length].decode("utf-8", errors="replace"), offset + length


def encode_utf8(value: str) -> bytes:
    raw = value.encode("utf-8")
    return struct.pack("!H", len(raw)) + raw


def topic_matches(filter_text: str, topic: str) -> bool:
    filter_parts = filter_text.split("/")
    topic_parts = topic.split("/")
    for index, part in enumerate(filter_parts):
        if part == "#":
            return index == len(filter_parts) - 1
        if index >= len(topic_parts):
            return False
        if part != "+" and part != topic_parts[index]:
            return False
    return len(topic_parts) == len(filter_parts)


@dataclass
class BrokerConfig:
    host: str
    port: int
    username: str
    password: str


@dataclass
class BrokerClient:
    sock: socket.socket
    address: Tuple[str, int]
    client_id: str = ""
    subscriptions: List[str] = field(default_factory=list)
    connected: bool = False

    def send_packet(self, packet_type_and_flags: int, payload: bytes) -> None:
        self.sock.sendall(bytes([packet_type_and_flags]) + encode_remaining_length(len(payload)) + payload)

    def close(self) -> None:
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            self.sock.close()
        except OSError:
            pass


class SimpleMqttBroker:
    """A compact MQTT 3.1.1 broker implementation for HighTac local usage."""

    def __init__(self, config: BrokerConfig, log: Callable[[str], None]) -> None:
        self.config = config
        self.log = log
        self._server_socket: Optional[socket.socket] = None
        self._accept_thread: Optional[threading.Thread] = None
        self._stop_event = threading.Event()
        self._lock = threading.RLock()
        self._clients: Dict[str, BrokerClient] = {}
        self._client_threads: List[threading.Thread] = []

    @property
    def running(self) -> bool:
        return self._server_socket is not None and not self._stop_event.is_set()

    def _emit_log(self, message: str) -> None:
        self.log(redact_sensitive_values(message, (self.config.username, self.config.password)))

    def start(self) -> None:
        if self.running:
            return
        self._stop_event.clear()
        server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server_socket.bind((self.config.host, self.config.port))
        server_socket.listen(64)
        server_socket.settimeout(1.0)
        self._server_socket = server_socket
        self._accept_thread = threading.Thread(target=self._accept_loop, name="mqtt-accept", daemon=True)
        self._accept_thread.start()
        self._emit_log(f"MQTT服务器已启动: 0.0.0.0:{self.config.port}")

    def stop(self) -> None:
        self._stop_event.set()
        server_socket = self._server_socket
        self._server_socket = None
        if server_socket is not None:
            try:
                server_socket.close()
            except OSError:
                pass
        with self._lock:
            clients = list(self._clients.values())
            self._clients.clear()
        for client in clients:
            client.close()
        self._emit_log("MQTT服务器已停止")

    def _accept_loop(self) -> None:
        while not self._stop_event.is_set():
            server_socket = self._server_socket
            if server_socket is None:
                break
            try:
                client_socket, address = server_socket.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            client_socket.settimeout(90.0)
            client = BrokerClient(sock=client_socket, address=address)
            thread = threading.Thread(target=self._client_loop, args=(client,), name=f"mqtt-client-{address}", daemon=True)
            self._client_threads.append(thread)
            thread.start()

    def _client_loop(self, client: BrokerClient) -> None:
        try:
            while not self._stop_event.is_set():
                fixed_header = client.sock.recv(1)
                if not fixed_header:
                    break
                packet_type_and_flags = fixed_header[0]
                packet_type = packet_type_and_flags >> 4
                remaining_length = read_remaining_length(client.sock)
                payload = read_exact(client.sock, remaining_length) if remaining_length else b""

                if packet_type == 1:
                    self._handle_connect(client, payload)
                elif packet_type == 3:
                    self._handle_publish(client, packet_type_and_flags, payload)
                elif packet_type == 8:
                    self._handle_subscribe(client, payload)
                elif packet_type == 10:
                    self._handle_unsubscribe(client, payload)
                elif packet_type == 12:
                    client.send_packet(0xD0, b"")
                elif packet_type == 14:
                    break
                elif packet_type in (4, 5, 6, 7):
                    continue
                else:
                    raise MqttProtocolError(f"unsupported packet type {packet_type}")
        except (ConnectionError, OSError, MqttProtocolError) as exc:
            if client.connected:
                self._emit_log(f"客户端断开: {client.client_id or client.address[0]} ({exc})")
        finally:
            self._remove_client(client)
            client.close()

    def _handle_connect(self, client: BrokerClient, payload: bytes) -> None:
        protocol_name, offset = read_utf8(payload, 0)
        if protocol_name not in ("MQTT", "MQIsdp"):
            client.send_packet(0x20, b"\x00\x01")
            raise MqttProtocolError("unsupported protocol")
        if offset + 4 > len(payload):
            raise MqttProtocolError("truncated connect header")
        protocol_level = payload[offset]
        connect_flags = payload[offset + 1]
        keep_alive = struct.unpack_from("!H", payload, offset + 2)[0]
        offset += 4
        if protocol_level not in (3, 4):
            client.send_packet(0x20, b"\x00\x01")
            raise MqttProtocolError("unsupported protocol level")

        client_id, offset = read_utf8(payload, offset)
        if not client_id:
            client_id = f"hightac-client-{client.address[0]}-{client.address[1]}"

        will_flag = bool(connect_flags & 0x04)
        password_flag = bool(connect_flags & 0x40)
        username_flag = bool(connect_flags & 0x80)
        if will_flag:
            _, offset = read_utf8(payload, offset)
            _, offset = read_utf8(payload, offset)
        username = ""
        password = ""
        if username_flag:
            username, offset = read_utf8(payload, offset)
        if password_flag:
            password, offset = read_utf8(payload, offset)

        if self.config.username and (username != self.config.username or password != self.config.password):
            client.send_packet(0x20, b"\x00\x05")
            self._emit_log(f"认证失败: {client_id} from {client.address[0]}")
            raise MqttProtocolError("bad username or password")

        client.client_id = client_id
        client.connected = True
        client.send_packet(0x20, b"\x00\x00")
        with self._lock:
            old_client = self._clients.get(client_id)
            if old_client is not None and old_client is not client:
                old_client.close()
            self._clients[client_id] = client
        self._emit_log(f"客户端已连接: {client_id} / {client.address[0]} / keepalive {keep_alive}s")

    def _handle_subscribe(self, client: BrokerClient, payload: bytes) -> None:
        if not client.connected:
            raise MqttProtocolError("subscribe before connect")
        if len(payload) < 2:
            raise MqttProtocolError("missing subscribe packet id")
        packet_id = payload[:2]
        offset = 2
        granted_qos = bytearray()
        new_filters: List[str] = []
        while offset < len(payload):
            topic_filter, offset = read_utf8(payload, offset)
            if offset >= len(payload):
                raise MqttProtocolError("missing requested qos")
            requested_qos = payload[offset]
            offset += 1
            if requested_qos > 2:
                granted_qos.append(0x80)
                continue
            new_filters.append(topic_filter)
            granted_qos.append(0)
        with self._lock:
            for topic_filter in new_filters:
                if topic_filter not in client.subscriptions:
                    client.subscriptions.append(topic_filter)
        client.send_packet(0x90, packet_id + bytes(granted_qos))
        if new_filters:
            self._emit_log(f"订阅: {client.client_id} -> {', '.join(new_filters)}")

    def _handle_unsubscribe(self, client: BrokerClient, payload: bytes) -> None:
        if len(payload) < 2:
            raise MqttProtocolError("missing unsubscribe packet id")
        packet_id = payload[:2]
        offset = 2
        removed: List[str] = []
        while offset < len(payload):
            topic_filter, offset = read_utf8(payload, offset)
            removed.append(topic_filter)
        with self._lock:
            client.subscriptions = [topic for topic in client.subscriptions if topic not in removed]
        client.send_packet(0xB0, packet_id)

    def _handle_publish(self, client: BrokerClient, packet_type_and_flags: int, payload: bytes) -> None:
        if not client.connected:
            raise MqttProtocolError("publish before connect")
        qos = (packet_type_and_flags & 0x06) >> 1
        topic, offset = read_utf8(payload, 0)
        packet_id = b""
        if qos:
            if offset + 2 > len(payload):
                raise MqttProtocolError("missing publish packet id")
            packet_id = payload[offset : offset + 2]
            offset += 2
        message = payload[offset:]
        if qos == 1:
            client.send_packet(0x40, packet_id)
        elif qos > 1:
            raise MqttProtocolError("qos 2 is not supported")

        self._emit_log(f"发布: {client.client_id} -> {topic} / {len(message)} bytes")
        self._forward_publish(topic, message)

    def _forward_publish(self, topic: str, message: bytes) -> None:
        packet = bytes([0x30]) + encode_remaining_length(len(encode_utf8(topic)) + len(message)) + encode_utf8(topic) + message
        with self._lock:
            recipients = [
                client
                for client in self._clients.values()
                if client.connected and any(topic_matches(topic_filter, topic) for topic_filter in client.subscriptions)
            ]
        for recipient in recipients:
            try:
                recipient.sock.sendall(packet)
            except OSError:
                self._remove_client(recipient)
                recipient.close()

    def _remove_client(self, client: BrokerClient) -> None:
        with self._lock:
            if client.client_id and self._clients.get(client.client_id) is client:
                self._clients.pop(client.client_id, None)


def detect_ipv4_addresses() -> List[str]:
    found = set()

    def add(value: str) -> None:
        try:
            address = ipaddress.ip_address(value)
        except ValueError:
            return
        if address.version == 4 and not address.is_loopback and not address.is_link_local:
            found.add(value)

    try:
        hostname = socket.gethostname()
        for item in socket.gethostbyname_ex(hostname)[2]:
            add(item)
    except OSError:
        pass

    try:
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        probe.connect(("8.8.8.8", 80))
        add(probe.getsockname()[0])
        probe.close()
    except OSError:
        pass

    def address_priority(value: str) -> Tuple[int, str]:
        if value == CURRENT_SITE_BROKER_HOST:
            return 0, value
        if value.startswith("192.168."):
            return 1, value
        if value.startswith("10."):
            return 2, value
        if value.startswith("172."):
            return 3, value
        return 4, value

    ordered = sorted(found, key=address_priority)
    return ordered or ["127.0.0.1"]


def make_config_text(station_id: str, broker_address: str, port: int, username: str, password: str) -> str:
    return (
        "HighTac 声光寻物 MQTT 配置\n"
        "========================\n"
        f"基站SN: {station_id}\n"
        f"Broker地址: {broker_address}\n"
        f"端口: {port}\n"
        f"用户名: {username}\n"
        f"密码: {password}\n"
        "TLS/SSL: 关闭\n\n"
        "APP填写:\n"
        f"- 基站SN: {station_id}\n"
        f"- Broker地址: {broker_address}\n"
        f"- 端口: {port}\n"
        f"- 用户名: {username}\n"
        f"- 密码: {password}\n"
        "- TLS/SSL: 关闭\n\n"
        "基站内置管理页填写:\n"
        f"- MQTT服务器地址和端口: {broker_address}:{port}\n"
        f"- 用户名: {username}\n"
        f"- 密码: {password}\n"
        "- TLS/SSL: 关闭\n"
    )


class HighTacMqttServerGui(tk.Tk):
    def __init__(self) -> None:
        super().__init__()
        self.title(APP_TITLE)
        self.geometry("900x680")
        self.minsize(780, 600)

        self.log_queue: "queue.Queue[str]" = queue.Queue()
        self.broker: Optional[SimpleMqttBroker] = None
        self.addresses = detect_ipv4_addresses()
        username, password = generate_credentials()

        self.station_id_var = tk.StringVar(value=DEFAULT_STATION_ID)
        self.ip_var = tk.StringVar(value=self.addresses[0])
        self.port_var = tk.StringVar(value=str(DEFAULT_PORT))
        self.username_var = tk.StringVar(value=username)
        self.password_var = tk.StringVar(value=password)
        self.status_var = tk.StringVar(value="未启动")

        self._build_ui()
        self._refresh_config_text()
        self.after(200, self._drain_log_queue)
        self.protocol("WM_DELETE_WINDOW", self._on_close)

    def _build_ui(self) -> None:
        container = ttk.Frame(self, padding=16)
        container.pack(fill=tk.BOTH, expand=True)

        title = ttk.Label(container, text="HighTac MQTT服务器一键配置", font=("Microsoft YaHei UI", 18, "bold"))
        title.pack(anchor=tk.W)
        subtitle = ttk.Label(
            container,
            text="在这台电脑上启动局域网 MQTT Broker，供 HighTac App 与 eStation 基站连接。",
            font=("Microsoft YaHei UI", 10),
        )
        subtitle.pack(anchor=tk.W, pady=(4, 14))

        form = ttk.LabelFrame(container, text="配置")
        form.pack(fill=tk.X)
        for index in range(4):
            form.columnconfigure(index, weight=1 if index in (1, 3) else 0)

        self._add_row(form, 0, "基站SN", ttk.Entry(form, textvariable=self.station_id_var))
        ip_box = ttk.Combobox(form, textvariable=self.ip_var, values=self.addresses, state="normal")
        self._add_row(form, 1, "Broker地址", ip_box)
        self._add_row(form, 2, "端口", ttk.Entry(form, textvariable=self.port_var))
        self._add_row(form, 3, "用户名", ttk.Entry(form, textvariable=self.username_var))
        self._add_row(form, 4, "密码", ttk.Entry(form, textvariable=self.password_var, show="*"))

        buttons = ttk.Frame(container)
        buttons.pack(fill=tk.X, pady=14)
        self.start_button = ttk.Button(buttons, text="一键启动服务器", command=self._start_server)
        self.start_button.pack(side=tk.LEFT)
        self.stop_button = ttk.Button(buttons, text="停止服务器", command=self._stop_server, state=tk.DISABLED)
        self.stop_button.pack(side=tk.LEFT, padx=(10, 0))
        ttk.Button(buttons, text="添加防火墙规则", command=self._add_firewall_rule).pack(side=tk.LEFT, padx=(10, 0))
        ttk.Button(buttons, text="刷新IP", command=self._refresh_ips).pack(side=tk.LEFT, padx=(10, 0))
        ttk.Button(buttons, text="复制配置", command=self._copy_config).pack(side=tk.LEFT, padx=(10, 0))
        ttk.Label(buttons, textvariable=self.status_var, font=("Microsoft YaHei UI", 10, "bold")).pack(side=tk.RIGHT)

        output_frame = ttk.LabelFrame(container, text="配置完成后给 APP / 基站填写这些信息")
        output_frame.pack(fill=tk.BOTH, expand=False, pady=(0, 14))
        self.output_text = tk.Text(output_frame, height=14, wrap=tk.WORD, font=("Consolas", 10))
        self.output_text.pack(fill=tk.BOTH, expand=True, padx=8, pady=8)

        log_frame = ttk.LabelFrame(container, text="运行日志")
        log_frame.pack(fill=tk.BOTH, expand=True)
        self.log_text = tk.Text(log_frame, height=12, wrap=tk.WORD, font=("Consolas", 9))
        self.log_text.pack(fill=tk.BOTH, expand=True, padx=8, pady=8)

        for variable in (
            self.station_id_var,
            self.ip_var,
            self.port_var,
            self.username_var,
            self.password_var,
        ):
            variable.trace_add("write", lambda *_: self._refresh_config_text())

    def _add_row(self, parent: ttk.Frame, row: int, label_text: str, widget: tk.Widget) -> None:
        ttk.Label(parent, text=label_text).grid(row=row, column=0, sticky=tk.W, padx=(10, 8), pady=8)
        widget.grid(row=row, column=1, columnspan=3, sticky=tk.EW, padx=(0, 10), pady=8)

    def _refresh_ips(self) -> None:
        self.addresses = detect_ipv4_addresses()
        if self.ip_var.get() not in self.addresses:
            self.ip_var.set(self.addresses[0])
        self._log("已刷新本机IP: " + ", ".join(self.addresses))

    def _validate_inputs(self) -> Tuple[str, int, str, str, str]:
        station_id = self.station_id_var.get().strip().upper()
        broker_address = self.ip_var.get().strip()
        username = self.username_var.get().strip()
        password = self.password_var.get()
        try:
            port = int(self.port_var.get().strip())
        except ValueError:
            raise ValueError("端口必须是数字。")
        if not station_id:
            raise ValueError("请填写基站SN。")
        if not broker_address:
            raise ValueError("请填写Broker地址。")
        if port < 1 or port > 65535:
            raise ValueError("端口必须在 1..65535 之间。")
        if not username:
            raise ValueError("请填写MQTT用户名。")
        if not password:
            raise ValueError("请填写MQTT密码。")
        return station_id, port, broker_address, username, password

    def _start_server(self) -> None:
        try:
            station_id, port, broker_address, username, password = self._validate_inputs()
        except ValueError as exc:
            messagebox.showerror(APP_TITLE, str(exc))
            return

        if self.broker and self.broker.running:
            self._log("服务器已经在运行。")
            return

        self.broker = SimpleMqttBroker(
            BrokerConfig(host="0.0.0.0", port=port, username=username, password=password),
            log=self._log,
        )
        try:
            self.broker.start()
        except OSError as exc:
            messagebox.showerror(APP_TITLE, f"启动失败: {exc}\n\n请确认端口 {port} 没有被其他程序占用。")
            self.broker = None
            return

        self._add_firewall_rule(silent=True)
        self.status_var.set(f"运行中: {broker_address}:{port}")
        self.start_button.configure(state=tk.DISABLED)
        self.stop_button.configure(state=tk.NORMAL)
        self._refresh_config_text()
        self._log(f"配置已生成，基站SN={station_id}")

    def _stop_server(self) -> None:
        if self.broker:
            self.broker.stop()
            self.broker = None
        self.status_var.set("未启动")
        self.start_button.configure(state=tk.NORMAL)
        self.stop_button.configure(state=tk.DISABLED)

    def _add_firewall_rule(self, silent: bool = False) -> None:
        try:
            port = int(self.port_var.get().strip())
        except ValueError:
            if not silent:
                messagebox.showerror(APP_TITLE, "端口必须是数字。")
            return
        rule_name = f"{FIREWALL_RULE_PREFIX} {port}"
        command = [
            "netsh",
            "advfirewall",
            "firewall",
            "add",
            "rule",
            f"name={rule_name}",
            "dir=in",
            "action=allow",
            "protocol=TCP",
            f"localport={port}",
        ]
        try:
            result = subprocess.run(
                command,
                text=True,
                capture_output=True,
                timeout=12,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
        except Exception as exc:  # noqa: BLE001 - GUI should keep running.
            self._log(f"防火墙规则添加失败: {exc}")
            if not silent:
                messagebox.showwarning(APP_TITLE, f"防火墙规则添加失败: {exc}")
            return

        combined = (result.stdout + "\n" + result.stderr).strip()
        if result.returncode == 0:
            self._log(f"防火墙规则已添加或已存在: {rule_name}")
        else:
            self._log(f"防火墙规则添加失败，可能需要管理员权限: {combined}")
            if not silent:
                messagebox.showwarning(
                    APP_TITLE,
                    "防火墙规则添加失败，可能需要右键以管理员身份运行。\n\n"
                    f"也可以手动放行 TCP {port}。\n\n{combined}",
                )

    def _refresh_config_text(self) -> None:
        try:
            station_id, port, broker_address, username, password = self._validate_inputs()
            text = make_config_text(station_id, broker_address, port, username, password)
        except ValueError as exc:
            text = f"配置未完整: {exc}"
        self.output_text.configure(state=tk.NORMAL)
        self.output_text.delete("1.0", tk.END)
        self.output_text.insert(tk.END, text)
        self.output_text.configure(state=tk.DISABLED)

    def _copy_config(self) -> None:
        text = self.output_text.get("1.0", tk.END).strip()
        self.clipboard_clear()
        self.clipboard_append(text)
        self._log("配置已复制到剪贴板。")

    def _log(self, message: str) -> None:
        message = redact_sensitive_values(message, (self.username_var.get(), self.password_var.get()))
        timestamp = time.strftime("%H:%M:%S")
        self.log_queue.put(f"[{timestamp}] {message}")

    def _drain_log_queue(self) -> None:
        try:
            while True:
                line = self.log_queue.get_nowait()
                self.log_text.insert(tk.END, line + "\n")
                self.log_text.see(tk.END)
        except queue.Empty:
            pass
        self.after(200, self._drain_log_queue)

    def _on_close(self) -> None:
        self._stop_server()
        self.destroy()


def run_smoke_test() -> bool:
    username, password = generate_credentials()
    if not username or not password:
        return False

    interpreter = tk.Tcl()
    if not str(interpreter.call("info", "patchlevel")):
        return False

    broker = SimpleMqttBroker(
        BrokerConfig(host="127.0.0.1", port=0, username=username, password=password),
        log=lambda _message: None,
    )
    broker.start()
    try:
        return broker.running
    finally:
        broker.stop()


def write_cli_output(message: str) -> None:
    if sys.stdout is not None:
        sys.stdout.write(message + "\n")


def main(argv: Optional[Sequence[str]] = None) -> int:
    arguments = list(sys.argv[1:] if argv is None else argv)
    if arguments == ["--version"]:
        write_cli_output(f"HighTacMqttServerSetup {APP_VERSION}")
        return 0
    if arguments == ["--smoke-test"]:
        try:
            return 0 if run_smoke_test() else 1
        except Exception:  # noqa: BLE001 - noninteractive smoke test reports via exit code.
            return 1
    if arguments:
        write_cli_output("Usage: HighTacMqttServerSetup.exe [--version | --smoke-test]")
        return 2

    app = HighTacMqttServerGui()
    app.mainloop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
