package com.example.deepchatdemo.ui.price

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.deepchatdemo.price.PriceFilterColumn
import com.example.deepchatdemo.price.PriceLookupUiState

@Composable
internal fun PriceFilterArea(
    uiState: PriceLookupUiState,
    modifier: Modifier = Modifier,
    onSelectColumn: (PriceFilterColumn) -> Unit,
    onFilterValueChange: (String) -> Unit,
    onAddFilter: () -> Unit,
    onRemoveFilter: (String) -> Unit,
    onStartSearch: () -> Unit,
    onRefreshSearch: () -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        PriceFilterChipRow(
            filters = uiState.filters,
            modifier = Modifier.fillMaxWidth(),
            onRemoveFilter = onRemoveFilter
        )

        PriceGlassPanel(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
            alpha = 0.58f,
            elevation = 20.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PriceColumnDropdown(
                        columns = uiState.availableColumns,
                        selectedColumn = uiState.selectedColumn,
                        enabled = !uiState.isSearching,
                        onSelectColumn = onSelectColumn,
                        modifier = Modifier.width(96.dp)
                    )
                    PriceValueInput(
                        value = uiState.filterValue,
                        enabled = !uiState.isSearching,
                        onValueChange = onFilterValueChange,
                        onDone = onAddFilter,
                        modifier = Modifier.weight(1f)
                    )
                    PriceGradientButton(
                        text = "添加筛选",
                        active = uiState.selectedColumn != null && uiState.filterValue.isNotBlank() && !uiState.isSearching,
                        enabled = !uiState.isSearching,
                        minHeight = 46.dp,
                        modifier = Modifier.widthIn(min = 92.dp, max = 108.dp),
                        onClick = onAddFilter
                    )
                }

                if (uiState.inputMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = uiState.inputMessage,
                        color = Color(0xFF5E47D8),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PriceGradientButton(
                        text = "刷新数据",
                        active = !uiState.isSearching,
                        enabled = !uiState.isSearching,
                        minHeight = 54.dp,
                        modifier = Modifier.width(96.dp),
                        onClick = onRefreshSearch
                    )
                    PriceGradientButton(
                        text = if (uiState.isSearching) "正在查找..." else "开始查找",
                        active = !uiState.isSearching,
                        enabled = !uiState.isSearching,
                        minHeight = 54.dp,
                        modifier = Modifier.weight(1f),
                        onClick = onStartSearch
                    )
                }
            }
        }
    }
}

@Composable
private fun PriceColumnDropdown(
    columns: List<PriceFilterColumn>,
    selectedColumn: PriceFilterColumn?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onSelectColumn: (PriceFilterColumn) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .height(46.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.54f))
                .border(1.dp, Color.White.copy(alpha = 0.74f), RoundedCornerShape(16.dp))
                .clickable(enabled = enabled) { expanded = true }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = selectedColumn?.label ?: "选列名",
                color = if (selectedColumn == null) PriceLookupColors.Muted else PriceLookupColors.Ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = "选择列名",
                tint = PriceLookupColors.Ink,
                modifier = Modifier.width(18.dp)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            columns.forEach { column ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = column.label,
                            color = PriceLookupColors.Ink,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelectColumn(column)
                    }
                )
            }
        }
    }
}

@Composable
private fun PriceValueInput(
    value: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .heightIn(min = 46.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.54f))
            .border(1.dp, Color.White.copy(alpha = 0.74f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        enabled = enabled,
        textStyle = TextStyle(
            color = PriceLookupColors.Ink,
            fontSize = 15.sp,
            lineHeight = 20.sp
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        singleLine = true,
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = "填数值",
                        color = PriceLookupColors.Muted,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                innerTextField()
            }
        }
    )
}
