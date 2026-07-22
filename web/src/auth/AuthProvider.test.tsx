import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import { api } from '../api/endpoints';
import { AuthProvider, useAuth } from './AuthProvider';

function AuthState() {
  const { user, isLoading } = useAuth();
  if (isLoading) return <span>loading</span>;
  return <span>{user ? user.username : 'anonymous'}</span>;
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe('AuthProvider', () => {
  it('treats the expected anonymous /auth/me 401 as a normal logged-out state', async () => {
    vi.spyOn(api.auth, 'me').mockRejectedValue(new ApiError('未登录', 401, 'UNAUTHENTICATED'));
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    render(
      <QueryClientProvider client={queryClient}>
        <AuthProvider><AuthState /></AuthProvider>
      </QueryClientProvider>,
    );

    expect(await screen.findByText('anonymous')).toBeInTheDocument();
    expect(queryClient.getQueryState(['auth', 'me'])?.status).toBe('success');
    expect(queryClient.getQueryData(['auth', 'me'])).toBeNull();
  });
});
