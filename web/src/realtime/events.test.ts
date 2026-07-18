import { describe, expect, it } from 'vitest';
import { isPlatformEvent, parsePlatformEvent } from './events';

const validEvent = {
  event_id: 'e4623e9a-5096-4d39-950e-d8c985735a26',
  schema_version: 1,
  event_type: 'broker.status_changed',
  occurred_at: '2026-07-16T08:15:30Z',
  entity_type: 'broker',
  entity_id: 'HighTacMqttBroker',
  payload: {
    previous_status: 'STARTING',
    current_status: 'RUNNING',
    windows_service_running: true,
    tcp_reachable: true,
    mqtt_connected: true,
    subscriptions_ready: true,
    endpoint: '192.168.1.105:1884',
    reason: null,
  },
};

describe('WebSocket event contract', () => {
  it('accepts a schema-valid version 1 event', () => {
    expect(isPlatformEvent(validEvent)).toBe(true);
    expect(parsePlatformEvent(JSON.stringify(validEvent))?.event_type).toBe('broker.status_changed');
  });

  it('rejects missing envelope fields, extra fields and invalid payloads', () => {
    const { schema_version: _version, ...withoutVersion } = validEvent;
    expect(isPlatformEvent(withoutVersion)).toBe(false);
    expect(isPlatformEvent({ ...validEvent, replayed: false })).toBe(false);
    expect(isPlatformEvent({ ...validEvent, payload: { ...validEvent.payload, mqtt_connected: 'yes' } })).toBe(false);
    expect(parsePlatformEvent('{invalid json')).toBeNull();
  });
});
