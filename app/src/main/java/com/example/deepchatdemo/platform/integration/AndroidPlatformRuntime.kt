package com.example.deepchatdemo.platform.integration

import android.content.Context
import com.example.deepchatdemo.light.data.LightBindingRepository
import com.example.deepchatdemo.light.data.SharedPreferencesLightBindingRepository
import com.example.deepchatdemo.platform.cache.PlatformCacheDatabase
import com.example.deepchatdemo.platform.cache.RoomPlatformCache
import com.example.deepchatdemo.platform.config.PlatformConfigStore
import com.example.deepchatdemo.platform.config.SharedPreferencesPlatformConfigStore
import com.example.deepchatdemo.platform.network.OkHttpHighTacPlatformApi
import com.example.deepchatdemo.platform.network.OkHttpPlatformEventClient
import com.example.deepchatdemo.platform.repository.PlatformRepository
import com.example.deepchatdemo.platform.repository.RemotePlatformRepository
import com.example.deepchatdemo.platform.security.AndroidKeystoreCredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class AndroidPlatformRuntime private constructor(
    val configStore: PlatformConfigStore,
    val repository: PlatformRepository,
    val legacyBindings: LightBindingRepository,
    val legacyMigrationReviews: LegacyBindingMigrationReviewStore,
    private val cacheDatabase: PlatformCacheDatabase,
    private val scope: CoroutineScope
) : AutoCloseable {
    override fun close() {
        (repository as? AutoCloseable)?.close() ?: repository.stopRealtime()
        cacheDatabase.close()
        scope.cancel()
    }

    companion object {
        fun create(context: Context): AndroidPlatformRuntime {
            val applicationContext = context.applicationContext
            clearLegacyMqttConnectionSettings(applicationContext)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val configStore = SharedPreferencesPlatformConfigStore(applicationContext)
            val credentials = AndroidKeystoreCredentialStore(applicationContext, configStore)
            val cacheDatabase = PlatformCacheDatabase.create(applicationContext)
            val api = OkHttpHighTacPlatformApi(
                endpointProvider = configStore,
                tokenProvider = credentials
            )
            val eventClient = OkHttpPlatformEventClient(
                endpointProvider = configStore,
                tokenProvider = credentials,
                scope = scope
            )
            val repository = RemotePlatformRepository(
                api = api,
                credentials = credentials,
                eventStream = eventClient,
                endpointProvider = configStore,
                scope = scope,
                cache = RoomPlatformCache(cacheDatabase.cacheDao())
            )
            return AndroidPlatformRuntime(
                configStore = configStore,
                repository = repository,
                legacyBindings = SharedPreferencesLightBindingRepository(applicationContext),
                legacyMigrationReviews =
                    SharedPreferencesLegacyBindingMigrationReviewStore(applicationContext),
                cacheDatabase = cacheDatabase,
                scope = scope
            )
        }

        private fun clearLegacyMqttConnectionSettings(context: Context) {
            val preferences = context.getSharedPreferences(
                LEGACY_STATION_PREFERENCES,
                Context.MODE_PRIVATE
            )
            check(
                preferences.edit()
                    .remove("broker_host")
                    .remove("broker_port")
                    .remove("username")
                    .remove("password")
                    .remove("tls")
                    .commit()
            ) { "Unable to remove retired Android MQTT connection settings." }
        }

        private const val LEGACY_STATION_PREFERENCES = "light_station_config"
    }
}
