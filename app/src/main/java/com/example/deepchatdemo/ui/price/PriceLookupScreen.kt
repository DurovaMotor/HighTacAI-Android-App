package com.example.deepchatdemo.ui.price

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import com.example.deepchatdemo.cloud.JianDaoYunImageUrlPolicy
import com.example.deepchatdemo.cloud.newJianDaoYunImageHttpClient
import com.example.deepchatdemo.light.domain.LightBinding
import com.example.deepchatdemo.price.InitialPriceRefreshState
import com.example.deepchatdemo.price.PriceFilterColumn
import com.example.deepchatdemo.price.PriceLookupResult
import com.example.deepchatdemo.price.PriceLookupUiState

@Composable
fun PriceLookupScreen(
    uiState: PriceLookupUiState,
    modifier: Modifier = Modifier,
    onSelectColumn: (PriceFilterColumn) -> Unit,
    onFilterValueChange: (String) -> Unit,
    onAddFilter: () -> Unit,
    onRemoveFilter: (String) -> Unit,
    onStartSearch: () -> Unit,
    onRefreshSearch: () -> Unit,
    onRetrySearch: () -> Unit,
    resolveImageUrl: suspend (PriceLookupResult, Boolean) -> String,
    lightBindingForCode: (String) -> LightBinding?,
    onBindLight: (PriceLookupResult) -> Unit,
    onTurnOnLight: (PriceLookupResult) -> Unit,
    onTurnOffLight: (PriceLookupResult) -> Unit
) {
    var selectedResult by remember { mutableStateOf<PriceLookupResult?>(null) }
    val applicationContext = LocalContext.current.applicationContext
    val imageUrlPolicy = remember(applicationContext) {
        JianDaoYunImageUrlPolicy()
    }
    val imageLoader = remember(applicationContext, imageUrlPolicy) {
        ImageLoader.Builder(applicationContext)
            .okHttpClient { newJianDaoYunImageHttpClient(imageUrlPolicy) }
            .build()
    }
    DisposableEffect(imageLoader) {
        onDispose(imageLoader::shutdown)
    }

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PriceResultContent(
            uiState = uiState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            imageUrlPolicy = imageUrlPolicy,
            imageLoader = imageLoader,
            resolveImageUrl = resolveImageUrl,
            onRetrySearch = onRetrySearch,
            onOpenDetail = { selectedResult = it },
            lightBindingForCode = lightBindingForCode,
            onBindLight = onBindLight,
            onTurnOnLight = onTurnOnLight,
            onTurnOffLight = onTurnOffLight
        )
        Spacer(Modifier.height(12.dp))
        PriceFilterArea(
            uiState = uiState,
            onSelectColumn = onSelectColumn,
            onFilterValueChange = onFilterValueChange,
            onAddFilter = onAddFilter,
            onRemoveFilter = onRemoveFilter,
            onStartSearch = onStartSearch,
            onRefreshSearch = onRefreshSearch
        )
    }

    selectedResult?.let { item ->
        PriceResultDetailSheet(
            item = item,
            imageUrlPolicy = imageUrlPolicy,
            imageLoader = imageLoader,
            resolveImageUrl = resolveImageUrl,
            onDismissRequest = { selectedResult = null }
        )
    }
}

@Composable
private fun PriceResultContent(
    uiState: PriceLookupUiState,
    modifier: Modifier = Modifier,
    imageUrlPolicy: JianDaoYunImageUrlPolicy,
    imageLoader: ImageLoader,
    resolveImageUrl: suspend (PriceLookupResult, Boolean) -> String,
    onRetrySearch: () -> Unit,
    onOpenDetail: (PriceLookupResult) -> Unit,
    lightBindingForCode: (String) -> LightBinding?,
    onBindLight: (PriceLookupResult) -> Unit,
    onTurnOnLight: (PriceLookupResult) -> Unit,
    onTurnOffLight: (PriceLookupResult) -> Unit
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        when {
            uiState.isSearching && uiState.results.isEmpty() -> {
                PriceStateCard(
                    title = "正在从简道云查询...",
                    message = "首次同步会逐页读取产品表，成功后会写入本机缓存；之后普通查找会直接本地筛选。",
                    loading = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            uiState.errorMessage != null -> {
                PriceStateCard(
                    title = uiState.errorMessage,
                    message = "请检查网络或简道云直连配置后重试；已有本地缓存不会被清除。",
                    actionText = "重试查询",
                    onAction = onRetrySearch,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            uiState.results.isNotEmpty() -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 4.dp)
                ) {
                    item {
                        ResultSummaryCard(
                            resultCount = uiState.resultCount,
                            isSearching = uiState.isSearching,
                            scannedPageCount = uiState.scannedPageCount,
                            scannedRowCount = uiState.scannedRowCount,
                            sourceLabel = uiState.sourceLabel,
                            cacheAgeLabel = uiState.cacheAgeLabel,
                            initialRefreshState = uiState.initialRefreshState
                        )
                    }
                    items(uiState.results, key = { it.id }) { item ->
                        PriceResultCard(
                            item = item,
                            imageUrlPolicy = imageUrlPolicy,
                            imageLoader = imageLoader,
                            resolveImageUrl = resolveImageUrl,
                            lightBinding = lightBindingForCode(item.code),
                            onClick = { onOpenDetail(item) },
                            onBindLight = { onBindLight(item) },
                            onTurnOnLight = { onTurnOnLight(item) },
                            onTurnOffLight = { onTurnOffLight(item) }
                        )
                    }
                }
            }
            uiState.hasSearched -> {
                PriceStateCard(
                    title = "没有找到符合条件的产品",
                    message = uiState.emptyMessage ?: "没有找到符合条件的产品，请调整筛选条件。",
                    modifier = Modifier.fillMaxWidth()
                )
            }
            else -> {
                PriceStateCard(
                    title = "摩托车配件筛选查价工作台",
                    message = buildInitialGuide(uiState),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private fun buildInitialGuide(uiState: PriceLookupUiState): String {
    val cacheStatus = buildList {
        if (uiState.sourceLabel.isNotBlank()) add(uiState.sourceLabel)
        uiState.cacheAgeLabel?.takeIf { it.isNotBlank() }?.let { add(it) }
        if (uiState.scannedRowCount > 0) add("已索引 ${uiState.scannedRowCount} 条")
    }.joinToString(" · ")
    val initialRefreshStatus = uiState.initialRefreshState.displayText()

    return if (cacheStatus.isBlank()) {
        "$priceInitialGuide\n\n$initialRefreshStatus"
    } else {
        "$priceInitialGuide\n\n$initialRefreshStatus\n本地数据：$cacheStatus"
    }
}

@Composable
private fun ResultSummaryCard(
    resultCount: Int,
    isSearching: Boolean,
    scannedPageCount: Int,
    scannedRowCount: Int,
    sourceLabel: String,
    cacheAgeLabel: String?,
    initialRefreshState: InitialPriceRefreshState
) {
    PriceGlassPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        alpha = 0.52f,
        elevation = 10.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (isSearching) {
                    "已匹配 $resultCount 条，已扫描 $scannedRowCount 条 / $scannedPageCount 页，继续查询中..."
                } else {
                    "共找到 $resultCount 条结果"
                },
                color = PriceLookupColors.Ink,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Bold
            )
            val detailText = buildList {
                if (sourceLabel.isNotBlank()) add(sourceLabel)
                cacheAgeLabel?.takeIf { it.isNotBlank() }?.let { add(it) }
                if (!isSearching && scannedRowCount > 0) add("已索引 $scannedRowCount 条")
                add(initialRefreshState.displayText())
            }.joinToString(" · ")
            if (detailText.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = detailText,
                    color = PriceLookupColors.Muted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

private fun InitialPriceRefreshState.displayText(): String = when (this) {
    InitialPriceRefreshState.Pending -> "首次数据同步：准备中"
    InitialPriceRefreshState.AlreadyCompleted -> "首次数据同步：已完成"
    is InitialPriceRefreshState.Running ->
        "首次数据同步：后台进行中（已读取 $fetchedRowCount 条 / $pageCount 页）"
    is InitialPriceRefreshState.Succeeded ->
        "首次数据同步：已完成（$fetchedRowCount 条 / $pageCount 页）"
    InitialPriceRefreshState.Failed ->
        "首次数据同步：本次失败，已保留旧缓存；下次启动会重试"
}
