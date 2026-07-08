package com.example.deepchatdemo.light.protocol

import com.example.deepchatdemo.light.domain.RgbColor
import org.json.JSONArray
import org.json.JSONObject

object TaskResultTypes {
    const val Button = 0xFD
    const val Communication = 0xFE
    const val Heartbeat = 0xFF
}

data class TaskResult(
    val stationId: String,
    val totalCount: Int,
    val sendCount: Int,
    val results: List<TaskItemResult>,
    val rawJson: String
) {
    init {
        EStationValidators.requireStationId(stationId)
    }

    companion object {
        fun parse(jsonText: String, expectedStationId: String? = null): TaskResult {
            val json = JSONObject(jsonText)
            val stationId = json.getString("ID")
            EStationValidators.requireStationId(stationId)
            if (expectedStationId != null) {
                EStationValidators.requireStationId(expectedStationId)
                require(stationId == expectedStationId) {
                    "Task result station ID does not match expected station."
                }
            }

            return TaskResult(
                stationId = stationId,
                totalCount = json.optInt("TotalCount"),
                sendCount = json.optInt("SendCount"),
                results = json.optJSONArray("Results").toTaskItemResults(),
                rawJson = jsonText
            )
        }
    }
}

data class TaskItemResult(
    val tagId: String,
    val version: String?,
    val resultType: Int,
    val rfPowerSend: Int?,
    val rfPowerRecv: Int?,
    val battery: Int?,
    val colors: List<RgbColor>,
    val group: Int?,
    val sequence: Int?,
    val rawJson: String
) {
    init {
        EStationValidators.requireTagId(tagId)
    }

    val batteryVoltage: Double?
        get() = battery?.let { it / 10.0 }

    val batteryLevelPercent: Int?
        get() = batteryVoltage?.let { voltage ->
            when {
                voltage >= 3.0 -> 100
                voltage >= 2.9 -> 90
                voltage >= 2.8 -> 80
                voltage >= 2.7 -> 60
                voltage >= 2.6 -> 30
                voltage >= 2.5 -> 10
                else -> 0
            }
        }
}

private fun JSONArray?.toTaskItemResults(): List<TaskItemResult> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            add(item.toTaskItemResult())
        }
    }
}

private fun JSONObject.toTaskItemResult(): TaskItemResult {
    return TaskItemResult(
        tagId = getString("TagID"),
        version = optionalString("Version"),
        resultType = optInt("ResultType"),
        rfPowerSend = optionalInt("RfPowerSend").withoutNoRfPower(),
        rfPowerRecv = optionalInt("RfPowerRecv").withoutNoRfPower(),
        battery = optionalInt("Battery"),
        colors = optJSONArray("Colors").toColors(),
        group = optionalInt("Group"),
        sequence = optionalInt("Sequence"),
        rawJson = toString()
    )
}

private fun JSONArray?.toColors(): List<RgbColor> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val color = optJSONObject(index) ?: continue
            add(
                RgbColor(
                    red = color.optBoolean("R"),
                    green = color.optBoolean("G"),
                    blue = color.optBoolean("B")
                )
            )
        }
    }
}

private fun JSONObject.optionalString(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name).takeIf { it.isNotBlank() }
}

private fun JSONObject.optionalInt(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    return optInt(name)
}

private fun Int?.withoutNoRfPower(): Int? {
    return takeUnless { it == NO_RF_POWER }
}

private const val NO_RF_POWER = -256
