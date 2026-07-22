export type Timestamp = string | null | undefined;
export type Uuid = string;

export interface PaginationMeta {
  page: number;
  page_size: 20 | 50 | 100;
  total_items: number;
  total_pages: number;
}

export interface PageResponse<T> {
  items: T[];
  pagination: PaginationMeta;
}

export interface AdminUser {
  id: Uuid;
  username: string;
  must_change_password: boolean;
  is_active: boolean;
  created_at: string;
  last_login_at: string | null;
}

export interface LoginResponse {
  user: AdminUser;
  csrf_token: string;
  session_expires_at: string;
}

export type CheckStatus = 'READY' | 'DEGRADED' | 'NOT_READY';
export type BrokerState = 'STOPPED' | 'STARTING' | 'RUNNING' | 'STOPPING' | 'FAILED' | 'UNKNOWN';

export interface BrokerStatus {
  service_name: 'HighTacMqttBroker';
  service_state: BrokerState;
  endpoint: string;
  tcp_reachable: boolean;
  mqtt_connected: boolean;
  subscriptions_ready: boolean;
  started_at: string | null;
  uptime_seconds: number | null;
  checked_at: string;
}

export interface BrokerConfigSummary {
  service_name: 'HighTacMqttBroker';
  listener_host: string;
  listener_port: number;
  tls_enabled: boolean;
  anonymous_enabled: false;
  persistence_enabled: boolean;
  backend_username: string;
  station_account_count: number;
}

export interface BrokerLogs {
  service_name: 'HighTacMqttBroker';
  requested_lines: 50 | 200 | 500;
  returned_lines: number;
  lines: string[];
  truncated: boolean;
  read_at: string;
}

export interface BrokerControlOperation {
  id: Uuid;
  action: 'START' | 'STOP' | 'RESTART';
  status: 'ACCEPTED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';
  requested_at: string;
}

export interface DashboardIncident {
  id: Uuid;
  severity: 'INFO' | 'WARNING' | 'ERROR';
  code: string;
  message: string;
  entity_type: string;
  entity_id: string | null;
  occurred_at: string;
}

export interface DashboardSummary {
  api_status: CheckStatus;
  broker: BrokerStatus;
  station_counts: {
    total: number;
    online: number;
    stale: number;
    offline: number;
    unknown: number;
  };
  tag_counts: {
    total: number;
    discovered_24h: number;
    low_battery: number;
    abnormal: number;
    unbound: number;
  };
  product_counts: {
    total: number;
    active_bindings: number;
  };
  command_counts_24h: {
    total: number;
    confirmed: number;
    partially_confirmed: number;
    unconfirmed: number;
    failed: number;
    confirmation_rate: number;
  };
  device_counts: {
    pending: number;
    approved: number;
    revoked: number;
    online: number;
  };
  recent_incidents: DashboardIncident[];
  recent_operations: OperationLog[];
  generated_at: string;
}

export interface DashboardTrendPoint {
  bucket_start: string;
  commands: number;
  confirmed: number;
  tags_discovered: number;
  incidents: number;
}

export interface DashboardTrends {
  range: '24h' | '7d';
  bucket_seconds: number;
  points: DashboardTrendPoint[];
  generated_at: string;
}

export type StationStatus = 'UNKNOWN' | 'ONLINE' | 'STALE' | 'OFFLINE';

export interface Station {
  station_id: string;
  site_id: Uuid;
  alias: string | null;
  status: StationStatus;
  mac: string | null;
  firmware_version: string | null;
  server_address: string | null;
  heartbeat_seconds: number | null;
  last_heartbeat_at: string | null;
  total_count: number;
  send_count: number;
  broker_connected: boolean;
  created_at: string;
  updated_at: string;
}

export interface StationCreate {
  station_id: string;
  site_id: Uuid;
  alias?: string | null;
}

export interface StationPatch {
  alias?: string | null;
}

export interface ConnectionChecklistItem {
  key: string;
  label: string;
  value: string;
  sensitive: boolean;
  status: 'READY' | 'ACTION_REQUIRED' | 'NOT_APPLICABLE';
}

export interface ConnectionChecklist {
  station_id: string;
  items: ConnectionChecklistItem[];
  generated_at: string;
}

export type BatteryLevel = 0 | 10 | 30 | 60 | 80 | 90 | 100 | null;

export interface LightTag {
  tag_id: string;
  site_id: Uuid | null;
  station_id: string | null;
  registered_at: string | null;
  first_seen_at: string | null;
  last_seen_at: string | null;
  online: boolean;
  battery_raw: number | null;
  battery_voltage: number | null;
  battery_level: BatteryLevel;
  low_battery: boolean;
  firmware_version: string | null;
  group_no: number | null;
  last_result_type: number | null;
  is_abnormal: boolean;
  abnormal_reason: string | null;
  active_binding_id: Uuid | null;
}

export interface TagStatusHistoryItem {
  id: Uuid;
  occurred_at: string;
  online: boolean;
  battery_raw: number | null;
  battery_level: BatteryLevel;
  result_type: number | null;
  is_abnormal: boolean;
  abnormal_reason: string | null;
}

export interface TagDetail {
  tag: LightTag;
  active_binding: Binding | null;
  recent_history: TagStatusHistoryItem[];
}

export interface Product {
  id: Uuid;
  product_code: string;
  product_name: string | null;
  source: 'BINDING' | 'EXCEL';
  is_active: boolean;
  active_binding_count: number;
  created_at: string;
  updated_at: string;
}

export interface ProductDetail {
  product: Product;
  active_bindings: Binding[];
}

export type BindingSource = 'ANDROID' | 'WEB' | 'MIGRATION';
export type ActorType = 'ADMIN' | 'ANDROID' | 'SYSTEM';

export interface Binding {
  id: Uuid;
  product_id: Uuid;
  product_code: string;
  product_name: string | null;
  tag_id: string;
  site_id: Uuid;
  station_id: string;
  source: BindingSource;
  actor_type: ActorType;
  actor_id: string;
  actor_display_name: string;
  bound_at: string;
  unbound_at: string | null;
  is_active: boolean;
}

export interface RebindResult {
  removed_binding: Binding;
  created_binding: Binding;
}

export interface ImportErrorRow {
  row_number: number;
  field: 'product_code' | 'product_name' | 'workbook';
  code: string;
  message: string;
  value: string | number | boolean | null;
}

export type ImportStatus = 'UPLOADED' | 'VALIDATING' | 'READY' | 'INVALID' | 'COMMITTING' | 'COMMITTED' | 'FAILED';

export interface ImportJob {
  id: Uuid;
  filename: string;
  status: ImportStatus;
  total_rows: number;
  valid_rows: number;
  error_rows: number;
  errors: ImportErrorRow[];
  errors_truncated: boolean;
  created_at: string;
  validated_at: string | null;
  committed_at: string | null;
}

export type LightAction = 'LIGHT_ON' | 'LIGHT_OFF';
export type LightColor = 'RED' | 'GREEN' | 'BLUE' | 'CYAN' | 'PINK';
export type CommandStatus = 'ACCEPTED' | 'PUBLISHED' | 'CONFIRMED' | 'PARTIALLY_CONFIRMED' | 'UNCONFIRMED' | 'FAILED' | 'SUPERSEDED';
export type CommandItemStatus = 'PENDING' | 'PUBLISHED' | 'CONFIRMED' | 'UNCONFIRMED' | 'FAILED' | 'SUPERSEDED';

export interface LightCommandCreate {
  action: LightAction;
  product_code?: string;
  tag_ids?: string[];
  color?: LightColor;
}

export interface CommandItem {
  id: Uuid;
  tag_id: string;
  station_id: string;
  status: CommandItemStatus;
  publish_attempts: number;
  published_at: string | null;
  confirmed_at: string | null;
  last_result_type: number | null;
  correlation: 'NONE' | 'HEURISTIC_STATION_TAG_TIME' | 'HEURISTIC_STATE_MATCH';
  failure_code: string | null;
}

export interface LightCommand {
  id: Uuid;
  action: LightAction;
  product_id: Uuid | null;
  product_code: string | null;
  requested_color: LightColor | null;
  status: CommandStatus;
  target_count: number;
  confirmed_count: number;
  unconfirmed_count: number;
  failed_count: number;
  created_at: string;
  published_at: string | null;
  completed_at: string | null;
  items: CommandItem[];
}

export type DeviceStatus = 'PENDING' | 'APPROVED' | 'REVOKED';

export interface AndroidDevice {
  id: Uuid;
  display_name: string | null;
  manufacturer: string;
  model: string;
  app_version: string;
  status: DeviceStatus;
  online: boolean;
  first_seen_at: string;
  last_seen_at: string | null;
  approved_at: string | null;
  revoked_at: string | null;
}

export interface OperationLog {
  id: Uuid;
  event_type: string;
  site_id: Uuid | null;
  station_id: string | null;
  tag_id: string | null;
  product_id: Uuid | null;
  command_id: Uuid | null;
  actor_type: ActorType;
  actor_id: string;
  actor_display_name: string;
  request_summary: string;
  result_summary: string;
  client_ip: string | null;
  device_model: string | null;
  failure_code: string | null;
  occurred_at: string;
}

export interface SiteSettings {
  id: Uuid;
  name: string;
  address: string | null;
  notes: string | null;
  low_battery_threshold: BatteryLevel;
  command_timeout_seconds: 10;
  updated_at: string;
}

export interface SiteSettingsPatch {
  name?: string;
  address?: string | null;
  notes?: string | null;
  low_battery_threshold?: Exclude<BatteryLevel, null>;
}

export interface NetworkSettings {
  api_bind_address: string;
  api_port: number;
  mqtt_host: string;
  mqtt_port: number;
  mqtt_tls_enabled: boolean;
  restart_required: boolean;
  updated_at: string;
}

export interface NetworkSettingsPatch {
  api_bind_address?: string;
  api_port?: number;
  mqtt_host?: string;
  mqtt_port?: number;
  mqtt_tls_enabled?: boolean;
}

export type BackupStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'DELETED';

export interface BackupRecord {
  id: Uuid;
  filename: string;
  reason: 'SCHEDULED' | 'MANUAL' | 'PRE_RESTORE';
  status: BackupStatus;
  size_bytes: number | null;
  sha256: string | null;
  schema_version: string | null;
  created_at: string;
  completed_at: string | null;
  error_code: string | null;
}

export interface RestoreOperation {
  id: Uuid;
  backup_id: Uuid;
  status: 'ACCEPTED' | 'VALIDATING' | 'BACKING_UP' | 'RESTORING' | 'RESTARTING' | 'FAILED';
  pre_restore_backup_id: Uuid | null;
  requested_at: string;
}

export type PlatformEventType =
  | 'broker.status_changed'
  | 'station.status_changed'
  | 'station.heartbeat'
  | 'tag.status_changed'
  | 'binding.created'
  | 'binding.removed'
  | 'command.status_changed'
  | 'device.status_changed'
  | 'system.notice';

export type PlatformEntityType = 'broker' | 'station' | 'tag' | 'binding' | 'command' | 'device' | 'system';

export interface PlatformEvent<T extends PlatformEventType = PlatformEventType> {
  event_id: Uuid;
  schema_version: 1;
  event_type: T;
  occurred_at: string;
  entity_type: PlatformEntityType;
  entity_id: string;
  payload: Record<string, unknown>;
}
