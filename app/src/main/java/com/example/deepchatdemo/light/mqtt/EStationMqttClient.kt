package com.example.deepchatdemo.light.mqtt

import com.example.deepchatdemo.light.protocol.EStationTopics
import com.example.deepchatdemo.light.protocol.EStationValidators
import com.example.deepchatdemo.light.protocol.EstationInfo
import com.example.deepchatdemo.light.protocol.TaskData
import com.example.deepchatdemo.light.protocol.TaskResult
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

data class MqttConnectionConfig(
    val stationId: String,
    val host: String,
    val port: Int = 1884,
    val username: String = "",
    val password: String = "",
    val tlsEnabled: Boolean = false
) {
    val normalizedStationId: String
        get() = EStationValidators.normalizeStationId(stationId)

    val serverUri: String
        get() {
            val scheme = if (tlsEnabled) "ssl" else "tcp"
            val normalizedHost = host.trim()
            val uriHost = if (':' in normalizedHost && !normalizedHost.startsWith("[")) {
                "[$normalizedHost]"
            } else {
                normalizedHost
            }
            return "$scheme://$uriHost:$port"
    }
}

data class MqttBrokerEndpoint(
    val host: String,
    val port: Int,
    val tlsEnabled: Boolean
) {
    companion object {
        fun parse(
            hostInput: String,
            fallbackPort: Int = 1884,
            defaultTlsEnabled: Boolean = false
        ): MqttBrokerEndpoint {
            val trimmed = hostInput.trim()
            require(trimmed.isNotBlank()) { "MQTT host is required." }
            require(fallbackPort in 1..65535) { "Invalid MQTT port." }

            var tlsEnabled = defaultTlsEnabled
            var authority = trimmed
            val schemeIndex = authority.indexOf("://")
            if (schemeIndex >= 0) {
                val scheme = authority.substring(0, schemeIndex).lowercase()
                tlsEnabled = when (scheme) {
                    "tcp", "mqtt" -> false
                    "ssl", "mqtts" -> true
                    else -> error("Unsupported MQTT scheme: $scheme.")
                }
                authority = authority.substring(schemeIndex + 3)
            }

            authority = authority.substringBefore('/').substringBefore('?').trim()
            require(authority.isNotBlank()) { "MQTT host is required." }
            require('@' !in authority) { "MQTT credentials must be entered separately." }

            val (host, parsedPort) = splitHostAndPort(authority, fallbackPort)
            require(host.isNotBlank()) { "MQTT host is required." }
            require(parsedPort in 1..65535) { "Invalid MQTT port." }

            return MqttBrokerEndpoint(
                host = host,
                port = parsedPort,
                tlsEnabled = tlsEnabled
            )
        }

        private fun splitHostAndPort(authority: String, fallbackPort: Int): Pair<String, Int> {
            if (authority.startsWith("[")) {
                val closingBracket = authority.indexOf(']')
                require(closingBracket > 1) { "Invalid MQTT host." }
                val host = authority.substring(1, closingBracket)
                val suffix = authority.substring(closingBracket + 1)
                val port = if (suffix.startsWith(":")) {
                    suffix.substring(1).toIntOrNull() ?: error("Invalid MQTT port.")
                } else {
                    fallbackPort
                }
                return host to port
            }

            val lastColon = authority.lastIndexOf(':')
            if (lastColon > 0 && authority.count { it == ':' } == 1) {
                val maybePort = authority.substring(lastColon + 1)
                if (maybePort.all { it.isDigit() }) {
                    return authority.substring(0, lastColon) to maybePort.toInt()
                }
            }

            return authority to fallbackPort
        }
    }
}

internal fun String.isUnavailableFromWifiPhone(): Boolean {
    val authority = trim()
        .lowercase()
        .substringAfter("://")
        .substringBefore('/')
        .substringBefore('?')
        .trim()
    val normalizedHost = when {
        authority.startsWith("[") -> authority.substringAfter('[').substringBefore(']')
        authority.count { it == ':' } == 1 && authority.substringAfterLast(':').all { it.isDigit() } -> {
            authority.substringBeforeLast(':')
        }
        else -> authority
    }

    return normalizedHost == "localhost" ||
        normalizedHost == "::1" ||
        normalizedHost == "0:0:0:0:0:0:0:1" ||
        normalizedHost == "0.0.0.0" ||
        normalizedHost == "::" ||
        normalizedHost == "10.0.2.2" ||
        normalizedHost.startsWith("127.")
}

sealed interface MqttConnectionState {
    data object Idle : MqttConnectionState
    data object Connecting : MqttConnectionState
    data object Subscribing : MqttConnectionState
    data object Ready : MqttConnectionState
    data object Reconnecting : MqttConnectionState
    data object Disconnected : MqttConnectionState
    data class Failed(val message: String) : MqttConnectionState
}

data class MqttMessageIssue(
    val topic: String,
    val message: String,
    val payloadPreview: String
)

class EStationMqttClient(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private var client: MqttAsyncClient? = null
    private var config: MqttConnectionConfig? = null

    private val _connectionState = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Idle)
    val connectionState: StateFlow<MqttConnectionState> = _connectionState

    private val _heartbeats = MutableSharedFlow<EstationInfo>(extraBufferCapacity = 32)
    val heartbeats: SharedFlow<EstationInfo> = _heartbeats

    private val _results = MutableSharedFlow<TaskResult>(extraBufferCapacity = 32)
    val results: SharedFlow<TaskResult> = _results

    private val _messageIssues = MutableSharedFlow<MqttMessageIssue>(extraBufferCapacity = 32)
    val messageIssues: SharedFlow<MqttMessageIssue> = _messageIssues

    fun connect(nextConfig: MqttConnectionConfig) {
        require(EStationValidators.isValidStationId(nextConfig.stationId)) {
            "Invalid station ID."
        }
        require(nextConfig.host.isNotBlank()) { "MQTT host is required." }
        require(nextConfig.port in 1..65535) { "Invalid MQTT port." }

        disconnect()
        config = nextConfig
        _connectionState.value = MqttConnectionState.Connecting

        val nextClient = MqttAsyncClient(
            nextConfig.serverUri,
            "hightac-android-${UUID.randomUUID()}",
            MemoryPersistence()
        )
        client = nextClient
        nextClient.setCallback(callbackFor(nextConfig))

        val options = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = true
            connectionTimeout = 8
            keepAliveInterval = 20
            if (nextConfig.username.isNotBlank()) {
                userName = nextConfig.username
            }
            if (nextConfig.password.isNotBlank()) {
                password = nextConfig.password.toCharArray()
            }
        }

        nextClient.connect(options, null, object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) {
                subscribeToStation(nextConfig)
            }

            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                _connectionState.value = MqttConnectionState.Failed(
                    exception?.message ?: "MQTT connect failed."
                )
            }
        })
    }

    fun disconnect() {
        val current = client
        client = null
        _connectionState.value = MqttConnectionState.Disconnected
        if (current != null) {
            // Detach callbacks first so a stale auto-reconnect cannot overwrite Disconnected.
            runCatching { current.setCallback(null) }
            runCatching {
                if (current.isConnected) current.disconnectForcibly(0, 0, false)
            }
            runCatching { current.close(true) }
        }
    }

    fun publishTask(taskData: TaskData) {
        val currentConfig = config ?: error("MQTT is not configured.")
        publish(EStationTopics.taskTopic(currentConfig.normalizedStationId), taskData.toJsonString())
    }

    private fun publish(topic: String, payload: String) {
        val current = client ?: error("MQTT is not connected.")
        if (!current.isConnected) error("MQTT is not connected.")
        val message = MqttMessage(payload.toByteArray(StandardCharsets.UTF_8)).apply {
            qos = 0
            isRetained = false
        }
        current.publish(topic, message)
    }

    private fun subscribeToStation(nextConfig: MqttConnectionConfig) {
        val current = client ?: return
        _connectionState.value = MqttConnectionState.Subscribing
        val topics = arrayOf(
            EStationTopics.heartbeatTopic(nextConfig.normalizedStationId),
            EStationTopics.resultTopic(nextConfig.normalizedStationId)
        )
        current.subscribe(topics, intArrayOf(0, 0), null, object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) {
                _connectionState.value = MqttConnectionState.Ready
            }

            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                _connectionState.value = MqttConnectionState.Failed(
                    exception?.message ?: "MQTT subscribe failed."
                )
            }
        })
    }

    private fun callbackFor(nextConfig: MqttConnectionConfig): MqttCallbackExtended {
        return object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                if (reconnect) {
                    subscribeToStation(nextConfig)
                }
            }

            override fun connectionLost(cause: Throwable?) {
                _connectionState.value = if (cause is MqttException) {
                    MqttConnectionState.Reconnecting
                } else {
                    MqttConnectionState.Disconnected
                }
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                val payload = String(message.payload, StandardCharsets.UTF_8)
                scope.launch {
                    when (topic) {
                        EStationTopics.heartbeatTopic(nextConfig.normalizedStationId) -> {
                            runCatching { EstationInfo.parse(payload, nextConfig.normalizedStationId) }
                                .onSuccess { _heartbeats.emit(it) }
                                .onFailure { emitMessageIssue(topic, payload, it) }
                        }
                        EStationTopics.resultTopic(nextConfig.normalizedStationId) -> {
                            runCatching { TaskResult.parse(payload, nextConfig.normalizedStationId) }
                                .onSuccess { _results.emit(it) }
                                .onFailure { emitMessageIssue(topic, payload, it) }
                        }
                        else -> {
                            _messageIssues.emit(
                                MqttMessageIssue(
                                    topic = topic,
                                    message = "Unexpected MQTT topic.",
                                    payloadPreview = payload.preview()
                                )
                            )
                        }
                    }
                }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        }
    }

    private suspend fun emitMessageIssue(topic: String, payload: String, error: Throwable) {
        _messageIssues.emit(
            MqttMessageIssue(
                topic = topic,
                message = error.message ?: "MQTT message parse failed.",
                payloadPreview = payload.preview()
            )
        )
    }
}

private fun String.preview(maxLength: Int = 240): String {
    val compact = replace(Regex("\\s+"), " ").trim()
    return if (compact.length <= maxLength) compact else compact.take(maxLength) + "..."
}
