import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { App, Button, Drawer, Form, Input, Modal, Select, Space, Table, Tooltip, type TableColumnsType } from 'antd';
import { CheckCircle2, Eye, Pencil, Plus, RadioTower, Search, XCircle } from 'lucide-react';
import { api } from '../api/endpoints';
import type { Station } from '../api/types';
import { EmptyBlock, InlineKeyValue, PageHeader, Panel, QueryError, StatusBadge } from '../components/common';
import { formatDateTime, formatRelative } from '../lib/format';

interface StationFormValues {
  station_id: string;
  alias?: string;
}

export default function StationsPage() {
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [search, setSearch] = useState('');
  const [searchDraft, setSearchDraft] = useState('');
  const [statusFilter, setStatusFilter] = useState<string>();
  const [selectedId, setSelectedId] = useState<string>();
  const [createOpen, setCreateOpen] = useState(false);
  const [editing, setEditing] = useState<Station>();
  const [form] = Form.useForm<StationFormValues>();

  const list = useQuery({
    queryKey: ['stations', { page, pageSize, search, statusFilter }],
    queryFn: () => api.stations.list({ page, page_size: pageSize, q: search, status: statusFilter, sort: 'station_id' }),
  });
  const site = useQuery({ queryKey: ['settings', 'site'], queryFn: api.settings.site });
  const detail = useQuery({ queryKey: ['stations', selectedId], queryFn: () => api.stations.get(selectedId!), enabled: Boolean(selectedId) });
  const checklist = useQuery({ queryKey: ['stations', selectedId, 'checklist'], queryFn: () => api.stations.checklist(selectedId!), enabled: Boolean(selectedId) });

  const save = useMutation({
    mutationFn: (values: StationFormValues) => editing
      ? api.stations.update(editing.station_id, { alias: values.alias?.trim() || null })
      : site.data
        ? api.stations.create({ station_id: values.station_id.trim().toUpperCase(), site_id: site.data.id, alias: values.alias?.trim() || null })
        : Promise.reject(new Error('站点设置尚未加载，请稍后重试')),
    onSuccess: () => {
      message.success(editing ? '基站信息已更新' : '基站已添加');
      setCreateOpen(false); setEditing(undefined); form.resetFields();
      queryClient.invalidateQueries({ queryKey: ['stations'] });
    },
    onError: (error) => message.error(error instanceof Error ? error.message : '保存失败'),
  });

  const openEdit = (station: Station) => {
    setEditing(station);
    form.setFieldsValue({ station_id: station.station_id, alias: station.alias ?? undefined });
  };

  const closeForm = () => { setCreateOpen(false); setEditing(undefined); form.resetFields(); };

  const columns: TableColumnsType<Station> = [
    {
      title: '基站 SN', dataIndex: 'station_id', width: 158,
      render: (value: string, row) => <button className="table-link mono" onClick={() => setSelectedId(row.station_id)}>{value}</button>,
    },
    { title: '别名', dataIndex: 'alias', width: 150, render: (value) => value || '—' },
    { title: '状态', dataIndex: 'status', width: 112, render: (value) => <StatusBadge status={value} /> },
    {
      title: 'Broker 地址', dataIndex: 'server_address', width: 190,
      render: (value) => value ? <span className="mono">{value}</span> : '—',
    },
    { title: '固件', dataIndex: 'firmware_version', width: 100, render: (value) => value || '—' },
    {
      title: '最后心跳', dataIndex: 'last_heartbeat_at', width: 180,
      render: (value) => <span>{formatDateTime(value)}<small className="table-secondary">{formatRelative(value)}</small></span>,
    },
    {
      title: '队列', key: 'queue', width: 110,
      render: (_, row) => <span className="numeric">{row.send_count ?? 0} / {row.total_count ?? 0}</span>,
    },
    {
      title: '操作', key: 'actions', width: 96, fixed: 'right',
      render: (_, row) => <Space size={4}>
        <Tooltip title="查看详情"><Button aria-label={`查看基站 ${row.station_id}`} type="text" icon={<Eye size={16} />} onClick={() => setSelectedId(row.station_id)} /></Tooltip>
        <Tooltip title="修改别名"><Button aria-label={`编辑基站 ${row.station_id}`} type="text" icon={<Pencil size={16} />} onClick={() => openEdit(row)} /></Tooltip>
      </Space>,
    },
  ];

  const checklistItems = checklist.data?.items || [];

  return (
    <div>
      <PageHeader title="基站管理" description="登记 eStation 基站并监控心跳、固件和任务队列" actions={
        <Button type="primary" icon={<Plus size={16} />} onClick={() => { form.resetFields(); setCreateOpen(true); }}>添加基站</Button>
      } />
      <Panel className="filter-panel">
        <div className="toolbar">
          <div className="toolbar-main">
            <Input className="filter-search" allowClear value={searchDraft} prefix={<Search size={15} />} placeholder="搜索基站 SN、别名" onChange={(event) => setSearchDraft(event.target.value)} onPressEnter={() => { setPage(1); setSearch(searchDraft.trim()); }} />
            <Select className="filter-field" allowClear placeholder="全部状态" value={statusFilter} onChange={(value) => { setPage(1); setStatusFilter(value); }} options={['ONLINE', 'STALE', 'OFFLINE', 'UNKNOWN'].map((value) => ({ value, label: <StatusBadge status={value} /> }))} />
          </div>
          <div className="toolbar-actions">
            <Button icon={<Search size={15} />} onClick={() => { setPage(1); setSearch(searchDraft.trim()); }}>查询</Button>
            <Button onClick={() => { setSearch(''); setSearchDraft(''); setStatusFilter(undefined); setPage(1); }}>重置</Button>
          </div>
        </div>
      </Panel>
      <Panel className="panel-table" title={`基站列表${list.data ? `（${list.data.pagination.total_items}）` : ''}`}>
        {list.isError && <div className="table-error"><QueryError error={list.error} onRetry={() => list.refetch()} /></div>}
        <div className="table-wrap">
          <Table<Station>
            rowKey="station_id" columns={columns} dataSource={list.data?.items || []} loading={list.isLoading}
            size="small" bordered scroll={{ x: 1110 }} locale={{ emptyText: <EmptyBlock description="暂无基站" /> }}
            pagination={{ current: page, pageSize, total: list.data?.pagination.total_items || 0, showSizeChanger: true, pageSizeOptions: [20, 50, 100], showTotal: (total) => `共 ${total} 条` }}
            onChange={(pagination) => { setPage(pagination.current || 1); setPageSize(pagination.pageSize || 20); }}
          />
        </div>
      </Panel>

      <Modal title={editing ? '修改基站别名' : '添加基站'} open={createOpen || Boolean(editing)} onCancel={closeForm} onOk={() => form.submit()} okText="保存" cancelText="取消" confirmLoading={save.isPending} destroyOnHidden>
        <Form form={form} layout="vertical" requiredMark={false} onFinish={(values) => save.mutate(values)} initialValues={{ station_id: '', alias: '' }}>
          <Form.Item name="station_id" label="基站 SN" normalize={(value) => String(value || '').toUpperCase()} rules={[{ required: true, message: '请输入 12 位基站 SN' }, { pattern: /^90A9F[0-9A-F]{7}$/, message: '基站 SN 格式应为 90A9F 加 7 位十六进制字符' }]}>
            <Input className="mono" maxLength={12} disabled={Boolean(editing)} placeholder="90A9F7301427" autoFocus={!editing} />
          </Form.Item>
          <Form.Item name="alias" label="现场别名" rules={[{ max: 64, message: '别名最多 64 个字符' }]}><Input placeholder="例如：主仓库基站" /></Form.Item>
        </Form>
      </Modal>

      <Drawer title="基站详情" open={Boolean(selectedId)} onClose={() => setSelectedId(undefined)} width={560} className="responsive-drawer" extra={detail.data && <Button icon={<Pencil size={15} />} onClick={() => openEdit(detail.data!)}>修改</Button>}>
        {detail.isLoading ? <div className="drawer-loading">正在加载…</div> : detail.isError ? <QueryError error={detail.error} onRetry={() => detail.refetch()} /> : detail.data && (
          <>
            <div className="drawer-status"><RadioTower size={20} /><strong>{detail.data.alias || detail.data.station_id}</strong><StatusBadge status={detail.data.status} /></div>
            <div className="detail-grid drawer-section">
              <InlineKeyValue label="基站 SN" value={<span className="mono">{detail.data.station_id}</span>} />
              <InlineKeyValue label="MAC" value={<span className="mono">{detail.data.mac || '—'}</span>} />
              <InlineKeyValue label="固件版本" value={detail.data.firmware_version} />
              <InlineKeyValue label="心跳周期" value={detail.data.heartbeat_seconds ? `${detail.data.heartbeat_seconds} 秒` : '—'} />
              <InlineKeyValue label="Broker 地址" value={detail.data.server_address} />
              <InlineKeyValue label="最后心跳" value={formatDateTime(detail.data.last_heartbeat_at)} />
              <InlineKeyValue label="任务队列" value={`${detail.data.send_count ?? 0} / ${detail.data.total_count ?? 0}`} />
              <InlineKeyValue label="更新时间" value={formatDateTime(detail.data.updated_at)} />
            </div>
            <h3 className="drawer-section-title">接入检查清单</h3>
            {checklist.isLoading ? <div className="drawer-loading">正在检查…</div> : checklist.isError ? <QueryError compact error={checklist.error} onRetry={() => checklist.refetch()} /> : checklistItems.length ? (
              <div className="checklist">
                {checklistItems.map((item) => <div className="checklist-item" key={item.key}>{item.status === 'READY' ? <CheckCircle2 size={17} color="#3f8500" /> : <XCircle size={17} color={item.status === 'ACTION_REQUIRED' ? '#e52020' : '#666666'} />}<div><strong>{item.label}</strong>{item.value && <span>{item.value}</span>}</div></div>)}
              </div>
            ) : <EmptyBlock description="服务端未返回检查项" />}
          </>
        )}
      </Drawer>
    </div>
  );
}
