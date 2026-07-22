package com.example.deepchatdemo.platform.network

import com.example.deepchatdemo.platform.config.PlatformEndpoint
import com.example.deepchatdemo.platform.config.PlatformEndpointProvider
import com.example.deepchatdemo.platform.model.SensitiveString
import com.example.deepchatdemo.platform.security.PlatformAuthTokenProvider
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class PlatformEventClientAnonymousTest {
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    @Test
    fun websocketUsesInstallationAuditIdAndNeverSendsLegacyAuthorization() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = Unit
            })
        )
        val installationId = UUID.fromString("40000000-0000-4000-8000-000000000001")
        val endpoint = PlatformEndpoint(
            serverBaseUrl = server.url("/"),
            apiBaseUrl = server.url("/api/v1/"),
            eventsWebSocketUrl = server.url("/api/v1/ws/events")
        )
        val client = OkHttpPlatformEventClient(
            endpointProvider = PlatformEndpointProvider { endpoint },
            tokenProvider = object : PlatformAuthTokenProvider {
                override fun deviceToken(): SensitiveString =
                    SensitiveString.fromTransport("legacy-revoked-token")

                override fun installationId(): UUID = installationId
            },
            scope = scope
        )

        client.connect()

        val request = server.takeRequest(5, TimeUnit.SECONDS)
            ?: throw AssertionError("WebSocket request was not received.")
        assertNull(request.getHeader("Authorization"))
        assertEquals(installationId.toString(), request.getHeader("X-Android-Installation-Id"))
        client.disconnect()
    }
}
