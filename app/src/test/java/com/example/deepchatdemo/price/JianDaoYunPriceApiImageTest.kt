package com.example.deepchatdemo.price

import com.example.deepchatdemo.platform.config.PlatformEndpointProvider
import com.example.deepchatdemo.platform.config.PlatformUrlValidator
import com.example.deepchatdemo.platform.network.PlatformImageUrlResolver
import com.example.deepchatdemo.platform.network.PlatformMobileApiResponse
import com.example.deepchatdemo.platform.network.PlatformMobileApiRoute
import com.example.deepchatdemo.platform.network.PlatformMobileApiTransport
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
class JianDaoYunPriceApiImageTest {
    @Test
    fun freshImageLookupUsesExactCodeFilterAndPrefersMatchingRecordId() = runBlocking {
        val transport = imageTransport(
            rows = JSONArray()
                .put(row("row-other", "ABC-001", "/api/v1/mobile/media/token-other"))
                .put(row("row-target", "ABC-001", "/api/v1/mobile/media/token-target"))
        )
        val api = JianDaoYunPriceApi(
            transport = transport,
            imageUrlResolver = imageUrlResolver()
        )

        val imageUrl = api.fetchFreshImageUrl(itemId = "row-target", code = "ABC-001")

        assertEquals(
            "http://192.168.1.105:8088/api/v1/mobile/media/token-target",
            imageUrl
        )
        val request = transport.requests.last { it.first == PlatformMobileApiRoute.JIANDAOYUN_DATA_LIST }.second
        assertEquals(10, request.getInt("limit"))
        assertFalse(request.has("app_id"))
        val fields = request.getJSONArray("fields").stringValues()
        assertTrue(CODE_FIELD in fields)
        assertTrue(IMAGE_FIELD in fields)
        val filter = request.getJSONObject("filter")
        assertEquals("and", filter.getString("rel"))
        val condition = filter.getJSONArray("cond").getJSONObject(0)
        assertEquals(CODE_FIELD, condition.getString("field"))
        assertEquals("eq", condition.getString("method"))
        assertEquals("ABC-001", condition.getJSONArray("value").getString(0))
    }

    @Test
    fun freshImageLookupRejectsAmbiguousExactCodeMatches() = runBlocking {
        val transport = imageTransport(
            rows = JSONArray()
                .put(row("row-a", "ABC-001", "/api/v1/mobile/media/token-a"))
                .put(row("row-b", "ABC-001", "/api/v1/mobile/media/token-b"))
        )
        val api = JianDaoYunPriceApi(
            transport = transport,
            imageUrlResolver = imageUrlResolver()
        )

        assertEquals("", api.fetchFreshImageUrl(itemId = "missing-row", code = "ABC-001"))
    }

    @Test
    fun freshImageLookupValidatesRecordCodeBeforeUsingId() = runBlocking {
        val transport = imageTransport(
            rows = JSONArray()
                .put(row("row-target", "WRONG-001", "/api/v1/mobile/media/wrong-token"))
                .put(row("row-correct", "ABC-001", "/api/v1/mobile/media/correct-token"))
        )
        val api = JianDaoYunPriceApi(
            transport = transport,
            imageUrlResolver = imageUrlResolver()
        )

        assertEquals(
            "http://192.168.1.105:8088/api/v1/mobile/media/correct-token",
            api.fetchFreshImageUrl(itemId = "row-target", code = "ABC-001")
        )
    }

    @Test
    fun freshImageLookupDoesNotClaimUniquenessWhenTheResponseHitsItsLimit() = runBlocking {
        val rows = JSONArray()
            .put(row("row-correct", "ABC-001", "/api/v1/mobile/media/correct-token"))
        repeat(9) { index ->
            rows.put(row("row-other-$index", "OTHER-$index", "/api/v1/mobile/media/other-$index"))
        }
        val api = JianDaoYunPriceApi(
            transport = imageTransport(rows),
            imageUrlResolver = imageUrlResolver()
        )

        assertEquals("", api.fetchFreshImageUrl(itemId = "missing-row", code = "ABC-001"))
    }

    @Test
    fun fullCatalogLoadDoesNotRequestEphemeralImageFields() = runBlocking {
        val transport = imageTransport(rows = JSONArray())
        val api = JianDaoYunPriceApi(
            transport = transport,
            imageUrlResolver = imageUrlResolver()
        )

        api.search(filters = emptyList(), forceRefresh = true)

        val request = transport.requests.last { it.first == PlatformMobileApiRoute.JIANDAOYUN_DATA_LIST }.second
        val fields = request.getJSONArray("fields").stringValues()
        assertTrue(CODE_FIELD in fields)
        assertFalse(IMAGE_FIELD in fields)
        assertFalse(request.has("filter"))
    }

    private fun imageTransport(rows: JSONArray): RecordingPriceTransport {
        return RecordingPriceTransport { route ->
            val body = when (route) {
                PlatformMobileApiRoute.JIANDAOYUN_ENTRY_LIST -> JSONObject()
                    .put(
                        "forms",
                        JSONArray().put(
                            JSONObject()
                                .put("entry_id", "entry-a")
                                .put("name", "产品信息")
                        )
                    )
                PlatformMobileApiRoute.JIANDAOYUN_WIDGET_LIST -> JSONObject()
                    .put(
                        "widgets",
                        JSONArray()
                            .put(widget(CODE_FIELD, "产品编码", "text"))
                            .put(widget(IMAGE_FIELD, "产品图片", "image"))
                    )
                PlatformMobileApiRoute.JIANDAOYUN_DATA_LIST -> JSONObject().put("data", rows)
                PlatformMobileApiRoute.OPENAI_RESPONSES -> error("Unexpected OpenAI route")
            }
            PlatformMobileApiResponse(200, body.toString(), null)
        }
    }

    private fun widget(name: String, label: String, type: String): JSONObject {
        return JSONObject()
            .put("name", name)
            .put("widgetName", name)
            .put("label", label)
            .put("type", type)
    }

    private fun row(id: String, code: String, imageUrl: String): JSONObject {
        return JSONObject()
            .put("_id", id)
            .put(
                "data",
                JSONObject()
                    .put(CODE_FIELD, code)
                    .put(IMAGE_FIELD, imageUrl)
            )
    }

    private fun imageUrlResolver(): PlatformImageUrlResolver {
        val endpoint = PlatformUrlValidator.requireForWifiProduction(
            "http://192.168.1.105:8088"
        )
        return PlatformImageUrlResolver(PlatformEndpointProvider { endpoint })
    }

    private companion object {
        const val CODE_FIELD = "_widget_code"
        const val IMAGE_FIELD = "_widget_image"
    }
}

private class RecordingPriceTransport(
    private val responder: (PlatformMobileApiRoute) -> PlatformMobileApiResponse
) : PlatformMobileApiTransport {
    val requests = mutableListOf<Pair<PlatformMobileApiRoute, JSONObject>>()

    override fun hasApprovedDeviceToken(): Boolean = true

    override fun postJson(
        route: PlatformMobileApiRoute,
        jsonBody: String
    ): PlatformMobileApiResponse {
        requests += route to JSONObject(jsonBody)
        return responder(route)
    }
}

private fun JSONArray.stringValues(): List<String> {
    return buildList {
        for (index in 0 until length()) add(getString(index))
    }
}
