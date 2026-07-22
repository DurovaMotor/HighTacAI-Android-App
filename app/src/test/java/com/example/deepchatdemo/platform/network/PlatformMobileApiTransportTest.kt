package com.example.deepchatdemo.platform.network

import com.example.deepchatdemo.platform.config.PlatformEndpoint
import com.example.deepchatdemo.platform.config.PlatformEndpointProvider
import com.example.deepchatdemo.platform.model.SensitiveString
import com.example.deepchatdemo.platform.security.PlatformAuthTokenProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlatformMobileApiTransportTest {
    private lateinit var server: MockWebServer
    private lateinit var endpoint: PlatformEndpoint

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        endpoint = PlatformEndpoint(
            serverBaseUrl = server.url("/"),
            apiBaseUrl = server.url("/api/v1/"),
            eventsWebSocketUrl = server.url("/api/v1/ws/events")
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun fixedRoutesUseAnonymousPlatformAccessWithInstallationAuditHeader() {
        val transport = transportWithToken("approved-device-token")
        val expectedPaths = linkedMapOf(
            PlatformMobileApiRoute.OPENAI_RESPONSES to
                "/api/v1/mobile/openai/responses",
            PlatformMobileApiRoute.JIANDAOYUN_ENTRY_LIST to
                "/api/v1/mobile/jiandaoyun/v5/app/entry/list",
            PlatformMobileApiRoute.JIANDAOYUN_WIDGET_LIST to
                "/api/v1/mobile/jiandaoyun/v5/app/entry/widget/list",
            PlatformMobileApiRoute.JIANDAOYUN_DATA_LIST to
                "/api/v1/mobile/jiandaoyun/v5/app/entry/data/list"
        )

        expectedPaths.forEach { (route, expectedPath) ->
            server.enqueue(jsonResponse("{}"))
            transport.postJson(route, "{\"request\":\"platform-only\"}")

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals(expectedPath, request.path)
            assertNull(request.getHeader("Authorization"))
            assertEquals(INSTALLATION_ID, request.getHeader("X-Android-Installation-Id"))
            assertEquals(
                "application/json; charset=utf-8",
                request.getHeader("Content-Type")
            )
            assertEquals("{\"request\":\"platform-only\"}", request.body.readUtf8())
            assertFalse(request.headers.names().any { it.equals("X-Api-Key", ignoreCase = true) })
        }
    }

    @Test
    fun missingDeviceTokenStillUsesEveryMobileFeature() {
        server.enqueue(jsonResponse("{\"ok\":true}"))
        val transport = transportWithToken(null)

        val response = transport.postJson(PlatformMobileApiRoute.OPENAI_RESPONSES, "{}")

        assertTrue(response.isSuccessful)
        val request = server.takeRequest()
        assertNull(request.getHeader("Authorization"))
        assertEquals(INSTALLATION_ID, request.getHeader("X-Android-Installation-Id"))
        assertTrue(transport.hasApprovedDeviceToken())
    }

    @Test
    fun legacyRejectedDeviceTokenIsNeverSent() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))
        val transport = transportWithToken("expired-device-token")

        val response = transport.postJson(PlatformMobileApiRoute.JIANDAOYUN_DATA_LIST, "{}")

        assertEquals(403, response.statusCode)
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun oversizedErrorResponseIsRejectedAtSixtyFourKibibytes() {
        server.enqueue(
            MockResponse()
                .setResponseCode(502)
                .setBody("x".repeat(64 * 1024 + 1))
        )
        val transport = transportWithToken("approved-device-token")

        val error = assertThrows(PlatformMobileResponseTooLargeException::class.java) {
            transport.postJson(PlatformMobileApiRoute.OPENAI_RESPONSES, "{}")
        }

        assertEquals(64L * 1024L, error.maximumBytes)
    }

    @Test
    fun oversizedSuccessfulResponseIsRejectedAtEightMebibytes() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{}")
                .setHeader("Content-Length", 8L * 1024L * 1024L + 1L)
        )
        val transport = transportWithToken("approved-device-token")

        val error = assertThrows(PlatformMobileResponseTooLargeException::class.java) {
            transport.postJson(PlatformMobileApiRoute.JIANDAOYUN_DATA_LIST, "{}")
        }

        assertEquals(8L * 1024L * 1024L, error.maximumBytes)
    }

    @Test
    fun successfulResponsePreservesBodyAndRetryHeader() {
        server.enqueue(jsonResponse("{\"ok\":true}").setHeader("Retry-After", "3"))
        val transport = transportWithToken("approved-device-token")

        val response = transport.postJson(PlatformMobileApiRoute.OPENAI_RESPONSES, "{}")

        assertEquals(200, response.statusCode)
        assertEquals("{\"ok\":true}", response.body)
        assertEquals("3", response.retryAfterHeader)
        assertTrue(response.isSuccessful)
        assertNull(server.takeRequest().getHeader("X-OpenAI-Authorization"))
    }

    private fun transportWithToken(token: String?): OkHttpPlatformMobileApiTransport {
        return OkHttpPlatformMobileApiTransport(
            endpointProvider = PlatformEndpointProvider { endpoint },
            tokenProvider = PlatformAuthTokenProvider {
                token?.let(SensitiveString::fromTransport)
            }
        )
    }
}

private const val INSTALLATION_ID = "00000000-0000-4000-8000-000000000001"

private fun jsonResponse(body: String): MockResponse = MockResponse()
    .setResponseCode(200)
    .setHeader("Content-Type", "application/json")
    .setBody(body)
