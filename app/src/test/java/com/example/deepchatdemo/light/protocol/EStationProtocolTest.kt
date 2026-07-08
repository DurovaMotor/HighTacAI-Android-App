package com.example.deepchatdemo.light.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EStationProtocolTest {
    @Test
    fun validatorsRequireDocumentedUppercaseIds() {
        assertTrue(EStationValidators.isValidStationId(STATION_ID))
        assertTrue(EStationValidators.isValidTagId(TAG_ID))

        assertFalse(EStationValidators.isValidStationId(STATION_ID.lowercase()))
        assertFalse(EStationValidators.isValidTagId(TAG_ID.lowercase()))

        expectIllegalArgument { EStationValidators.requireStationId(STATION_ID.lowercase()) }
        expectIllegalArgument { EStationValidators.requireTagId(TAG_ID.lowercase()) }
    }

    @Test
    fun topicsKeepLeadingSlashAndParseKnownKinds() {
        assertEquals("/estation/$STATION_ID/result", EStationTopics.resultTopic(STATION_ID))
        assertEquals("/estation/$STATION_ID/heartbeat", EStationTopics.heartbeatTopic(STATION_ID))
        assertEquals("/estation/$STATION_ID/task", EStationTopics.taskTopic(STATION_ID))

        val parsed = EStationTopics.parse("/estation/$STATION_ID/result")
        assertEquals(STATION_ID, parsed?.stationId)
        assertEquals(EStationTopics.Kind.Result, parsed?.kind)
        assertTrue(
            EStationTopics.isExpectedTopic(
                topic = "/estation/$STATION_ID/heartbeat",
                stationId = STATION_ID,
                kind = EStationTopics.Kind.Heartbeat
            )
        )
    }

    @Test
    fun parseRejectsMalformedOrWildcardTopics() {
        assertNull(EStationTopics.parse("estation/$STATION_ID/result"))
        assertNull(EStationTopics.parse("/estation/$STATION_ID/+"))
        assertNull(EStationTopics.parse("/estation/${STATION_ID.lowercase()}/result"))
    }
}

private const val STATION_ID = "90A9F1234567"
private const val TAG_ID = "AD100000048F"

private fun expectIllegalArgument(block: () -> Unit) {
    try {
        block()
    } catch (_: IllegalArgumentException) {
        return
    }
    throw AssertionError("Expected IllegalArgumentException.")
}
