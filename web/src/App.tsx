import { lazy, Suspense } from 'react';
import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { Spin } from 'antd';
import { useAuth } from './auth/AuthProvider';
import { RealtimeProvider } from './realtime/RealtimeProvider';

const AppShell = lazy(() => import('./layout/AppShell'));
const LoginPage = lazy(() => import('./pages/LoginPage'));
const DashboardPage = lazy(() => import('./pages/DashboardPage'));
const BrokerPage = lazy(() => import('./pages/BrokerPage'));
const StationsPage = lazy(() => import('./pages/StationsPage'));
const TagsPage = lazy(() => import('./pages/TagsPage'));
const ProductsPage = lazy(() => import('./pages/ProductsPage'));
const BindingsPage = lazy(() => import('./pages/BindingsPage'));
const LightControlPage = lazy(() => import('./pages/LightControlPage'));
const OperationLogsPage = lazy(() => import('./pages/OperationLogsPage'));
const SettingsPage = lazy(() => import('./pages/SettingsPage'));

function FullPageLoading() {
  return <div className="full-page-loading"><Spin size="large" /><span>正在加载控制台</span></div>;
}

function ProtectedLayout() {
  const { user, isLoading } = useAuth();
  const location = useLocation();
  if (isLoading) return <FullPageLoading />;
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  if (user.must_change_password && location.pathname !== '/settings/security') {
    return <Navigate to="/settings/security" replace />;
  }
  return <RealtimeProvider><AppShell /></RealtimeProvider>;
}

export default function AppRoutes() {
  return (
    <Suspense fallback={<FullPageLoading />}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route element={<ProtectedLayout />}>
          <Route index element={<DashboardPage />} />
          <Route path="mqtt" element={<BrokerPage />} />
          <Route path="stations" element={<StationsPage />} />
          <Route path="tags/:view" element={<TagsPage />} />
          <Route path="tags" element={<Navigate to="/tags/all" replace />} />
          <Route path="products" element={<ProductsPage />} />
          <Route path="bindings" element={<BindingsPage />} />
          <Route path="light-control" element={<LightControlPage />} />
          <Route path="operation-logs" element={<OperationLogsPage />} />
          <Route path="settings/:section" element={<SettingsPage />} />
          <Route path="settings" element={<Navigate to="/settings/site" replace />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>
    </Suspense>
  );
}
