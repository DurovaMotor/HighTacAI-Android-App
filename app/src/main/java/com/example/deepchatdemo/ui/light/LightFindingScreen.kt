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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ViewWeek
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.deepchatdemo.light.domain.LightBinding
import com.example.deepchatdemo.light.domain.LightColor
import com.example.deepchatdemo.light.domain.LightEvent
import com.example.deepchatdemo.light.mqtt.MqttConnectionState
import com.example.deepchatdemo.ui.scanner.BarcodeScannerDialog
import kotlin.math.roundToInt

@Composable
fun LightFindingScreen(
    uiState: LightFindingUiState,
    modifier: Modifier = Modifier,
    onStationIdChange: (String) -> Unit,
    onBrokerHostChange: (String) -> Unit,
    onBrokerPortChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onItemCodeChange: (String) -> Unit,
    onTagIdChange: (String) -> Unit,
    onSaveAndConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onToggleSettings: () -> Unit,
    onBindCurrent: () -> Unit,
    onLightCurrentItem: () -> Unit,
    onSelectColor: (LightColor) -> Unit,
    onBeepChange: (Boolean) -> Unit,
    onFlashingChange: (Boolean) -> Unit,
    onDurationChange: (Int) -> Unit
) {
    var showBindingScanner by remember { mutableStateOf(false) }

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
                    onStationIdChange = onStationIdChange,
                    onBrokerHostChange = onBrokerHostChange,
                    onBrokerPortChange = onBrokerPortChange,
                    onUsernameChange = onUsernameChange,
                    onPasswordChange = onPasswordChange,
                    onSaveAndConnect = onSaveAndConnect
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
                onScanBindingTag = { showBindingScanner = true }
            )
        }
        item {
            LightSettingsPanel(
                uiState = uiState,
                onSelectColor = onSelectColor,
                onBeepChange = onBeepChange,
                onFlashingChange = onFlashingChange,
                onDurationChange = onDurationChange
            )
        }
        item {
            Text(
                text = "最近绑定",
                color = LightUiColors.Ink,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        }
        if (uiState.bindings.isEmpty()) {
            item {
                EmptyHint(text = "还没有绑定记录，先输入产品编码和灯条 ID。")
            }
        } else {
            items(uiState.bindings.take(5), key = { it.id }) { binding ->
                BindingRow(binding = binding)
            }
        }
        item {
            Text(
                text = "操作记录",
                color = LightUiColors.Ink,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        }
        if (uiState.events.isEmpty()) {
            item { EmptyHint(text = "连接、点亮、回执会显示在这里。") }
        } else {
            items(uiState.events.take(6), key = { it.id }) { event ->
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
    val canConnect = uiState.isConfigured && uiState.connectionState.canConnect()
    val canDisconnect = uiState.connectionState.canDisconnect()

    LightGlassPanel {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPill(
                    icon = Icons.Rounded.Wifi,
                    text = uiState.connectionState.label(),
                    color = uiState.connectionState.statusColor()
                )
                Spacer(Modifier.width(8.dp))
                StatusPill(
                    icon = Icons.Rounded.FlashOn,
                    text = if (uiState.isStationOnline) "基站在线" else "基站未上线",
                    color = if (uiState.isStationOnline) LightUiColors.Green else LightUiColors.Warning
                )
                Spacer(Modifier.weight(1f))
                RoundIconButton(icon = Icons.Rounded.Settings, onClick = onToggleSettings)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = uiState.stationId.ifBlank { "未配置基站 SN" },
                color = LightUiColors.Ink,
                fontSize = 22.sp,
                lineHeight = 27.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = heartbeatSummary(uiState),
                color = LightUiColors.Muted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            uiState.inputMessage?.takeIf { it.isNotBlank() }?.let { message ->
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
                    text = "连接",
                    icon = Icons.Rounded.Link,
                    modifier = Modifier.weight(1f),
                    enabled = canConnect,
                    onClick = onConnect
                )
                LightOutlineButton(
                    text = "断开",
                    icon = Icons.Rounded.LinkOff,
                    modifier = Modifier.weight(1f),
                    contentColor = LightUiColors.Danger,
                    enabled = canDisconnect,
                    onClick = onDisconnect
                )
            }
        }
    }
}

@Composable
private fun SettingsPanel(
    uiState: LightFindingUiState,
    onStationIdChange: (String) -> Unit,
    onBrokerHostChange: (String) -> Unit,
    onBrokerPortChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSaveAndConnect: () -> Unit
) {
    LightGlassPanel(alpha = 0.62f) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("基站 / MQTT")
            CompactField(
                value = uiState.stationId,
                onValueChange = onStationIdChange,
                label = "基站 SN",
                placeholder = "90A9F..."
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CompactField(
                    value = uiState.brokerHost,
                    onValueChange = onBrokerHostChange,
                    label = "Broker 地址",
                    placeholder = "现场默认 192.168.1.105",
                    modifier = Modifier.weight(1f)
                )
                CompactField(
                    value = uiState.brokerPort,
                    onValueChange = onBrokerPortChange,
                    label = "端口",
                    placeholder = "1884",
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.width(92.dp)
                )
            }
            Text(
                text = "Durova-5G 默认使用 192.168.1.105:1884。切换 Wi-Fi 后请改为 MQTT 电脑在新网络中的局域网 IP；不要填 127.0.0.1、localhost 或 10.0.2.2。",
                color = LightUiColors.Muted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CompactField(
                    value = uiState.username,
                    onValueChange = onUsernameChange,
                    label = "用户名",
                    placeholder = "hightac",
                    modifier = Modifier.weight(1f)
                )
                CompactField(
                    value = uiState.password,
                    onValueChange = onPasswordChange,
                    label = "密码",
                    placeholder = "hightac-light",
                    modifier = Modifier.weight(1f)
                )
            }
            LightGradientButton(
                text = "保存并连接",
                icon = Icons.Rounded.Link,
                modifier = Modifier.fillMaxWidth(),
                onClick = onSaveAndConnect
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
    onScanBindingTag: () -> Unit
) {
    LightGlassPanel {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("扫码绑定")
            CompactField(
                value = uiState.itemCode,
                onValueChange = onItemCodeChange,
                label = "产品编码",
                placeholder = "输入或扫码产品编码"
            )
            CompactField(
                value = uiState.tagId,
                onValueChange = onTagIdChange,
                label = "灯条 ID",
                placeholder = "AD1...",
                trailingIcon = { ScannerIconButton(onClick = onScanBindingTag) }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LightGradientButton(
                    text = "绑定",
                    icon = Icons.Rounded.Link,
                    modifier = Modifier.weight(1f),
                    onClick = onBindCurrent
                )
                LightGradientButton(
                    text = "亮灯",
                    icon = Icons.Rounded.FlashOn,
                    modifier = Modifier.weight(1f),
                    onClick = onLightCurrentItem
                )
            }
        }
    }
}

@Composable
private fun LightSettingsPanel(
    uiState: LightFindingUiState,
    onSelectColor: (LightColor) -> Unit,
    onBeepChange: (Boolean) -> Unit,
    onFlashingChange: (Boolean) -> Unit,
    onDurationChange: (Int) -> Unit
) {
    LightGlassPanel(alpha = 0.52f, elevation = 10.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("亮灯参数")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LightColor.presets.forEach { color ->
                    ColorDot(
                        color = color,
                        selected = color == uiState.commandSettings.color,
                        onClick = { onSelectColor(color) }
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToggleWithLabel("蜂鸣", uiState.commandSettings.beep, onBeepChange)
                Spacer(Modifier.width(14.dp))
                ToggleWithLabel("闪烁", uiState.commandSettings.flashing, onFlashingChange)
            }
            Text(
                text = "时长 ${uiState.commandSettings.durationSeconds} 秒",
                color = LightUiColors.Ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = uiState.commandSettings.durationSeconds.toFloat(),
                onValueChange = { onDurationChange((it / 5f).roundToInt() * 5) },
                valueRange = 5f..180f,
                steps = 34
            )
        }
    }
}

@Composable
private fun BindingRow(binding: LightBinding) {
    LightGlassPanel(
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        alpha = 0.48f,
        elevation = 6.dp
    ) {
        Column {
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
private fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
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
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        trailingIcon = trailingIcon
    )
}

@Composable
private fun ScannerIconButton(onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.ViewWeek,
            contentDescription = "扫描灯条 ID",
            tint = LightUiColors.Blue,
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
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(5.dp))
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
private fun ToggleWithLabel(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            color = LightUiColors.Ink,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.width(6.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.58f))
            .border(1.dp, Color.White.copy(alpha = 0.80f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = LightUiColors.Ink, modifier = Modifier.size(21.dp))
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
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = textColor, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            color = textColor,
            fontSize = 14.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
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

private fun heartbeatSummary(uiState: LightFindingUiState): String {
    val broker = if (uiState.brokerHost.isBlank()) {
        "Broker 未配置"
    } else {
        "${uiState.brokerHost}:${uiState.brokerPort}"
    }
    val heartbeat = uiState.lastHeartbeat ?: return broker
    return "$broker / 固件 ${heartbeat.appVersion.ifBlank { "--" }} / 待发 ${heartbeat.sendCount}"
}

private fun MqttConnectionState.label(): String {
    return when (this) {
        MqttConnectionState.Idle -> "未连接"
        MqttConnectionState.Connecting -> "连接中"
        MqttConnectionState.Subscribing -> "订阅中"
        MqttConnectionState.Ready -> "Broker 已连"
        MqttConnectionState.Reconnecting -> "重连中"
        MqttConnectionState.Disconnected -> "已断开"
        is MqttConnectionState.Failed -> "连接失败"
    }
}

private fun MqttConnectionState.statusColor(): Color {
    return when (this) {
        MqttConnectionState.Ready -> LightUiColors.Green
        MqttConnectionState.Connecting,
        MqttConnectionState.Subscribing,
        MqttConnectionState.Reconnecting -> LightUiColors.Warning
        is MqttConnectionState.Failed -> LightUiColors.Danger
        else -> LightUiColors.Muted
    }
}

private fun MqttConnectionState.canConnect(): Boolean {
    return this is MqttConnectionState.Idle ||
        this is MqttConnectionState.Disconnected ||
        this is MqttConnectionState.Failed
}

private fun MqttConnectionState.canDisconnect(): Boolean {
    return this is MqttConnectionState.Connecting ||
        this is MqttConnectionState.Subscribing ||
        this is MqttConnectionState.Ready ||
        this is MqttConnectionState.Reconnecting
}

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
