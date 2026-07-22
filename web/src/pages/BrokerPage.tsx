import { useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { App, Button, Select, Space, Switch, Tooltip } from 'antd';
import { CheckCircle2, Clipboard, Play, RadioTower, RefreshCw, Square, Unplug, Wifi, XCircle } from 'lucide-react';
import { api } from '../api/endpoints';
import type { BrokerState } from '../api/types';
import { InlineKeyValue, LoadingBlock, PageHeader, Panel, QueryError, StatusBadge } from '../components/common';
import { copyText } from '../lib/clipboard';
import { formatDateTime, formatDuration } from '../lib/format';

type BrokerAction = 'start' | 'stop' | 'restart';

export default function BrokerPage() {
  const { message, modal } = App.useApp();
  const queryClient = useQueryClient();
  const [lineCount, setLineCount] = useState<50 | 200 | 500>(200);
  const [autoScrollPaused, setAutoScrollPaused] = useState(false);
  const logView = useRef<HTMLPreElement>(null);
  const status = useQuery({ queryKey: ['broker', 'status'], queryFn: api.broker.status, refetchInterval: 5000 });
  const config = useQuery({ queryKey: ['broker', 'config'], queryFn: api.broker.config });
  const stationHealth = useQuery({ queryKey: ['dashboard', 'summary'], queryFn: api.dashboard.summary, refetchInterval: 15_000 });
  const logs = useQuery({ queryKey: ['broker', 'logs', lineCount], queryFn: () => api.broker.logs(lineCount), refetchInterval: 3000 });

  const action = useMutation({
    mutationFn: (kind: BrokerAction) => api.broker[kind](),
    onSuccess: (_, kind) => {
      message.success(kind === 'start' ? '启动请求已提交' : kind === 'stop' ? '停止请求已提交' : '重启请求已提交');
      queryClient.invalidateQueries({ queryKey: ['broker'] });
    },
    onError: (error) => message.error(error instanceof Error ? error.message : '服务控制失败'),
  });

  const logLines = useMemo(() => logs.data?.lines || [], [logs.data]);
  useEffect(() => {
    if (!autoScrollPaused && logView.current) logView.current.scrollTop = logView.current.scrollHeight;
  }, [autoScrollPaused, logLines]);

  const state = status.data?.service_state || 'UNKNOWN';
  const transitioning = action.isPending || ['STARTING', 'STOPPING'].includes(state);
  const canStart = ['STOPPED', 'FAILED', 'UNKNOWN'].includes(state) && !transitioning;
  const canStop = state === 'RUNNING' && !transitioning;

  const confirmAction = (kind: BrokerAction) => {
    if (kind === 'start') return action.mutate(kind);
    modal.confirm({
      title: kind === 'stop' ? '确认停止 MQTT Broker？' : '确认重启 MQTT Broker？',
      icon: kind === 'stop' ? <Unplug size={20} color="#e52020" /> : <RefreshCw size={20} color="#df6500" />,
      content: kind === 'stop' ? '停止后，基站和所有 Android 设备将无法亮灯或灭灯。网页仍可查看历史数据。' : '重启期间现场命令会短暂不可用，正在执行的命令可能进入未确认状态。',
      okText: kind === 'stop' ? '确认停止' : '确认重启',
      okButtonProps: { danger: kind === 'stop' },
      cancelText: '取消',
      onOk: () => action.mutateAsync(kind),
    });
  };

  const copyLogs = async () => {
    try {
      await copyText(logLines.join('\n'));
      message.success('日志已复制');
    } catch (error) {
      message.error(error instanceof Error ? error.message : '复制日志失败，请手动选择后复制');
    }
  };

  return (
    <div>
      <PageHeader
        title="MQTT 服务"
        description="管理 Windows Broker 服务，并检查 TCP、后台客户端与基站心跳三层链路"
        actions={[
          <Button key="start" type="primary" icon={<Play size={16} />} disabled={!canStart} loading={action.isPending && action.variables === 'start'} onClick={() => confirmAction('start')}>启动</Button>,
          <Button key="stop" danger icon={<Square size={15} />} disabled={!canStop} loading={action.isPending && action.variables === 'stop'} onClick={() => confirmAction('stop')}>停止</Button>,
          <Button key="restart" icon={<RefreshCw size={16} />} disabled={!canStop} loading={action.isPending && action.variables === 'restart'} onClick={() => confirmAction('restart')}>重启</Button>,
        ]}
      />

      {status.isLoading ? <LoadingBlock rows={3} /> : status.isError ? <QueryError error={status.error} onRetry={() => status.refetch()} /> : (
        <>
          <div className="content-grid-equal">
            <Panel title="服务状态" extra={<StatusBadge status={state} />}>
              <div className="detail-grid">
                <InlineKeyValue label="Windows 服务" value={status.data?.service_name} />
                <InlineKeyValue label="Broker 端点" value={<span className="mono">{status.data?.endpoint}</span>} />
                <InlineKeyValue label="启动时间" value={formatDateTime(status.data?.started_at)} />
                <InlineKeyValue label="运行时长" value={formatDuration(status.data?.uptime_seconds ?? undefined)} />
                <InlineKeyValue label="检查时间" value={formatDateTime(status.data?.checked_at)} />
                <InlineKeyValue label="订阅状态" value={<StatusBadge status={status.data?.subscriptions_ready ? 'READY' : 'NOT_READY'} />} />
              </div>
            </Panel>
            <Panel title="脱敏配置">
              {config.isLoading ? <LoadingBlock rows={3} /> : config.isError ? <QueryError compact error={config.error} onRetry={() => config.refetch()} /> : (
                <div className="detail-grid">
                  <InlineKeyValue label="主机" value={config.data?.listener_host} />
                  <InlineKeyValue label="端口" value={config.data?.listener_port} />
                  <InlineKeyValue label="后台用户名" value={config.data?.backend_username || '—'} />
                  <InlineKeyValue label="TLS" value={config.data?.tls_enabled ? '启用' : '未启用'} />
                  <InlineKeyValue label="持久化" value={config.data?.persistence_enabled ? '启用' : '未启用'} />
                  <InlineKeyValue label="基站账号数" value={config.data?.station_account_count ?? 0} />
                </div>
              )}
            </Panel>
          </div>

          <Panel title="链路检查">
            <div className="health-grid">
              <HealthItem icon={<Wifi size={22} />} label="TCP 探测" ok={Boolean(status.data?.tcp_reachable)} detail={status.data?.tcp_reachable ? 'Broker 端口可连接' : '无法连接 Broker 端口'} />
              <HealthItem icon={<Unplug size={22} />} label="后台 MQTT" ok={Boolean(status.data?.mqtt_connected && status.data?.subscriptions_ready)} detail={status.data?.mqtt_connected ? (status.data.subscriptions_ready ? '客户端已连接并订阅' : '客户端已连接，订阅尚未就绪') : '后台客户端未连接'} />
              <HealthItem
                icon={<RadioTower size={22} />}
                label="基站心跳"
                ok={Boolean(stationHealth.data?.station_counts.total && stationHealth.data.station_counts.online === stationHealth.data.station_counts.total)}
                detail={stationHealth.isError
                  ? '无法读取基站心跳汇总'
                  : stationHealth.data
                    ? stationHealth.data.station_counts.total
                      ? `${stationHealth.data.station_counts.online} 在线 · ${stationHealth.data.station_counts.stale} 超时 · ${stationHealth.data.station_counts.offline} 离线`
                      : '尚未登记基站'
                    : '正在读取基站心跳'}
              />
            </div>
          </Panel>
        </>
      )}

      <Panel
        title="Broker 最近日志"
        extra={
          <Space wrap>
            <span className="switch-label"><Switch aria-label="暂停 Broker 日志自动滚动" size="small" checked={autoScrollPaused} onChange={setAutoScrollPaused} /> 暂停滚动</span>
            <Select aria-label="Broker 日志显示行数" value={lineCount} onChange={setLineCount} options={[50, 200, 500].map((value) => ({ value, label: `${value} 行` }))} style={{ width: 96 }} />
            <Tooltip title="复制日志"><Button aria-label="复制日志" icon={<Clipboard size={16} />} onClick={copyLogs} disabled={!logLines.length} /></Tooltip>
            <Tooltip title="手动刷新日志"><Button aria-label="刷新日志" icon={<RefreshCw size={16} />} onClick={() => logs.refetch()} /></Tooltip>
          </Space>
        }
      >
        {logs.isLoading ? <LoadingBlock rows={5} /> : logs.isError ? <QueryError error={logs.error} onRetry={() => logs.refetch()} /> : (
          <pre ref={logView} className="code-block" aria-label="Broker 日志">{logLines.length ? logLines.join('\n') : '暂无日志'}</pre>
        )}
      </Panel>
    </div>
  );
}

function HealthItem({ icon, label, ok, detail }: { icon: React.ReactNode; label: string; ok: boolean; detail: string }) {
  return (
    <div className="health-item">
      <Space size={8}>{icon}{ok ? <CheckCircle2 size={18} color="#3f8500" /> : <XCircle size={18} color="#e52020" />}</Space>
      <strong>{label}</strong><span>{detail}</span>
    </div>
  );
}
