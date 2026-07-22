package com.example.deepchatdemo.ui.price

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import com.example.deepchatdemo.platform.network.PlatformImageUrlResolver
import com.example.deepchatdemo.price.PriceLookupResult

internal data class PriceDetailRow(
    val label: String,
    val value: String
)

internal data class PriceDetailSection(
    val title: String,
    val rows: List<PriceDetailRow>
)

/**
 * Keeps the detail contract independent from Compose so field ordering and raw-field handling can
 * be unit tested. The first section mirrors the mini program; the second preserves source labels.
 */
internal fun buildPriceDetailSections(item: PriceLookupResult): List<PriceDetailSection> {
    val primaryRows = listOf(
        "产品编码" to item.code,
        "中文名称" to item.nameCn,
        "英文名称" to item.nameEn,
        "品牌" to item.brand,
        "适用车型" to item.models,
        "规格" to item.spec,
        "单位" to item.unit,
        "标准价" to priceOrNoRecord(item.standardPrice),
        "最新价" to priceOrNoRecord(item.latestPrice),
        "售价日期" to item.latestPriceDate,
        "售价最新数量" to item.latestPriceQty,
        "装箱数" to item.ctnQty,
        "毛重" to item.grossWeightKg,
        "长" to item.lengthCm,
        "宽" to item.widthCm,
        "高" to item.heightCm,
        "体积" to item.volumeCbm,
        "备注" to item.remark
    ).map { (label, value) ->
        PriceDetailRow(label = label, value = displayOrNoRecord(value))
    }

    return buildList {
        add(PriceDetailSection(title = "配件详细信息", rows = primaryRows))
        item.rawDetails
            .mapNotNull { (label, value) ->
                label.trim().takeIf { it.isNotBlank() }?.let { safeLabel ->
                    PriceDetailRow(safeLabel, displayOrNoRecord(value.trim()))
                }
            }
            .takeIf { it.isNotEmpty() }
            ?.let { add(PriceDetailSection(title = "原始详细字段", rows = it)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PriceResultDetailSheet(
    item: PriceLookupResult,
    imageUrlResolver: PlatformImageUrlResolver,
    imageLoader: ImageLoader,
    resolveImageUrl: suspend (PriceLookupResult, Boolean) -> String,
    onDismissRequest: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxContentHeight = LocalConfiguration.current.screenHeightDp.dp * 0.78f
    val sections = buildPriceDetailSections(item)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = Color(0xFFF3F7FF),
        scrimColor = PriceLookupColors.Ink.copy(alpha = 0.30f),
        tonalElevation = 0.dp
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxContentHeight)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp)
        ) {
            item(key = "header") {
                DetailHeader(
                    title = item.nameCn.ifBlank { item.nameEn }.ifBlank { "暂无记录" },
                    onClose = onDismissRequest
                )
                Spacer(Modifier.height(14.dp))
                DetailHero(
                    item = item,
                    imageUrlResolver = imageUrlResolver,
                    imageLoader = imageLoader,
                    resolveImageUrl = resolveImageUrl
                )
                Spacer(Modifier.height(20.dp))
            }

            sections.forEachIndexed { sectionIndex, section ->
                item(key = "section_$sectionIndex") {
                    Text(
                        text = section.title,
                        color = PriceLookupColors.Purple,
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                itemsIndexed(
                    items = section.rows,
                    key = { rowIndex, _ -> "${sectionIndex}_$rowIndex" }
                ) { rowIndex, row ->
                    DetailRow(row)
                    if (rowIndex == section.rows.lastIndex) {
                        Spacer(Modifier.height(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailHeader(
    title: String,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = PriceLookupColors.Ink,
            fontSize = 20.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "关闭配件详情",
                tint = PriceLookupColors.Ink
            )
        }
    }
}

@Composable
private fun DetailHero(
    item: PriceLookupResult,
    imageUrlResolver: PlatformImageUrlResolver,
    imageLoader: ImageLoader,
    resolveImageUrl: suspend (PriceLookupResult, Boolean) -> String
) {
    PriceGlassPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(14.dp),
        alpha = 0.60f,
        elevation = 10.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            ProductImage(
                item = item,
                imageUrlResolver = imageUrlResolver,
                imageLoader = imageLoader,
                resolveImageUrl = resolveImageUrl,
                contentDescription = item.nameCn.ifBlank { item.nameEn }.ifBlank { "产品图片" },
                modifier = Modifier.size(104.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text(
                    text = "最新价 ${priceOrNoRecord(item.latestPrice)}",
                    color = PriceLookupColors.Purple,
                    fontSize = 17.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "标准价 ${priceOrNoRecord(item.standardPrice)}",
                    color = PriceLookupColors.Muted,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "适用车型：${displayOrNoRecord(item.models)}",
                    color = PriceLookupColors.Ink,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "数据来源 · ${displayOrNoRecord(item.source)}",
                    color = PriceLookupColors.Blue,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun DetailRow(row: PriceDetailRow) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 11.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = row.label,
                color = PriceLookupColors.Muted,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(96.dp)
            )
            Text(
                text = row.value,
                color = PriceLookupColors.Ink,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.72f))
        )
    }
}
