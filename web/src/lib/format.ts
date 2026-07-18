import dayjs from 'dayjs';
import type { Timestamp } from '../api/types';

export function formatDateTime(value: Timestamp, fallback = '—') {
  if (value === null || value === undefined || value === '') return fallback;
  const date = typeof value === 'number' && value < 10_000_000_000 ? value * 1000 : value;
  const parsed = dayjs(date);
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm:ss') : fallback;
}

export function formatRelative(value: Timestamp, fallback = '—') {
  if (value === null || value === undefined || value === '') return fallback;
  const dateValue = typeof value === 'number' && value < 10_000_000_000 ? value * 1000 : value;
  const date = dayjs(dateValue);
  if (!date.isValid()) return fallback;
  const seconds = Math.max(0, dayjs().diff(date, 'second'));
  if (seconds < 60) return `${seconds} 秒前`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟前`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)} 小时前`;
  return `${Math.floor(seconds / 86400)} 天前`;
}

export function formatDuration(seconds?: number) {
  if (seconds === undefined || seconds === null || Number.isNaN(seconds)) return '—';
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  if (days) return `${days} 天 ${hours} 小时`;
  if (hours) return `${hours} 小时 ${minutes} 分钟`;
  return `${minutes} 分钟`;
}

export function formatBytes(value?: number) {
  if (value === undefined || value === null || Number.isNaN(value)) return '—';
  if (value < 1024) return `${value} B`;
  if (value < 1024 ** 2) return `${(value / 1024).toFixed(1)} KB`;
  if (value < 1024 ** 3) return `${(value / 1024 ** 2).toFixed(1)} MB`;
  return `${(value / 1024 ** 3).toFixed(1)} GB`;
}

export function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

export function safeJson(value: unknown) {
  if (typeof value === 'string') return value;
  if (value === undefined || value === null) return '—';
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}
