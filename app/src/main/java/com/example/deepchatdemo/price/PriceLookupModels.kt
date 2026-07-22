package com.example.deepchatdemo.price

import java.math.BigDecimal

data class PriceFilterColumn(
    val key: String,
    val label: String,
    val possibleFieldNames: List<String>
)

data class PriceFilterCondition(
    val id: String,
    val columnKey: String,
    val columnLabel: String,
    val value: String
)

data class PriceLookupUiState(
    val availableColumns: List<PriceFilterColumn> = PriceLookupColumns.filterColumns,
    val selectedColumn: PriceFilterColumn? = null,
    val filterValue: String = "",
    val filters: List<PriceFilterCondition> = emptyList(),
    val results: List<PriceLookupResult> = emptyList(),
    val isSearching: Boolean = false,
    val errorMessage: String? = null,
    val emptyMessage: String? = null,
    val inputMessage: String? = null,
    val hasSearched: Boolean = false,
    val resultCount: Int = 0,
    val scannedPageCount: Int = 0,
    val scannedRowCount: Int = 0,
    val sourceLabel: String = "",
    val cacheAgeLabel: String? = null,
    val initialRefreshState: InitialPriceRefreshState = InitialPriceRefreshState.Pending
)

data class PriceLookupResult(
    val id: String,
    val imageUrl: String = "",
    val code: String = "",
    val nameCn: String = "",
    val nameEn: String = "",
    val brand: String = "",
    val models: String = "",
    val spec: String = "",
    val unit: String = "",
    val standardPrice: String = "",
    val latestPrice: String = "",
    val latestPriceDate: String = "",
    val latestPriceQty: String = "",
    val ctnQty: String = "",
    val grossWeightKg: String = "",
    val lengthCm: String = "",
    val widthCm: String = "",
    val heightCm: String = "",
    val volumeCbm: String = "",
    val remark: String = "",
    val source: String = "简道云",
    val fieldValues: Map<String, String> = emptyMap(),
    val searchValues: Map<String, String> = emptyMap(),
    val rawDetails: List<Pair<String, String>> = emptyList()
) {
    fun valueForColumn(columnKey: String): String {
        return when (columnKey) {
            PriceLookupColumns.CODE -> code
            PriceLookupColumns.NAME_CN -> nameCn
            PriceLookupColumns.NAME_EN -> nameEn
            PriceLookupColumns.BRAND -> brand
            PriceLookupColumns.MODELS -> models
            PriceLookupColumns.SPEC -> spec
            PriceLookupColumns.UNIT -> unit
            PriceLookupColumns.STANDARD_PRICE -> standardPrice
            PriceLookupColumns.LATEST_PRICE -> latestPrice
            PriceLookupColumns.LATEST_PRICE_DATE -> latestPriceDate
            PriceLookupColumns.CTN_QTY -> ctnQty
            PriceLookupColumns.GROSS_WEIGHT_KG -> grossWeightKg
            PriceLookupColumns.LENGTH_CM -> lengthCm
            PriceLookupColumns.WIDTH_CM -> widthCm
            PriceLookupColumns.HEIGHT_CM -> heightCm
            PriceLookupColumns.VOLUME_CBM -> volumeCbm
            PriceLookupColumns.REMARK -> remark
            else -> fieldValues[columnKey].orEmpty()
        }
    }
}

data class PriceLookupSearchResult(
    val results: List<PriceLookupResult>,
    val sourceLabel: String,
    val pageCount: Int,
    val fetchedRowCount: Int,
    val fromCache: Boolean = false,
    val cacheAgeMs: Long? = null
)

data class PriceLookupCachedSnapshot(
    val entryId: String,
    val entryName: String,
    val results: List<PriceLookupResult>,
    val pageCount: Int,
    val fetchedRowCount: Int,
    val createdAtEpochMs: Long
) {
    fun ageMs(nowEpochMs: Long = System.currentTimeMillis()): Long {
        return (nowEpochMs - createdAtEpochMs).coerceAtLeast(0L)
    }

    fun isFresh(maxAgeMs: Long, nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        return ageMs(nowEpochMs) <= maxAgeMs
    }
}

data class JianDaoYunWidget(
    val name: String,
    val widgetName: String,
    val label: String,
    val type: String
) {
    val keys: List<String>
        get() = listOf(name, widgetName, label).filter { it.isNotBlank() }.distinct()

    val apiKeys: List<String>
        get() = listOf(name, widgetName).filter { it.isNotBlank() }.distinct()

    val displayName: String
        get() = label.ifBlank { name.ifBlank { widgetName } }
}

data class JianDaoYunFieldMap(
    val widgetsByColumnKey: Map<String, JianDaoYunWidget?> = emptyMap(),
    val candidateWidgetsByColumnKey: Map<String, List<JianDaoYunWidget>> = emptyMap()
) {
    fun widgetFor(columnKey: String): JianDaoYunWidget? = widgetsByColumnKey[columnKey]

    fun widgetsFor(columnKey: String): List<JianDaoYunWidget> {
        val candidates = candidateWidgetsByColumnKey[columnKey].orEmpty()
        return (listOfNotNull(widgetFor(columnKey)) + candidates)
            .distinctBy { it.widgetName.ifBlank { it.name.ifBlank { it.label } } }
    }

    val selectedWidgets: List<JianDaoYunWidget>
        get() = (widgetsByColumnKey.values.filterNotNull() + candidateWidgetsByColumnKey.values.flatten())
            .distinctBy { it.widgetName.ifBlank { it.name.ifBlank { it.label } } }

    val summary: String
        get() = PriceLookupColumns.filterColumns.joinToString("；") { column ->
            "${column.label}:${widgetFor(column.key)?.displayName ?: "未匹配"}"
        }
}

object PriceLookupColumns {
    const val CODE = "code"
    const val NAME_CN = "name_cn"
    const val NAME_EN = "name_en"
    const val BRAND = "brand"
    const val MODELS = "models"
    const val SPEC = "spec"
    const val UNIT = "unit"
    const val STANDARD_PRICE = "standard_price"
    const val LATEST_PRICE = "latest_price"
    const val LATEST_PRICE_DATE = "latest_price_date"
    const val LATEST_PRICE_QTY = "latest_price_qty"
    const val CTN_QTY = "ctn_qty"
    const val GROSS_WEIGHT_KG = "gross_weight_kg"
    const val LENGTH_CM = "length_cm"
    const val WIDTH_CM = "width_cm"
    const val HEIGHT_CM = "height_cm"
    const val VOLUME_CBM = "volume_cbm"
    const val REMARK = "remark"
    const val IMAGE = "image"

    val filterColumns: List<PriceFilterColumn> = listOf(
        PriceFilterColumn(
            key = CODE,
            label = "编码",
            possibleFieldNames = listOf("产品编码", "商品编码", "新产品编码", "配件编码", "物料编码", "货号", "编码", "代码", "SKU", "sku", "code", "part no", "item no")
        ),
        PriceFilterColumn(
            key = NAME_CN,
            label = "中文名称",
            possibleFieldNames = listOf("产品中文名称", "中文名称", "中文名", "产品名称", "配件名称", "商品名称", "物料名称", "名称", "品名", "name")
        ),
        PriceFilterColumn(
            key = NAME_EN,
            label = "英文名称",
            possibleFieldNames = listOf("产品英文名称", "英文名称", "英文名", "英文品名", "English Name", "name_en")
        ),
        PriceFilterColumn(
            key = BRAND,
            label = "品牌",
            possibleFieldNames = listOf("品牌", "牌子", "适用品牌", "brand")
        ),
        PriceFilterColumn(
            key = MODELS,
            label = "车型",
            possibleFieldNames = listOf("通用车型", "适用车型", "车型", "车款", "适用车款", "通用车款", "model", "models")
        )
    )

    private val resultColumns: List<PriceFilterColumn> = listOf(
        PriceFilterColumn(
            key = SPEC,
            label = "规格",
            possibleFieldNames = listOf("规格型号", "规格", "型号规格", "参数", "spec", "specification")
        ),
        PriceFilterColumn(
            key = UNIT,
            label = "单位",
            possibleFieldNames = listOf("单位（中文）", "单位(中文)", "单位", "unit")
        ),
        PriceFilterColumn(
            key = STANDARD_PRICE,
            label = "标准价",
            possibleFieldNames = listOf("销售单价/元（标准价）", "销售单价/元(标准价)", "销售单价（标准价）", "标准价", "标准单价", "price_standard")
        ),
        PriceFilterColumn(
            key = LATEST_PRICE,
            label = "最新价",
            possibleFieldNames = listOf("销售单价/元（最新价）", "销售单价/元(最新价)", "销售单价（最新价）", "售价最新", "最新价", "最新价格", "price_latest")
        ),
        PriceFilterColumn(
            key = LATEST_PRICE_DATE,
            label = "售价日期",
            possibleFieldNames = listOf("售价最新日期", "价格日期", "报价日期", "最新日期", "日期", "date")
        ),
        PriceFilterColumn(
            key = CTN_QTY,
            label = "装箱数",
            possibleFieldNames = listOf("每箱数量(CTN)", "每箱数量（CTN）", "每箱数量", "装箱数", "CTN", "ctn_qty")
        ),
        PriceFilterColumn(
            key = GROSS_WEIGHT_KG,
            label = "毛重",
            possibleFieldNames = listOf("毛重(KG)", "毛重（KG）", "毛重", "重量", "gross weight", "gross_weight")
        ),
        PriceFilterColumn(
            key = LENGTH_CM,
            label = "长",
            possibleFieldNames = listOf("长(CM)", "长（CM）", "长度", "长", "length")
        ),
        PriceFilterColumn(
            key = WIDTH_CM,
            label = "宽",
            possibleFieldNames = listOf("宽(CM)", "宽（CM）", "宽度", "宽", "width")
        ),
        PriceFilterColumn(
            key = HEIGHT_CM,
            label = "高",
            possibleFieldNames = listOf("高(CM)", "高（CM）", "高度", "高", "height")
        ),
        PriceFilterColumn(
            key = VOLUME_CBM,
            label = "体积",
            possibleFieldNames = listOf("体积(CBM)", "体积（CBM）", "体积", "CBM", "volume")
        ),
        PriceFilterColumn(
            key = REMARK,
            label = "备注",
            possibleFieldNames = listOf("备注", "说明", "描述", "remark", "note")
        )
    )

    val internalColumns: List<PriceFilterColumn> = filterColumns + resultColumns + listOf(
        PriceFilterColumn(
            key = LATEST_PRICE_QTY,
            label = "售价最新数量",
            possibleFieldNames = listOf("售价最新数量", "最新数量", "销售数量", "数量", "qty", "stock")
        ),
        PriceFilterColumn(
            key = IMAGE,
            label = "图片",
            possibleFieldNames = listOf("图片", "产品图片", "配件图片", "主图", "照片", "附件", "image", "picture", "photo")
        )
    )

    fun columnForKey(key: String): PriceFilterColumn? {
        return internalColumns.firstOrNull { it.key == key }
    }
}

internal fun String.ifNoRecord(): String = ifBlank { "暂无记录" }

internal fun Number.catalogNumber(): String {
    return when (this) {
        is Double, is Float -> BigDecimal.valueOf(toDouble()).stripTrailingZeros().toPlainString()
        else -> toString()
    }
}
