import type { PageResponse, PaginationMeta } from './types';

const API_BASE = (import.meta.env.VITE_API_BASE || '/api/v1').replace(/\/$/, '');
const MOCK_MODE = import.meta.env.VITE_MOCK_API === 'true';
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);
const PAGE_SIZES = new Set([20, 50, 100]);
const CSRF_STORAGE_KEY = 'hightac.csrf';

interface ContractError {
  code?: unknown;
  message?: unknown;
  details?: unknown;
  request_id?: unknown;
}

export class ApiError extends Error {
  status: number;
  code?: string;
  details?: unknown;
  requestId?: string;

  constructor(message: string, status: number, code?: string, details?: unknown, requestId?: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.details = details;
    this.requestId = requestId;
  }
}

let inMemoryCsrf = typeof sessionStorage !== 'undefined' ? sessionStorage.getItem(CSRF_STORAGE_KEY) || '' : '';

export function setCsrfToken(token?: string | null) {
  if (token === undefined) return;
  inMemoryCsrf = token || '';
  if (typeof sessionStorage === 'undefined') return;
  if (inMemoryCsrf) sessionStorage.setItem(CSRF_STORAGE_KEY, inMemoryCsrf);
  else sessionStorage.removeItem(CSRF_STORAGE_KEY);
}

export function clearCsrfToken() {
  setCsrfToken(null);
}

function cookieValue(name: string) {
  if (typeof document === 'undefined') return '';
  const prefix = `${encodeURIComponent(name)}=`;
  const match = document.cookie.split('; ').find((entry) => entry.startsWith(prefix));
  return match ? decodeURIComponent(match.slice(prefix.length)) : '';
}

function createUuid() {
  if (typeof globalThis.crypto?.randomUUID === 'function') return globalThis.crypto.randomUUID();
  const bytes = new Uint8Array(16);
  if (typeof globalThis.crypto?.getRandomValues === 'function') globalThis.crypto.getRandomValues(bytes);
  else for (let index = 0; index < bytes.length; index += 1) bytes[index] = Math.floor(Math.random() * 256);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = [...bytes].map((value) => value.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export function queryString(params: Record<string, unknown>) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '') return;
    if (Array.isArray(value)) value.forEach((item) => search.append(key, String(item)));
    else search.set(key, String(value));
  });
  const text = search.toString();
  return text ? `?${text}` : '';
}

export interface RequestOptions extends Omit<RequestInit, 'body'> {
  body?: unknown;
  responseType?: 'json' | 'blob';
  idempotent?: boolean;
  idempotencyKey?: string;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function contractError(payload: unknown): ContractError {
  if (!isRecord(payload)) return {};
  if (isRecord(payload.error)) return payload.error;
  return payload;
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const {
    body: requestBody,
    responseType = 'json',
    idempotent = false,
    idempotencyKey,
    headers: inputHeaders,
    ...fetchOptions
  } = options;
  const method = (fetchOptions.method || 'GET').toUpperCase();

  if (MOCK_MODE) {
    const { mockRequest } = await import('./mock');
    return mockRequest<T>(path, { ...fetchOptions, headers: inputHeaders, body: requestBody, method });
  }

  const headers = new Headers(inputHeaders);
  headers.set('Accept', responseType === 'blob' ? '*/*' : 'application/json');
  const csrf = inMemoryCsrf || cookieValue('csrf_token') || cookieValue('XSRF-TOKEN');
  if (!SAFE_METHODS.has(method) && csrf) headers.set('X-CSRF-Token', csrf);
  if (idempotent) headers.set('Idempotency-Key', idempotencyKey || createUuid());

  let body: BodyInit | undefined;
  if (requestBody instanceof FormData || requestBody instanceof Blob) body = requestBody;
  else if (requestBody !== undefined) {
    if (!headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
    body = JSON.stringify(requestBody);
  }

  const response = await fetch(`${API_BASE}${path}`, {
    ...fetchOptions,
    method,
    headers,
    body,
    credentials: 'include',
  });
  setCsrfToken(response.headers.get('X-CSRF-Token') || undefined);

  if (!response.ok) {
    let payload: unknown;
    try {
      payload = await response.json();
    } catch {
      payload = undefined;
    }
    const error = contractError(payload);
    const legacyDetail = isRecord(payload) ? payload.detail : undefined;
    const message = typeof error.message === 'string'
      ? error.message
      : typeof legacyDetail === 'string'
        ? legacyDetail
        : `请求失败（${response.status}）`;
    throw new ApiError(
      message,
      response.status,
      typeof error.code === 'string' ? error.code : undefined,
      error.details ?? legacyDetail ?? payload,
      typeof error.request_id === 'string' ? error.request_id : response.headers.get('X-Request-ID') || undefined,
    );
  }

  if (responseType === 'blob') return await response.blob() as T;
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

function validPagination(value: unknown): value is PaginationMeta {
  if (!isRecord(value)) return false;
  const keys = Object.keys(value);
  return keys.length === 4
    && keys.every((key) => ['page', 'page_size', 'total_items', 'total_pages'].includes(key))
    && Number.isInteger(value.page) && Number(value.page) >= 1
    && Number.isInteger(value.page_size) && PAGE_SIZES.has(Number(value.page_size))
    && Number.isInteger(value.total_items) && Number(value.total_items) >= 0
    && Number.isInteger(value.total_pages) && Number(value.total_pages) >= 0;
}

export function parsePage<T>(payload: unknown): PageResponse<T> {
  if (!isRecord(payload)
    || Object.keys(payload).length !== 2
    || !Array.isArray(payload.items)
    || !validPagination(payload.pagination)) {
    throw new ApiError('服务端返回了不符合契约的分页数据', 502, 'INVALID_RESPONSE');
  }
  return { items: payload.items as T[], pagination: payload.pagination };
}
