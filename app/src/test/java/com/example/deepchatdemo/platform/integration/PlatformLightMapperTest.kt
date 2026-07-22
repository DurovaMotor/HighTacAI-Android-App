package com.example.deepchatdemo.platform.integration

import com.example.deepchatdemo.platform.model.ActorType
import com.example.deepchatdemo.platform.model.Binding
import com.example.deepchatdemo.platform.model.BindingSource
import com.example.deepchatdemo.platform.model.Tag
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformLightMapperTest {
    @Test
    fun preservesEveryActiveTagForTheSameProduct() {
        val first = binding(
            id = UUID.fromString("168a5b9c-572e-4925-bec3-3c2a031f166f"),
            tagId = "AD1000165FC2",
            boundAt = Instant.parse("2026-07-16T08:15:30Z")
        )
        val second = binding(
            id = UUID.fromString("f52cb27d-8aa6-4800-a106-217c3062d366"),
            tagId = "AD1000161C7A",
            boundAt = Instant.parse("2026-07-16T08:16:30Z")
        )
        val inactive = first.copy(
            id = UUID.fromString("02f2783d-6ed6-430e-af01-f79bd616f74c"),
            tagId = "AD100000048F",
            isActive = false,
            unboundAt = Instant.parse("2026-07-16T08:20:30Z")
        )

        val mapped = PlatformLightMapper.bindings(listOf(first, inactive, second))

        assertEquals(2, mapped.size)
        assertEquals(listOf("AD1000161C7A", "AD1000165FC2"), mapped.map { it.tagId })
        assertTrue(mapped.all { it.itemCode == "1711A-ABA-PT" })
        assertEquals(second.id.toString(), mapped.first().id)
    }

    @Test
    fun mapsTagSnapshotWithoutInventingAStation() {
        val withStation = tag("AD1000165FC2", "90A9F1234567")
        val withoutStation = tag("AD1000161C7A", null)

        val mapped = PlatformLightMapper.statuses(listOf(withStation, withoutStation))

        assertEquals(setOf("AD1000165FC2"), mapped.keys)
        assertEquals(90, mapped.getValue("AD1000165FC2").batteryLevel)
        assertFalse(mapped.containsKey("AD1000161C7A"))
    }
}

private fun binding(
    id: UUID,
    tagId: String,
    boundAt: Instant
) = Binding(
    id = id,
    productId = UUID.fromString("12f2390e-301f-4c44-a90d-205b5ff0b39f"),
    productCode = "1711A-ABA-PT",
    productName = "Sample",
    tagId = tagId,
    siteId = UUID.fromString("2b16ae29-f7ab-4c44-9fc6-a0ca693d9c68"),
    stationId = "90A9F1234567",
    source = BindingSource.ANDROID,
    actorType = ActorType.ANDROID,
    actorId = "android-device",
    actorDisplayName = "Receiving Phone",
    boundAt = boundAt,
    unboundAt = null,
    isActive = true
)

private fun tag(tagId: String, stationId: String?) = Tag(
    tagId = tagId,
    siteId = UUID.fromString("2b16ae29-f7ab-4c44-9fc6-a0ca693d9c68"),
    stationId = stationId,
    registeredAt = Instant.parse("2026-07-16T08:15:30Z"),
    firstSeenAt = Instant.parse("2026-07-16T08:15:30Z"),
    lastSeenAt = Instant.parse("2026-07-16T08:16:30Z"),
    online = true,
    batteryRaw = 29,
    batteryVoltage = 2.9,
    batteryLevel = 90,
    lowBattery = false,
    firmwareVersion = "1.6.7",
    groupNo = 10,
    lastResultType = 254,
    isAbnormal = false,
    abnormalReason = null,
    activeBindingId = null
)
