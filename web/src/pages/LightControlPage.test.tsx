import { App as AntApp } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import LightControlPage from './LightControlPage';

const mocks = vi.hoisted(() => ({
  brokerStatus: vi.fn(),
  stationList: vi.fn(),
  createCommand: vi.fn(),
  getCommand: vi.fn(),
  allOff: vi.fn(),
}));

vi.mock('../api/endpoints', () => ({
  api: {
    broker: { status: mocks.brokerStatus },
    stations: { list: mocks.stationList, allOff: mocks.allOff },
    commands: { create: mocks.createCommand, get: mocks.getCommand },
  },
}));

vi.mock('../realtime/RealtimeProvider', () => ({
  useRealtime: () => ({ state: 'connected', retry: vi.fn(), browserOnline: true }),
}));

const command = {
  id: 'd7d74ed4-44d7-4934-af3a-a80d93aaf63f',
  action: 'LIGHT_ON',
  product_id: '12f2390e-301f-4c44-a90d-205b5ff0b39f',
  product_code: '1711A-ABA-PT',
  requested_color: 'RED',
  status: 'CONFIRMED',
  target_count: 1,
  confirmed_count: 1,
  unconfirmed_count: 0,
  failed_count: 0,
  created_at: '2026-07-17T00:00:00Z',
  published_at: '2026-07-17T00:00:01Z',
  completed_at: '2026-07-17T00:00:02Z',
  items: [{
    id: '0d15e762-38f9-4b9e-ab72-e4c40482816d',
    tag_id: 'AD100000048F',
    station_id: '90A9F7301427',
    status: 'CONFIRMED',
    publish_attempts: 1,
    published_at: '2026-07-17T00:00:01Z',
    confirmed_at: '2026-07-17T00:00:02Z',
    last_result_type: 254,
    correlation: 'HEURISTIC_STATE_MATCH',
    failure_code: null,
  }],
};

describe('LightControlPage command details', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.brokerStatus.mockResolvedValue({ service_state: 'RUNNING', mqtt_connected: true, subscriptions_ready: true });
    mocks.stationList.mockResolvedValue({ items: [], pagination: { page: 1, page_size: 100, total_items: 0, total_pages: 0 } });
    mocks.createCommand.mockResolvedValue(command);
    mocks.getCommand.mockResolvedValue(command);
  });

  it('renders only the per-tag item status returned by the command API', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    const view = render(
      <AntApp><QueryClientProvider client={client}><LightControlPage /></QueryClientProvider></AntApp>,
    );

    await waitFor(() => expect(screen.getByRole('button', { name: '发送亮灯命令' })).toBeEnabled());
    fireEvent.change(screen.getByLabelText('产品编码'), { target: { value: '1711A-ABA-PT' } });
    fireEvent.click(screen.getByRole('button', { name: '发送亮灯命令' }));

    expect(await screen.findByText('逐灯结果')).toBeInTheDocument();
    expect(screen.getByText('AD100000048F')).toBeInTheDocument();
    expect(screen.getByText('90A9F7301427')).toBeInTheDocument();
    expect(screen.getAllByText('已确认').length).toBeGreaterThan(0);
    expect(mocks.createCommand).toHaveBeenCalledWith({ action: 'LIGHT_ON', product_code: '1711A-ABA-PT', color: 'RED' });

    view.unmount();
  });
});
