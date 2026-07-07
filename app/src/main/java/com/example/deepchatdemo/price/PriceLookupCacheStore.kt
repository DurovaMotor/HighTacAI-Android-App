package com.example.deepchatdemo.price

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class PriceLookupCacheStore internal constructor(
    private val cacheFile: File
) {
    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, CACHE_FILE_NAME)
    )

    fun load(entryId: String): PriceLookupCachedSnapshot? {
        if (!cacheFile.exists()) return null

        return loadSnapshotFromJsonText(
            text = cacheFile.readText(Charsets.UTF_8),
            expectedEntryId = entryId
        )
    }

    fun loadSnapshotFromJsonText(
        text: String,
        expectedEntryId: String? = null
    ): PriceLookupCachedSnapshot? {
        return runCatching {
            val json = JSONObject(text)
            if (json.optInt("version") != CACHE_VERSION) return@runCatching null
            if (expectedEntryId != null && json.optString("entry_id") != expectedEntryId) {
                return@runCatching null
            }

            PriceLookupCachedSnapshot(
                entryId = json.optString("entry_id"),
                entryName = json.optString("entry_name"),
                results = json.optJSONArray("results").orEmptyJsonArray().toLookupResults(),
                pageCount = json.optInt("page_count"),
                fetchedRowCount = json.optInt("fetched_row_count"),
                createdAtEpochMs = json.optLong("created_at_epoch_ms")
            )
        }.getOrNull()
    }

    fun save(snapshot: PriceLookupCachedSnapshot) {
        cacheFile.parentFile?.mkdirs()
        val tmpFile = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
        tmpFile.writeText(snapshot.toJson().toString(), Charsets.UTF_8)

        if (cacheFile.exists() && !cacheFile.delete()) {
            tmpFile.delete()
            error("无法更新查价缓存文件")
        }
        if (!tmpFile.renameTo(cacheFile)) {
            tmpFile.copyTo(cacheFile, overwrite = true)
            tmpFile.delete()
        }
    }

    fun clear() {
        if (cacheFile.exists()) {
            cacheFile.delete()
        }
    }

    private fun PriceLookupCachedSnapshot.toJson(): JSONObject {
        return JSONObject()
            .put("version", CACHE_VERSION)
            .put("entry_id", entryId)
            .put("entry_name", entryName)
            .put("page_count", pageCount)
            .put("fetched_row_count", fetchedRowCount)
            .put("created_at_epoch_ms", createdAtEpochMs)
            .put("results", JSONArray(results.map { it.toJson() }))
    }

    private fun PriceLookupResult.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("image_url", imageUrl)
            .put("code", code)
            .put("name_cn", nameCn)
            .put("name_en", nameEn)
            .put("brand", brand)
            .put("models", models)
            .put("spec", spec)
            .put("unit", unit)
            .put("standard_price", standardPrice)
            .put("latest_price", latestPrice)
            .put("latest_price_date", latestPriceDate)
            .put("latest_price_qty", latestPriceQty)
            .put("ctn_qty", ctnQty)
            .put("gross_weight_kg", grossWeightKg)
            .put("length_cm", lengthCm)
            .put("width_cm", widthCm)
            .put("height_cm", heightCm)
            .put("volume_cbm", volumeCbm)
            .put("remark", remark)
            .put("source", source)
            .put("field_values", fieldValues.toJsonObject())
            .put("search_values", searchValues.toJsonObject())
            .put("raw_details", rawDetails.toJsonArray())
    }

    private fun JSONArray.toLookupResults(): List<PriceLookupResult> {
        return buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                add(item.toLookupResult())
            }
        }
    }

    private fun JSONObject.toLookupResult(): PriceLookupResult {
        return PriceLookupResult(
            id = optString("id"),
            imageUrl = optString("image_url"),
            code = optString("code"),
            nameCn = optString("name_cn"),
            nameEn = optString("name_en"),
            brand = optString("brand"),
            models = optString("models"),
            spec = optString("spec"),
            unit = optString("unit"),
            standardPrice = optString("standard_price"),
            latestPrice = optString("latest_price"),
            latestPriceDate = optString("latest_price_date"),
            latestPriceQty = optString("latest_price_qty"),
            ctnQty = optString("ctn_qty"),
            grossWeightKg = optString("gross_weight_kg"),
            lengthCm = optString("length_cm"),
            widthCm = optString("width_cm"),
            heightCm = optString("height_cm"),
            volumeCbm = optString("volume_cbm"),
            remark = optString("remark"),
            source = optString("source", "简道云"),
            fieldValues = optJSONObject("field_values").orEmptyJsonObject().toStringMap(),
            searchValues = optJSONObject("search_values").orEmptyJsonObject().toStringMap(),
            rawDetails = optJSONArray("raw_details").orEmptyJsonArray().toRawDetails()
        )
    }

    private fun Map<String, String>.toJsonObject(): JSONObject {
        return JSONObject().also { json ->
            entries.sortedBy { it.key }.forEach { (key, value) ->
                json.put(key, value)
            }
        }
    }

    private fun List<Pair<String, String>>.toJsonArray(): JSONArray {
        return JSONArray(map { (label, value) ->
            JSONObject()
                .put("label", label)
                .put("value", value)
        })
    }

    private fun JSONObject.toStringMap(): Map<String, String> {
        return keys().asSequence()
            .associateWith { key -> optString(key) }
    }

    private fun JSONArray.toRawDetails(): List<Pair<String, String>> {
        return buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                val label = item.optString("label")
                val value = item.optString("value")
                if (label.isNotBlank() && value.isNotBlank()) {
                    add(label to value)
                }
            }
        }
    }

    private fun JSONArray?.orEmptyJsonArray(): JSONArray = this ?: JSONArray()

    private fun JSONObject?.orEmptyJsonObject(): JSONObject = this ?: JSONObject()

    private companion object {
        const val CACHE_FILE_NAME = "price_lookup_cache_v1.json"
        const val CACHE_VERSION = 1
    }
}
