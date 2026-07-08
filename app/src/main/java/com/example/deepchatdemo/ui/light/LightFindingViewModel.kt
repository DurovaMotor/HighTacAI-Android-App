package com.example.deepchatdemo.ui.light

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.deepchatdemo.light.data.SharedPreferencesLightBindingRepository
import com.example.deepchatdemo.light.data.StationConfigStore
import com.example.deepchatdemo.light.domain.LightBinding
import com.example.deepchatdemo.light.domain.LightColor
import com.example.deepchatdemo.light.domain.LightCommandSettings
import com.example.deepchatdemo.light.domain.LightEvent
import com.example.deepchatdemo.light.domain.LightStatus
import com.example.deepchatdemo.light.domain.StationConfig
import com.example.deepchatdemo.light.domain.batteryLevelFromRaw
import com.example.deepchatdemo.light.mqtt.MqttBrokerEndpoint
import com.example.deepchatdemo.light.mqtt.EStationMqttClient
import com.example.deepchatdemo.light.mqtt.MqttConnectionConfig
import com.example.deepchatdemo.light.mqtt.MqttConnectionState
import com.example.deepchatdemo.light.protocol.EStationValidators
import com.example.deepchatdemo.light.protocol.EstationInfo
import com.example.deepchatdemo.light.protocol.TaskData
import com.example.deepchatdemo.light.protocol.TaskResult
import com.example.deepchatdemo.light.protocol.TaskResultTypes
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class LightFindingUiState(
    val stationId: String = "",
    val brokerHost: String = "",
    val brokerPort: String = "1884",
    val username: String = "hightac_mqtt",
    val password: String = "hightac-light",
    val itemCode: String = "",
    val itemName: String = "",
    val tagId: String = "",
    val commandSettings: LightCommandSettings = LightCommandSettings(),
    val connectionState: MqttConnectionState = MqttConnectionState.Idle,
    val lastHeartbeat: EstationInfo? = null,
    val lastHeartbeatAtMillis: Long? = null,
    val lastResult: TaskResult? = null,
    val bindings: List<LightBinding> = emptyList(),
    val lightStatuses: Map<String, LightStatus> = emptyMap(),
    val events: List<LightEvent> = emptyList(),
    val inputMessage: String? = null,
    val showSettings: Boolean = false
) {
    val isConfigured: Boolean
        get() = stationId.isNotBlank() && brokerHost.isNotBlank()

    val isStationOnline: Boolean
        get() {
            val heartbeat = lastHeartbeat ?: return false
            val lastAt = lastHeartbeatAtMillis ?: return false
            return heartbeat.isHeartbeatFresh(lastAt, System.currentTimeMillis())
        }

    val boundCurrentItem: LightBinding?
        get() = bindings.firstOrNull { it.normalizedItemCode == itemCode.trim().uppercase() }
}

class LightFindingViewModel(
    private val configStore: StationConfigStore,
    private val bindingRepository: SharedPreferencesLightBindingRepository,
    private val mqttClient: EStationMqttClient = EStationMqttClient()
) : ViewModel() {
    var uiState by mutableStateOf(LightFindingUiState())
        private set

    init {
        val config = configStore.getConfig()
        uiState = uiState.copy(
            stationId = config.stationId,
            brokerHost = config.brokerHost,
            brokerPort = config.brokerPort.toString(),
            username = config.username,
            password = config.password,
            bindings = bindingRepository.getAll()
        )
        collectMqtt()
        if (config.isUsable()) {
            connectToBroker(config, "已读取上次 MQTT 配置，正在自动连接。")
        }
    }

    fun onStationIdChange(value: String) {
        uiState = uiState.copy(stationId = value.trim().uppercase(), inputMessage = null)
    }

    fun onBrokerHostChange(value: String) {
        uiState = uiState.copy(brokerHost = value.trim(), inputMessage = null)
    }

    fun onBrokerPortChange(value: String) {
        uiState = uiState.copy(brokerPort = value.filter { it.isDigit() }.take(5), inputMessage = null)
    }

    fun onUsernameChange(value: String) {
        uiState = uiState.copy(username = value.trim(), inputMessage = null)
    }

    fun onPasswordChange(value: String) {
        uiState = uiState.copy(password = value, inputMessage = null)
    }

    fun onItemCodeChange(value: String) {
        uiState = uiState.copy(itemCode = value.trim().uppercase(), inputMessage = null)
    }

    fun onItemNameChange(value: String) {
        uiState = uiState.copy(itemName = value, inputMessage = null)
    }

    fun onTagIdChange(value: String) {
        uiState = uiState.copy(tagId = value.trim().uppercase(), inputMessage = null)
    }

    fun selectColor(color: LightColor) {
        uiState = uiState.copy(
            commandSettings = uiState.commandSettings.copy(color = color)
        )
    }

    fun setBeep(enabled: Boolean) {
        uiState = uiState.copy(commandSettings = uiState.commandSettings.copy(beep = enabled))
    }

    fun setFlashing(enabled: Boolean) {
        uiState = uiState.copy(commandSettings = uiState.commandSettings.copy(flashing = enabled))
    }

    fun setDuration(seconds: Int) {
        uiState = uiState.copy(
            commandSettings = uiState.commandSettings.copy(
                durationSeconds = seconds.coerceIn(5, 180)
            )
        )
    }

    fun toggleSettings() {
        uiState = uiState.copy(showSettings = !uiState.showSettings)
    }

    fun prefillFromPrice(itemCode: String, itemName: String = "") {
        uiState = uiState.copy(
            itemCode = itemCode.trim().uppercase(),
            itemName = itemName.trim(),
            inputMessage = "已带入产品编码，请扫描或输入灯条 ID 后绑定。"
        )
    }

    fun saveAndConnect() {
        val stationId = runCatching { EStationValidators.requireStationId(uiState.stationId) }
            .getOrElse {
                uiState = uiState.copy(inputMessage = "基站 SN 格式应为 90A9F + 7 位十六进制字符。")
                return
            }
        val fallbackPort = uiState.brokerPort.toIntOrNull() ?: 1884
        val endpoint = runCatching {
            MqttBrokerEndpoint.parse(uiState.brokerHost, fallbackPort)
        }.getOrElse { error ->
            uiState = uiState.copy(inputMessage = error.message ?: "请填写可访问的 MQTT 地址和端口。")
            return
        }

        val config = StationConfig(
            stationId = stationId,
            brokerHost = endpoint.host,
            brokerPort = endpoint.port,
            username = uiState.username,
            password = uiState.password,
            tlsEnabled = endpoint.tlsEnabled
        )
        configStore.saveConfig(config)
        addEvent("保存 MQTT 配置", "${config.brokerHost}:${config.brokerPort} / $stationId")
        uiState = uiState.copy(
            brokerHost = config.brokerHost,
            brokerPort = config.brokerPort.toString()
        )
        connectToBroker(config, "正在连接 MQTT，并订阅基站心跳与回执。")
    }

    private fun connectToBroker(config: StationConfig, pendingMessage: String) {
        mqttClient.connect(
            MqttConnectionConfig(
                stationId = config.stationId,
                host = config.brokerHost,
                port = config.brokerPort,
                username = config.username,
                password = config.password,
                tlsEnabled = config.tlsEnabled
            )
        )
        uiState = uiState.copy(inputMessage = pendingMessage)
    }

    fun disconnect() {
        mqttClient.disconnect()
        addEvent("断开 MQTT", "App 已主动断开 broker。")
    }

    fun bindCurrent() {
        val itemCode = uiState.itemCode.trim().uppercase()
        if (itemCode.isBlank()) {
            uiState = uiState.copy(inputMessage = "请先输入产品编码。")
            return
        }
        val tagId = validateTagIdOrShowMessage(uiState.tagId) ?: return
        val stationId = validateStationIdOrShowMessage() ?: return
        val binding = LightBinding(
            itemCode = itemCode,
            itemName = uiState.itemName.takeIf { it.isNotBlank() },
            tagId = tagId,
            stationId = stationId
        )
        bindingRepository.upsert(binding)
        val bindings = bindingRepository.getAll()
        uiState = uiState.copy(
            bindings = bindings,
            inputMessage = "已绑定 $itemCode -> $tagId。"
        )
        addEvent("绑定灯条", "$itemCode -> $tagId")
    }

    fun lightByCurrentItem() {
        lightByItemCode(uiState.itemCode)
    }

    fun lightByItemCode(itemCode: String) {
        val binding = bindingForCode(itemCode)
        if (binding == null) {
            uiState = uiState.copy(inputMessage = "该产品编码还没有绑定灯条。")
            return
        }
        publishTask(
            task = TaskData.singleLightOn(binding.tagId, uiState.commandSettings),
            eventTitle = "点亮灯条",
            eventDetail = "${binding.itemCode} / ${binding.tagId}"
        )
    }

    fun lightByTagId(tagIdValue: String = uiState.tagId) {
        val tagId = validateTagIdOrShowMessage(tagIdValue) ?: return
        publishTask(
            task = TaskData.singleLightOn(tagId, uiState.commandSettings),
            eventTitle = "按灯条点亮",
            eventDetail = tagId
        )
    }

    fun turnOffByCurrentItem() {
        turnOffByItemCode(uiState.itemCode)
    }

    fun turnOffByItemCode(itemCode: String) {
        val binding = bindingForCode(itemCode)
        if (binding == null) {
            uiState = uiState.copy(inputMessage = "该产品编码还没有绑定灯条。")
            return
        }
        publishTask(
            task = TaskData.lightOff(listOf(binding.tagId)),
            eventTitle = "熄灭灯条",
            eventDetail = "${binding.itemCode} / ${binding.tagId}"
        )
    }

    fun turnOffAllBound() {
        val tagIds = uiState.bindings.map { it.tagId }.distinct()
        if (tagIds.isEmpty()) {
            uiState = uiState.copy(inputMessage = "暂无绑定灯条。")
            return
        }
        tagIds.chunked(EStationValidators.DEFAULT_TASK_BATCH_SIZE).forEach { batch ->
            publishTask(
                task = TaskData.lightOff(batch),
                eventTitle = "批量熄灭",
                eventDetail = "${batch.size} 个已绑定灯条"
            )
        }
    }

    fun bindingForCode(itemCode: String): LightBinding? {
        val normalized = itemCode.trim().uppercase()
        if (normalized.isBlank()) return null
        return uiState.bindings.firstOrNull { it.normalizedItemCode == normalized }
            ?: bindingRepository.findByItemCode(normalized).firstOrNull()
    }

    private fun publishTask(task: TaskData, eventTitle: String, eventDetail: String) {
        if (uiState.connectionState !is MqttConnectionState.Ready) {
            uiState = uiState.copy(inputMessage = "请先连接 MQTT。")
            return
        }
        if (!uiState.isStationOnline) {
            uiState = uiState.copy(inputMessage = "Broker 已连接，但还没有收到基站心跳。请先确认基站已连入 MQTT。")
            addEvent("基站未在线", "Broker 已连接，等待 ${uiState.stationId} 心跳", warning = true)
            return
        }
        val result = runCatching { mqttClient.publishTask(task) }
        result.onSuccess {
            addEvent(eventTitle, "$eventDetail / 已发布")
            uiState = uiState.copy(inputMessage = "$eventTitle 指令已发布，等待基站回执。")
        }.onFailure { error ->
            uiState = uiState.copy(inputMessage = error.message ?: "MQTT 发布失败。")
            addEvent(eventTitle, "$eventDetail / 发布失败", warning = true)
        }
    }

    private fun collectMqtt() {
        viewModelScope.launch {
            mqttClient.connectionState.collectLatest { state ->
                val message = when (state) {
                    MqttConnectionState.Ready -> {
                        if (uiState.isStationOnline) {
                            uiState.inputMessage
                        } else {
                            "Broker 已连接，暂未收到基站心跳；请检查基站 MQTT 地址、端口、账号密码和 TLS。"
                        }
                    }
                    is MqttConnectionState.Failed -> "MQTT 连接失败：${state.message}"
                    else -> uiState.inputMessage
                }
                uiState = uiState.copy(connectionState = state, inputMessage = message)
                when (state) {
                    MqttConnectionState.Ready -> {
                        if (!uiState.isStationOnline) {
                            addEvent("Broker 已连接", "等待基站 ${uiState.stationId} 心跳")
                        }
                    }
                    is MqttConnectionState.Failed -> addEvent("MQTT 连接失败", state.message, warning = true)
                    else -> Unit
                }
            }
        }
        viewModelScope.launch {
            mqttClient.heartbeats.collect { heartbeat ->
                uiState = uiState.copy(
                    lastHeartbeat = heartbeat,
                    lastHeartbeatAtMillis = System.currentTimeMillis(),
                    inputMessage = "已收到基站心跳，等待灯条回执。"
                )
                addEvent("基站心跳", "${heartbeat.stationId} / v${heartbeat.appVersion}")
            }
        }
        viewModelScope.launch {
            mqttClient.results.collect { result ->
                val nextStatuses = uiState.lightStatuses.toMutableMap()
                result.results.forEach { item ->
                    nextStatuses[item.tagId] = LightStatus(
                        tagId = item.tagId,
                        stationId = result.stationId,
                        version = item.version,
                        batteryVoltage = item.batteryVoltage,
                        batteryLevel = batteryLevelFromRaw(item.battery),
                        group = item.group,
                        onlineAtMillis = System.currentTimeMillis(),
                        lastResultType = item.resultType
                    )
                }
                uiState = uiState.copy(
                    lastResult = result,
                    lightStatuses = nextStatuses,
                    inputMessage = result.toReadableMessage()
                )
                addEvent("收到回执", result.toReadableMessage())
            }
        }
        viewModelScope.launch {
            mqttClient.messageIssues.collect { issue ->
                val detail = "${issue.topic} / ${issue.message}"
                uiState = uiState.copy(inputMessage = "收到 MQTT 消息但解析失败：${issue.message}")
                addEvent("MQTT 消息异常", detail, warning = true)
            }
        }
    }

    private fun TaskResult.toReadableMessage(): String {
        val first = results.firstOrNull() ?: return "基站已回执，暂无灯条明细。"
        return when (first.resultType) {
            TaskResultTypes.Communication -> "${first.tagId} 已通信回执。"
            TaskResultTypes.Button -> "${first.tagId} 现场按键已返回。"
            TaskResultTypes.Heartbeat -> "${first.tagId} 心跳更新，电量 ${first.batteryVoltage ?: "--"}V。"
            else -> "${first.tagId} 已回执，类型 ${first.resultType}。"
        }
    }

    private fun validateStationIdOrShowMessage(): String? {
        return runCatching { EStationValidators.requireStationId(uiState.stationId) }
            .getOrElse {
                uiState = uiState.copy(inputMessage = "请先填写合法基站 SN。")
                null
            }
    }

    private fun validateTagIdOrShowMessage(value: String): String? {
        val normalized = EStationValidators.normalizeScannedTagId(value)
        return runCatching { EStationValidators.requireTagId(normalized) }
            .getOrElse {
                uiState = uiState.copy(inputMessage = "灯条 ID 格式应为 AD1 + 9 位十六进制字符，或直接扫 9 位灯条短码。")
                null
            }
    }

    private fun addEvent(title: String, detail: String, warning: Boolean = false) {
        val event = LightEvent(title = title, detail = detail, isWarning = warning)
        uiState = uiState.copy(events = (listOf(event) + uiState.events).take(12))
    }

    override fun onCleared() {
        mqttClient.disconnect()
        super.onCleared()
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(LightFindingViewModel::class.java)) {
                        return LightFindingViewModel(
                            configStore = StationConfigStore(context),
                            bindingRepository = SharedPreferencesLightBindingRepository(context)
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }
}

private fun StationConfig.isUsable(): Boolean {
    return EStationValidators.isValidStationId(stationId) &&
        brokerHost.isNotBlank() &&
        brokerPort in 1..65535
}
