package com.example.deepchatdemo.price

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceModelMatcherTest {
    @Test
    fun shortModelMatchesStandaloneTokenAndNumericSuffix() {
        assertTrue(PriceModelMatcher.matches("SYMPHONY ST", "ST"))
        assertTrue(PriceModelMatcher.matches("ST150", "ST"))
    }

    @Test
    fun shortModelDoesNotMatchInsideLongerWords() {
        assertFalse(PriceModelMatcher.matches("ESTATE", "ST"))
        assertFalse(PriceModelMatcher.matches("MAJESTY250", "ST"))
    }
}
