package com.example.deepchatdemo.ui.light

import com.example.deepchatdemo.platform.model.BrokerServiceState
import com.example.deepchatdemo.platform.model.BrokerStatus
import com.example.deepchatdemo.platform.model.Station
import com.example.deepchatdemo.platform.model.StationStatus
import com.example.deepchatdemo.platform.network.PlatformEventConnectionState
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class LightFindingCompactStatusTest {
    @Test
    fun disconnectedStateUsesTheQuietCompactSummary() {
        assertEquals(
            CompactLightSystemSummary(
                label = "寻物未连接",
                level = CompactLightSystemLevel.DISCONNECTED
            ),
            compactLightSystemSummary(LightFindingUiState())
        )
    }

    @Test
    fun fullyReadyStateUsesOneHealthySummary() {
        val now = Instant.parse("2026-07-22T06:00:00Z")
        val state = readyState(now)

        assertEquals(
            CompactLightSystemSummary(
                label = "寻物系统正常",
                level = CompactLightSystemLevel.HEALTHY
            ),
            compactLightSystemSummary(state)
        )
    }

    @Test
    fun criticalServiceFailureIsPromotedToTheTopLine() {
        val state = readyState(Instant.parse("2026-07-22T06:00:00Z")).copy(
            backendStatus = PlatformBackendUiStatus.UNAVAILABLE
        )

        assertEquals(
            CompactLightSystemSummary(
                label = "寻物服务异常",
                level = CompactLightSystemLevel.ERROR
            ),
            compactLightSystemSummary(state)
        )
    }

    @Test
    fun staleStationKeepsTheCardCompactButRequestsAttention() {
        val now = Instant.parse("2026-07-22T06:00:00Z")
        val state = readyState(now).copy(
            stations = listOf(station(now, StationStatus.STALE))
        )

        assertEquals(
            CompactLightSystemSummary(
                label = "寻物状态需检查",
                level = CompactLightSystemLevel.ATTENTION
            ),
            compactLightSystemSummary(state)
        )
    }

    private fun readyState(now: Instant): LightFindingUiState {
        return LightFindingUiState(
            stationId = STATION_ID,
            backendStatus = PlatformBackendUiStatus.AVAILABLE,
            brokerStatus = BrokerStatus(
                serviceName = "HighTacMqttBroker",
                serviceState = BrokerServiceState.RUNNING,
                endpoint = "192.168.1.105:1884",
                tcpReachable = true,
                mqttConnected = true,
                subscriptionsReady = true,
                startedAt = now,
                uptimeSeconds = 120,
                checkedAt = now
            ),
            brokerSnapshotStale = false,
            cacheSynchronizedAt = now,
            cacheStale = false,
            stations = listOf(station(now, StationStatus.ONLINE)),
            eventConnectionState = PlatformEventConnectionState.Connected(now),
            connectionEnabled = true,
            endpointVerified = true
        )
    }

    private fun station(now: Instant, status: StationStatus): Station {
        return Station(
            stationId = STATION_ID,
            siteId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            alias = "主基站",
            status = status,
            mac = null,
            firmwareVersion = null,
            serverAddress = null,
            heartbeatSeconds = 5,
            lastHeartbeatAt = now,
            totalCount = 0,
            sendCount = 0,
            brokerConnected = true,
            createdAt = now,
            updatedAt = now
        )
    }

    private companion object {
        const val STATION_ID = "90A9F7301427"
    }
}
