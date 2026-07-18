import type { ReactNode } from 'react';
import { Alert, Button, Empty, Skeleton, Space, Tag, Tooltip } from 'antd';
import { RefreshCw } from 'lucide-react';
import { ApiError } from '../api/client';

const statusLabels: Record<string, string> = {
  ONLINE: '在线', OFFLINE: '离线', UNKNOWN: '未知', STALE: '心跳超时',
  RUNNING: '运行中', STARTING: '启动中', STOPPING: '停止中', STOPPED: '已停止', FAILED: '失败',
  PENDING: '待处理', APPROVED: '已批准', REVOKED: '已撤销',
  GOOD: '良好', MEDIUM: '一般', LOW: '低电量', CRITICAL: '严重低电量',
  ACCEPTED: '已接受', PUBLISHED: '已下发', CONFIRMED: '已确认',
  PARTIALLY_CONFIRMED: '部分确认', UNCONFIRMED: '未确认', SUPERSEDED: '已取代',
  SUCCESS: '成功', SUCCEEDED: '已完成', COMPLETED: '已完成', CREATING: '创建中', RESTORING: '恢复中',
  READY: '就绪', DEGRADED: '降级', NOT_READY: '未就绪',
  UPLOADED: '已上传', VALIDATING: '校验中', INVALID: '存在错误', COMMITTING: '提交中', COMMITTED: '已提交',
  BACKING_UP: '备份中', RESTARTING: '重启中', DELETED: '已删除',
  LIGHT_ON: '亮灯', LIGHT_OFF: '灭灯',
  ANDROID: 'Android', ADMIN: '管理员', SYSTEM: '系统', WEB: '网页', MIGRATION: '迁移', EXCEL: 'Excel', BINDING: '绑定创建',
  SCHEDULED: '定时', MANUAL: '手动', PRE_RESTORE: '恢复前',
};

function statusTone(status: string) {
  const upper = status.toUpperCase();
  if (['ONLINE', 'RUNNING', 'APPROVED', 'GOOD', 'CONFIRMED', 'SUCCESS', 'SUCCEEDED', 'COMPLETED', 'COMMITTED', 'READY'].includes(upper)) return 'success';
  if (['STARTING', 'STOPPING', 'PUBLISHED', 'ACCEPTED', 'CREATING', 'RESTORING', 'UPLOADED', 'VALIDATING', 'COMMITTING', 'BACKING_UP', 'RESTARTING'].includes(upper)) return 'info';
  if (['PENDING', 'STALE', 'MEDIUM', 'LOW', 'UNCONFIRMED', 'PARTIALLY_CONFIRMED', 'DEGRADED'].includes(upper)) return 'warning';
  if (['OFFLINE', 'FAILED', 'REVOKED', 'CRITICAL', 'INVALID', 'NOT_READY', 'DELETED'].includes(upper)) return 'error';
  return 'neutral';
}

export function StatusBadge({ status, label }: { status?: string | null; label?: string }) {
  const value = status || 'UNKNOWN';
  return <Tag className={`status-tag status-${statusTone(value)}`}><span className="status-dot" aria-hidden="true" />{label || statusLabels[value.toUpperCase()] || value}</Tag>;
}

export function PageHeader({ title, description, actions }: { title: string; description?: string; actions?: ReactNode }) {
  return (
    <div className="page-heading">
      <div>
        <h1>{title}</h1>
        {description && <p>{description}</p>}
      </div>
      {actions && <Space wrap className="page-actions">{actions}</Space>}
    </div>
  );
}

export function Panel({ title, extra, children, className = '' }: { title?: ReactNode; extra?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={`panel ${className}`}>
      {(title || extra) && (
        <div className="panel-heading">
          <h2>{title}</h2>
          {extra}
        </div>
      )}
      <div className="panel-body">{children}</div>
    </section>
  );
}

export function MetricTile({ label, value, meta, tone = 'default' }: { label: string; value: ReactNode; meta?: ReactNode; tone?: 'default' | 'success' | 'warning' | 'error' | 'info' }) {
  return (
    <div className={`metric-tile metric-${tone}`}>
      <span className="metric-label">{label}</span>
      <strong>{value}</strong>
      {meta && <span className="metric-meta">{meta}</span>}
    </div>
  );
}

export function QueryError({ error, onRetry, compact = false }: { error: unknown; onRetry?: () => void; compact?: boolean }) {
  const message = error instanceof ApiError ? error.message : error instanceof Error ? error.message : '暂时无法加载数据';
  return (
    <Alert
      type="error"
      showIcon
      message={compact ? message : '数据加载失败'}
      description={compact ? undefined : message}
      action={onRetry && <Button size="small" icon={<RefreshCw size={14} />} onClick={onRetry}>重试</Button>}
    />
  );
}

export function LoadingBlock({ rows = 4 }: { rows?: number }) {
  return <div className="loading-block"><Skeleton active paragraph={{ rows }} title /></div>;
}

export function EmptyBlock({ description = '暂无数据' }: { description?: string }) {
  return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={description} />;
}

export function IconButton({ label, children, ...props }: { label: string; children: ReactNode } & Omit<React.ComponentProps<typeof Button>, 'children'>) {
  return <Tooltip title={label}><Button aria-label={label} {...props}>{children}</Button></Tooltip>;
}

export function InlineKeyValue({ label, value }: { label: string; value: ReactNode }) {
  return <div className="inline-kv"><span>{label}</span><strong>{value || '—'}</strong></div>;
}
