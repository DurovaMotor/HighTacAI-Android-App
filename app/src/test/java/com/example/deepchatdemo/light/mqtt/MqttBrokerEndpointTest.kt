package com.example.deepchatdemo.light.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttBrokerEndpointTest {
    @Test
    fun parsesPlainHostAndPortFields() {
        val endpoint = MqttBrokerEndpoint.parse(
            hostInput = "192.168.1.105",
            fallbackPort = 1884
        )

        assertEquals("192.168.1.105", endpoint.host)
        assertEquals(1884, endpoint.port)
        assertFalse(endpoint.tlsEnabled)
    }

    @Test
    fun parsesHostPortTypedIntoHostField() {
        val endpoint = MqttBrokerEndpoint.parse(
            hostInput = "192.168.1.105:1884",
            fallbackPort = 1883
        )

        assertEquals("192.168.1.105", endpoint.host)
        assertEquals(1884, endpoint.port)
        assertFalse(endpoint.tlsEnabled)
    }

    @Test
    fun parsesDocumentStyleTcpUri() {
        val endpoint = MqttBrokerEndpoint.parse(
            hostInput = "tcp://192.168.1.105:1884",
            fallbackPort = 1883
        )

        assertEquals("192.168.1.105", endpoint.host)
        assertEquals(1884, endpoint.port)
        assertFalse(endpoint.tlsEnabled)
    }

    @Test
    fun mqttsUriEnablesTlsForFutureUse() {
        val endpoint = MqttBrokerEndpoint.parse(
            hostInput = "mqtts://broker.local:8883",
            fallbackPort = 1884
        )

        assertEquals("broker.local", endpoint.host)
        assertEquals(8883, endpoint.port)
        assertTrue(endpoint.tlsEnabled)
    }

    @Test
    fun parsesUnbracketedIpv6AsHostWithoutMistakingLastSegmentForPort() {
        val endpoint = MqttBrokerEndpoint.parse(
            hostInput = "::1",
            fallbackPort = 1884
        )

        assertEquals("::1", endpoint.host)
        assertEquals(1884, endpoint.port)
    }

    @Test
    fun formatsIpv6BrokerUriWithBrackets() {
        val config = MqttConnectionConfig(
            stationId = "90A9F7301427",
            host = "fe80::1234",
            port = 1884
        )

        assertEquals("tcp://[fe80::1234]:1884", config.serverUri)
    }

    @Test
    fun rejectsAddressesThatCannotReachTheComputerFromWifiPhone() {
        val unavailableHosts = listOf(
            "127.0.0.1",
            "tcp://127.0.0.1:1884",
            "localhost",
            "[::1]:1884",
            "0:0:0:0:0:0:0:1",
            "0.0.0.0",
            "::",
            "10.0.2.2"
        )

        unavailableHosts.forEach { host ->
            assertTrue("Expected $host to be unavailable from a Wi-Fi phone", host.isUnavailableFromWifiPhone())
        }
        assertFalse("192.168.1.50".isUnavailableFromWifiPhone())
        assertFalse("broker.hightac.local".isUnavailableFromWifiPhone())
    }
}
