import { describe, expect, it } from 'vitest';
import { ApiError } from './client';
import { mockRequest } from './mock';
import type { PageResponse, Station } from './types';

describe('mock transport contract', () => {
  it('enforces the development login and returns paginated station data', async () => {
    await expect(mockRequest('/auth/login', { method: 'POST', body: { username: 'Adam', password: 'wrong' } })).rejects.toBeInstanceOf(ApiError);
    await expect(mockRequest('/auth/login', { method: 'POST', body: { username: 'Adam', password: 'Adam' } })).resolves.toMatchObject({ user: { username: 'Adam' } });
    const page = await mockRequest<PageResponse<Station>>('/stations?page=1&page_size=20', { method: 'GET' });
    expect(page.items).toHaveLength(3);
    expect(page.pagination.page_size).toBe(20);
    expect(page.pagination.total_items).toBeGreaterThan(2);
    expect(page.items[0].station_id).toMatch(/^[0-9A-F]{12}$/);
  });
});
