package com.example.deepchatdemo.light.domain

object SiteMqttDefaults {
    const val BROKER_HOST = "192.168.1.105"
    const val BROKER_PORT = 1884

    private val legacyBrokerHosts = setOf(
        "192.168.2.105",
        "192.168.2.104"
    )

    fun resolveBrokerHost(storedHost: String?): String {
        val host = storedHost.orEmpty().trim()
        return if (host.isBlank() || host in legacyBrokerHosts) BROKER_HOST else host
    }
}
