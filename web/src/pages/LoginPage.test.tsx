import { App as AntApp } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../auth/AuthProvider';
import LoginPage from './LoginPage';

const mocks = vi.hoisted(() => ({
  me: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
}));

vi.mock('../api/endpoints', () => ({
  api: {
    auth: {
      me: mocks.me,
      login: mocks.login,
      logout: mocks.logout,
    },
  },
}));

describe('LoginPage', () => {
  beforeEach(() => {
    mocks.me.mockRejectedValue(new Error('未登录'));
    mocks.login.mockResolvedValue({ id: 'admin-adam', username: 'Adam', must_change_password: false });
    mocks.logout.mockResolvedValue(undefined);
  });

  it('submits the Adam credentials through the auth provider', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    render(
      <AntApp>
        <QueryClientProvider client={client}>
          <AuthProvider><MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}><LoginPage /></MemoryRouter></AuthProvider>
        </QueryClientProvider>
      </AntApp>,
    );
    expect(screen.getByLabelText('用户名')).toHaveValue('Adam');
    fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'Adam' } });
    fireEvent.click(screen.getByRole('button', { name: /登\s*录/ }));
    await waitFor(() => expect(mocks.login).toHaveBeenCalled());
    expect(mocks.login.mock.calls[0][0]).toEqual({ username: 'Adam', password: 'Adam' });
  });
});
