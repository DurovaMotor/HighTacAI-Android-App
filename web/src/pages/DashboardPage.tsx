import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { AlertTriangle, CheckCircle2, RadioTower, Server, Zap } from 'lucide-react';
import { Button, List, Segmented, Space } from 'antd';
import { CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip as ChartTooltip, XAxis, YAxis } from 'recharts';
import { api } from '../api/endpoints';
import type { DashboardIncident, OperationLog } from '../api/types';
import { EmptyBlock, LoadingBlock, MetricTile, PageHeader, Panel, QueryError, StatusBadge } from '../components/common';
import { formatDateTime } from '../lib/format';

function percent(value?: number) {
  if (value === undefined || value === null) return '—';
  return `${value <= 1 ? (value * 100).toFixed(1) : value.toFixed(1)}%`;
}

const incidentSeverityLabels: Record<DashboardIncident['severity'], string> = {
  INFO: '提示',
  WARNING: '警告',
  ERROR: '错误',
};

function RecentOperations({ items, empty }: { items?: OperationLog[]; empty: string }) {
  if (!items?.length) return <EmptyBlock description={empty} />;
  return (
    <List
      className="event-list"
      dataSource={items.slice(0, 6)}
      renderItem={(item) => (
        <List.Item>
          <div className="event-item">
            <StatusBadge status={item.failure_code ? 'FAILED' : 'SUCCESS'} />
            <div><strong>{item.event_type}</strong><span>{item.actor_display_name || item.actor_type} · {formatDateTime(item.occurred_at)}</span></div>
          </div>
        </List.Item>
      )}
    />
  );
}

function RecentIncidents({ items }: { items?: DashboardIncident[] }) {
  if (!items?.length) return <EmptyBlock description="当前没有异常" />;
  return (
    <List
      className="event-list"
      dataSource={items.slice(0, 6)}
      renderItem={(item) => (
        <List.Item>
          <div className="event-item">
            <StatusBadge status={item.severity === 'ERROR' ? 'FAILED' : item.severity === 'WARNING' ? 'STALE' : 'ONLINE'} label={incidentSeverityLabels[item.severity]} />
            <div><strong>{item.message}</strong><span>{item.code} · {formatDateTime(item.occurred_at)}</span></div>
          </div>
        </List.Item>
      )}
    />
  );
}

export default function DashboardPage() {
  const [period, setPeriod] = useState<'24h' | '7d'>('24h');
  const summary = useQuery({ queryKey: ['dashboard', 'summary'], queryFn: api.dashboard.summary, refetchInterval: 15_000 });
  const trends = useQuery({ queryKey: ['dashboard', 'trends', period], queryFn: () => api.dashboard.trends(period) });
  const data = summary.data;
  const points = trends.data?.points || [];

  return (
    <div>
      <PageHeader title="仪表盘" description="现场服务、基站、灯条与命令执行状态的实时总览" actions={<Button onClick={() => summary.refetch()}>刷新快照</Button>} />
      {summary.isLoading ? <LoadingBlock rows={5} /> : summary.isError ? <QueryError error={summary.error} onRetry={() => summary.refetch()} /> : (
        <>
          <div className="metric-grid">
            <MetricTile label="API 服务" value={<StatusBadge status={data?.api_status || 'NOT_READY'} />} tone={data?.api_status === 'READY' ? 'success' : data?.api_status === 'DEGRADED' ? 'warning' : 'error'} meta={`快照 ${formatDateTime(data?.generated_at)}`} />
            <MetricTile label="MQTT Broker" value={<StatusBadge status={data?.broker.service_state || 'UNKNOWN'} />} tone={data?.broker.mqtt_connected ? 'success' : 'error'} meta={data?.broker.endpoint || '端点未返回'} />
            <MetricTile label="基站" value={`${data?.station_counts.online ?? 0} / ${data?.station_counts.total ?? 0}`} tone={(data?.station_counts.offline || 0) > 0 ? 'error' : 'success'} meta={`在线 / 总数 · 离线 ${data?.station_counts.offline ?? 0}`} />
            <MetricTile label="灯条" value={data?.tag_counts.total ?? 0} tone={(data?.tag_counts.abnormal || 0) > 0 ? 'warning' : 'default'} meta={`24h 新发现 ${data?.tag_counts.discovered_24h ?? 0} · 异常 ${data?.tag_counts.abnormal ?? 0}`} />
            <MetricTile label="低电量灯条" value={data?.tag_counts.low_battery ?? 0} tone={(data?.tag_counts.low_battery || 0) > 0 ? 'warning' : 'success'} meta={`未绑定 ${data?.tag_counts.unbound ?? 0}`} />
            <MetricTile label="产品与绑定" value={data?.product_counts.total ?? 0} tone="info" meta={`有效绑定 ${data?.product_counts.active_bindings ?? 0}`} />
            <MetricTile label="24h 命令确认率" value={percent(data?.command_counts_24h.confirmation_rate)} tone={(data?.command_counts_24h.unconfirmed || 0) > 0 ? 'warning' : 'success'} meta={`命令 ${data?.command_counts_24h.total ?? 0} · 未确认 ${data?.command_counts_24h.unconfirmed ?? 0}`} />
          </div>

          <div className="system-strip" role="status">
            <div><Server size={17} /><span>Broker TCP</span><StatusBadge status={data?.broker?.tcp_reachable ? 'ONLINE' : 'OFFLINE'} /></div>
            <div><Zap size={17} /><span>后台 MQTT</span><StatusBadge status={data?.broker?.mqtt_connected ? 'ONLINE' : 'OFFLINE'} /></div>
            <div><RadioTower size={17} /><span>基站心跳</span><strong>{data?.station_counts.online ?? 0} 在线 · {data?.station_counts.stale ?? 0} 超时</strong></div>
            <div><CheckCircle2 size={17} /><span>事件订阅</span><StatusBadge status={data?.broker.subscriptions_ready ? 'READY' : 'NOT_READY'} /></div>
          </div>

          <div className="content-grid-2">
            <Panel title="命令趋势" extra={<Segmented size="small" value={period} options={[{ label: '24 小时', value: '24h' }, { label: '7 天', value: '7d' }]} onChange={(value) => setPeriod(value as '24h' | '7d')} />}>
              {trends.isLoading ? <LoadingBlock rows={4} /> : trends.isError ? <QueryError compact error={trends.error} onRetry={() => trends.refetch()} /> : points.length === 0 ? <EmptyBlock description="暂无命令趋势数据" /> : (
                <div className="chart-container" aria-label="命令执行趋势图">
                  <ResponsiveContainer width="100%" height="100%">
                    <LineChart data={points} margin={{ top: 8, right: 14, left: -16, bottom: 0 }}>
                      <CartesianGrid stroke="#e1e1e1" vertical={false} />
                      <XAxis dataKey="bucket_start" tickFormatter={(value) => formatDateTime(value).slice(5, 16)} tick={{ fontSize: 11 }} tickLine={false} axisLine={{ stroke: '#cccccc' }} />
                      <YAxis allowDecimals={false} tick={{ fontSize: 11 }} tickLine={false} axisLine={false} />
                      <ChartTooltip labelFormatter={(value) => formatDateTime(String(value))} contentStyle={{ border: '1px solid #cccccc', borderRadius: 2, boxShadow: 'none' }} />
                      <Legend iconType="square" wrapperStyle={{ fontSize: 12 }} />
                      <Line name="命令" type="monotone" dataKey="commands" stroke="#76b900" strokeWidth={2} dot={false} />
                      <Line name="已确认" type="monotone" dataKey="confirmed" stroke="#007c91" strokeWidth={2} dot={false} />
                      <Line name="新灯条" type="monotone" dataKey="tags_discovered" stroke="#0046a4" strokeWidth={2} dot={false} />
                      <Line name="异常" type="monotone" dataKey="incidents" stroke="#df6500" strokeWidth={2} dot={false} />
                    </LineChart>
                  </ResponsiveContainer>
                </div>
              )}
            </Panel>
            <Panel title={<Space size={8}><AlertTriangle size={17} color="#df6500" />最近异常</Space>}>
              <RecentIncidents items={data?.recent_incidents} />
            </Panel>
          </div>
          <Panel title="最近操作">
            <RecentOperations items={data?.recent_operations} empty="暂无操作记录" />
          </Panel>
        </>
      )}
    </div>
  );
}
