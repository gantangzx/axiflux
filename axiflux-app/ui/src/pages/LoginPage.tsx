import { useState, type ReactNode } from 'react'
import {
  App as AntApp,
  Button,
  Form,
  Input,
  Tabs,
} from 'antd'
import { LockOutlined, MailOutlined, TeamOutlined, UserOutlined } from '@ant-design/icons'
import { useAuth } from '../auth'
import { AxifluxMark } from '../brand'
import { OC } from '../ui'

type LoginForm = { login: string; password: string }
type RegisterForm = { username: string; email: string; password: string; confirm: string }

/** Map known backend (English) reason strings to concise Chinese messages. */
const REASON_ZH: Array<[RegExp, string]> = [
  [/invalid login credentials/i, '用户名或密码错误'],
  [/login and password are required/i, '请输入用户名和密码'],
  [/local account login is not enabled/i, '当前未启用账号密码登录'],
  [/username already taken/i, '该用户名已被占用'],
  [/email already registered/i, '该邮箱已注册'],
  [/account suspended/i, '账号已被停用，请联系管理员'],
  [/password must be at least 8/i, '密码至少需要 8 位'],
]

function localize(reason: string): string {
  for (const [re, zh] of REASON_ZH) if (re.test(reason)) return zh
  return reason
}

/** Pull a human-readable detail out of the request wrapper's error message. */
function errorDetail(e: unknown): string {
  const msg = e instanceof Error ? e.message : String(e)
  // api errors look like: "POST /path -> 401 invalid login credentials"
  const afterStatus = msg.replace(/^\w+ \S+ -> \d+\s*/, '')
  const reason = afterStatus && afterStatus !== msg ? afterStatus : msg
  return localize(reason.trim())
}

function BrandHeader(): ReactNode {
  return (
    <div style={{ textAlign: 'center', marginBottom: 22 }}>
      <div style={{ display: 'flex', justifyContent: 'center', marginBottom: 14 }}>
        <AxifluxMark size={58} radius={0.28} />
      </div>
      <div style={{ fontSize: 21, fontWeight: 700, color: OC.textStrong, letterSpacing: 0.5 }}>
        AxiFlux · Axiflux
      </div>
      <div style={{ marginTop: 6, fontSize: 13, color: OC.muted }}>
        智能体编排中枢
      </div>
    </div>
  )
}

function LoginTab(): ReactNode {
  const { login } = useAuth()
  const { message } = AntApp.useApp()
  const [loading, setLoading] = useState(false)

  const onFinish = async (values: LoginForm) => {
    setLoading(true)
    try {
      await login(values.login.trim(), values.password)
    } catch (e) {
      message.error(errorDetail(e) || '登录失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <Form<LoginForm> layout="vertical" onFinish={onFinish} requiredMark={false} style={{ marginTop: 4 }}>
      <Form.Item
        name="login"
        label="用户名或邮箱"
        rules={[{ required: true, message: '请输入用户名或邮箱' }]}
      >
        <Input size="large" prefix={<UserOutlined />} placeholder="用户名 / 邮箱" autoComplete="username" />
      </Form.Item>
      <Form.Item
        name="password"
        label="密码"
        rules={[{ required: true, message: '请输入密码' }]}
      >
        <Input.Password size="large" prefix={<LockOutlined />} placeholder="密码" autoComplete="current-password" />
      </Form.Item>
      <Button type="primary" htmlType="submit" size="large" block loading={loading} style={{ marginTop: 6 }}>
        登录
      </Button>
    </Form>
  )
}

type SsoForm = { orgId: string }

function SsoTab(): ReactNode {
  const { message } = AntApp.useApp()
  const [loading, setLoading] = useState(false)

  const onFinish = (values: SsoForm) => {
    const orgId = values.orgId.trim()
    if (!orgId) {
      message.warning('请输入组织 ID')
      return
    }
    setLoading(true)
    window.location.assign('/api/v1/sso/start?orgId=' + encodeURIComponent(orgId))
  }

  return (
    <Form<SsoForm> layout="vertical" onFinish={onFinish} requiredMark={false} style={{ marginTop: 4 }}>
      <Form.Item
        name="orgId"
        label="组织 ID"
        rules={[{ required: true, message: '请输入组织 ID' }]}
        extra="企业成员使用统一身份（OIDC）登录，由管理员提供组织 ID。"
      >
        <Input size="large" prefix={<TeamOutlined />} placeholder="org_…" autoComplete="organization" />
      </Form.Item>
      <Button type="primary" htmlType="submit" size="large" block loading={loading} style={{ marginTop: 6 }}>
        使用企业账号登录
      </Button>
    </Form>
  )
}

function RegisterTab(): ReactNode {
  const { register, login } = useAuth()
  const { message } = AntApp.useApp()
  const [loading, setLoading] = useState(false)

  const onFinish = async (values: RegisterForm) => {
    setLoading(true)
    try {
      const res = await register(values.username.trim(), values.email.trim(), values.password)
      message.success('注册成功，已为你开通工作空间'
        + (res.organization?.trialEndsAt ? '与 14 天免费试用' : ''))
      await login(values.username.trim(), values.password)
    } catch (e) {
      message.error(errorDetail(e) || '注册失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <Form<RegisterForm> layout="vertical" onFinish={onFinish} requiredMark={false} style={{ marginTop: 4 }}>
      <Form.Item
        name="username"
        label="用户名"
        rules={[
          { required: true, message: '请输入用户名' },
          { pattern: /^[A-Za-z0-9._-]{3,64}$/, message: '3–64 位，仅限字母、数字、. _ -' },
        ]}
      >
        <Input size="large" prefix={<UserOutlined />} placeholder="用户名" autoComplete="username" />
      </Form.Item>
      <Form.Item
        name="email"
        label="邮箱（可选）"
        rules={[
          { type: 'email', message: '邮箱格式不正确' },
        ]}
      >
        <Input size="large" prefix={<MailOutlined />} placeholder="you@example.com" autoComplete="email" />
      </Form.Item>
      <Form.Item
        name="password"
        label="密码"
        rules={[
          { required: true, message: '请输入密码' },
          { min: 8, max: 128, message: '密码长度 8–128 位' },
        ]}
      >
        <Input.Password size="large" prefix={<LockOutlined />} placeholder="至少 8 位" autoComplete="new-password" />
      </Form.Item>
      <Form.Item
        name="confirm"
        label="确认密码"
        dependencies={['password']}
        rules={[
          { required: true, message: '请再次输入密码' },
          ({ getFieldValue }) => ({
            validator(_, value) {
              if (!value || getFieldValue('password') === value) return Promise.resolve()
              return Promise.reject(new Error('两次输入的密码不一致'))
            },
          }),
        ]}
      >
        <Input.Password size="large" prefix={<LockOutlined />} placeholder="再次输入密码" autoComplete="new-password" />
      </Form.Item>
      <Button type="primary" htmlType="submit" size="large" block loading={loading} style={{ marginTop: 6 }}>
        注册并开始免费试用
      </Button>
    </Form>
  )
}

export default function LoginPage() {
  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: OC.bg,
        padding: 24,
      }}
    >
      <div
        style={{
          width: '100%',
          maxWidth: 420,
          background: OC.card,
          border: `1px solid ${OC.border}`,
          borderRadius: OC.radiusLg,
          padding: '30px 28px 24px',
          boxShadow: 'var(--oc-shadow-pop)',
        }}
      >
        <BrandHeader />
        <Tabs
          centered
          defaultActiveKey="login"
          items={[
            { key: 'login', label: '登录', children: <LoginTab /> },
            { key: 'sso', label: '企业 SSO', children: <SsoTab /> },
            { key: 'register', label: '注册', children: <RegisterTab /> },
          ]}
        />
      </div>
    </div>
  )
}
