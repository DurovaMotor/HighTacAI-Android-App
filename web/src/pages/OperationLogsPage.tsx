import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { App, Button, DatePicker, Drawer, Dropdown, Input, Select, Space, Table, Tooltip, type MenuProps, type TableColumnsType } from 'antd';
import { ChevronDown, Download, Eye, Search } from 'lucide-react';
import { api } from '../api/endpoints';
import type { OperationLog } from '../api/types';
import { EmptyBlock, InlineKeyValue, PageHeader, Panel, QueryError, StatusBadge } from '../components/common';
import { downloadBlob, formatDateTime, safeJson } from '../lib/format';

const { RangePicker } = DatePicker;

export default function OperationLogsPage() {
  const { message } = App.useApp();
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [filters, setFilters] = useState<Record<string, unknown>>({});
  const [draft, setDraft] = useState<Record<string, unknown>>({});
  const [selected, setSelected] = useState<OperationLog>();
  const [exporting, setExporting] = useState<'csv' | 'xlsx'>();
  const list = useQuery({ queryKey: ['operation-logs', { page, pageSize, filters }], queryFn: () => api.logs.list({ page, page_size: pageSize, ...filters, sort: '-occurred_at' }) });

  const apply = () => { setFilters({ ...draft }); setPage(1); };
  const exportLogs = async (format: 'csv' | 'xlsx') => {
    setExporting(format);
    try { downloadBlob(await api.logs.export(filters, format), `HighTac-操作记录-${new Date().toISOString().slice(0, 10)}.${format}`); message.success(`操作记录已导出为 ${format.toUpperCase()}`); }
    catch (error) { message.error(error instanceof Error ? error.message : '导出失败'); }
    finally { setExporting(undefined); }
  };
  const exportMenu: MenuProps = {
    items: [{ key: 'xlsx', label: '导出 XLSX' }],
    onClick: ({ key }) => exportLogs(key as 'xlsx'),
  };

  const columns: TableColumnsType<OperationLog> = [
    { title: '时间', dataIndex: 'occurred_at', width: 180, render: (value) => formatDateTime(value) },
    { title: '动作', dataIndex: 'event_type', width: 220, render: (value) => <strong>{value}</strong> },
    { title: '操作者', key: 'actor', width: 150, render: (_, row) => <span>{row.actor_display_name || row.actor_id}<small className="table-secondary">{row.actor_type}</small></span> },
    { title: '结果', key: 'result', width: 108, render: (_, row) => <StatusBadge status={row.failure_code ? 'FAILED' : 'SUCCESS'} /> },
    { title: '产品 / 灯条', key: 'entity', width: 190, render: (_, row) => <span className="mono">{row.product_id || row.tag_id || row.station_id || '—'}</span> },
    { title: '客户端 IP', dataIndex: 'client_ip', width: 130, render: (value) => value || '—' },
    { title: '失败代码', dataIndex: 'failure_code', ellipsis: true, render: (value) => value ? <span className="error-text">{value}</span> : '—' },
    { title: '操作', key: 'action', width: 58, fixed: 'right', render: (_, row) => <Tooltip title="查看详情"><Button type="text" aria-label={`查看操作 ${row.id}`} icon={<Eye size={16} />} onClick={() => setSelected(row)} /></Tooltip> },
  ];

  return (
    <div>
      <PageHeader title="操作记录" description="查询管理员、Android 与系统产生的只追加审计记录" actions={
        <Space.Compact>
          <Button icon={<Download size={16} />} loading={exporting === 'csv'} disabled={Boolean(exporting)} onClick={() => exportLogs('csv')}>导出 CSV</Button>
          <Dropdown menu={exportMenu} disabled={Boolean(exporting)} placement="bottomRight" trigger={['click']}>
            <Button aria-label="选择操作记录导出格式" icon={<ChevronDown size={16} />} />
          </Dropdown>
        </Space.Compact>
      } />
      <Panel className="filter-panel">
        <div className="filter-row filter-row-topless">
          <div className="filter-range-wrap">
            <label className="visually-hidden" htmlFor="operation-log-start-time">开始时间</label>
            <label className="visually-hidden" htmlFor="operation-log-end-time">结束时间</label>
            <RangePicker
              id={{ start: 'operation-log-start-time', end: 'operation-log-end-time' }}
              showTime
              className="filter-range"
              placeholder={['开始时间', '结束时间']}
              onChange={(dates) => setDraft((old) => ({ ...old, occurred_from: dates?.[0]?.toISOString(), occurred_to: dates?.[1]?.toISOString() }))}
            />
          </div>
          <Select aria-label="操作者类型" className="filter-field" allowClear placeholder="操作者类型" value={draft.actor_type as string} onChange={(value) => setDraft((old) => ({ ...old, actor_type: value }))} options={['ADMIN', 'ANDROID', 'SYSTEM'].map((value) => ({ value, label: <StatusBadge status={value} /> }))} />
          <Select aria-label="实体类型" className="filter-field" allowClear placeholder="实体类型" value={draft.entity_type as string} onChange={(value) => setDraft((old) => ({ ...old, entity_type: value }))} options={['station', 'tag', 'product', 'binding', 'command', 'device', 'broker', 'backup'].map((value) => ({ value, label: value }))} />
          <Input aria-label="动作类型" className="filter-field" allowClear placeholder="动作类型" value={draft.event_type as string} onChange={(event) => setDraft((old) => ({ ...old, event_type: event.target.value }))} />
          <Input aria-label="实体 ID" className="filter-search" allowClear prefix={<Search size={15} />} placeholder="实体 ID" value={draft.entity_id as string} onChange={(event) => setDraft((old) => ({ ...old, entity_id: event.target.value }))} onPressEnter={apply} />
          <Space><Button icon={<Search size={15} />} onClick={apply}>查询</Button><Button onClick={() => { setDraft({}); setFilters({}); setPage(1); }}>重置</Button></Space>
        </div>
      </Panel>
      <Panel className="panel-table" title={`审计记录${list.data ? `（${list.data.pagination.total_items}）` : ''}`}>
        {list.isError && <div className="table-error"><QueryError error={list.error} onRetry={() => list.refetch()} /></div>}
        <div className="table-wrap"><Table<OperationLog> rowKey="id" columns={columns} dataSource={list.data?.items || []} loading={list.isLoading} size="small" bordered scroll={{ x: 1300 }} locale={{ emptyText: <EmptyBlock description="暂无操作记录" /> }} pagination={{ current: page, pageSize, total: list.data?.pagination.total_items || 0, showSizeChanger: true, pageSizeOptions: [20, 50, 100], showTotal: (total) => `共 ${total} 条` }} onChange={(pagination) => { setPage(pagination.current || 1); setPageSize(pagination.pageSize || 20); }} /></div>
      </Panel>
      <Drawer title="操作详情" open={Boolean(selected)} onClose={() => setSelected(undefined)} width={640} className="responsive-drawer">
        {selected && <>
          <div className="drawer-status"><strong>{selected.event_type}</strong><StatusBadge status={selected.failure_code ? 'FAILED' : 'SUCCESS'} /></div>
          <div className="detail-grid drawer-section"><InlineKeyValue label="记录 ID" value={<span className="mono">{selected.id}</span>} /><InlineKeyValue label="时间" value={formatDateTime(selected.occurred_at)} /><InlineKeyValue label="操作者" value={selected.actor_display_name || selected.actor_id} /><InlineKeyValue label="操作者类型" value={<StatusBadge status={selected.actor_type} />} /><InlineKeyValue label="客户端 IP" value={selected.client_ip} /><InlineKeyValue label="设备型号" value={selected.device_model} /><InlineKeyValue label="基站" value={selected.station_id} /><InlineKeyValue label="灯条" value={selected.tag_id} /><InlineKeyValue label="产品" value={selected.product_id} /><InlineKeyValue label="命令" value={selected.command_id} /></div>
          {selected.failure_code && <div className="drawer-section"><StatusBadge status="FAILED" /><p className="failure-reason">{selected.failure_code}</p></div>}
          <h3 className="drawer-section-title">请求摘要</h3><pre className="json-block">{safeJson(selected.request_summary)}</pre>
          <h3 className="drawer-section-title">结果摘要</h3><pre className="json-block">{safeJson(selected.result_summary)}</pre>
        </>}
      </Drawer>
    </div>
  );
}
