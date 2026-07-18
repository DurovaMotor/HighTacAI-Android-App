package com.example.deepchatdemo.platform.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.example.deepchatdemo.platform.config.PlatformEndpointProvider
import com.example.deepchatdemo.platform.model.SensitiveString
import com.example.deepchatdemo.platform.network.IdempotencyKey
import com.example.deepchatdemo.platform.network.IdempotencyKeyFactory
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

fun interface PlatformAuthTokenProvider {
    fun deviceToken(): SensitiveString?
}

data class StoredEnrollment(
    val id: UUID,
    val pollSecret: SensitiveString
)

interface PlatformCredentialStore : PlatformAuthTokenProvider {
    fun installationKeyHash(): String

    fun deviceId(): UUID?

    fun pendingEnrollment(): StoredEnrollment?

    fun savePendingEnrollment(id: UUID, pollSecret: SensitiveString)

    fun clearPendingEnrollment()

    fun saveDeviceToken(deviceId: UUID, token: SensitiveString)

    fun clearDeviceToken()

    fun enrollmentIdempotencyKey(factory: IdempotencyKeyFactory): IdempotencyKey

    fun clearEnrollmentIdempotencyKey()
}

class AndroidKeystoreCredentialStore(
    context: Context,
    private val endpointProvider: PlatformEndpointProvider,
    private val secureRandom: SecureRandom = SecureRandom()
) : PlatformCredentialStore {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val lock = Any()
    private val keyAlias = "${applicationContext.packageName}.hightac-platform-secrets.v1"

    override fun installationKeyHash(): String = synchronized(lock) {
        val hadStoredSecret = preferences.contains(KEY_INSTALLATION_SECRET)
        var secret = readProtectedBytes(KEY_INSTALLATION_SECRET)
        try {
            if (secret == null) {
                if (hadStoredSecret) clearProtectedCredentialsLocked()
                secret = ByteArray(INSTALLATION_SECRET_BYTES).also(secureRandom::nextBytes)
                writeProtectedBytes(KEY_INSTALLATION_SECRET, secret)
            }
            DeviceFingerprint.sha256Hex(secret)
        } finally {
            secret?.fill(0)
        }
    }

    override fun pendingEnrollment(): StoredEnrollment? = synchronized(lock) {
        ensureEndpointScopeLocked()
        val idText = preferences.getString(KEY_ENROLLMENT_ID, null) ?: return@synchronized null
        val secret = readProtectedString(KEY_ENROLLMENT_POLL_SECRET)
        if (secret == null) {
            clearPendingEnrollmentLocked()
            return@synchronized null
        }
        val id = runCatching { UUID.fromString(idText) }.getOrNull()
        if (id == null) {
            clearPendingEnrollmentLocked()
            return@synchronized null
        }
        StoredEnrollment(id, SensitiveString.fromTransport(secret))
    }

    override fun deviceId(): UUID? = synchronized(lock) {
        ensureEndpointScopeLocked()
        preferences.getString(KEY_DEVICE_ID, null)?.let { value ->
            runCatching { UUID.fromString(value) }.getOrNull()
        }
    }

    override fun savePendingEnrollment(id: UUID, pollSecret: SensitiveString) = synchronized(lock) {
        prepareEndpointWriteLocked()
        val encodedSecret = pollSecret.use { it.toByteArray(StandardCharsets.UTF_8) }
        try {
            writeProtectedBytes(KEY_ENROLLMENT_POLL_SECRET, encodedSecret)
            check(preferences.edit().putString(KEY_ENROLLMENT_ID, id.toString()).commit()) {
                "Unable to persist enrollment metadata."
            }
        } finally {
            encodedSecret.fill(0)
        }
    }

    override fun clearPendingEnrollment() = synchronized(lock) {
        ensureEndpointScopeLocked()
        clearPendingEnrollmentLocked()
    }

    override fun deviceToken(): SensitiveString? = synchronized(lock) {
        ensureEndpointScopeLocked()
        readProtectedString(KEY_DEVICE_TOKEN)?.let(SensitiveString::fromTransport)
    }

    override fun saveDeviceToken(deviceId: UUID, token: SensitiveString) = synchronized(lock) {
        prepareEndpointWriteLocked()
        val encodedToken = token.use { it.toByteArray(StandardCharsets.UTF_8) }
        try {
            writeProtectedBytes(KEY_DEVICE_TOKEN, encodedToken)
            check(preferences.edit().putString(KEY_DEVICE_ID, deviceId.toString()).commit()) {
                "Unable to persist approved device metadata."
            }
            clearPendingEnrollmentLocked()
            clearEnrollmentIdempotencyKeyLocked()
        } finally {
            encodedToken.fill(0)
        }
    }

    override fun clearDeviceToken() = synchronized(lock) {
        ensureEndpointScopeLocked()
        check(preferences.edit().remove(KEY_DEVICE_TOKEN).remove(KEY_DEVICE_ID).commit()) {
            "Unable to clear the device token."
        }
    }

    override fun enrollmentIdempotencyKey(factory: IdempotencyKeyFactory): IdempotencyKey =
        synchronized(lock) {
            prepareEndpointWriteLocked()
            readProtectedString(KEY_ENROLLMENT_IDEMPOTENCY)?.let { stored ->
                runCatching { IdempotencyKey.parse(stored) }.getOrNull()
            }?.let { return@synchronized it }

            val created = factory.create()
            writeProtectedBytes(
                KEY_ENROLLMENT_IDEMPOTENCY,
                created.value.toByteArray(StandardCharsets.US_ASCII)
            )
            created
        }

    override fun clearEnrollmentIdempotencyKey() = synchronized(lock) {
        ensureEndpointScopeLocked()
        clearEnrollmentIdempotencyKeyLocked()
    }

    private fun ensureEndpointScopeLocked() {
        val currentEndpoint = endpointProvider.currentEndpoint().canonicalServerUrl
        val storedEndpoint = preferences.getString(KEY_CREDENTIAL_ENDPOINT, null)
        if (storedEndpoint == currentEndpoint) return

        val hasEndpointCredentials = ENDPOINT_CREDENTIAL_KEYS.any(preferences::contains)
        if (hasEndpointCredentials || storedEndpoint != null) {
            clearEndpointCredentialsLocked()
        }
    }

    private fun prepareEndpointWriteLocked() {
        ensureEndpointScopeLocked()
        val currentEndpoint = endpointProvider.currentEndpoint().canonicalServerUrl
        if (preferences.getString(KEY_CREDENTIAL_ENDPOINT, null) == currentEndpoint) return
        check(
            preferences.edit()
                .putString(KEY_CREDENTIAL_ENDPOINT, currentEndpoint)
                .commit()
        ) { "Unable to persist the platform credential endpoint." }
    }

    private fun readProtectedString(name: String): String? {
        val bytes = readProtectedBytes(name) ?: return null
        return try {
            String(bytes, StandardCharsets.UTF_8).takeIf { it.isNotBlank() }
        } finally {
            bytes.fill(0)
        }
    }

    private fun readProtectedBytes(name: String): ByteArray? {
        val envelope = preferences.getString(name, null) ?: return null
        return try {
            val parts = envelope.split(':')
            require(parts.size == 3 && parts[0] == ENVELOPE_VERSION)
            val iv = Base64.getDecoder().decode(parts[1])
            val ciphertext = Base64.getDecoder().decode(parts[2])
            require(iv.size == GCM_IV_BYTES)
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(aadFor(name))
            cipher.doFinal(ciphertext)
        } catch (_: Exception) {
            preferences.edit().remove(name).commit()
            null
        }
    }

    private fun writeProtectedBytes(name: String, plaintext: ByteArray) {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(aadFor(name))
        val ciphertext = cipher.doFinal(plaintext)
        val envelope = buildString {
            append(ENVELOPE_VERSION)
            append(':')
            append(Base64.getEncoder().encodeToString(cipher.iv))
            append(':')
            append(Base64.getEncoder().encodeToString(ciphertext))
        }
        check(preferences.edit().putString(name, envelope).commit()) {
            "Unable to persist protected platform credentials."
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return keyGenerator.generateKey()
    }

    private fun aadFor(name: String): ByteArray =
        "${applicationContext.packageName}:$name:$ENVELOPE_VERSION"
            .toByteArray(StandardCharsets.UTF_8)

    private fun clearPendingEnrollmentLocked() {
        check(
            preferences.edit()
                .remove(KEY_ENROLLMENT_ID)
                .remove(KEY_ENROLLMENT_POLL_SECRET)
                .commit()
        ) { "Unable to clear pending enrollment credentials." }
    }

    private fun clearEnrollmentIdempotencyKeyLocked() {
        check(preferences.edit().remove(KEY_ENROLLMENT_IDEMPOTENCY).commit()) {
            "Unable to clear enrollment idempotency metadata."
        }
    }

    private fun clearEndpointCredentialsLocked() {
        check(
            preferences.edit()
                .remove(KEY_CREDENTIAL_ENDPOINT)
                .remove(KEY_ENROLLMENT_ID)
                .remove(KEY_ENROLLMENT_POLL_SECRET)
                .remove(KEY_ENROLLMENT_IDEMPOTENCY)
                .remove(KEY_DEVICE_TOKEN)
                .remove(KEY_DEVICE_ID)
                .commit()
        ) { "Unable to reset endpoint-scoped platform credentials." }
    }

    private fun clearProtectedCredentialsLocked() {
        check(
            preferences.edit()
                .remove(KEY_INSTALLATION_SECRET)
                .remove(KEY_CREDENTIAL_ENDPOINT)
                .remove(KEY_ENROLLMENT_ID)
                .remove(KEY_ENROLLMENT_POLL_SECRET)
                .remove(KEY_ENROLLMENT_IDEMPOTENCY)
                .remove(KEY_DEVICE_TOKEN)
                .remove(KEY_DEVICE_ID)
                .commit()
        ) { "Unable to reset protected platform credentials." }
    }

    private companion object {
        const val PREFERENCES_NAME = "hightac_platform_protected_credentials"
        const val KEY_INSTALLATION_SECRET = "installation_secret"
        const val KEY_CREDENTIAL_ENDPOINT = "credential_endpoint"
        const val KEY_ENROLLMENT_ID = "enrollment_id"
        const val KEY_ENROLLMENT_POLL_SECRET = "enrollment_poll_secret"
        const val KEY_ENROLLMENT_IDEMPOTENCY = "enrollment_idempotency"
        const val KEY_DEVICE_TOKEN = "device_token"
        const val KEY_DEVICE_ID = "device_id"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val ENVELOPE_VERSION = "v1"
        const val GCM_TAG_BITS = 128
        const val GCM_IV_BYTES = 12
        const val INSTALLATION_SECRET_BYTES = 32
        val ENDPOINT_CREDENTIAL_KEYS = listOf(
            KEY_ENROLLMENT_ID,
            KEY_ENROLLMENT_POLL_SECRET,
            KEY_ENROLLMENT_IDEMPOTENCY,
            KEY_DEVICE_TOKEN,
            KEY_DEVICE_ID
        )
    }
}
