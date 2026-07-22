import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { App, Button, Form, Input, Modal, Select, Space, Table, Tooltip, type TableColumnsType } from 'antd';
import { Link2, Link2Off, Plus, RefreshCw, ScanBarcode, Search } from 'lucide-react';
import { ApiError } from '../api/client';
import { api } from '../api/endpoints';
import type { Binding } from '../api/types';
import { EmptyBlock, PageHeader, Panel, QueryError, StatusBadge } from '../components/common';
import { formatDateTime } from '../lib/format';

interface BindFormValues { product_code: string; product_name?: string; tag_ids: string; station_id: string }

function parseTagIds(value: string) {
  return [...new Set(value.toUpperCase().split(/[\s,;，；]+/).map((item) => item.trim()).filter(Boolean))];
}

export default function BindingsPage() {
  const { message, modal } = App.useApp();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [filters, setFilters] = useState<Record<string, string | undefined>>({});
  const [draft, setDraft] = useState<Record<string, string | undefined>>({});
  const [bindOpen, setBindOpen] = useState(false);
  const [rebindTarget, setRebindTarget] = useState<Binding>();
  const [form] = Form.useForm<BindFormValues>();
  const [rebindForm] = Form.useForm<{ product_code: string }>();

  const list = useQuery({ queryKey: ['bindings', { page, pageSize, filters }], queryFn: () => api.bindings.list({ page, page_size: pageSize, is_active: true, ...filters, sort: '-bound_at' }) });
  const create = useMutation({
    mutationFn: async (values: BindFormValues) => {
      const tagIds = parseTagIds(values.tag_ids);
      const created: Binding[] = [];
      const failed: Array<{ tagId: string; error: unknown }> = [];
      for (const tagId of tagIds) {
        try {
          created.push(await api.bindings.create({ product_code: values.product_code.trim().toUpperCase(), product_name: values.product_name?.trim() || null, tag_id: tagId, station_id: values.station_id.trim().toUpperCase() }));
        } catch (error) {
          failed.push({ tagId, error });
        }
      }
      return { created, failed };
    },
    onSuccess: ({ created, failed }) => {
      queryClient.invalidateQueries({ queryKey: ['bindings'] });
      queryClient.invalidateQueries({ queryKey: ['products'] });
      queryClient.invalidateQueries({ queryKey: ['tags'] });
      if (failed.length) {
        form.setFieldValue('tag_ids', failed.map((item) => item.tagId).join('\n'));
        const conflictOnly = failed.every((item) => item.error instanceof ApiError && item.error.status === 409);
        message.warning(created.length
          ? `已创建 ${created.length} 条绑定，${failed.length} 条失败；失败灯条已保留在输入框中`
          : conflictOnly
            ? '灯条已绑定其他产品，请使用“显式重绑”'
            : `${failed.length} 条绑定创建失败，请检查后重试`);
        return;
      }
      message.success(`已创建 ${created.length} 条绑定`);
      setBindOpen(false);
      form.resetFields();
    },
    onError: (error) => message.error(error instanceof ApiError && error.status === 409 ? '灯条已绑定其他产品，请在列表中使用“显式重绑”' : error instanceof Error ? error.message : '绑定失败'),
  });
  const remove = useMutation({
    mutationFn: api.bindings.remove,
    onSuccess: () => { message.success('绑定已解除并保留历史记录'); queryClient.invalidateQueries({ queryKey: ['bindings'] }); queryClient.invalidateQueries({ queryKey: ['products'] }); queryClient.invalidateQueries({ queryKey: ['tags'] }); },
    onError: (error) => message.error(error instanceof Error ? error.message : '解绑失败'),
  });
  const rebind = useMutation({
    mutationFn: ({ binding, product_code }: { binding: Binding; product_code: string }) => api.bindings.rebind(binding.id, { product_code: product_code.trim().toUpperCase(), expected_tag_id: binding.tag_id }),
    onSuccess: () => { message.success('灯条已显式重绑'); setRebindTarget(undefined); rebindForm.resetFields(); queryClient.invalidateQueries({ queryKey: ['bindings'] }); queryClient.invalidateQueries({ queryKey: ['products'] }); queryClient.invalidateQueries({ queryKey: ['tags'] }); },
    onError: (error) => message.error(error instanceof Error ? error.message : '重绑失败'),
  });

  const confirmRemove = (binding: Binding) => modal.confirm({ title: '确认解绑灯条？', icon: <Link2Off size={20} color="#e52020" />, content: <span>灯条 <strong className="mono">{binding.tag_id}</strong> 将从产品 <strong className="mono">{binding.product_code}</strong> 解除。历史记录会保留。</span>, okText: '确认解绑', okButtonProps: { danger: true }, cancelText: '取消', onOk: () => remove.mutateAsync(binding.id) });

  const columns: TableColumnsType<Binding> = [
    { title: '产品编码', dataIndex: 'product_code', width: 205, render: (value) => <strong className="mono">{value || '—'}</strong> },
    { title: '产品名称', dataIndex: 'product_name', width: 190, render: (value) => value || '—' },
    { title: '灯条 ID', dataIndex: 'tag_id', width: 176, render: (value) => <span className="mono">{value}</span> },
    { title: '状态', dataIndex: 'is_active', width: 110, render: (value) => <StatusBadge status={value ? 'APPROVED' : 'REVOKED'} label={value ? '有效' : '已解除'} /> },
    { title: '基站', dataIndex: 'station_id', width: 148, render: (value) => value ? <span className="mono">{value}</span> : '—' },
    { title: '来源', dataIndex: 'source', width: 108, render: (value) => <StatusBadge status={value || 'WEB'} /> },
    { title: '绑定时间', dataIndex: 'bound_at', width: 178, render: (value) => formatDateTime(value) },
    {
      title: '操作', key: 'actions', width: 96, fixed: 'right',
      render: (_, row) => <Space size={3}>
        <Tooltip title="显式重绑"><Button type="text" aria-label={`重绑灯条 ${row.tag_id}`} icon={<RefreshCw size={16} />} onClick={() => { setRebindTarget(row); rebindForm.setFieldValue('product_code', ''); }} /></Tooltip>
        <Tooltip title="解除绑定"><Button type="text" danger aria-label={`解绑灯条 ${row.tag_id}`} icon={<Link2Off size={16} />} onClick={() => confirmRemove(row)} /></Tooltip>
      </Space>,
    },
  ];

  const apply = () => {
    setFilters(Object.fromEntries(Object.entries(draft).map(([key, value]) => [key, value?.trim().toUpperCase() || undefined])));
    setPage(1);
  };

  return (
    <div>
      <PageHeader title="绑定关系" description="支持扫码枪输入、单产品多灯条绑定、软解绑和显式重绑" actions={<Button type="primary" icon={<Plus size={16} />} onClick={() => setBindOpen(true)}>新建绑定</Button>} />
      <Panel className="filter-panel">
        <div className="toolbar">
          <div className="toolbar-main">
            <Input aria-label="产品编码筛选" className="filter-search" allowClear prefix={<Search size={15} />} value={draft.product_code} placeholder="产品编码" onChange={(event) => setDraft((old) => ({ ...old, product_code: event.target.value }))} onPressEnter={apply} />
            <Input aria-label="灯条 ID 筛选" className="filter-field mono" allowClear value={draft.tag_id} placeholder="灯条 ID" onChange={(event) => setDraft((old) => ({ ...old, tag_id: event.target.value }))} onPressEnter={apply} />
            <Select className="filter-field" allowClear placeholder="绑定来源" value={draft.source} onChange={(value) => setDraft((old) => ({ ...old, source: value }))} options={['WEB', 'ANDROID', 'MIGRATION'].map((value) => ({ value, label: <StatusBadge status={value} /> }))} />
          </div>
          <div className="toolbar-actions"><Button icon={<Search size={15} />} onClick={apply}>查询</Button><Button onClick={() => { setDraft({}); setFilters({}); setPage(1); }}>重置</Button></div>
        </div>
      </Panel>
      <Panel className="panel-table" title={`有效绑定${list.data ? `（${list.data.pagination.total_items}）` : ''}`}>
        {list.isError && <div className="table-error"><QueryError error={list.error} onRetry={() => list.refetch()} /></div>}
        <div className="table-wrap"><Table<Binding> rowKey="id" columns={columns} dataSource={list.data?.items || []} loading={list.isLoading} size="small" bordered scroll={{ x: 1220 }} locale={{ emptyText: <EmptyBlock description="暂无有效绑定" /> }} pagination={{ current: page, pageSize, total: list.data?.pagination.total_items || 0, showSizeChanger: true, pageSizeOptions: [20, 50, 100], showTotal: (total) => `共 ${total} 条` }} onChange={(pagination) => { setPage(pagination.current || 1); setPageSize(pagination.pageSize || 20); }} /></div>
      </Panel>

      <Modal title="新建绑定" open={bindOpen} onCancel={() => { setBindOpen(false); form.resetFields(); }} onOk={() => form.submit()} okText="创建绑定" cancelText="取消" confirmLoading={create.isPending} width={600} destroyOnHidden>
        <Form form={form} layout="vertical" requiredMark={false} onFinish={(values) => create.mutate(values)}>
          <div className="form-grid-2">
            <Form.Item name="product_code" label="产品编码" normalize={(value) => String(value || '').trim().toUpperCase()} rules={[{ required: true, message: '请输入产品编码' }]}><Input className="mono" prefix={<ScanBarcode size={15} />} autoFocus placeholder="扫码或键盘输入" /></Form.Item>
            <Form.Item name="product_name" label="产品名称"><Input placeholder="产品不存在时一并创建" /></Form.Item>
          </div>
          <Form.Item name="tag_ids" label="灯条 ID（可输入多个）" extra="支持扫码枪逐行输入，或使用空格、逗号分隔；最多 100 条。" rules={[{ required: true, message: '至少输入一个灯条 ID' }, { validator: (_, value) => { const ids = parseTagIds(value || ''); if (ids.length > 100) return Promise.reject(new Error('一次最多绑定 100 条灯条')); const invalid = ids.find((id) => !/^AD1[0-9A-F]{9}$/.test(id)); return invalid ? Promise.reject(new Error(`灯条 ID 格式错误：${invalid}`)) : Promise.resolve(); } }]}><Input.TextArea className="mono" autoSize={{ minRows: 4, maxRows: 9 }} placeholder={'AD1000000001\nAD1000000002'} /></Form.Item>
          <Form.Item name="station_id" label="基站 SN" normalize={(value) => String(value || '').trim().toUpperCase()} rules={[{ required: true, message: '请输入基站 SN' }, { pattern: /^90A9F[0-9A-F]{7}$/, message: '基站 SN 格式不正确' }]}><Input className="mono" maxLength={12} /></Form.Item>
          <div className="form-note"><Link2 size={16} /><span>产品不存在时服务端会自动创建；已绑定其他产品的灯条不会被静默覆盖。</span></div>
        </Form>
      </Modal>

      <Modal title="显式重绑" open={Boolean(rebindTarget)} onCancel={() => setRebindTarget(undefined)} onOk={() => rebindForm.submit()} okText="确认重绑" cancelText="取消" confirmLoading={rebind.isPending}>
        <p>灯条 <strong className="mono">{rebindTarget?.tag_id}</strong> 当前绑定 <strong className="mono">{rebindTarget?.product_code}</strong>。重绑会软解除旧关系并创建新关系。</p>
        <Form form={rebindForm} layout="vertical" requiredMark={false} onFinish={(values) => rebindTarget && rebind.mutate({ binding: rebindTarget, product_code: values.product_code })}>
          <Form.Item name="product_code" label="目标产品编码" normalize={(value) => String(value || '').trim().toUpperCase()} rules={[{ required: true, message: '请输入目标产品编码' }, { validator: (_, value) => value === rebindTarget?.product_code ? Promise.reject(new Error('目标产品不能与当前产品相同')) : Promise.resolve() }]}><Input className="mono" autoFocus prefix={<RefreshCw size={15} />} /></Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
