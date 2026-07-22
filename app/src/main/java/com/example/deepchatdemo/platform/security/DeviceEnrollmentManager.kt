package com.example.deepchatdemo.platform.security

import com.example.deepchatdemo.platform.config.PlatformEndpointChangedException
import com.example.deepchatdemo.platform.config.PlatformEndpointProvider
import com.example.deepchatdemo.platform.model.DeviceEnrollmentCreated
import com.example.deepchatdemo.platform.model.DeviceEnrollmentRequest
import com.example.deepchatdemo.platform.model.DeviceEnrollmentState
import com.example.deepchatdemo.platform.model.EnrollmentStatus
import com.example.deepchatdemo.platform.model.SensitiveString
import com.example.deepchatdemo.platform.network.HighTacPlatformApi
import com.example.deepchatdemo.platform.network.IdempotencyKeyFactory
import com.example.deepchatdemo.platform.network.PlatformApiException
import com.example.deepchatdemo.platform.network.PlatformProtocolException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface EnrollmentClientState {
    data object NotStarted : EnrollmentClientState

    data object Creating : EnrollmentClientState

    data class Pending(val enrollment: StoredEnrollment) : EnrollmentClientState

    data object Approved : EnrollmentClientState

    data class Finished(val status: EnrollmentStatus) : EnrollmentClientState
}

class PendingEnrollmentExistsException : IllegalStateException(
    "A device enrollment is already pending."
)

class DeviceAlreadyApprovedException : IllegalStateException(
    "This Android installation already has approved device credentials."
)

open class EnrollmentRestartRequiredException(message: String) : IllegalStateException(message)

class EnrollmentTokenUnavailableException : EnrollmentRestartRequiredException(
    "The approved enrollment token was already delivered and must be restarted."
)

interface DeviceEnrollmentController {
    val state: StateFlow<EnrollmentClientState>

    suspend fun beginEnrollment(): DeviceEnrollmentCreated

    suspend fun pollEnrollment(): DeviceEnrollmentState
}

class DeviceEnrollmentManager(
    private val api: HighTacPlatformApi,
    private val identityProvider: DeviceIdentityProvider,
    private val metadataProvider: AndroidDeviceMetadataProvider,
    private val credentialStore: PlatformCredentialStore,
    private val endpointProvider: PlatformEndpointProvider,
    private val idempotencyKeyFactory: IdempotencyKeyFactory = IdempotencyKeyFactory()
) : DeviceEnrollmentController, AutoCloseable {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<EnrollmentClientState>(initialState())
    private val endpointChangeSubscription = endpointProvider.addEndpointChangeListener {
        _state.value = initialState()
    }

    override val state: StateFlow<EnrollmentClientState> = _state.asStateFlow()

    override suspend fun beginEnrollment(): DeviceEnrollmentCreated = mutex.withLock {
        val endpoint = currentEndpoint()
        if (credentialStore.deviceToken() != null) {
            _state.value = EnrollmentClientState.Approved
            throw DeviceAlreadyApprovedException()
        }
        credentialStore.pendingEnrollment()?.let {
            _state.value = EnrollmentClientState.Pending(it)
            throw PendingEnrollmentExistsException()
        }

        _state.value = EnrollmentClientState.Creating
        val identity = identityProvider.identity()
        val metadata = metadataProvider.metadata()
        val created = try {
            api.createDeviceEnrollment(
                request = DeviceEnrollmentRequest(
                    fingerprintHash = identity.fingerprintHash,
                    installationKeyHash = credentialStore.installationKeyHash(),
                    manufacturer = metadata.manufacturer,
                    model = metadata.model,
                    appVersion = metadata.appVersion
                ),
                idempotencyKey = credentialStore.enrollmentIdempotencyKey(idempotencyKeyFactory)
            )
        } catch (error: CancellationException) {
            _state.value = EnrollmentClientState.NotStarted
            throw error
        } catch (error: Exception) {
            _state.value = EnrollmentClientState.NotStarted
            throw error
        }
        ensureEndpointUnchanged(endpoint)
        when (created.status) {
            EnrollmentStatus.PENDING -> {
                val pollSecret = created.pollSecret ?: throw PlatformProtocolException()
                credentialStore.savePendingEnrollment(created.id, pollSecret)
                _state.value = EnrollmentClientState.Pending(
                    StoredEnrollment(created.id, pollSecret)
                )
            }
            EnrollmentStatus.APPROVED -> {
                if (!acceptApprovedResult(
                        deviceId = created.deviceId,
                        deviceToken = created.deviceToken
                    )
                ) {
                    throw EnrollmentTokenUnavailableException()
                }
            }
            EnrollmentStatus.REJECTED,
            EnrollmentStatus.EXPIRED -> {
                credentialStore.clearPendingEnrollment()
                credentialStore.clearEnrollmentIdempotencyKey()
                _state.value = EnrollmentClientState.Finished(created.status)
                throw EnrollmentRestartRequiredException(
                    "The enrollment replay is ${created.status} and must be restarted."
                )
            }
        }
        created
    }

    override suspend fun pollEnrollment(): DeviceEnrollmentState = mutex.withLock {
        val endpoint = currentEndpoint()
        val pending = credentialStore.pendingEnrollment()
            ?: throw IllegalStateException("No device enrollment is pending.")
        _state.value = EnrollmentClientState.Pending(pending)

        val result = try {
            api.getDeviceEnrollment(pending.id, pending.pollSecret)
        } catch (error: PlatformApiException) {
            if (error.statusCode == 401 || error.statusCode == 404 || error.statusCode == 410) {
                credentialStore.clearPendingEnrollment()
                credentialStore.clearEnrollmentIdempotencyKey()
                _state.value = EnrollmentClientState.NotStarted
            }
            throw error
        }
        ensureEndpointUnchanged(endpoint)

        when (result.status) {
            EnrollmentStatus.PENDING -> Unit
            EnrollmentStatus.APPROVED -> {
                if (!acceptApprovedResult(
                        deviceId = result.deviceId,
                        deviceToken = result.deviceToken
                    )
                ) {
                    throw EnrollmentTokenUnavailableException()
                }
            }
            EnrollmentStatus.REJECTED,
            EnrollmentStatus.EXPIRED -> {
                credentialStore.clearPendingEnrollment()
                credentialStore.clearEnrollmentIdempotencyKey()
                _state.value = EnrollmentClientState.Finished(result.status)
                throw EnrollmentRestartRequiredException(
                    "The enrollment is ${result.status} and must be restarted."
                )
            }
        }
        result
    }

    override fun close() {
        endpointChangeSubscription.close()
    }

    private fun acceptApprovedResult(deviceId: UUID?, deviceToken: SensitiveString?): Boolean {
        val deliveredToken = deviceToken
        val deliveredDeviceId = deviceId
        if (deliveredToken != null && deliveredDeviceId != null) {
            credentialStore.saveDeviceToken(deliveredDeviceId, deliveredToken)
        } else if (credentialStore.deviceToken() == null || credentialStore.deviceId() == null) {
            credentialStore.clearPendingEnrollment()
            credentialStore.clearEnrollmentIdempotencyKey()
            _state.value = EnrollmentClientState.NotStarted
            return false
        } else {
            credentialStore.clearPendingEnrollment()
            credentialStore.clearEnrollmentIdempotencyKey()
        }
        _state.value = EnrollmentClientState.Approved
        return true
    }

    private fun initialState(): EnrollmentClientState {
        if (credentialStore.deviceToken() != null) return EnrollmentClientState.Approved
        val pending = credentialStore.pendingEnrollment()
        return if (pending != null) {
            EnrollmentClientState.Pending(pending)
        } else {
            EnrollmentClientState.NotStarted
        }
    }

    private fun currentEndpoint(): String =
        endpointProvider.currentEndpoint().canonicalServerUrl

    private fun ensureEndpointUnchanged(expected: String) {
        if (currentEndpoint() != expected) throw PlatformEndpointChangedException()
    }
}
