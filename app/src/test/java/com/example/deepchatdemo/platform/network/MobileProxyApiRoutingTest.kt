package com.example.deepchatdemo.platform.network

import com.example.deepchatdemo.catalog.SearchPlanApi
import com.example.deepchatdemo.chat.ChatMessage
import com.example.deepchatdemo.chat.ChatRole
import com.example.deepchatdemo.chat.OpenAiResponsesApi
import com.example.deepchatdemo.price.JianDaoYunPriceApi
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MobileProxyApiRoutingTest {
    @Test
    fun openAiResponsesUsesOnlyThePlatformProxyRoute() = runBlocking {
        val transport = RecordingTransport { route ->
            assertEquals(PlatformMobileApiRoute.OPENAI_RESPONSES, route)
            PlatformMobileApiResponse(200, "{\"output_text\":\"proxy reply\"}", null)
        }
        val api = OpenAiResponsesApi(transport)

        val reply = api.sendChat(
            messages = listOf(ChatMessage(1L, ChatRole.USER, "hello"))
        )

        assertEquals("proxy reply", reply)
        assertEquals(listOf(PlatformMobileApiRoute.OPENAI_RESPONSES), transport.routes)
        assertFalse(transport.bodies.single().contains("Authorization", ignoreCase = true))
    }

    @Test
    fun searchPlanUsesTheSameControlledOpenAiProxy() = runBlocking {
        val planJson = JSONObject()
            .put("intent", "general_chat")
            .put("queryType", "general")
            .put("rawQuery", "hello")
            .put("codes", JSONArray())
            .toString()
        val transport = RecordingTransport {
            PlatformMobileApiResponse(
                statusCode = 200,
                body = JSONObject().put("output_text", planJson).toString(),
                retryAfterHeader = null
            )
        }

        val plan = SearchPlanApi(transport).createSearchPlan("hello")

        assertEquals("general_chat", plan.intent)
        assertEquals(listOf(PlatformMobileApiRoute.OPENAI_RESPONSES), transport.routes)
    }

    @Test
    fun jiandaoYunUsesOnlyPlatformProxyRoutes() = runBlocking {
        val transport = RecordingTransport { route ->
            val body = when (route) {
                PlatformMobileApiRoute.JIANDAOYUN_ENTRY_LIST ->
                    "{\"forms\":[{\"entry_id\":\"test-entry\",\"name\":\"配件\"}]}"
                PlatformMobileApiRoute.JIANDAOYUN_WIDGET_LIST -> "{\"widgets\":[]}"
                PlatformMobileApiRoute.JIANDAOYUN_DATA_LIST -> "{\"data\":[]}"
                PlatformMobileApiRoute.OPENAI_RESPONSES -> error("Unexpected AI route")
            }
            PlatformMobileApiResponse(200, body, null)
        }

        JianDaoYunPriceApi(transport).search(filters = emptyList(), forceRefresh = true)

        assertTrue(transport.routes.isNotEmpty())
        assertEquals(PlatformMobileApiRoute.JIANDAOYUN_ENTRY_LIST, transport.routes.first())
        assertTrue(
            transport.routes.all { route ->
                route == PlatformMobileApiRoute.JIANDAOYUN_ENTRY_LIST ||
                    route == PlatformMobileApiRoute.JIANDAOYUN_WIDGET_LIST ||
                    route == PlatformMobileApiRoute.JIANDAOYUN_DATA_LIST
            }
        )
        assertTrue(transport.bodies.all { body -> !JSONObject(body).has("app_id") })
        assertEquals(PlatformMobileApiRoute.JIANDAOYUN_WIDGET_LIST, transport.routes.takeLast(2)[0])
        assertEquals(PlatformMobileApiRoute.JIANDAOYUN_DATA_LIST, transport.routes.takeLast(2)[1])
    }
}

private class RecordingTransport(
    private val responder: (PlatformMobileApiRoute) -> PlatformMobileApiResponse
) : PlatformMobileApiTransport {
    val routes = mutableListOf<PlatformMobileApiRoute>()
    val bodies = mutableListOf<String>()

    override fun hasApprovedDeviceToken(): Boolean = true

    override fun postJson(
        route: PlatformMobileApiRoute,
        jsonBody: String
    ): PlatformMobileApiResponse {
        routes += route
        bodies += jsonBody
        return responder(route)
    }
}
