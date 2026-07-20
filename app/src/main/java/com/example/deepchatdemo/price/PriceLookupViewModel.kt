package com.example.deepchatdemo.price

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.deepchatdemo.platform.network.PlatformMobileAuthorizationException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PriceLookupViewModel(
    private val repository: PriceLookupRepository
) : ViewModel() {
    var uiState by mutableStateOf(PriceLookupUiState())
        private set

    init {
        prepareLocalCache()
    }

    private fun prepareLocalCache() {
        viewModelScope.launch {
            runCatching {
                repository.prepareLocalCache()
            }.getOrNull()?.let { status ->
                uiState = uiState.copy(
                    scannedPageCount = status.pageCount,
                    scannedRowCount = status.fetchedRowCount,
                    sourceLabel = status.sourceLabel,
                    cacheAgeLabel = status.cacheAgeMs.toCacheAgeLabel()
                )
            }
        }
    }

    fun selectColumn(column: PriceFilterColumn) {
        uiState = uiState.copy(
            selectedColumn = column,
            inputMessage = null
        )
    }

    fun onFilterValueChange(value: String) {
        uiState = uiState.copy(
            filterValue = value,
            inputMessage = null
        )
    }

    fun addFilter() {
        val column = uiState.selectedColumn
        val value = uiState.filterValue.trim()
        when {
            column == null -> {
                uiState = uiState.copy(inputMessage = "请先选择列名")
                return
            }
            value.isBlank() -> {
                uiState = uiState.copy(inputMessage = "请填写筛选值")
                return
            }
        }

        val normalizedValue = value.normalizedFilterValue()
        val duplicate = uiState.filters.any { condition ->
            condition.columnKey == column.key &&
                condition.value.normalizedFilterValue() == normalizedValue
        }
        if (duplicate) {
            uiState = uiState.copy(inputMessage = "该筛选条件已存在")
            return
        }

        val condition = column.toFilterCondition(value)
        uiState = uiState.copy(
            filters = uiState.filters + condition,
            filterValue = "",
            inputMessage = null
        )
    }

    fun removeFilter(filterId: String) {
        uiState = uiState.copy(
            filters = uiState.filters.filterNot { it.id == filterId },
            inputMessage = null
        )
    }

    fun clearFilters() {
        uiState = uiState.copy(
            filters = emptyList(),
            results = emptyList(),
            errorMessage = null,
            emptyMessage = null,
            inputMessage = null,
            hasSearched = false,
            resultCount = 0,
            scannedPageCount = 0,
            scannedRowCount = 0,
            sourceLabel = "",
            cacheAgeLabel = null
        )
    }

    fun startSearch() {
        startSearch(forceRefresh = false)
    }

    fun refreshSearch() {
        startSearch(forceRefresh = true)
    }

    suspend fun resolveImageUrl(
        item: PriceLookupResult,
        forceRefresh: Boolean
    ): String = repository.resolveImageUrl(item, forceRefresh)

    private fun startSearch(forceRefresh: Boolean) {
        if (uiState.isSearching) return
        val filters = commitPendingFilterForSearch() ?: return

        uiState = uiState.copy(
            isSearching = true,
            results = emptyList(),
            errorMessage = null,
            emptyMessage = null,
            inputMessage = null,
            hasSearched = true,
            resultCount = 0,
            scannedPageCount = 0,
            scannedRowCount = 0,
            sourceLabel = if (forceRefresh) "简道云实时数据" else "",
            cacheAgeLabel = null
        )

        viewModelScope.launch {
            val result = runCatching {
                repository.lookup(filters, forceRefresh = forceRefresh) { partialResults, pageCount, fetchedRowCount ->
                    withContext(Dispatchers.Main.immediate) {
                        uiState = uiState.copy(
                            results = partialResults,
                            resultCount = partialResults.size,
                            scannedPageCount = pageCount,
                            scannedRowCount = fetchedRowCount,
                            errorMessage = null,
                            emptyMessage = null,
                            sourceLabel = if (forceRefresh) "简道云实时数据" else uiState.sourceLabel,
                            cacheAgeLabel = null
                        )
                    }
                }
            }
            uiState = result.fold(
                onSuccess = { lookupResult ->
                    uiState.copy(
                        isSearching = false,
                        results = lookupResult.results,
                        errorMessage = null,
                        emptyMessage = if (lookupResult.results.isEmpty()) {
                            "没有找到符合条件的产品，请调整筛选条件。"
                        } else {
                            null
                        },
                        resultCount = lookupResult.results.size,
                        scannedPageCount = lookupResult.pageCount,
                        scannedRowCount = lookupResult.fetchedRowCount,
                        sourceLabel = lookupResult.sourceLabel,
                        cacheAgeLabel = lookupResult.cacheAgeMs?.toCacheAgeLabel()
                    )
                },
                onFailure = { error ->
                    uiState.copy(
                        isSearching = false,
                        results = emptyList(),
                        errorMessage = error.toPriceLookupErrorMessage(),
                        emptyMessage = null,
                        resultCount = 0,
                        scannedPageCount = 0,
                        scannedRowCount = 0,
                        sourceLabel = "",
                        cacheAgeLabel = null
                    )
                }
            )
        }
    }

    fun retrySearch() {
        startSearch()
    }

    private fun commitPendingFilterForSearch(): List<PriceFilterCondition>? {
        val value = uiState.filterValue.trim()
        if (value.isBlank()) return uiState.filters

        val column = uiState.selectedColumn
        if (column == null) {
            uiState = uiState.copy(inputMessage = "请先选择列名")
            return null
        }

        val normalizedValue = value.normalizedFilterValue()
        val duplicate = uiState.filters.any { condition ->
            condition.columnKey == column.key &&
                condition.value.normalizedFilterValue() == normalizedValue
        }
        if (duplicate) {
            uiState = uiState.copy(
                filterValue = "",
                inputMessage = null
            )
            return uiState.filters
        }

        val filters = uiState.filters + column.toFilterCondition(value)
        uiState = uiState.copy(
            filters = filters,
            filterValue = "",
            inputMessage = null
        )
        return filters
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(PriceLookupViewModel::class.java)) {
                        return PriceLookupViewModel(
                            repository = PriceLookupRepository.fromContext(context)
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }
}

private fun PriceFilterColumn.toFilterCondition(value: String): PriceFilterCondition {
    return PriceFilterCondition(
        id = UUID.randomUUID().toString(),
        columnKey = key,
        columnLabel = label,
        value = value
    )
}

private fun String.normalizedFilterValue(): String {
    return trim().lowercase()
}

private fun Long.toCacheAgeLabel(): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(this)
    return when {
        minutes < 1 -> "刚刚同步"
        minutes < 60 -> "${minutes} 分钟前同步"
        minutes < 24 * 60 -> "${TimeUnit.MILLISECONDS.toHours(this)} 小时前同步"
        else -> "${TimeUnit.MILLISECONDS.toDays(this)} 天前同步"
    }
}

private fun Throwable.toPriceLookupErrorMessage(): String {
    return when (this) {
        is PlatformMobileAuthorizationException ->
            message ?: "此手机尚未通过后台审批，请先在网页管理平台批准该设备。"
        is JianDaoYunPriceApi.JianDaoYunHttpException -> toPriceLookupErrorMessage()
        else -> message
            ?.takeIf { it.isNotBlank() }
            ?.let { "简道云查询失败：$it" }
            ?: "简道云查询失败，请检查网络或接口配置。"
    }
}

private fun JianDaoYunPriceApi.JianDaoYunHttpException.toPriceLookupErrorMessage(): String {
    val detail = apiMessage.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
    return when (statusCode) {
        400, 422 -> "简道云请求参数错误$detail"
        401, 403 -> "后台简道云服务未获授权，请联系管理员检查平台配置$detail"
        404 -> "后台简道云表单配置不存在，请联系管理员检查平台设置$detail"
        408 -> "简道云请求超时，请稍后重试$detail"
        429 -> "简道云请求过于频繁，请稍后重试$detail"
        in 500..599 -> "简道云服务暂时不可用，请稍后重试$detail"
        else -> "简道云接口错误 $statusCode$detail"
    }
}
