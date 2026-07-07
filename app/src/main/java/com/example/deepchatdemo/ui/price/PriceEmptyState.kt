package com.example.deepchatdemo.ui.price

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun PriceStateCard(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    PriceGlassPanel(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
        alpha = 0.56f,
        elevation = 12.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                color = PriceLookupColors.Ink,
                fontSize = 18.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                color = PriceLookupColors.Muted,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Medium
            )
            if (loading) {
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator(
                    color = PriceLookupColors.Blue,
                    trackColor = Color.White.copy(alpha = 0.52f)
                )
            }
            if (actionText != null && onAction != null) {
                Spacer(Modifier.height(16.dp))
                PriceGradientButton(
                    text = actionText,
                    modifier = Modifier.fillMaxWidth(),
                    minHeight = 48.dp,
                    onClick = onAction
                )
            }
        }
    }
}

internal val priceInitialGuide = """
选择列名并填写筛选值，添加条件后开始查找。

示例：
品牌：SYM
车型：JET 14
名称：前刹车片
编码：45100-F75-000
""".trimIndent()
