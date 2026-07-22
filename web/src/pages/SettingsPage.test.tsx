import { App as AntApp } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import SettingsPage from './SettingsPage';

const mocks = vi.hoisted(() => ({
  site: vi.fn(),
  network: vi.fn(),
  updateSite: vi.fn(),
  updateNetwork: vi.fn(),
  changePassword: vi.fn(),
  useAuth: vi.fn(),
}));

vi.mock('../api/endpoints', () => ({
  api: {
    settings: {
      site: mocks.site,
      network: mocks.network,
      updateSite: mocks.updateSite,
      updateNetwork: mocks.updateNetwork,
    },
    auth: { changePassword: mocks.changePassword },
  },
}));

vi.mock('../auth/AuthProvider', () => ({ useAuth: mocks.useAuth }));

const siteSettings = {
  id: '0d15e762-38f9-4b9e-ab72-e4c40482816d',
  name: 'HighTac 主仓库',
  address: 'A 区一层',
  notes: '单站点生产环境',
  low_battery_threshold: 30,
  command_timeout_seconds: 10,
  updated_at: '2026-07-16T00:00:00Z',
};

const networkSettings = {
  api_bind_address: '0.0.0.0',
  api_port: 8088,
  mqtt_host: '192.168.1.105',
  mqtt_port: 1884,
  mqtt_tls_enabled: false,
  restart_required: false,
  updated_at: '2026-07-16T00:00:00Z',
};

function renderSettingsPage(path = '/settings/site') {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  const user = mocks.useAuth()?.user;
  if (user) client.setQueryData(['auth', 'me'], user);
  const view = render(
    <AntApp>
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[path]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
          <Routes>
            <Route path="/settings/:section" element={<SettingsPage />} />
            <Route path="/" element={<h1>仪表盘</h1>} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </AntApp>,
  );
  return { ...view, client };
}

async function unmountAndFlush(view: ReturnType<typeof render>) {
  view.unmount();
  await new Promise<void>((resolve) => setImmediate(resolve));
}

describe('SettingsPage site and network settings', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.site.mockResolvedValue(siteSettings);
    mocks.network.mockResolvedValue(networkSettings);
    mocks.updateSite.mockResolvedValue(siteSettings);
    mocks.changePassword.mockResolvedValue(undefined);
    mocks.useAuth.mockReturnValue({
      user: {
        id: 'b9c79a62-3b52-49e6-88d8-4d2ed4577ed2',
        username: 'Adam',
        must_change_password: false,
        is_active: true,
        created_at: '2026-07-16T00:00:00Z',
        last_login_at: '2026-07-17T00:00:00Z',
      },
    });
  });

  it('shows effective deployment-managed network values without editable controls', async () => {
    const view = renderSettingsPage();

    expect(await screen.findByText('当前版本仅支持局域网明文连接')).toBeInTheDocument();
    expect(screen.getByText(/调整配置并重启平台后才会变化/)).toBeInTheDocument();

    const expectedValues = [
      ['API 监听地址（当前值）', '0.0.0.0'],
      ['API 端口（当前值）', '8088'],
      ['MQTT 主机（当前值）', '192.168.1.105'],
      ['MQTT 端口（当前值）', '1884'],
      ['API 传输（当前值）', 'HTTP / WebSocket（明文）'],
      ['MQTT TLS（当前值）', '未启用（明文 MQTT）'],
    ];
    for (const [label, value] of expectedValues) {
      expect(screen.getByLabelText(label)).toHaveValue(value);
      expect(screen.getByLabelText(label)).toHaveAttribute('readonly');
    }

    expect(screen.queryByRole('switch')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '保存网络设置' })).not.toBeInTheDocument();
    expect(mocks.updateNetwork).not.toHaveBeenCalled();
    await unmountAndFlush(view);
  });

  it('keeps site settings editable and submits them through the live endpoint', async () => {
    mocks.updateSite.mockImplementation(() => new Promise(() => {}));
    const view = renderSettingsPage();

    const name = await screen.findByLabelText('站点名称');
    expect(name).not.toHaveAttribute('readonly');
    fireEvent.change(name, { target: { value: 'HighTac 新站点' } });
    fireEvent.click(screen.getByRole('button', { name: /保存站点设置/ }));

    await waitFor(() => expect(mocks.updateSite).toHaveBeenCalledTimes(1));
    expect(mocks.updateSite.mock.calls[0][0]).toEqual(expect.objectContaining({
      name: 'HighTac 新站点',
      low_battery_threshold: 30,
    }));
    await unmountAndFlush(view);
  });

  it('updates the auth cache and releases the first-password gate immediately', async () => {
    const forcedUser = {
      id: 'b9c79a62-3b52-49e6-88d8-4d2ed4577ed2',
      username: 'Adam',
      must_change_password: true,
      is_active: true,
      created_at: '2026-07-16T00:00:00Z',
      last_login_at: '2026-07-17T00:00:00Z',
    };
    mocks.useAuth.mockReturnValue({
      user: forcedUser,
    });
    mocks.changePassword.mockImplementation(async () => {
      forcedUser.must_change_password = false;
    });
    const view = renderSettingsPage('/settings/security');

    fireEvent.change(await screen.findByLabelText('当前密码'), { target: { value: 'current-password' } });
    fireEvent.change(screen.getByLabelText('新密码', { exact: true }), { target: { value: 'HighTacAdmin2026' } });
    fireEvent.change(screen.getByLabelText('确认新密码'), { target: { value: 'HighTacAdmin2026' } });
    fireEvent.click(screen.getByRole('button', { name: '修改密码' }));

    await waitFor(() => expect(mocks.changePassword).toHaveBeenCalledWith({ current_password: 'current-password', new_password: 'HighTacAdmin2026' }));
    expect(await screen.findByRole('heading', { name: '仪表盘' })).toBeInTheDocument();
    expect(view.client.getQueryData(['auth', 'me'])).toMatchObject({ must_change_password: false });
    await unmountAndFlush(view);
  });
});
