package com.example.deepchatdemo.light.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EstationInfoParseTest {
    @Test
    fun parsesHeartbeatPayloadAndTimeoutWindow() {
        val info = EstationInfo.parse(
            jsonText = """
                {
                  "ID": "$STATION_ID",
                  "MAC": "AA:BB:CC:DD:EE:FF",
                  "Alias": "Dock A",
                  "ServerAddress": "192.168.1.8:1883",
                  "Parameters": ["mqtt_user", "mqtt_password"],
                  "Heartbeat": 20,
                  "AppVersion": "1.6.7",
                  "TotalCount": 3,
                  "SendCount": 1
                }
            """.trimIndent(),
            expectedStationId = STATION_ID
        )

        assertEquals(STATION_ID, info.stationId)
        assertEquals("AA:BB:CC:DD:EE:FF", info.mac)
        assertEquals("Dock A", info.alias)
        assertEquals("192.168.1.8:1883", info.serverAddress)
        assertEquals(listOf("mqtt_user", "mqtt_password"), info.parameters)
        assertEquals(20, info.heartbeatSeconds)
        assertEquals("1.6.7", info.appVersion)
        assertEquals(60_000L, info.heartbeatTimeoutMillis)
        assertTrue(info.isHeartbeatFresh(lastHeartbeatAtMillis = 1_000L, nowMillis = 61_000L))
        assertFalse(info.isHeartbeatFresh(lastHeartbeatAtMillis = 1_000L, nowMillis = 61_001L))
    }

    @Test
    fun parseRejectsLowercaseStationId() {
        expectIllegalArgument {
            EstationInfo.parse(
                jsonText = """
                    {
                      "ID": "${STATION_ID.lowercase()}",
                      "Heartbeat": 20
                    }
                """.trimIndent()
            )
        }
    }
}

private const val STATION_ID = "90A9F1234567"

private fun expectIllegalArgument(block: () -> Unit) {
    try {
        block()
    } catch (_: IllegalArgumentException) {
        return
    }
    throw AssertionError("Expected IllegalArgumentException.")
}
