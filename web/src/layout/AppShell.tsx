import { useEffect, useMemo, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { App as AntApp, Avatar, Breadcrumb, Button, Drawer, Dropdown, Layout, Menu, Space, Tooltip, type MenuProps } from 'antd';
import {
  Activity, ArchiveRestore, Boxes, ChevronLeft, ChevronRight, CircleUserRound, ClipboardList,
  Gauge, Lightbulb, Logs, Menu as MenuIcon, RadioTower, RefreshCw, Server, Settings, ShieldCheck,
  Tags, Unplug, Wifi, WifiOff, X,
} from 'lucide-react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';
import { useRealtime } from '../realtime/RealtimeProvider';
import { StatusBadge } from '../components/common';

const { Header, Sider, Content } = Layout;

const navItems: MenuProps['items'] = [
  { key: '/', icon: <Gauge size={17} />, label: '仪表盘' },
  { key: '/mqtt', icon: <Server size={17} />, label: 'MQTT 服务' },
  { key: '/stations', icon: <RadioTower size={17} />, label: '基站管理' },
  {
    key: 'tag-group', icon: <Tags size={17} />, label: '灯条管理', children: [
      { key: '/tags/all', label: '全部灯条' },
      { key: '/tags/low-battery', label: '低电量' },
      { key: '/tags/abnormal', label: '异常灯条' },
    ],
  },
  {
    key: 'product-group', icon: <Boxes size={17} />, label: '产品与绑定', children: [
      { key: '/products', label: '产品' },
      { key: '/bindings', label: '绑定关系' },
    ],
  },
  { key: '/light-control', icon: <Lightbulb size={17} />, label: '灯光控制' },
  { key: '/operation-logs', icon: <ClipboardList size={17} />, label: '操作记录' },
  {
    key: 'settings-group', icon: <Settings size={17} />, label: '系统设置', children: [
      { key: '/settings/site', icon: <Activity size={15} />, label: '站点与网络' },
      { key: '/settings/backups', icon: <ArchiveRestore size={15} />, label: '备份与恢复' },
      { key: '/settings/security', icon: <ShieldCheck size={15} />, label: '管理员安全' },
    ],
  },
];

const routeMeta: Array<[string, string, string?]> = [
  ['/settings/security', '管理员安全', '系统设置'], ['/settings/backups', '备份与恢复', '系统设置'],
  ['/settings/site', '站点与网络', '系统设置'],
  ['/operation-logs', '操作记录'], ['/light-control', '灯光控制'], ['/bindings', '绑定关系', '产品与绑定'],
  ['/products', '产品', '产品与绑定'], ['/tags/low-battery', '低电量灯条', '灯条管理'],
  ['/tags/abnormal', '异常灯条', '灯条管理'], ['/tags', '全部灯条', '灯条管理'],
  ['/stations', '基站管理'], ['/mqtt', 'MQTT 服务'], ['/', '仪表盘'],
];

function selectedPath(pathname: string) {
  return routeMeta.find(([path]) => path === '/' ? pathname === '/' : pathname.startsWith(path))?.[0] || pathname;
}

function useMediaQuery(query: string) {
  const [matches, setMatches] = useState(() => window.matchMedia(query).matches);
  useEffect(() => {
    const media = window.matchMedia(query);
    const update = () => setMatches(media.matches);
    update();
    media.addEventListener('change', update);
    return () => media.removeEventListener('change', update);
  }, [query]);
  return matches;
}

export default function AppShell() {
  const desktop = useMediaQuery('(min-width: 769px)');
  const compactDesktop = useMediaQuery('(max-width: 1024px)');
  const [collapsed, setCollapsed] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const { message } = AntApp.useApp();
  const { user, logout } = useAuth();
  const realtime = useRealtime();
  const meta = routeMeta.find(([path]) => path === '/' ? location.pathname === '/' : location.pathname.startsWith(path)) || ['', 'HighTac'];
  const siderWidth = collapsed ? 64 : 232;

  useEffect(() => setCollapsed(compactDesktop), [compactDesktop]);

  const userMenu = useMemo<MenuProps['items']>(() => [
    { key: 'security', icon: <ShieldCheck size={15} />, label: '修改密码', onClick: () => navigate('/settings/security') },
    { type: 'divider' },
    {
      key: 'logout',
      icon: <Unplug size={15} />,
      label: '退出登录',
      danger: true,
      onClick: async () => {
        try {
          await logout();
          navigate('/login', { replace: true });
        } catch (error) {
          message.error(error instanceof Error ? error.message : '退出登录失败，请重试');
        }
      },
    },
  ], [logout, message, navigate]);

  const menu = (
    <Menu
      theme="dark"
      mode="inline"
      items={navItems}
      selectedKeys={[selectedPath(location.pathname)]}
      defaultOpenKeys={['tag-group', 'product-group', 'settings-group']}
      inlineCollapsed={desktop && collapsed}
      onClick={({ key }) => { if (key.startsWith('/')) navigate(key); setDrawerOpen(false); }}
    />
  );

  const realtimeLabel = realtime.state === 'connected' ? '实时连接正常' : realtime.state === 'offline' ? '网络离线' : '实时通道重连中';

  return (
    <Layout className="app-layout">
      {desktop ? (
        <Sider width={232} collapsedWidth={64} collapsed={collapsed} trigger={null} className="app-sider" style={{ width: siderWidth }}>
          <div className="brand-lockup" data-collapsed={collapsed}>
            <span className="brand-mark" aria-hidden="true" />
            {!collapsed && <span><strong>HighTac</strong><small>现场控制台</small></span>}
          </div>
          <nav aria-label="主导航">{menu}</nav>
          <Button
            className="sider-collapse"
            type="text"
            aria-label={collapsed ? '展开导航' : '收起导航'}
            icon={collapsed ? <ChevronRight size={17} /> : <ChevronLeft size={17} />}
            onClick={() => setCollapsed((value) => !value)}
          />
        </Sider>
      ) : (
        <Drawer
          open={drawerOpen}
          onClose={() => setDrawerOpen(false)}
          placement="left"
          width={280}
          closeIcon={<X size={18} />}
          title={<div className="drawer-brand"><span className="brand-mark" aria-hidden="true" /><strong>HighTac</strong></div>}
          className="mobile-nav-drawer"
        >
          {menu}
        </Drawer>
      )}
      <Layout className="app-main" style={desktop ? { marginLeft: siderWidth } : undefined}>
        <Header className="app-header" style={desktop ? { left: siderWidth } : undefined}>
          <div className="header-leading">
            {!desktop && <Button type="text" aria-label="打开导航" icon={<MenuIcon size={20} />} onClick={() => setDrawerOpen(true)} />}
            <div className="header-title"><span>{meta[1]}</span><small>HighTac Platform</small></div>
          </div>
          <Space size={8}>
            <Tooltip title={realtimeLabel}>
              <button
                type="button"
                className={`realtime-indicator realtime-${realtime.state}`}
                aria-label={realtime.state === 'connected' ? realtimeLabel : `${realtimeLabel}，点击重试`}
                onClick={realtime.state === 'connected' ? undefined : realtime.retry}
              >
                {realtime.state === 'connected' ? <Wifi size={15} /> : <WifiOff size={15} />}
                <span className="realtime-text">{realtime.state === 'connected' ? '实时' : '重连'}</span>
              </button>
            </Tooltip>
            <Tooltip title="刷新当前数据">
              <Button aria-label="刷新当前数据" type="text" icon={<RefreshCw size={17} />} onClick={() => queryClient.invalidateQueries({ type: 'active' })} />
            </Tooltip>
            <Dropdown menu={{ items: userMenu }} placement="bottomRight" trigger={['click']}>
              <Button type="text" className="user-button" aria-label={`打开用户菜单，当前用户 ${user?.username || 'Adam'}`}>
                <Avatar size={28} icon={<CircleUserRound size={18} />} />
                {desktop && <span>{user?.username || 'Adam'}</span>}
              </Button>
            </Dropdown>
          </Space>
        </Header>
        <Content className="app-content">
          <Breadcrumb className="app-breadcrumb" items={[{ title: '控制台' }, ...(meta[2] ? [{ title: meta[2] }] : []), { title: meta[1] }]} />
          {user?.must_change_password && location.pathname !== '/settings/security' && (
            <div className="password-warning"><ShieldCheck size={17} /><span>初始密码仍在使用，请立即修改管理员密码。</span><Button size="small" onClick={() => navigate('/settings/security')}>立即修改</Button></div>
          )}
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}
