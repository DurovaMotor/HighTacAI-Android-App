package com.example.deepchatdemo.platform.integration

import android.content.Context
import com.example.deepchatdemo.light.domain.LightBinding
import com.example.deepchatdemo.platform.json.PlatformValueRules
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationClassification
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationPreview
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationPreviewRequest
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationRecord
import com.example.deepchatdemo.platform.model.Binding
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class LegacyBindingConflictReason {
    TAG_BOUND_TO_DIFFERENT_PRODUCT,
    STATION_MISMATCH,
    STATION_NOT_FOUND,
    INVALID_LEGACY_RECORD,
    BATCH_LIMIT_EXCEEDED,
    DUPLICATE_LEGACY_TAG,
    SERVER_REJECTED
}

data class LegacyBindingMigrationConflict(
    val legacy: LightBinding,
    val reason: LegacyBindingConflictReason,
    val remoteBinding: Binding? = null,
    val clientRecordKey: String? = null,
    val authoritativeProductCode: String? = remoteBinding?.productCode,
    val authoritativeStationId: String? = remoteBinding?.stationId,
    val serverErrorCode: String? = null
)

data class LegacyBindingMigrationPlan(
    val identical: List<LightBinding>,
    val conflicts: List<LegacyBindingMigrationConflict>,
    val migratable: List<LightBinding>
) {
    val totalLegacyBindings: Int
        get() = identical.size + conflicts.size + migratable.size
}

data class LegacyBindingMigrationPreparedRecord(
    val legacy: LightBinding,
    val request: AndroidBindingMigrationRecord
)

data class LegacyBindingMigrationPreparation(
    val records: List<LegacyBindingMigrationPreparedRecord>,
    val invalidConflicts: List<LegacyBindingMigrationConflict>,
    val totalLegacyBindings: Int
) {
    val previewRequest: AndroidBindingMigrationPreviewRequest
        get() = AndroidBindingMigrationPreviewRequest(records.map { it.request })

    fun legacyFor(clientRecordKey: String): LightBinding? = records
        .firstOrNull { it.request.clientRecordKey == clientRecordKey }
        ?.legacy

    fun clientRecordKeyFor(binding: LightBinding): String? = records
        .firstOrNull { it.legacy == binding }
        ?.request
        ?.clientRecordKey
}

object LegacyBindingMigrationPlanner {
    fun prepare(legacyBindings: Iterable<LightBinding>): LegacyBindingMigrationPreparation {
        val source = legacyBindings.toList()
        val valid = mutableListOf<LegacyBindingMigrationPreparedRecord>()
        val invalid = mutableListOf<LegacyBindingMigrationConflict>()
        val keyOccurrences = mutableMapOf<String, Int>()

        source.forEach { legacy ->
            when (val normalized = normalizeLegacy(legacy)) {
                is NormalizedLegacy.Invalid -> invalid += LegacyBindingMigrationConflict(
                    legacy = normalized.binding,
                    reason = LegacyBindingConflictReason.INVALID_LEGACY_RECORD
                )

                is NormalizedLegacy.Valid -> {
                    val baseMaterial = normalized.binding.clientRecordKeyMaterial(legacy.id)
                    val occurrence = keyOccurrences.getOrDefault(baseMaterial, 0)
                    keyOccurrences[baseMaterial] = occurrence + 1
                    val keyMaterial = if (occurrence == 0) {
                        baseMaterial
                    } else {
                        "$baseMaterial|occurrence=$occurrence"
                    }
                    valid += LegacyBindingMigrationPreparedRecord(
                        legacy = normalized.binding,
                        request = AndroidBindingMigrationRecord(
                            clientRecordKey = deterministicClientRecordKey(keyMaterial),
                            productCode = normalized.binding.normalizedItemCode,
                            productName = normalized.binding.itemName,
                            tagId = normalized.binding.normalizedTagId,
                            stationId = normalized.binding.stationId
                        )
                    )
                }
            }
        }

        if (valid.size > MAX_MIGRATION_RECORDS) {
            invalid += valid.map { prepared ->
                LegacyBindingMigrationConflict(
                    legacy = prepared.legacy,
                    reason = LegacyBindingConflictReason.BATCH_LIMIT_EXCEEDED,
                    clientRecordKey = prepared.request.clientRecordKey
                )
            }
            valid.clear()
        }
        return LegacyBindingMigrationPreparation(valid, invalid, source.size)
    }

    fun awaitingServerPlan(
        preparation: LegacyBindingMigrationPreparation
    ): LegacyBindingMigrationPlan = LegacyBindingMigrationPlan(
        identical = emptyList(),
        conflicts = preparation.invalidConflicts,
        migratable = emptyList()
    )

    fun plan(
        preparation: LegacyBindingMigrationPreparation,
        preview: AndroidBindingMigrationPreview
    ): LegacyBindingMigrationPlan {
        val expectedKeys = preparation.records.map { it.request.clientRecordKey }
        require(expectedKeys == preview.records.map { it.clientRecordKey }) {
            "Migration preview records do not match the prepared legacy batch."
        }
        val byKey = preparation.records.associateBy { it.request.clientRecordKey }
        val identical = mutableListOf<LightBinding>()
        val conflicts = preparation.invalidConflicts.toMutableList()
        val migratable = mutableListOf<LightBinding>()
        preview.records.forEach { record ->
            val legacy = requireNotNull(byKey[record.clientRecordKey]).legacy
            when (record.classification) {
                AndroidBindingMigrationClassification.MIGRATABLE -> migratable += legacy
                AndroidBindingMigrationClassification.IDENTICAL -> identical += legacy
                else -> conflicts += LegacyBindingMigrationConflict(
                    legacy = legacy,
                    reason = record.classification.toConflictReason(),
                    clientRecordKey = record.clientRecordKey,
                    authoritativeProductCode = record.authoritativeProductCode,
                    authoritativeStationId = record.authoritativeStationId
                )
            }
        }
        return LegacyBindingMigrationPlan(identical, conflicts, migratable)
    }

    private fun normalizeLegacy(binding: LightBinding): NormalizedLegacy {
        return runCatching {
            val productCode = PlatformValueRules.normalizeProductCode(binding.itemCode)
            val rawTagId = binding.tagId.trim().uppercase()
            val tagId = if (SHORT_TAG_PATTERN.matches(rawTagId)) "AD1$rawTagId" else rawTagId
            PlatformValueRules.requireTagId(tagId)
            val stationId = PlatformValueRules.requireStationId(
                binding.stationId.trim().uppercase()
            )
            val productName = binding.itemName?.trim()?.takeIf(String::isNotEmpty)
            require(productName == null || productName.length <= 256)
            NormalizedLegacy.Valid(
                binding.copy(
                    itemCode = productCode,
                    itemName = productName,
                    tagId = tagId,
                    stationId = stationId
                )
            )
        }.getOrElse {
            NormalizedLegacy.Invalid(binding)
        }
    }

    private sealed interface NormalizedLegacy {
        data class Valid(val binding: LightBinding) : NormalizedLegacy

        data class Invalid(val binding: LightBinding) : NormalizedLegacy
    }

    private val SHORT_TAG_PATTERN = Regex("^[0-9A-F]{9}$")
    private const val MAX_MIGRATION_RECORDS = 2_000
}

private fun AndroidBindingMigrationClassification.toConflictReason(): LegacyBindingConflictReason {
    return when (this) {
        AndroidBindingMigrationClassification.DUPLICATE_LEGACY_TAG ->
            LegacyBindingConflictReason.DUPLICATE_LEGACY_TAG
        AndroidBindingMigrationClassification.TAG_BOUND_TO_DIFFERENT_PRODUCT ->
            LegacyBindingConflictReason.TAG_BOUND_TO_DIFFERENT_PRODUCT
        AndroidBindingMigrationClassification.STATION_MISMATCH ->
            LegacyBindingConflictReason.STATION_MISMATCH
        AndroidBindingMigrationClassification.STATION_NOT_FOUND ->
            LegacyBindingConflictReason.STATION_NOT_FOUND
        AndroidBindingMigrationClassification.MIGRATABLE,
        AndroidBindingMigrationClassification.IDENTICAL ->
            error("Successful migration classifications are not conflicts.")
    }
}

private fun LightBinding.clientRecordKeyMaterial(stableLocalId: String): String = listOf(
    stableLocalId,
    createdAtMillis.toString(),
    updatedAtMillis.toString(),
    normalizedTagId,
    normalizedItemCode,
    stationId
).joinToString(separator = "|") { value -> "${value.length}:$value" }

private fun deterministicClientRecordKey(material: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(material.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    return "legacy-v1-$digest"
}

interface LegacyBindingMigrationReviewStore {
    fun isReviewed(serverUrl: String): Boolean

    fun markReviewed(serverUrl: String)
}

class SharedPreferencesLegacyBindingMigrationReviewStore(
    context: Context
) : LegacyBindingMigrationReviewStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    override fun isReviewed(serverUrl: String): Boolean {
        return canonical(serverUrl) in preferences
            .getStringSet(KEY_REVIEWED_SERVERS, emptySet())
            .orEmpty()
    }

    override fun markReviewed(serverUrl: String) {
        val next = preferences
            .getStringSet(KEY_REVIEWED_SERVERS, emptySet())
            .orEmpty()
            .toMutableSet()
            .apply { add(canonical(serverUrl)) }
        check(preferences.edit().putStringSet(KEY_REVIEWED_SERVERS, next).commit()) {
            "Unable to persist legacy binding migration review state."
        }
    }

    private fun canonical(serverUrl: String): String = serverUrl.trim().trimEnd('/')

    private companion object {
        const val PREFERENCES_NAME = "hightac_platform_migration"
        const val KEY_REVIEWED_SERVERS = "reviewed_legacy_binding_servers"
    }
}
