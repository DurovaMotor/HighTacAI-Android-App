package com.example.deepchatdemo.light.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttBrokerEndpointTest {
    @Test
    fun parsesPlainHostAndPortFields() {
        val endpoint = MqttBrokerEndpoint.parse(
            hostInput = "192.168.2.105",
            fallbackPort = 1884
        )

        assertEquals("192.168.2.105", endpoint.host)
        assertEquals(1884, endpoint.port)
        assertFalse(endpoint.tlsEnabled)
    }

    @Test
    fun parsesHostPortTypedIntoHostField() {
        val endpoint = MqttBrokerEndpoint.parse(
            hostInput = "192.168.2.105:1884",
            fallbackPort = 1883
        )

        assertEquals("192.168.2.105", endpoint.host)
        assertEquals(1884, endpoint.port)
        assertFalse(endpoint.tlsEnabled)
    }

    @Test
    fun parsesDocumentStyleTcpUri() {
        val endpoint = MqttBrokerEndpoint.parse(
            hostInput = "tcp://192.168.2.105:1884",
            fallbackPort = 1883
        )

        assertEquals("192.168.2.105", endpoint.host)
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
}
