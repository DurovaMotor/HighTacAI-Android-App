package com.example.deepchatdemo.platform.network

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class IdempotencyTest {
    @Test
    fun factoryUsesOneCanonicalUuidPerOperation() {
        val uuids = ArrayDeque(
            listOf(
                UUID.fromString("2ec5fd45-0e4d-4be4-a6bd-3517118b64df"),
                UUID.fromString("51c5655a-aadc-471c-87f7-e05c49b86e37")
            )
        )
        val factory = IdempotencyKeyFactory(UuidSource { uuids.removeFirst() })

        val first = factory.create()
        val second = factory.create()

        assertEquals("2ec5fd45-0e4d-4be4-a6bd-3517118b64df", first.value)
        assertNotEquals(first, second)
        assertEquals(first, IdempotencyKey.parse(first.value))
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseRejectsNonUuidValue() {
        IdempotencyKey.parse("retry-number-one")
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseRejectsNonCanonicalUuidValue() {
        IdempotencyKey.parse("2ec5fd45-0e4d-4be4-a6bd-3517118b64d")
    }
}
