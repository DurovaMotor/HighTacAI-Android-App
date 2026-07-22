import { afterEach, describe, expect, it, vi } from 'vitest';
import { setCsrfToken } from './client';
import { api } from './endpoints';

function jsonResponse(payload: unknown = {}) {
  return new Response(JSON.stringify(payload), {
    status: 202,
    headers: { 'Content-Type': 'application/json' },
  });
}

afterEach(() => {
  vi.restoreAllMocks();
  setCsrfToken(null);
});

describe('contract write payloads', () => {
  it('sends exact broker, binding, command, all-off, rebind and restore semantics', async () => {
    setCsrfToken('csrf-token');
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => jsonResponse());

    await api.broker.start();
    await api.broker.stop();
    await api.broker.restart();
    await api.bindings.remove('168a5b9c-572e-4925-bec3-3c2a031f166f');
    await api.commands.create({ action: 'LIGHT_OFF', tag_ids: ['AD100000048F'] });
    await api.stations.allOff('90A9F7301427');
    await api.bindings.rebind('168a5b9c-572e-4925-bec3-3c2a031f166f', {
      product_code: '1711A-ABA-PT-NEW',
      expected_tag_id: 'AD100000048F',
    });
    await api.backups.restore('fa8d5849-0bd3-416d-9b1a-4c914ea20d86', 'a'.repeat(64));

    const calls = fetchMock.mock.calls.map(([url, init]) => ({
      url: String(url),
      method: init?.method,
      headers: new Headers(init?.headers),
      body: typeof init?.body === 'string' ? JSON.parse(init.body) : init?.body,
    }));

    expect(calls.map((call) => call.url)).toEqual([
      '/api/v1/broker/start',
      '/api/v1/broker/stop',
      '/api/v1/broker/restart',
      '/api/v1/bindings/168a5b9c-572e-4925-bec3-3c2a031f166f',
      '/api/v1/light-commands',
      '/api/v1/stations/90A9F7301427/all-off',
      '/api/v1/bindings/168a5b9c-572e-4925-bec3-3c2a031f166f/rebind',
      '/api/v1/backups/fa8d5849-0bd3-416d-9b1a-4c914ea20d86/restore',
    ]);
    calls.forEach((call) => {
      expect(call.headers.get('X-CSRF-Token')).toBe('csrf-token');
      expect(call.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/i);
    });
    expect(calls[0].body).toBeUndefined();
    expect(calls[1].body).toEqual({ confirmation: 'STOP BROKER' });
    expect(calls[2].body).toBeUndefined();
    expect(calls[3].method).toBe('DELETE');
    expect(calls[3].headers.get('confirmation')).toBe('UNBIND');
    expect(calls[4].body).toEqual({ action: 'LIGHT_OFF', tag_ids: ['AD100000048F'] });
    expect(calls[5].body).toEqual({ confirmation: 'ALL OFF' });
    expect(calls[6].body).toEqual({
      product_code: '1711A-ABA-PT-NEW',
      expected_tag_id: 'AD100000048F',
    });
    expect(calls[7].body).toEqual({
      confirmation: 'RESTORE BACKUP',
      expected_sha256: 'a'.repeat(64),
    });
  });

  it('uses merge-patch media types for contract PATCH operations', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => jsonResponse());

    await api.stations.update('90A9F7301427', { alias: '主仓库' });
    await api.products.update('00000000-0000-4000-8000-000000000001', { product_name: '更新名称' });
    await api.settings.updateSite({ name: 'HighTac 主仓库' });
    await api.settings.updateNetwork({ api_port: 8088 });

    for (const [, init] of fetchMock.mock.calls) {
      expect(init?.method).toBe('PATCH');
      expect(new Headers(init?.headers).get('Content-Type')).toBe('application/merge-patch+json');
    }
  });

  it('keeps special tag views and audit exports within declared query parameters', async () => {
    const pagePayload = {
      items: [],
      pagination: { page: 1, page_size: 20, total_items: 0, total_pages: 0 },
    };
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (url) => new Response(
      String(url).includes('/operation-logs/export') ? 'mock export' : JSON.stringify(pagePayload),
      { status: 200, headers: { 'Content-Type': 'application/json' } },
    ));

    await api.tags.list({ page: 1, page_size: 20, q: 'AD100000048F', station_id: '90A9F7301427', sort: '-last_seen_at' }, 'low-battery');
    await api.tags.list({ page: 1, page_size: 20, q: 'AD100000048F', station_id: '90A9F7301427', sort: '-last_seen_at' }, 'abnormal');
    await api.logs.export({
      event_type: 'binding.created',
      actor_type: 'ADMIN',
      entity_type: 'binding',
      entity_id: 'ignored-by-export-contract',
      occurred_from: '2026-07-16T00:00:00Z',
      occurred_to: '2026-07-17T00:00:00Z',
    }, 'xlsx');

    const urls = fetchMock.mock.calls.map(([url]) => String(url));
    expect(urls[0]).toBe('/api/v1/tags/low-battery?page=1&page_size=20&sort=-last_seen_at&station_id=90A9F7301427');
    expect(urls[1]).toBe('/api/v1/tags/abnormal?page=1&page_size=20&sort=-last_seen_at&station_id=90A9F7301427');
    expect(urls[2]).toContain('/api/v1/operation-logs/export?format=xlsx');
    expect(urls[2]).toContain('event_type=binding.created');
    expect(urls[2]).toContain('actor_type=ADMIN');
    expect(urls[2]).not.toContain('entity_type');
    expect(urls[2]).not.toContain('entity_id');
  });
});
