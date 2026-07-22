package com.example.deepchatdemo.platform.network

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectPolicyTest {
    @Test
    fun calculatesCappedExponentialDelayWithoutJitterAtMidpoint() {
        val policy = ReconnectPolicy(
            initialDelayMillis = 1_000,
            maximumDelayMillis = 10_000,
            multiplier = 2.0,
            jitterRatio = 0.2,
            randomUnit = { 0.5 }
        )

        assertEquals(1_000, policy.delayMillis(0))
        assertEquals(2_000, policy.delayMillis(1))
        assertEquals(4_000, policy.delayMillis(2))
        assertEquals(8_000, policy.delayMillis(3))
        assertEquals(10_000, policy.delayMillis(4))
        assertEquals(10_000, policy.delayMillis(30))
    }

    @Test
    fun appliesBoundedSymmetricJitter() {
        val low = ReconnectPolicy(
            initialDelayMillis = 1_000,
            maximumDelayMillis = 10_000,
            jitterRatio = 0.2,
            randomUnit = { 0.0 }
        )
        val high = ReconnectPolicy(
            initialDelayMillis = 1_000,
            maximumDelayMillis = 10_000,
            jitterRatio = 0.2,
            randomUnit = { 0.999999 }
        )

        assertEquals(800, low.delayMillis(0))
        assertEquals(1_200, high.delayMillis(0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfRangeRandomSource() {
        ReconnectPolicy(randomUnit = { 1.0 }).delayMillis(0)
    }
}
