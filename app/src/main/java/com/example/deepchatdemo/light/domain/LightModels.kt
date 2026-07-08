package com.example.deepchatdemo.light.domain

import java.util.UUID

data class StationConfig(
    val stationId: String = "",
    val alias: String = "",
    val brokerHost: String = "",
    val brokerPort: Int = 1884,
    val username: String = "hightac_mqtt",
    val password: String = "hightac-light",
    val tlsEnabled: Boolean = false
) {
    val normalizedStationId: String
        get() = stationId.trim().uppercase()
}

data class LightBinding(
    val id: String = UUID.randomUUID().toString(),
    val itemCode: String,
    val itemName: String? = null,
    val tagId: String,
    val stationId: String,
    val shelfCode: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = createdAtMillis
) {
    val normalizedItemCode: String
        get() = itemCode.trim().uppercase()

    val normalizedTagId: String
        get() = tagId.trim().uppercase()
}

data class LightColor(
    val label: String,
    val red: Boolean,
    val green: Boolean,
    val blue: Boolean
) {
    companion object {
        val Red = LightColor("红", red = true, green = false, blue = false)
        val Green = LightColor("绿", red = false, green = true, blue = false)
        val Blue = LightColor("蓝", red = false, green = false, blue = true)
        val Cyan = LightColor("青", red = false, green = true, blue = true)
        val Purple = LightColor("紫", red = true, green = false, blue = true)
        val White = LightColor("白", red = true, green = true, blue = true)
        val Off = LightColor("灭", red = false, green = false, blue = false)

        val presets: List<LightColor> = listOf(Red, Green, Blue, Cyan, Purple, White)
    }
}

data class LightCommandSettings(
    val color: LightColor = LightColor.Red,
    val beep: Boolean = true,
    val flashing: Boolean = true,
    val durationSeconds: Int = 30
) {
    val timeSlots: Int
        get() = (durationSeconds / 5).coerceIn(1, 36)

    fun clipTimeUnits(): Int = timeSlots
}

data class LightStatus(
    val tagId: String,
    val stationId: String,
    val version: String? = null,
    val batteryVoltage: Double? = null,
    val batteryLevel: Int? = null,
    val group: Int? = null,
    val onlineAtMillis: Long? = null,
    val lastResultType: Int? = null
)

data class LightEvent(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val detail: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val isWarning: Boolean = false
)

fun batteryLevelFromRaw(rawBattery: Int?): Int? {
    val voltage = rawBattery?.let { it / 10.0 } ?: return null
    return when {
        voltage >= 3.0 -> 100
        voltage >= 2.9 -> 90
        voltage >= 2.8 -> 80
        voltage >= 2.7 -> 60
        voltage >= 2.6 -> 30
        voltage >= 2.5 -> 10
        else -> 0
    }
}
