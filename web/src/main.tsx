import '@ant-design/v5-patch-for-react-19';
import React from 'react';
import ReactDOM from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntApp, ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { BrowserRouter } from 'react-router-dom';
import { ApiError } from './api/client';
import { AuthProvider } from './auth/AuthProvider';
import AppRoutes from './App';
import './styles.css';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 15_000,
      refetchOnWindowFocus: false,
      retry: (failureCount, error) => !(error instanceof ApiError && error.status >= 400 && error.status < 500) && failureCount < 2,
    },
    mutations: { retry: false },
  },
});

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider
      locale={zhCN}
      theme={{
        token: {
          colorPrimary: '#76b900',
          colorLink: '#0046a4',
          colorSuccess: '#3f8500',
          colorWarning: '#df6500',
          colorError: '#e52020',
          colorInfo: '#007c91',
          colorText: '#1a1a1a',
          colorTextSecondary: '#757575',
          colorBorder: '#cccccc',
          colorBgLayout: '#f7f7f7',
          borderRadius: 2,
          borderRadiusLG: 2,
          fontFamily: 'Inter, "Noto Sans SC", "Microsoft YaHei", Arial, sans-serif',
          fontSize: 14,
          boxShadow: 'none',
          boxShadowSecondary: 'none',
          controlHeight: 40,
        },
        components: {
          Button: { fontWeight: 700, primaryShadow: 'none', defaultShadow: 'none', dangerShadow: 'none' },
          Card: { boxShadow: 'none' },
          Layout: { siderBg: '#000000', headerBg: '#ffffff' },
          Menu: { darkItemBg: '#000000', darkSubMenuItemBg: '#000000', darkItemSelectedBg: '#1a1a1a', darkItemSelectedColor: '#ffffff', itemBorderRadius: 2 },
          Table: { headerBg: '#f7f7f7', headerColor: '#1a1a1a', borderColor: '#d9d9d9', rowHoverBg: '#f7f7f7', cellPaddingBlockSM: 9, cellPaddingInlineSM: 12 },
          Modal: { boxShadow: 'none' },
        },
      }}
    >
      <AntApp>
        <QueryClientProvider client={queryClient}>
          <AuthProvider>
            <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}><AppRoutes /></BrowserRouter>
          </AuthProvider>
        </QueryClientProvider>
      </AntApp>
    </ConfigProvider>
  </React.StrictMode>,
);
