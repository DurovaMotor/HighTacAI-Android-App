package com.example.deepchatdemo.price

import com.example.deepchatdemo.platform.network.PlatformMobileApiResponse
import com.example.deepchatdemo.platform.network.PlatformMobileApiRoute
import com.example.deepchatdemo.platform.network.PlatformMobileApiTransport
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceLookupRepositoryImageTest {
    @Test
    fun completedImageLookupIsCachedForAtMostTenSecondsAndForceRefreshBypassesIt() = runBlocking {
        var now = 1_000L
        var calls = 0
        val repository = repository(
            elapsedRealtimeMs = { now },
            imageUrlLookup = { _, _ -> "image-${++calls}" }
        )
        val item = PriceLookupResult(id = "row-1", code = "ABC-001")

        assertEquals("image-1", repository.resolveImageUrl(item))
        assertEquals("image-1", repository.resolveImageUrl(item))
        assertEquals(1, calls)

        assertEquals("image-2", repository.resolveImageUrl(item, forceRefresh = true))
        assertEquals(2, calls)

        now += 9_999L
        assertEquals("image-2", repository.resolveImageUrl(item))
        now += 1L
        assertEquals("image-3", repository.resolveImageUrl(item))
        assertEquals(3, calls)
    }

    @Test
    fun concurrentRequestsForTheSameItemShareOneLookupEvenWhenForced() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val repository = repository(
            imageUrlLookup = { _, _ ->
                calls.incrementAndGet()
                started.complete(Unit)
                release.await()
                "shared-image"
            }
        )
        val item = PriceLookupResult(id = "row-1", code = "ABC-001")

        val requests = List(8) { index ->
            async {
                repository.resolveImageUrl(item, forceRefresh = index % 2 == 0)
            }
        }
        started.await()
        release.complete(Unit)

        assertEquals(List(8) { "shared-image" }, requests.awaitAll())
        assertEquals(1, calls.get())
    }

    @Test
    fun imageLookupsNeverExceedFourConcurrentRequests() = runBlocking {
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val repository = repository(
            imageUrlLookup = { _, code ->
                val current = active.incrementAndGet()
                maximumActive.updateAndGet { previous -> maxOf(previous, current) }
                try {
                    delay(50L)
                    "image-$code"
                } finally {
                    active.decrementAndGet()
                }
            }
        )

        val results = List(12) { index ->
            async {
                repository.resolveImageUrl(
                    PriceLookupResult(id = "row-$index", code = "CODE-$index")
                )
            }
        }.awaitAll()

        assertEquals(12, results.size)
        assertEquals(4, maximumActive.get())
    }

    @Test
    fun failedLookupIsRemovedFromTheInFlightMapAndCanBeRetried() = runBlocking {
        var calls = 0
        val repository = repository(
            imageUrlLookup = { _, _ ->
                calls += 1
                if (calls == 1) throw IOException("temporary failure")
                "recovered-image"
            }
        )
        val item = PriceLookupResult(id = "row-1", code = "ABC-001")

        val firstFailure = runCatching { repository.resolveImageUrl(item) }.exceptionOrNull()
        assertTrue(firstFailure is IOException)
        assertEquals("recovered-image", repository.resolveImageUrl(item))
        assertEquals(2, calls)
    }

    private fun repository(
        imageUrlLookup: suspend (String, String) -> String,
        elapsedRealtimeMs: () -> Long = { 1_000L }
    ): PriceLookupRepository {
        return PriceLookupRepository(
            jiandaoYunPriceApi = JianDaoYunPriceApi(UnusedPriceTransport),
            imageUrlLookup = imageUrlLookup,
            elapsedRealtimeMs = elapsedRealtimeMs
        )
    }
}

private object UnusedPriceTransport : PlatformMobileApiTransport {
    override fun hasApprovedDeviceToken(): Boolean = true

    override fun postJson(
        route: PlatformMobileApiRoute,
        jsonBody: String
    ): PlatformMobileApiResponse = error("Unexpected transport request: $route")
}
