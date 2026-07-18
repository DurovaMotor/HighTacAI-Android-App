import { App as AntApp } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import BrokerPage from './BrokerPage';

const mocks = vi.hoisted(() => ({
  status: vi.fn(),
  config: vi.fn(),
  logs: vi.fn(),
  summary: vi.fn(),
  start: vi.fn(),
  stop: vi.fn(),
  restart: vi.fn(),
}));

vi.mock('../api/endpoints', () => ({
  api: {
    broker: {
      status: mocks.status,
      config: mocks.config,
      logs: mocks.logs,
      start: mocks.start,
      stop: mocks.stop,
      restart: mocks.restart,
    },
    dashboard: { summary: mocks.summary },
  },
}));

function brokerLogs(lines: string[]) {
  return {
    service_name: 'HighTacMqttBroker',
    requested_lines: 200,
    returned_lines: lines.length,
    lines,
    truncated: false,
    read_at: '2026-07-17T00:00:00Z',
  };
}

describe('BrokerPage operational checks', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.status.mockResolvedValue({
      service_name: 'HighTacMqttBroker',
      service_state: 'RUNNING',
      endpoint: '192.168.1.105:1884',
      tcp_reachable: true,
      mqtt_connected: true,
      subscriptions_ready: true,
      started_at: '2026-07-17T00:00:00Z',
      uptime_seconds: 3600,
      checked_at: '2026-07-17T01:00:00Z',
    });
    mocks.config.mockResolvedValue({
      service_name: 'HighTacMqttBroker', listener_host: '0.0.0.0', listener_port: 1884,
      tls_enabled: false, anonymous_enabled: false, persistence_enabled: true,
      backend_username: 'backend-redacted', station_account_count: 3,
    });
    mocks.logs.mockResolvedValue(brokerLogs(['line 1']));
    mocks.summary.mockResolvedValue({ station_counts: { total: 3, online: 1, stale: 1, offline: 1, unknown: 0 } });
  });

  it('shows station heartbeat and lets operators pause only automatic scrolling', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    const view = render(
      <AntApp>
        <QueryClientProvider client={client}><BrokerPage /></QueryClientProvider>
      </AntApp>,
    );

    expect(await screen.findByText('基站心跳')).toBeInTheDocument();
    expect(screen.getByText('1 在线 · 1 超时 · 1 离线')).toBeInTheDocument();

    const logView = await screen.findByLabelText('Broker 日志');
    Object.defineProperty(logView, 'scrollHeight', { configurable: true, value: 240 });
    logView.scrollTop = 0;
    mocks.logs.mockResolvedValue(brokerLogs(['line 1', 'line 2']));
    fireEvent.click(screen.getByRole('button', { name: '刷新日志' }));
    await waitFor(() => expect(logView.scrollTop).toBe(240));

    fireEvent.click(screen.getByRole('switch', { name: '暂停 Broker 日志自动滚动' }));
    logView.scrollTop = 40;
    mocks.logs.mockResolvedValue(brokerLogs(['line 1', 'line 2', 'line 3']));
    fireEvent.click(screen.getByRole('button', { name: '刷新日志' }));
    await waitFor(() => expect(logView).toHaveTextContent('line 3'));
    expect(logView.scrollTop).toBe(40);

    view.unmount();
  });
});
