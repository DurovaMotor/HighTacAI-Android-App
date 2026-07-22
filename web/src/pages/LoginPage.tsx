import { App, Button, Form, Input } from 'antd';
import { KeyRound, LockKeyhole, UserRound } from 'lucide-react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';

export default function LoginPage() {
  const { user, login, loginPending } = useAuth();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const location = useLocation();
  const destination = (location.state as { from?: string } | null)?.from || '/';

  if (user) return <Navigate to={user.must_change_password ? '/settings/security' : '/'} replace />;

  const submit = async (values: { username: string; password: string }) => {
    try {
      const authenticated = await login(values);
      navigate(authenticated.must_change_password ? '/settings/security' : destination, { replace: true });
    } catch (error) {
      message.error(error instanceof Error ? error.message : '用户名或密码错误');
    }
  };

  return (
    <main className="login-page">
      <header className="login-header">
        <div className="brand-lockup">
          <span className="brand-mark" aria-hidden="true" />
          <span><strong>HighTac</strong><small>现场控制台</small></span>
        </div>
        <span>局域网运营管理</span>
      </header>
      <section className="login-workspace" aria-labelledby="login-title">
        <div className="login-panel">
          <div className="login-accent" />
          <KeyRound size={28} />
          <h1 id="login-title">管理员登录</h1>
          <p>使用现场平台管理员凭据进入控制台</p>
          <Form layout="vertical" requiredMark={false} initialValues={{ username: 'Adam' }} onFinish={submit} size="large">
            <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
              <Input prefix={<UserRound size={17} />} autoComplete="username" placeholder="Adam" />
            </Form.Item>
            <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }]}>
              <Input.Password prefix={<LockKeyhole size={17} />} autoComplete="current-password" placeholder="请输入密码" />
            </Form.Item>
            <Button type="primary" htmlType="submit" block loading={loginPending}>登录</Button>
          </Form>
        </div>
      </section>
      <footer className="login-footer">HighTac Platform · 仅限授权现场网络</footer>
    </main>
  );
}
