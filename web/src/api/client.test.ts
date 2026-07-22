import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, apiRequest, parsePage, queryString, setCsrfToken } from './client';

afterEach(() => {
  vi.restoreAllMocks();
  setCsrfToken(null);
});

describe('API contract helpers', () => {
  it('accepts only the nested contract pagination envelope', () => {
    expect(parsePage<number>({
      items: [1, 2],
      pagination: { page: 2, page_size: 20, total_items: 22, total_pages: 2 },
    })).toEqual({
      items: [1, 2],
      pagination: { page: 2, page_size: 20, total_items: 22, total_pages: 2 },
    });
    expect(() => parsePage({ items: [], page: 1, page_size: 20, total: 0 })).toThrow(ApiError);
    expect(() => parsePage({ items: [], pagination: { page: 1, page_size: 25, total_items: 0, total_pages: 0 } })).toThrow(ApiError);
  });

  it('omits empty filters and repeats array parameters', () => {
    expect(queryString({ page: 1, status: '', source: undefined, color: ['RED', 'GREEN'] })).toBe('?page=1&color=RED&color=GREEN');
  });

  it('sends CSRF and a generated UUID idempotency key on guarded writes', async () => {
    setCsrfToken('csrf-from-me');
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('{}', {
      status: 202,
      headers: { 'Content-Type': 'application/json' },
    }));

    await apiRequest('/broker/start', { method: 'POST', idempotent: true });

    const [, init] = fetchMock.mock.calls[0];
    const headers = new Headers(init?.headers);
    expect(headers.get('X-CSRF-Token')).toBe('csrf-from-me');
    expect(headers.get('Idempotency-Key')).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
  });

  it('recovers the CSRF token from an /auth/me response for a refreshed tab', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response('{}', {
        status: 200,
        headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': 'csrf-recovered' },
      }))
      .mockResolvedValueOnce(new Response('{}', {
        status: 202,
        headers: { 'Content-Type': 'application/json' },
      }));

    await apiRequest('/auth/me');
    await apiRequest('/broker/start', { method: 'POST', idempotent: true });

    const headers = new Headers(fetchMock.mock.calls[1][1]?.headers);
    expect(headers.get('X-CSRF-Token')).toBe('csrf-recovered');
  });

  it('parses the standard nested error envelope', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      error: {
        code: 'BROKER_NOT_READY',
        message: 'MQTT 尚未就绪',
        details: [{ field: null, code: 'NOT_READY', message: '后台订阅未完成' }],
        request_id: 'e4623e9a-5096-4d39-950e-d8c985735a26',
      },
    }), { status: 503, headers: { 'Content-Type': 'application/json' } }));

    await expect(apiRequest('/broker/status')).rejects.toMatchObject({
      name: 'ApiError',
      status: 503,
      code: 'BROKER_NOT_READY',
      message: 'MQTT 尚未就绪',
      requestId: 'e4623e9a-5096-4d39-950e-d8c985735a26',
    });
  });
});
