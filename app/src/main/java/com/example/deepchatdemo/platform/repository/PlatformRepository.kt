package com.example.deepchatdemo.platform.repository

import com.example.deepchatdemo.platform.model.Binding
import com.example.deepchatdemo.platform.model.BindingCreateRequest
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationCommitRequest
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationCommitResult
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationPreview
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationPreviewRequest
import com.example.deepchatdemo.platform.model.BrokerStatus
import com.example.deepchatdemo.platform.model.LightCommand
import com.example.deepchatdemo.platform.model.LightCommandRequest
import com.example.deepchatdemo.platform.model.PlatformEvent
import com.example.deepchatdemo.platform.model.Product
import com.example.deepchatdemo.platform.model.RebindRequest
import com.example.deepchatdemo.platform.model.RebindResult
import com.example.deepchatdemo.platform.model.Station
import com.example.deepchatdemo.platform.model.Tag
import com.example.deepchatdemo.platform.model.TagRegisterRequest
import com.example.deepchatdemo.platform.network.PlatformEventConnectionState
import com.example.deepchatdemo.platform.network.IdempotencyKey
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class PlatformApiAvailability {
    UNKNOWN,
    CHECKING,
    AVAILABLE,
    UNAVAILABLE,
    CONTRACT_INCOMPATIBLE
}

enum class PlatformDeviceAuthorization {
    UNKNOWN,
    ENROLLMENT_REQUIRED,
    APPROVED,
    REVOKED_OR_INVALID
}

data class PlatformAccessState(
    val apiAvailability: PlatformApiAvailability,
    val deviceAuthorization: PlatformDeviceAuthorization,
    val lastVerifiedAt: Instant? = null
) {
    val isApiReachable: Boolean
        get() = apiAvailability == PlatformApiAvailability.AVAILABLE

    val isDeviceApproved: Boolean
        get() = true

    val canWrite: Boolean
        get() = isApiReachable
}

enum class PlatformWriteClosedReason {
    API_NOT_VERIFIED,
    API_UNAVAILABLE,
    CONTRACT_INCOMPATIBLE,
    DEVICE_NOT_APPROVED
}

class PlatformWriteClosedException(
    val reason: PlatformWriteClosedReason
) : IllegalStateException("Platform write is disabled: $reason")

data class PlatformSnapshot<T>(
    val value: T,
    val synchronizedAt: Instant? = null,
    val isStale: Boolean = true,
    val isRefreshing: Boolean = false
)

interface PlatformRepository {
    val accessState: StateFlow<PlatformAccessState>
    val brokerStatus: StateFlow<PlatformSnapshot<BrokerStatus?>>
    val products: StateFlow<PlatformSnapshot<List<Product>>>
    val bindings: StateFlow<PlatformSnapshot<List<Binding>>>
    val tags: StateFlow<PlatformSnapshot<List<Tag>>>
    val stations: StateFlow<PlatformSnapshot<List<Station>>>
    val commands: StateFlow<Map<UUID, LightCommand>>
    val eventConnectionState: StateFlow<PlatformEventConnectionState>
    val events: SharedFlow<PlatformEvent>

    fun startRealtime()

    fun stopRealtime()

    fun setStationStatusRefreshActive(active: Boolean)

    fun newWriteIdempotencyKey(): IdempotencyKey

    suspend fun refreshAll()

    suspend fun refreshProducts()

    suspend fun refreshBrokerStatus()

    suspend fun refreshBindings()

    suspend fun refreshTags()

    suspend fun refreshStations()

    suspend fun bind(
        request: BindingCreateRequest,
        idempotencyKey: IdempotencyKey? = null
    ): Binding

    suspend fun rebind(
        bindingId: UUID,
        request: RebindRequest,
        idempotencyKey: IdempotencyKey? = null
    ): RebindResult

    suspend fun unbind(bindingId: UUID, idempotencyKey: IdempotencyKey? = null): Binding

    suspend fun previewAndroidBindingMigration(
        request: AndroidBindingMigrationPreviewRequest
    ): AndroidBindingMigrationPreview

    suspend fun commitAndroidBindingMigration(
        request: AndroidBindingMigrationCommitRequest,
        idempotencyKey: IdempotencyKey
    ): AndroidBindingMigrationCommitResult

    suspend fun registerTag(
        request: TagRegisterRequest,
        idempotencyKey: IdempotencyKey? = null
    ): Tag

    suspend fun sendLightCommand(
        request: LightCommandRequest,
        idempotencyKey: IdempotencyKey? = null
    ): LightCommand

    suspend fun allOff(
        stationId: String,
        idempotencyKey: IdempotencyKey? = null
    ): LightCommand

    suspend fun command(commandId: UUID): LightCommand
}
