import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, App, Button, Form, Input, Modal, Select, Space, Table, Tabs, Tooltip, type TableColumnsType } from 'antd';
import { ArchiveRestore, Ban, Check, DatabaseBackup, KeyRound, Pencil, Save, ShieldCheck, Smartphone } from 'lucide-react';
import { useNavigate, useParams } from 'react-router-dom';
import { api } from '../api/endpoints';
import type { AdminUser, AndroidDevice, BackupRecord, SiteSettingsPatch } from '../api/types';
import { EmptyBlock, InlineKeyValue, PageHeader, Panel, QueryError, StatusBadge } from '../components/common';
import { formatBytes, formatDateTime } from '../lib/format';
import { useAuth } from '../auth/AuthProvider';

const sections = ['site', 'devices', 'backups', 'security'] as const;
type Section = typeof sections[number];

export default function SettingsPage() {
  const params = useParams();
  const navigate = useNavigate();
  const section: Section = sections.includes(params.section as Section) ? params.section as Section : 'site';
  const title = { site: '站点与网络', devices: 'Android 设备', backups: '备份与恢复', security: '管理员安全' }[section];
  return (
    <div>
      <PageHeader title={title} description="HighTac 现场平台系统设置" />
      <Tabs className="settings-tabs" activeKey={section} onChange={(key) => navigate(`/settings/${key}`)} items={[
        { key: 'site', label: '站点与网络' }, { key: 'devices', label: 'Android 设备' }, { key: 'backups', label: '备份与恢复' }, { key: 'security', label: '管理员安全' },
      ]} />
      {section === 'site' && <SiteNetworkSettings />}
      {section === 'devices' && <DeviceSettings />}
      {section === 'backups' && <BackupSettings />}
      {section === 'security' && <SecuritySettings />}
    </div>
  );
}

function SiteNetworkSettings() {
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [siteForm] = Form.useForm<SiteSettingsPatch>();
  const site = useQuery({ queryKey: ['settings', 'site'], queryFn: api.settings.site });
  const network = useQuery({ queryKey: ['settings', 'network'], queryFn: api.settings.network });
  useEffect(() => {
    if (site.data) siteForm.setFieldsValue({
      name: site.data.name,
      address: site.data.address,
      notes: site.data.notes,
      low_battery_threshold: site.data.low_battery_threshold ?? undefined,
    });
  }, [site.data, siteForm]);
  const saveSite = useMutation({ mutationFn: api.settings.updateSite, onSuccess: () => { message.success('站点设置已保存'); queryClient.invalidateQueries({ queryKey: ['settings', 'site'] }); }, onError: (error) => message.error(error instanceof Error ? error.message : '保存失败') });

  return (
    <div className="content-grid-equal settings-grid">
      <Panel title="站点设置（在线生效）">
        {site.isLoading ? <div className="drawer-loading">正在加载…</div> : site.isError ? <QueryError error={site.error} onRetry={() => site.refetch()} /> : (
          <Form form={siteForm} layout="vertical" requiredMark={false} onFinish={(values) => saveSite.mutate(values)}>
            <Form.Item name="name" label="站点名称" rules={[{ required: true, message: '请输入站点名称' }, { max: 100 }]}><Input /></Form.Item>
            <Form.Item name="address" label="现场地址" rules={[{ max: 300 }]}><Input /></Form.Item>
            <Form.Item name="low_battery_threshold" label="低电量阈值（%）"><Select options={[0, 10, 30, 60, 80, 90, 100].map((value) => ({ value, label: `${value}%` }))} /></Form.Item>
            <Form.Item name="notes" label="备注" rules={[{ max: 1000 }]}><Input.TextArea autoSize={{ minRows: 3, maxRows: 6 }} /></Form.Item>
            <div className="form-footer"><Button type="primary" htmlType="submit" icon={<Save size={16} />} loading={saveSite.isPending}>保存站点设置</Button></div>
          </Form>
        )}
      </Panel>
      <Panel title="运行网络（安装/重启管理）">
        {network.isLoading ? <div className="drawer-loading">正在加载…</div> : network.isError ? <QueryError error={network.error} onRetry={() => network.refetch()} /> : network.data ? (
          <>
            <Alert
              className="connection-alert"
              type="info"
              showIcon
              message="当前版本仅支持局域网明文连接"
              description="API 使用 HTTP / WebSocket，MQTT 不启用 TLS。以下为服务当前实际运行值，由安装程序或服务启动配置管理；调整配置并重启平台后才会变化，无法在本页修改。"
            />
            <Form layout="vertical" requiredMark={false}>
              <div className="form-grid-2">
                <Form.Item label="API 监听地址（当前值）" htmlFor="runtime-api-bind-address"><Input id="runtime-api-bind-address" className="mono" value={network.data.api_bind_address} readOnly /></Form.Item>
                <Form.Item label="API 端口（当前值）" htmlFor="runtime-api-port"><Input id="runtime-api-port" className="mono" value={network.data.api_port} readOnly /></Form.Item>
              </div>
              <div className="form-grid-2">
                <Form.Item label="MQTT 主机（当前值）" htmlFor="runtime-mqtt-host"><Input id="runtime-mqtt-host" className="mono" value={network.data.mqtt_host} readOnly /></Form.Item>
                <Form.Item label="MQTT 端口（当前值）" htmlFor="runtime-mqtt-port"><Input id="runtime-mqtt-port" className="mono" value={network.data.mqtt_port} readOnly /></Form.Item>
              </div>
              <div className="form-grid-2">
                <Form.Item label="API 传输（当前值）" htmlFor="runtime-api-transport"><Input id="runtime-api-transport" value="HTTP / WebSocket（明文）" readOnly /></Form.Item>
                <Form.Item label="MQTT TLS（当前值）" htmlFor="runtime-mqtt-tls"><Input id="runtime-mqtt-tls" value={network.data.mqtt_tls_enabled ? '已启用' : '未启用（明文 MQTT）'} readOnly /></Form.Item>
              </div>
            </Form>
          </>
        ) : null}
      </Panel>
    </div>
  );
}

function DeviceSettings() {
  const { message, modal } = App.useApp();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [status, setStatus] = useState<string>();
  const [approveTarget, setApproveTarget] = useState<AndroidDevice>();
  const [renameTarget, setRenameTarget] = useState<AndroidDevice>();
  const [approveForm] = Form.useForm<{ display_name: string }>();
  const [renameForm] = Form.useForm<{ display_name: string }>();
  const list = useQuery({ queryKey: ['devices', { page, pageSize, status }], queryFn: () => api.devices.list({ page, page_size: pageSize, status, sort: '-last_seen_at' }), refetchInterval: 10_000 });
  const approve = useMutation({ mutationFn: ({ id, name }: { id: string; name: string }) => api.devices.approve(id, name), onSuccess: () => { message.success('设备已批准'); setApproveTarget(undefined); approveForm.resetFields(); queryClient.invalidateQueries({ queryKey: ['devices'] }); }, onError: (error) => message.error(error instanceof Error ? error.message : '批准失败') });
  const rename = useMutation({ mutationFn: ({ id, name }: { id: string; name: string }) => api.devices.rename(id, name), onSuccess: () => { message.success('设备名称已更新'); setRenameTarget(undefined); renameForm.resetFields(); queryClient.invalidateQueries({ queryKey: ['devices'] }); }, onError: (error) => message.error(error instanceof Error ? error.message : '重命名失败') });
  const revoke = useMutation({ mutationFn: api.devices.revoke, onSuccess: () => { message.success('设备访问权限已撤销'); queryClient.invalidateQueries({ queryKey: ['devices'] }); }, onError: (error) => message.error(error instanceof Error ? error.message : '撤销失败') });
  const confirmRevoke = (device: AndroidDevice) => modal.confirm({ title: '确认撤销 Android 设备？', icon: <Ban size={20} color="#e52020" />, content: `${device.display_name || device.model || device.id} 将立即失去写入与灯光控制权限。`, okText: '确认撤销', okButtonProps: { danger: true }, cancelText: '取消', onOk: () => revoke.mutateAsync(device.id) });

  const columns: TableColumnsType<AndroidDevice> = [
    { title: '现场名称', dataIndex: 'display_name', width: 180, render: (value, row) => <span><strong>{value || '未命名设备'}</strong><small className="table-secondary mono">{row.id}</small></span> },
    { title: '设备', key: 'device', width: 200, render: (_, row) => <span>{[row.manufacturer, row.model].filter(Boolean).join(' ') || '未知型号'}<small className="table-secondary">App {row.app_version || '—'}</small></span> },
    { title: '审批状态', dataIndex: 'status', width: 112, render: (value) => <StatusBadge status={value} /> },
    { title: '连接', dataIndex: 'online', width: 98, render: (value) => <StatusBadge status={value ? 'ONLINE' : 'OFFLINE'} /> },
    { title: '首次连接', dataIndex: 'first_seen_at', width: 176, render: (value) => formatDateTime(value) },
    { title: '最后在线', dataIndex: 'last_seen_at', width: 176, render: (value) => formatDateTime(value) },
    { title: '批准时间', dataIndex: 'approved_at', width: 176, render: (value) => formatDateTime(value) },
    {
      title: '操作', key: 'actions', width: 128, fixed: 'right', render: (_, row) => <Space size={2}>
        {row.status === 'PENDING' && <Tooltip title="批准设备"><Button type="text" aria-label={`批准设备 ${row.id}`} icon={<Check size={17} color="#3f8500" />} onClick={() => { setApproveTarget(row); approveForm.setFieldValue('display_name', row.display_name || `${row.manufacturer || ''} ${row.model || ''}`.trim()); }} /></Tooltip>}
        {row.status === 'APPROVED' && <Tooltip title="修改现场名称"><Button type="text" aria-label={`重命名设备 ${row.id}`} icon={<Pencil size={16} />} onClick={() => { setRenameTarget(row); renameForm.setFieldValue('display_name', row.display_name); }} /></Tooltip>}
        {row.status === 'APPROVED' && <Tooltip title="撤销访问"><Button type="text" danger aria-label={`撤销设备 ${row.id}`} icon={<Ban size={16} />} onClick={() => confirmRevoke(row)} /></Tooltip>}
      </Space>,
    },
  ];

  return <>
    <Panel className="filter-panel"><div className="toolbar"><div className="toolbar-main"><Select allowClear className="filter-field" placeholder="全部审批状态" value={status} onChange={(value) => { setStatus(value); setPage(1); }} options={['PENDING', 'APPROVED', 'REVOKED'].map((value) => ({ value, label: <StatusBadge status={value} /> }))} /></div><StatusBadge status="PENDING" label={`待审批 ${list.data?.items.filter((item) => item.status === 'PENDING').length ?? 0}`} /></div></Panel>
    <Panel className="panel-table" title={`Android 设备${list.data ? `（${list.data.pagination.total_items}）` : ''}`}>
      {list.isError && <div className="table-error"><QueryError error={list.error} onRetry={() => list.refetch()} /></div>}
      <div className="table-wrap"><Table<AndroidDevice> rowKey="id" columns={columns} dataSource={list.data?.items || []} loading={list.isLoading} size="small" bordered scroll={{ x: 1260 }} locale={{ emptyText: <EmptyBlock description="暂无 Android 设备" /> }} pagination={{ current: page, pageSize, total: list.data?.pagination.total_items || 0, showSizeChanger: true, pageSizeOptions: [20, 50, 100], showTotal: (total) => `共 ${total} 台` }} onChange={(pagination) => { setPage(pagination.current || 1); setPageSize(pagination.pageSize || 20); }} /></div>
    </Panel>
    <Modal title="批准 Android 设备" open={Boolean(approveTarget)} onCancel={() => setApproveTarget(undefined)} onOk={() => approveForm.submit()} okText="批准设备" cancelText="取消" confirmLoading={approve.isPending}>
      <div className="device-identity"><Smartphone size={22} /><div><strong>{[approveTarget?.manufacturer, approveTarget?.model].filter(Boolean).join(' ') || '未知设备'}</strong><span className="mono">{approveTarget?.id}</span></div></div>
      <Form form={approveForm} layout="vertical" requiredMark={false} onFinish={(values) => approveTarget && approve.mutate({ id: approveTarget.id, name: values.display_name })}><Form.Item name="display_name" label="现场名称" rules={[{ required: true, message: '请输入便于识别的现场名称' }, { max: 80 }]}><Input autoFocus placeholder="例如：拣货手机 01" /></Form.Item></Form>
    </Modal>
    <Modal title="修改设备名称" open={Boolean(renameTarget)} onCancel={() => setRenameTarget(undefined)} onOk={() => renameForm.submit()} okText="保存" cancelText="取消" confirmLoading={rename.isPending}><Form form={renameForm} layout="vertical" requiredMark={false} onFinish={(values) => renameTarget && rename.mutate({ id: renameTarget.id, name: values.display_name })}><Form.Item name="display_name" label="现场名称" rules={[{ required: true }, { max: 80 }]}><Input autoFocus /></Form.Item></Form></Modal>
  </>;
}

function BackupSettings() {
  const { message, modal } = App.useApp();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [restoreTarget, setRestoreTarget] = useState<BackupRecord>();
  const [restoreForm] = Form.useForm<{ confirmation: string }>();
  const list = useQuery({ queryKey: ['backups', { page, pageSize }], queryFn: () => api.backups.list({ page, page_size: pageSize, sort: '-created_at' }), refetchInterval: 10_000 });
  const create = useMutation({ mutationFn: api.backups.create, onSuccess: () => { message.success('备份任务已创建'); queryClient.invalidateQueries({ queryKey: ['backups'] }); }, onError: (error) => message.error(error instanceof Error ? error.message : '备份创建失败') });
  const restore = useMutation({ mutationFn: ({ id, expectedSha256 }: { id: string; expectedSha256: string }) => api.backups.restore(id, expectedSha256), onSuccess: () => { message.success('恢复任务已提交，平台将进行健康检查'); setRestoreTarget(undefined); restoreForm.resetFields(); queryClient.invalidateQueries({ queryKey: ['backups'] }); }, onError: (error) => message.error(error instanceof Error ? error.message : '恢复失败') });
  const confirmCreate = () => modal.confirm({ title: '立即创建一致性备份？', content: '备份将在后台执行，不会停止查询。', okText: '创建备份', cancelText: '取消', onOk: () => create.mutateAsync() });
  const columns: TableColumnsType<BackupRecord> = [
    { title: '文件', dataIndex: 'filename', width: 300, render: (value) => <strong className="mono backup-filename">{value}</strong> },
    { title: '原因', dataIndex: 'reason', width: 125, render: (value) => <StatusBadge status={value} /> },
    { title: '状态', dataIndex: 'status', width: 118, render: (value) => <StatusBadge status={value} /> },
    { title: '大小', dataIndex: 'size_bytes', width: 100, align: 'right', render: formatBytes },
    { title: 'Schema', dataIndex: 'schema_version', width: 100, render: (value) => value || '—' },
    { title: '创建时间', dataIndex: 'created_at', width: 178, render: (value) => formatDateTime(value) },
    { title: '校验值', dataIndex: 'sha256', width: 150, render: (value) => <Tooltip title={value}><span className="mono">{value ? `${value.slice(0, 12)}…` : '—'}</span></Tooltip> },
    { title: '操作', key: 'action', width: 72, fixed: 'right', render: (_, row) => <Tooltip title={row.status === 'SUCCEEDED' && row.sha256 ? '恢复此备份' : '仅可恢复校验完成的备份'}><Button type="text" danger aria-label={`恢复备份 ${row.filename}`} icon={<ArchiveRestore size={16} />} disabled={row.status !== 'SUCCEEDED' || !row.sha256} onClick={() => { setRestoreTarget(row); restoreForm.resetFields(); }} /></Tooltip> },
  ];
  return <>
    <Alert className="connection-alert" type="info" showIcon message="自动备份每天 02:00 执行，默认保留最近 30 天。恢复前系统会先创建恢复前备份，并暂停写入与命令调度。" />
    <Panel className="panel-table" title="备份记录" extra={<Button type="primary" icon={<DatabaseBackup size={16} />} loading={create.isPending} onClick={confirmCreate}>立即备份</Button>}>
      {list.isError && <div className="table-error"><QueryError error={list.error} onRetry={() => list.refetch()} /></div>}
      <div className="table-wrap"><Table<BackupRecord> rowKey="id" columns={columns} dataSource={list.data?.items || []} loading={list.isLoading} size="small" bordered scroll={{ x: 1200 }} locale={{ emptyText: <EmptyBlock description="暂无备份记录" /> }} pagination={{ current: page, pageSize, total: list.data?.pagination.total_items || 0, showSizeChanger: true, pageSizeOptions: [20, 50, 100], showTotal: (total) => `共 ${total} 个备份` }} onChange={(pagination) => { setPage(pagination.current || 1); setPageSize(pagination.pageSize || 20); }} /></div>
    </Panel>
    <Modal title="恢复数据库备份" open={Boolean(restoreTarget)} onCancel={() => setRestoreTarget(undefined)} onOk={() => restoreForm.submit()} okText="确认恢复" okButtonProps={{ danger: true }} cancelText="取消" confirmLoading={restore.isPending}>
      <Alert type="error" showIcon message="恢复期间平台将短暂不可用" description="写入与 MQTT 命令调度会暂停；恢复完成后服务将重启并执行健康检查。" />
      <div className="restore-target"><InlineKeyValue label="备份文件" value={<span className="mono">{restoreTarget?.filename}</span>} /><InlineKeyValue label="创建时间" value={formatDateTime(restoreTarget?.created_at)} /><InlineKeyValue label="Schema" value={restoreTarget?.schema_version} /><InlineKeyValue label="SHA-256" value={<span className="mono">{restoreTarget?.sha256}</span>} /></div>
      <Form form={restoreForm} layout="vertical" requiredMark={false} onFinish={(values) => restoreTarget?.sha256 && restore.mutate({ id: restoreTarget.id, expectedSha256: restoreTarget.sha256 })}><Form.Item name="confirmation" label={<>输入 <strong>RESTORE BACKUP</strong> 以继续</>} rules={[{ required: true }, { validator: (_, value) => value === 'RESTORE BACKUP' ? Promise.resolve() : Promise.reject(new Error('请输入“RESTORE BACKUP”')) }]}><Input autoFocus autoComplete="off" /></Form.Item></Form>
    </Modal>
  </>;
}

function SecuritySettings() {
  const { user } = useAuth();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [form] = Form.useForm<{ current_password: string; new_password: string; confirm_password: string }>();
  const change = useMutation({
    mutationFn: ({ current_password, new_password }: { current_password: string; new_password: string }) => api.auth.changePassword({ current_password, new_password }),
    onMutate: () => ({ wasForced: Boolean(user?.must_change_password) }),
    onSuccess: (_data, _variables, context) => {
      queryClient.setQueryData<AdminUser | null | undefined>(['auth', 'me'], (current) => current
        ? { ...current, must_change_password: false }
        : user
          ? { ...user, must_change_password: false }
          : current);
      message.success('管理员密码已修改');
      form.resetFields();
      if (context?.wasForced) navigate('/', { replace: true });
    },
    onError: (error) => message.error(error instanceof Error ? error.message : '密码修改失败'),
  });
  return <div className="content-grid-2 security-layout">
    <Panel title="修改管理员密码">
      <Form form={form} layout="vertical" requiredMark={false} onFinish={(values) => change.mutate(values)}>
        <Form.Item name="current_password" label="当前密码" rules={[{ required: true, message: '请输入当前密码' }]}><Input.Password autoComplete="current-password" prefix={<KeyRound size={15} />} /></Form.Item>
        <Form.Item name="new_password" label="新密码" rules={[{ required: true, message: '请输入新密码' }, { min: 12, message: '密码至少 12 个字符' }, { validator: (_, value) => value && !/(?=.*[A-Za-z])(?=.*\d)/.test(value) ? Promise.reject(new Error('密码需同时包含字母和数字')) : Promise.resolve() }]}><Input.Password autoComplete="new-password" prefix={<ShieldCheck size={15} />} /></Form.Item>
        <Form.Item name="confirm_password" label="确认新密码" dependencies={['new_password']} rules={[{ required: true, message: '请再次输入新密码' }, ({ getFieldValue }) => ({ validator: (_, value) => !value || getFieldValue('new_password') === value ? Promise.resolve() : Promise.reject(new Error('两次输入的密码不一致')) })]}><Input.Password autoComplete="new-password" prefix={<ShieldCheck size={15} />} /></Form.Item>
        <Button type="primary" htmlType="submit" icon={<Save size={16} />} loading={change.isPending}>修改密码</Button>
      </Form>
    </Panel>
    <Panel title="管理员状态">
      <div className="security-status"><ShieldCheck size={30} color={user?.must_change_password ? '#df6500' : '#3f8500'} /><StatusBadge status={user?.must_change_password ? 'PENDING' : 'APPROVED'} label={user?.must_change_password ? '必须修改初始密码' : '密码状态正常'} /></div>
      <InlineKeyValue label="管理员" value={user?.username} /><InlineKeyValue label="最后登录" value={formatDateTime(user?.last_login_at)} />
      <div className="password-rules"><strong>密码要求</strong><span>至少 12 个字符</span><span>同时包含字母和数字</span><span>不要复用现场设备或 MQTT 密码</span></div>
    </Panel>
  </div>;
}
