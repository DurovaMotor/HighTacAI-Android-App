package com.example.deepchatdemo.price

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PriceLookupRepository(
    private val jiandaoYunPriceApi: JianDaoYunPriceApi = JianDaoYunPriceApi(),
    private val cacheStore: PriceLookupCacheStore? = null,
    private val seedImporter: PriceLookupSeedImporter? = null
) {
    suspend fun prepareLocalCache(): PriceLookupCacheStatus? = withContext(Dispatchers.IO) {
        val store = cacheStore ?: return@withContext null
        val snapshot = seedImporter?.ensureImported(store)
            ?: store.load(PriceLookupSeedImporter.cacheEntryId())
            ?: return@withContext null
        PriceLookupCacheStatus(
            sourceLabel = localSourceLabel(snapshot),
            fetchedRowCount = snapshot.fetchedRowCount,
            pageCount = snapshot.pageCount,
            cacheAgeMs = snapshot.ageMs()
        )
    }

    suspend fun lookup(
        filters: List<PriceFilterCondition>,
        forceRefresh: Boolean = false,
        onProgress: suspend (results: List<PriceLookupResult>, pageCount: Int, fetchedRowCount: Int) -> Unit = { _, _, _ -> }
    ): PriceLookupSearchResult = withContext(Dispatchers.IO) {
        val startedAt = SystemClock.elapsedRealtime()
        Log.d(
            TAG,
            "Price lookup start: filterCount=${filters.size}, forceRefresh=$forceRefresh, " +
                "columns=${filters.joinToString { it.columnKey }}"
        )

        try {
            if (!forceRefresh) {
                loadLocalCacheResult(
                    filters = filters,
                    onProgress = onProgress
                )?.let { result ->
                    Log.d(
                        TAG,
                        "Price lookup local cache hit: rows=${result.fetchedRowCount}, " +
                            "filtered=${result.results.size}, elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
                    )
                    return@withContext result
                }
            }

            val result = jiandaoYunPriceApi.search(
                filters = filters,
                forceRefresh = forceRefresh,
                onProgress = onProgress
            )
            Log.d(
                TAG,
                "Price lookup success: pages=${result.pageCount}, rows=${result.fetchedRowCount}, " +
                    "filtered=${result.results.size}, fromCache=${result.fromCache}, " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
            )
            result
        } catch (error: Throwable) {
            if (forceRefresh && error.canUseRefreshFallback()) {
                val fallbackResult = loadRefreshFallback(
                    filters = filters,
                    onProgress = onProgress,
                    refreshError = error
                )
                if (fallbackResult != null) {
                    Log.w(
                        TAG,
                        "Price lookup live refresh failed; using local cache: " +
                            "type=${error.javaClass.simpleName}, elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
                    )
                    return@withContext fallbackResult
                }
            }

            Log.w(
                TAG,
                "Price lookup failed: type=${error.javaClass.simpleName}, elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
            )
            throw error
        }
    }

    private suspend fun loadLocalCacheResult(
        filters: List<PriceFilterCondition>,
        onProgress: suspend (results: List<PriceLookupResult>, pageCount: Int, fetchedRowCount: Int) -> Unit
    ): PriceLookupSearchResult? {
        val store = cacheStore ?: return null
        val snapshot = seedImporter?.ensureImported(store)
            ?: store.load(PriceLookupSeedImporter.cacheEntryId())
            ?: return null
        val compiledFilters = filters.toCompiledFilters()
        val results = snapshot.results.filter { result -> result.matchesFilters(compiledFilters) }
        onProgress(results, snapshot.pageCount, snapshot.fetchedRowCount)
        return PriceLookupSearchResult(
            results = results,
            sourceLabel = localSourceLabel(snapshot),
            pageCount = snapshot.pageCount,
            fetchedRowCount = snapshot.fetchedRowCount,
            fromCache = true,
            cacheAgeMs = snapshot.ageMs()
        )
    }

    private suspend fun loadRefreshFallback(
        filters: List<PriceFilterCondition>,
        onProgress: suspend (results: List<PriceLookupResult>, pageCount: Int, fetchedRowCount: Int) -> Unit,
        refreshError: Throwable
    ): PriceLookupSearchResult? {
        return runCatching {
            loadLocalCacheResult(
                filters = filters,
                onProgress = onProgress
            )?.takeIf { it.fetchedRowCount > 0 }
                ?.withRefreshFailureLabel()
        }.getOrElse { fallbackError ->
            Log.w(
                TAG,
                "Price lookup refresh fallback unavailable: " +
                    "refreshType=${refreshError.javaClass.simpleName}, fallbackType=${fallbackError.javaClass.simpleName}"
            )
            null
        }
    }

    private fun localSourceLabel(snapshot: PriceLookupCachedSnapshot): String {
        return "本地缓存 · ${snapshot.entryName.ifBlank { "内置配件数据" }}"
    }

    companion object {
        fun fromContext(context: Context): PriceLookupRepository {
            val appContext = context.applicationContext
            val cacheStore = PriceLookupCacheStore(appContext)
            return PriceLookupRepository(
                jiandaoYunPriceApi = JianDaoYunPriceApi(
                    cacheStore = cacheStore
                ),
                cacheStore = cacheStore,
                seedImporter = PriceLookupSeedImporter(appContext)
            )
        }

        const val TAG = "HighTacAI"
    }
}

private fun PriceLookupSearchResult.withRefreshFailureLabel(): PriceLookupSearchResult {
    return copy(sourceLabel = "$sourceLabel · 实时刷新失败")
}

private fun Throwable.canUseRefreshFallback(): Boolean {
    return when (this) {
        is JianDaoYunPriceApi.JianDaoYunConfigException -> false
        is JianDaoYunPriceApi.JianDaoYunHttpException -> isTransient
        is IOException -> true
        else -> false
    }
}

data class PriceLookupCacheStatus(
    val sourceLabel: String,
    val fetchedRowCount: Int,
    val pageCount: Int,
    val cacheAgeMs: Long
)

private data class CompiledPriceFilterCondition(
    val columnKey: String,
    val text: String,
    val compact: String
)

private fun List<PriceFilterCondition>.toCompiledFilters(): List<CompiledPriceFilterCondition> {
    return map { condition ->
        CompiledPriceFilterCondition(
            columnKey = condition.columnKey,
            text = condition.value.normalizeText(),
            compact = condition.value.normalizeCompact()
        )
    }
}

private fun PriceLookupResult.matchesFilters(filters: List<CompiledPriceFilterCondition>): Boolean {
    return filters.all { condition ->
        val value = searchValues[condition.columnKey]
            ?.takeIf { it.isNotBlank() }
            ?: valueForColumn(condition.columnKey)
        value.matchesCondition(condition)
    }
}

private fun String.matchesCondition(condition: CompiledPriceFilterCondition): Boolean {
    if (isBlank() || condition.text.isBlank()) return false
    return when (condition.columnKey) {
        PriceLookupColumns.CODE -> normalizeCompact().contains(condition.compact)
        PriceLookupColumns.MODELS -> PriceModelMatcher.matches(value = this, query = condition.compact)
        PriceLookupColumns.BRAND -> normalizeText().contains(condition.text)
        else -> normalizeText().contains(condition.text)
    }
}

private fun String.normalizeText(): String {
    return java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFKC)
        .lowercase(java.util.Locale.ROOT)
        .trim()
}

private fun String.normalizeCompact(): String {
    return normalizeText().replace(SEPARATOR_REGEX, "")
}

private val SEPARATOR_REGEX = Regex("[\\s\\-_/\\\\.,;:，。；：、()（）\\[\\]{}]+")
