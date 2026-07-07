package com.example.deepchatdemo.ui.price

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.deepchatdemo.price.PriceFilterCondition

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PriceFilterChipRow(
    filters: List<PriceFilterCondition>,
    modifier: Modifier = Modifier,
    onRemoveFilter: (String) -> Unit
) {
    if (filters.isEmpty()) return

    FlowRow(
        modifier = modifier
            .heightIn(max = 92.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 8.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
    ) {
        filters.forEach { filter ->
            FilterChip(
                filter = filter,
                onRemove = { onRemoveFilter(filter.id) }
            )
        }
    }
}

@Composable
private fun FilterChip(
    filter: PriceFilterCondition,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(PriceLookupColors.glassBrush(0.50f))
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.84f),
                        PriceLookupColors.Blue.copy(alpha = 0.18f)
                    )
                ),
                shape = RoundedCornerShape(18.dp)
            )
            .padding(PaddingValues(start = 12.dp, top = 7.dp, end = 7.dp, bottom = 7.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${filter.columnLabel}：${filter.value}",
            color = PriceLookupColors.Ink,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(6.dp))
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.54f))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "删除筛选条件",
                tint = PriceLookupColors.Ink,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}
