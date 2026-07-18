package com.example.deepchatdemo.platform.config

import android.content.Context

fun interface PlatformEndpointProvider {
    fun currentEndpoint(): PlatformEndpoint

    fun addEndpointChangeListener(listener: (PlatformEndpoint) -> Unit): AutoCloseable =
        AutoCloseable { }
}

class PlatformEndpointChangedException : IllegalStateException(
    "The platform endpoint changed while an operation was in flight."
)

interface PlatformConfigStore : PlatformEndpointProvider {
    fun currentServerUrl(): String

    fun updateServerUrl(rawUrl: String): PlatformUrlValidation

    fun resetToDefault()
}

class SharedPreferencesPlatformConfigStore(
    context: Context
) : PlatformConfigStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val lock = Any()
    private val endpointListeners = linkedSetOf<(PlatformEndpoint) -> Unit>()
    private var endpoint = loadEndpoint()

    override fun currentServerUrl(): String = currentEndpoint().canonicalServerUrl

    override fun currentEndpoint(): PlatformEndpoint = loadEndpoint()

    override fun addEndpointChangeListener(
        listener: (PlatformEndpoint) -> Unit
    ): AutoCloseable {
        synchronized(lock) { endpointListeners += listener }
        return AutoCloseable { synchronized(lock) { endpointListeners -= listener } }
    }

    private fun loadEndpoint(): PlatformEndpoint {
        val stored = preferences.getString(KEY_SERVER_URL, null)
        val validated = stored?.let(PlatformUrlValidator::validateForWifiProduction)
        return (validated as? PlatformUrlValidation.Valid)?.endpoint
            ?: PlatformUrlValidator.requireForWifiProduction(PlatformUrlValidator.DEFAULT_SERVER_URL)
    }

    override fun updateServerUrl(rawUrl: String): PlatformUrlValidation {
        val result = PlatformUrlValidator.validateForWifiProduction(rawUrl)
        if (result is PlatformUrlValidation.Valid) {
            check(
                preferences.edit()
                .putString(KEY_SERVER_URL, result.endpoint.canonicalServerUrl)
                    .commit()
            ) { "Unable to persist the platform server URL." }
            publishEndpoint(result.endpoint)
        }
        return result
    }

    override fun resetToDefault() {
        check(preferences.edit().remove(KEY_SERVER_URL).commit()) {
            "Unable to reset the platform server URL."
        }
        publishEndpoint(
            PlatformUrlValidator.requireForWifiProduction(PlatformUrlValidator.DEFAULT_SERVER_URL)
        )
    }

    private fun publishEndpoint(next: PlatformEndpoint) {
        val listeners = synchronized(lock) {
            if (endpoint.canonicalServerUrl == next.canonicalServerUrl) return
            endpoint = next
            endpointListeners.toList()
        }
        listeners.forEach { it(next) }
    }

    private companion object {
        const val PREFERENCES_NAME = "hightac_platform_config"
        const val KEY_SERVER_URL = "server_url"
    }
}
