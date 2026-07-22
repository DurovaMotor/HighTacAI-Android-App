from __future__ import annotations

import logging
import threading
from dataclasses import dataclass
from queue import Empty, Full, Queue
from typing import Any

import paho.mqtt.client as mqtt

from hightac_platform.config import Settings
from hightac_platform.events import EventBus
from hightac_platform.services.telemetry import TelemetryService


logger = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class MqttIngressStats:
    enqueued_messages: int
    processed_messages: int
    failed_messages: int
    dropped_messages: int
    queue_depth: int
    queue_capacity: int


class MqttBridge:
    def __init__(
        self,
        settings: Settings,
        telemetry_service: TelemetryService,
        event_bus: EventBus,
        *,
        queue_capacity: int = 256,
        batch_size: int = 32,
    ) -> None:
        if queue_capacity < 1:
            raise ValueError("MQTT queue capacity must be positive.")
        if not 1 <= batch_size <= queue_capacity:
            raise ValueError("MQTT batch size must be between 1 and queue capacity.")
        self.settings = settings
        self.telemetry_service = telemetry_service
        self.event_bus = event_bus
        self._client: mqtt.Client | None = None
        self._connected = threading.Event()
        self._subscribed = threading.Event()
        self._reconnect_count = 0
        self._message_queue: Queue[tuple[str, bytes]] = Queue(
            maxsize=queue_capacity
        )
        self._batch_size = batch_size
        self._worker_stop = threading.Event()
        self._worker: threading.Thread | None = None
        self._stats_lock = threading.Lock()
        self._enqueued_messages = 0
        self._processed_messages = 0
        self._failed_messages = 0
        self._dropped_messages = 0

    @property
    def connected(self) -> bool:
        return self._connected.is_set() and self._subscribed.is_set()

    @property
    def reconnect_count(self) -> int:
        return self._reconnect_count

    @property
    def ingress_stats(self) -> MqttIngressStats:
        with self._stats_lock:
            return MqttIngressStats(
                enqueued_messages=self._enqueued_messages,
                processed_messages=self._processed_messages,
                failed_messages=self._failed_messages,
                dropped_messages=self._dropped_messages,
                queue_depth=self._message_queue.qsize(),
                queue_capacity=self._message_queue.maxsize,
            )

    def start(self) -> None:
        if not self.settings.mqtt_enabled or self._client is not None:
            return
        client = mqtt.Client(
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
            client_id=self.settings.mqtt_client_id,
            protocol=mqtt.MQTTv311,
        )
        if self.settings.mqtt_username:
            password = (
                self.settings.mqtt_password.get_secret_value()
                if self.settings.mqtt_password
                else None
            )
            client.username_pw_set(self.settings.mqtt_username, password)
        client.reconnect_delay_set(min_delay=1, max_delay=30)
        client.on_connect = self._on_connect
        client.on_disconnect = self._on_disconnect
        client.on_subscribe = self._on_subscribe
        client.on_message = self._on_message
        self._client = client
        self._start_worker()
        try:
            client.connect_async(
                self.settings.mqtt_host,
                self.settings.mqtt_port,
                keepalive=self.settings.mqtt_keepalive_seconds,
            )
            client.loop_start()
        except Exception:
            self._client = None
            self._stop_worker()
            logger.exception("mqtt_bridge_start_failed", extra={"event": "mqtt.bridge_start_failed"})

    def stop(self) -> None:
        client, self._client = self._client, None
        self._connected.clear()
        self._subscribed.clear()
        try:
            if client is not None:
                client.disconnect()
        finally:
            try:
                if client is not None:
                    client.loop_stop()
            finally:
                self._stop_worker()

    def publish(self, topic: str, payload: bytes) -> bool:
        client = self._client
        if client is None or not self.connected:
            return False
        info = client.publish(topic, payload=payload, qos=0, retain=False)
        return info.rc == mqtt.MQTT_ERR_SUCCESS

    def _on_connect(
        self,
        client: mqtt.Client,
        _userdata: Any,
        _flags: mqtt.ConnectFlags,
        reason_code: mqtt.ReasonCode,
        _properties: mqtt.Properties | None,
    ) -> None:
        if reason_code.is_failure:
            logger.warning(
                "mqtt_connect_rejected",
                extra={"event": "mqtt.connect_rejected", "reason_code": str(reason_code)},
            )
            return
        self._connected.set()
        self._subscribed.clear()
        client.subscribe(
            [
                ("/estation/+/heartbeat", 0),
                ("/estation/+/result", 0),
                ("$SYS/broker/clients/connected", 0),
                ("$SYS/broker/uptime", 0),
            ]
        )
        self.event_bus.publish_threadsafe(
            "broker.status_changed",
            "HighTacMqttBroker",
            self._broker_event_payload(
                previous_status="STARTING",
                current_status="RUNNING",
                mqtt_connected=True,
                subscriptions_ready=False,
                reason=None,
            ),
        )

    def _on_disconnect(
        self,
        _client: mqtt.Client,
        _userdata: Any,
        _disconnect_flags: mqtt.DisconnectFlags,
        reason_code: mqtt.ReasonCode,
        _properties: mqtt.Properties | None,
    ) -> None:
        was_connected = self._connected.is_set()
        self._connected.clear()
        self._subscribed.clear()
        if was_connected and reason_code != 0:
            self._reconnect_count += 1
        self.event_bus.publish_threadsafe(
            "broker.status_changed",
            "HighTacMqttBroker",
            self._broker_event_payload(
                previous_status="RUNNING",
                current_status="UNKNOWN",
                mqtt_connected=False,
                subscriptions_ready=False,
                reason=str(reason_code),
            ),
        )

    def _on_subscribe(
        self,
        _client: mqtt.Client,
        _userdata: Any,
        _mid: int,
        reason_codes: list[mqtt.ReasonCode],
        _properties: mqtt.Properties | None,
    ) -> None:
        if reason_codes and all(not reason.is_failure for reason in reason_codes):
            self._subscribed.set()
            self.event_bus.publish_threadsafe(
                "broker.status_changed",
                "HighTacMqttBroker",
                self._broker_event_payload(
                    previous_status="RUNNING",
                    current_status="RUNNING",
                    mqtt_connected=True,
                    subscriptions_ready=True,
                    reason=None,
                ),
            )

    def _broker_event_payload(
        self,
        *,
        previous_status: str,
        current_status: str,
        mqtt_connected: bool,
        subscriptions_ready: bool,
        reason: str | None,
    ) -> dict[str, object]:
        return {
            "previous_status": previous_status,
            "current_status": current_status,
            "windows_service_running": (
                self.settings.broker_mode == "windows_service"
                and mqtt_connected
            ),
            "tcp_reachable": mqtt_connected,
            "mqtt_connected": mqtt_connected,
            "subscriptions_ready": subscriptions_ready,
            "endpoint": f"{self.settings.mqtt_host}:{self.settings.mqtt_port}",
            "reason": reason[:512] if reason else None,
        }

    def _on_message(self, _client: mqtt.Client, _userdata: Any, message: mqtt.MQTTMessage) -> None:
        if message.topic.startswith("$SYS/"):
            return
        try:
            self._message_queue.put_nowait(
                (message.topic, bytes(message.payload))
            )
        except Full:
            with self._stats_lock:
                self._dropped_messages += 1
                dropped_messages = self._dropped_messages
            if _should_log_drop(dropped_messages):
                logger.warning(
                    "mqtt_ingress_queue_full",
                    extra={
                        "event": "mqtt.ingress_queue_full",
                        "queue_capacity": self._message_queue.maxsize,
                        "queue_depth": self._message_queue.qsize(),
                        "dropped_messages": dropped_messages,
                    },
                )
        else:
            with self._stats_lock:
                self._enqueued_messages += 1

    def _start_worker(self) -> None:
        if self._worker is not None:
            return
        self._worker_stop.clear()
        self._worker = threading.Thread(
            target=self._run_worker,
            name="hightac-mqtt-ingress",
            daemon=True,
        )
        self._worker.start()

    def _stop_worker(self) -> None:
        worker, self._worker = self._worker, None
        if worker is None:
            return
        self._worker_stop.set()
        worker.join()

    def _run_worker(self) -> None:
        while not self._worker_stop.is_set() or not self._message_queue.empty():
            try:
                first = self._message_queue.get(timeout=0.1)
            except Empty:
                continue
            batch = [first]
            while len(batch) < self._batch_size:
                try:
                    batch.append(self._message_queue.get_nowait())
                except Empty:
                    break
            try:
                successful = self.telemetry_service.handle_messages(batch)
                if not 0 <= successful <= len(batch):
                    raise ValueError(
                        "Telemetry batch returned an invalid success count."
                    )
                failed = len(batch) - successful
            except Exception:
                failed = len(batch)
                logger.exception(
                    "mqtt_ingress_batch_failed",
                    extra={
                        "event": "mqtt.ingress_batch_failed",
                        "batch_size": len(batch),
                    },
                )
            finally:
                for _message in batch:
                    self._message_queue.task_done()
            with self._stats_lock:
                self._processed_messages += len(batch)
                self._failed_messages += failed


def _should_log_drop(dropped_messages: int) -> bool:
    return dropped_messages == 1 or dropped_messages & (dropped_messages - 1) == 0
