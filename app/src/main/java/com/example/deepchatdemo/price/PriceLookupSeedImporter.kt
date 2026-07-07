package com.example.deepchatdemo.price

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.deepchatdemo.catalog.PartItem
import com.example.deepchatdemo.config.ApiConfig
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class PriceLookupSeedImporter(
    context: Context
) {
    private val appContext = context.applicationContext

    suspend fun ensureImported(cacheStore: PriceLookupCacheStore): PriceLookupCachedSnapshot =
        withContext(Dispatchers.IO) {
            val entryId = cacheEntryId()
            val existingSnapshot = cacheStore.load(entryId)
            loadBundledCacheSeed(cacheStore, entryId)?.let { bundledSnapshot ->
                if (existingSnapshot.shouldReplaceWith(bundledSnapshot)) {
                    cacheStore.save(bundledSnapshot)
                    Log.d(
                        TAG,
                        "Bundled price cache imported: rows=${bundledSnapshot.fetchedRowCount}, " +
                            "images=${bundledSnapshot.imageUrlCount()}"
                    )
                    return@withContext bundledSnapshot
                }
            }

            existingSnapshot?.let { snapshot ->
                Log.d(
                    TAG,
                    "Price seed cache already available: rows=${snapshot.fetchedRowCount}, " +
                        "images=${snapshot.imageUrlCount()}"
                )
                return@withContext snapshot
            }

            val startedAt = SystemClock.elapsedRealtime()
            val meta = loadMeta()
            val results = loadSeedResults()
            val snapshot = PriceLookupCachedSnapshot(
                entryId = entryId,
                entryName = meta.entryName,
                results = results,
                pageCount = (results.size + PAGE_LIMIT - 1) / PAGE_LIMIT,
                fetchedRowCount = results.size,
                createdAtEpochMs = System.currentTimeMillis()
            )
            cacheStore.save(snapshot)
            Log.d(
                TAG,
                "Price seed imported: rows=${results.size}, source=${meta.source}, " +
                    "generatedAt=${meta.generatedAt}, elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
            )
            snapshot
        }

    private fun loadBundledCacheSeed(
        cacheStore: PriceLookupCacheStore,
        entryId: String
    ): PriceLookupCachedSnapshot? {
        return runCatching {
            appContext.assets.open(FULL_CACHE_ASSET).bufferedReader(Charsets.UTF_8).use { reader ->
                cacheStore.loadSnapshotFromJsonText(reader.readText())
            }?.copy(
                entryId = entryId,
                createdAtEpochMs = System.currentTimeMillis()
            )
        }.getOrNull()
    }

    private fun loadSeedResults(): List<PriceLookupResult> {
        val results = mutableListOf<PriceLookupResult>()
        try {
            appContext.assets.open(PARTS_ASSET).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEachIndexed { index, line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotBlank()) {
                        results.add(PartItem.fromJsonLine(trimmed).toLookupResult(index))
                    }
                }
            }
        } catch (error: FileNotFoundException) {
            throw IOException("Price lookup seed asset is missing: $PARTS_ASSET", error)
        }
        return results
    }

    private fun loadMeta(): SeedMeta {
        return runCatching {
            appContext.assets.open(META_ASSET).bufferedReader(Charsets.UTF_8).use { reader ->
                val json = JSONObject(reader.readText())
                val generatedAt = json.optString("generatedAt").trim()
                val generatedDate = generatedAt.substringBefore('T').takeIf { it.isNotBlank() }
                SeedMeta(
                    source = json.optString("source").trim(),
                    generatedAt = generatedAt,
                    entryName = listOfNotNull("内置配件数据", generatedDate)
                        .joinToString(separator = " · ")
                )
            }
        }.getOrElse {
            SeedMeta(
                source = PARTS_ASSET,
                generatedAt = "",
                entryName = "内置配件数据"
            )
        }
    }

    private fun PartItem.toLookupResult(index: Int): PriceLookupResult {
        val fieldValues = mapOf(
            PriceLookupColumns.CODE to code,
            PriceLookupColumns.NAME_CN to nameCn,
            PriceLookupColumns.NAME_EN to nameEn,
            PriceLookupColumns.BRAND to brand,
            PriceLookupColumns.MODELS to models,
            PriceLookupColumns.SPEC to spec,
            PriceLookupColumns.UNIT to unit,
            PriceLookupColumns.STANDARD_PRICE to standardPrice.toDisplayText(),
            PriceLookupColumns.LATEST_PRICE to latestPrice.toDisplayText(),
            PriceLookupColumns.LATEST_PRICE_DATE to latestPriceDate.orEmpty(),
            PriceLookupColumns.LATEST_PRICE_QTY to latestPriceQty.toDisplayText(),
            PriceLookupColumns.CTN_QTY to ctnQty.toDisplayText(),
            PriceLookupColumns.GROSS_WEIGHT_KG to grossWeightKg.toDisplayText(),
            PriceLookupColumns.LENGTH_CM to lengthCm.toDisplayText(),
            PriceLookupColumns.WIDTH_CM to widthCm.toDisplayText(),
            PriceLookupColumns.HEIGHT_CM to heightCm.toDisplayText(),
            PriceLookupColumns.VOLUME_CBM to volumeCbm.toDisplayText(),
            PriceLookupColumns.REMARK to remark
        )
        val searchValues = fieldValues + mapOf(
            PriceLookupColumns.CODE to "$code $searchText".trim(),
            PriceLookupColumns.NAME_CN to "$nameCn $searchText".trim(),
            PriceLookupColumns.NAME_EN to "$nameEn $searchText".trim(),
            PriceLookupColumns.BRAND to "$brand $searchText".trim(),
            PriceLookupColumns.MODELS to "$models $searchText".trim()
        )

        return PriceLookupResult(
            id = "seed_${index}_${code.ifBlank { "row" }}",
            code = code,
            nameCn = nameCn,
            nameEn = nameEn,
            brand = brand,
            models = models,
            spec = spec,
            unit = unit,
            standardPrice = standardPrice.toDisplayText(),
            latestPrice = latestPrice.toDisplayText(),
            latestPriceDate = latestPriceDate.orEmpty(),
            latestPriceQty = latestPriceQty.toDisplayText(),
            ctnQty = ctnQty.toDisplayText(),
            grossWeightKg = grossWeightKg.toDisplayText(),
            lengthCm = lengthCm.toDisplayText(),
            widthCm = widthCm.toDisplayText(),
            heightCm = heightCm.toDisplayText(),
            volumeCbm = volumeCbm.toDisplayText(),
            remark = remark,
            source = "内置数据",
            fieldValues = fieldValues,
            searchValues = searchValues,
            rawDetails = fieldValues.toRawDetails()
        )
    }

    private fun Map<String, String>.toRawDetails(): List<Pair<String, String>> {
        return PriceLookupColumns.internalColumns.mapNotNull { column ->
            if (column.key == PriceLookupColumns.IMAGE) return@mapNotNull null
            val value = get(column.key).orEmpty()
            if (value.isBlank()) null else column.label to value
        }
    }

    private fun Double?.toDisplayText(): String {
        return this?.catalogNumber().orEmpty()
    }

    private data class SeedMeta(
        val source: String,
        val generatedAt: String,
        val entryName: String
    )

    companion object {
        private const val TAG = "HighTacAI"
        private const val PARTS_ASSET = "parts_catalog.jsonl"
        private const val META_ASSET = "parts_catalog_meta.json"
        private const val FULL_CACHE_ASSET = "price_lookup_cache_seed_v1.json"
        private const val PAGE_LIMIT = 100
        private const val FALLBACK_ENTRY_ID = "parts_catalog_seed_v1"

        fun cacheEntryId(): String {
            return ApiConfig.jiandaoYunEntryId.trim().ifBlank { FALLBACK_ENTRY_ID }
        }
    }
}

private fun PriceLookupCachedSnapshot?.shouldReplaceWith(
    bundledSnapshot: PriceLookupCachedSnapshot
): Boolean {
    if (this == null) return true
    return bundledSnapshot.imageUrlCount() > imageUrlCount()
}

private fun PriceLookupCachedSnapshot.imageUrlCount(): Int {
    return results.count { it.imageUrl.isNotBlank() }
}
