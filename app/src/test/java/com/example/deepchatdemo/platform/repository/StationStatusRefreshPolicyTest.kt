package com.example.deepchatdemo.platform.repository

import com.example.deepchatdemo.platform.model.Station
import com.example.deepchatdemo.platform.model.StationStatus
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class StationStatusRefreshPolicyTest {
    @Test
    fun refreshIntervalTracksHeartbeatTimeoutWithinBounds() {
        assertEquals(30_000L, StationStatusRefreshPolicy.intervalFor(emptyList()))
        assertEquals(30_000L, StationStatusRefreshPolicy.intervalFor(listOf(station(20))))
        assertEquals(45_000L, StationStatusRefreshPolicy.intervalFor(listOf(station(30))))
        assertEquals(60_000L, StationStatusRefreshPolicy.intervalFor(listOf(station(120))))
    }

    @Test
    fun failuresBackOffToBoundedMaximum() {
        assertEquals(30_000L, StationStatusRefreshPolicy.retryDelay(30_000L, 1))
        assertEquals(60_000L, StationStatusRefreshPolicy.retryDelay(30_000L, 2))
        assertEquals(120_000L, StationStatusRefreshPolicy.retryDelay(30_000L, 3))
        assertEquals(120_000L, StationStatusRefreshPolicy.retryDelay(30_000L, 20))
    }

    private fun station(heartbeatSeconds: Int) = Station(
        stationId = "90A9F1234567",
        siteId = UUID.nameUUIDFromBytes("site".toByteArray()),
        alias = null,
        status = StationStatus.ONLINE,
        mac = null,
        firmwareVersion = null,
        serverAddress = null,
        heartbeatSeconds = heartbeatSeconds,
        lastHeartbeatAt = Instant.EPOCH,
        totalCount = 0,
        sendCount = 0,
        brokerConnected = true,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH
    )
}
