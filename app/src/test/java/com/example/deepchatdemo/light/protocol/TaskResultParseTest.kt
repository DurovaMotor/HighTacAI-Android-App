package com.example.deepchatdemo.light.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskResultParseTest {
    @Test
    fun parsesTaskResultWithOptionalSequenceAndRfNoData() {
        val result = TaskResult.parse(
            jsonText = """
                {
                  "ID": "$STATION_ID",
                  "TotalCount": 2,
                  "SendCount": 1,
                  "Results": [
                    {
                      "TagID": "$TAG_ID",
                      "Version": "1.6.7",
                      "ResultType": 254,
                      "RfPowerSend": -256,
                      "RfPowerRecv": -70,
                      "Battery": 30,
                      "Colors": [{"R": true, "G": false, "B": false}],
                      "Group": 10,
                      "Sequence": 7
                    }
                  ]
                }
            """.trimIndent(),
            expectedStationId = STATION_ID
        )

        assertEquals(STATION_ID, result.stationId)
        assertEquals(2, result.totalCount)
        assertEquals(1, result.sendCount)

        val item = result.results.single()
        assertEquals(TAG_ID, item.tagId)
        assertEquals("1.6.7", item.version)
        assertEquals(TaskResultTypes.Communication, item.resultType)
        assertNull(item.rfPowerSend)
        assertEquals(-70, item.rfPowerRecv)
        assertEquals(30, item.battery)
        assertEquals(3.0, item.batteryVoltage ?: 0.0, 0.0)
        assertEquals(100, item.batteryLevelPercent)
        assertEquals(10, item.group)
        assertEquals(7, item.sequence)
        assertTrue(item.colors.single().red)
    }

    @Test
    fun parseRejectsMismatchedStationId() {
        expectIllegalArgument {
            TaskResult.parse(
                jsonText = """{"ID":"$STATION_ID","TotalCount":0,"SendCount":0,"Results":[]}""",
                expectedStationId = OTHER_STATION_ID
            )
        }
    }
}

private const val STATION_ID = "90A9F1234567"
private const val OTHER_STATION_ID = "90A9F7654321"
private const val TAG_ID = "AD100000048F"

private fun expectIllegalArgument(block: () -> Unit) {
    try {
        block()
    } catch (_: IllegalArgumentException) {
        return
    }
    throw AssertionError("Expected IllegalArgumentException.")
}
