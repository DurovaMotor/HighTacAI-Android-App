import { createContext, useContext, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, clearCsrfToken } from '../api/client';
import { api } from '../api/endpoints';
import type { AdminUser } from '../api/types';

interface AuthContextValue {
  user: AdminUser | null;
  isLoading: boolean;
  login: (values: { username: string; password: string }) => Promise<AdminUser>;
  logout: () => Promise<void>;
  loginPending: boolean;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const me = useQuery({
    queryKey: ['auth', 'me'],
    queryFn: async () => {
      try {
        return await api.auth.me();
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) {
          clearCsrfToken();
          return null;
        }
        throw error;
      }
    },
    retry: false,
    staleTime: 60_000,
  });
  const loginMutation = useMutation({
    mutationFn: api.auth.login,
    onSuccess: (user) => queryClient.setQueryData(['auth', 'me'], user),
  });
  const logoutMutation = useMutation({
    mutationFn: api.auth.logout,
    onSuccess: () => {
      clearCsrfToken();
      queryClient.clear();
      queryClient.setQueryData(['auth', 'me'], null);
    },
  });

  const value: AuthContextValue = {
    user: me.data ?? null,
    isLoading: me.isLoading,
    login: loginMutation.mutateAsync,
    logout: logoutMutation.mutateAsync,
    loginPending: loginMutation.isPending,
  };
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth must be used within AuthProvider');
  return value;
}
