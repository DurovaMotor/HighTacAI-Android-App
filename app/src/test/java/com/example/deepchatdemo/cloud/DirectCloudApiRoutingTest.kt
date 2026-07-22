package com.example.deepchatdemo.cloud

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
class DirectCloudApiRoutingTest {
    @Test
    fun advisorApisDependOnlyOnTheOpenAiRelayTransport() = runBlocking {
        val planJson = JSONObject()
            .put("intent", "general_chat")
            .put("queryType", "general")
            .put("rawQuery", "hello")
            .put("codes", JSONArray())
            .toString()
        val transport = RecordingOpenAiTransport(
            responses = ArrayDeque(
                listOf(
                    JSONObject().put("output_text", "direct reply").toString(),
                    JSONObject().put("output_text", planJson).toString()
                )
            )
        )

        val reply = OpenAiResponsesApi(transport).sendChat(
            messages = listOf(ChatMessage(1L, ChatRole.USER, "hello"))
        )
        val plan = SearchPlanApi(transport).createSearchPlan("hello")

        assertEquals("direct reply", reply)
        assertEquals("general_chat", plan.intent)
        assertEquals(2, transport.bodies.size)
        assertTrue(transport.bodies.all { !it.contains("Authorization", ignoreCase = true) })
        assertTrue(transport.bodies.all { !it.contains("/mobile/") })
    }

    @Test
    fun priceApiDependsOnlyOnJianDaoYunProviderRoutes() = runBlocking {
        val transport = RecordingJianDaoYunTransport()

        JianDaoYunPriceApi(transport).search(filters = emptyList(), forceRefresh = true)

        assertEquals(
            listOf(
                JianDaoYunApiRoute.ENTRY_LIST,
                JianDaoYunApiRoute.WIDGET_LIST,
                JianDaoYunApiRoute.DATA_LIST
            ),
            transport.routes
        )
        assertFalse(transport.bodies.any { it.contains("Authorization", ignoreCase = true) })
    }
}

private class RecordingOpenAiTransport(
    private val responses: ArrayDeque<String>
) : OpenAiRelayTransport {
    val bodies = mutableListOf<String>()

    override fun postResponses(jsonBody: String): DirectCloudResponse {
        bodies += jsonBody
        return DirectCloudResponse(200, responses.removeFirst(), null)
    }
}

private class RecordingJianDaoYunTransport : JianDaoYunTransport {
    val routes = mutableListOf<JianDaoYunApiRoute>()
    val bodies = mutableListOf<String>()

    override fun postJson(
        route: JianDaoYunApiRoute,
        jsonBody: String
    ): DirectCloudResponse {
        routes += route
        bodies += jsonBody
        val body = when (route) {
            JianDaoYunApiRoute.ENTRY_LIST ->
                "{\"forms\":[{\"entry_id\":\"entry\",\"name\":\"配件\"}]}"
            JianDaoYunApiRoute.WIDGET_LIST -> "{\"widgets\":[]}"
            JianDaoYunApiRoute.DATA_LIST -> "{\"data\":[]}"
        }
        return DirectCloudResponse(200, body, null)
    }
}
