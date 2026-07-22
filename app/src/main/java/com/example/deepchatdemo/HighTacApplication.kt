package com.example.deepchatdemo

import android.app.Application
import com.example.deepchatdemo.price.InitialPriceRefreshCoordinator
import com.example.deepchatdemo.price.PriceLookupRepository
import com.example.deepchatdemo.price.SharedPreferencesInitialPriceRefreshCompletionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class HighTacApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    internal val appServices: HighTacAppServices by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        HighTacAppServices(this)
    }

    override fun onCreate() {
        super.onCreate()
        appServices.initialPriceRefreshCoordinator.start(applicationScope)
    }
}

internal class HighTacAppServices(application: Application) {
    val priceLookupRepository: PriceLookupRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PriceLookupRepository.create(application)
    }

    val initialPriceRefreshCoordinator: InitialPriceRefreshCoordinator by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED
    ) {
        InitialPriceRefreshCoordinator(
            completionStore = SharedPreferencesInitialPriceRefreshCompletionStore(application),
            refresh = { onProgress ->
                priceLookupRepository.lookup(
                    filters = emptyList(),
                    forceRefresh = true,
                    onProgress = onProgress
                )
            }
        )
    }
}
