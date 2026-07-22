package com.example.deepchatdemo.cloud

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectCloudTransportsTest {
    @Test
    fun openAiRelayUsesConfiguredHttpsOriginAndBearerWithoutPlatformUrl() {
        val recorder = RecordingInterceptor(body = "{\"output_text\":\"ok\"}")
        val transport = OkHttpOpenAiRelayTransport(
            baseUrl = "https://relay.example/custom/v1",
            apiKey = "test-openai-key",
            client = testClient(recorder)
        )

        val response = transport.postResponses("{\"model\":\"test-model\"}")

        assertEquals(200, response.statusCode)
        val request = recorder.requests.single()
        assertEquals("https://relay.example/custom/v1/responses", request.url.toString())
        assertEquals("Bearer test-openai-key", request.header("Authorization"))
        assertEquals("https://relay.example", request.header("Origin"))
        assertEquals("https://relay.example/custom/v1/", request.header("Referer"))
        assertEquals("{\"model\":\"test-model\"}", request.bodyText())
        assertFalse(request.url.toString().contains("/mobile/"))
        assertFalse(request.url.host.contains("192.168."))
    }

    @Test
    fun jiandaoYunUsesOnlyProviderRoutesAndOverwritesConfiguredIdentifiers() {
        val recorder = RecordingInterceptor(body = "{}")
        val transport = OkHttpJianDaoYunTransport(
            baseUrl = "https://api.jiandaoyun.test/api",
            apiKey = "test-jiandaoyun-key",
            appId = "configured-app",
            entryId = "configured-entry",
            client = testClient(recorder)
        )

        transport.postJson(
            JianDaoYunApiRoute.ENTRY_LIST,
            "{\"app_id\":\"client-app\",\"entry_id\":\"client-entry\"}"
        )
        transport.postJson(JianDaoYunApiRoute.WIDGET_LIST, "{\"entry_id\":\"client-entry\"}")
        transport.postJson(JianDaoYunApiRoute.DATA_LIST, "{\"entry_id\":\"client-entry\"}")

        assertEquals(
            listOf(
                "/api/v5/app/entry/list",
                "/api/v5/app/entry/widget/list",
                "/api/v5/app/entry/data/list"
            ),
            recorder.requests.map { it.url.encodedPath }
        )
        recorder.requests.forEach { request ->
            assertEquals("api.jiandaoyun.test", request.url.host)
            assertEquals("Bearer test-jiandaoyun-key", request.header("Authorization"))
            assertFalse(request.url.toString().contains("/mobile/"))
            assertEquals("configured-app", JSONObject(request.bodyText()).getString("app_id"))
        }
        assertFalse(JSONObject(recorder.requests.first().bodyText()).has("entry_id"))
        recorder.requests.drop(1).forEach { request ->
            assertEquals(
                "configured-entry",
                JSONObject(request.bodyText()).getString("entry_id")
            )
        }
    }

    @Test
    fun missingOrNonHttpsConfigurationFailsWithoutEchoingSecret() {
        val missing = assertThrows(DirectCloudConfigurationException::class.java) {
            OkHttpOpenAiRelayTransport("", "sensitive-test-value", testClient(RecordingInterceptor()))
                .postResponses("{}")
        }
        assertFalse(missing.message.orEmpty().contains("sensitive-test-value"))

        assertThrows(DirectCloudConfigurationException::class.java) {
            OkHttpJianDaoYunTransport(
                baseUrl = "http://api.jiandaoyun.test/api",
                apiKey = "test-key",
                appId = "app",
                entryId = "entry",
                client = testClient(RecordingInterceptor())
            ).postJson(JianDaoYunApiRoute.DATA_LIST, "{}")
        }
    }

    @Test
    fun errorResponsesRemainBounded() {
        val recorder = RecordingInterceptor(
            code = 502,
            body = "x".repeat(64 * 1024 + 1)
        )
        val transport = OkHttpOpenAiRelayTransport(
            "https://relay.example/v1",
            "test-key",
            testClient(recorder)
        )

        val error = assertThrows(DirectCloudResponseTooLargeException::class.java) {
            transport.postResponses("{}")
        }

        assertEquals(64L * 1024L, error.maximumBytes)
    }

    @Test
    fun providerResponsesCannotEchoTheConfiguredSecret() {
        val recorder = RecordingInterceptor(
            code = 401,
            body = "upstream echoed test-sensitive-key"
        )
        val transport = OkHttpOpenAiRelayTransport(
            "https://relay.example/v1",
            "test-sensitive-key",
            testClient(recorder)
        )

        val response = transport.postResponses("{}")

        assertFalse(response.body.contains("test-sensitive-key"))
        assertTrue(response.body.contains("[REDACTED]"))
    }

    @Test
    fun imagePolicyAllowsOnlyTheExactJianDaoYunHttpsMediaOrigin() {
        val policy = JianDaoYunImageUrlPolicy()
        assertEquals(
            "https://files.jiandaoyun.com/path/image.png?token=opaque",
            policy.sanitize("https://files.jiandaoyun.com/path/image.png?token=opaque")
        )
        listOf(
            "http://files.jiandaoyun.com/path/image.png",
            "https://cdn.files.jiandaoyun.com/path/image.png",
            "https://files.jiandaoyun.com.evil.test/path/image.png",
            "https://user@files.jiandaoyun.com/path/image.png",
            "https://files.jiandaoyun.com:444/path/image.png",
            "https://files.jiandaoyun.com/path/image.png#fragment"
        ).forEach { assertTrue(policy.sanitize(it).isEmpty()) }
    }
}

private class RecordingInterceptor(
    private val code: Int = 200,
    private val body: String = "{}"
) : Interceptor {
    val requests = mutableListOf<Request>()

    override fun intercept(chain: Interceptor.Chain): Response {
        requests += chain.request()
        return Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }
}

private fun testClient(interceptor: Interceptor): OkHttpClient = OkHttpClient.Builder()
    .addInterceptor(interceptor)
    .build()

private fun Request.bodyText(): String {
    val buffer = Buffer()
    body?.writeTo(buffer)
    return buffer.readUtf8()
}
