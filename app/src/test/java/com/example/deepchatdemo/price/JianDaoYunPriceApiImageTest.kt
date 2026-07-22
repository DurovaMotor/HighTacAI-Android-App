package com.example.deepchatdemo.price

import com.example.deepchatdemo.cloud.DirectCloudResponse
import com.example.deepchatdemo.cloud.JianDaoYunApiRoute
import com.example.deepchatdemo.cloud.JianDaoYunImageUrlPolicy
import com.example.deepchatdemo.cloud.JianDaoYunTransport
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
                .put(row("row-other", "ABC-001", "https://files.jiandaoyun.com/token-other"))
                .put(row("row-target", "ABC-001", "https://files.jiandaoyun.com/token-target"))
        )
        val api = JianDaoYunPriceApi(
            transport = transport,
            imageUrlPolicy = JianDaoYunImageUrlPolicy()
        )

        val imageUrl = api.fetchFreshImageUrl(itemId = "row-target", code = "ABC-001")

        assertEquals(
            "https://files.jiandaoyun.com/token-target",
            imageUrl
        )
        val request = transport.requests.last { it.first == JianDaoYunApiRoute.DATA_LIST }.second
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
                .put(row("row-a", "ABC-001", "https://files.jiandaoyun.com/token-a"))
                .put(row("row-b", "ABC-001", "https://files.jiandaoyun.com/token-b"))
        )
        val api = JianDaoYunPriceApi(
            transport = transport,
            imageUrlPolicy = JianDaoYunImageUrlPolicy()
        )

        assertEquals("", api.fetchFreshImageUrl(itemId = "missing-row", code = "ABC-001"))
    }

    @Test
    fun freshImageLookupValidatesRecordCodeBeforeUsingId() = runBlocking {
        val transport = imageTransport(
            rows = JSONArray()
                .put(row("row-target", "WRONG-001", "https://files.jiandaoyun.com/wrong-token"))
                .put(row("row-correct", "ABC-001", "https://files.jiandaoyun.com/correct-token"))
        )
        val api = JianDaoYunPriceApi(
            transport = transport,
            imageUrlPolicy = JianDaoYunImageUrlPolicy()
        )

        assertEquals(
            "https://files.jiandaoyun.com/correct-token",
            api.fetchFreshImageUrl(itemId = "row-target", code = "ABC-001")
        )
    }

    @Test
    fun freshImageLookupDoesNotClaimUniquenessWhenTheResponseHitsItsLimit() = runBlocking {
        val rows = JSONArray()
            .put(row("row-correct", "ABC-001", "https://files.jiandaoyun.com/correct-token"))
        repeat(9) { index ->
            rows.put(row("row-other-$index", "OTHER-$index", "https://files.jiandaoyun.com/other-$index"))
        }
        val api = JianDaoYunPriceApi(
            transport = imageTransport(rows),
            imageUrlPolicy = JianDaoYunImageUrlPolicy()
        )

        assertEquals("", api.fetchFreshImageUrl(itemId = "missing-row", code = "ABC-001"))
    }

    @Test
    fun fullCatalogLoadDoesNotRequestEphemeralImageFields() = runBlocking {
        val transport = imageTransport(rows = JSONArray())
        val api = JianDaoYunPriceApi(
            transport = transport,
            imageUrlPolicy = JianDaoYunImageUrlPolicy()
        )

        api.search(filters = emptyList(), forceRefresh = true)

        val request = transport.requests.last { it.first == JianDaoYunApiRoute.DATA_LIST }.second
        val fields = request.getJSONArray("fields").stringValues()
        assertTrue(CODE_FIELD in fields)
        assertFalse(IMAGE_FIELD in fields)
        assertFalse(request.has("filter"))
    }

    private fun imageTransport(rows: JSONArray): RecordingPriceTransport {
        return RecordingPriceTransport { route ->
            val body = when (route) {
                JianDaoYunApiRoute.ENTRY_LIST -> JSONObject()
                    .put(
                        "forms",
                        JSONArray().put(
                            JSONObject()
                                .put("entry_id", "entry-a")
                                .put("name", "产品信息")
                        )
                    )
                JianDaoYunApiRoute.WIDGET_LIST -> JSONObject()
                    .put(
                        "widgets",
                        JSONArray()
                            .put(widget(CODE_FIELD, "产品编码", "text"))
                            .put(widget(IMAGE_FIELD, "产品图片", "image"))
                    )
                JianDaoYunApiRoute.DATA_LIST -> JSONObject().put("data", rows)
            }
            DirectCloudResponse(200, body.toString(), null)
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

    private companion object {
        const val CODE_FIELD = "_widget_code"
        const val IMAGE_FIELD = "_widget_image"
    }
}

private class RecordingPriceTransport(
    private val responder: (JianDaoYunApiRoute) -> DirectCloudResponse
) : JianDaoYunTransport {
    val requests = mutableListOf<Pair<JianDaoYunApiRoute, JSONObject>>()

    override fun postJson(
        route: JianDaoYunApiRoute,
        jsonBody: String
    ): DirectCloudResponse {
        requests += route to JSONObject(jsonBody)
        return responder(route)
    }
}

private fun JSONArray.stringValues(): List<String> {
    return buildList {
        for (index in 0 until length()) add(getString(index))
    }
}
