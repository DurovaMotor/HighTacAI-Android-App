package com.example.deepchatdemo.platform.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DeviceFingerprintTest {
    @Test
    fun fingerprintMatchesGoldenVectorAndPreservesAndroidIdCase() {
        val syntheticAndroidId = "Synthetic-ANDROID-ID-01"
        val signingCertificateDigest =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        val fingerprint = DeviceFingerprint.derive(
            syntheticAndroidId,
            signingCertificateDigest
        )

        assertEquals(
            "4c523a5fe411e48bbfb711cf98e94957ed2985e2b74281d1f2cb5bf9030f2d70",
            fingerprint
        )
        assertNotEquals(
            fingerprint,
            DeviceFingerprint.derive(syntheticAndroidId.lowercase(), signingCertificateDigest)
        )
    }

    @Test
    fun signingCertificateDigestRequiresLowercaseHex() {
        assertThrows(IllegalArgumentException::class.java) {
            DeviceFingerprint.derive(
                "synthetic-installation-id",
                "AB".repeat(32)
            )
        }
    }

    @Test
    fun fingerprintIsDeterministicAndNamespaced() {
        val certificate = "ab".repeat(32)

        val first = DeviceFingerprint.derive("android-installation-id", certificate)
        val second = DeviceFingerprint.derive("android-installation-id", certificate)

        assertEquals(first, second)
        assertEquals(64, first.length)
        assertNotEquals(first, DeviceFingerprint.derive("different-installation", certificate))
        assertNotEquals(first, DeviceFingerprint.derive("android-installation-id", "cd".repeat(32)))
    }
}
