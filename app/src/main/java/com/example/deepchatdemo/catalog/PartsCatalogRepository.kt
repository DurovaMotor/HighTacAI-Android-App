package com.example.deepchatdemo.catalog

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException

class PartsCatalogRepository(
    context: Context
) {
    private val appContext = context.applicationContext

    suspend fun loadParts(): List<PartItem> = withContext(Dispatchers.IO) {
        cachedParts?.let { parts ->
            Log.d(TAG, "Parts catalog cache hit: count=${parts.size}")
            return@withContext parts
        }

        synchronized(cacheLock) {
            cachedParts?.let { parts ->
                Log.d(TAG, "Parts catalog cache hit: count=${parts.size}")
                return@synchronized parts
            }

            val startedAt = SystemClock.elapsedRealtime()
            Log.d(TAG, "Parts catalog load start: asset=$PARTS_ASSET")
            val parsedParts = mutableListOf<PartItem>()
            try {
                appContext.assets.open(PARTS_ASSET).bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        val trimmed = line.trim()
                        if (trimmed.isNotBlank()) {
                            try {
                                parsedParts.add(PartItem.fromJsonLine(trimmed))
                            } catch (error: JSONException) {
                                throw IOException(
                                    "Invalid parts catalog JSON at line ${index + 1}.",
                                    error
                                )
                            }
                        }
                    }
                }
            } catch (error: FileNotFoundException) {
                throw IOException(
                    "Company parts catalog asset is missing. Run tools/convert_parts_excel.py " +
                        "to generate app/src/main/assets/$PARTS_ASSET.",
                    error
                )
            }

            val loadedParts = parsedParts.toList()
            cachedParts = loadedParts
            Log.d(
                TAG,
                "Parts catalog load success: count=${loadedParts.size}, " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
            )
            loadedParts
        }
    }

    private companion object {
        const val TAG = "HighTacAI"
        const val PARTS_ASSET = "parts_catalog.jsonl"

        private val cacheLock = Any()

        @Volatile
        private var cachedParts: List<PartItem>? = null
    }
}
