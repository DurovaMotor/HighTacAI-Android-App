package com.example.deepchatdemo.ui.price

import com.example.deepchatdemo.price.PriceLookupResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceResultDetailSheetTest {

    @Test
    fun primarySectionMatchesMiniProgramFieldContract() {
        val sections = buildPriceDetailSections(
            PriceLookupResult(
                id = "part-1",
                code = "HT-001",
                nameCn = "前大灯",
                models = "STUNT 50",
                standardPrice = "80",
                latestPrice = "76.5"
            )
        )

        assertEquals(1, sections.size)
        val rows = sections.single().rows
        assertEquals(18, rows.size)
        assertEquals(
            listOf(
                "产品编码", "中文名称", "英文名称", "品牌", "适用车型", "规格", "单位",
                "标准价", "最新价", "售价日期", "售价最新数量", "装箱数", "毛重", "长",
                "宽", "高", "体积", "备注"
            ),
            rows.map { it.label }
        )
        assertEquals("￥80", rows.first { it.label == "标准价" }.value)
        assertEquals("￥76.5", rows.first { it.label == "最新价" }.value)
        assertEquals("暂无记录", rows.first { it.label == "备注" }.value)
    }

    @Test
    fun originalSourceLabelsArePreservedInSeparateSection() {
        val sections = buildPriceDetailSections(
            PriceLookupResult(
                id = "part-2",
                rawDetails = listOf(
                    "销售单价/元（标准价）" to "100",
                    " 供应商备注 " to " 复核后发货 ",
                    " " to "ignored"
                )
            )
        )

        assertEquals(2, sections.size)
        val rawSection = sections.last()
        assertEquals("原始详细字段", rawSection.title)
        assertEquals(
            listOf(
                PriceDetailRow("销售单价/元（标准价）", "100"),
                PriceDetailRow("供应商备注", "复核后发货")
            ),
            rawSection.rows
        )
        assertTrue(rawSection.rows.any { it.label == "供应商备注" })
        assertFalse(rawSection.rows.any { it.label.isBlank() })
    }
}
