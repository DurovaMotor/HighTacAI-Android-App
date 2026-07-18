package com.example.deepchatdemo.platform.network

import com.example.deepchatdemo.platform.config.PlatformEndpointProvider
import com.example.deepchatdemo.platform.config.PlatformUrlValidator
import java.io.IOException
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformImageUrlResolverTest {
    private val endpoint = PlatformUrlValidator.requireForWifiProduction(
        "http://192.168.1.105:8088"
    )
    private val resolver = PlatformImageUrlResolver(
        PlatformEndpointProvider { endpoint }
    )

    @Test
    fun allowsOnlyAbsoluteMediaUrlsOnTheConfiguredOrigin() {
        assertEquals(
            "http://192.168.1.105:8088/api/v1/mobile/media/signed-token?size=thumb",
            resolver.sanitize(
                "http://192.168.1.105:8088/api/v1/mobile/media/signed-token?size=thumb"
            )
        )

        listOf(
            "https://files.jiandaoyun.com/api/v1/mobile/media/token",
            "http://cdn.example.com/api/v1/mobile/media/token",
            "http://192.168.1.105.evil.example:8088/api/v1/mobile/media/token",
            "http://192.168.1.105:8089/api/v1/mobile/media/token",
            "https://192.168.1.105:8088/api/v1/mobile/media/token",
            "http://192.168.1.105:8088/images/token"
        ).forEach { url ->
            assertNull("Expected URL to be rejected: $url", resolver.resolve(url))
        }
    }

    @Test
    fun rejectsMaliciousHostsCredentialsAndProtocols() {
        listOf(
            "http://192.168.1.105:8088@evil.example/api/v1/mobile/media/token",
            "http://user@192.168.1.105:8088/api/v1/mobile/media/token",
            "//files.jiandaoyun.com/api/v1/mobile/media/token",
            "/\\files.jiandaoyun.com/api/v1/mobile/media/token",
            "file:///api/v1/mobile/media/token",
            "content://media/api/v1/mobile/media/token",
            "data:image/png;base64,AAAA",
            "javascript:alert(1)",
            "ftp://192.168.1.105:8088/api/v1/mobile/media/token",
            "http://192.168.1.105:8088/api/v1/mobile/media/token#fragment"
        ).forEach { url ->
            assertNull("Expected URL to be rejected: $url", resolver.resolve(url))
        }
    }

    @Test
    fun resolvesSafeRelativeMediaUrlsWithoutAllowingAuthorityOrTraversalChanges() {
        listOf(
            "/api/v1/mobile/media/root-relative",
            "api/v1/mobile/media/path-relative",
            "./api/v1/mobile/media/dot-relative"
        ).forEach { url ->
            assertEquals(
                "http://192.168.1.105:8088/${url.removePrefix("/").removePrefix("./")}",
                resolver.sanitize(url)
            )
        }

        listOf(
            "../api/v1/mobile/media/traversal",
            "/other/api/v1/mobile/media/token",
            "//evil.example/api/v1/mobile/media/token",
            "api/v1/mobile/media/",
            "/api/v1/mobile/media//"
        ).forEach { url ->
            assertNull("Expected relative URL to be rejected: $url", resolver.resolve(url))
        }
    }

    @Test
    fun imageClientFailsClosedAndNeverFollowsRedirects() {
        val client = newPlatformImageHttpClient(resolver)

        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        val error = assertThrows(IOException::class.java) {
            client.newCall(
                Request.Builder()
                    .url("https://files.jiandaoyun.com/image.jpg")
                    .build()
            ).execute().use { }
        }
        assertTrue(error.message.orEmpty().contains("Blocked image request"))
    }
}
