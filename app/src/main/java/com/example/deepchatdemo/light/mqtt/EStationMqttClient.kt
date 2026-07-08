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
            return "$scheme://${host.trim()}:$port"
        }
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
        if (current != null) {
            runCatching {
                if (current.isConnected) current.disconnectForcibly(600, 600)
                current.close()
            }
        }
        _connectionState.value = MqttConnectionState.Disconnected
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
                        }
                        EStationTopics.resultTopic(nextConfig.normalizedStationId) -> {
                            runCatching { TaskResult.parse(payload, nextConfig.normalizedStationId) }
                                .onSuccess { _results.emit(it) }
                        }
                    }
                }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        }
    }
}
