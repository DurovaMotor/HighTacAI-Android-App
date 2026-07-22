package com.example.deepchatdemo.platform.json

import com.example.deepchatdemo.platform.model.AndroidBindingMigrationCommitRequest
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationOutcome
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationPreviewRequest
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationRecord
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidBindingMigrationJsonTest {
    @Test
    fun requestEncodingIsCanonicalAndCommitRepeatsExactOrderedRecords() {
        val records = listOf(
            AndroidBindingMigrationRecord(
                clientRecordKey = "legacy-1",
                productCode = " product-a ",
                productName = "Product A",
                tagId = "00000048f",
                stationId = "90a9f1234567"
            ),
            AndroidBindingMigrationRecord(
                clientRecordKey = "legacy-2",
                productCode = "PRODUCT-B",
                productName = null,
                tagId = "AD1000165FC2",
                stationId = STATION_ID
            )
        )

        val preview = JSONObject(
            PlatformJsonCodec.encodeAndroidBindingMigrationPreview(
                AndroidBindingMigrationPreviewRequest(records)
            )
        )
        val encodedRecords = preview.getJSONArray("records")
        assertEquals(setOf("records"), preview.keys().asSequence().toSet())
        assertEquals("legacy-1", encodedRecords.getJSONObject(0).getString("client_record_key"))
        assertEquals("PRODUCT-A", encodedRecords.getJSONObject(0).getString("product_code"))
        assertEquals("AD100000048F", encodedRecords.getJSONObject(0).getString("tag_id"))
        assertEquals(STATION_ID, encodedRecords.getJSONObject(0).getString("station_id"))

        val commit = JSONObject(
            PlatformJsonCodec.encodeAndroidBindingMigrationCommit(
                AndroidBindingMigrationCommitRequest(
                    records = records,
                    previewToken = PREVIEW_TOKEN,
                    selectedDuplicateKeys = listOf("legacy-2")
                )
            )
        )
        assertEquals(
            setOf("records", "preview_token", "selected_duplicate_keys"),
            commit.keys().asSequence().toSet()
        )
        assertEquals(encodedRecords.toString(), commit.getJSONArray("records").toString())
        assertEquals(PREVIEW_TOKEN, commit.getString("preview_token"))
        assertEquals("legacy-2", commit.getJSONArray("selected_duplicate_keys").getString(0))
    }

    @Test
    fun previewParsingRequiresExactShapeAndSummaryCounts() {
        val parsed = PlatformJsonCodec.parseAndroidBindingMigrationPreview(
            previewJson().toString()
        )

        assertEquals(PREVIEW_TOKEN, parsed.previewToken)
        assertEquals("legacy-1", parsed.records.single().clientRecordKey)
        assertNull(parsed.records.single().authoritativeBindingId)

        val unknown = previewJson().put("unexpected", true)
        assertJsonRejected(unknown)

        val mismatched = previewJson().also {
            it.getJSONObject("summary").put("migratable", 0)
        }
        assertJsonRejected(mismatched)
    }

    @Test
    fun commitParsingChecksOutcomeBindingAndSummaryConsistency() {
        val parsed = PlatformJsonCodec.parseAndroidBindingMigrationCommit(
            commitJson().toString()
        )

        assertEquals(AndroidBindingMigrationOutcome.MIGRATED, parsed.records.single().outcome)
        assertEquals(BINDING_ID, parsed.bindings.single().id)
        assertEquals(1, parsed.summary.createdBindings)

        val invalid = commitJson().also {
            it.getJSONArray("records").getJSONObject(0).put("outcome", "SKIPPED")
        }
        try {
            PlatformJsonCodec.parseAndroidBindingMigrationCommit(invalid.toString())
            throw AssertionError("Expected inconsistent migration outcome to be rejected.")
        } catch (_: PlatformJsonException) {
            assertTrue(true)
        }
    }

    private fun assertJsonRejected(json: JSONObject) {
        try {
            PlatformJsonCodec.parseAndroidBindingMigrationPreview(json.toString())
            throw AssertionError("Expected migration preview JSON to be rejected.")
        } catch (_: PlatformJsonException) {
            assertTrue(true)
        }
    }
}

private fun previewJson() = JSONObject()
    .put("preview_token", PREVIEW_TOKEN)
    .put(
        "summary",
        JSONObject()
            .put("total_records", 1)
            .put("migratable", 1)
            .put("identical", 0)
            .put("duplicate_legacy_tag", 0)
            .put("tag_bound_to_different_product", 0)
            .put("station_mismatch", 0)
            .put("station_not_found", 0)
    )
    .put(
        "records",
        JSONArray().put(
            JSONObject()
                .put("client_record_key", "legacy-1")
                .put("product_code", "PRODUCT-A")
                .put("product_name", "Product A")
                .put("tag_id", "AD100000048F")
                .put("station_id", STATION_ID)
                .put("classification", "MIGRATABLE")
                .put("authoritative_binding_id", JSONObject.NULL)
                .put("authoritative_product_code", JSONObject.NULL)
                .put("authoritative_station_id", JSONObject.NULL)
        )
    )

private fun commitJson() = JSONObject()
    .put("committed_at", "2026-07-16T08:15:30Z")
    .put(
        "summary",
        JSONObject()
            .put("total_records", 1)
            .put("migrated_records", 1)
            .put("identical_records", 0)
            .put("skipped_records", 0)
            .put("created_products", 1)
            .put("created_tags", 1)
            .put("created_bindings", 1)
    )
    .put(
        "records",
        JSONArray().put(
            JSONObject()
                .put("client_record_key", "legacy-1")
                .put("classification", "MIGRATABLE")
                .put("outcome", "MIGRATED")
                .put("binding_id", BINDING_ID.toString())
        )
    )
    .put("bindings", JSONArray().put(bindingJson()))

private fun bindingJson() = JSONObject()
    .put("id", BINDING_ID.toString())
    .put("product_id", "a4087334-ce5d-4afb-9207-ddc3a867bf95")
    .put("product_code", "PRODUCT-A")
    .put("product_name", "Product A")
    .put("tag_id", "AD100000048F")
    .put("site_id", "2b16ae29-f7ab-4c44-9fc6-a0ca693d9c68")
    .put("station_id", STATION_ID)
    .put("source", "MIGRATION")
    .put("actor_type", "ANDROID")
    .put("actor_id", "android-device")
    .put("actor_display_name", "Receiving Phone")
    .put("bound_at", "2026-07-16T08:15:30Z")
    .put("unbound_at", JSONObject.NULL)
    .put("is_active", true)

private const val STATION_ID = "90A9F1234567"
private val BINDING_ID = UUID.fromString("fcf421af-1b4d-4919-8273-7fda9067f6dc")
private val PREVIEW_TOKEN = "${"a".repeat(32)}.${"b".repeat(43)}"
