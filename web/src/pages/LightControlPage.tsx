import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, App, Button, Form, Input, Select, Segmented, Space, Table, type TableColumnsType } from 'antd';
import { Lightbulb, Power, PowerOff, RadioTower, RefreshCw, Send, WifiOff } from 'lucide-react';
import { api } from '../api/endpoints';
import type { CommandItem, LightAction, LightColor, LightCommand } from '../api/types';
import { EmptyBlock, InlineKeyValue, PageHeader, Panel, QueryError, StatusBadge } from '../components/common';
import { formatDateTime } from '../lib/format';
import { useRealtime } from '../realtime/RealtimeProvider';

const colors: Array<{ value: LightColor; label: string; hex: string }> = [
  { value: 'RED', label: '红色', hex: '#e52020' },
  { value: 'GREEN', label: '绿色', hex: '#3f8500' },
  { value: 'BLUE', label: '蓝色', hex: '#1769aa' },
  { value: 'CYAN', label: '青色', hex: '#007c91' },
  { value: 'PINK', label: '粉色', hex: '#c22c75' },
];

interface CommandFormValues {
  target: 'product' | 'tag';
  target_id: string;
  action: LightAction;
  color: LightColor;
}

const terminalStatuses = ['CONFIRMED', 'PARTIALLY_CONFIRMED', 'UNCONFIRMED', 'FAILED', 'SUPERSEDED'];
const commandItemColumns: TableColumnsType<CommandItem> = [
  { title: '灯条 ID', dataIndex: 'tag_id', width: 170, render: (value) => <span className="mono">{value}</span> },
  { title: '基站', dataIndex: 'station_id', width: 150, render: (value) => <span className="mono">{value}</span> },
  { title: '状态', dataIndex: 'status', width: 118, render: (value) => <StatusBadge status={value} /> },
  { title: '发布次数', dataIndex: 'publish_attempts', width: 96, align: 'right' },
  { title: '发布时间', dataIndex: 'published_at', width: 176, render: (value) => formatDateTime(value) },
  { title: '确认时间', dataIndex: 'confirmed_at', width: 176, render: (value) => formatDateTime(value) },
  { title: '失败代码', dataIndex: 'failure_code', width: 150, render: (value) => value ? <span className="error-text">{value}</span> : '—' },
];

export default function LightControlPage() {
  const { message, modal } = App.useApp();
  const queryClient = useQueryClient();
  const realtime = useRealtime();
  const [form] = Form.useForm<CommandFormValues>();
  const [commandId, setCommandId] = useState<string>();
  const [initialCommand, setInitialCommand] = useState<LightCommand>();
  const [allOffStation, setAllOffStation] = useState<string>();
  const actionValue = Form.useWatch('action', form) || 'LIGHT_ON';
  const colorValue = Form.useWatch('color', form) || 'RED';
  const targetValue = Form.useWatch('target', form) || 'product';

  const broker = useQuery({ queryKey: ['broker', 'status'], queryFn: api.broker.status, refetchInterval: 5000 });
  const stations = useQuery({ queryKey: ['stations', 'command-selector'], queryFn: () => api.stations.list({ page: 1, page_size: 100, sort: 'station_id' }) });
  const command = useQuery({
    queryKey: ['commands', commandId], queryFn: () => api.commands.get(commandId!), enabled: Boolean(commandId),
    refetchInterval: (query) => terminalStatuses.includes(query.state.data?.status || '') ? false : 2000,
  });
  const dispatch = useMutation({
    mutationFn: (values: CommandFormValues) => api.commands.create({ action: values.action, ...(values.target === 'product' ? { product_code: values.target_id.trim().toUpperCase() } : { tag_ids: [values.target_id.trim().toUpperCase()] }), ...(values.action === 'LIGHT_ON' ? { color: values.color } : {}) }),
    onSuccess: (result) => { setInitialCommand(result); setCommandId(result.id); message.success('命令已接受，正在等待灯条确认'); queryClient.invalidateQueries({ queryKey: ['dashboard'] }); },
    onError: (error) => message.error(error instanceof Error ? error.message : '命令提交失败'),
  });
  const allOff = useMutation({
    mutationFn: api.stations.allOff,
    onSuccess: (result) => { setInitialCommand(result); setCommandId(result.id); message.success('全灭命令已接受'); queryClient.invalidateQueries({ queryKey: ['dashboard'] }); },
    onError: (error) => message.error(error instanceof Error ? error.message : '全灭命令提交失败'),
  });

  const ready = broker.data?.service_state === 'RUNNING' && broker.data.mqtt_connected && broker.data.subscriptions_ready;
  const current = command.data || initialCommand;
  const submit = (values: CommandFormValues) => {
    if (values.action === 'LIGHT_OFF') {
      modal.confirm({ title: `确认发送${values.target === 'product' ? '产品' : '灯条'}灭灯命令？`, content: <span>目标：<strong className="mono">{values.target_id.toUpperCase()}</strong></span>, okText: '确认灭灯', cancelText: '取消', onOk: () => dispatch.mutateAsync(values) });
    } else dispatch.mutate(values);
  };
  const confirmAllOff = () => {
    if (!allOffStation) return;
    modal.confirm({ title: '确认当前基站全灭？', icon: <PowerOff size={20} color="#e52020" />, content: <span>基站 <strong className="mono">{allOffStation}</strong> 下的全部灯条将收到灭灯命令。</span>, okText: '确认全部灭灯', okButtonProps: { danger: true }, cancelText: '取消', onOk: () => allOff.mutateAsync(allOffStation) });
  };

  return (
    <div>
      <PageHeader title="灯光控制" description="按产品或单条灯条下发亮灭灯命令，并跟踪最终确认状态" />
      {!ready && <Alert className="connection-alert" type="warning" showIcon icon={<WifiOff size={18} />} message={`MQTT 尚未就绪：服务 ${broker.data?.service_state || 'UNKNOWN'} · 连接 ${broker.data?.mqtt_connected ? '正常' : '断开'} · 订阅 ${broker.data?.subscriptions_ready ? '就绪' : '未就绪'}`} />}
      {ready && realtime.state !== 'connected' && <Alert className="connection-alert" type="info" showIcon icon={<WifiOff size={18} />} message="实时事件通道正在重连，命令仍可发送，结果将通过轮询更新。" action={<Button size="small" icon={<RefreshCw size={14} />} onClick={realtime.retry}>重新连接</Button>} />}
      <div className="content-grid-2 control-layout">
        <Panel title="发送命令">
          <Form form={form} layout="vertical" requiredMark={false} initialValues={{ target: 'product', action: 'LIGHT_ON', color: 'RED' }} onFinish={submit}>
            <Form.Item name="target" label="控制目标"><Segmented block options={[{ label: '按产品', value: 'product', icon: <Lightbulb size={15} /> }, { label: '按灯条', value: 'tag', icon: <RadioTower size={15} /> }]} /></Form.Item>
            <Form.Item name="target_id" label={targetValue === 'product' ? '产品编码' : '灯条 ID'} normalize={(value) => String(value || '').trim().toUpperCase()} rules={[{ required: true, message: targetValue === 'product' ? '请输入产品编码' : '请输入灯条 ID' }, ...(targetValue === 'tag' ? [{ pattern: /^AD1[0-9A-F]{9}$/, message: '灯条 ID 格式不正确' }] : [])]}><Input className="mono" autoFocus placeholder={targetValue === 'product' ? '1711A-ABA-PT' : 'AD1000000001'} /></Form.Item>
            <Form.Item name="action" label="动作"><Segmented block options={[{ label: '亮灯', value: 'LIGHT_ON', icon: <Power size={15} /> }, { label: '灭灯', value: 'LIGHT_OFF', icon: <PowerOff size={15} /> }]} /></Form.Item>
            {actionValue === 'LIGHT_ON' && <Form.Item name="color" label="灯光颜色">
              <div className="swatch-group" role="radiogroup" aria-label="灯光颜色">
                {colors.map((color) => <button key={color.value} type="button" role="radio" aria-checked={colorValue === color.value} aria-label={color.label} title={color.label} className="color-swatch" data-selected={colorValue === color.value} style={{ backgroundColor: color.hex }} onClick={() => form.setFieldValue('color', color.value)} />)}
              </div>
            </Form.Item>}
            <Button type="primary" block icon={<Send size={16} />} htmlType="submit" disabled={!ready} loading={dispatch.isPending}>发送{actionValue === 'LIGHT_ON' ? '亮灯' : '灭灯'}命令</Button>
          </Form>
        </Panel>

        <Panel title="命令结果" extra={current && <StatusBadge status={current.status} />}>
          {!current ? <div className="empty-command"><Send size={28} /><strong>暂无当前命令</strong><span>发送后将在此显示下发与确认状态</span></div> : command.isError ? <QueryError error={command.error} onRetry={() => command.refetch()} /> : (
            <div className="command-result">
              <InlineKeyValue label="命令 ID" value={<span className="mono">{current.id}</span>} />
              <InlineKeyValue label="动作" value={<StatusBadge status={current.action} />} />
              <InlineKeyValue label="目标" value={<span className="mono">{current.product_code || current.items.map((item) => item.tag_id).join(', ') || '—'}</span>} />
              <InlineKeyValue label="灯条数量" value={current.target_count} />
              <InlineKeyValue label="已确认" value={current.confirmed_count} />
              <InlineKeyValue label="未确认" value={current.unconfirmed_count} />
              <InlineKeyValue label="失败" value={current.failed_count} />
              <InlineKeyValue label="创建时间" value={formatDateTime(current.created_at)} />
              {['FAILED', 'UNCONFIRMED', 'PARTIALLY_CONFIRMED'].includes(current.status) && <Alert type={current.status === 'FAILED' ? 'error' : 'warning'} showIcon message={`命令状态：${current.status}`} />}
              <h3 className="drawer-section-title">逐灯结果</h3>
              {current.items.length ? (
                <div className="table-wrap">
                  <Table<CommandItem>
                    aria-label="逐灯命令结果"
                    rowKey="id"
                    size="small"
                    bordered
                    pagination={false}
                    columns={commandItemColumns}
                    dataSource={current.items}
                    scroll={{ x: 1040 }}
                  />
                </div>
              ) : <EmptyBlock description={current.target_count ? '服务端尚未返回逐灯结果' : '命令没有可执行的灯条目标'} />}
            </div>
          )}
        </Panel>
      </div>

      <Panel title="基站全部灭灯">
        <Space wrap className="all-off-controls">
          <Select showSearch optionFilterProp="label" placeholder="选择基站" value={allOffStation} onChange={setAllOffStation} loading={stations.isLoading} options={(stations.data?.items || []).map((station) => ({ value: station.station_id, label: `${station.alias || station.station_id} · ${station.status}`, disabled: station.status !== 'ONLINE' }))} style={{ minWidth: 280 }} />
          <Button danger icon={<PowerOff size={16} />} disabled={!ready || !allOffStation} loading={allOff.isPending} onClick={confirmAllOff}>全部灭灯</Button>
        </Space>
      </Panel>
    </div>
  );
}
