import type { PlatformEntityType, PlatformEvent, PlatformEventType } from '../api/types';

type JsonObject = Record<string, unknown>;

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const STATION_ID = /^90A9F[0-9A-F]{7}$/;
const TAG_ID = /^AD1[0-9A-F]{9}$/;
const EVENT_TYPES: PlatformEventType[] = [
  'broker.status_changed',
  'station.status_changed',
  'station.heartbeat',
  'tag.status_changed',
  'binding.created',
  'binding.removed',
  'command.status_changed',
  'device.status_changed',
  'system.notice',
];
const ENTITY_BY_EVENT: Record<PlatformEventType, PlatformEntityType> = {
  'broker.status_changed': 'broker',
  'station.status_changed': 'station',
  'station.heartbeat': 'station',
  'tag.status_changed': 'tag',
  'binding.created': 'binding',
  'binding.removed': 'binding',
  'command.status_changed': 'command',
  'device.status_changed': 'device',
  'system.notice': 'system',
};
const BROKER_STATES = ['STOPPED', 'STARTING', 'RUNNING', 'STOPPING', 'FAILED', 'UNKNOWN'];
const STATION_STATES = ['UNKNOWN', 'ONLINE', 'STALE', 'OFFLINE'];
const COMMAND_STATES = ['ACCEPTED', 'PUBLISHED', 'CONFIRMED', 'PARTIALLY_CONFIRMED', 'UNCONFIRMED', 'FAILED', 'SUPERSEDED'];
const COMMAND_ITEM_STATES = ['PENDING', 'PUBLISHED', 'CONFIRMED', 'UNCONFIRMED', 'FAILED', 'SUPERSEDED'];
const DEVICE_STATES = ['PENDING', 'APPROVED', 'REVOKED'];
const BATTERY_LEVELS = [0, 10, 30, 60, 80, 90, 100, null];

function record(value: unknown): value is JsonObject {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function exact(value: JsonObject, keys: string[]) {
  const actual = Object.keys(value).sort();
  const expected = [...keys].sort();
  return actual.length === expected.length && actual.every((key, index) => key === expected[index]);
}

function text(value: unknown, min = 0, max = Number.MAX_SAFE_INTEGER) {
  return typeof value === 'string' && value.length >= min && value.length <= max;
}

function nullableText(value: unknown, max: number) {
  return value === null || text(value, 0, max);
}

function integer(value: unknown, min = Number.MIN_SAFE_INTEGER, max = Number.MAX_SAFE_INTEGER) {
  return Number.isInteger(value) && Number(value) >= min && Number(value) <= max;
}

function number(value: unknown, min = 0) {
  return typeof value === 'number' && Number.isFinite(value) && value >= min;
}

function timestamp(value: unknown) {
  return typeof value === 'string'
    && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(value)
    && !Number.isNaN(Date.parse(value));
}

function nullableTimestamp(value: unknown) {
  return value === null || timestamp(value);
}

function oneOf(value: unknown, values: readonly unknown[]) {
  return values.includes(value);
}

function bindingSnapshot(value: unknown) {
  if (!record(value) || !exact(value, [
    'binding_id', 'product_id', 'product_code', 'product_name', 'tag_id', 'site_id',
    'station_id', 'source', 'actor_type', 'actor_id',
  ])) return false;
  return UUID.test(String(value.binding_id))
    && UUID.test(String(value.product_id))
    && text(value.product_code, 1, 128)
    && nullableText(value.product_name, 256)
    && TAG_ID.test(String(value.tag_id))
    && UUID.test(String(value.site_id))
    && STATION_ID.test(String(value.station_id))
    && oneOf(value.source, ['ANDROID', 'WEB', 'MIGRATION'])
    && oneOf(value.actor_type, ['ADMIN', 'ANDROID', 'SYSTEM'])
    && text(value.actor_id, 1, 128);
}

function brokerPayload(value: unknown) {
  if (!record(value) || !exact(value, [
    'previous_status', 'current_status', 'windows_service_running', 'tcp_reachable',
    'mqtt_connected', 'subscriptions_ready', 'endpoint', 'reason',
  ])) return false;
  return oneOf(value.previous_status, BROKER_STATES)
    && oneOf(value.current_status, BROKER_STATES)
    && typeof value.windows_service_running === 'boolean'
    && typeof value.tcp_reachable === 'boolean'
    && typeof value.mqtt_connected === 'boolean'
    && typeof value.subscriptions_ready === 'boolean'
    && text(value.endpoint, 1) && /^[^\s:]+:[0-9]{1,5}$/.test(String(value.endpoint))
    && nullableText(value.reason, 512);
}

function stationStatusPayload(value: unknown) {
  if (!record(value) || !exact(value, [
    'station_id', 'previous_status', 'current_status', 'last_heartbeat_at', 'broker_connected', 'reason',
  ])) return false;
  return STATION_ID.test(String(value.station_id))
    && oneOf(value.previous_status, STATION_STATES)
    && oneOf(value.current_status, STATION_STATES)
    && nullableTimestamp(value.last_heartbeat_at)
    && typeof value.broker_connected === 'boolean'
    && nullableText(value.reason, 512);
}

function heartbeatPayload(value: unknown) {
  if (!record(value) || !exact(value, [
    'station_id', 'status', 'mac', 'alias', 'server_address', 'heartbeat_seconds',
    'firmware_version', 'total_count', 'send_count',
  ])) return false;
  return STATION_ID.test(String(value.station_id))
    && oneOf(value.status, STATION_STATES)
    && text(value.mac, 0, 64)
    && nullableText(value.alias, 128)
    && text(value.server_address, 0, 255)
    && integer(value.heartbeat_seconds, 1, 3600)
    && text(value.firmware_version, 0, 64)
    && integer(value.total_count, 0)
    && integer(value.send_count, 0);
}

function tagPayload(value: unknown) {
  if (!record(value) || !exact(value, [
    'tag_id', 'station_id', 'online', 'last_seen_at', 'battery_raw', 'battery_voltage',
    'battery_level', 'low_battery', 'is_abnormal', 'abnormal_reason', 'last_result_type',
  ])) return false;
  return TAG_ID.test(String(value.tag_id))
    && (value.station_id === null || STATION_ID.test(String(value.station_id)))
    && typeof value.online === 'boolean'
    && nullableTimestamp(value.last_seen_at)
    && (value.battery_raw === null || integer(value.battery_raw, 0))
    && (value.battery_voltage === null || number(value.battery_voltage))
    && oneOf(value.battery_level, BATTERY_LEVELS)
    && typeof value.low_battery === 'boolean'
    && typeof value.is_abnormal === 'boolean'
    && nullableText(value.abnormal_reason, 512)
    && (value.last_result_type === null || integer(value.last_result_type, 0, 255));
}

function bindingCreatedPayload(value: unknown) {
  return record(value)
    && exact(value, ['binding', 'bound_at'])
    && bindingSnapshot(value.binding)
    && timestamp(value.bound_at);
}

function bindingRemovedPayload(value: unknown) {
  return record(value)
    && exact(value, ['binding', 'unbound_at', 'replacement_binding_id'])
    && bindingSnapshot(value.binding)
    && timestamp(value.unbound_at)
    && (value.replacement_binding_id === null || UUID.test(String(value.replacement_binding_id)));
}

function commandItem(value: unknown) {
  if (value === null) return true;
  if (!record(value) || !exact(value, [
    'item_id', 'tag_id', 'station_id', 'previous_status', 'current_status', 'correlation', 'result_type',
  ])) return false;
  return UUID.test(String(value.item_id))
    && TAG_ID.test(String(value.tag_id))
    && STATION_ID.test(String(value.station_id))
    && oneOf(value.previous_status, COMMAND_ITEM_STATES)
    && oneOf(value.current_status, COMMAND_ITEM_STATES)
    && oneOf(value.correlation, ['NONE', 'HEURISTIC_STATION_TAG_TIME', 'HEURISTIC_STATE_MATCH'])
    && (value.result_type === null || integer(value.result_type, 0, 255));
}

function commandPayload(value: unknown) {
  if (!record(value) || !exact(value, [
    'command_id', 'action', 'previous_status', 'current_status', 'target_count',
    'confirmed_count', 'unconfirmed_count', 'failed_count', 'item',
  ])) return false;
  return UUID.test(String(value.command_id))
    && oneOf(value.action, ['LIGHT_ON', 'LIGHT_OFF'])
    && oneOf(value.previous_status, COMMAND_STATES)
    && oneOf(value.current_status, COMMAND_STATES)
    && integer(value.target_count, 0)
    && integer(value.confirmed_count, 0)
    && integer(value.unconfirmed_count, 0)
    && integer(value.failed_count, 0)
    && commandItem(value.item);
}

function devicePayload(value: unknown) {
  if (!record(value) || !exact(value, [
    'device_id', 'display_name', 'previous_status', 'current_status', 'manufacturer',
    'model', 'app_version', 'reason',
  ])) return false;
  return UUID.test(String(value.device_id))
    && nullableText(value.display_name, 128)
    && oneOf(value.previous_status, DEVICE_STATES)
    && oneOf(value.current_status, DEVICE_STATES)
    && text(value.manufacturer, 1, 128)
    && text(value.model, 1, 128)
    && text(value.app_version, 1, 64)
    && nullableText(value.reason, 512);
}

function noticePayload(value: unknown) {
  if (!record(value) || !exact(value, ['severity', 'code', 'message', 'resource_type', 'resource_id'])) return false;
  return oneOf(value.severity, ['INFO', 'WARNING', 'ERROR'])
    && text(value.code, 2, 64) && /^[A-Z][A-Z0-9_]{1,63}$/.test(String(value.code))
    && text(value.message, 1, 1024)
    && nullableText(value.resource_type, 64)
    && nullableText(value.resource_id, 128);
}

const payloadValidators: Record<PlatformEventType, (value: unknown) => boolean> = {
  'broker.status_changed': brokerPayload,
  'station.status_changed': stationStatusPayload,
  'station.heartbeat': heartbeatPayload,
  'tag.status_changed': tagPayload,
  'binding.created': bindingCreatedPayload,
  'binding.removed': bindingRemovedPayload,
  'command.status_changed': commandPayload,
  'device.status_changed': devicePayload,
  'system.notice': noticePayload,
};

export function isPlatformEvent(value: unknown): value is PlatformEvent {
  if (!record(value) || !exact(value, [
    'event_id', 'schema_version', 'event_type', 'occurred_at', 'entity_type', 'entity_id', 'payload',
  ])) return false;
  if (!UUID.test(String(value.event_id))
    || value.schema_version !== 1
    || !EVENT_TYPES.includes(value.event_type as PlatformEventType)
    || !timestamp(value.occurred_at)
    || !text(value.entity_id, 1, 128)) return false;

  const eventType = value.event_type as PlatformEventType;
  if (value.entity_type !== ENTITY_BY_EVENT[eventType]) return false;
  if (eventType === 'broker.status_changed' && value.entity_id !== 'HighTacMqttBroker') return false;
  if (eventType === 'system.notice' && value.entity_id !== 'HighTacPlatform') return false;
  if ((eventType === 'station.status_changed' || eventType === 'station.heartbeat') && !STATION_ID.test(String(value.entity_id))) return false;
  if (eventType === 'tag.status_changed' && !TAG_ID.test(String(value.entity_id))) return false;
  if (['binding.created', 'binding.removed', 'command.status_changed', 'device.status_changed'].includes(eventType) && !UUID.test(String(value.entity_id))) return false;
  return payloadValidators[eventType](value.payload);
}

export function parsePlatformEvent(data: unknown): PlatformEvent | null {
  try {
    const value = typeof data === 'string' ? JSON.parse(data) : data;
    return isPlatformEvent(value) ? value : null;
  } catch {
    return null;
  }
}
