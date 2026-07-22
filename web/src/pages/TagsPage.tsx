import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { App, Button, Drawer, Form, Input, Modal, Progress, Segmented, Select, Space, Table, Timeline, Tooltip, type TableColumnsType } from 'antd';
import { BatteryLow, Clipboard, Eye, Plus, Search, Tag as TagIcon, TriangleAlert } from 'lucide-react';
import { useNavigate, useParams } from 'react-router-dom';
import { api } from '../api/endpoints';
import type { BatteryLevel, LightTag } from '../api/types';
import { EmptyBlock, InlineKeyValue, PageHeader, Panel, QueryError, StatusBadge } from '../components/common';
import { copyText } from '../lib/clipboard';
import { formatDateTime, formatRelative } from '../lib/format';

type TagView = 'all' | 'low-battery' | 'abnormal';
type TagFilters = Record<string, string | boolean | undefined>;

function tagStatus(tag: LightTag) {
  if (!tag.last_seen_at) return 'UNKNOWN';
  return tag.online ? 'ONLINE' : 'OFFLINE';
}

function batteryBadge(level: BatteryLevel, low: boolean) {
  if (level === null) return <StatusBadge status="UNKNOWN" label="未知" />;
  return <StatusBadge status={low ? 'LOW' : 'GOOD'} label={`${level}%${low ? ' · 低电量' : ''}`} />;
}

export default function TagsPage() {
  const params = useParams();
  const view: TagView = params.view === 'low-battery' || params.view === 'abnormal' ? params.view : 'all';
  const navigate = useNavigate();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState<20 | 50 | 100>(20);
  const [filters, setFilters] = useState<TagFilters>({});
  const [draft, setDraft] = useState<TagFilters>({});
  const [selectedId, setSelectedId] = useState<string>();
  const [registerOpen, setRegisterOpen] = useState(false);
  const [form] = Form.useForm<{ tag_id: string; station_id: string }>();

  const list = useQuery({
    queryKey: ['tags', view, { page, pageSize, filters }],
    queryFn: () => api.tags.list({ page, page_size: pageSize, ...filters, sort: '-last_seen_at' }, view),
  });
  const detail = useQuery({ queryKey: ['tags', selectedId], queryFn: () => api.tags.get(selectedId!), enabled: Boolean(selectedId) });
  const register = useMutation({
    mutationFn: api.tags.register,
    onSuccess: () => {
      message.success('灯条已登记');
      setRegisterOpen(false);
      form.resetFields();
      queryClient.invalidateQueries({ queryKey: ['tags'] });
    },
    onError: (error) => message.error(error instanceof Error ? error.message : '登记失败'),
  });

  const copyId = async (tagId: string) => {
    try {
      await copyText(tagId);
      message.success('灯条 ID 已复制');
    } catch (error) {
      message.error(error instanceof Error ? error.message : '复制灯条 ID 失败，请手动选择后复制');
    }
  };

  const columns: TableColumnsType<LightTag> = [
    {
      title: '灯条 ID', dataIndex: 'tag_id', width: 188,
      render: (value: string, row) => <Space size={4}><button className="table-link mono" onClick={() => setSelectedId(row.tag_id)}>{value}</button><Tooltip title="复制"><Button aria-label={`复制灯条 ${value}`} type="text" size="small" icon={<Clipboard size={14} />} onClick={() => copyId(value)} /></Tooltip></Space>,
    },
    { title: '状态', key: 'status', width: 104, render: (_, row) => <StatusBadge status={tagStatus(row)} /> },
    {
      title: '电量', key: 'battery', width: 180,
      render: (_, row) => <div className="battery-cell">{batteryBadge(row.battery_level, row.low_battery)}<span>{row.battery_voltage !== null ? `${row.battery_voltage.toFixed(2)} V` : '电压未知'}</span></div>,
    },
    {
      title: '绑定状态', key: 'binding', width: 190,
      render: (_, row) => row.active_binding_id
        ? <span><strong>已绑定</strong><small className="table-secondary mono">{row.active_binding_id.slice(0, 12)}…</small></span>
        : <StatusBadge status="UNKNOWN" label="未绑定" />,
    },
    { title: '基站', dataIndex: 'station_id', width: 142, render: (value) => value ? <span className="mono">{value}</span> : '—' },
    {
      title: '异常', dataIndex: 'is_abnormal', width: 160,
      render: (value, row) => value ? <span className="error-text"><TriangleAlert size={14} />{row.abnormal_reason || '异常'}</span> : <span className="muted-text">正常</span>,
    },
    {
      title: '最后发现', dataIndex: 'last_seen_at', width: 180,
      render: (value) => <span>{formatDateTime(value)}<small className="table-secondary">{formatRelative(value)}</small></span>,
    },
    { title: '固件', dataIndex: 'firmware_version', width: 90, render: (value) => value || '—' },
    {
      title: '操作', key: 'actions', width: 56, fixed: 'right',
      render: (_, row) => <Tooltip title="查看详情"><Button aria-label={`查看灯条 ${row.tag_id}`} type="text" icon={<Eye size={16} />} onClick={() => setSelectedId(row.tag_id)} /></Tooltip>,
    },
  ];

  const applyFilters = () => {
    setPage(1);
    setFilters({ ...draft, q: typeof draft.q === 'string' ? draft.q.trim() : undefined });
  };
  const tag = detail.data?.tag;
  const activeBinding = detail.data?.active_binding;
  const batteryPercent = tag?.battery_level ?? 0;

  return (
    <div>
      <PageHeader title={view === 'low-battery' ? '低电量灯条' : view === 'abnormal' ? '异常灯条' : '灯条管理'} description="查询灯条在线、电池、异常与绑定状态；列表始终由服务端分页" actions={<Button type="primary" icon={<Plus size={16} />} onClick={() => setRegisterOpen(true)}>手工登记</Button>} />
      <Panel className="filter-panel">
        <div className="toolbar view-toolbar">
          <Segmented value={view} options={[{ label: '全部灯条', value: 'all' }, { label: '低电量', value: 'low-battery', icon: <BatteryLow size={14} /> }, { label: '异常', value: 'abnormal', icon: <TriangleAlert size={14} /> }]} onChange={(value) => { setPage(1); navigate(`/tags/${value}`); }} />
          <div className="toolbar-actions">
            <Button icon={<Search size={15} />} onClick={applyFilters}>查询</Button>
            <Button onClick={() => { setDraft({}); setFilters({}); setPage(1); }}>重置</Button>
          </div>
        </div>
        <div className="filter-row">
          {view === 'all' && <Input aria-label="灯条或产品筛选" className="filter-search" allowClear value={draft.q as string} prefix={<Search size={15} />} placeholder="灯条 ID / 产品编码" onChange={(event) => setDraft((old) => ({ ...old, q: event.target.value }))} onPressEnter={applyFilters} />}
          <Input aria-label="基站 SN 筛选" className="filter-field" allowClear value={draft.station_id as string} placeholder="基站 SN" onChange={(event) => setDraft((old) => ({ ...old, station_id: event.target.value.trim().toUpperCase() }))} />
          {view === 'all' && <Select className="filter-field" allowClear placeholder="在线状态" value={draft.online as boolean} onChange={(value) => setDraft((old) => ({ ...old, online: value }))} options={[{ value: true, label: <StatusBadge status="ONLINE" /> }, { value: false, label: <StatusBadge status="OFFLINE" /> }]} />}
          {view === 'all' && <Select className="filter-field" allowClear placeholder="电量状态" value={draft.low_battery as boolean} onChange={(value) => setDraft((old) => ({ ...old, low_battery: value }))} options={[{ value: true, label: '低电量' }, { value: false, label: '电量正常' }]} />}
          {view === 'all' && <Select className="filter-field" allowClear placeholder="绑定状态" value={draft.bound as boolean} onChange={(value) => setDraft((old) => ({ ...old, bound: value }))} options={[{ value: true, label: '已绑定' }, { value: false, label: '未绑定' }]} />}
        </div>
      </Panel>

      <Panel className="panel-table" title={`灯条列表${list.data ? `（${list.data.pagination.total_items}）` : ''}`}>
        {list.isError && <div className="table-error"><QueryError error={list.error} onRetry={() => list.refetch()} /></div>}
        <div className="table-wrap">
          <Table<LightTag>
            rowKey="tag_id" columns={columns} dataSource={list.data?.items || []} loading={list.isLoading} size="small" bordered scroll={{ x: 1300 }}
            locale={{ emptyText: <EmptyBlock description={view === 'all' ? '暂无灯条' : '当前视图暂无灯条'} /> }}
            pagination={{ current: page, pageSize, total: list.data?.pagination.total_items || 0, showSizeChanger: true, pageSizeOptions: [20, 50, 100], showTotal: (total) => `共 ${total} 条` }}
            onChange={(pagination) => { setPage(pagination.current || 1); setPageSize((pagination.pageSize || 20) as 20 | 50 | 100); }}
          />
        </div>
      </Panel>

      <Modal title="手工登记灯条" open={registerOpen} onCancel={() => { setRegisterOpen(false); form.resetFields(); }} onOk={() => form.submit()} okText="登记" cancelText="取消" confirmLoading={register.isPending} destroyOnHidden>
        <Form form={form} layout="vertical" requiredMark={false} onFinish={(values) => register.mutate({ tag_id: values.tag_id.toUpperCase(), station_id: values.station_id.toUpperCase() })}>
          <Form.Item name="tag_id" label="灯条 ID" normalize={(value) => String(value || '').trim().toUpperCase()} rules={[{ required: true, message: '请输入灯条 ID' }, { pattern: /^AD1[0-9A-F]{9}$/, message: '格式应为 AD1 加 9 位十六进制字符' }]}><Input className="mono" maxLength={12} autoFocus placeholder="AD1000000001" prefix={<TagIcon size={15} />} /></Form.Item>
          <Form.Item name="station_id" label="基站 SN" normalize={(value) => String(value || '').trim().toUpperCase()} rules={[{ required: true, message: '请输入基站 SN' }, { pattern: /^90A9F[0-9A-F]{7}$/, message: '基站 SN 格式不正确' }]}><Input className="mono" maxLength={12} placeholder="90A9F7301427" /></Form.Item>
        </Form>
      </Modal>

      <Drawer title="灯条详情" open={Boolean(selectedId)} onClose={() => setSelectedId(undefined)} width={600} className="responsive-drawer">
        {detail.isLoading ? <div className="drawer-loading">正在加载…</div> : detail.isError ? <QueryError error={detail.error} onRetry={() => detail.refetch()} /> : tag && (
          <>
            <div className="drawer-status"><TagIcon size={20} /><strong className="mono">{tag.tag_id}</strong><StatusBadge status={tagStatus(tag)} />{tag.is_abnormal && <StatusBadge status="FAILED" label="异常" />}</div>
            <div className="battery-detail drawer-section">
              <div><span>电池状态</span>{batteryBadge(tag.battery_level, tag.low_battery)}</div>
              <Progress percent={batteryPercent} showInfo={false} strokeColor={tag.low_battery ? '#df6500' : '#76b900'} trailColor="#e8e8e8" size="small" />
              <small>{tag.battery_voltage !== null ? `${tag.battery_voltage.toFixed(2)} V` : '电压未知'}{tag.battery_raw !== null ? ` · 原始值 ${tag.battery_raw}` : ''}</small>
            </div>
            <div className="detail-grid drawer-section">
              <InlineKeyValue label="绑定产品" value={activeBinding?.product_code || '未绑定'} />
              <InlineKeyValue label="产品名称" value={activeBinding?.product_name} />
              <InlineKeyValue label="所属基站" value={<span className="mono">{tag.station_id || '—'}</span>} />
              <InlineKeyValue label="分组" value={tag.group_no ?? '—'} />
              <InlineKeyValue label="固件版本" value={tag.firmware_version} />
              <InlineKeyValue label="最近回执" value={tag.last_result_type ?? '—'} />
              <InlineKeyValue label="首次发现" value={formatDateTime(tag.first_seen_at)} />
              <InlineKeyValue label="最后发现" value={formatDateTime(tag.last_seen_at)} />
              <InlineKeyValue label="异常原因" value={tag.abnormal_reason || '无'} />
            </div>
            <h3 className="drawer-section-title">最近状态历史</h3>
            {detail.data?.recent_history.length ? <Timeline items={detail.data.recent_history.map((item) => ({
              color: item.is_abnormal ? 'red' : 'gray',
              children: <div><strong>{item.is_abnormal ? '异常状态' : item.online ? '在线状态' : '离线状态'}</strong><span className="timeline-meta">{formatDateTime(item.occurred_at)}{item.result_type !== null ? ` · 回执 ${item.result_type}` : ''}{item.abnormal_reason ? ` · ${item.abnormal_reason}` : ''}</span></div>,
            }))} /> : <EmptyBlock description="暂无状态历史" />}
          </>
        )}
      </Drawer>
    </div>
  );
}
