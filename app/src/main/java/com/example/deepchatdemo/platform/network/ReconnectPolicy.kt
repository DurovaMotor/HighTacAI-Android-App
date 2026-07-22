package com.example.deepchatdemo.platform.network

import java.util.concurrent.ThreadLocalRandom
import kotlin.math.roundToLong

class ReconnectPolicy(
    private val initialDelayMillis: Long = 1_000L,
    private val maximumDelayMillis: Long = 30_000L,
    private val multiplier: Double = 2.0,
    private val jitterRatio: Double = 0.20,
    private val randomUnit: () -> Double = { ThreadLocalRandom.current().nextDouble() }
) {
    init {
        require(initialDelayMillis > 0) { "Initial delay must be positive." }
        require(maximumDelayMillis >= initialDelayMillis) { "Maximum delay is too small." }
        require(multiplier >= 1.0 && multiplier.isFinite()) { "Multiplier must be finite and at least 1." }
        require(jitterRatio in 0.0..1.0) { "Jitter ratio must be between 0 and 1." }
    }

    fun delayMillis(attempt: Int): Long {
        require(attempt >= 0) { "Reconnect attempt cannot be negative." }
        var base = initialDelayMillis.toDouble()
        for (ignored in 0 until attempt) {
            base = (base * multiplier).coerceAtMost(maximumDelayMillis.toDouble())
            if (base >= maximumDelayMillis) break
        }
        val unit = randomUnit()
        require(unit >= 0.0 && unit < 1.0 && unit.isFinite()) {
            "Random jitter source must return a finite value in [0, 1)."
        }
        val factor = 1.0 + ((unit * 2.0) - 1.0) * jitterRatio
        return (base * factor)
            .roundToLong()
            .coerceIn(1L, maximumDelayMillis)
    }
}
