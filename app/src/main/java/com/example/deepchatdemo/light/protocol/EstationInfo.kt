package com.example.deepchatdemo.light.protocol

import org.json.JSONArray
import org.json.JSONObject

data class EstationInfo(
    val stationId: String,
    val mac: String,
    val alias: String?,
    val serverAddress: String,
    val parameters: List<String>,
    val heartbeatSeconds: Int,
    val appVersion: String,
    val totalCount: Int,
    val sendCount: Int,
    val rawJson: String
) {
    init {
        EStationValidators.requireStationId(stationId)
        require(heartbeatSeconds > 0) { "Heartbeat seconds must be positive." }
    }

    val heartbeatTimeoutMillis: Long
        get() = heartbeatSeconds * 3_000L

    fun isHeartbeatFresh(lastHeartbeatAtMillis: Long, nowMillis: Long): Boolean {
        return nowMillis - lastHeartbeatAtMillis <= heartbeatTimeoutMillis
    }

    companion object {
        fun parse(jsonText: String, expectedStationId: String? = null): EstationInfo {
            val json = JSONObject(jsonText)
            val stationId = json.getString("ID")
            EStationValidators.requireStationId(stationId)
            if (expectedStationId != null) {
                EStationValidators.requireStationId(expectedStationId)
                require(stationId == expectedStationId) {
                    "Heartbeat station ID does not match expected station."
                }
            }

            return EstationInfo(
                stationId = stationId,
                mac = json.optString("MAC"),
                alias = json.optionalString("Alias"),
                serverAddress = json.optString("ServerAddress"),
                parameters = json.optJSONArray("Parameters").toStringList(),
                heartbeatSeconds = json.optInt("Heartbeat", DEFAULT_HEARTBEAT_SECONDS),
                appVersion = json.optString("AppVersion"),
                totalCount = json.optInt("TotalCount"),
                sendCount = json.optInt("SendCount"),
                rawJson = jsonText
            )
        }
    }
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            if (!isNull(index)) add(optString(index))
        }
    }
}

private fun JSONObject.optionalString(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name).takeIf { it.isNotBlank() }
}

private const val DEFAULT_HEARTBEAT_SECONDS = 20
