package com.example.deepchatdemo.price

import android.content.Context
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface InitialPriceRefreshState {
    data object Pending : InitialPriceRefreshState
    data object AlreadyCompleted : InitialPriceRefreshState
    data class Running(val pageCount: Int, val fetchedRowCount: Int) : InitialPriceRefreshState
    data class Succeeded(val pageCount: Int, val fetchedRowCount: Int) : InitialPriceRefreshState
    data object Failed : InitialPriceRefreshState
}

internal interface InitialPriceRefreshCompletionStore {
    fun isComplete(): Boolean
    fun markComplete()
}

internal class SharedPreferencesInitialPriceRefreshCompletionStore(
    context: Context
) : InitialPriceRefreshCompletionStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    override fun isComplete(): Boolean = preferences.getBoolean(KEY_COMPLETE, false)

    override fun markComplete() {
        check(preferences.edit().putBoolean(KEY_COMPLETE, true).commit()) {
            "无法保存首次查价同步状态"
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "price_initial_refresh_v1"
        const val KEY_COMPLETE = "full_refresh_persisted"
    }
}

class InitialPriceRefreshCoordinator internal constructor(
    private val completionStore: InitialPriceRefreshCompletionStore,
    private val refresh: suspend (
        onProgress: suspend (results: List<PriceLookupResult>, pageCount: Int, fetchedRowCount: Int) -> Unit
    ) -> PriceLookupSearchResult
) {
    private val attemptedInProcess = AtomicBoolean(false)
    private val _state = MutableStateFlow<InitialPriceRefreshState>(
        if (completionStore.isComplete()) {
            InitialPriceRefreshState.AlreadyCompleted
        } else {
            InitialPriceRefreshState.Pending
        }
    )
    val state: StateFlow<InitialPriceRefreshState> = _state.asStateFlow()

    fun start(scope: CoroutineScope): Job? {
        if (completionStore.isComplete()) {
            _state.value = InitialPriceRefreshState.AlreadyCompleted
            return null
        }
        if (!attemptedInProcess.compareAndSet(false, true)) return null

        return scope.launch {
            _state.value = InitialPriceRefreshState.Running(pageCount = 0, fetchedRowCount = 0)
            try {
                val result = refresh { _, pageCount, fetchedRowCount ->
                    _state.value = InitialPriceRefreshState.Running(
                        pageCount = pageCount,
                        fetchedRowCount = fetchedRowCount
                    )
                }
                if (result.fromCache) {
                    throw IOException("首次查价同步未获得新的完整数据。")
                }
                completionStore.markComplete()
                _state.value = InitialPriceRefreshState.Succeeded(
                    pageCount = result.pageCount,
                    fetchedRowCount = result.fetchedRowCount
                )
            } catch (error: CancellationException) {
                _state.value = InitialPriceRefreshState.Failed
                throw error
            } catch (error: Throwable) {
                _state.value = InitialPriceRefreshState.Failed
            }
        }
    }
}
