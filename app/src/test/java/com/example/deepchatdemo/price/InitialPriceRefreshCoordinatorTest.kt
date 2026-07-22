package com.example.deepchatdemo.price

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InitialPriceRefreshCoordinatorTest {
    @Test
    fun successMarksCompletionOnlyAfterLiveRefreshReturns() = runBlocking {
        var refreshReturned = false
        val store = FakeCompletionStore(onMark = { assertTrue(refreshReturned) })
        val coordinator = InitialPriceRefreshCoordinator(store) { onProgress ->
            onProgress(emptyList(), 2, 150)
            refreshReturned = true
            liveResult(pageCount = 3, rowCount = 220)
        }

        coordinator.start(this)?.join()

        assertTrue(store.complete)
        assertEquals(1, store.markCalls)
        assertEquals(InitialPriceRefreshState.Succeeded(3, 220), coordinator.state.value)
        assertNull(coordinator.start(this))
    }

    @Test
    fun networkFailureDoesNotMarkCompletionOrRetryInTheSameProcess() = runBlocking {
        val store = FakeCompletionStore()
        var attempts = 0
        val coordinator = InitialPriceRefreshCoordinator(store) {
            attempts += 1
            throw IOException("offline")
        }

        coordinator.start(this)?.join()

        assertFalse(store.complete)
        assertEquals(InitialPriceRefreshState.Failed, coordinator.state.value)
        assertNull(coordinator.start(this))
        assertEquals(1, attempts)
    }

    @Test
    fun staleCacheFallbackDoesNotCountAsFirstRefreshSuccess() = runBlocking {
        val store = FakeCompletionStore()
        val coordinator = InitialPriceRefreshCoordinator(store) {
            liveResult(pageCount = 1, rowCount = 10).copy(fromCache = true)
        }

        coordinator.start(this)?.join()

        assertFalse(store.complete)
        assertEquals(InitialPriceRefreshState.Failed, coordinator.state.value)
    }

    @Test
    fun cancellationLeavesFlagUnset() = runBlocking {
        val store = FakeCompletionStore()
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val coordinator = InitialPriceRefreshCoordinator(store) {
            started.complete(Unit)
            never.await()
            liveResult(1, 1)
        }

        val job = requireNotNull(coordinator.start(this))
        started.await()
        job.cancelAndJoin()

        assertFalse(store.complete)
        assertEquals(InitialPriceRefreshState.Failed, coordinator.state.value)
    }

    @Test
    fun aNewProcessCoordinatorRetriesAfterPreviousFailure() = runBlocking {
        val store = FakeCompletionStore()
        InitialPriceRefreshCoordinator(store) { throw IOException("offline") }
            .start(this)
            ?.join()

        val nextProcess = InitialPriceRefreshCoordinator(store) { liveResult(4, 400) }
        nextProcess.start(this)?.join()

        assertTrue(store.complete)
        assertEquals(InitialPriceRefreshState.Succeeded(4, 400), nextProcess.state.value)
    }

    @Test
    fun completedRefreshIsNotRepeatedByANewProcessCoordinator() = runBlocking {
        val store = FakeCompletionStore(complete = true)
        var attempts = 0
        val nextProcess = InitialPriceRefreshCoordinator(store) {
            attempts += 1
            liveResult(1, 100)
        }

        assertNull(nextProcess.start(this))

        assertEquals(0, attempts)
        assertEquals(InitialPriceRefreshState.AlreadyCompleted, nextProcess.state.value)
        assertEquals(0, store.markCalls)
    }

    private fun liveResult(pageCount: Int, rowCount: Int) = PriceLookupSearchResult(
        results = emptyList(),
        sourceLabel = "简道云实时数据",
        pageCount = pageCount,
        fetchedRowCount = rowCount,
        fromCache = false
    )
}

private class FakeCompletionStore(
    var complete: Boolean = false,
    private val onMark: () -> Unit = {}
) : InitialPriceRefreshCompletionStore {
    var markCalls: Int = 0

    override fun isComplete(): Boolean = complete

    override fun markComplete() {
        onMark()
        markCalls += 1
        complete = true
    }
}
