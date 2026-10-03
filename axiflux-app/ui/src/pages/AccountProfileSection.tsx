import { useState } from 'react'
import { Button, Card, Form, Input, Tag, App } from 'antd'
import { api, getAuthUser, saveAuth } from '../api'
import { OC } from '../theme'

export type Profile = {
  userId: string
  username: string
  email?: string | null
  displayName?: string | null
  status?: string
  platformRole?: string
  managedExternally?: boolean
}

/** Deterministic colour from a string so the letter avatar is stable per user. */
function avatarColor(seed: string): string {
  let h = 0
  for (let i = 0; i < seed.length; i++) h = (h * 31 + seed.charCodeAt(i)) >>> 0
  return `hsl(${h % 360} 62% 45%)`
}

function LetterAvatar({ name }: { name: string }) {
  const letter = (name || '?').trim().charAt(0).toUpperCase()
  return (
    <div
      aria-hidden
      style={{
        width: 56,
        height: 56,
        borderRadius: '50%',
        background: avatarColor(name || '?'),
        color: '#fff',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        fontSize: 24,
        fontWeight: 700,
        flex: '0 0 auto',
      }}
    >
      {letter}
    </div>
  )
}

/**
 * Presentational account section: shows identity and the profile / password
 * forms. Data is supplied by the owning center page (ProfileCenterPage), which
 * performs the single {@code /api/v1/me/profile} fetch. This keeps the section
 * reusable and avoids a second request when rendered inside a center page.
 */
export default function AccountProfileSection({
  profile,
  reload,
}: {
  profile: Profile
  reload: () => void
}) {
  const { message } = App.useApp()
  const [profileForm] = Form.useForm()
  const [pwdForm] = Form.useForm()
  const [savingProfile, setSavingProfile] = useState(false)
  const [savingPwd, setSavingPwd] = useState(false)

  const readOnly = !!profile.managedExternally
  const displayName = profile.displayName || profile.username

  const submitProfile = async (values: { displayName?: string; email?: string }) => {
    setSavingProfile(true)
    try {
      const updated = await api.post<Profile>('/api/v1/me/profile', {
        displayName: values.displayName ?? '',
        email: values.email ?? '',
      })
      // Keep the cached identity (used by the shell/topbar) in sync.
      const cached = getAuthUser()
      if (cached)
        saveAuth(localStorage.getItem('oc.auth.token') || '', {
          ...cached,
          displayName: updated.displayName,
          email: updated.email,
        })
      profileForm.setFieldsValue({ displayName: updated.displayName, email: updated.email })
      reload()
      message.success('资料已更新')
    } catch (e) {
      message.error(e instanceof Error ? e.message : '更新失败')
    } finally {
      setSavingProfile(false)
    }
  }

  const submitPassword = async (values: {
    currentPassword?: string
    newPassword?: string
  }) => {
    setSavingPwd(true)
    try {
      await api.post('/api/v1/me/password', {
        currentPassword: values.currentPassword,
        newPassword: values.newPassword,
      })
      pwdForm.resetFields()
      message.success('密码已修改')
    } catch (e) {
      message.error(e instanceof Error ? e.message : '修改失败')
    } finally {
      setSavingPwd(false)
    }
  }

  return (
    <Card
      title="账号资料"
      variant="borderless"
      style={{ background: OC.card, marginBottom: 16 }}
      styles={{ header: { borderBottom: `1px solid ${OC.border}`, color: OC.textStrong } }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginBottom: 20 }}>
        <LetterAvatar name={displayName} />
        <div style={{ minWidth: 0 }}>
          <div style={{ fontSize: 16, fontWeight: 700, color: OC.textStrong }}>
            {displayName}
          </div>
          <div style={{ color: OC.muted, fontSize: 13 }}>@{profile.username}</div>
          <div style={{ marginTop: 6, display: 'flex', gap: 6, flexWrap: 'wrap' }}>
            {profile.platformRole && <Tag color="default">{profile.platformRole}</Tag>}
            {profile.status && (
              <Tag color={profile.status === 'active' ? 'success' : 'warning'}>
                {profile.status === 'active' ? '正常' : profile.status}
              </Tag>
            )}
            {readOnly && <Tag color="blue">外部身份统一管理</Tag>}
          </div>
        </div>
      </div>

      {readOnly && (
        <div
          style={{
            fontSize: 12.5,
            color: OC.muted,
            background: 'var(--oc-accent-soft)',
            borderRadius: OC.radiusSm,
            padding: '8px 12px',
            marginBottom: 16,
          }}
        >
          该账号由外部身份提供方（SSO）管理，显示名、邮箱与密码请在对应系统中修改。
        </div>
      )}

      {!readOnly && (
        <>
          <Form
            form={profileForm}
            layout="vertical"
            initialValues={{ displayName: profile.displayName, email: profile.email }}
            onFinish={submitProfile}
          >
            <Form.Item
              name="displayName"
              label={<span style={{ color: OC.text }}>显示名</span>}
              rules={[{ required: true, message: '请输入显示名' }]}
            >
              <Input maxLength={255} placeholder="在界面中展示的名字" />
            </Form.Item>
            <Form.Item
              name="email"
              label={<span style={{ color: OC.text }}>邮箱</span>}
              rules={[{ type: 'email', message: '邮箱格式不正确' }]}
            >
              <Input placeholder="可选，用于登录与通知" allowClear />
            </Form.Item>
            <Form.Item style={{ marginBottom: 24 }}>
              <Button type="primary" htmlType="submit" loading={savingProfile}>
                保存资料
              </Button>
            </Form.Item>
          </Form>

          <Form
            form={pwdForm}
            layout="vertical"
            onFinish={submitPassword}
            style={{ borderTop: `1px solid ${OC.border}`, paddingTop: 20 }}
          >
            <Form.Item
              name="currentPassword"
              label={<span style={{ color: OC.text }}>当前密码</span>}
              rules={[{ required: true, message: '请输入当前密码' }]}
            >
              <Input.Password autoComplete="current-password" />
            </Form.Item>
            <Form.Item
              name="newPassword"
              label={<span style={{ color: OC.text }}>新密码</span>}
              rules={[
                { required: true, message: '请输入新密码' },
                { min: 8, message: '至少 8 个字符' },
              ]}
            >
              <Input.Password autoComplete="new-password" />
            </Form.Item>
            <Form.Item
              name="confirm"
              label={<span style={{ color: OC.text }}>确认新密码</span>}
              dependencies={['newPassword']}
              rules={[
                { required: true, message: '请再次输入新密码' },
                ({ getFieldValue }) => ({
                  validator(_, value) {
                    if (!value || getFieldValue('newPassword') === value) return Promise.resolve()
                    return Promise.reject(new Error('两次输入的密码不一致'))
                  },
                }),
              ]}
            >
              <Input.Password autoComplete="new-password" />
            </Form.Item>
            <Form.Item style={{ marginBottom: 0 }}>
              <Button type="primary" htmlType="submit" loading={savingPwd}>
                修改密码
              </Button>
            </Form.Item>
          </Form>
        </>
      )}
    </Card>
  )
}
