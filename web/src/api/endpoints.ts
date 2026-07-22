import { apiRequest, parsePage, queryString, setCsrfToken } from './client';
import type {
  AdminUser,
  BackupRecord,
  Binding,
  BrokerConfigSummary,
  BrokerControlOperation,
  BrokerLogs,
  BrokerStatus,
  ConnectionChecklist,
  DashboardSummary,
  DashboardTrends,
  ImportJob,
  LightCommand,
  LightCommandCreate,
  LightTag,
  LoginResponse,
  NetworkSettings,
  NetworkSettingsPatch,
  OperationLog,
  PageResponse,
  Product,
  ProductDetail,
  RebindResult,
  RestoreOperation,
  SiteSettings,
  SiteSettingsPatch,
  Station,
  StationCreate,
  StationPatch,
  TagDetail,
} from './types';

type Params = Record<string, unknown>;

async function page<T>(path: string, params: Params): Promise<PageResponse<T>> {
  return parsePage<T>(await apiRequest<unknown>(`${path}${queryString(params)}`));
}

function mergePatch<T>(path: string, body: unknown) {
  return apiRequest<T>(path, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/merge-patch+json' },
    body,
  });
}

export const api = {
  auth: {
    async login(payload: { username: string; password: string }) {
      const result = await apiRequest<LoginResponse>('/auth/login', { method: 'POST', body: payload });
      setCsrfToken(result.csrf_token);
      return result.user;
    },
    me: () => apiRequest<AdminUser>('/auth/me'),
    logout: () => apiRequest<void>('/auth/logout', { method: 'POST' }),
    changePassword: (payload: { current_password: string; new_password: string }) =>
      apiRequest<void>('/auth/change-password', { method: 'POST', body: payload }),
  },
  health: {
    live: () => apiRequest<Record<string, unknown>>('/health/live'),
    ready: () => apiRequest<Record<string, unknown>>('/health/ready'),
  },
  dashboard: {
    summary: () => apiRequest<DashboardSummary>('/dashboard/summary'),
    trends: (range: '24h' | '7d' = '24h') => apiRequest<DashboardTrends>(`/dashboard/trends${queryString({ range })}`),
  },
  broker: {
    status: () => apiRequest<BrokerStatus>('/broker/status'),
    start: () => apiRequest<BrokerControlOperation>('/broker/start', { method: 'POST', idempotent: true }),
    stop: () => apiRequest<BrokerControlOperation>('/broker/stop', { method: 'POST', body: { confirmation: 'STOP BROKER' }, idempotent: true }),
    restart: () => apiRequest<BrokerControlOperation>('/broker/restart', { method: 'POST', idempotent: true }),
    logs: (lines: 50 | 200 | 500) => apiRequest<BrokerLogs>(`/broker/logs${queryString({ lines })}`),
    config: () => apiRequest<BrokerConfigSummary>('/broker/config-summary'),
  },
  stations: {
    list: (params: Params) => page<Station>('/stations', params),
    get: (id: string) => apiRequest<Station>(`/stations/${encodeURIComponent(id)}`),
    create: (payload: StationCreate) => apiRequest<Station>('/stations', { method: 'POST', body: payload, idempotent: true }),
    update: (id: string, payload: StationPatch) => mergePatch<Station>(`/stations/${encodeURIComponent(id)}`, payload),
    checklist: (id: string) => apiRequest<ConnectionChecklist>(`/stations/${encodeURIComponent(id)}/connection-checklist`),
    allOff: (id: string) => apiRequest<LightCommand>(`/stations/${encodeURIComponent(id)}/all-off`, {
      method: 'POST',
      body: { confirmation: 'ALL OFF' },
      idempotent: true,
    }),
  },
  tags: {
    list: (params: Params, view: 'all' | 'low-battery' | 'abnormal' = 'all') => {
      const path = view === 'low-battery' ? '/tags/low-battery' : view === 'abnormal' ? '/tags/abnormal' : '/tags';
      if (view === 'all') return page<LightTag>(path, params);
      const { page: pageNumber, page_size, sort, station_id } = params;
      return page<LightTag>(path, { page: pageNumber, page_size, sort, station_id });
    },
    get: (id: string) => apiRequest<TagDetail>(`/tags/${encodeURIComponent(id)}`),
    register: (payload: { tag_id: string; station_id: string }) =>
      apiRequest<LightTag>('/tags/register', { method: 'POST', body: payload, idempotent: true }),
  },
  products: {
    list: (params: Params) => page<Product>('/products', params),
    get: (id: string) => apiRequest<ProductDetail>(`/products/${encodeURIComponent(id)}`),
    create: (payload: { product_code: string; product_name?: string | null }) =>
      apiRequest<Product>('/products', { method: 'POST', body: payload, idempotent: true }),
    update: (id: string, payload: { product_name?: string | null; is_active?: boolean }) =>
      mergePatch<Product>(`/products/${encodeURIComponent(id)}`, payload),
    template: () => apiRequest<Blob>('/products/import-template', { responseType: 'blob' }),
    previewImport: (file: File) => {
      const body = new FormData();
      body.append('file', file);
      return apiRequest<ImportJob>('/products/imports', { method: 'POST', body, idempotent: true });
    },
    importJob: (id: string) => apiRequest<ImportJob>(`/products/imports/${encodeURIComponent(id)}`),
    importErrors: (id: string) => apiRequest<Blob>(`/products/imports/${encodeURIComponent(id)}/errors`, { responseType: 'blob' }),
    commitImport: (id: string) => apiRequest<ImportJob>(`/products/imports/${encodeURIComponent(id)}/commit`, { method: 'POST', idempotent: true }),
  },
  bindings: {
    list: (params: Params) => page<Binding>('/bindings', params),
    create: (payload: { product_code: string; product_name?: string | null; tag_id: string; station_id: string }) =>
      apiRequest<Binding>('/bindings', { method: 'POST', body: payload, idempotent: true }),
    remove: (id: string) => apiRequest<Binding>(`/bindings/${encodeURIComponent(id)}`, {
      method: 'DELETE',
      headers: { confirmation: 'UNBIND' },
      idempotent: true,
    }),
    rebind: (id: string, payload: { product_code: string; product_name?: string | null; expected_tag_id: string }) =>
      apiRequest<RebindResult>(`/bindings/${encodeURIComponent(id)}/rebind`, { method: 'POST', body: payload, idempotent: true }),
  },
  commands: {
    create: (payload: LightCommandCreate) => apiRequest<LightCommand>('/light-commands', { method: 'POST', body: payload, idempotent: true }),
    get: (id: string) => apiRequest<LightCommand>(`/light-commands/${encodeURIComponent(id)}`),
  },
  logs: {
    list: (params: Params) => page<OperationLog>('/operation-logs', params),
    export: (params: Params, format: 'csv' | 'xlsx' = 'csv') => {
      const { event_type, actor_type, occurred_from, occurred_to } = params;
      return apiRequest<Blob>(`/operation-logs/export${queryString({ format, event_type, actor_type, occurred_from, occurred_to })}`, { responseType: 'blob' });
    },
  },
  settings: {
    site: () => apiRequest<SiteSettings>('/settings/site'),
    updateSite: (payload: SiteSettingsPatch) => mergePatch<SiteSettings>('/settings/site', payload),
    network: () => apiRequest<NetworkSettings>('/settings/network'),
    updateNetwork: (payload: NetworkSettingsPatch) => mergePatch<NetworkSettings>('/settings/network', payload),
  },
  backups: {
    list: (params: Params) => page<BackupRecord>('/backups', params),
    create: () => apiRequest<BackupRecord>('/backups', { method: 'POST', idempotent: true }),
    restore: (id: string, expected_sha256: string) =>
      apiRequest<RestoreOperation>(`/backups/${encodeURIComponent(id)}/restore`, {
        method: 'POST',
        body: { confirmation: 'RESTORE BACKUP', expected_sha256 },
        idempotent: true,
      }),
  },
};
