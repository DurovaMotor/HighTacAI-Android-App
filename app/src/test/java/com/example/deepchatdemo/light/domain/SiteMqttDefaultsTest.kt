package com.example.deepchatdemo.light.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SiteMqttDefaultsTest {
    @Test
    fun stationConfigUsesCurrentSiteBrokerByDefault() {
        val config = StationConfig()

        assertEquals("192.168.1.105", config.brokerHost)
        assertEquals(1884, config.brokerPort)
        assertFalse(config.tlsEnabled)
    }

    @Test
    fun missingAndLegacySiteHostsResolveToCurrentBroker() {
        listOf(null, "", "192.168.2.105", "192.168.2.104").forEach { host ->
            assertEquals("192.168.1.105", SiteMqttDefaults.resolveBrokerHost(host))
        }
    }

    @Test
    fun customBrokerHostRemainsConfigurable() {
        assertEquals("broker.hightac.local", SiteMqttDefaults.resolveBrokerHost(" broker.hightac.local "))
        assertEquals("10.20.30.40", SiteMqttDefaults.resolveBrokerHost("10.20.30.40"))
    }
}
