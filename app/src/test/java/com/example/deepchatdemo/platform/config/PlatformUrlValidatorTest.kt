package com.example.deepchatdemo.platform.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformUrlValidatorTest {
    @Test
    fun defaultEndpointBuildsRestAndWebSocketUrls() {
        val endpoint = PlatformUrlValidator.requireForWifiProduction(
            PlatformUrlValidator.DEFAULT_SERVER_URL
        )

        assertEquals("http://192.168.1.105:8088", endpoint.canonicalServerUrl)
        assertEquals("http://192.168.1.105:8088/api/v1/", endpoint.apiBaseUrl.toString())
        assertEquals(
            "http://192.168.1.105:8088/api/v1/ws/events",
            endpoint.eventsWebSocketUrl.toString()
        )
    }

    @Test
    fun acceptsPrivateAndLocalCleartextHosts() {
        val urls = listOf(
            "http://10.20.30.40:8088",
            "http://172.16.0.1:8088",
            "http://172.31.255.254:8088",
            "http://192.168.50.25:8088",
            "http://169.254.20.10:8088",
            "http://[fd12:3456::10]:8088",
            "http://[fe80::10]:8088",
            "http://hightac-server:8088",
            "http://hightac-server.local:8088",
            "http://hightac-server.home.arpa:8088"
        )

        urls.forEach { url ->
            assertTrue(
                "$url should be accepted as a LAN cleartext endpoint.",
                PlatformUrlValidator.validateForWifiProduction(url) is PlatformUrlValidation.Valid
            )
        }
    }

    @Test
    fun requiresHttpsForNonLanHosts() {
        val cleartextUrls = listOf(
            "http://example.com:8088",
            "http://8.8.8.8:8088",
            "http://172.15.0.1:8088",
            "http://172.32.0.1:8088",
            "http://192.0.2.10:8088",
            "http://[2001:db8::10]:8088"
        )

        cleartextUrls.forEach { url ->
            assertEquals(
                "$url should require HTTPS.",
                PlatformUrlValidation.Invalid(PlatformUrlProblem.CLEARTEXT_HOST_NOT_LAN),
                PlatformUrlValidator.validateForWifiProduction(url)
            )
        }

        val https = PlatformUrlValidator.validateForWifiProduction(
            "https://platform.example.com:8443/"
        )
        assertTrue(https is PlatformUrlValidation.Valid)
        https as PlatformUrlValidation.Valid
        assertEquals("https", https.endpoint.eventsWebSocketUrl.scheme)
    }

    @Test
    fun rejectsLoopbackAndEmulatorOnlyHosts() {
        val cases = mapOf(
            "http://localhost:8088" to PlatformUrlProblem.LOOPBACK_HOST,
            "http://api.localhost:8088" to PlatformUrlProblem.LOOPBACK_HOST,
            "http://127.0.0.1:8088" to PlatformUrlProblem.LOOPBACK_HOST,
            "http://[::1]:8088" to PlatformUrlProblem.LOOPBACK_HOST,
            "http://[::ffff:127.0.0.1]:8088" to PlatformUrlProblem.LOOPBACK_HOST,
            "http://0.0.0.0:8088" to PlatformUrlProblem.UNSPECIFIED_HOST,
            "http://10.0.2.2:8088" to PlatformUrlProblem.EMULATOR_ONLY_HOST,
            "http://10.0.3.2:8088" to PlatformUrlProblem.EMULATOR_ONLY_HOST,
            "http://host.docker.internal:8088" to PlatformUrlProblem.EMULATOR_ONLY_HOST
        )

        cases.forEach { (url, expectedProblem) ->
            assertEquals(
                PlatformUrlValidation.Invalid(expectedProblem),
                PlatformUrlValidator.validateForWifiProduction(url)
            )
        }
    }

    @Test
    fun rejectsCredentialsPathQueryAndFragment() {
        val cases = mapOf(
            "http://user:pass@192.168.1.105:8088" to PlatformUrlProblem.CREDENTIALS_NOT_ALLOWED,
            "http://192.168.1.105:8088/api/v1" to PlatformUrlProblem.PATH_NOT_ALLOWED,
            "http://192.168.1.105:8088?debug=true" to PlatformUrlProblem.QUERY_NOT_ALLOWED,
            "http://192.168.1.105:8088#fragment" to PlatformUrlProblem.FRAGMENT_NOT_ALLOWED
        )

        cases.forEach { (url, expectedProblem) ->
            assertEquals(
                PlatformUrlValidation.Invalid(expectedProblem),
                PlatformUrlValidator.validateForWifiProduction(url)
            )
        }
    }
}
