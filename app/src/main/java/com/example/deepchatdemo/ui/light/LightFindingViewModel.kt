package com.example.deepchatdemo.ui.light

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.deepchatdemo.light.data.LightBindingRepository
import com.example.deepchatdemo.light.domain.LightBinding
import com.example.deepchatdemo.light.domain.LightColor
import com.example.deepchatdemo.light.domain.LightCommandSettings
import com.example.deepchatdemo.light.domain.LightEvent
import com.example.deepchatdemo.light.domain.LightStatus
import com.example.deepchatdemo.platform.config.PlatformConfigStore
import com.example.deepchatdemo.platform.config.PlatformUrlProblem
import com.example.deepchatdemo.platform.config.PlatformUrlValidation
import com.example.deepchatdemo.platform.config.PlatformUrlValidator
import com.example.deepchatdemo.platform.integration.AndroidPlatformRuntime
import com.example.deepchatdemo.platform.integration.LegacyBindingConflictReason
import com.example.deepchatdemo.platform.integration.LegacyBindingMigrationPlan
import com.example.deepchatdemo.platform.integration.LegacyBindingMigrationPlanner
import com.example.deepchatdemo.platform.integration.LegacyBindingMigrationPreparation
import com.example.deepchatdemo.platform.integration.LegacyBindingMigrationReviewStore
import com.example.deepchatdemo.platform.integration.PlatformLightMapper
import com.example.deepchatdemo.platform.json.PlatformValueRules
import com.example.deepchatdemo.platform.model.BindingCreateRequest
import com.example.deepchatdemo.platform.model.Binding
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationClassification
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationCommitRequest
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationCommitResult
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationOutcome
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationPreview
import com.example.deepchatdemo.platform.model.BindingCreatedEvent
import com.example.deepchatdemo.platform.model.BindingRemovedEvent
import com.example.deepchatdemo.platform.model.BrokerServiceState
import com.example.deepchatdemo.platform.model.BrokerStatus
import com.example.deepchatdemo.platform.model.BrokerStatusChangedEvent
import com.example.deepchatdemo.platform.model.CommandStatus
import com.example.deepchatdemo.platform.model.CommandStatusChangedEvent
import com.example.deepchatdemo.platform.model.DeviceStatus
import com.example.deepchatdemo.platform.model.DeviceStatusChangedEvent
import com.example.deepchatdemo.platform.model.EnrollmentStatus
import com.example.deepchatdemo.platform.model.LightAction
import com.example.deepchatdemo.platform.model.LightCommand
import com.example.deepchatdemo.platform.model.LightCommandRequest
import com.example.deepchatdemo.platform.model.PlatformEvent
import com.example.deepchatdemo.platform.model.RebindRequest
import com.example.deepchatdemo.platform.model.Station
import com.example.deepchatdemo.platform.model.StationStatus
import com.example.deepchatdemo.platform.model.StationStatusChangedEvent
import com.example.deepchatdemo.platform.model.SystemNoticeEvent
import com.example.deepchatdemo.platform.network.IdempotencyKey
import com.example.deepchatdemo.platform.network.PlatformApiException
import com.example.deepchatdemo.platform.network.PlatformAuthenticationException
import com.example.deepchatdemo.platform.network.PlatformEventConnectionState
import com.example.deepchatdemo.platform.network.PlatformProtocolException
import com.example.deepchatdemo.platform.network.PlatformTransportException
import com.example.deepchatdemo.platform.repository.PlatformAccessState
import com.example.deepchatdemo.platform.repository.PlatformApiAvailability
import com.example.deepchatdemo.platform.repository.PlatformDeviceAuthorization
import com.example.deepchatdemo.platform.repository.PlatformRepository
import com.example.deepchatdemo.platform.repository.PlatformWriteClosedException
import com.example.deepchatdemo.platform.repository.PlatformWriteClosedReason
import com.example.deepchatdemo.platform.security.DeviceAlreadyApprovedException
import com.example.deepchatdemo.platform.security.DeviceEnrollmentController
import com.example.deepchatdemo.platform.security.EnrollmentClientState
import com.example.deepchatdemo.platform.security.EnrollmentRestartRequiredException
import com.example.deepchatdemo.platform.security.PendingEnrollmentExistsException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

enum class PlatformBackendUiStatus {
    UNKNOWN,
    CHECKING,
    AVAILABLE,
    UNAVAILABLE,
    CONTRACT_INCOMPATIBLE
}

enum class DeviceEnrollmentUiStatus {
    NOT_STARTED,
    REGISTERING,
    AUTO_REGISTERING,
    APPROVED,
    REJECTED,
    EXPIRED,
    REVOKED
}

data class LegacyBindingMigrationUiState(
    val serverUrl: String,
    val preparation: LegacyBindingMigrationPreparation,
    val plan: LegacyBindingMigrationPlan,
    val preview: AndroidBindingMigrationPreview? = null,
    val selectedDuplicateCandidates: Set<String> = emptySet(),
    val migrated: List<LightBinding> = emptyList(),
    val isPreviewing: Boolean = false,
    val isApplying: Boolean = false,
    val isComplete: Boolean = false
) {
    val pendingCount: Int
        get() = plan.migratable.size +
            plan.conflicts.count { conflict ->
                conflict.reason == LegacyBindingConflictReason.DUPLICATE_LEGACY_TAG &&
                    conflict.remoteBinding == null &&
                    conflict.clientRecordKey in selectedDuplicateCandidates
            }

    fun isSelected(binding: LightBinding): Boolean {
        return preparation.clientRecordKeyFor(binding) in selectedDuplicateCandidates
    }
}

data class LightFindingUiState(
    val serverUrl: String = PlatformUrlValidator.DEFAULT_SERVER_URL,
    val stationId: String = "",
    val itemCode: String = "",
    val itemName: String = "",
    val tagId: String = "",
    val commandSettings: LightCommandSettings = LightCommandSettings(),
    val backendStatus: PlatformBackendUiStatus = PlatformBackendUiStatus.UNKNOWN,
    val enrollmentStatus: DeviceEnrollmentUiStatus = DeviceEnrollmentUiStatus.NOT_STARTED,
    val platformAccess: PlatformAccessState = PlatformAccessState(
        apiAvailability = PlatformApiAvailability.UNKNOWN,
        deviceAuthorization = PlatformDeviceAuthorization.ENROLLMENT_REQUIRED
    ),
    val brokerStatus: BrokerStatus? = null,
    val brokerSnapshotStale: Boolean = true,
    val cacheSynchronizedAt: Instant? = null,
    val cacheStale: Boolean = true,
    val stations: List<Station> = emptyList(),
    val eventConnectionState: PlatformEventConnectionState =
        PlatformEventConnectionState.Stopped,
    val bindings: List<LightBinding> = emptyList(),
    val lightStatuses: Map<String, LightStatus> = emptyMap(),
    val events: List<LightEvent> = emptyList(),
    val legacyMigration: LegacyBindingMigrationUiState? = null,
    val inputMessage: String? = null,
    val showSettings: Boolean = false,
    val connectionEnabled: Boolean = false,
    val endpointVerified: Boolean = false,
    val operationInProgress: Boolean = false
) {
    val isConfigured: Boolean
        get() = PlatformUrlValidator.validateForWifiProduction(serverUrl) is
            PlatformUrlValidation.Valid

    val selectedStation: Station?
        get() = stations.firstOrNull { it.stationId == stationId }
            ?: stations.firstOrNull()

    val isStationOnline: Boolean
        get() = selectedStation?.status == StationStatus.ONLINE

    val isBrokerReady: Boolean
        get() = !brokerSnapshotStale && brokerStatus?.isReady == true

    val isAutoRegistering: Boolean
        get() = enrollmentStatus == DeviceEnrollmentUiStatus.AUTO_REGISTERING

    val canWrite: Boolean
        get() = connectionEnabled &&
            endpointVerified &&
            !operationInProgress &&
            platformAccess.canWrite &&
            isBrokerReady

    val boundCurrentItem: LightBinding?
        get() = bindings.firstOrNull {
            it.normalizedItemCode == itemCode.trim().uppercase()
        }
}

class LightFindingViewModel(
    private val configStore: PlatformConfigStore,
    private val repository: PlatformRepository,
    private val enrollmentManager: DeviceEnrollmentController,
    private val legacyBindingRepository: LightBindingRepository,
    private val legacyMigrationReviews: LegacyBindingMigrationReviewStore,
    private val runtimeCloser: () -> Unit = { repository.stopRealtime() },
    private val externalScope: CoroutineScope? = null,
    private val pollSleeper: suspend (Long) -> Unit = { delay(it) },
    autoConnect: Boolean = true
) : ViewModel() {
    var uiState by mutableStateOf(
        LightFindingUiState(
            serverUrl = configStore.currentServerUrl(),
            enrollmentStatus = enrollmentManager.state.value.toUiStatus()
        )
    )
        private set

    private val workScope: CoroutineScope
        get() = externalScope ?: viewModelScope

    private var connectionJob: Job? = null
    private var migrationJob: Job? = null
    private val plannedMigrationServers = mutableSetOf<String>()
    private var migrationCommitAttempt: MigrationCommitAttempt? = null
    private var awaitingInitialSnapshot = false

    init {
        collectPlatformState()
        if (autoConnect) connectOrEnroll()
    }

    fun onServerUrlChange(value: String) {
        uiState = uiState.copy(serverUrl = value.trim(), inputMessage = null)
    }

    fun onStationIdChange(value: String) {
        uiState = uiState.copy(stationId = value.trim().uppercase(), inputMessage = null)
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

    fun toggleSettings() {
        uiState = uiState.copy(showSettings = !uiState.showSettings)
    }

    fun onScreenActiveChanged(active: Boolean) {
        repository.setStationStatusRefreshActive(active)
    }

    fun prefillFromPrice(itemCode: String, itemName: String = "") {
        uiState = uiState.copy(
            itemCode = itemCode.trim().uppercase(),
            itemName = itemName.trim(),
            inputMessage = "已带入产品编码，请扫描或输入灯条 ID 后绑定。"
        )
    }

    fun saveAndConnect() {
        if (uiState.operationInProgress) {
            uiState = uiState.copy(inputMessage = "请等待当前写操作完成。")
            return
        }
        val previousServerUrl = configStore.currentServerUrl().trim().trimEnd('/')
        when (val validation = configStore.updateServerUrl(uiState.serverUrl)) {
            is PlatformUrlValidation.Invalid -> {
                uiState = uiState.copy(
                    showSettings = true,
                    connectionEnabled = false,
                    endpointVerified = false,
                    inputMessage = validation.problem.toUserMessage()
                )
            }

            is PlatformUrlValidation.Valid -> {
                connectionJob?.cancel()
                migrationJob?.cancel()
                migrationJob = null
                if (validation.endpoint.canonicalServerUrl != previousServerUrl) {
                    migrationCommitAttempt = null
                }
                repository.stopRealtime()
                uiState = uiState.copy(
                    serverUrl = validation.endpoint.canonicalServerUrl,
                    backendStatus = PlatformBackendUiStatus.CHECKING,
                    connectionEnabled = false,
                    endpointVerified = false,
                    legacyMigration = uiState.legacyMigration?.takeIf {
                        it.serverUrl == validation.endpoint.canonicalServerUrl
                    },
                    inputMessage = "正在连接 HighTac Platform。"
                )
                connectOrEnroll()
            }
        }
    }

    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        migrationJob?.cancel()
        migrationJob = null
        repository.stopRealtime()
        awaitingInitialSnapshot = false
        uiState = uiState.copy(
            backendStatus = PlatformBackendUiStatus.UNKNOWN,
            connectionEnabled = false,
            endpointVerified = false,
            inputMessage = "已断开 HighTac Platform。"
        )
        addEvent("断开后台", uiState.serverUrl)
    }

    fun bindCurrent() {
        val productCode = normalizeProductCodeOrShowMessage(uiState.itemCode) ?: return
        val tagId = normalizeTagIdOrShowMessage(uiState.tagId) ?: return
        val stationId = stationIdOrShowMessage() ?: return
        val productName = uiState.itemName.trim().takeIf(String::isNotEmpty)
        val existing = uiState.bindings.firstOrNull { it.normalizedTagId == tagId }

        if (existing?.normalizedItemCode == productCode) {
            uiState = uiState.copy(inputMessage = "$tagId 已绑定到 $productCode。")
            return
        }

        launchWrite(
            eventTitle = if (existing == null) "绑定灯条" else "重新绑定灯条",
            eventDetail = "$productCode / $tagId"
        ) {
            if (existing == null) {
                repository.bind(
                    BindingCreateRequest(
                        productCode = productCode,
                        productName = productName,
                        tagId = tagId,
                        stationId = stationId
                    )
                )
            } else {
                repository.rebind(
                    bindingId = UUID.fromString(existing.id),
                    request = RebindRequest(
                        productCode = productCode,
                        productName = productName,
                        expectedTagId = tagId
                    )
                )
            }
        }
    }

    fun unbind(binding: LightBinding) {
        val bindingId = runCatching { UUID.fromString(binding.id) }.getOrElse {
            uiState = uiState.copy(inputMessage = "绑定记录标识无效，请刷新服务器快照。")
            return
        }
        launchWrite(
            eventTitle = "解绑灯条",
            eventDetail = "${binding.itemCode} / ${binding.tagId}"
        ) {
            repository.unbind(bindingId)
        }
    }

    fun selectLegacyMigrationCandidate(binding: LightBinding) {
        val migration = uiState.legacyMigration ?: return
        if (migration.isPreviewing || migration.isApplying || migration.isComplete) return
        val clientRecordKey = migration.preparation.clientRecordKeyFor(binding) ?: return
        val selectedConflict = migration.plan.conflicts.firstOrNull { conflict ->
            conflict.clientRecordKey == clientRecordKey &&
                conflict.reason == LegacyBindingConflictReason.DUPLICATE_LEGACY_TAG &&
                conflict.remoteBinding == null
        } ?: return
        val tagId = selectedConflict.legacy.normalizedTagId
        val keysForTag = migration.plan.conflicts
            .filter { it.legacy.normalizedTagId == tagId }
            .mapNotNull { it.clientRecordKey }
            .toSet()
        migrationCommitAttempt = null
        uiState = uiState.copy(
            legacyMigration = migration.copy(
                selectedDuplicateCandidates =
                    (migration.selectedDuplicateCandidates - keysForTag) +
                        clientRecordKey
            )
        )
    }

    fun confirmLegacyBindingMigration() {
        val migration = uiState.legacyMigration ?: return
        if (migration.isPreviewing || migration.isApplying || migration.isComplete) return
        if (migration.preparation.records.isEmpty()) {
            val completed = migration.copy(isComplete = true)
            uiState = uiState.copy(
                legacyMigration = completed,
                inputMessage = "旧记录均无法提交，请确认保留这些本地冲突。"
            )
            return
        }
        val preview = migration.preview
        if (preview == null) {
            requestLegacyMigrationPreview(migration, stale = false)
            return
        }
        if (!uiState.canWrite) {
            showWritesClosedMessage()
            return
        }

        val selectedKeys = migration.preparation.records
            .map { it.request.clientRecordKey }
            .filter(migration.selectedDuplicateCandidates::contains)
        val commitRequest = AndroidBindingMigrationCommitRequest(
            records = migration.preparation.previewRequest.records,
            previewToken = preview.previewToken,
            selectedDuplicateKeys = selectedKeys
        )
        val attempt = migrationCommitAttempt
            ?.takeIf { it.serverUrl == migration.serverUrl && it.request == commitRequest }
            ?: MigrationCommitAttempt(
                serverUrl = migration.serverUrl,
                request = commitRequest,
                idempotencyKey = repository.newWriteIdempotencyKey()
            )
        migrationCommitAttempt = attempt
        uiState = uiState.copy(
            legacyMigration = migration.copy(isApplying = true),
            operationInProgress = true,
            inputMessage = "正在原子提交 ${migration.pendingCount} 条旧绑定。"
        )

        migrationJob?.cancel()
        migrationJob = workScope.launch {
            val serverUrl = migration.serverUrl
            try {
                val committed = repository.commitAndroidBindingMigration(
                    request = attempt.request,
                    idempotencyKey = attempt.idempotencyKey
                )
                if (uiState.serverUrl != serverUrl || !uiState.connectionEnabled) {
                    throw CancellationException("Platform endpoint changed.")
                }
                validateLegacyMigrationCommit(migration, committed, selectedKeys.toSet())
                migrationCommitAttempt = null
                finishLegacyMigrationCommit(serverUrl, committed)
            } catch (error: CancellationException) {
                if (uiState.serverUrl == serverUrl) {
                    uiState = uiState.copy(
                        operationInProgress = false,
                        legacyMigration = uiState.legacyMigration?.copy(isApplying = false)
                    )
                }
                throw error
            } catch (error: PlatformApiException) {
                if (error.apiError?.code == MIGRATION_PREVIEW_STALE) {
                    migrationCommitAttempt = null
                    repreviewStaleLegacyMigration(serverUrl)
                } else {
                    pauseLegacyMigration(serverUrl, error)
                }
            } catch (error: Exception) {
                pauseLegacyMigration(serverUrl, error)
            }
        }
    }

    fun acknowledgeLegacyMigrationConflicts() {
        val migration = uiState.legacyMigration ?: return
        if (!migration.isComplete || migration.isApplying || migration.pendingCount > 0) return
        runCatching { legacyMigrationReviews.markReviewed(migration.serverUrl) }
            .onSuccess {
                uiState = uiState.copy(
                    legacyMigration = null,
                    inputMessage = "旧绑定迁移预览已确认，冲突记录未被覆盖。"
                )
            }
            .onFailure {
                uiState = uiState.copy(inputMessage = "无法保存迁移预览确认状态。")
            }
    }

    fun dismissLegacyMigrationPreview() {
        if (uiState.legacyMigration?.isApplying == true) return
        migrationJob?.cancel()
        migrationJob = null
        migrationCommitAttempt = null
        uiState = uiState.copy(legacyMigration = null)
    }

    fun lightByCurrentItem() {
        lightByItemCode(uiState.itemCode)
    }

    fun lightByItemCode(itemCode: String) {
        val productCode = normalizeProductCodeOrShowMessage(itemCode) ?: return
        val bindings = bindingsForCode(productCode)
        if (bindings.isEmpty()) {
            uiState = uiState.copy(inputMessage = "该产品编码还没有绑定灯条。")
            return
        }
        val color = platformColorOrShowMessage() ?: return
        launchCommand(
            request = LightCommandRequest.forProduct(
                action = LightAction.LIGHT_ON,
                productCode = productCode,
                color = color
            ),
            eventTitle = "点亮产品灯条",
            eventDetail = "$productCode / ${bindings.size} 个灯条"
        )
    }

    fun lightByTagId(tagIdValue: String = uiState.tagId) {
        val tagId = normalizeTagIdOrShowMessage(tagIdValue) ?: return
        val color = platformColorOrShowMessage() ?: return
        launchCommand(
            request = LightCommandRequest.forTags(
                action = LightAction.LIGHT_ON,
                tagIds = listOf(tagId),
                color = color
            ),
            eventTitle = "按灯条点亮",
            eventDetail = tagId
        )
    }

    fun turnOffByCurrentItem() {
        turnOffByItemCode(uiState.itemCode)
    }

    fun turnOffByItemCode(itemCode: String) {
        val productCode = normalizeProductCodeOrShowMessage(itemCode) ?: return
        val bindings = bindingsForCode(productCode)
        if (bindings.isEmpty()) {
            uiState = uiState.copy(inputMessage = "该产品编码还没有绑定灯条。")
            return
        }
        launchCommand(
            request = LightCommandRequest.forProduct(
                action = LightAction.LIGHT_OFF,
                productCode = productCode
            ),
            eventTitle = "熄灭产品灯条",
            eventDetail = "$productCode / ${bindings.size} 个灯条"
        )
    }

    fun turnOffAllBound() {
        val stationIds = uiState.bindings
            .map { it.stationId }
            .plus(uiState.selectedStation?.stationId.orEmpty())
            .filter(String::isNotBlank)
            .distinct()
        if (stationIds.isEmpty()) {
            uiState = uiState.copy(inputMessage = "服务器快照中暂无可全灭的基站。")
            return
        }
        launchWrite(
            eventTitle = "全灭",
            eventDetail = "${stationIds.size} 个基站"
        ) {
            stationIds.map { stationId -> repository.allOff(stationId) }
        }
    }

    fun bindingForCode(itemCode: String): LightBinding? {
        val normalized = itemCode.trim().uppercase()
        if (normalized.isBlank()) return null
        return uiState.bindings.firstOrNull { it.normalizedItemCode == normalized }
    }

    private fun bindingsForCode(itemCode: String): List<LightBinding> {
        val normalized = itemCode.trim().uppercase()
        return uiState.bindings.filter { it.normalizedItemCode == normalized }
    }

    private fun connectOrEnroll() {
        connectionJob?.cancel()
        uiState = uiState.copy(
            backendStatus = PlatformBackendUiStatus.CHECKING,
            connectionEnabled = true,
            endpointVerified = false
        )
        connectionJob = workScope.launch {
            val authorization = repository.accessState.value.deviceAuthorization
            when (val enrollment = enrollmentManager.state.value) {
                EnrollmentClientState.Approved -> {
                    if (
                        authorization == PlatformDeviceAuthorization.REVOKED_OR_INVALID ||
                        authorization == PlatformDeviceAuthorization.ENROLLMENT_REQUIRED
                    ) {
                        beginThenPoll()
                    } else {
                        startApprovedSession()
                    }
                }
                is EnrollmentClientState.Pending -> pollUntilFinished(AUTO_REGISTRATION_POLL_MILLIS)
                EnrollmentClientState.Creating -> Unit
                EnrollmentClientState.NotStarted,
                is EnrollmentClientState.Finished -> beginThenPoll()
            }
        }
    }

    private suspend fun beginThenPoll() {
        while (true) {
            uiState = uiState.copy(
                enrollmentStatus = DeviceEnrollmentUiStatus.REGISTERING,
                inputMessage = "正在登记此设备。"
            )
            try {
                val created = enrollmentManager.beginEnrollment()
                if (created.status == EnrollmentStatus.APPROVED) {
                    uiState = uiState.copy(
                        backendStatus = PlatformBackendUiStatus.AVAILABLE,
                        enrollmentStatus = DeviceEnrollmentUiStatus.APPROVED,
                        inputMessage = "设备已自动注册，正在同步服务器快照。"
                    )
                    addEvent("设备自动注册", created.displayName ?: "Android 设备")
                    startApprovedSession()
                    return
                }
                uiState = uiState.copy(
                    backendStatus = PlatformBackendUiStatus.AVAILABLE,
                    enrollmentStatus = DeviceEnrollmentUiStatus.AUTO_REGISTERING,
                    inputMessage = "设备正在自动注册。"
                )
                addEvent("设备登记", "正在自动注册")
                val shouldRestart = pollUntilFinished(
                    created.pollAfterSeconds?.times(1_000L) ?: AUTO_REGISTRATION_POLL_MILLIS
                )
                if (!shouldRestart) return
            } catch (_: DeviceAlreadyApprovedException) {
                startApprovedSession()
                return
            } catch (_: PendingEnrollmentExistsException) {
                if (!pollUntilFinished(AUTO_REGISTRATION_POLL_MILLIS)) return
            } catch (_: EnrollmentRestartRequiredException) {
                pollSleeper(AUTO_REGISTRATION_POLL_MILLIS)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                handleConnectionFailure(error)
                return
            }
        }
    }

    private suspend fun pollUntilFinished(initialDelayMillis: Long): Boolean {
        var nextDelayMillis = initialDelayMillis.coerceAtLeast(1_000L)
        uiState = uiState.copy(
            enrollmentStatus = DeviceEnrollmentUiStatus.AUTO_REGISTERING,
            inputMessage = "设备正在自动注册。"
        )
        while (true) {
            pollSleeper(nextDelayMillis)
            try {
                val result = enrollmentManager.pollEnrollment()
                uiState = uiState.copy(backendStatus = PlatformBackendUiStatus.AVAILABLE)
                when (result.status) {
                    EnrollmentStatus.PENDING -> {
                        uiState = uiState.copy(
                            enrollmentStatus = DeviceEnrollmentUiStatus.AUTO_REGISTERING,
                            inputMessage = "设备正在自动注册。"
                        )
                        nextDelayMillis = AUTO_REGISTRATION_POLL_MILLIS
                    }

                    EnrollmentStatus.APPROVED -> {
                        uiState = uiState.copy(
                            enrollmentStatus = DeviceEnrollmentUiStatus.APPROVED,
                            inputMessage = "设备已自动注册，正在同步服务器快照。"
                        )
                        addEvent("设备自动注册", result.displayName ?: "Android 设备")
                        startApprovedSession()
                        return false
                    }

                    EnrollmentStatus.REJECTED -> {
                        uiState = uiState.copy(
                            enrollmentStatus = DeviceEnrollmentUiStatus.AUTO_REGISTERING,
                            endpointVerified = false,
                            inputMessage = "设备注册状态已更新，正在自动重试。"
                        )
                        pollSleeper(AUTO_REGISTRATION_POLL_MILLIS)
                        return true
                    }

                    EnrollmentStatus.EXPIRED -> {
                        uiState = uiState.copy(
                            enrollmentStatus = DeviceEnrollmentUiStatus.AUTO_REGISTERING,
                            endpointVerified = false,
                            inputMessage = "设备注册已刷新，正在自动重试。"
                        )
                        pollSleeper(AUTO_REGISTRATION_POLL_MILLIS)
                        return true
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: EnrollmentRestartRequiredException) {
                pollSleeper(AUTO_REGISTRATION_POLL_MILLIS)
                return true
            } catch (error: PlatformTransportException) {
                uiState = uiState.copy(
                    backendStatus = PlatformBackendUiStatus.UNAVAILABLE,
                    endpointVerified = false,
                    inputMessage = "后台暂时不可用，恢复后将继续自动注册。"
                )
                nextDelayMillis = AUTO_REGISTRATION_POLL_MILLIS
            } catch (error: PlatformApiException) {
                if (error.statusCode == 429 || error.isUnavailable) {
                    uiState = uiState.copy(
                        backendStatus = PlatformBackendUiStatus.UNAVAILABLE,
                        endpointVerified = false,
                        inputMessage = "后台暂时不可用，恢复后将继续自动注册。"
                    )
                    nextDelayMillis = error.retryAfterSeconds
                        ?.coerceIn(1L, 60L)
                        ?.times(1_000L)
                        ?: AUTO_REGISTRATION_POLL_MILLIS
                } else {
                    handleConnectionFailure(error)
                    return false
                }
            } catch (error: Exception) {
                handleConnectionFailure(error)
                return false
            }
        }
    }

    private fun startApprovedSession() {
        awaitingInitialSnapshot = true
        uiState = uiState.copy(
            enrollmentStatus = DeviceEnrollmentUiStatus.APPROVED,
            backendStatus = PlatformBackendUiStatus.CHECKING,
            connectionEnabled = true,
            endpointVerified = false,
            inputMessage = PLATFORM_SYNCING_MESSAGE
        )
        repository.startRealtime()
    }

    private fun launchCommand(
        request: LightCommandRequest,
        eventTitle: String,
        eventDetail: String
    ) {
        launchWrite(eventTitle, eventDetail) {
            repository.sendLightCommand(request)
        }
    }

    private fun launchWrite(
        eventTitle: String,
        eventDetail: String,
        block: suspend () -> Any
    ) {
        if (!uiState.canWrite) {
            showWritesClosedMessage()
            return
        }
        uiState = uiState.copy(operationInProgress = true, inputMessage = "$eventTitle 请求中。")
        workScope.launch {
            try {
                val result = block()
                val accepted = (result as? LightCommand)?.let { command ->
                    "服务端已受理 ${command.targetCount} 个灯条。"
                } ?: "$eventTitle 已完成。"
                uiState = uiState.copy(
                    operationInProgress = false,
                    inputMessage = accepted
                )
                addEvent(eventTitle, "$eventDetail / 已提交")
            } catch (error: CancellationException) {
                uiState = uiState.copy(operationInProgress = false)
                throw error
            } catch (error: Exception) {
                uiState = uiState.copy(
                    operationInProgress = false,
                    endpointVerified = uiState.endpointVerified &&
                        error !is PlatformTransportException &&
                        error !is PlatformAuthenticationException,
                    inputMessage = error.toUserMessage()
                )
                addEvent(eventTitle, "$eventDetail / 失败", warning = true)
            }
        }
    }

    private fun collectPlatformState() {
        workScope.launch {
            repository.accessState.collect { access ->
                val backendStatus = when (access.apiAvailability) {
                    PlatformApiAvailability.UNKNOWN -> uiState.backendStatus
                    PlatformApiAvailability.CHECKING -> PlatformBackendUiStatus.CHECKING
                    PlatformApiAvailability.AVAILABLE -> PlatformBackendUiStatus.AVAILABLE
                    PlatformApiAvailability.UNAVAILABLE -> PlatformBackendUiStatus.UNAVAILABLE
                    PlatformApiAvailability.CONTRACT_INCOMPATIBLE ->
                        PlatformBackendUiStatus.CONTRACT_INCOMPATIBLE
                }
                val enrollmentStatus = when (access.deviceAuthorization) {
                    PlatformDeviceAuthorization.APPROVED ->
                        DeviceEnrollmentUiStatus.APPROVED
                    PlatformDeviceAuthorization.REVOKED_OR_INVALID ->
                        DeviceEnrollmentUiStatus.AUTO_REGISTERING
                    PlatformDeviceAuthorization.ENROLLMENT_REQUIRED ->
                        enrollmentManager.state.value.toUiStatus()
                    PlatformDeviceAuthorization.UNKNOWN -> uiState.enrollmentStatus
                }
                uiState = uiState.copy(
                    platformAccess = access,
                    backendStatus = backendStatus,
                    enrollmentStatus = enrollmentStatus,
                    endpointVerified = uiState.connectionEnabled &&
                        access.apiAvailability == PlatformApiAvailability.AVAILABLE &&
                        access.deviceAuthorization == PlatformDeviceAuthorization.APPROVED,
                    inputMessage = when {
                        access.deviceAuthorization ==
                            PlatformDeviceAuthorization.REVOKED_OR_INVALID ->
                            "设备凭据已失效，正在重新自动注册。"
                        access.apiAvailability ==
                            PlatformApiAvailability.CONTRACT_INCOMPATIBLE ->
                            "后台响应与客户端合同不兼容，写操作已关闭。"
                        access.apiAvailability == PlatformApiAvailability.UNAVAILABLE ->
                            PLATFORM_UNAVAILABLE_MESSAGE
                        access.apiAvailability == PlatformApiAvailability.AVAILABLE &&
                            access.deviceAuthorization == PlatformDeviceAuthorization.APPROVED &&
                            uiState.inputMessage == PLATFORM_UNAVAILABLE_MESSAGE -> null
                        else -> uiState.inputMessage
                    }
                )
                val shouldRecoverCredentials = uiState.connectionEnabled &&
                    (
                        access.deviceAuthorization ==
                            PlatformDeviceAuthorization.ENROLLMENT_REQUIRED ||
                            access.deviceAuthorization ==
                            PlatformDeviceAuthorization.REVOKED_OR_INVALID
                    ) &&
                    enrollmentManager.state.value == EnrollmentClientState.Approved &&
                    connectionJob?.isActive != true
                if (shouldRecoverCredentials) connectOrEnroll()
                maybePlanLegacyMigration()
            }
        }
        workScope.launch {
            enrollmentManager.state.collect { state ->
                uiState = uiState.copy(
                    enrollmentStatus = state.toUiStatus()
                )
            }
        }
        workScope.launch {
            repository.brokerStatus.collect { snapshot ->
                uiState = uiState.copy(
                    brokerStatus = snapshot.value,
                    brokerSnapshotStale = snapshot.isStale
                )
            }
        }
        workScope.launch {
            combine(repository.products, repository.bindings, repository.tags) {
                    products, bindings, tags ->
                val synchronizedAt = listOfNotNull(
                    products.synchronizedAt,
                    bindings.synchronizedAt,
                    tags.synchronizedAt
                ).minOrNull()
                val isIncomplete = products.synchronizedAt == null ||
                    bindings.synchronizedAt == null ||
                    tags.synchronizedAt == null
                synchronizedAt to (
                    isIncomplete || products.isStale || bindings.isStale || tags.isStale
                )
            }.collect { (synchronizedAt, stale) ->
                val initialSyncCompleted = awaitingInitialSnapshot &&
                    synchronizedAt != null &&
                    !stale
                if (initialSyncCompleted) {
                    awaitingInitialSnapshot = false
                }
                uiState = uiState.copy(
                    cacheSynchronizedAt = synchronizedAt,
                    cacheStale = stale,
                    inputMessage = if (
                        initialSyncCompleted && uiState.inputMessage == PLATFORM_SYNCING_MESSAGE
                    ) {
                        PLATFORM_SYNCED_MESSAGE
                    } else {
                        uiState.inputMessage
                    }
                )
            }
        }
        workScope.launch {
            repository.bindings.collect { snapshot ->
                uiState = uiState.copy(
                    bindings = PlatformLightMapper.bindings(snapshot.value)
                )
                maybePlanLegacyMigration()
            }
        }
        workScope.launch {
            repository.tags.collect { snapshot ->
                uiState = uiState.copy(
                    lightStatuses = PlatformLightMapper.statuses(snapshot.value)
                )
            }
        }
        workScope.launch {
            repository.stations.collect { snapshot ->
                val stations = snapshot.value
                val selectedStationId = uiState.stationId
                    .takeIf { current -> stations.any { it.stationId == current } }
                    ?: stations.firstOrNull()?.stationId
                    ?: uiState.stationId
                uiState = uiState.copy(
                    stations = stations,
                    stationId = selectedStationId
                )
            }
        }
        workScope.launch {
            repository.eventConnectionState.collect { state ->
                uiState = uiState.copy(eventConnectionState = state)
            }
        }
        workScope.launch {
            repository.events.collect { event ->
                event.toLightEvent()?.let { presentation ->
                    addEvent(
                        title = presentation.title,
                        detail = presentation.detail,
                        warning = presentation.warning
                    )
                }
            }
        }
    }

    private fun maybePlanLegacyMigration() {
        val serverUrl = uiState.serverUrl.trim().trimEnd('/')
        if (!uiState.endpointVerified ||
            uiState.platformAccess.deviceAuthorization != PlatformDeviceAuthorization.APPROVED ||
            serverUrl in plannedMigrationServers
        ) {
            return
        }
        if (runCatching { legacyMigrationReviews.isReviewed(serverUrl) }.getOrDefault(false)) {
            plannedMigrationServers += serverUrl
            return
        }

        plannedMigrationServers += serverUrl
        val legacyBindings = runCatching { legacyBindingRepository.getAll() }
            .getOrElse {
                uiState = uiState.copy(
                    inputMessage = "无法读取旧绑定，未执行任何迁移写入。"
                )
                addEvent("旧绑定预览失败", "旧 SharedPreferences 读取失败", warning = true)
                return
            }
        if (legacyBindings.isEmpty()) {
            return
        }

        val preparation = LegacyBindingMigrationPlanner.prepare(legacyBindings)
        val migration = LegacyBindingMigrationUiState(
            serverUrl = serverUrl,
            preparation = preparation,
            plan = LegacyBindingMigrationPlanner.awaitingServerPlan(preparation)
        )
        uiState = uiState.copy(
            legacyMigration = migration,
            inputMessage = "发现 ${preparation.totalLegacyBindings} 条旧绑定，正在请求服务端预览。"
        )
        if (preparation.records.isEmpty()) {
            uiState = uiState.copy(
                inputMessage = "旧绑定均为本地无效记录，未向服务端提交任何数据。"
            )
            addEvent(
                title = "旧绑定迁移预览",
                detail = "本地冲突 ${preparation.invalidConflicts.size}",
                warning = true
            )
        } else {
            requestLegacyMigrationPreview(migration, stale = false)
        }
    }

    private fun requestLegacyMigrationPreview(
        migration: LegacyBindingMigrationUiState,
        stale: Boolean
    ) {
        migrationCommitAttempt = null
        val loading = migration.copy(
            preview = null,
            plan = LegacyBindingMigrationPlanner.awaitingServerPlan(migration.preparation),
            selectedDuplicateCandidates = emptySet(),
            isPreviewing = true,
            isApplying = false,
            isComplete = false
        )
        uiState = uiState.copy(
            operationInProgress = false,
            legacyMigration = loading,
            inputMessage = if (stale) {
                "服务端状态已变化，正在重新预览；旧选择已清除。"
            } else {
                "正在获取旧绑定的服务端预览。"
            }
        )
        migrationJob?.cancel()
        migrationJob = workScope.launch { loadLegacyMigrationPreview(loading, stale) }
    }

    private suspend fun loadLegacyMigrationPreview(
        loading: LegacyBindingMigrationUiState,
        stale: Boolean
    ) {
        val serverUrl = loading.serverUrl
        try {
            val preview = repository.previewAndroidBindingMigration(
                loading.preparation.previewRequest
            )
            if (uiState.serverUrl != serverUrl || !uiState.connectionEnabled) {
                throw CancellationException("Platform endpoint changed.")
            }
            val plan = LegacyBindingMigrationPlanner.plan(loading.preparation, preview)
            uiState = uiState.copy(
                legacyMigration = loading.copy(
                    preview = preview,
                    plan = plan,
                    isPreviewing = false
                ),
                inputMessage = if (stale) {
                    "服务端预览已更新，请重新选择并再次确认。"
                } else {
                    "服务端已预览 ${plan.totalLegacyBindings} 条旧绑定，请确认后再迁移。"
                }
            )
            addEvent(
                title = if (stale) "旧绑定迁移已重新预览" else "旧绑定迁移预览",
                detail = "可迁移 ${plan.migratable.size} / 冲突 ${plan.conflicts.size}",
                warning = stale
            )
        } catch (error: CancellationException) {
            if (uiState.serverUrl == serverUrl) {
                uiState = uiState.copy(
                    legacyMigration = uiState.legacyMigration?.copy(isPreviewing = false)
                )
            }
            throw error
        } catch (error: Exception) {
            if (uiState.serverUrl == serverUrl) {
                uiState = uiState.copy(
                    operationInProgress = false,
                    legacyMigration = loading.copy(isPreviewing = false),
                    inputMessage = "旧绑定预览失败：${error.toUserMessage()}"
                )
                addEvent("旧绑定预览失败", error.toUserMessage(), warning = true)
            }
        }
    }

    private suspend fun repreviewStaleLegacyMigration(serverUrl: String) {
        val migration = uiState.legacyMigration?.takeIf { it.serverUrl == serverUrl } ?: return
        val loading = migration.copy(
            preview = null,
            plan = LegacyBindingMigrationPlanner.awaitingServerPlan(migration.preparation),
            selectedDuplicateCandidates = emptySet(),
            isPreviewing = true,
            isApplying = false,
            isComplete = false
        )
        uiState = uiState.copy(
            operationInProgress = false,
            legacyMigration = loading,
            inputMessage = "服务端状态已变化，正在重新预览；旧选择已清除。"
        )
        loadLegacyMigrationPreview(loading, stale = true)
    }

    private fun pauseLegacyMigration(serverUrl: String, error: Exception) {
        if (uiState.serverUrl != serverUrl) return
        uiState = uiState.copy(
            operationInProgress = false,
            legacyMigration = uiState.legacyMigration?.copy(isApplying = false),
            inputMessage = "旧绑定迁移已暂停：${error.toUserMessage()}"
        )
        addEvent("旧绑定迁移暂停", error.toUserMessage(), warning = true)
    }

    private fun validateLegacyMigrationCommit(
        migration: LegacyBindingMigrationUiState,
        committed: AndroidBindingMigrationCommitResult,
        selectedKeys: Set<String>
    ) {
        val preview = requireNotNull(migration.preview)
        if (preview.records.map { it.clientRecordKey } !=
            committed.records.map { it.clientRecordKey }
        ) {
            throw PlatformProtocolException()
        }
        preview.records.zip(committed.records).forEach { (previewRecord, committedRecord) ->
            val expectedOutcome = when (previewRecord.classification) {
                AndroidBindingMigrationClassification.MIGRATABLE ->
                    AndroidBindingMigrationOutcome.MIGRATED
                AndroidBindingMigrationClassification.IDENTICAL ->
                    AndroidBindingMigrationOutcome.IDENTICAL
                AndroidBindingMigrationClassification.DUPLICATE_LEGACY_TAG ->
                    if (previewRecord.clientRecordKey in selectedKeys) {
                        AndroidBindingMigrationOutcome.MIGRATED
                    } else {
                        AndroidBindingMigrationOutcome.SKIPPED
                    }
                AndroidBindingMigrationClassification.TAG_BOUND_TO_DIFFERENT_PRODUCT,
                AndroidBindingMigrationClassification.STATION_MISMATCH,
                AndroidBindingMigrationClassification.STATION_NOT_FOUND ->
                    AndroidBindingMigrationOutcome.SKIPPED
            }
            if (committedRecord.classification != previewRecord.classification ||
                committedRecord.outcome != expectedOutcome
            ) {
                throw PlatformProtocolException()
            }
        }
    }

    private fun finishLegacyMigrationCommit(
        serverUrl: String,
        committed: AndroidBindingMigrationCommitResult
    ) {
        val migration = uiState.legacyMigration ?: return
        if (migration.serverUrl != serverUrl) return
        val migratedKeys = committed.records
            .filter { it.outcome == AndroidBindingMigrationOutcome.MIGRATED }
            .map { it.clientRecordKey }
            .toSet()
        val migrated = migratedKeys.mapNotNull(migration.preparation::legacyFor)
        val completed = migration.copy(
            plan = migration.plan.copy(
                migratable = emptyList(),
                conflicts = migration.plan.conflicts.filterNot {
                    it.clientRecordKey in migratedKeys
                }
            ),
            selectedDuplicateCandidates = emptySet(),
            migrated = migrated,
            isApplying = false,
            isComplete = true
        )
        val conflictCount = completed.plan.conflicts.size
        val reviewSaved = if (conflictCount == 0) {
            runCatching { legacyMigrationReviews.markReviewed(serverUrl) }.isSuccess
        } else {
            false
        }
        uiState = uiState.copy(
            operationInProgress = false,
            legacyMigration = completed,
            inputMessage = when {
                conflictCount > 0 ->
                    "已迁移 ${completed.migrated.size} 条，保留 $conflictCount 条冲突且未覆盖。"
                reviewSaved ->
                    "旧绑定迁移完成，共迁移 ${completed.migrated.size} 条。"
                else -> "迁移已完成，但无法保存一次性预览状态。"
            }
        )
        addEvent(
            title = "旧绑定迁移完成",
            detail = "迁移 ${committed.summary.migratedRecords} / 冲突 $conflictCount",
            warning = conflictCount > 0
        )
    }

    private fun normalizeProductCodeOrShowMessage(value: String): String? {
        return runCatching { PlatformValueRules.normalizeProductCode(value) }
            .getOrElse {
                uiState = uiState.copy(inputMessage = "请先输入有效的产品编码。")
                null
            }
    }

    private fun normalizeTagIdOrShowMessage(value: String): String? {
        val trimmed = value.trim().uppercase()
        val normalized = if (SHORT_TAG_PATTERN.matches(trimmed)) "AD1$trimmed" else trimmed
        return runCatching { PlatformValueRules.requireTagId(normalized) }
            .getOrElse {
                uiState = uiState.copy(
                    inputMessage = "灯条 ID 应为 AD1 加 9 位十六进制字符，或 9 位灯条短码。"
                )
                null
            }
    }

    private fun stationIdOrShowMessage(): String? {
        val stationId = uiState.stationId.ifBlank {
            uiState.selectedStation?.stationId.orEmpty()
        }
        return runCatching { PlatformValueRules.requireStationId(stationId) }
            .getOrElse {
                uiState = uiState.copy(inputMessage = "服务器快照中暂无有效基站 SN。")
                null
            }
    }

    private fun platformColorOrShowMessage():
        com.example.deepchatdemo.platform.model.LightColor? {
        return runCatching { uiState.commandSettings.color.toPlatformColor() }
            .getOrElse {
                uiState = uiState.copy(inputMessage = "请选择服务端支持的亮灯颜色。")
                null
            }
    }

    private fun showWritesClosedMessage() {
        uiState = uiState.copy(
            inputMessage = when {
                uiState.enrollmentStatus == DeviceEnrollmentUiStatus.AUTO_REGISTERING ->
                    "设备正在自动注册，请稍候重试。"
                uiState.enrollmentStatus == DeviceEnrollmentUiStatus.REVOKED ->
                    "设备凭据正在自动刷新，请稍候重试。"
                uiState.backendStatus == PlatformBackendUiStatus.CONTRACT_INCOMPATIBLE ->
                    "后台合同不兼容，写操作已关闭。"
                uiState.backendStatus == PlatformBackendUiStatus.UNAVAILABLE ->
                    PLATFORM_UNAVAILABLE_MESSAGE
                !uiState.connectionEnabled -> "请先连接 HighTac Platform。"
                else -> "后台尚未完成身份验证，写操作已关闭。"
            }
        )
    }

    private fun handleConnectionFailure(error: Exception) {
        val backendStatus = when (error) {
            is PlatformProtocolException -> PlatformBackendUiStatus.CONTRACT_INCOMPATIBLE
            is PlatformTransportException -> PlatformBackendUiStatus.UNAVAILABLE
            is PlatformApiException -> if (error.isUnavailable) {
                PlatformBackendUiStatus.UNAVAILABLE
            } else {
                uiState.backendStatus
            }
            else -> uiState.backendStatus
        }
        uiState = uiState.copy(
            backendStatus = backendStatus,
            connectionEnabled = false,
            endpointVerified = false,
            inputMessage = error.toUserMessage()
        )
        addEvent("平台连接失败", uiState.serverUrl, warning = true)
    }

    private fun addEvent(title: String, detail: String, warning: Boolean = false) {
        val event = LightEvent(title = title, detail = detail, isWarning = warning)
        uiState = uiState.copy(events = (listOf(event) + uiState.events).take(12))
    }

    override fun onCleared() {
        connectionJob?.cancel()
        migrationJob?.cancel()
        runtimeCloser()
        super.onCleared()
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(LightFindingViewModel::class.java)) {
                        val runtime = AndroidPlatformRuntime.create(context)
                        return LightFindingViewModel(
                            configStore = runtime.configStore,
                            repository = runtime.repository,
                            enrollmentManager = runtime.enrollment,
                            legacyBindingRepository = runtime.legacyBindings,
                            legacyMigrationReviews = runtime.legacyMigrationReviews,
                            runtimeCloser = runtime::close
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }
}

private data class PlatformEventPresentation(
    val title: String,
    val detail: String,
    val warning: Boolean = false
)

private fun PlatformEvent.toLightEvent(): PlatformEventPresentation? {
    return when (this) {
        is BindingCreatedEvent -> PlatformEventPresentation(
            title = "绑定快照已更新",
            detail = "${payload.binding.productCode} / ${payload.binding.tagId}"
        )
        is BindingRemovedEvent -> PlatformEventPresentation(
            title = "绑定已移除",
            detail = "${payload.binding.productCode} / ${payload.binding.tagId}"
        )
        is BrokerStatusChangedEvent -> PlatformEventPresentation(
            title = "Broker ${payload.currentStatus.name}",
            detail = payload.endpoint,
            warning = payload.currentStatus != BrokerServiceState.RUNNING
        )
        is StationStatusChangedEvent -> PlatformEventPresentation(
            title = "基站 ${payload.currentStatus.name}",
            detail = payload.stationId,
            warning = payload.currentStatus != StationStatus.ONLINE
        )
        is CommandStatusChangedEvent -> PlatformEventPresentation(
            title = "命令 ${payload.currentStatus.name}",
            detail = "${payload.confirmedCount}/${payload.targetCount} 已确认",
            warning = payload.currentStatus == CommandStatus.FAILED ||
                payload.currentStatus == CommandStatus.UNCONFIRMED
        )
        is DeviceStatusChangedEvent -> PlatformEventPresentation(
            title = "设备 ${payload.currentStatus.name}",
            detail = payload.displayName ?: payload.deviceId.toString(),
            warning = payload.currentStatus == DeviceStatus.REVOKED
        )
        is SystemNoticeEvent -> PlatformEventPresentation(
            title = payload.code,
            detail = payload.message,
            warning = payload.severity !=
                com.example.deepchatdemo.platform.model.NoticeSeverity.INFO
        )
        else -> null
    }
}

private fun EnrollmentClientState.toUiStatus(): DeviceEnrollmentUiStatus {
    return when (this) {
        EnrollmentClientState.NotStarted -> DeviceEnrollmentUiStatus.NOT_STARTED
        EnrollmentClientState.Creating -> DeviceEnrollmentUiStatus.REGISTERING
        is EnrollmentClientState.Pending -> DeviceEnrollmentUiStatus.AUTO_REGISTERING
        EnrollmentClientState.Approved -> DeviceEnrollmentUiStatus.APPROVED
        is EnrollmentClientState.Finished -> when (status) {
            EnrollmentStatus.REJECTED -> DeviceEnrollmentUiStatus.REJECTED
            EnrollmentStatus.EXPIRED -> DeviceEnrollmentUiStatus.EXPIRED
            EnrollmentStatus.APPROVED -> DeviceEnrollmentUiStatus.APPROVED
            EnrollmentStatus.PENDING -> DeviceEnrollmentUiStatus.AUTO_REGISTERING
        }
    }
}

private fun LightColor.toPlatformColor(): com.example.deepchatdemo.platform.model.LightColor {
    return when {
        red && !green && blue -> com.example.deepchatdemo.platform.model.LightColor.PINK
        !red && green && blue -> com.example.deepchatdemo.platform.model.LightColor.CYAN
        red && !green && !blue -> com.example.deepchatdemo.platform.model.LightColor.RED
        !red && green && !blue -> com.example.deepchatdemo.platform.model.LightColor.GREEN
        !red && !green && blue -> com.example.deepchatdemo.platform.model.LightColor.BLUE
        else -> throw IllegalArgumentException("Unsupported platform light color.")
    }
}

private fun PlatformUrlProblem.toUserMessage(): String {
    return when (this) {
        PlatformUrlProblem.EMPTY -> "请填写服务器地址。"
        PlatformUrlProblem.LOOPBACK_HOST,
        PlatformUrlProblem.EMULATOR_ONLY_HOST,
        PlatformUrlProblem.UNSPECIFIED_HOST ->
            "服务器地址不能使用本机、模拟器专用或未指定主机。"
        PlatformUrlProblem.CLEARTEXT_HOST_NOT_LAN ->
            "HTTP 仅支持可信局域网地址；其他服务器请使用 HTTPS。"
        PlatformUrlProblem.UNSUPPORTED_SCHEME ->
            "服务器地址只支持 http:// 或 https://。"
        PlatformUrlProblem.CREDENTIALS_NOT_ALLOWED ->
            "服务器地址不能包含用户名或密码。"
        PlatformUrlProblem.PATH_NOT_ALLOWED,
        PlatformUrlProblem.QUERY_NOT_ALLOWED,
        PlatformUrlProblem.FRAGMENT_NOT_ALLOWED ->
            "服务器地址只能包含协议、主机和端口。"
        PlatformUrlProblem.MALFORMED -> "服务器地址格式无效。"
    }
}

private fun Exception.toUserMessage(): String {
    return when (this) {
        is PlatformWriteClosedException -> when (reason) {
            PlatformWriteClosedReason.API_NOT_VERIFIED ->
                "后台尚未完成验证，写操作已关闭。"
            PlatformWriteClosedReason.API_UNAVAILABLE ->
                PLATFORM_UNAVAILABLE_MESSAGE
            PlatformWriteClosedReason.CONTRACT_INCOMPATIBLE ->
                "后台合同不兼容，写操作已关闭。"
            PlatformWriteClosedReason.DEVICE_NOT_APPROVED ->
                "设备正在自动注册，请稍候重试。"
        }
        is PlatformAuthenticationException -> "设备凭据尚未就绪，正在自动注册。"
        is PlatformTransportException -> "无法连接 HighTac Platform。"
        is PlatformProtocolException -> "后台响应与客户端合同不兼容。"
        is PlatformApiException -> apiError?.message
            ?: "后台请求失败（HTTP $statusCode）。"
        is IllegalArgumentException -> message ?: "请求参数无效。"
        else -> "操作失败，请稍后重试。"
    }
}

private data class MigrationCommitAttempt(
    val serverUrl: String,
    val request: AndroidBindingMigrationCommitRequest,
    val idempotencyKey: IdempotencyKey
)

private val SHORT_TAG_PATTERN = Regex("^[0-9A-F]{9}$")
private const val MIGRATION_PREVIEW_STALE = "MIGRATION_PREVIEW_STALE"
private const val AUTO_REGISTRATION_POLL_MILLIS = 1_000L
private const val PLATFORM_SYNCING_MESSAGE = "正在同步 HighTac Platform 快照。"
private const val PLATFORM_SYNCED_MESSAGE = "HighTac Platform 快照已同步。"
private const val PLATFORM_UNAVAILABLE_MESSAGE = "后台不可用，写操作已关闭。"
