package com.example.deepchatdemo.light.data

import android.content.Context
import com.example.deepchatdemo.light.domain.SiteMqttDefaults
import com.example.deepchatdemo.light.domain.StationConfig

class StationConfigStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "light_station_config",
        Context.MODE_PRIVATE
    )

    fun getConfig(): StationConfig {
        val storedBrokerHost = preferences.getString(KEY_BROKER_HOST, null)
        val brokerHost = SiteMqttDefaults.resolveBrokerHost(storedBrokerHost)
        if (storedBrokerHost != brokerHost) {
            preferences.edit().putString(KEY_BROKER_HOST, brokerHost).apply()
        }

        return StationConfig(
            stationId = preferences.getString(KEY_STATION_ID, "").orEmpty(),
            alias = preferences.getString(KEY_ALIAS, "").orEmpty(),
            brokerHost = brokerHost,
            brokerPort = preferences.getInt(KEY_BROKER_PORT, SiteMqttDefaults.BROKER_PORT),
            username = preferences.getString(KEY_USERNAME, "hightac_mqtt").orEmpty(),
            password = preferences.getString(KEY_PASSWORD, "hightac-light").orEmpty(),
            tlsEnabled = preferences.getBoolean(KEY_TLS, false)
        )
    }

    fun saveConfig(config: StationConfig) {
        preferences.edit()
            .putString(KEY_STATION_ID, config.normalizedStationId)
            .putString(KEY_ALIAS, config.alias.trim())
            .putString(KEY_BROKER_HOST, config.brokerHost.trim())
            .putInt(KEY_BROKER_PORT, config.brokerPort)
            .putString(KEY_USERNAME, config.username.trim())
            .putString(KEY_PASSWORD, config.password)
            .putBoolean(KEY_TLS, config.tlsEnabled)
            .apply()
    }

    companion object {
        private const val KEY_STATION_ID = "station_id"
        private const val KEY_ALIAS = "alias"
        private const val KEY_BROKER_HOST = "broker_host"
        private const val KEY_BROKER_PORT = "broker_port"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_TLS = "tls"
    }
}
