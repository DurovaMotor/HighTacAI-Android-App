package com.example.deepchatdemo.ui.light

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.ViewWeek
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.deepchatdemo.light.domain.LightBinding
import com.example.deepchatdemo.light.domain.LightColor
import com.example.deepchatdemo.light.domain.LightEvent
import com.example.deepchatdemo.platform.integration.LegacyBindingConflictReason
import com.example.deepchatdemo.platform.integration.LegacyBindingMigrationConflict
import com.example.deepchatdemo.platform.model.BrokerServiceState
import com.example.deepchatdemo.platform.model.StationStatus
import com.example.deepchatdemo.platform.network.PlatformEventConnectionState
import com.example.deepchatdemo.ui.scanner.BarcodeScannerDialog
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun LightFindingScreen(
    uiState: LightFindingUiState,
    modifier: Modifier = Modifier,
    onServerUrlChange: (String) -> Unit,
    onStationIdChange: (String) -> Unit,
    onItemCodeChange: (String) -> Unit,
    onTagIdChange: (String) -> Unit,
    onSaveAndConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onToggleSettings: () -> Unit,
    onBindCurrent: () -> Unit,
    onLightCurrentItem: () -> Unit,
    onTurnOffCurrentItem: () -> Unit,
    onTurnOffAll: () -> Unit,
    onUnbind: (LightBinding) -> Unit,
    onSelectColor: (LightColor) -> Unit,
    onSelectLegacyCandidate: (LightBinding) -> Unit,
    onConfirmLegacyMigration: () -> Unit,
    onAcknowledgeLegacyConflicts: () -> Unit,
    onDismissLegacyMigration: () -> Unit,
    onScreenActiveChanged: (Boolean) -> Unit
) {
    var showBindingScanner by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnScreenActiveChanged by rememberUpdatedState(onScreenActiveChanged)

    LaunchedEffect(uiState.canWrite) {
        if (!uiState.canWrite) showBindingScanner = false
    }

    DisposableEffect(lifecycleOwner) {
        var reportedActive = lifecycleOwner.lifecycle.currentState.isAtLeast(
            Lifecycle.State.STARTED
        )
        currentOnScreenActiveChanged(reportedActive)
        val observer = LifecycleEventObserver { source, _ ->
            val active = source.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (active != reportedActive) {
                reportedActive = active
                currentOnScreenActiveChanged(active)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (reportedActive) currentOnScreenActiveChanged(false)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 8.dp)
    ) {
        item {
            StatusPanel(
                uiState = uiState,
                onConnect = onSaveAndConnect,
                onToggleSettings = onToggleSettings,
                onDisconnect = onDisconnect
            )
        }
        item {
            AnimatedVisibility(visible = uiState.showSettings || !uiState.isConfigured) {
                SettingsPanel(
                    uiState = uiState,
                    onServerUrlChange = onServerUrlChange,
                    onStationIdChange = onStationIdChange,
                    onSaveAndConnect = onSaveAndConnect
                )
            }
        }
        uiState.legacyMigration?.let { migration ->
            item {
                LegacyMigrationPanel(
                    migration = migration,
                    canWrite = uiState.canWrite,
                    onSelectCandidate = onSelectLegacyCandidate,
                    onConfirm = onConfirmLegacyMigration,
                    onAcknowledgeConflicts = onAcknowledgeLegacyConflicts,
                    onDismiss = onDismissLegacyMigration
                )
            }
        }
        item {
            CommandPanel(
                uiState = uiState,
                onItemCodeChange = onItemCodeChange,
                onTagIdChange = onTagIdChange,
                onBindCurrent = onBindCurrent,
                onLightCurrentItem = onLightCurrentItem,
                onTurnOffCurrentItem = onTurnOffCurrentItem,
                onTurnOffAll = onTurnOffAll,
                onScanBindingTag = {
                    if (uiState.canWrite) showBindingScanner = true
                }
            )
        }
        item {
            LightSettingsPanel(
                uiState = uiState,
                onSelectColor = onSelectColor
            )
        }
        item { ListHeading("最近绑定") }
        if (uiState.bindings.isEmpty()) {
            item {
                EmptyHint(text = "服务器快照中还没有绑定记录。")
            }
        } else {
            items(uiState.bindings.take(8), key = { it.id }) { binding ->
                BindingRow(
                    binding = binding,
                    canWrite = uiState.canWrite,
                    onUnbind = { onUnbind(binding) }
                )
            }
        }
        item { ListHeading("操作记录") }
        if (uiState.events.isEmpty()) {
            item { EmptyHint(text = "平台连接、绑定和命令状态会显示在这里。") }
        } else {
            items(uiState.events.take(8), key = { it.id }) { event ->
                EventRow(event = event)
            }
        }
    }

    if (showBindingScanner) {
        BarcodeScannerDialog(
            title = "扫描绑定灯条 ID",
            onScanned = { scannedValue ->
                onTagIdChange(scannedValue.trim().uppercase())
                showBindingScanner = false
            },
            onDismiss = { showBindingScanner = false }
        )
    }
}

@Composable
private fun StatusPanel(
    uiState: LightFindingUiState,
    onConnect: () -> Unit,
    onToggleSettings: () -> Unit,
    onDisconnect: () -> Unit
) {
    LightGlassPanel {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPill(
                    icon = Icons.Rounded.Wifi,
                    text = uiState.backendStatus.label(),
                    color = uiState.backendStatus.statusColor()
                )
                Spacer(Modifier.weight(1f))
                RoundIconButton(
                    icon = Icons.Rounded.Settings,
                    contentDescription = "服务器配置",
                    onClick = onToggleSettings
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(
                    icon = Icons.Rounded.Link,
                    text = brokerLabel(uiState),
                    color = brokerColor(uiState)
                )
                StatusPill(
                    icon = Icons.Rounded.FlashOn,
                    text = stationLabel(uiState),
                    color = stationColor(uiState)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(
                    icon = Icons.Rounded.VerifiedUser,
                    text = uiState.enrollmentStatus.label(),
                    color = uiState.enrollmentStatus.statusColor()
                )
                StatusPill(
                    icon = Icons.Rounded.Sync,
                    text = uiState.eventConnectionState.label(),
                    color = uiState.eventConnectionState.statusColor()
                )
            }
            Spacer(Modifier.height(8.dp))
            StatusPill(
                icon = Icons.Rounded.History,
                text = cacheLabel(uiState),
                color = cacheColor(uiState)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = uiState.stationId.ifBlank { "HighTac Platform" },
                color = LightUiColors.Ink,
                fontSize = 22.sp,
                lineHeight = 27.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = uiState.serverUrl,
                color = LightUiColors.Muted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            uiState.inputMessage?.takeIf(String::isNotBlank)?.let { message ->
                Spacer(Modifier.height(10.dp))
                Text(
                    text = message,
                    color = LightUiColors.Blue,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LightGradientButton(
                    text = connectButtonLabel(uiState),
                    icon = Icons.Rounded.Link,
                    modifier = Modifier.weight(1f),
                    enabled = uiState.isConfigured && !uiState.operationInProgress,
                    onClick = onConnect
                )
                LightOutlineButton(
                    text = "断开",
                    icon = Icons.Rounded.LinkOff,
                    modifier = Modifier.weight(1f),
                    contentColor = LightUiColors.Danger,
                    enabled = uiState.connectionEnabled && !uiState.operationInProgress,
                    onClick = onDisconnect
                )
            }
        }
    }
}

@Composable
private fun SettingsPanel(
    uiState: LightFindingUiState,
    onServerUrlChange: (String) -> Unit,
    onStationIdChange: (String) -> Unit,
    onSaveAndConnect: () -> Unit
) {
    LightGlassPanel(alpha = 0.62f) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("服务器配置")
            CompactField(
                value = uiState.serverUrl,
                onValueChange = onServerUrlChange,
                label = "HighTac Platform 地址",
                placeholder = "http://192.168.1.105:8088"
            )
            CompactField(
                value = uiState.stationId,
                onValueChange = onStationIdChange,
                label = "绑定基站 SN",
                placeholder = "同步后自动选择"
            )
            LightGradientButton(
                text = "保存并连接",
                icon = Icons.Rounded.Link,
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.operationInProgress,
                onClick = onSaveAndConnect
            )
        }
    }
}

@Composable
private fun LegacyMigrationPanel(
    migration: LegacyBindingMigrationUiState,
    canWrite: Boolean,
    onSelectCandidate: (LightBinding) -> Unit,
    onConfirm: () -> Unit,
    onAcknowledgeConflicts: () -> Unit,
    onDismiss: () -> Unit
) {
    LightGlassPanel(alpha = 0.62f) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("旧绑定迁移预览")
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = onDismiss,
                    enabled = !migration.isPreviewing && !migration.isApplying,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.LinkOff,
                        contentDescription = "稍后处理旧绑定",
                        tint = LightUiColors.Muted
                    )
                }
            }
            Text(
                text = if (migration.isPreviewing) {
                    "正在由服务端分类 ${migration.preparation.totalLegacyBindings} 条旧绑定"
                } else {
                    "可迁移 ${migration.plan.migratable.size} / " +
                        "相同 ${migration.plan.identical.size} / " +
                        "冲突 ${migration.plan.conflicts.size}"
                },
                color = LightUiColors.Muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            migration.plan.migratable.forEach { binding ->
                MigrationBindingLine(binding = binding, marker = "可迁移")
            }
            migration.plan.conflicts.forEach { conflict ->
                MigrationConflictLine(
                    conflict = conflict,
                    selected = migration.isSelected(conflict.legacy),
                    enabled = !migration.isPreviewing && !migration.isApplying,
                    onSelect = { onSelectCandidate(conflict.legacy) }
                )
            }
            if (migration.isComplete && migration.plan.conflicts.isNotEmpty()) {
                LightOutlineButton(
                    text = "确认保留冲突",
                    icon = Icons.Rounded.History,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !migration.isApplying,
                    onClick = onAcknowledgeConflicts
                )
            } else if (migration.isComplete) {
                LightOutlineButton(
                    text = "完成",
                    icon = Icons.Rounded.History,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !migration.isApplying,
                    onClick = onDismiss
                )
            } else {
                LightGradientButton(
                    text = when {
                        migration.isPreviewing -> "正在预览"
                        migration.preview == null && migration.preparation.records.isNotEmpty() ->
                            "重新预览"
                        migration.pendingCount == 0 -> "确认预览"
                        else -> "确认迁移 ${migration.pendingCount} 条"
                    },
                    icon = Icons.Rounded.Link,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !migration.isPreviewing &&
                        !migration.isApplying &&
                        (migration.preview == null || canWrite),
                    onClick = onConfirm
                )
            }
        }
    }
}

@Composable
private fun MigrationBindingLine(binding: LightBinding, marker: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = marker,
            color = LightUiColors.Green,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(58.dp)
        )
        Text(
            text = "${binding.itemCode} / ${binding.tagId}",
            color = LightUiColors.Ink,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MigrationConflictLine(
    conflict: LegacyBindingMigrationConflict,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit
) {
    val selectable = conflict.reason == LegacyBindingConflictReason.DUPLICATE_LEGACY_TAG &&
        conflict.remoteBinding == null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selectable) {
                    Modifier.clickable(enabled = enabled, onClick = onSelect)
                } else {
                    Modifier
                }
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectable) {
            RadioButton(
                selected = selected,
                onClick = onSelect,
                enabled = enabled
            )
        } else {
            Box(modifier = Modifier.width(48.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${conflict.legacy.itemCode} / ${conflict.legacy.tagId}",
                color = LightUiColors.Ink,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = conflict.label(),
                color = LightUiColors.Danger,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CommandPanel(
    uiState: LightFindingUiState,
    onItemCodeChange: (String) -> Unit,
    onTagIdChange: (String) -> Unit,
    onBindCurrent: () -> Unit,
    onLightCurrentItem: () -> Unit,
    onTurnOffCurrentItem: () -> Unit,
    onTurnOffAll: () -> Unit,
    onScanBindingTag: () -> Unit
) {
    LightGlassPanel {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("扫码绑定与寻物")
            CompactField(
                value = uiState.itemCode,
                onValueChange = onItemCodeChange,
                label = "产品编码",
                placeholder = "输入或扫描一维条形码"
            )
            CompactField(
                value = uiState.tagId,
                onValueChange = onTagIdChange,
                label = "灯条 ID",
                placeholder = "AD1...",
                trailingIcon = {
                    ScannerIconButton(
                        enabled = uiState.canWrite,
                        onClick = onScanBindingTag
                    )
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LightGradientButton(
                    text = "绑定",
                    icon = Icons.Rounded.Link,
                    modifier = Modifier.weight(1f),
                    enabled = uiState.canWrite,
                    onClick = onBindCurrent
                )
                LightGradientButton(
                    text = "亮灯",
                    icon = Icons.Rounded.FlashOn,
                    modifier = Modifier.weight(1f),
                    enabled = uiState.canWrite,
                    onClick = onLightCurrentItem
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LightOutlineButton(
                    text = "产品灭灯",
                    icon = Icons.Rounded.LinkOff,
                    modifier = Modifier.weight(1f),
                    enabled = uiState.canWrite,
                    onClick = onTurnOffCurrentItem
                )
                LightOutlineButton(
                    text = "全部灭灯",
                    icon = Icons.Rounded.LinkOff,
                    modifier = Modifier.weight(1f),
                    contentColor = LightUiColors.Danger,
                    enabled = uiState.canWrite,
                    onClick = onTurnOffAll
                )
            }
        }
    }
}

@Composable
private fun LightSettingsPanel(
    uiState: LightFindingUiState,
    onSelectColor: (LightColor) -> Unit
) {
    LightGlassPanel(alpha = 0.52f, elevation = 10.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("亮灯颜色")
                Spacer(Modifier.weight(1f))
                Text(
                    text = "5 秒 / 蜂鸣 / 闪烁",
                    color = LightUiColors.Muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LightColor.presets.forEach { color ->
                    ColorDot(
                        color = color,
                        selected = color == uiState.commandSettings.color,
                        onClick = { onSelectColor(color) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BindingRow(
    binding: LightBinding,
    canWrite: Boolean,
    onUnbind: () -> Unit
) {
    LightGlassPanel(
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        alpha = 0.48f,
        elevation = 6.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = binding.itemCode,
                    color = LightUiColors.Ink,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "${binding.tagId} / ${binding.stationId}",
                    color = LightUiColors.Muted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            LightOutlineButton(
                text = "解绑",
                icon = Icons.Rounded.LinkOff,
                modifier = Modifier.width(86.dp),
                contentColor = LightUiColors.Danger,
                enabled = canWrite,
                onClick = onUnbind
            )
        }
    }
}

@Composable
private fun EventRow(event: LightEvent) {
    LightGlassPanel(
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        alpha = 0.44f,
        elevation = 5.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.History,
                contentDescription = null,
                tint = if (event.isWarning) LightUiColors.Warning else LightUiColors.Blue,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.title,
                    color = LightUiColors.Ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = event.detail,
                    color = LightUiColors.Muted,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    LightGlassPanel(alpha = 0.38f, elevation = 4.dp) {
        Text(
            text = text,
            color = LightUiColors.Muted,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
    }
}

@Composable
private fun ListHeading(text: String) {
    Text(
        text = text,
        color = LightUiColors.Ink,
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
    )
}

@Composable
private fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    trailingIcon: (@Composable () -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        shape = RoundedCornerShape(18.dp),
        trailingIcon = trailingIcon
    )
}

@Composable
private fun ScannerIconButton(enabled: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.ViewWeek,
            contentDescription = "扫描灯条 ID",
            tint = LightUiColors.Blue.copy(alpha = if (enabled) 1f else 0.38f),
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = LightUiColors.Ink,
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun StatusPill(
    icon: ImageVector,
    text: String,
    color: Color
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.50f))
            .border(1.dp, Color.White.copy(alpha = 0.76f), RoundedCornerShape(18.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(5.dp))
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            color = LightUiColors.Ink,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun ColorDot(
    color: LightColor,
    selected: Boolean,
    onClick: () -> Unit
) {
    val dotColor = Color(
        red = if (color.red) 0xF4 else 0x38,
        green = if (color.green) 0xD8 else 0x55,
        blue = if (color.blue) 0xFF else 0x66
    )
    Box(
        modifier = Modifier
            .size(34.dp)
            .shadow(if (selected) 9.dp else 2.dp, CircleShape)
            .clip(CircleShape)
            .background(dotColor)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = Color.White.copy(alpha = if (selected) 0.95f else 0.62f),
                shape = CircleShape
            )
            .clickable(onClick = onClick)
    )
}

@Composable
private fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.58f))
            .border(1.dp, Color.White.copy(alpha = 0.80f), CircleShape)
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = LightUiColors.Ink,
            modifier = Modifier.size(21.dp)
        )
    }
}

@Composable
private fun LightGradientButton(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    ActionButton(
        text = text,
        icon = icon,
        modifier = modifier,
        brush = LightUiColors.actionBrush(),
        textColor = Color.White,
        elevation = 12.dp,
        enabled = enabled,
        onClick = onClick
    )
}

@Composable
private fun LightOutlineButton(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    contentColor: Color = LightUiColors.Ink,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    ActionButton(
        text = text,
        icon = icon,
        modifier = modifier,
        brush = Brush.linearGradient(
            listOf(
                contentColor.copy(alpha = 0.12f),
                Color.White.copy(alpha = 0.70f)
            )
        ),
        textColor = contentColor,
        elevation = 5.dp,
        borderColor = contentColor.copy(alpha = 0.24f),
        enabled = enabled,
        onClick = onClick
    )
}

@Composable
private fun ActionButton(
    text: String,
    icon: ImageVector,
    modifier: Modifier,
    brush: Brush,
    textColor: Color,
    elevation: Dp,
    borderColor: Color? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .height(48.dp)
            .alpha(if (enabled) 1f else 0.42f)
            .shadow(if (enabled) elevation else 0.dp, shape)
            .clip(shape)
            .background(brush)
            .then(
                if (borderColor != null) Modifier.border(1.dp, borderColor, shape) else Modifier
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = textColor, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            text = text,
            color = textColor,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LightGlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    contentPadding: PaddingValues = PaddingValues(15.dp),
    alpha: Float = 0.56f,
    elevation: Dp = 12.dp,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = elevation,
                shape = shape,
                ambientColor = LightUiColors.Blue.copy(alpha = 0.08f),
                spotColor = LightUiColors.Purple.copy(alpha = 0.12f)
            )
            .clip(shape)
            .background(LightUiColors.glassBrush(alpha))
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.88f),
                        Color.White.copy(alpha = 0.25f),
                        LightUiColors.Cyan.copy(alpha = 0.16f)
                    )
                ),
                shape = shape
            )
            .padding(contentPadding),
        content = content
    )
}

private fun connectButtonLabel(uiState: LightFindingUiState): String {
    return when {
        uiState.isAutoRegistering -> "检查注册"
        uiState.enrollmentStatus == DeviceEnrollmentUiStatus.APPROVED -> "刷新"
        else -> "登记设备"
    }
}

private fun brokerLabel(uiState: LightFindingUiState): String {
    val status = uiState.brokerStatus ?: return "Broker 未知"
    return when {
        uiState.brokerSnapshotStale -> "Broker 状态过期"
        uiState.isBrokerReady -> "Broker 就绪"
        status.serviceState == BrokerServiceState.RUNNING -> "Broker 未就绪"
        else -> "Broker ${status.serviceState.name}"
    }
}

private fun brokerColor(uiState: LightFindingUiState): Color {
    val status = uiState.brokerStatus ?: return LightUiColors.Muted
    return when {
        uiState.brokerSnapshotStale -> LightUiColors.Warning
        uiState.isBrokerReady -> LightUiColors.Green
        status.serviceState == BrokerServiceState.STARTING ||
            status.serviceState == BrokerServiceState.STOPPING -> LightUiColors.Warning
        else -> LightUiColors.Danger
    }
}

private fun cacheLabel(uiState: LightFindingUiState): String {
    val synchronizedAt = uiState.cacheSynchronizedAt ?: return "缓存未同步"
    val formatted = CACHE_TIME_FORMATTER.format(synchronizedAt)
    return if (uiState.cacheStale) "缓存过期 $formatted" else "已同步 $formatted"
}

private fun cacheColor(uiState: LightFindingUiState): Color = when {
    uiState.cacheSynchronizedAt == null -> LightUiColors.Muted
    uiState.cacheStale -> LightUiColors.Warning
    else -> LightUiColors.Green
}

private fun stationLabel(uiState: LightFindingUiState): String {
    val station = uiState.selectedStation ?: return "基站未知"
    return when (station.status) {
        StationStatus.ONLINE -> "基站在线"
        StationStatus.STALE -> "基站延迟"
        StationStatus.OFFLINE -> "基站离线"
        StationStatus.UNKNOWN -> "基站未知"
    }
}

private fun stationColor(uiState: LightFindingUiState): Color {
    return when (uiState.selectedStation?.status) {
        StationStatus.ONLINE -> LightUiColors.Green
        StationStatus.STALE -> LightUiColors.Warning
        StationStatus.OFFLINE -> LightUiColors.Danger
        else -> LightUiColors.Muted
    }
}

private fun PlatformBackendUiStatus.label(): String {
    return when (this) {
        PlatformBackendUiStatus.UNKNOWN -> "API 未连接"
        PlatformBackendUiStatus.CHECKING -> "API 连接中"
        PlatformBackendUiStatus.AVAILABLE -> "API 可达"
        PlatformBackendUiStatus.UNAVAILABLE -> "API 不可达"
        PlatformBackendUiStatus.CONTRACT_INCOMPATIBLE -> "API 合同不兼容"
    }
}

private fun PlatformBackendUiStatus.statusColor(): Color {
    return when (this) {
        PlatformBackendUiStatus.AVAILABLE -> LightUiColors.Green
        PlatformBackendUiStatus.CHECKING -> LightUiColors.Warning
        PlatformBackendUiStatus.UNAVAILABLE,
        PlatformBackendUiStatus.CONTRACT_INCOMPATIBLE -> LightUiColors.Danger
        PlatformBackendUiStatus.UNKNOWN -> LightUiColors.Muted
    }
}

private fun DeviceEnrollmentUiStatus.label(): String {
    return when (this) {
        DeviceEnrollmentUiStatus.NOT_STARTED -> "设备未登记"
        DeviceEnrollmentUiStatus.REGISTERING -> "设备登记中"
        DeviceEnrollmentUiStatus.AUTO_REGISTERING -> "设备自动注册中"
        DeviceEnrollmentUiStatus.APPROVED -> "设备已注册"
        DeviceEnrollmentUiStatus.REJECTED -> "设备被拒绝"
        DeviceEnrollmentUiStatus.EXPIRED -> "登记已过期"
        DeviceEnrollmentUiStatus.REVOKED -> "凭据刷新中"
    }
}

private fun DeviceEnrollmentUiStatus.statusColor(): Color {
    return when (this) {
        DeviceEnrollmentUiStatus.APPROVED -> LightUiColors.Green
        DeviceEnrollmentUiStatus.REGISTERING,
        DeviceEnrollmentUiStatus.AUTO_REGISTERING -> LightUiColors.Warning
        DeviceEnrollmentUiStatus.REJECTED,
        DeviceEnrollmentUiStatus.EXPIRED,
        DeviceEnrollmentUiStatus.REVOKED -> LightUiColors.Danger
        DeviceEnrollmentUiStatus.NOT_STARTED -> LightUiColors.Muted
    }
}

private fun PlatformEventConnectionState.label(): String {
    return when (this) {
        PlatformEventConnectionState.Stopped -> "实时未连接"
        is PlatformEventConnectionState.Connecting -> "实时连接中"
        is PlatformEventConnectionState.Connected -> "实时已连接"
        is PlatformEventConnectionState.ReconnectScheduled -> "实时重连中"
        PlatformEventConnectionState.AuthenticationRequired -> "实时鉴权恢复中"
    }
}

private fun PlatformEventConnectionState.statusColor(): Color {
    return when (this) {
        is PlatformEventConnectionState.Connected -> LightUiColors.Green
        is PlatformEventConnectionState.Connecting,
        is PlatformEventConnectionState.ReconnectScheduled -> LightUiColors.Warning
        PlatformEventConnectionState.AuthenticationRequired -> LightUiColors.Danger
        PlatformEventConnectionState.Stopped -> LightUiColors.Muted
    }
}

private fun LegacyBindingMigrationConflict.label(): String {
    return when (reason) {
        LegacyBindingConflictReason.TAG_BOUND_TO_DIFFERENT_PRODUCT ->
            "服务端已绑定 ${authoritativeProductCode ?: remoteBinding?.productCode ?: "其他产品"}"
        LegacyBindingConflictReason.STATION_MISMATCH ->
            "服务端基站为 ${authoritativeStationId ?: remoteBinding?.stationId ?: "其他基站"}"
        LegacyBindingConflictReason.STATION_NOT_FOUND -> "服务端不存在该基站"
        LegacyBindingConflictReason.INVALID_LEGACY_RECORD -> "旧记录格式无效"
        LegacyBindingConflictReason.BATCH_LIMIT_EXCEEDED -> "旧记录超过单批 2000 条上限"
        LegacyBindingConflictReason.DUPLICATE_LEGACY_TAG -> "旧数据中同一 tag 对应多个产品，请单选"
        LegacyBindingConflictReason.SERVER_REJECTED ->
            "服务端拒绝迁移${serverErrorCode?.let { " / $it" }.orEmpty()}"
    }
}

private val CACHE_TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    .withZone(ZoneId.systemDefault())

private object LightUiColors {
    val Ink = Color(0xFF07143A)
    val Muted = Color(0xFF6F7897)
    val Cyan = Color(0xFF35D7F6)
    val Blue = Color(0xFF3F86FF)
    val Purple = Color(0xFF7A48FF)
    val Green = Color(0xFF20C657)
    val Warning = Color(0xFFFFB23E)
    val Danger = Color(0xFFFF5A7A)

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
}
