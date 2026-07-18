package com.example.deepchatdemo.platform.json

import com.example.deepchatdemo.platform.model.CheckStatus
import com.example.deepchatdemo.platform.model.BrokerServiceState
import com.example.deepchatdemo.platform.model.CommandStatus
import com.example.deepchatdemo.platform.model.EnrollmentStatus
import com.example.deepchatdemo.platform.model.LightAction
import com.example.deepchatdemo.platform.model.LightColor
import com.example.deepchatdemo.platform.model.ReadinessCheckName
import com.example.deepchatdemo.platform.model.SensitiveString
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformJsonCodecTest {
    @Test
    fun normalizesProductCodeDashesProducedByMobileKeyboards() {
        assertEquals(
            "45121-AAA-FC-T",
            PlatformValueRules.normalizeProductCode(
                " 45121\uFF0DAAA\u2013FC\u2212T "
            )
        )
    }

    @Test
    fun parsesStrictHealthResponses() {
        val liveness = PlatformJsonCodec.parseLiveness(LIVENESS_JSON)
        val readiness = PlatformJsonCodec.parseReadiness(READINESS_JSON)

        assertEquals("alive", liveness.status)
        assertEquals("HighTacPlatform", liveness.service)
        assertEquals(CheckStatus.DEGRADED, readiness.status)
        assertEquals(
            setOf(
                ReadinessCheckName.DATABASE,
                ReadinessCheckName.MIGRATIONS,
                ReadinessCheckName.MQTT_BRIDGE
            ),
            readiness.checks.map { it.name }.toSet()
        )
    }

    @Test
    fun parsesStrictBrokerStatus() {
        val broker = PlatformJsonCodec.parseBrokerStatus(BROKER_STATUS_JSON)

        assertEquals("HighTacMqttBroker", broker.serviceName)
        assertEquals(BrokerServiceState.RUNNING, broker.serviceState)
        assertEquals("192.168.1.105:1884", broker.endpoint)
        assertTrue(broker.tcpReachable)
        assertTrue(broker.mqttConnected)
        assertTrue(broker.subscriptionsReady)
        assertEquals(3600L, broker.uptimeSeconds)

        expectJsonFailure {
            PlatformJsonCodec.parseBrokerStatus(
                BROKER_STATUS_JSON.replace("\"uptime_seconds\": 3600", "\"uptime_seconds\": -1")
            )
        }
    }

    @Test
    fun parsesEnrollmentAndRedactsServerSecrets() {
        val created = PlatformJsonCodec.parseDeviceEnrollmentCreated(ENROLLMENT_CREATED_JSON)
        val approved = PlatformJsonCodec.parseDeviceEnrollmentState(ENROLLMENT_APPROVED_JSON)

        assertEquals(EnrollmentStatus.PENDING, created.status)
        assertEquals("[REDACTED]", created.pollSecret.toString())
        assertEquals(EnrollmentStatus.APPROVED, approved.status)
        assertEquals("[REDACTED]", approved.deviceToken.toString())
        assertEquals(48, approved.deviceToken?.length)
    }

    @Test
    fun parsesPagedProductsBindingsTagsAndStations() {
        val products = PlatformJsonCodec.parseProductPage(pageJson(PRODUCT_JSON))
        val bindings = PlatformJsonCodec.parseBindingPage(pageJson(BINDING_JSON))
        val tags = PlatformJsonCodec.parseTagPage(pageJson(TAG_JSON))
        val stations = PlatformJsonCodec.parseStationPage(pageJson(STATION_JSON))

        assertEquals("1711A-ABA-PT", products.items.single().productCode)
        assertEquals(TAG_ID, bindings.items.single().tagId)
        assertEquals(100, tags.items.single().batteryLevel)
        assertTrue(tags.items.single().online)
        assertEquals(20, stations.items.single().heartbeatSeconds)
        assertTrue(stations.items.single().brokerConnected)
    }

    @Test
    fun parsesTagAndProductDetails() {
        val tag = PlatformJsonCodec.parseTagDetail(
            """
                {
                  "tag": $TAG_JSON,
                  "active_binding": $BINDING_JSON,
                  "recent_history": [
                    {
                      "id": "$HISTORY_ID",
                      "occurred_at": "$TIME",
                      "online": true,
                      "battery_raw": 30,
                      "battery_level": 100,
                      "result_type": 254,
                      "is_abnormal": false,
                      "abnormal_reason": null
                    }
                  ]
                }
            """.trimIndent()
        )
        val product = PlatformJsonCodec.parseProductDetail(
            """{"product":$PRODUCT_JSON,"active_bindings":[$BINDING_JSON]}"""
        )

        assertEquals(BINDING_ID, tag.activeBinding?.id.toString())
        assertEquals(HISTORY_ID, tag.recentHistory.single().id.toString())
        assertEquals(TAG_ID, product.activeBindings.single().tagId)
    }

    @Test
    fun parsesLightCommandAndItemAcknowledgement() {
        val command = PlatformJsonCodec.parseLightCommand(LIGHT_COMMAND_JSON)

        assertEquals(LightAction.LIGHT_ON, command.action)
        assertEquals(LightColor.RED, command.requestedColor)
        assertEquals(CommandStatus.CONFIRMED, command.status)
        assertEquals(1, command.confirmedCount)
        assertEquals(254, command.items.single().lastResultType)
    }

    @Test
    fun encodesContractRequestsWithNormalizedProductCode() {
        val bindingJson = PlatformJsonCodec.encodeBindingCreate(
            com.example.deepchatdemo.platform.model.BindingCreateRequest(
                productCode = " 1711a-aba-pt ",
                productName = null,
                tagId = TAG_ID,
                stationId = STATION_ID
            )
        )
        val commandJson = PlatformJsonCodec.encodeLightCommand(
            com.example.deepchatdemo.platform.model.LightCommandRequest.forProduct(
                action = LightAction.LIGHT_ON,
                productCode = "1711a-aba-pt",
                color = LightColor.CYAN
            )
        )

        assertEquals("1711A-ABA-PT", JSONObject(bindingJson).getString("product_code"))
        assertTrue(JSONObject(bindingJson).isNull("product_name"))
        assertEquals("CYAN", JSONObject(commandJson).getString("color"))
        assertFalse(JSONObject(commandJson).has("tag_ids"))
    }

    @Test
    fun parsesTypedErrorEnvelope() {
        val error = PlatformJsonCodec.parseApiError(
            """
                {
                  "error": {
                    "code": "TAG_ALREADY_BOUND",
                    "message": "Explicit rebind is required.",
                    "details": [
                      {"field":"tag_id","code":"ACTIVE_BINDING_EXISTS","message":"Already bound."}
                    ],
                    "request_id": "$REQUEST_ID"
                  }
                }
            """.trimIndent()
        )

        assertEquals("TAG_ALREADY_BOUND", error.code)
        assertEquals("tag_id", error.details.single().field)
    }

    @Test
    fun rejectsUnknownMissingAndWrongTypeFields() {
        expectJsonFailure {
            PlatformJsonCodec.parseLiveness(
                LIVENESS_JSON.dropLast(1) + ",\"unexpected\":true}"
            )
        }
        expectJsonFailure {
            PlatformJsonCodec.parseLiveness(
                LIVENESS_JSON.replace("\"version\": \"0.1.0\",", "")
            )
        }
        expectJsonFailure {
            PlatformJsonCodec.parseLiveness(
                LIVENESS_JSON.replace("\"status\": \"alive\"", "\"status\": true")
            )
        }
        expectJsonFailure {
            PlatformJsonCodec.parseTag(
                TAG_JSON.replace("\"battery_level\": 100", "\"battery_level\": 50")
            )
        }
    }

    @Test
    fun sensitiveValueNeverRendersPlaintext() {
        val value = SensitiveString.fromTransport("server-secret-that-must-not-be-logged")

        assertEquals("[REDACTED]", value.toString())
        assertEquals(37, value.length)
        assertEquals("server-secret-that-must-not-be-logged", value.use { it })
    }

    private fun pageJson(item: String): String =
        """{"items":[$item],"pagination":{"page":1,"page_size":20,"total_items":1,"total_pages":1}}"""
}

private fun expectJsonFailure(block: () -> Unit) {
    try {
        block()
    } catch (_: PlatformJsonException) {
        return
    }
    throw AssertionError("Expected strict platform JSON parsing to fail.")
}

private const val SITE_ID = "2b16ae29-f7ab-4c44-9fc6-a0ca693d9c68"
private const val PRODUCT_ID = "12f2390e-301f-4c44-a90d-205b5ff0b39f"
private const val BINDING_ID = "168a5b9c-572e-4925-bec3-3c2a031f166f"
private const val ACTOR_ID = "ba17796c-c44e-4dc5-9afc-e82af7ef0cf1"
private const val HISTORY_ID = "b17311f8-4e61-4b2a-a313-6f9db4eedb2c"
private const val COMMAND_ID = "d7d74ed4-44d7-4934-af3a-a80d93aaf63f"
private const val COMMAND_ITEM_ID = "06a676b9-d923-455f-9500-57f94730feb8"
private const val REQUEST_ID = "b6d35a55-63ac-4b0e-99ae-78262b27c7da"
private const val ENROLLMENT_ID = "88f22b30-f160-4446-b793-883628620243"
private const val STATION_ID = "90A9F1234567"
private const val TAG_ID = "AD100000048F"
private const val TIME = "2026-07-16T08:15:30Z"

private val LIVENESS_JSON =
    """
        {
          "status": "alive",
          "service": "HighTacPlatform",
          "version": "0.1.0",
          "checked_at": "$TIME"
        }
    """.trimIndent()

private val READINESS_JSON =
    """
        {
          "status": "DEGRADED",
          "checks": [
            {"name":"database","status":"READY","message":"ok","checked_at":"$TIME"},
            {"name":"migrations","status":"READY","message":"ok","checked_at":"$TIME"},
            {"name":"mqtt_bridge","status":"DEGRADED","message":"offline","checked_at":"$TIME"}
          ],
          "checked_at": "$TIME"
        }
    """.trimIndent()

private val BROKER_STATUS_JSON =
    """
        {
          "service_name": "HighTacMqttBroker",
          "service_state": "RUNNING",
          "endpoint": "192.168.1.105:1884",
          "tcp_reachable": true,
          "mqtt_connected": true,
          "subscriptions_ready": true,
          "started_at": "$TIME",
          "uptime_seconds": 3600,
          "checked_at": "$TIME"
        }
    """.trimIndent()

private val ENROLLMENT_CREATED_JSON =
    """
        {
          "id": "$ENROLLMENT_ID",
          "status": "PENDING",
          "poll_secret": "poll-secret-value-with-at-least-32-characters",
          "expires_at": "2026-07-17T08:15:30Z",
          "poll_after_seconds": 5
        }
    """.trimIndent()

private val ENROLLMENT_APPROVED_JSON =
    """
        {
          "id": "$ENROLLMENT_ID",
          "status": "APPROVED",
          "display_name": "Receiving Phone",
          "device_id": "$ACTOR_ID",
          "device_token": "123456789012345678901234567890123456789012345678",
          "expires_at": "2026-07-17T08:15:30Z"
        }
    """.trimIndent()

private val PRODUCT_JSON =
    """
        {
          "id": "$PRODUCT_ID",
          "product_code": "1711A-ABA-PT",
          "product_name": "HighTac sample product",
          "source": "BINDING",
          "is_active": true,
          "active_binding_count": 1,
          "created_at": "$TIME",
          "updated_at": "$TIME"
        }
    """.trimIndent()

private val BINDING_JSON =
    """
        {
          "id": "$BINDING_ID",
          "product_id": "$PRODUCT_ID",
          "product_code": "1711A-ABA-PT",
          "product_name": "HighTac sample product",
          "tag_id": "$TAG_ID",
          "site_id": "$SITE_ID",
          "station_id": "$STATION_ID",
          "source": "ANDROID",
          "actor_type": "ANDROID",
          "actor_id": "$ACTOR_ID",
          "actor_display_name": "Receiving Phone",
          "bound_at": "$TIME",
          "unbound_at": null,
          "is_active": true
        }
    """.trimIndent()

private val TAG_JSON =
    """
        {
          "tag_id": "$TAG_ID",
          "site_id": "$SITE_ID",
          "station_id": "$STATION_ID",
          "registered_at": "$TIME",
          "first_seen_at": "$TIME",
          "last_seen_at": "$TIME",
          "online": true,
          "battery_raw": 30,
          "battery_voltage": 3.0,
          "battery_level": 100,
          "low_battery": false,
          "firmware_version": "1.6.7",
          "group_no": 10,
          "last_result_type": 254,
          "is_abnormal": false,
          "abnormal_reason": null,
          "active_binding_id": "$BINDING_ID"
        }
    """.trimIndent()

private val STATION_JSON =
    """
        {
          "station_id": "$STATION_ID",
          "site_id": "$SITE_ID",
          "alias": "Dock A",
          "status": "ONLINE",
          "mac": "AA:BB:CC:DD:EE:FF",
          "firmware_version": "1.6.7",
          "server_address": "192.168.1.105:1884",
          "heartbeat_seconds": 20,
          "last_heartbeat_at": "$TIME",
          "total_count": 3,
          "send_count": 1,
          "broker_connected": true,
          "created_at": "$TIME",
          "updated_at": "$TIME"
        }
    """.trimIndent()

private val LIGHT_COMMAND_JSON =
    """
        {
          "id": "$COMMAND_ID",
          "action": "LIGHT_ON",
          "product_id": "$PRODUCT_ID",
          "product_code": "1711A-ABA-PT",
          "requested_color": "RED",
          "status": "CONFIRMED",
          "target_count": 1,
          "confirmed_count": 1,
          "unconfirmed_count": 0,
          "failed_count": 0,
          "created_at": "$TIME",
          "published_at": "$TIME",
          "completed_at": "$TIME",
          "items": [
            {
              "id": "$COMMAND_ITEM_ID",
              "tag_id": "$TAG_ID",
              "station_id": "$STATION_ID",
              "status": "CONFIRMED",
              "publish_attempts": 1,
              "published_at": "$TIME",
              "confirmed_at": "$TIME",
              "last_result_type": 254,
              "correlation": "HEURISTIC_STATE_MATCH",
              "failure_code": null
            }
          ]
        }
    """.trimIndent()
