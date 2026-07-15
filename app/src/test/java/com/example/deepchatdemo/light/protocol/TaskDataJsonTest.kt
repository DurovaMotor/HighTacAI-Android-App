package com.example.deepchatdemo.light.protocol

import com.example.deepchatdemo.light.domain.LightCommandSettings
import com.example.deepchatdemo.light.domain.LightColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskDataJsonTest {
    @Test
    fun lightCommandDefaultsToFiveSeconds() {
        val settings = LightCommandSettings()

        assertEquals(5, settings.durationSeconds)
        assertEquals(1, settings.clipTimeUnits())
    }

    @Test
    fun singleLightOnEncodesProtocolJson() {
        val task = TaskData.singleLightOn(
            tagId = TAG_ID,
            settings = LightCommandSettings(
                color = LightColor.Red,
                beep = true,
                flashing = true,
                durationSeconds = 25
            )
        )

        val json = task.toJson()
        assertEquals(5, json.getInt("Time"))

        val item = json.getJSONArray("Items").getJSONObject(0)
        assertEquals(TAG_ID, item.getString("TagID"))
        assertTrue(item.getBoolean("Beep"))
        assertTrue(item.getBoolean("Flashing"))

        val color = item.getJSONArray("Colors").getJSONObject(0)
        assertTrue(color.getBoolean("R"))
        assertFalse(color.getBoolean("G"))
        assertFalse(color.getBoolean("B"))
    }

    @Test
    fun lightOffEncodesTimeZeroFalseRgbAndNullFlashing() {
        val json = TaskData.lightOff(listOf(TAG_ID)).toJson()

        assertEquals(0, json.getInt("Time"))
        val item = json.getJSONArray("Items").getJSONObject(0)
        assertEquals(TAG_ID, item.getString("TagID"))
        assertFalse(item.getBoolean("Beep"))
        assertTrue(item.isNull("Flashing"))

        val color = item.getJSONArray("Colors").getJSONObject(0)
        assertFalse(color.getBoolean("R"))
        assertFalse(color.getBoolean("G"))
        assertFalse(color.getBoolean("B"))
    }

    @Test
    fun taskDataRejectsSixtyItems() {
        expectIllegalArgument {
            TaskData.lightOff(tagIds(count = 60))
        }
    }

    @Test
    fun lightOnBatchesDefaultToTwentyItems() {
        val batches = TaskData.lightOnBatches(tagIds(count = 45))

        assertEquals(3, batches.size)
        assertEquals(20, batches[0].items.size)
        assertEquals(20, batches[1].items.size)
        assertEquals(5, batches[2].items.size)
    }
}

private const val TAG_ID = "AD100000048F"

private fun tagIds(count: Int): List<String> {
    return (0 until count).map { index ->
        "AD1${index.toString(16).uppercase().padStart(9, '0')}"
    }
}

private fun expectIllegalArgument(block: () -> Unit) {
    try {
        block()
    } catch (_: IllegalArgumentException) {
        return
    }
    throw AssertionError("Expected IllegalArgumentException.")
}
