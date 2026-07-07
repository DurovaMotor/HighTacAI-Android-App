package com.example.deepchatdemo.ui.price

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object PriceLookupColors {
    val Ink = Color(0xFF07143A)
    val Muted = Color(0xFF6F7897)
    val Cyan = Color(0xFF35D7F6)
    val Blue = Color(0xFF3F86FF)
    val Purple = Color(0xFF7A48FF)

    fun glassBrush(alpha: Float) = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = alpha + 0.08f),
            Color.White.copy(alpha = alpha),
            Color(0xFFE8F4FF).copy(alpha = alpha * 0.80f),
            Color(0xFFEDE6FF).copy(alpha = alpha * 0.62f)
        ),
        start = Offset.Zero,
        end = Offset(1000f, 1000f)
    )

    fun actionBrush() = Brush.linearGradient(
        listOf(Cyan, Blue, Purple),
        start = Offset.Zero,
        end = Offset(900f, 900f)
    )

    fun disabledBrush() = Brush.linearGradient(
        listOf(Color(0xFFB9C5DD), Color(0xFFAEB7D0))
    )
}

@Composable
internal fun PriceGlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    contentPadding: PaddingValues = PaddingValues(16.dp),
    alpha: Float = 0.56f,
    elevation: Dp = 14.dp,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .shadow(
                elevation = elevation,
                shape = shape,
                ambientColor = Color(0xFF6884FF).copy(alpha = 0.08f),
                spotColor = Color(0xFF5E73FF).copy(alpha = 0.14f)
            )
            .clip(shape)
            .background(PriceLookupColors.glassBrush(alpha))
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.88f),
                        Color.White.copy(alpha = 0.24f),
                        Color(0xFF96B4FF).copy(alpha = 0.18f)
                    )
                ),
                shape = shape
            )
            .padding(contentPadding),
        content = content
    )
}

@Composable
internal fun PriceGradientButton(
    text: String,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    enabled: Boolean = true,
    minHeight: Dp = 48.dp,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(minHeight)
            .shadow(
                elevation = if (active) 12.dp else 4.dp,
                shape = RoundedCornerShape(18.dp),
                ambientColor = PriceLookupColors.Blue.copy(alpha = if (active) 0.20f else 0.08f),
                spotColor = PriceLookupColors.Purple.copy(alpha = if (active) 0.24f else 0.08f)
            )
            .clip(RoundedCornerShape(18.dp))
            .background(if (active) PriceLookupColors.actionBrush() else PriceLookupColors.disabledBrush())
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 15.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

internal fun displayOrNoRecord(value: String): String {
    return value.ifBlank { "暂无记录" }
}

internal fun priceOrNoRecord(value: String): String {
    return value.ifBlank { "暂无记录" }.let { text ->
        if (text == "暂无记录" || text.startsWith("￥")) text else "￥$text"
    }
}
