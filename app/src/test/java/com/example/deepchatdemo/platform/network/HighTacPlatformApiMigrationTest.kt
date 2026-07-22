package com.example.deepchatdemo.platform.network

import com.example.deepchatdemo.platform.config.PlatformEndpoint
import com.example.deepchatdemo.platform.config.PlatformEndpointProvider
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationCommitRequest
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationPreviewRequest
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationRecord
import com.example.deepchatdemo.platform.model.SensitiveString
import com.example.deepchatdemo.platform.security.PlatformAuthTokenProvider
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class HighTacPlatformApiMigrationTest {
    private lateinit var server: MockWebServer
    private lateinit var api: OkHttpHighTacPlatformApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val endpoint = PlatformEndpoint(
            serverBaseUrl = server.url("/"),
            apiBaseUrl = server.url("/api/v1/"),
            eventsWebSocketUrl = server.url("/api/v1/ws/events")
        )
        api = OkHttpHighTacPlatformApi(
            endpointProvider = PlatformEndpointProvider { endpoint },
            tokenProvider = PlatformAuthTokenProvider {
                SensitiveString.fromTransport("approved-android-token")
            }
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun migrationUsesAnonymousInstallationIdentityAndProvidedIdempotencyUuid() = runBlocking {
        server.enqueue(jsonResponse(previewResponse()))
        server.enqueue(jsonResponse(commitResponse()))
        val record = AndroidBindingMigrationRecord(
            clientRecordKey = "legacy-1",
            productCode = "PRODUCT-A",
            productName = "Product A",
            tagId = "AD100000048F",
            stationId = STATION_ID
        )

        val preview = api.previewAndroidBindingMigration(
            AndroidBindingMigrationPreviewRequest(listOf(record))
        )
        val previewRequest = server.takeRequest()
        assertEquals("POST", previewRequest.method)
        assertEquals("/api/v1/migrations/android-bindings/preview", previewRequest.path)
        assertNull(previewRequest.getHeader("Authorization"))
        assertEquals(INSTALLATION_ID, previewRequest.getHeader("X-Android-Installation-Id"))
        assertNull(previewRequest.getHeader("Idempotency-Key"))
        assertEquals("legacy-1", previewRequest.bodyJson().getJSONArray("records")
            .getJSONObject(0).getString("client_record_key"))

        val idempotencyKey = IdempotencyKey.parse("2ec5fd45-0e4d-4be4-a6bd-3517118b64df")
        api.commitAndroidBindingMigration(
            request = AndroidBindingMigrationCommitRequest(
                records = listOf(record),
                previewToken = preview.previewToken,
                selectedDuplicateKeys = emptyList()
            ),
            idempotencyKey = idempotencyKey
        )
        val commitRequest = server.takeRequest()
        assertEquals("POST", commitRequest.method)
        assertEquals("/api/v1/migrations/android-bindings/commit", commitRequest.path)
        assertNull(commitRequest.getHeader("Authorization"))
        assertEquals(INSTALLATION_ID, commitRequest.getHeader("X-Android-Installation-Id"))
        assertEquals(idempotencyKey.value, commitRequest.getHeader("Idempotency-Key"))
        val commitBody = commitRequest.bodyJson()
        assertEquals(PREVIEW_TOKEN, commitBody.getString("preview_token"))
        assertEquals(0, commitBody.getJSONArray("selected_duplicate_keys").length())
    }
}

private const val INSTALLATION_ID = "00000000-0000-4000-8000-000000000001"

private fun jsonResponse(body: String) = MockResponse()
    .setResponseCode(200)
    .setHeader("Content-Type", "application/json")
    .setBody(body)

private fun previewResponse() = """
    {
      "preview_token":"$PREVIEW_TOKEN",
      "summary":{
        "total_records":1,
        "migratable":0,
        "identical":1,
        "duplicate_legacy_tag":0,
        "tag_bound_to_different_product":0,
        "station_mismatch":0,
        "station_not_found":0
      },
      "records":[{
        "client_record_key":"legacy-1",
        "product_code":"PRODUCT-A",
        "product_name":"Product A",
        "tag_id":"AD100000048F",
        "station_id":"$STATION_ID",
        "classification":"IDENTICAL",
        "authoritative_binding_id":"$BINDING_ID",
        "authoritative_product_code":"PRODUCT-A",
        "authoritative_station_id":"$STATION_ID"
      }]
    }
""".trimIndent()

private fun commitResponse() = """
    {
      "committed_at":"2026-07-16T08:15:30Z",
      "summary":{
        "total_records":1,
        "migrated_records":0,
        "identical_records":1,
        "skipped_records":0,
        "created_products":0,
        "created_tags":0,
        "created_bindings":0
      },
      "records":[{
        "client_record_key":"legacy-1",
        "classification":"IDENTICAL",
        "outcome":"IDENTICAL",
        "binding_id":"$BINDING_ID"
      }],
      "bindings":[]
    }
""".trimIndent()

private fun okhttp3.mockwebserver.RecordedRequest.bodyJson(): JSONObject =
    JSONObject(body.readUtf8())

private const val STATION_ID = "90A9F1234567"
private const val BINDING_ID = "fcf421af-1b4d-4919-8273-7fda9067f6dc"
private val PREVIEW_TOKEN = "${"a".repeat(32)}.${"b".repeat(43)}"
