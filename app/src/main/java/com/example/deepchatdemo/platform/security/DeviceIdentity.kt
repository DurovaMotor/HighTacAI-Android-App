package com.example.deepchatdemo.platform.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.example.deepchatdemo.platform.model.AndroidDeviceMetadata
import com.example.deepchatdemo.platform.model.DeviceIdentity
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

fun interface DeviceIdentityProvider {
    fun identity(): DeviceIdentity
}

fun interface AndroidDeviceMetadataProvider {
    fun metadata(): AndroidDeviceMetadata
}

object DeviceFingerprint {
    private const val NAMESPACE = "hightac.android.installation.identity.v1"

    fun derive(androidId: String, signingCertificateDigest: String): String {
        require(androidId.isNotBlank()) { "ANDROID_ID is unavailable." }
        require(SHA256_PATTERN.matches(signingCertificateDigest)) {
            "Signing certificate digest is invalid."
        }
        val input = buildString {
            append(NAMESPACE)
            append('\u0000')
            append(androidId)
            append('\u0000')
            append(signingCertificateDigest)
        }
        return sha256Hex(input.toByteArray(StandardCharsets.UTF_8))
    }

    internal fun sha256Hex(value: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
}

class AndroidDeviceIdentityProvider(
    context: Context
) : DeviceIdentityProvider {
    private val applicationContext = context.applicationContext

    override fun identity(): DeviceIdentity {
        val androidId = Settings.Secure.getString(
            applicationContext.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()
        require(androidId.isNotBlank()) { "ANDROID_ID is unavailable." }

        val signingDigest = currentSigningCertificateDigest()
        return DeviceIdentity(
            fingerprintHash = DeviceFingerprint.derive(androidId, signingDigest),
            signingCertificateDigest = signingDigest
        )
    }

    @Suppress("DEPRECATION")
    private fun currentSigningCertificateDigest(): String {
        val packageInfo = applicationContext.packageManager.getPackageInfo(
            applicationContext.packageName,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }
        )
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            packageInfo.signatures.orEmpty()
        }
        require(signatures.isNotEmpty()) { "Application signing certificate is unavailable." }

        val digests = signatures
            .map { signature -> DeviceFingerprint.sha256Hex(signature.toByteArray()) }
            .sorted()
        return if (digests.size == 1) {
            digests.single()
        } else {
            DeviceFingerprint.sha256Hex(
                digests.joinToString(separator = ":").toByteArray(StandardCharsets.US_ASCII)
            )
        }
    }
}

class AndroidBuildMetadataProvider(
    context: Context
) : AndroidDeviceMetadataProvider {
    private val applicationContext = context.applicationContext

    @Suppress("DEPRECATION")
    override fun metadata(): AndroidDeviceMetadata {
        val packageInfo = applicationContext.packageManager.getPackageInfo(
            applicationContext.packageName,
            0
        )
        return AndroidDeviceMetadata(
            manufacturer = Build.MANUFACTURER.trim().ifEmpty { "Unknown" }.take(128),
            model = Build.MODEL.trim().ifEmpty { "Unknown" }.take(128),
            appVersion = packageInfo.versionName.orEmpty().trim().ifEmpty { "Unknown" }.take(64)
        )
    }
}
