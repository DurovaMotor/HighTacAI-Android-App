package com.example.deepchatdemo.price

import android.os.SystemClock
import android.util.Log
import com.example.deepchatdemo.config.ApiConfig
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class JianDaoYunPriceApi(
    private val client: OkHttpClient = defaultClient,
    private val cacheStore: PriceLookupCacheStore? = null
) {
    suspend fun search(
        filters: List<PriceFilterCondition>,
        forceRefresh: Boolean = false,
        onProgress: suspend (results: List<PriceLookupResult>, pageCount: Int, fetchedRowCount: Int) -> Unit = { _, _, _ -> }
    ): PriceLookupSearchResult = withContext(Dispatchers.IO) {
        ensureConfigured()

        val startedAt = SystemClock.elapsedRealtime()
        Log.d(TAG, "JianDaoYun query start: filterCount=${filters.size}, forceRefresh=$forceRefresh")

        val entry = resolveEntry()
        val widgets = loadWidgets(entry.id)
        val fieldMap = widgets.inferFieldMap()
        val compiledFilters = filters.toCompiledFilters()

        val diskSnapshot = cacheStore?.load(entry.id)
        if (!forceRefresh) {
            cachedSnapshot?.takeIf { it.entryId == entry.id && it.isFresh(MEMORY_CACHE_TTL_MS) }?.let { snapshot ->
                return@withContext snapshot.toSearchResult(
                    compiledFilters = compiledFilters,
                    sourceLabel = "简道云本地缓存 · ${snapshot.entryName.ifBlank { entry.name }}",
                    startedAt = startedAt,
                    cacheKind = "memory",
                    onProgress = onProgress
                )
            }

            diskSnapshot?.takeIf { it.isFresh(DISK_CACHE_TTL_MS) }?.let { snapshot ->
                cachedSnapshot = snapshot
                return@withContext snapshot.toSearchResult(
                    compiledFilters = compiledFilters,
                    sourceLabel = "简道云本地缓存 · ${snapshot.entryName.ifBlank { entry.name }}",
                    startedAt = startedAt,
                    cacheKind = "disk",
                    onProgress = onProgress
                )
            }
        }

        val rowsResult = try {
            loadRows(
                entryId = entry.id,
                fieldMap = fieldMap,
                filters = compiledFilters,
                onProgress = onProgress
            )
        } catch (error: Throwable) {
            diskSnapshot?.takeIf { it.results.isNotEmpty() }?.let { snapshot ->
                Log.w(TAG, "JianDaoYun live refresh failed, falling back to stale cache: ${error.javaClass.simpleName}")
                return@withContext snapshot.toSearchResult(
                    compiledFilters = compiledFilters,
                    sourceLabel = "简道云本地缓存 · ${snapshot.entryName.ifBlank { entry.name }}",
                    startedAt = startedAt,
                    cacheKind = "stale-disk",
                    onProgress = onProgress
                )
            }
            throw error
        }
        val results = rowsResult.matchedResults
        val snapshot = PriceLookupCachedSnapshot(
            entryId = entry.id,
            entryName = entry.name,
            results = rowsResult.results,
            pageCount = rowsResult.pageCount,
            fetchedRowCount = rowsResult.fetchedRowCount,
            createdAtEpochMs = System.currentTimeMillis()
        )
        cachedSnapshot = snapshot
        runCatching { cacheStore?.save(snapshot) }
            .onFailure { error -> Log.w(TAG, "JianDaoYun cache save failed: ${error.javaClass.simpleName}") }

        Log.d(
            TAG,
            "JianDaoYun query complete: pages=${rowsResult.pageCount}, rows=${rowsResult.fetchedRowCount}, " +
                "filtered=${results.size}, " +
                "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
        )

        PriceLookupSearchResult(
            results = results,
            sourceLabel = "简道云实时数据 · ${entry.name}",
            pageCount = rowsResult.pageCount,
            fetchedRowCount = rowsResult.fetchedRowCount,
            fromCache = false,
            cacheAgeMs = null
        )
    }

    private fun ensureConfigured() {
        val missing = buildList {
            if (ApiConfig.jiandaoYunApiKey.isBlank()) add("JIANDAOYUN_API_KEY")
            if (ApiConfig.jiandaoYunAppId.isBlank()) add("JIANDAOYUN_APP_ID")
        }
        if (missing.isNotEmpty()) {
            throw JianDaoYunConfigException("缺少简道云配置：${missing.joinToString()}")
        }
    }

    private fun resolveEntry(): JianDaoYunEntry {
        ApiConfig.jiandaoYunEntryId.trim().takeIf { it.isNotBlank() }?.let { entryId ->
            return JianDaoYunEntry(id = entryId, name = "指定表单")
        }

        cachedEntry?.let { return it }

        val requestJson = JSONObject()
            .put("app_id", ApiConfig.jiandaoYunAppId)
            .put("limit", 100)
            .put("skip", 0)
        val responseJson = postJson("/v5/app/entry/list", requestJson)
        val formsJson = responseJson.optJSONArray("forms") ?: JSONArray()
        val entries = buildList {
            for (index in 0 until formsJson.length()) {
                val item = formsJson.optJSONObject(index) ?: continue
                val entryId = item.optString("entry_id").trim()
                if (entryId.isNotBlank()) {
                    add(
                        JianDaoYunEntry(
                            id = entryId,
                            name = item.optString("name").trim().ifBlank { "未命名表单" }
                        )
                    )
                }
            }
        }

        val selected = entries.maxByOrNull { it.selectionScore() }
            ?: throw IOException("简道云应用下没有可用表单。")
        cachedEntry = selected
        Log.d(TAG, "JianDaoYun auto entry selected: name=${selected.name}, id=${selected.id}")
        return selected
    }

    private fun loadWidgets(entryId: String): List<JianDaoYunWidget> {
        cachedWidgets?.takeIf { cachedWidgetEntryId == entryId }?.let { return it }

        val requestJson = JSONObject()
            .put("app_id", ApiConfig.jiandaoYunAppId)
            .put("entry_id", entryId)
        val responseJson = postJson("/v5/app/entry/widget/list", requestJson)
        val widgetsJson = responseJson.optJSONArray("widgets") ?: JSONArray()
        val widgets = buildList {
            for (index in 0 until widgetsJson.length()) {
                val item = widgetsJson.optJSONObject(index) ?: continue
                add(
                    JianDaoYunWidget(
                        name = item.optString("name").trim(),
                        widgetName = item.optString("widgetName").trim(),
                        label = item.optString("label").trim(),
                        type = item.optString("type").trim()
                    )
                )
            }
        }
        cachedWidgetEntryId = entryId
        cachedWidgets = widgets
        Log.d(TAG, "JianDaoYun widgets loaded: count=${widgets.size}")
        return widgets
    }

    private suspend fun loadRows(
        entryId: String,
        fieldMap: JianDaoYunFieldMap,
        filters: List<CompiledPriceFilterCondition>,
        onProgress: suspend (results: List<PriceLookupResult>, pageCount: Int, fetchedRowCount: Int) -> Unit
    ): JianDaoYunRowsResult {
        val results = mutableListOf<PriceLookupResult>()
        val matchedResults = mutableListOf<PriceLookupResult>()
        var dataId = ""
        var pageCount = 0
        val seenCursors = mutableSetOf<String>()
        val requestFields = fieldMap.selectedWidgets
            .flatMap { widget -> widget.apiKeys.take(1) }
            .distinct()

        while (true) {
            val requestJson = JSONObject()
                .put("app_id", ApiConfig.jiandaoYunAppId)
                .put("entry_id", entryId)
                .put("limit", PAGE_LIMIT)

            if (dataId.isNotBlank()) {
                requestJson.put("data_id", dataId)
            }
            if (requestFields.isNotEmpty()) {
                requestJson.put("fields", JSONArray(requestFields))
            }

            val responseJson = postJson("/v5/app/entry/data/list", requestJson)
            val data = responseJson.optJSONArray("data")
                ?: responseJson.optJSONArray("data_list")
                ?: JSONArray()
            if (data.length() == 0) break

            pageCount += 1
            for (index in 0 until data.length()) {
                val record = data.optJSONObject(index) ?: continue
                val recordId = record.optString("_id")
                    .ifBlank { record.optString("data_id") }
                    .ifBlank { "${results.size}" }
                val values = record.optJSONObject("data") ?: record
                val result = JianDaoYunRow(id = recordId, data = values).toLookupResult(fieldMap)
                results.add(result)
                if (result.matchesFilters(filters)) {
                    matchedResults.add(result)
                }
            }

            Log.d(
                TAG,
                "JianDaoYun page loaded: page=$pageCount, pageRows=${data.length()}, " +
                    "totalRows=${results.size}, filtered=${matchedResults.size}"
            )
            onProgress(matchedResults.toList(), pageCount, results.size)

            val lastRecord = data.optJSONObject(data.length() - 1)
            val nextDataId = lastRecord?.optString("_id")
                ?.ifBlank { lastRecord.optString("data_id") }
                .orEmpty()

            if (data.length() < PAGE_LIMIT || nextDataId.isBlank()) break
            if (!seenCursors.add(nextDataId)) {
                Log.w(TAG, "JianDaoYun pagination cursor repeated: page=$pageCount")
                break
            }
            dataId = nextDataId
        }

        return JianDaoYunRowsResult(
            results = results,
            matchedResults = matchedResults,
            pageCount = pageCount,
            fetchedRowCount = results.size
        )
    }

    private fun postJson(path: String, json: JSONObject): JSONObject {
        var lastError: IOException? = null
        for (attempt in 1..MAX_REQUEST_ATTEMPTS) {
            try {
                return executePostJson(path, json)
            } catch (error: IOException) {
                lastError = error
                if (!error.isTransientRequestError() || attempt == MAX_REQUEST_ATTEMPTS) {
                    throw error
                }

                val delayMs = error.retryAfterDelayMs() ?: retryDelayMs(attempt)
                Log.w(
                    TAG,
                    "JianDaoYun request failed; retrying: path=$path, attempt=$attempt, " +
                        "delayMs=$delayMs, type=${error.javaClass.simpleName}"
                )
                sleepBeforeRetry(delayMs)
            }
        }

        throw lastError ?: IOException("简道云请求失败。")
    }

    private fun executePostJson(path: String, json: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(endpoint(path))
            .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Authorization", "Bearer ${ApiConfig.jiandaoYunApiKey.trim()}")
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val errorJson = runCatching { JSONObject(body) }.getOrNull()
                val message = errorJson?.optString("msg")
                    ?.takeIf { it.isNotBlank() }
                    ?: body.take(160)
                throw JianDaoYunHttpException(
                    statusCode = response.code,
                    apiMessage = message,
                    retryAfterHeader = response.header("Retry-After")
                )
            }
            return runCatching {
                JSONObject(body.ifBlank { "{}" })
            }.getOrElse { error ->
                throw IOException("简道云接口返回不是有效 JSON。", error)
            }
        }
    }

    private fun IOException.isTransientRequestError(): Boolean {
        return when (this) {
            is JianDaoYunHttpException -> isTransient
            else -> true
        }
    }

    private fun IOException.retryAfterDelayMs(): Long? {
        val retryAfter = (this as? JianDaoYunHttpException)
            ?.retryAfterHeader
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return retryAfter.toLongOrNull()
            ?.times(1000L)
            ?.coerceIn(MIN_RETRY_DELAY_MS, MAX_RETRY_DELAY_MS)
    }

    private fun retryDelayMs(attempt: Int): Long {
        return (MIN_RETRY_DELAY_MS * (1L shl (attempt - 1)))
            .coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    private fun sleepBeforeRetry(delayMs: Long) {
        try {
            Thread.sleep(delayMs)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("简道云请求重试被中断。", error)
        }
    }

    private fun endpoint(path: String): String {
        val base = ApiConfig.JIANDAOYUN_BASE_URL.trim().trimEnd('/')
        val apiBase = if (base.endsWith("/api", ignoreCase = true)) {
            base
        } else {
            "$base/api"
        }
        return "$apiBase$path"
    }

    private fun List<JianDaoYunWidget>.inferFieldMap(): JianDaoYunFieldMap {
        val candidateWidgetsByKey = PriceLookupColumns.internalColumns.mapNotNull { column ->
            val candidates = candidateFieldsFor(column)
            if (candidates.isEmpty()) null else column.key to candidates
        }.toMap()
        val widgetsByKey = PriceLookupColumns.internalColumns.associate { column ->
            val candidates = candidateWidgetsByKey[column.key].orEmpty()
            column.key to if (candidates.isNotEmpty()) {
                candidates.first()
            } else {
                bestField(
                    keywords = column.possibleFieldNames,
                    preferredTypes = column.preferredTypes(),
                    excludeKeywords = column.excludeKeywords()
                )
            }
        }
        val fieldMap = JianDaoYunFieldMap(
            widgetsByColumnKey = widgetsByKey,
            candidateWidgetsByColumnKey = candidateWidgetsByKey
        )
        Log.d(TAG, "JianDaoYun field map: ${fieldMap.summary}")
        candidateWidgetsByKey.forEach { (columnKey, widgets) ->
            Log.d(TAG, "JianDaoYun candidate fields: $columnKey=${widgets.joinToString { it.displayName }.take(160)}")
        }
        return fieldMap
    }

    private fun List<JianDaoYunWidget>.candidateFieldsFor(column: PriceFilterColumn): List<JianDaoYunWidget> {
        return when (column.key) {
            PriceLookupColumns.CODE -> productCodeFields()
            PriceLookupColumns.NAME_CN -> productChineseNameFields()
            PriceLookupColumns.NAME_EN -> productEnglishNameFields()
            PriceLookupColumns.MODELS -> applicableModelFields()
            PriceLookupColumns.REMARK -> remarkFields()
            PriceLookupColumns.IMAGE -> imageFields()
            else -> emptyList()
        }
    }

    private fun List<JianDaoYunWidget>.applicableModelFields(): List<JianDaoYunWidget> {
        return scoredFields { widget -> widget.applicableModelFieldScore() }
    }

    private fun JianDaoYunWidget.applicableModelFieldScore(): Int {
        val label = displayName.normalizeFieldName()
        val names = "$name $widgetName".normalizeFieldName()
        val haystack = "$label $names"
        if (MODEL_FIELD_EXCLUDE_KEYWORDS.any { haystack.contains(it.normalizeFieldName()) }) return 0

        return when {
            label == "适用车型".normalizeFieldName() -> 300
            label == "通用车型".normalizeFieldName() -> 290
            label == "车型".normalizeFieldName() -> 270
            label == "适用车款".normalizeFieldName() -> 260
            label == "通用车款".normalizeFieldName() -> 250
            label == "车款".normalizeFieldName() -> 230
            label.contains("适用车型".normalizeFieldName()) -> 220
            label.contains("通用车型".normalizeFieldName()) -> 215
            label.contains("车型".normalizeFieldName()) && (
                label.contains("适用".normalizeFieldName()) || label.contains("通用".normalizeFieldName())
                ) -> 210
            label.contains("车型".normalizeFieldName()) -> 180
            label.contains("车款".normalizeFieldName()) -> 160
            label == "model" || label == "models" -> 120
            names.contains("model") || names.contains("models") -> 80
            else -> 0
        }
    }

    private fun List<JianDaoYunWidget>.productCodeFields(): List<JianDaoYunWidget> {
        return scoredFields { widget ->
            val label = widget.displayName.normalizeFieldName()
            val names = "${widget.name} ${widget.widgetName}".normalizeFieldName()
            val haystack = "$label $names"
            if (CODE_FIELD_EXCLUDE_KEYWORDS.any { haystack.contains(it.normalizeFieldName()) }) return@scoredFields 0

            when {
                label == "产品编码".normalizeFieldName() -> 300
                label == "产品新编码".normalizeFieldName() -> 240
                label.startsWith("产品新编码".normalizeFieldName()) -> 180
                label.contains("产品".normalizeFieldName()) && label.contains("编码".normalizeFieldName()) -> 140
                label == "sku" || label == "code" -> 100
                else -> 0
            }
        }
    }

    private fun List<JianDaoYunWidget>.productChineseNameFields(): List<JianDaoYunWidget> {
        return scoredFields { widget ->
            val label = widget.displayName.normalizeFieldName()
            val haystack = "$label ${widget.name} ${widget.widgetName}".normalizeFieldName()
            if (NAME_CN_FIELD_EXCLUDE_KEYWORDS.any { haystack.contains(it.normalizeFieldName()) }) return@scoredFields 0

            when {
                label == "产品中文名称".normalizeFieldName() -> 300
                label == "产品新中文名称".normalizeFieldName() -> 240
                label == "中文名称".normalizeFieldName() -> 220
                label.contains("中文".normalizeFieldName()) && label.contains("名称".normalizeFieldName()) -> 180
                label.contains("产品".normalizeFieldName()) && label.contains("名称".normalizeFieldName()) -> 120
                else -> 0
            }
        }
    }

    private fun List<JianDaoYunWidget>.productEnglishNameFields(): List<JianDaoYunWidget> {
        return scoredFields { widget ->
            val label = widget.displayName.normalizeFieldName()
            val haystack = "$label ${widget.name} ${widget.widgetName}".normalizeFieldName()
            if (NAME_EN_FIELD_EXCLUDE_KEYWORDS.any { haystack.contains(it.normalizeFieldName()) }) return@scoredFields 0

            when {
                label == "产品英文名称".normalizeFieldName() -> 300
                label == "产品新英文名称".normalizeFieldName() -> 240
                label == "英文名称".normalizeFieldName() -> 220
                label.contains("英文".normalizeFieldName()) && label.contains("名称".normalizeFieldName()) -> 180
                label.contains("english") && label.contains("name") -> 160
                label == "nameen" -> 120
                else -> 0
            }
        }
    }

    private fun List<JianDaoYunWidget>.remarkFields(): List<JianDaoYunWidget> {
        return scoredFields { widget ->
            val label = widget.displayName.normalizeFieldName()
            when {
                label == "备注".normalizeFieldName() -> 300
                label.startsWith("备注".normalizeFieldName()) -> 200
                label == "说明".normalizeFieldName() || label == "描述".normalizeFieldName() -> 160
                label == "remark" || label == "note" -> 120
                else -> 0
            }
        }
    }

    private fun List<JianDaoYunWidget>.imageFields(): List<JianDaoYunWidget> {
        return scoredFields { widget ->
            val label = widget.displayName.normalizeFieldName()
            val type = widget.type.lowercase(Locale.ROOT)
            val isImageLike = listOf("image", "file", "upload", "attachment").any { type.contains(it) }
            if (!isImageLike || IMAGE_FIELD_EXCLUDE_KEYWORDS.any { label.contains(it.normalizeFieldName()) }) {
                return@scoredFields 0
            }

            when {
                label == "产品图片".normalizeFieldName() -> 300
                label == "单张图片".normalizeFieldName() -> 260
                label == "主图".normalizeFieldName() -> 240
                label == "配件图片".normalizeFieldName() -> 220
                label == "备用图片".normalizeFieldName() -> 180
                label.contains("图片".normalizeFieldName()) -> 140
                label.contains("照片".normalizeFieldName()) -> 120
                else -> 0
            }
        }
    }

    private fun List<JianDaoYunWidget>.scoredFields(scoreFor: (JianDaoYunWidget) -> Int): List<JianDaoYunWidget> {
        return mapNotNull { widget ->
            val score = scoreFor(widget)
            if (score > 0) widget to score else null
        }
            .sortedWith(
                compareByDescending<Pair<JianDaoYunWidget, Int>> { it.second }
                    .thenBy { it.first.displayName }
            )
            .map { it.first }
    }

    private fun PriceFilterColumn.preferredTypes(): Set<String> {
        return when (key) {
            PriceLookupColumns.STANDARD_PRICE,
            PriceLookupColumns.LATEST_PRICE,
            PriceLookupColumns.LATEST_PRICE_QTY,
            PriceLookupColumns.CTN_QTY,
            PriceLookupColumns.GROSS_WEIGHT_KG,
            PriceLookupColumns.LENGTH_CM,
            PriceLookupColumns.WIDTH_CM,
            PriceLookupColumns.HEIGHT_CM,
            PriceLookupColumns.VOLUME_CBM -> setOf("number")
            PriceLookupColumns.LATEST_PRICE_DATE -> setOf("datetime", "date")
            PriceLookupColumns.IMAGE -> setOf("image", "file", "upload", "attachment")
            PriceLookupColumns.BRAND,
            PriceLookupColumns.UNIT -> setOf("text", "dropdown", "radio")
            else -> setOf("text", "textarea", "dropdown")
        }
    }

    private fun PriceFilterColumn.excludeKeywords(): List<String> {
        return when (key) {
            PriceLookupColumns.STANDARD_PRICE -> listOf("最新", "日期", "时间", "数量", "成本", "采购", "总额", "合计", "date", "qty")
            PriceLookupColumns.LATEST_PRICE -> listOf("标准", "日期", "时间", "数量", "成本", "采购", "总额", "合计", "date", "qty")
            PriceLookupColumns.LATEST_PRICE_DATE -> listOf("数量", "单价", "价格", "金额", "qty")
            PriceLookupColumns.LATEST_PRICE_QTY -> listOf("每箱", "CTN", "ctn", "单价", "价格", "金额")
            PriceLookupColumns.CTN_QTY -> listOf("售价", "销售单价", "金额", "价格")
            PriceLookupColumns.GROSS_WEIGHT_KG -> listOf("净重", "体积", "长", "宽", "高")
            PriceLookupColumns.LENGTH_CM -> listOf("毛重", "重量", "宽", "高", "体积")
            PriceLookupColumns.WIDTH_CM -> listOf("毛重", "重量", "长", "高", "体积")
            PriceLookupColumns.HEIGHT_CM -> listOf("毛重", "重量", "长", "宽", "体积")
            PriceLookupColumns.VOLUME_CBM -> listOf("毛重", "重量", "长", "宽", "高")
            else -> emptyList()
        }
    }

    private fun List<JianDaoYunWidget>.bestField(
        keywords: List<String>,
        preferredTypes: Set<String> = emptySet(),
        excludeKeywords: List<String> = emptyList()
    ): JianDaoYunWidget? {
        return map { widget ->
            val label = widget.label.normalizeFieldName()
            val names = "${widget.name} ${widget.widgetName}".normalizeFieldName()
            val haystack = "$label $names"
            val excluded = excludeKeywords.any { haystack.contains(it.normalizeFieldName()) }
            val keywordScore = keywords.maxOf { keyword ->
                val normalizedKeyword = keyword.normalizeFieldName()
                when {
                    label == normalizedKeyword -> 140
                    label.contains(normalizedKeyword) -> 90
                    names.contains(normalizedKeyword) -> 45
                    else -> 0
                }
            }
            val type = widget.type.lowercase(Locale.ROOT)
            val typeScore = if (preferredTypes.isNotEmpty() && preferredTypes.any { type.contains(it) }) 20 else 0
            widget to if (excluded) 0 else keywordScore + typeScore
        }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun JianDaoYunRow.toLookupResult(fieldMap: JianDaoYunFieldMap): PriceLookupResult {
        val fieldValues = PriceLookupColumns.internalColumns.associate { column ->
            val primaryValue = data.textFor(column, fieldMap.widgetFor(column.key))
            val value = primaryValue.ifBlank {
                data.textFor(column, fieldMap.widgetsFor(column.key))
            }
            column.key to value
        }
        val searchValues = PriceLookupColumns.internalColumns.associate { column ->
            val searchValue = data.textFor(column, fieldMap.widgetsFor(column.key))
                .ifBlank { fieldValues[column.key].orEmpty() }
            column.key to searchValue
        }
        val imageUrl = data.imageUrlFor(fieldMap.widgetsFor(PriceLookupColumns.IMAGE))
        val rawDetails = PriceLookupColumns.internalColumns.mapNotNull { column ->
            if (column.key == PriceLookupColumns.IMAGE) return@mapNotNull null
            val value = fieldValues[column.key].orEmpty()
            if (value.isBlank()) {
                null
            } else {
                val label = fieldMap.widgetFor(column.key)?.displayName ?: column.label
                label to value
            }
        }

        return PriceLookupResult(
            id = id,
            imageUrl = imageUrl,
            code = fieldValues[PriceLookupColumns.CODE].orEmpty(),
            nameCn = fieldValues[PriceLookupColumns.NAME_CN].orEmpty(),
            nameEn = fieldValues[PriceLookupColumns.NAME_EN].orEmpty(),
            brand = fieldValues[PriceLookupColumns.BRAND].orEmpty(),
            models = fieldValues[PriceLookupColumns.MODELS].orEmpty(),
            spec = fieldValues[PriceLookupColumns.SPEC].orEmpty(),
            unit = fieldValues[PriceLookupColumns.UNIT].orEmpty(),
            standardPrice = fieldValues[PriceLookupColumns.STANDARD_PRICE].orEmpty(),
            latestPrice = fieldValues[PriceLookupColumns.LATEST_PRICE].orEmpty(),
            latestPriceDate = fieldValues[PriceLookupColumns.LATEST_PRICE_DATE].orEmpty(),
            latestPriceQty = fieldValues[PriceLookupColumns.LATEST_PRICE_QTY].orEmpty(),
            ctnQty = fieldValues[PriceLookupColumns.CTN_QTY].orEmpty(),
            grossWeightKg = fieldValues[PriceLookupColumns.GROSS_WEIGHT_KG].orEmpty(),
            lengthCm = fieldValues[PriceLookupColumns.LENGTH_CM].orEmpty(),
            widthCm = fieldValues[PriceLookupColumns.WIDTH_CM].orEmpty(),
            heightCm = fieldValues[PriceLookupColumns.HEIGHT_CM].orEmpty(),
            volumeCbm = fieldValues[PriceLookupColumns.VOLUME_CBM].orEmpty(),
            remark = fieldValues[PriceLookupColumns.REMARK].orEmpty(),
            source = "简道云",
            fieldValues = fieldValues,
            searchValues = searchValues,
            rawDetails = rawDetails
        )
    }

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

    private suspend fun PriceLookupCachedSnapshot.toSearchResult(
        compiledFilters: List<CompiledPriceFilterCondition>,
        sourceLabel: String,
        startedAt: Long,
        cacheKind: String,
        onProgress: suspend (results: List<PriceLookupResult>, pageCount: Int, fetchedRowCount: Int) -> Unit
    ): PriceLookupSearchResult {
        val cachedResults = results.filter { result -> result.matchesFilters(compiledFilters) }
        onProgress(cachedResults, pageCount, fetchedRowCount)
        Log.d(
            TAG,
            "JianDaoYun cache hit: kind=$cacheKind, rows=$fetchedRowCount, filtered=${cachedResults.size}, " +
                "ageMs=${ageMs()}, elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
        )
        return PriceLookupSearchResult(
            results = cachedResults,
            sourceLabel = sourceLabel,
            pageCount = pageCount,
            fetchedRowCount = fetchedRowCount,
            fromCache = true,
            cacheAgeMs = ageMs()
        )
    }

    private fun String.matchesCondition(condition: CompiledPriceFilterCondition): Boolean {
        if (isBlank() || condition.text.isBlank()) return false
        return when (condition.columnKey) {
            PriceLookupColumns.CODE -> normalizeCompact().contains(condition.compact)
            PriceLookupColumns.MODELS -> matchesModelCondition(condition)
            PriceLookupColumns.BRAND -> normalizeText().contains(condition.text)
            else -> normalizeText().contains(condition.text)
        }
    }

    private fun String.matchesModelCondition(condition: CompiledPriceFilterCondition): Boolean {
        return PriceModelMatcher.matches(value = this, query = condition.compact)
    }

    private fun JSONObject.textFor(column: PriceFilterColumn, widget: JianDaoYunWidget?): String {
        return rawFor(column = column, widget = widget).toDisplayText()
    }

    private fun JSONObject.textFor(column: PriceFilterColumn, widgets: List<JianDaoYunWidget>): String {
        val values = widgets.mapNotNull { widget ->
            rawFor(column = null, widget = widget)
                .toDisplayText()
                .takeIf { it.isNotBlank() }
        }.distinct()

        return values.takeIf { it.isNotEmpty() }
            ?.joinToString(" / ")
            ?: rawFor(column = column, widget = null).toDisplayText()
    }

    private fun JSONObject.imageUrlFor(widgets: List<JianDaoYunWidget>): String {
        return widgets.firstNotNullOfOrNull { widget ->
            rawFor(column = null, widget = widget)
                .toImageUrl()
                .takeIf { it.isNotBlank() }
        } ?: rawFor(
            column = PriceLookupColumns.columnForKey(PriceLookupColumns.IMAGE),
            widget = null
        ).toImageUrl()
    }

    private fun JSONObject.rawFor(column: PriceFilterColumn?, widget: JianDaoYunWidget?): Any? {
        widget?.keys?.firstNotNullOfOrNull { key ->
            valueForKey(key)
        }?.let { return it }

        column?.possibleFieldNames?.firstNotNullOfOrNull { fieldName ->
            valueForKey(fieldName)
        }?.let { return it }

        return null
    }

    private fun JSONObject.valueForKey(key: String): Any? {
        val exact = opt(key)
        if (exact != null && exact != JSONObject.NULL) return exact

        val normalizedKey = key.normalizeFieldName()
        return keys().asSequence()
            .firstOrNull { candidate -> candidate.normalizeFieldName() == normalizedKey }
            ?.let { matchedKey -> opt(matchedKey) }
            ?.takeUnless { it == JSONObject.NULL }
    }

    private fun Any?.toDisplayText(): String {
        return when (this) {
            null, JSONObject.NULL -> ""
            is String -> trim()
            is Number -> catalogNumber()
            is Boolean -> if (this) "是" else "否"
            is JSONArray -> {
                buildList {
                    for (index in 0 until length()) {
                        val value = opt(index).toDisplayText()
                        if (value.isNotBlank()) add(value)
                    }
                }.joinToString(", ")
            }
            is JSONObject -> {
                val priorityKeys = listOf("value", "name", "label", "text", "filename", "fileName", "url")
                priorityKeys.firstNotNullOfOrNull { key ->
                    if (has(key)) opt(key).toDisplayText().takeIf { it.isNotBlank() } else null
                } ?: keys().asSequence()
                    .mapNotNull { key -> opt(key).toDisplayText().takeIf { it.isNotBlank() } }
                    .take(4)
                    .joinToString(", ")
            }
            else -> toString().trim()
        }
    }

    private fun Any?.toImageUrl(): String {
        return when (this) {
            null, JSONObject.NULL -> ""
            is String -> trim().takeIf { it.startsWith("http://") || it.startsWith("https://") }.orEmpty()
            is JSONArray -> {
                for (index in 0 until length()) {
                    val url = opt(index).toImageUrl()
                    if (url.isNotBlank()) return url
                }
                ""
            }
            is JSONObject -> {
                val priorityKeys = listOf(
                    "url",
                    "download_url",
                    "downloadUrl",
                    "file_url",
                    "fileUrl",
                    "thumbnail",
                    "thumbUrl",
                    "preview_url",
                    "previewUrl",
                    "link"
                )
                priorityKeys.firstNotNullOfOrNull { key ->
                    if (has(key)) opt(key).toImageUrl().takeIf { it.isNotBlank() } else null
                } ?: keys().asSequence()
                    .mapNotNull { key -> opt(key).toImageUrl().takeIf { it.isNotBlank() } }
                    .firstOrNull()
                    .orEmpty()
            }
            else -> ""
        }
    }

    private fun String.normalizeText(): String {
        return Normalizer.normalize(this, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .trim()
    }

    private fun String.normalizeCompact(): String {
        return normalizeText().replace(SEPARATOR_REGEX, "")
    }

    private fun String.normalizeFieldName(): String {
        return Normalizer.normalize(this, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(FIELD_NAME_IGNORED_REGEX, "")
            .trim()
    }

    private data class JianDaoYunRowsResult(
        val results: List<PriceLookupResult>,
        val matchedResults: List<PriceLookupResult>,
        val pageCount: Int,
        val fetchedRowCount: Int
    )

    private data class CompiledPriceFilterCondition(
        val columnKey: String,
        val text: String,
        val compact: String
    )

    private data class JianDaoYunRow(
        val id: String,
        val data: JSONObject
    )

    private data class JianDaoYunEntry(
        val id: String,
        val name: String
    ) {
        fun selectionScore(): Int {
            val normalized = name.normalizeKeyword()
            var score = 0
            if (normalized == "产品信息") score += 500
            if ("产品信息" in normalized) score += 300
            if ("零配件信息" in normalized) score += 220
            if ("价格" in normalized || "报价" in normalized || "价目" in normalized) score += 80
            if ("配件" in normalized || "产品" in normalized || "商品" in normalized || "物料" in normalized) score += 55
            if ("库存" in normalized || "采购" in normalized || "销售" in normalized) score += 30
            if ("历史" in normalized || "平均" in normalized || "辅表" in normalized) score -= 120
            if ("订单" in normalized || "退货" in normalized || "入库" in normalized || "出库" in normalized) score -= 80
            if ("测试" in normalized || "副本" in normalized || "备份" in normalized) score -= 25
            return score
        }

        private fun String.normalizeKeyword(): String {
            return Normalizer.normalize(this, Normalizer.Form.NFKC)
                .lowercase(Locale.ROOT)
        }
    }

    class JianDaoYunConfigException(message: String) : IllegalStateException(message)

    class JianDaoYunHttpException(
        val statusCode: Int,
        val apiMessage: String,
        val retryAfterHeader: String?
    ) : IOException("简道云接口错误 $statusCode: $apiMessage") {
        val isTransient: Boolean
            get() = statusCode == 408 || statusCode == 429 || statusCode in 500..599
    }

    companion object {
        private const val TAG = "HighTacAI"
        private const val PAGE_LIMIT = 100
        private const val MAX_REQUEST_ATTEMPTS = 3
        private const val MIN_RETRY_DELAY_MS = 600L
        private const val MAX_RETRY_DELAY_MS = 3_000L
        private const val MEMORY_CACHE_TTL_MS = 10 * 60 * 1000L
        private const val DISK_CACHE_TTL_MS = Long.MAX_VALUE

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val SEPARATOR_REGEX = Regex("[\\s\\-_/\\\\.,;:，。；：、()（）\\[\\]{}]+")
        private val FIELD_NAME_IGNORED_REGEX = Regex("[\\s\\-_/\\\\.,;:，。；：、()（）\\[\\]{}]+")
        private val MODEL_FIELD_EXCLUDE_KEYWORDS = listOf(
            "规格",
            "型号",
            "编码",
            "编号",
            "单价",
            "价格",
            "数量",
            "日期",
            "图片",
            "照片",
            "附件",
            "备注",
            "品牌",
            "字段",
            "完善",
            "进度",
            "情况",
            "状态",
            "产品名称",
            "中文名称",
            "英文名称",
            "配件名称",
            "商品名称",
            "spec",
            "price",
            "qty",
            "code",
            "brand"
        )
        private val CODE_FIELD_EXCLUDE_KEYWORDS = listOf(
            "海关",
            "二维码",
            "条码",
            "barcode",
            "customs"
        )
        private val NAME_CN_FIELD_EXCLUDE_KEYWORDS = listOf(
            "英文",
            "拼音",
            "分类",
            "类型",
            "完善",
            "进度"
        )
        private val NAME_EN_FIELD_EXCLUDE_KEYWORDS = listOf(
            "中文",
            "拼音",
            "分类",
            "类型",
            "完善",
            "进度"
        )
        private val IMAGE_FIELD_EXCLUDE_KEYWORDS = listOf(
            "完善",
            "进度",
            "情况",
            "字段"
        )

        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

        @Volatile
        private var cachedEntry: JianDaoYunEntry? = null

        @Volatile
        private var cachedWidgetEntryId: String? = null

        @Volatile
        private var cachedWidgets: List<JianDaoYunWidget>? = null

        @Volatile
        private var cachedSnapshot: PriceLookupCachedSnapshot? = null
    }
}
