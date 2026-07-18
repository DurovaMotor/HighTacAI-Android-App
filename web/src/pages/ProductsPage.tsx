import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, App, Button, Drawer, Form, Input, Modal, Select, Space, Table, Tooltip, Upload, type TableColumnsType, type UploadFile } from 'antd';
import { Download, Eye, FileSpreadsheet, Lightbulb, Pencil, Plus, Search, UploadCloud, XCircle } from 'lucide-react';
import { api } from '../api/endpoints';
import type { Binding, ImportErrorRow, ImportJob, Product } from '../api/types';
import { EmptyBlock, InlineKeyValue, PageHeader, Panel, QueryError, StatusBadge } from '../components/common';
import { downloadBlob, formatDateTime } from '../lib/format';

interface ProductFormValues { product_code: string; product_name?: string }

export default function ProductsPage() {
  const { message, modal } = App.useApp();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [search, setSearch] = useState('');
  const [searchDraft, setSearchDraft] = useState('');
  const [sourceFilter, setSourceFilter] = useState<string>();
  const [selected, setSelected] = useState<Product>();
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<Product>();
  const [importOpen, setImportOpen] = useState(false);
  const [form] = Form.useForm<ProductFormValues>();

  const list = useQuery({ queryKey: ['products', { page, pageSize, search, sourceFilter }], queryFn: () => api.products.list({ page, page_size: pageSize, q: search, source: sourceFilter, sort: 'product_code' }) });
  const detail = useQuery({ queryKey: ['products', selected?.id], queryFn: () => api.products.get(selected!.id), enabled: Boolean(selected) });
  const broker = useQuery({ queryKey: ['broker', 'status'], queryFn: api.broker.status, refetchInterval: 5000 });
  const commandReady = broker.data?.service_state === 'RUNNING' && broker.data.mqtt_connected && broker.data.subscriptions_ready;

  const save = useMutation({
    mutationFn: (values: ProductFormValues) => editing
      ? api.products.update(editing.id, { product_name: values.product_name?.trim() || null })
      : api.products.create({ product_code: values.product_code.trim().toUpperCase(), product_name: values.product_name?.trim() || null }),
    onSuccess: () => { message.success(editing ? '产品已更新' : '产品已创建'); setFormOpen(false); setEditing(undefined); form.resetFields(); queryClient.invalidateQueries({ queryKey: ['products'] }); },
    onError: (error) => message.error(error instanceof Error ? error.message : '保存失败'),
  });
  const light = useMutation({
    mutationFn: ({ product, action }: { product: Product; action: 'LIGHT_ON' | 'LIGHT_OFF' }) => api.commands.create({ action, product_code: product.product_code, ...(action === 'LIGHT_ON' ? { color: 'GREEN' } : {}) }),
    onSuccess: (command) => { message.success(`命令已接受：${command.status}`); queryClient.invalidateQueries({ queryKey: ['dashboard'] }); },
    onError: (error) => message.error(error instanceof Error ? error.message : '命令提交失败'),
  });

  const dispatch = (product: Product, action: 'LIGHT_ON' | 'LIGHT_OFF') => {
    if (action === 'LIGHT_OFF') {
      modal.confirm({ title: `确认关闭产品 ${product.product_code} 的全部灯条？`, content: `将向 ${product.active_binding_count} 条灯条发送灭灯命令。`, okText: '确认灭灯', okButtonProps: { danger: true }, cancelText: '取消', onOk: () => light.mutateAsync({ product, action }) });
    } else light.mutate({ product, action });
  };

  const columns: TableColumnsType<Product> = [
    { title: '产品编码', dataIndex: 'product_code', width: 210, render: (value: string, row) => <button className="table-link mono" onClick={() => setSelected(row)}>{value}</button> },
    { title: '产品名称', dataIndex: 'product_name', width: 230, render: (value) => value || <span className="muted-text">未填写</span> },
    { title: '来源', dataIndex: 'source', width: 110, render: (value) => <StatusBadge status={value || 'BINDING'} /> },
    { title: '有效绑定', dataIndex: 'active_binding_count', width: 110, align: 'right', render: (value) => <span className="numeric">{value ?? 0}</span> },
    { title: '状态', dataIndex: 'is_active', width: 100, render: (value) => <StatusBadge status={value === false ? 'REVOKED' : 'APPROVED'} label={value === false ? '已停用' : '启用'} /> },
    { title: '更新时间', dataIndex: 'updated_at', width: 176, render: (value) => formatDateTime(value) },
    {
      title: '操作', key: 'actions', width: 150, fixed: 'right',
      render: (_, row) => <Space size={2}>
        <Tooltip title="查看绑定"><Button type="text" aria-label={`查看产品 ${row.product_code}`} icon={<Eye size={16} />} onClick={() => setSelected(row)} /></Tooltip>
        <Tooltip title={commandReady ? '亮灯（绿色）' : 'MQTT 尚未就绪'}><Button type="text" aria-label={`点亮产品 ${row.product_code}`} icon={<Lightbulb size={16} />} disabled={!commandReady || !row.active_binding_count || light.isPending} onClick={() => dispatch(row, 'LIGHT_ON')} /></Tooltip>
        <Tooltip title={commandReady ? '灭灯' : 'MQTT 尚未就绪'}><Button type="text" aria-label={`关闭产品 ${row.product_code}`} icon={<XCircle size={16} />} disabled={!commandReady || !row.active_binding_count || light.isPending} onClick={() => dispatch(row, 'LIGHT_OFF')} /></Tooltip>
        <Tooltip title="编辑"><Button type="text" aria-label={`编辑产品 ${row.product_code}`} icon={<Pencil size={16} />} onClick={() => { setEditing(row); form.setFieldsValue({ product_code: row.product_code, product_name: row.product_name ?? undefined }); }} /></Tooltip>
      </Space>,
    },
  ];

  const detailProduct = detail.data?.product || selected;
  const detailBindings = detail.data?.active_bindings || [];

  return (
    <div>
      <PageHeader title="产品" description="维护产品编码，并查看每个产品关联的全部灯条" actions={[
        <Button key="import" icon={<FileSpreadsheet size={16} />} onClick={() => setImportOpen(true)}>Excel 导入</Button>,
        <Button key="create" type="primary" icon={<Plus size={16} />} onClick={() => { form.resetFields(); setFormOpen(true); }}>新建产品</Button>,
      ]} />
      <Panel className="filter-panel">
        <div className="toolbar">
          <div className="toolbar-main">
            <Input aria-label="产品关键字筛选" className="filter-search" allowClear prefix={<Search size={15} />} value={searchDraft} placeholder="产品编码、名称或灯条 ID" onChange={(event) => setSearchDraft(event.target.value)} onPressEnter={() => { setSearch(searchDraft.trim()); setPage(1); }} />
            <Select aria-label="产品来源筛选" className="filter-field" allowClear placeholder="全部来源" value={sourceFilter} onChange={(value) => { setSourceFilter(value); setPage(1); }} options={['BINDING', 'EXCEL'].map((value) => ({ value, label: <StatusBadge status={value} /> }))} />
          </div>
          <div className="toolbar-actions"><Button icon={<Search size={15} />} onClick={() => { setSearch(searchDraft.trim()); setPage(1); }}>查询</Button><Button onClick={() => { setSearch(''); setSearchDraft(''); setSourceFilter(undefined); setPage(1); }}>重置</Button></div>
        </div>
      </Panel>
      <Panel className="panel-table" title={`产品列表${list.data ? `（${list.data.pagination.total_items}）` : ''}`}>
        {list.isError && <div className="table-error"><QueryError error={list.error} onRetry={() => list.refetch()} /></div>}
        <div className="table-wrap"><Table<Product> rowKey="id" columns={columns} dataSource={list.data?.items || []} loading={list.isLoading} size="small" bordered scroll={{ x: 1080 }} locale={{ emptyText: <EmptyBlock description="暂无产品" /> }} pagination={{ current: page, pageSize, total: list.data?.pagination.total_items || 0, showSizeChanger: true, pageSizeOptions: [20, 50, 100], showTotal: (total) => `共 ${total} 条` }} onChange={(pagination) => { setPage(pagination.current || 1); setPageSize(pagination.pageSize || 20); }} /></div>
      </Panel>

      <Modal title={editing ? '编辑产品' : '新建产品'} open={formOpen || Boolean(editing)} onCancel={() => { setFormOpen(false); setEditing(undefined); form.resetFields(); }} onOk={() => form.submit()} okText="保存" cancelText="取消" confirmLoading={save.isPending} destroyOnHidden>
        <Form form={form} layout="vertical" requiredMark={false} onFinish={(values) => save.mutate(values)}>
          <Form.Item name="product_code" label="产品编码" normalize={(value) => String(value || '').trim().toUpperCase()} rules={[{ required: true, message: '请输入产品编码' }, { max: 128, message: '产品编码最多 128 个字符' }]}><Input className="mono" disabled={Boolean(editing)} autoFocus placeholder="1711A-ABA-PT" /></Form.Item>
          <Form.Item name="product_name" label="产品名称" rules={[{ max: 200, message: '产品名称最多 200 个字符' }]}><Input placeholder="可稍后补充" /></Form.Item>
        </Form>
      </Modal>

      <ImportProductsModal open={importOpen} onClose={() => setImportOpen(false)} onCommitted={() => { setImportOpen(false); queryClient.invalidateQueries({ queryKey: ['products'] }); }} />

      <Drawer title="产品详情" open={Boolean(selected)} onClose={() => setSelected(undefined)} width={720} className="responsive-drawer" extra={detailProduct && <Space><Button icon={<Lightbulb size={15} />} disabled={!commandReady || !detailBindings.length} onClick={() => dispatch(detailProduct, 'LIGHT_ON')}>亮灯</Button><Button icon={<XCircle size={15} />} disabled={!commandReady || !detailBindings.length} onClick={() => dispatch(detailProduct, 'LIGHT_OFF')}>灭灯</Button></Space>}>
        {detail.isError ? <QueryError error={detail.error} onRetry={() => detail.refetch()} /> : detailProduct && (
          <>
            <div className="drawer-status"><span className="brand-mark" /><strong className="mono">{detailProduct.product_code}</strong><StatusBadge status={detailProduct.is_active === false ? 'REVOKED' : 'APPROVED'} label={detailProduct.is_active === false ? '已停用' : '启用'} /></div>
            <div className="detail-grid drawer-section"><InlineKeyValue label="产品名称" value={detailProduct.product_name} /><InlineKeyValue label="创建来源" value={<StatusBadge status={detailProduct.source || 'BINDING'} />} /><InlineKeyValue label="有效绑定" value={detailBindings.length || detailProduct.active_binding_count || 0} /><InlineKeyValue label="更新时间" value={formatDateTime(detailProduct.updated_at)} /></div>
            <h3 className="drawer-section-title">已绑定灯条</h3>
            <BindingsTable data={detailBindings} loading={detail.isLoading} />
          </>
        )}
      </Drawer>
    </div>
  );
}

function BindingsTable({ data, loading }: { data: Binding[]; loading: boolean }) {
  return <Table<Binding> rowKey="id" loading={loading} size="small" bordered pagination={false} dataSource={data} locale={{ emptyText: <EmptyBlock description="该产品暂无绑定灯条" /> }} columns={[
    { title: '灯条 ID', dataIndex: 'tag_id', render: (value) => <span className="mono">{value}</span> },
    { title: '状态', dataIndex: 'is_active', width: 100, render: (value) => <StatusBadge status={value ? 'APPROVED' : 'REVOKED'} label={value ? '有效' : '已解除'} /> },
    { title: '基站', dataIndex: 'station_id', width: 150, render: (value) => value ? <span className="mono">{value}</span> : '—' },
    { title: '绑定时间', dataIndex: 'bound_at', width: 168, render: (value) => formatDateTime(value) },
  ]} />;
}

const activeImportStatuses = new Set<ImportJob['status']>(['UPLOADED', 'VALIDATING', 'COMMITTING']);

export function ImportProductsModal({ open, onClose, onCommitted }: { open: boolean; onClose: () => void; onCommitted: () => void }) {
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [file, setFile] = useState<File>();
  const [jobId, setJobId] = useState<string>();
  const jobQuery = useQuery({
    queryKey: ['products', 'imports', jobId],
    queryFn: () => api.products.importJob(jobId!),
    enabled: open && Boolean(jobId),
    refetchInterval: (query) => activeImportStatuses.has(query.state.data?.status as ImportJob['status']) ? 400 : false,
  });
  const job = jobQuery.data;
  const preview = useMutation({
    mutationFn: (selected: File) => api.products.previewImport(selected),
    onSuccess: (result) => {
      queryClient.setQueryData(['products', 'imports', result.id], result);
      setJobId(result.id);
    },
    onError: (error) => message.error(error instanceof Error ? error.message : '预检失败'),
  });
  const commit = useMutation({
    mutationFn: (id: string) => api.products.commitImport(id),
    onSuccess: (result) => {
      queryClient.setQueryData(['products', 'imports', result.id], result);
      message.success('Excel 提交任务已接受');
    },
    onError: (error) => message.error(error instanceof Error ? error.message : '提交失败'),
  });
  const fileList: UploadFile[] = useMemo(() => file ? [{ uid: 'selected', name: file.name, status: 'done', size: file.size }] : [], [file]);

  const reset = () => { setFile(undefined); setJobId(undefined); };
  const close = () => { if (preview.isPending || commit.isPending) return; reset(); onClose(); };
  useEffect(() => {
    if (!open || job?.status !== 'COMMITTED') return;
    message.success('产品导入已完成');
    setFile(undefined);
    setJobId(undefined);
    onCommitted();
  }, [job?.id, job?.status, message, onCommitted, open]);

  const downloadTemplate = async () => { try { downloadBlob(await api.products.template(), 'HighTac-产品导入模板.xlsx'); } catch (error) { message.error(error instanceof Error ? error.message : '模板下载失败'); } };
  const downloadErrors = async () => {
    if (!job) return;
    try {
      downloadBlob(await api.products.importErrors(job.id), 'HighTac-导入错误.csv');
    } catch (error) {
      message.error(error instanceof Error ? error.message : '错误文件下载失败');
    }
  };
  const canCommit = Boolean(job && job.error_rows === 0 && job.status === 'READY');
  const busy = preview.isPending || commit.isPending || Boolean(job && activeImportStatuses.has(job.status) && !jobQuery.isError);

  return (
    <Modal title="Excel 导入产品" open={open} onCancel={close} width={760} footer={
      <Space><Button disabled={busy} onClick={close}>取消</Button>{job?.error_rows ? <Button icon={<Download size={15} />} onClick={downloadErrors}>下载错误 CSV</Button> : null}<Button type="primary" icon={<UploadCloud size={15} />} disabled={!canCommit || busy} loading={commit.isPending} onClick={() => job && commit.mutate(job.id)}>整体提交</Button></Space>
    } destroyOnHidden={false}>
      <div className="import-steps" aria-label="Excel 导入流程"><span data-active={!job}>1 选择文件</span><span data-active={Boolean(job && !['COMMITTING', 'COMMITTED'].includes(job.status))}>2 预检结果</span><span data-active={job?.status === 'COMMITTING' || job?.status === 'COMMITTED'}>3 整体提交</span></div>
      <div className="import-toolbar"><p>字段：产品编码（必填）、产品名称。预检有错误时不会写入任何数据。</p><Button icon={<Download size={15} />} onClick={downloadTemplate}>下载模板</Button></div>
      <Upload.Dragger accept=".xlsx" maxCount={1} fileList={fileList} beforeUpload={(selected) => { setFile(selected); setJobId(undefined); return false; }} onRemove={() => { setFile(undefined); setJobId(undefined); }} disabled={busy}>
        <FileSpreadsheet size={30} /><p className="ant-upload-text">选择或拖放 Excel 文件</p><p className="ant-upload-hint">文件仅发送到本机 HighTac 服务进行预检</p>
      </Upload.Dragger>
      <div className="import-preview-action"><Button icon={<Search size={15} />} disabled={!file || busy} loading={preview.isPending} onClick={() => file && preview.mutate(file)}>开始预检</Button></div>
      {jobQuery.isError && <div className="import-result"><QueryError compact error={jobQuery.error} onRetry={() => jobQuery.refetch()} /></div>}
      {job && <div className="import-result"><div className="import-summary"><InlineKeyValue label="总行数" value={job.total_rows} /><InlineKeyValue label="有效行" value={job.valid_rows} /><InlineKeyValue label="错误行" value={job.error_rows} /><InlineKeyValue label="状态" value={<StatusBadge status={job.status} />} /></div>{job.errors.length ? <Table<ImportErrorRow> size="small" bordered rowKey={(row) => `${row.row_number}-${row.field}-${row.code}`} pagination={{ pageSize: 5, hideOnSinglePage: true }} dataSource={job.errors} columns={[{ title: '行', dataIndex: 'row_number', width: 60 }, { title: '字段', dataIndex: 'field', width: 110 }, { title: '错误码', dataIndex: 'code', width: 150 }, { title: '值', dataIndex: 'value', width: 160 }, { title: '错误', dataIndex: 'message' }]} /> : job.status === 'READY' ? <div className="import-valid"><StatusBadge status="READY" label="预检通过，可整体提交" /></div> : null}{job.errors_truncated && <Alert className="import-truncated" type="warning" showIcon message="页面仅显示前 100 条错误，请下载 CSV 查看全部错误。" />}</div>}
    </Modal>
  );
}
