package com.example.deepchatdemo.platform.security

import com.example.deepchatdemo.platform.config.PlatformEndpointProvider
import com.example.deepchatdemo.platform.config.PlatformUrlValidator
import com.example.deepchatdemo.platform.model.AndroidDeviceMetadata
import com.example.deepchatdemo.platform.model.DeviceEnrollmentCreated
import com.example.deepchatdemo.platform.model.DeviceIdentity
import com.example.deepchatdemo.platform.model.EnrollmentStatus
import com.example.deepchatdemo.platform.model.SensitiveString
import com.example.deepchatdemo.platform.network.HighTacPlatformApi
import com.example.deepchatdemo.platform.network.IdempotencyKey
import com.example.deepchatdemo.platform.network.IdempotencyKeyFactory
import com.example.deepchatdemo.platform.network.UuidSource
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceEnrollmentManagerTest {
    @Test
    fun replayWithoutUsableCredentialsClearsChallengeAndAllowsFreshAutomaticRegistration() =
        runBlocking {
            val firstEnrollmentId = UUID.fromString("10000000-0000-4000-8000-000000000001")
            val secondEnrollmentId = UUID.fromString("10000000-0000-4000-8000-000000000002")
            val thirdEnrollmentId = UUID.fromString("10000000-0000-4000-8000-000000000003")
            val deviceId = UUID.fromString("20000000-0000-4000-8000-000000000001")
            val capturedKeys = mutableListOf<String>()
            var createCalls = 0
            val api = platformApiProxy { method, arguments ->
                check(method.startsWith("createDeviceEnrollment")) {
                    "Unexpected API call: $method"
                }
                capturedKeys += arguments[1].toString()
                createCalls += 1
                DeviceEnrollmentCreated(
                    id = when (createCalls) {
                        1 -> firstEnrollmentId
                        2 -> secondEnrollmentId
                        else -> thirdEnrollmentId
                    },
                    status = if (createCalls == 2) {
                        EnrollmentStatus.EXPIRED
                    } else {
                        EnrollmentStatus.APPROVED
                    },
                    pollSecret = null,
                    expiresAt = Instant.parse("2026-07-22T00:00:00Z"),
                    pollAfterSeconds = null,
                    displayName = "Warehouse phone",
                    deviceId = deviceId.takeUnless { createCalls == 2 },
                    deviceToken = if (createCalls < 3) null else {
                        SensitiveString.fromTransport("t".repeat(48))
                    }
                )
            }
            val credentials = InMemoryCredentialStore()
            val keyFactory = IdempotencyKeyFactory(
                UuidSource {
                    when (credentials.generatedKeyCount++) {
                        0 -> UUID.fromString("30000000-0000-4000-8000-000000000001")
                        1 -> UUID.fromString("30000000-0000-4000-8000-000000000002")
                        else -> UUID.fromString("30000000-0000-4000-8000-000000000003")
                    }
                }
            )
            val manager = DeviceEnrollmentManager(
                api = api,
                identityProvider = DeviceIdentityProvider {
                    DeviceIdentity("a".repeat(64), "b".repeat(64))
                },
                metadataProvider = AndroidDeviceMetadataProvider {
                    AndroidDeviceMetadata("HighTac", "Test Phone", "2.0.0")
                },
                credentialStore = credentials,
                endpointProvider = PlatformEndpointProvider {
                    PlatformUrlValidator.requireForWifiProduction("http://192.168.1.105:8088")
                },
                idempotencyKeyFactory = keyFactory
            )

            try {
                manager.beginEnrollment()
                throw AssertionError("Expected a replay without a token to restart enrollment.")
            } catch (_: EnrollmentTokenUnavailableException) {
                // A lost one-time token must create a new challenge instead of replaying forever.
            }

            assertEquals(EnrollmentClientState.NotStarted, manager.state.value)
            assertNull(credentials.pendingEnrollment())
            assertNull(credentials.currentIdempotencyKey)

            try {
                manager.beginEnrollment()
                throw AssertionError("Expected an expired replay to restart enrollment.")
            } catch (_: EnrollmentRestartRequiredException) {
                // Expired and rejected idempotent replays must also get a fresh challenge.
            }

            assertEquals(
                EnrollmentClientState.Finished(EnrollmentStatus.EXPIRED),
                manager.state.value
            )
            assertNull(credentials.currentIdempotencyKey)

            manager.beginEnrollment()

            assertEquals(EnrollmentClientState.Approved, manager.state.value)
            assertEquals(deviceId, credentials.deviceId())
            assertNotNull(credentials.deviceToken())
            assertEquals(3, capturedKeys.size)
            assertNotEquals(capturedKeys[0], capturedKeys[1])
            assertNotEquals(capturedKeys[1], capturedKeys[2])
        }
}

private class InMemoryCredentialStore : PlatformCredentialStore {
    private var token: SensitiveString? = null
    private var storedDeviceId: UUID? = null
    private var pending: StoredEnrollment? = null
    var currentIdempotencyKey: IdempotencyKey? = null
    var generatedKeyCount: Int = 0

    override fun installationKeyHash(): String = "c".repeat(64)
    override fun deviceId(): UUID? = storedDeviceId
    override fun pendingEnrollment(): StoredEnrollment? = pending
    override fun savePendingEnrollment(id: UUID, pollSecret: SensitiveString) {
        pending = StoredEnrollment(id, pollSecret)
    }
    override fun clearPendingEnrollment() {
        pending = null
    }
    override fun deviceToken(): SensitiveString? = token
    override fun saveDeviceToken(deviceId: UUID, token: SensitiveString) {
        storedDeviceId = deviceId
        this.token = token
        pending = null
        currentIdempotencyKey = null
    }
    override fun clearDeviceToken() {
        storedDeviceId = null
        token = null
    }
    override fun enrollmentIdempotencyKey(factory: IdempotencyKeyFactory): IdempotencyKey =
        currentIdempotencyKey ?: factory.create().also { currentIdempotencyKey = it }
    override fun clearEnrollmentIdempotencyKey() {
        currentIdempotencyKey = null
    }
}

private fun platformApiProxy(
    onCall: (String, Array<out Any?>) -> Any?
): HighTacPlatformApi = Proxy.newProxyInstance(
    HighTacPlatformApi::class.java.classLoader,
    arrayOf(HighTacPlatformApi::class.java)
) { _, method, arguments -> onCall(method.name, arguments.orEmpty()) } as HighTacPlatformApi
