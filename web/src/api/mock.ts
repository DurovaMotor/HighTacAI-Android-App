import { ApiError, type RequestOptions } from './client';
import type {
  AdminUser,
  AndroidDevice,
  BackupRecord,
  Binding,
  BrokerStatus,
  DashboardIncident,
  DashboardSummary,
  DashboardTrends,
  ImportJob,
  LightCommand,
  LightCommandCreate,
  LightTag,
  NetworkSettings,
  OperationLog,
  PageResponse,
  Product,
  SiteSettings,
  Station,
  TagDetail,
} from './types';

const SITE_ID = '2b16ae29-f7ab-4c44-9fc6-a0ca693d9c68';
const ADMIN_ID = 'b9c79a62-3b52-49e6-88d8-4d2ed4577ed2';
const STATION_ID = '90A9F7301427';
const now = Date.now();

function iso(offsetMs = 0) {
  return new Date(now + offsetMs).toISOString();
}

function uuid() {
  if (typeof globalThis.crypto?.randomUUID === 'function') return globalThis.crypto.randomUUID();
  return `${Math.random().toString(16).slice(2, 10).padEnd(8, '0')}-4000-8000-8000-${Math.random().toString(16).slice(2, 14).padEnd(12, '0')}`;
}

function pageSize(value: number): 20 | 50 | 100 {
  return value === 50 || value === 100 ? value : 20;
}

function paginate<T>(items: T[], url: URL): PageResponse<T> {
  const page = Math.max(1, Number(url.searchParams.get('page') || 1));
  const size = pageSize(Number(url.searchParams.get('page_size') || 20));
  return {
    items: items.slice((page - 1) * size, page * size),
    pagination: {
      page,
      page_size: size,
      total_items: items.length,
      total_pages: items.length ? Math.ceil(items.length / size) : 0,
    },
  };
}

function body<T>(options: RequestOptions): T {
  return (options.body || {}) as T;
}

function notFound(resource: string): never {
  throw new ApiError(`${resource}不存在`, 404, 'NOT_FOUND');
}

function contains(value: string | null | undefined, query: string) {
  return Boolean(value?.toLowerCase().includes(query.toLowerCase()));
}

let authenticated = false;
const admin: AdminUser = {
  id: ADMIN_ID,
  username: 'Adam',
  must_change_password: false,
  is_active: true,
  created_at: iso(-90 * 86_400_000),
  last_login_at: iso(-3_600_000),
};

let broker: BrokerStatus = {
  service_name: 'HighTacMqttBroker',
  service_state: 'RUNNING',
  endpoint: '192.168.1.105:1884',
  tcp_reachable: true,
  mqtt_connected: true,
  subscriptions_ready: true,
  started_at: iso(-5 * 3_600_000),
  uptime_seconds: 18_000,
  checked_at: iso(),
};

let stations: Station[] = [
  {
    station_id: STATION_ID,
    site_id: SITE_ID,
    alias: '主仓库基站',
    status: 'ONLINE',
    mac: 'A4:CF:12:73:01:42',
    firmware_version: '1.6.7',
    server_address: '192.168.1.105:1884',
    heartbeat_seconds: 20,
    last_heartbeat_at: iso(-8_000),
    total_count: 18,
    send_count: 2,
    broker_connected: true,
    created_at: iso(-30 * 86_400_000),
    updated_at: iso(-8_000),
  },
  {
    station_id: '90A9F1234567',
    site_id: SITE_ID,
    alias: '测试基站',
    status: 'STALE',
    mac: 'A4:CF:12:12:34:56',
    firmware_version: '1.6.5',
    server_address: '192.168.1.105:1884',
    heartbeat_seconds: 20,
    last_heartbeat_at: iso(-95_000),
    total_count: 4,
    send_count: 0,
    broker_connected: true,
    created_at: iso(-20 * 86_400_000),
    updated_at: iso(-95_000),
  },
  {
    station_id: '90A9F7654321',
    site_id: SITE_ID,
    alias: '备用基站',
    status: 'OFFLINE',
    mac: null,
    firmware_version: null,
    server_address: null,
    heartbeat_seconds: null,
    last_heartbeat_at: iso(-3 * 86_400_000),
    total_count: 0,
    send_count: 0,
    broker_connected: false,
    created_at: iso(-10 * 86_400_000),
    updated_at: iso(-3 * 86_400_000),
  },
];

let tags: LightTag[] = Array.from({ length: 34 }, (_, index) => {
  const level = [100, 90, 80, 60, 30, 10, 0][index % 7] as LightTag['battery_level'];
  const online = index % 7 !== 0;
  return {
    tag_id: `AD1${index.toString(16).toUpperCase().padStart(9, '0')}`,
    site_id: SITE_ID,
    station_id: index % 9 === 0 ? '90A9F1234567' : STATION_ID,
    registered_at: iso(-(index + 5) * 86_400_000),
    first_seen_at: iso(-(index + 3) * 86_400_000),
    last_seen_at: iso(-(index + 1) * 45_000),
    online,
    battery_raw: level === null ? null : Math.round(level / 10) + 20,
    battery_voltage: level === null ? null : 2.6 + Number(level) / 250,
    battery_level: level,
    low_battery: level !== null && level <= 30,
    firmware_version: index % 3 ? '2.0.1' : null,
    group_no: index % 5,
    last_result_type: index % 4 ? 254 : 253,
    is_abnormal: index % 13 === 0,
    abnormal_reason: index % 13 === 0 ? '连续回执超时' : null,
    active_binding_id: null,
  };
});

let products: Product[] = Array.from({ length: 26 }, (_, index) => ({
  id: `00000000-0000-4000-8000-${(index + 1).toString().padStart(12, '0')}`,
  product_code: index === 0 ? '1711A-ABA-PT' : `HT-${String(index + 1000).padStart(5, '0')}`,
  product_name: index === 0 ? '主测试配件' : index % 4 ? `仓储配件 ${index + 1}` : null,
  source: index % 3 ? 'BINDING' : 'EXCEL',
  is_active: index % 17 !== 0,
  active_binding_count: 0,
  created_at: iso(-(index + 15) * 86_400_000),
  updated_at: iso(-index * 3_600_000),
}));

let bindings: Binding[] = tags.slice(0, 22).map((tag, index) => ({
  id: `10000000-0000-4000-8000-${(index + 1).toString().padStart(12, '0')}`,
  product_id: products[index % 9].id,
  product_code: products[index % 9].product_code,
  product_name: products[index % 9].product_name,
  tag_id: tag.tag_id,
  site_id: SITE_ID,
  station_id: tag.station_id || STATION_ID,
  source: index % 3 === 0 ? 'ANDROID' : 'WEB',
  actor_type: index % 3 === 0 ? 'ANDROID' : 'ADMIN',
  actor_id: index % 3 === 0 ? 'ba17796c-c44e-4dc5-9afc-e82af7ef0cf1' : ADMIN_ID,
  actor_display_name: index % 3 === 0 ? '拣货手机 01' : 'Adam',
  bound_at: iso(-(index + 1) * 86_400_000),
  unbound_at: null,
  is_active: true,
}));

function refreshBindingState() {
  products.forEach((product) => {
    product.active_binding_count = bindings.filter((binding) => binding.is_active && binding.product_id === product.id).length;
  });
  tags.forEach((tag) => {
    tag.active_binding_id = bindings.find((binding) => binding.is_active && binding.tag_id === tag.tag_id)?.id || null;
  });
}
refreshBindingState();

let devices: AndroidDevice[] = [
  { id: 'ba17796c-c44e-4dc5-9afc-e82af7ef0cf1', display_name: '拣货手机 01', manufacturer: 'Xiaomi', model: '23127PN0CC', app_version: '2.0.0', status: 'APPROVED', online: true, first_seen_at: iso(-30 * 86_400_000), last_seen_at: iso(-12_000), approved_at: iso(-29 * 86_400_000), revoked_at: null },
  { id: 'c4d5e6f7-1234-4abc-8def-1234567890ab', display_name: '收货手机', manufacturer: 'HONOR', model: 'BVL-AN00', app_version: '2.0.0', status: 'APPROVED', online: false, first_seen_at: iso(-12 * 86_400_000), last_seen_at: iso(-3_600_000), approved_at: iso(-11 * 86_400_000), revoked_at: null },
  { id: 'd5e6f7a8-2345-4bcd-9efa-2345678901bc', display_name: null, manufacturer: 'Samsung', model: 'SM-S9210', app_version: '2.0.0', status: 'PENDING', online: true, first_seen_at: iso(-600_000), last_seen_at: iso(-8_000), approved_at: null, revoked_at: null },
  { id: 'e6f7a8b9-3456-4cde-8fab-3456789012cd', display_name: '已退役手机', manufacturer: 'OPPO', model: 'PGT110', app_version: '1.8.4', status: 'REVOKED', online: false, first_seen_at: iso(-90 * 86_400_000), last_seen_at: iso(-30 * 86_400_000), approved_at: iso(-89 * 86_400_000), revoked_at: iso(-30 * 86_400_000) },
];

let backups: BackupRecord[] = Array.from({ length: 5 }, (_, index) => ({
  id: `20000000-0000-4000-8000-${(index + 1).toString().padStart(12, '0')}`,
  filename: `hightac-${new Date(now - index * 86_400_000).toISOString().slice(0, 10)}.db`,
  reason: index === 0 ? 'MANUAL' : 'SCHEDULED',
  status: index === 4 ? 'FAILED' : 'SUCCEEDED',
  size_bytes: 4_820_000 + index * 12_340,
  sha256: index === 4 ? null : String(index + 1).repeat(64),
  schema_version: '12',
  created_at: iso(-index * 86_400_000),
  completed_at: iso(-index * 86_400_000 + 12_000),
  error_code: index === 4 ? 'INSUFFICIENT_STORAGE' : null,
}));

let siteSettings: SiteSettings = {
  id: SITE_ID,
  name: 'HighTac 主仓库',
  address: 'A 区一层',
  notes: '单站点生产环境',
  low_battery_threshold: 30,
  command_timeout_seconds: 10,
  updated_at: iso(-3_600_000),
};

let networkSettings: NetworkSettings = {
  api_bind_address: '0.0.0.0',
  api_port: 8088,
  mqtt_host: '192.168.1.105',
  mqtt_port: 1884,
  mqtt_tls_enabled: false,
  restart_required: false,
  updated_at: iso(-3_600_000),
};

let logs: OperationLog[] = Array.from({ length: 64 }, (_, index) => ({
  id: `30000000-0000-4000-8000-${(index + 1).toString().padStart(12, '0')}`,
  event_type: index % 5 === 0 ? 'light.command.created' : index % 3 === 0 ? 'binding.created' : 'station.heartbeat',
  site_id: SITE_ID,
  station_id: STATION_ID,
  tag_id: index < tags.length ? tags[index].tag_id : null,
  product_id: products[index % products.length].id,
  command_id: null,
  actor_type: index % 4 === 0 ? 'ANDROID' : 'ADMIN',
  actor_id: index % 4 === 0 ? devices[0].id : ADMIN_ID,
  actor_display_name: index % 4 === 0 ? '拣货手机 01' : 'Adam',
  request_summary: JSON.stringify({ source: 'mock', index }),
  result_summary: JSON.stringify({ accepted: index % 13 !== 0 }),
  client_ip: `192.168.1.${20 + (index % 10)}`,
  device_model: index % 4 === 0 ? devices[0].model : null,
  failure_code: index % 13 === 0 ? 'TAG_UNCONFIRMED' : null,
  occurred_at: iso(-index * 1_800_000),
}));

function addLog(eventType: string, details: Partial<OperationLog> = {}) {
  logs = [{
    id: uuid(),
    event_type: eventType,
    site_id: SITE_ID,
    station_id: null,
    tag_id: null,
    product_id: null,
    command_id: null,
    actor_type: 'ADMIN',
    actor_id: ADMIN_ID,
    actor_display_name: 'Adam',
    request_summary: '{}',
    result_summary: '{"accepted":true}',
    client_ip: '192.168.1.20',
    device_model: null,
    failure_code: null,
    occurred_at: new Date().toISOString(),
    ...details,
  }, ...logs];
}

const commands = new Map<string, LightCommand>();
let importJob: ImportJob | undefined;

function createCommand(input: LightCommandCreate, stationId?: string): LightCommand {
  const targetBindings = input.product_code
    ? bindings.filter((binding) => binding.is_active && binding.product_code === input.product_code)
    : bindings.filter((binding) => binding.is_active && input.tag_ids?.includes(binding.tag_id));
  const targetTags = stationId
    ? bindings.filter((binding) => binding.is_active && binding.station_id === stationId)
    : targetBindings;
  const command: LightCommand = {
    id: uuid(),
    action: input.action,
    product_id: input.product_code ? products.find((product) => product.product_code === input.product_code)?.id || null : null,
    product_code: input.product_code || null,
    requested_color: input.action === 'LIGHT_ON' ? input.color || 'RED' : null,
    status: 'PUBLISHED',
    target_count: targetTags.length,
    confirmed_count: 0,
    unconfirmed_count: 0,
    failed_count: 0,
    created_at: new Date().toISOString(),
    published_at: new Date().toISOString(),
    completed_at: null,
    items: targetTags.map((binding) => ({
      id: uuid(),
      tag_id: binding.tag_id,
      station_id: binding.station_id,
      status: 'PUBLISHED',
      publish_attempts: 1,
      published_at: new Date().toISOString(),
      confirmed_at: null,
      last_result_type: null,
      correlation: 'NONE',
      failure_code: null,
    })),
  };
  commands.set(command.id, command);
  window.setTimeout(() => {
    command.status = 'CONFIRMED';
    command.confirmed_count = command.target_count;
    command.completed_at = new Date().toISOString();
    command.items.forEach((item) => {
      item.status = 'CONFIRMED';
      item.confirmed_at = command.completed_at;
      item.last_result_type = 254;
      item.correlation = 'HEURISTIC_STATE_MATCH';
    });
  }, 700);
  addLog('light.command.created', { command_id: command.id, product_id: command.product_id });
  return command;
}

export async function mockRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  await new Promise((resolve) => setTimeout(resolve, 35));
  const url = new URL(path, 'http://mock.local');
  const route = url.pathname;
  const method = (options.method || 'GET').toUpperCase();

  if (route === '/auth/login' && method === 'POST') {
    const credentials = body<{ username: string; password: string }>(options);
    if (credentials.username !== 'Adam' || credentials.password !== 'Adam') throw new ApiError('用户名或密码错误', 401, 'INVALID_CREDENTIALS');
    admin.must_change_password = typeof sessionStorage !== 'undefined' && sessionStorage.getItem('hightac.mock.must_change') === 'true';
    authenticated = true;
    return { user: admin, csrf_token: uuid(), session_expires_at: iso(8 * 3_600_000) } as T;
  }
  if (route === '/auth/me') {
    if (!authenticated) throw new ApiError('未登录', 401, 'UNAUTHENTICATED');
    return admin as T;
  }
  if (route === '/auth/logout' && method === 'POST') {
    authenticated = false;
    return undefined as T;
  }
  if (route === '/auth/change-password' && method === 'POST') {
    admin.must_change_password = false;
    if (typeof sessionStorage !== 'undefined') sessionStorage.removeItem('hightac.mock.must_change');
    return undefined as T;
  }
  if (route === '/health/live') return { status: 'alive', checked_at: new Date().toISOString() } as T;
  if (route === '/health/ready') return { status: 'READY', checks: [], checked_at: new Date().toISOString() } as T;

  if (route === '/dashboard/summary') {
    const incidents: DashboardIncident[] = logs.filter((item) => item.failure_code).slice(0, 5).map((item) => ({
      id: item.id,
      severity: 'WARNING',
      code: item.failure_code || 'UNKNOWN',
      message: '灯条命令未在确认窗口内回执',
      entity_type: 'tag',
      entity_id: item.tag_id,
      occurred_at: item.occurred_at,
    }));
    const summary: DashboardSummary = {
      api_status: 'READY',
      broker,
      station_counts: {
        total: stations.length,
        online: stations.filter((item) => item.status === 'ONLINE').length,
        stale: stations.filter((item) => item.status === 'STALE').length,
        offline: stations.filter((item) => item.status === 'OFFLINE').length,
        unknown: stations.filter((item) => item.status === 'UNKNOWN').length,
      },
      tag_counts: {
        total: tags.length,
        discovered_24h: 7,
        low_battery: tags.filter((item) => item.low_battery).length,
        abnormal: tags.filter((item) => item.is_abnormal).length,
        unbound: tags.filter((item) => !item.active_binding_id).length,
      },
      product_counts: { total: products.length, active_bindings: bindings.filter((item) => item.is_active).length },
      command_counts_24h: { total: 184, confirmed: 170, partially_confirmed: 3, unconfirmed: 7, failed: 4, confirmation_rate: 0.962 },
      device_counts: {
        pending: devices.filter((item) => item.status === 'PENDING').length,
        approved: devices.filter((item) => item.status === 'APPROVED').length,
        revoked: devices.filter((item) => item.status === 'REVOKED').length,
        online: devices.filter((item) => item.online).length,
      },
      recent_incidents: incidents,
      recent_operations: logs.slice(0, 6),
      generated_at: new Date().toISOString(),
    };
    return summary as T;
  }
  if (route === '/dashboard/trends') {
    const range = url.searchParams.get('range') === '7d' ? '7d' : '24h';
    const count = range === '7d' ? 7 : 24;
    const trends: DashboardTrends = {
      range,
      bucket_seconds: range === '7d' ? 86_400 : 3_600,
      points: Array.from({ length: count }, (_, index) => ({
        bucket_start: iso(-(count - index) * (range === '7d' ? 86_400_000 : 3_600_000)),
        commands: 5 + (index * 7) % 19,
        confirmed: 4 + (index * 7) % 17,
        tags_discovered: index % 4,
        incidents: index % 5 === 0 ? 2 : index % 3,
      })),
      generated_at: new Date().toISOString(),
    };
    return trends as T;
  }

  if (route === '/broker/status') return { ...broker, checked_at: new Date().toISOString() } as T;
  if (route === '/broker/config-summary') return {
    service_name: 'HighTacMqttBroker',
    listener_host: '0.0.0.0',
    listener_port: 1884,
    tls_enabled: false,
    anonymous_enabled: false,
    persistence_enabled: true,
    backend_username: 'hightac_backend',
    station_account_count: 1,
  } as T;
  if (route === '/broker/logs') {
    const requested = Number(url.searchParams.get('lines') || 200) as 50 | 200 | 500;
    const entries = Array.from({ length: Math.min(requested, 34) }, (_, index) => `${iso(-(33 - index) * 12_000)} [info] ${index % 3 === 0 ? 'Client estation_90A9F7301427 heartbeat received' : index % 3 === 1 ? 'PUBLISH /estation/90A9F7301427/result' : 'Bridge subscriptions healthy'}`);
    return { service_name: 'HighTacMqttBroker', requested_lines: requested, returned_lines: entries.length, lines: entries, truncated: false, read_at: new Date().toISOString() } as T;
  }
  if (/^\/broker\/(start|stop|restart)$/.test(route) && method === 'POST') {
    const action = route.split('/').at(-1)!.toUpperCase() as 'START' | 'STOP' | 'RESTART';
    if (action === 'STOP' && body<{ confirmation?: string }>(options).confirmation !== 'STOP BROKER') throw new ApiError('确认短语不正确', 400, 'CONFIRMATION_REQUIRED');
    const running = action !== 'STOP';
    broker = {
      ...broker,
      service_state: running ? 'RUNNING' : 'STOPPED',
      tcp_reachable: running,
      mqtt_connected: running,
      subscriptions_ready: running,
      started_at: running ? new Date().toISOString() : null,
      uptime_seconds: running ? 1 : null,
      checked_at: new Date().toISOString(),
    };
    addLog(`broker.${action.toLowerCase()}`);
    return { id: uuid(), action, status: 'ACCEPTED', requested_at: new Date().toISOString() } as T;
  }

  if (route === '/stations' && method === 'GET') {
    const query = url.searchParams.get('q') || '';
    const status = url.searchParams.get('status');
    return paginate(stations.filter((item) => (!query || contains(item.station_id, query) || contains(item.alias, query)) && (!status || item.status === status)), url) as T;
  }
  if (route === '/stations' && method === 'POST') {
    const input = body<{ station_id: string; site_id: string; alias?: string | null }>(options);
    if (stations.some((item) => item.station_id === input.station_id)) throw new ApiError('基站已存在', 409, 'STATION_EXISTS');
    const station: Station = {
      station_id: input.station_id,
      site_id: input.site_id,
      alias: input.alias || null,
      status: 'UNKNOWN',
      mac: null,
      firmware_version: null,
      server_address: null,
      heartbeat_seconds: null,
      last_heartbeat_at: null,
      total_count: 0,
      send_count: 0,
      broker_connected: false,
      created_at: new Date().toISOString(),
      updated_at: new Date().toISOString(),
    };
    stations.unshift(station);
    addLog('station.created', { station_id: station.station_id });
    return station as T;
  }
  const stationAllOff = route.match(/^\/stations\/([^/]+)\/all-off$/);
  if (stationAllOff && method === 'POST') {
    if (body<{ confirmation?: string }>(options).confirmation !== 'ALL OFF') throw new ApiError('确认短语不正确', 400, 'CONFIRMATION_REQUIRED');
    return createCommand({ action: 'LIGHT_OFF' }, decodeURIComponent(stationAllOff[1])) as T;
  }
  const stationChecklist = route.match(/^\/stations\/([^/]+)\/connection-checklist$/);
  if (stationChecklist) {
    const id = decodeURIComponent(stationChecklist[1]);
    return {
      station_id: id,
      items: [
        { key: 'broker_address', label: 'Broker 地址', value: '192.168.1.105:1884', sensitive: false, status: 'READY' },
        { key: 'station_account', label: '基站账号', value: id, sensitive: false, status: 'READY' },
        { key: 'heartbeat', label: '心跳', value: '最近 8 秒内收到', sensitive: false, status: 'READY' },
      ],
      generated_at: new Date().toISOString(),
    } as T;
  }
  const stationRoute = route.match(/^\/stations\/([^/]+)$/);
  if (stationRoute) {
    const station = stations.find((item) => item.station_id === decodeURIComponent(stationRoute[1])) || notFound('基站');
    if (method === 'PATCH') {
      station.alias = body<{ alias?: string | null }>(options).alias ?? station.alias;
      station.updated_at = new Date().toISOString();
    }
    return station as T;
  }

  if (['/tags', '/tags/low-battery', '/tags/abnormal'].includes(route) && method === 'GET') {
    const query = url.searchParams.get('q') || '';
    const stationId = url.searchParams.get('station_id');
    const online = url.searchParams.get('online');
    const low = url.searchParams.get('low_battery');
    const bound = url.searchParams.get('bound');
    let filtered = tags.filter((item) =>
      (!query || contains(item.tag_id, query) || bindings.some((binding) => binding.tag_id === item.tag_id && contains(binding.product_code, query)))
      && (!stationId || item.station_id === stationId)
      && (online === null || item.online === (online === 'true'))
      && (low === null || item.low_battery === (low === 'true'))
      && (bound === null || Boolean(item.active_binding_id) === (bound === 'true')));
    if (route.endsWith('low-battery')) filtered = filtered.filter((item) => item.low_battery);
    if (route.endsWith('abnormal')) filtered = filtered.filter((item) => item.is_abnormal);
    return paginate(filtered, url) as T;
  }
  if (route === '/tags/register' && method === 'POST') {
    const input = body<{ tag_id: string; station_id: string }>(options);
    if (tags.some((item) => item.tag_id === input.tag_id)) throw new ApiError('灯条已登记', 409, 'TAG_EXISTS');
    const tag: LightTag = {
      tag_id: input.tag_id,
      site_id: SITE_ID,
      station_id: input.station_id,
      registered_at: new Date().toISOString(),
      first_seen_at: null,
      last_seen_at: null,
      online: false,
      battery_raw: null,
      battery_voltage: null,
      battery_level: null,
      low_battery: false,
      firmware_version: null,
      group_no: null,
      last_result_type: null,
      is_abnormal: false,
      abnormal_reason: null,
      active_binding_id: null,
    };
    tags.unshift(tag);
    addLog('tag.registered', { tag_id: tag.tag_id, station_id: tag.station_id });
    return tag as T;
  }
  const tagRoute = route.match(/^\/tags\/([^/]+)$/);
  if (tagRoute) {
    const tag = tags.find((item) => item.tag_id === decodeURIComponent(tagRoute[1])) || notFound('灯条');
    const activeBinding = bindings.find((binding) => binding.is_active && binding.tag_id === tag.tag_id) || null;
    const detail: TagDetail = {
      tag,
      active_binding: activeBinding,
      recent_history: Array.from({ length: 5 }, (_, index) => ({
        id: uuid(),
        occurred_at: iso(-(index + 1) * 3_600_000),
        online: tag.online,
        battery_raw: tag.battery_raw,
        battery_level: tag.battery_level,
        result_type: tag.last_result_type,
        is_abnormal: index === 0 && tag.is_abnormal,
        abnormal_reason: index === 0 ? tag.abnormal_reason : null,
      })),
    };
    return detail as T;
  }

  if (route === '/products' && method === 'GET') {
    const query = url.searchParams.get('q') || '';
    const source = url.searchParams.get('source');
    const tagId = url.searchParams.get('tag_id');
    const isActive = url.searchParams.get('is_active');
    return paginate(products.filter((item) =>
      (!query || contains(item.product_code, query) || contains(item.product_name, query))
      && (!source || item.source === source)
      && (!tagId || bindings.some((binding) => binding.is_active && binding.product_id === item.id && binding.tag_id === tagId))
      && (isActive === null || item.is_active === (isActive === 'true'))), url) as T;
  }
  if (route === '/products' && method === 'POST') {
    const input = body<{ product_code: string; product_name?: string | null }>(options);
    if (products.some((item) => item.product_code === input.product_code)) throw new ApiError('产品编码已存在', 409, 'PRODUCT_EXISTS');
    const product: Product = {
      id: uuid(),
      product_code: input.product_code,
      product_name: input.product_name || null,
      source: 'BINDING',
      is_active: true,
      active_binding_count: 0,
      created_at: new Date().toISOString(),
      updated_at: new Date().toISOString(),
    };
    products.unshift(product);
    return product as T;
  }
  if (route === '/products/import-template') return new Blob(['mock xlsx template']) as T;
  if (route === '/products/imports' && method === 'POST') {
    const file = body<FormData>(options).get('file') as File | null;
    const invalid = Boolean(file?.name.toLowerCase().includes('invalid'));
    importJob = {
      id: uuid(),
      filename: file?.name || 'products.xlsx',
      status: 'UPLOADED',
      total_rows: 12,
      valid_rows: invalid ? 10 : 12,
      error_rows: invalid ? 2 : 0,
      errors: invalid ? [
        { row_number: 4, field: 'product_code', code: 'REQUIRED', value: '', message: '产品编码不能为空' },
        { row_number: 9, field: 'product_code', code: 'DUPLICATE', value: '1711A', message: '产品编码重复' },
      ] : [],
      errors_truncated: false,
      created_at: new Date().toISOString(),
      validated_at: null,
      committed_at: null,
    };
    return importJob as T;
  }
  const importErrorRoute = route.match(/^\/products\/imports\/([^/]+)\/errors$/);
  if (importErrorRoute) return new Blob(['row_number,field,code,message,value\n4,product_code,REQUIRED,产品编码不能为空,']) as T;
  const importCommitRoute = route.match(/^\/products\/imports\/([^/]+)\/commit$/);
  if (importCommitRoute && method === 'POST') {
    if (!importJob || importJob.id !== importCommitRoute[1]) notFound('导入任务');
    if (importJob!.status !== 'READY') throw new ApiError('导入任务不可提交', 409, 'IMPORT_NOT_READY');
    importJob = { ...importJob!, status: 'COMMITTING' };
    return importJob as T;
  }
  const importGetRoute = route.match(/^\/products\/imports\/([^/]+)$/);
  if (importGetRoute) {
    if (!importJob || importJob.id !== importGetRoute[1]) notFound('导入任务');
    if (importJob!.status === 'UPLOADED') importJob = { ...importJob!, status: 'VALIDATING' };
    else if (importJob!.status === 'VALIDATING') importJob = { ...importJob!, status: importJob!.error_rows ? 'INVALID' : 'READY', validated_at: new Date().toISOString() };
    else if (importJob!.status === 'COMMITTING') importJob = { ...importJob!, status: 'COMMITTED', committed_at: new Date().toISOString() };
    return importJob as T;
  }
  const productRoute = route.match(/^\/products\/([^/]+)$/);
  if (productRoute) {
    const product = products.find((item) => item.id === decodeURIComponent(productRoute[1])) || notFound('产品');
    if (method === 'PATCH') {
      const patch = body<{ product_name?: string | null; is_active?: boolean }>(options);
      if ('product_name' in patch) product.product_name = patch.product_name ?? null;
      if ('is_active' in patch && patch.is_active !== undefined) product.is_active = patch.is_active;
      product.updated_at = new Date().toISOString();
      return product as T;
    }
    return { product, active_bindings: bindings.filter((binding) => binding.is_active && binding.product_id === product.id) } as T;
  }

  if (route === '/bindings' && method === 'GET') {
    const source = url.searchParams.get('source');
    const productId = url.searchParams.get('product_id');
    const productCode = url.searchParams.get('product_code');
    const tagId = url.searchParams.get('tag_id');
    const stationId = url.searchParams.get('station_id');
    const isActive = url.searchParams.get('is_active');
    return paginate(bindings.filter((item) =>
      (!source || item.source === source)
      && (!productId || item.product_id === productId)
      && (!productCode || item.product_code === productCode)
      && (!tagId || item.tag_id === tagId)
      && (!stationId || item.station_id === stationId)
      && (isActive === null || item.is_active === (isActive === 'true'))), url) as T;
  }
  if (route === '/bindings' && method === 'POST') {
    const input = body<{ product_code: string; product_name?: string | null; tag_id: string; station_id: string }>(options);
    if (bindings.some((item) => item.tag_id === input.tag_id && item.is_active)) throw new ApiError('灯条已绑定其他产品', 409, 'TAG_ALREADY_BOUND');
    let product = products.find((item) => item.product_code === input.product_code);
    if (!product) {
      product = {
        id: uuid(),
        product_code: input.product_code,
        product_name: input.product_name || null,
        source: 'BINDING',
        is_active: true,
        active_binding_count: 0,
        created_at: new Date().toISOString(),
        updated_at: new Date().toISOString(),
      };
      products.unshift(product);
    }
    const binding: Binding = {
      id: uuid(),
      product_id: product.id,
      product_code: product.product_code,
      product_name: product.product_name,
      tag_id: input.tag_id,
      site_id: SITE_ID,
      station_id: input.station_id,
      source: 'WEB',
      actor_type: 'ADMIN',
      actor_id: ADMIN_ID,
      actor_display_name: 'Adam',
      bound_at: new Date().toISOString(),
      unbound_at: null,
      is_active: true,
    };
    bindings.unshift(binding);
    refreshBindingState();
    addLog('binding.created', { tag_id: binding.tag_id, product_id: binding.product_id, station_id: binding.station_id });
    return binding as T;
  }
  const rebindRoute = route.match(/^\/bindings\/([^/]+)\/rebind$/);
  if (rebindRoute && method === 'POST') {
    const old = bindings.find((item) => item.id === decodeURIComponent(rebindRoute[1]) && item.is_active) || notFound('绑定');
    const input = body<{ product_code: string; product_name?: string | null; expected_tag_id: string }>(options);
    if (input.expected_tag_id !== old.tag_id) throw new ApiError('灯条与预期不一致', 409, 'BINDING_CHANGED');
    let product = products.find((item) => item.product_code === input.product_code);
    if (!product) {
      product = {
        id: uuid(),
        product_code: input.product_code,
        product_name: input.product_name || null,
        source: 'BINDING',
        is_active: true,
        active_binding_count: 0,
        created_at: new Date().toISOString(),
        updated_at: new Date().toISOString(),
      };
      products.unshift(product);
    }
    old.is_active = false;
    old.unbound_at = new Date().toISOString();
    const replacement: Binding = {
      ...old,
      id: uuid(),
      product_id: product.id,
      product_code: product.product_code,
      product_name: product.product_name,
      bound_at: new Date().toISOString(),
      unbound_at: null,
      is_active: true,
      source: 'WEB',
      actor_type: 'ADMIN',
      actor_id: ADMIN_ID,
      actor_display_name: 'Adam',
    };
    bindings.unshift(replacement);
    refreshBindingState();
    addLog('binding.rebound', { tag_id: old.tag_id, product_id: product.id, station_id: old.station_id });
    return { removed_binding: old, created_binding: replacement } as T;
  }
  const bindingRoute = route.match(/^\/bindings\/([^/]+)$/);
  if (bindingRoute && method === 'DELETE') {
    const binding = bindings.find((item) => item.id === decodeURIComponent(bindingRoute[1]) && item.is_active) || notFound('绑定');
    const headers = new Headers(options.headers);
    if (headers.get('confirmation') !== 'UNBIND') throw new ApiError('需要解绑确认', 400, 'CONFIRMATION_REQUIRED');
    binding.is_active = false;
    binding.unbound_at = new Date().toISOString();
    refreshBindingState();
    addLog('binding.removed', { tag_id: binding.tag_id, product_id: binding.product_id, station_id: binding.station_id });
    return binding as T;
  }

  if (route === '/light-commands' && method === 'POST') return createCommand(body<LightCommandCreate>(options)) as T;
  const commandRoute = route.match(/^\/light-commands\/([^/]+)$/);
  if (commandRoute) return (commands.get(decodeURIComponent(commandRoute[1])) || notFound('命令')) as T;

  if (route === '/android-devices' && method === 'GET') {
    const status = url.searchParams.get('status');
    return paginate(devices.filter((item) => !status || item.status === status), url) as T;
  }
  const deviceAction = route.match(/^\/android-devices\/([^/]+)\/(approve|revoke|rename)$/);
  if (deviceAction && method === 'POST') {
    const device = devices.find((item) => item.id === decodeURIComponent(deviceAction[1])) || notFound('设备');
    const action = deviceAction[2];
    if (action === 'revoke') {
      if (body<{ confirmation?: string }>(options).confirmation !== 'REVOKE DEVICE') throw new ApiError('确认短语不正确', 400, 'CONFIRMATION_REQUIRED');
      device.status = 'REVOKED';
      device.online = false;
      device.revoked_at = new Date().toISOString();
    } else {
      device.display_name = body<{ display_name: string }>(options).display_name;
      if (action === 'approve') {
        device.status = 'APPROVED';
        device.approved_at = new Date().toISOString();
      }
    }
    addLog(`device.${action}`);
    return device as T;
  }

  if (route === '/operation-logs') {
    const actor = url.searchParams.get('actor_type');
    const event = (url.searchParams.get('event_type') || '').toLowerCase();
    const entityId = (url.searchParams.get('entity_id') || '').toLowerCase();
    return paginate(logs.filter((item) =>
      (!actor || item.actor_type === actor)
      && (!event || item.event_type.toLowerCase().includes(event))
      && (!entityId || [item.station_id, item.tag_id, item.product_id, item.command_id].some((value) => value?.toLowerCase().includes(entityId)))), url) as T;
  }
  if (route === '/operation-logs/export') return new Blob(['id,event_type,occurred_at\nmock,station.heartbeat,2026-07-16T00:00:00Z']) as T;

  if (route === '/settings/site') {
    if (method === 'PATCH') siteSettings = { ...siteSettings, ...body<Partial<SiteSettings>>(options), updated_at: new Date().toISOString() };
    return siteSettings as T;
  }
  if (route === '/settings/network') {
    if (method === 'PATCH') networkSettings = { ...networkSettings, ...body<Partial<NetworkSettings>>(options), restart_required: true, updated_at: new Date().toISOString() };
    return networkSettings as T;
  }

  if (route === '/backups' && method === 'GET') return paginate(backups, url) as T;
  if (route === '/backups' && method === 'POST') {
    const backup: BackupRecord = {
      id: uuid(),
      filename: `hightac-${new Date().toISOString().replaceAll(':', '').slice(0, 15)}Z.db`,
      reason: 'MANUAL',
      status: 'RUNNING',
      size_bytes: null,
      sha256: null,
      schema_version: '12',
      created_at: new Date().toISOString(),
      completed_at: null,
      error_code: null,
    };
    backups.unshift(backup);
    window.setTimeout(() => Object.assign(backup, {
      status: 'SUCCEEDED',
      size_bytes: 4_910_000,
      sha256: 'b'.repeat(64),
      completed_at: new Date().toISOString(),
    }), 500);
    addLog('backup.created');
    return backup as T;
  }
  const restoreRoute = route.match(/^\/backups\/([^/]+)\/restore$/);
  if (restoreRoute && method === 'POST') {
    const backup = backups.find((item) => item.id === decodeURIComponent(restoreRoute[1])) || notFound('备份');
    const input = body<{ confirmation: string; expected_sha256: string }>(options);
    if (input.confirmation !== 'RESTORE BACKUP' || input.expected_sha256 !== backup.sha256) throw new ApiError('恢复确认或校验值不匹配', 422, 'RESTORE_CONFIRMATION_INVALID');
    addLog('backup.restore.requested');
    return { id: uuid(), backup_id: backup.id, status: 'ACCEPTED', pre_restore_backup_id: null, requested_at: new Date().toISOString() } as T;
  }

  throw new ApiError(`Mock 未实现 ${method} ${route}`, 501, 'MOCK_NOT_IMPLEMENTED');
}
