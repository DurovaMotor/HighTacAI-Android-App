package com.example.deepchatdemo.platform.integration

import com.example.deepchatdemo.light.domain.LightBinding
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationClassification
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationPreview
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationPreviewRecord
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationPreviewSummary
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyBindingMigrationPlannerTest {
    @Test
    fun preparesCanonicalRecordsWithStableContentDerivedKeys() {
        val legacy = legacy(
            id = "stable-id",
            productCode = " product-a ",
            tagId = "00000048f",
            stationId = "90a9f1234567"
        )

        val first = LegacyBindingMigrationPlanner.prepare(listOf(legacy)).records.single().request
        val second = LegacyBindingMigrationPlanner.prepare(listOf(legacy)).records.single().request
        val changed = LegacyBindingMigrationPlanner.prepare(
            listOf(legacy.copy(itemCode = "PRODUCT-B"))
        ).records.single().request

        assertEquals("PRODUCT-A", first.productCode)
        assertEquals("AD100000048F", first.tagId)
        assertEquals("90A9F1234567", first.stationId)
        assertEquals(first.clientRecordKey, second.clientRecordKey)
        assertNotEquals(first.clientRecordKey, changed.clientRecordKey)
        assertTrue(first.clientRecordKey.matches(Regex("^legacy-v1-[0-9a-f]{64}$")))
    }

    @Test
    fun keepsSyntaxInvalidRecordsClientSide() {
        val valid = legacy("valid", "PRODUCT-A", "AD1000165FC2")
        val invalid = legacy("invalid", "PRODUCT-B", "not-a-tag")

        val preparation = LegacyBindingMigrationPlanner.prepare(listOf(valid, invalid))

        assertEquals(listOf("valid"), preparation.records.map { it.legacy.id })
        assertEquals(1, preparation.invalidConflicts.size)
        assertEquals(
            LegacyBindingConflictReason.INVALID_LEGACY_RECORD,
            preparation.invalidConflicts.single().reason
        )
        assertEquals("invalid", preparation.invalidConflicts.single().legacy.id)
    }

    @Test
    fun mergesAuthoritativeServerOutcomesIncludingDuplicateCandidates() {
        val duplicateA = legacy("duplicate-a", "PRODUCT-A", "AD1000165FC2")
        val duplicateB = legacy("duplicate-b", "PRODUCT-B", "AD1000165FC2")
        val unique = legacy("unique", "PRODUCT-C", "AD100000048F")
        val preparation = LegacyBindingMigrationPlanner.prepare(
            listOf(duplicateA, duplicateB, unique)
        )
        val classificationByProduct = mapOf(
            "PRODUCT-A" to AndroidBindingMigrationClassification.DUPLICATE_LEGACY_TAG,
            "PRODUCT-B" to AndroidBindingMigrationClassification.DUPLICATE_LEGACY_TAG,
            "PRODUCT-C" to AndroidBindingMigrationClassification.MIGRATABLE
        )

        val plan = LegacyBindingMigrationPlanner.plan(
            preparation,
            preview(preparation, classificationByProduct)
        )

        assertEquals(listOf("unique"), plan.migratable.map { it.id })
        assertEquals(setOf("duplicate-a", "duplicate-b"), plan.conflicts.map { it.legacy.id }.toSet())
        assertTrue(plan.conflicts.all { it.clientRecordKey != null })
        assertTrue(
            plan.conflicts.all {
                it.reason == LegacyBindingConflictReason.DUPLICATE_LEGACY_TAG
            }
        )
    }
}

private fun preview(
    preparation: LegacyBindingMigrationPreparation,
    classifications: Map<String, AndroidBindingMigrationClassification>
): AndroidBindingMigrationPreview {
    val records = preparation.records.map { prepared ->
        val request = prepared.request
        AndroidBindingMigrationPreviewRecord(
            clientRecordKey = request.clientRecordKey,
            productCode = request.productCode,
            productName = request.productName,
            tagId = request.tagId,
            stationId = request.stationId,
            classification = requireNotNull(classifications[request.productCode]),
            authoritativeBindingId = null,
            authoritativeProductCode = null,
            authoritativeStationId = null
        )
    }
    return AndroidBindingMigrationPreview(
        previewToken = "${"a".repeat(32)}.${"b".repeat(43)}",
        summary = AndroidBindingMigrationPreviewSummary(
            totalRecords = records.size,
            migratable = records.count {
                it.classification == AndroidBindingMigrationClassification.MIGRATABLE
            },
            identical = records.count {
                it.classification == AndroidBindingMigrationClassification.IDENTICAL
            },
            duplicateLegacyTag = records.count {
                it.classification == AndroidBindingMigrationClassification.DUPLICATE_LEGACY_TAG
            },
            tagBoundToDifferentProduct = 0,
            stationMismatch = 0,
            stationNotFound = 0
        ),
        records = records
    )
}

private fun legacy(
    id: String,
    productCode: String,
    tagId: String,
    stationId: String = "90A9F1234567"
) = LightBinding(
    id = id,
    itemCode = productCode,
    itemName = productCode.trim(),
    tagId = tagId,
    stationId = stationId,
    createdAtMillis = NOW.toEpochMilli(),
    updatedAtMillis = NOW.toEpochMilli()
)

private val NOW = Instant.parse("2026-07-16T08:15:30Z")
