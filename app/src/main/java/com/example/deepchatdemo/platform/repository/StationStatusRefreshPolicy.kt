package com.example.deepchatdemo.platform.repository

import com.example.deepchatdemo.platform.model.Station

internal object StationStatusRefreshPolicy {
    const val MIN_INTERVAL_MILLIS = 30_000L
    const val MAX_INTERVAL_MILLIS = 60_000L
    const val MAX_FAILURE_INTERVAL_MILLIS = 120_000L

    fun intervalFor(stations: List<Station>): Long {
        val shortestHeartbeatMillis = stations
            .mapNotNull(Station::heartbeatSeconds)
            .minOrNull()
            ?.times(3_000L)
            ?.coerceAtLeast(60_000L)
            ?: 60_000L
        return (shortestHeartbeatMillis / 2L)
            .coerceIn(MIN_INTERVAL_MILLIS, MAX_INTERVAL_MILLIS)
    }

    fun retryDelay(baseIntervalMillis: Long, consecutiveFailures: Int): Long {
        require(consecutiveFailures > 0) { "Consecutive failures must be positive." }
        var delayMillis = baseIntervalMillis.coerceIn(
            MIN_INTERVAL_MILLIS,
            MAX_INTERVAL_MILLIS
        )
        repeat((consecutiveFailures - 1).coerceAtMost(8)) {
            delayMillis = (delayMillis * 2L).coerceAtMost(MAX_FAILURE_INTERVAL_MILLIS)
        }
        return delayMillis
    }
}
