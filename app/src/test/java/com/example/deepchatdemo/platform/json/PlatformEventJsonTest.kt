package com.example.deepchatdemo.platform.json

import com.example.deepchatdemo.platform.model.BindingCreatedEvent
import com.example.deepchatdemo.platform.model.BindingRemovedEvent
import com.example.deepchatdemo.platform.model.BrokerStatusChangedEvent
import com.example.deepchatdemo.platform.model.CommandStatusChangedEvent
import com.example.deepchatdemo.platform.model.DeviceStatusChangedEvent
import com.example.deepchatdemo.platform.model.StationHeartbeatEvent
import com.example.deepchatdemo.platform.model.StationStatusChangedEvent
import com.example.deepchatdemo.platform.model.SystemNoticeEvent
import com.example.deepchatdemo.platform.model.TagStatusChangedEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformEventJsonTest {
    @Test
    fun parsesEveryVersionOneEventVariant() {
        val events = listOf(
            PlatformJsonCodec.parseEvent(BROKER_EVENT),
            PlatformJsonCodec.parseEvent(STATION_STATUS_EVENT),
            PlatformJsonCodec.parseEvent(STATION_HEARTBEAT_EVENT),
            PlatformJsonCodec.parseEvent(TAG_EVENT),
            PlatformJsonCodec.parseEvent(BINDING_CREATED_EVENT),
            PlatformJsonCodec.parseEvent(BINDING_REMOVED_EVENT),
            PlatformJsonCodec.parseEvent(COMMAND_EVENT),
            PlatformJsonCodec.parseEvent(DEVICE_EVENT),
            PlatformJsonCodec.parseEvent(SYSTEM_EVENT)
        )

        assertTrue(events[0] is BrokerStatusChangedEvent)
        assertTrue(events[1] is StationStatusChangedEvent)
        assertTrue(events[2] is StationHeartbeatEvent)
        assertTrue(events[3] is TagStatusChangedEvent)
        assertTrue(events[4] is BindingCreatedEvent)
        assertTrue(events[5] is BindingRemovedEvent)
        assertTrue(events[6] is CommandStatusChangedEvent)
        assertTrue(events[7] is DeviceStatusChangedEvent)
        assertTrue(events[8] is SystemNoticeEvent)
        assertEquals(1, events.map { it.schemaVersion }.distinct().single())
    }

    @Test
    fun parsesCommandItemUpdateAndNullableReplacement() {
        val command = PlatformJsonCodec.parseEvent(COMMAND_EVENT) as CommandStatusChangedEvent
        val removed = PlatformJsonCodec.parseEvent(BINDING_REMOVED_EVENT) as BindingRemovedEvent

        assertEquals(EVENT_TAG_ID, command.payload.item?.tagId)
        assertEquals(254, command.payload.item?.resultType)
        assertEquals(null, removed.payload.replacementBindingId)
    }

    @Test
    fun rejectsEntityPayloadMismatch() {
        expectEventFailure {
            PlatformJsonCodec.parseEvent(
                TAG_EVENT.replace(
                    "\"entity_id\": \"$EVENT_TAG_ID\"",
                    "\"entity_id\": \"AD100000049A\""
                )
            )
        }
    }

    @Test
    fun rejectsUnknownSchemaVersionAndPayloadFields() {
        expectEventFailure {
            PlatformJsonCodec.parseEvent(SYSTEM_EVENT.replace("\"schema_version\": 1", "\"schema_version\": 2"))
        }
        expectEventFailure {
            PlatformJsonCodec.parseEvent(
                BROKER_EVENT.replace(
                    "\"reason\": null",
                    "\"reason\": null, \"secret\": \"must-not-be-accepted\""
                )
            )
        }
    }
}

private fun expectEventFailure(block: () -> Unit) {
    try {
        block()
    } catch (_: PlatformJsonException) {
        return
    }
    throw AssertionError("Expected event parsing to fail.")
}

private fun eventJson(
    eventId: String,
    type: String,
    entityType: String,
    entityId: String,
    payload: String
): String =
    """
        {
          "event_id": "$eventId",
          "schema_version": 1,
          "event_type": "$type",
          "occurred_at": "$EVENT_TIME",
          "entity_type": "$entityType",
          "entity_id": "$entityId",
          "payload": $payload
        }
    """.trimIndent()

private const val EVENT_TIME = "2026-07-16T08:15:30Z"
private const val EVENT_STATION_ID = "90A9F1234567"
private const val EVENT_TAG_ID = "AD100000048F"
private const val EVENT_SITE_ID = "2b16ae29-f7ab-4c44-9fc6-a0ca693d9c68"
private const val EVENT_PRODUCT_ID = "12f2390e-301f-4c44-a90d-205b5ff0b39f"
private const val EVENT_BINDING_ID = "168a5b9c-572e-4925-bec3-3c2a031f166f"
private const val EVENT_COMMAND_ID = "d7d74ed4-44d7-4934-af3a-a80d93aaf63f"
private const val EVENT_ITEM_ID = "06a676b9-d923-455f-9500-57f94730feb8"
private const val EVENT_DEVICE_ID = "ba17796c-c44e-4dc5-9afc-e82af7ef0cf1"

private val BROKER_EVENT = eventJson(
    eventId = "e4623e9a-5096-4d39-950e-d8c985735a26",
    type = "broker.status_changed",
    entityType = "broker",
    entityId = "HighTacMqttBroker",
    payload =
        """
            {
              "previous_status": "STARTING",
              "current_status": "RUNNING",
              "windows_service_running": true,
              "tcp_reachable": true,
              "mqtt_connected": true,
              "subscriptions_ready": true,
              "endpoint": "192.168.1.105:1884",
              "reason": null
            }
        """.trimIndent()
)

private val STATION_STATUS_EVENT = eventJson(
    eventId = "8acde9b9-9274-47fe-b3e5-42ec0af6738c",
    type = "station.status_changed",
    entityType = "station",
    entityId = EVENT_STATION_ID,
    payload =
        """
            {
              "station_id": "$EVENT_STATION_ID",
              "previous_status": "UNKNOWN",
              "current_status": "ONLINE",
              "last_heartbeat_at": "$EVENT_TIME",
              "broker_connected": true,
              "reason": "heartbeat_received"
            }
        """.trimIndent()
)

private val STATION_HEARTBEAT_EVENT = eventJson(
    eventId = "19061535-942c-4d7d-9dca-31e5a091a4e9",
    type = "station.heartbeat",
    entityType = "station",
    entityId = EVENT_STATION_ID,
    payload =
        """
            {
              "station_id": "$EVENT_STATION_ID",
              "status": "ONLINE",
              "mac": "AA:BB:CC:DD:EE:FF",
              "alias": "Dock A",
              "server_address": "192.168.1.105:1884",
              "heartbeat_seconds": 20,
              "firmware_version": "1.6.7",
              "total_count": 3,
              "send_count": 1
            }
        """.trimIndent()
)

private val TAG_EVENT = eventJson(
    eventId = "5a982fe8-ce1a-4094-86b3-d3b52bd7b8f7",
    type = "tag.status_changed",
    entityType = "tag",
    entityId = EVENT_TAG_ID,
    payload =
        """
            {
              "tag_id": "$EVENT_TAG_ID",
              "station_id": "$EVENT_STATION_ID",
              "online": true,
              "last_seen_at": "$EVENT_TIME",
              "battery_raw": 30,
              "battery_voltage": 3.0,
              "battery_level": 100,
              "low_battery": false,
              "is_abnormal": false,
              "abnormal_reason": null,
              "last_result_type": 254
            }
        """.trimIndent()
)

private val BINDING_SNAPSHOT =
    """
        {
          "binding_id": "$EVENT_BINDING_ID",
          "product_id": "$EVENT_PRODUCT_ID",
          "product_code": "1711A-ABA-PT",
          "product_name": "HighTac sample product",
          "tag_id": "$EVENT_TAG_ID",
          "site_id": "$EVENT_SITE_ID",
          "station_id": "$EVENT_STATION_ID",
          "source": "ANDROID",
          "actor_type": "ANDROID",
          "actor_id": "$EVENT_DEVICE_ID"
        }
    """.trimIndent()

private val BINDING_CREATED_EVENT = eventJson(
    eventId = "81e5dd81-2f6b-4f9a-873a-50c3b493a7d5",
    type = "binding.created",
    entityType = "binding",
    entityId = EVENT_BINDING_ID,
    payload = """{"binding":$BINDING_SNAPSHOT,"bound_at":"$EVENT_TIME"}"""
)

private val BINDING_REMOVED_EVENT = eventJson(
    eventId = "626c1a76-ee3d-48af-ad53-b5f14e11787b",
    type = "binding.removed",
    entityType = "binding",
    entityId = EVENT_BINDING_ID,
    payload =
        """{"binding":$BINDING_SNAPSHOT,"unbound_at":"$EVENT_TIME","replacement_binding_id":null}"""
)

private val COMMAND_EVENT = eventJson(
    eventId = "bb814db4-a833-420c-9e1d-3047548149a9",
    type = "command.status_changed",
    entityType = "command",
    entityId = EVENT_COMMAND_ID,
    payload =
        """
            {
              "command_id": "$EVENT_COMMAND_ID",
              "action": "LIGHT_ON",
              "previous_status": "PUBLISHED",
              "current_status": "CONFIRMED",
              "target_count": 1,
              "confirmed_count": 1,
              "unconfirmed_count": 0,
              "failed_count": 0,
              "item": {
                "item_id": "$EVENT_ITEM_ID",
                "tag_id": "$EVENT_TAG_ID",
                "station_id": "$EVENT_STATION_ID",
                "previous_status": "PUBLISHED",
                "current_status": "CONFIRMED",
                "correlation": "HEURISTIC_STATE_MATCH",
                "result_type": 254
              }
            }
        """.trimIndent()
)

private val DEVICE_EVENT = eventJson(
    eventId = "4b587d2c-5c3d-47e4-80de-9bfaec480b93",
    type = "device.status_changed",
    entityType = "device",
    entityId = EVENT_DEVICE_ID,
    payload =
        """
            {
              "device_id": "$EVENT_DEVICE_ID",
              "display_name": "Receiving Phone",
              "previous_status": "PENDING",
              "current_status": "APPROVED",
              "manufacturer": "Example Mobile",
              "model": "Field-10",
              "app_version": "1.0.0",
              "reason": "approved_by_admin"
            }
        """.trimIndent()
)

private val SYSTEM_EVENT = eventJson(
    eventId = "dd25bb08-a3db-417d-9ace-252e599361c6",
    type = "system.notice",
    entityType = "system",
    entityId = "HighTacPlatform",
    payload =
        """
            {
              "severity": "WARNING",
              "code": "BACKUP_FAILED",
              "message": "The scheduled backup did not complete.",
              "resource_type": "backup",
              "resource_id": "fa8d5849-0bd3-416d-9b1a-4c914ea20d86"
            }
        """.trimIndent()
)
