import { Card, Descriptions, Spin, Tag } from 'antd'
import { IdcardOutlined, DesktopOutlined, LockOutlined } from '@ant-design/icons'
import CenterShell, { type CenterTab } from '../components/CenterShell'
import AccountProfileSection, { type Profile } from './AccountProfileSection'
import { useApi, mono } from '../ui'
import { api } from '../api'
import { OC } from '../theme'
import { useAuth } from '../auth'

/**
 * 个人中心 —— standalone route (top-level key `profile`), reached from the
 * first item of the top-right account dropdown.
 *
 * It is intentionally NOT nested under Settings: account self-management
 * (identity, display name, password) is core functionality that every user,
 * including single-user/embedded deployments, expects from the account menu.
 *
 * The page groups related sections via {@link CenterShell} tabs so future
 * sections (preferences, notifications, linked identities …) can be added by
 * appending a tab — no routing changes.
 */
export default function ProfileCenterPage() {
  const { status, user } = useAuth()
  const { data, loading, error, reload } = useApi<Profile>(
    () => api.get<Profile>('/api/v1/me/profile'),
    [],
  )

  if (loading) {
    return (
      <div style={{ padding: '18vh 0', textAlign: 'center', color: OC.muted }}>
        <Spin />
      </div>
    )
  }

  // Single-user / embedded open-source mode: the placeholder identity has no
  // stored account. Show local workspace info instead of an empty page.
  if (error || !data) {
    return (
      <CenterShell
        icon={<IdcardOutlined />}
        title="个人中心"
        description="当前为本地嵌入式模式，无需登录即可使用全部开源能力。"
      >
        <LocalIdentityCard status={status} username={user?.username} />
      </CenterShell>
    )
  }

  const localTab: CenterTab = {
    key: 'account',
    label: '账号资料',
    icon: <IdcardOutlined />,
    content: <AccountProfileSection profile={data} reload={reload} />,
  }

  // Extension point: append more CenterTabs (e.g. 偏好设置 / 通知 / 绑定账号)
  // here. They appear in the header tab strip automatically.
  const tabs: CenterTab[] = [localTab]

  return (
    <CenterShell
      icon={<IdcardOutlined />}
      title={data.displayName || data.username}
      description={`@${data.username} · 管理你的身份信息与账号安全`}
      tabs={tabs}
      defaultKey="account"
    />
  )
}

function LocalIdentityCard({
  status,
  username,
}: {
  status: string
  username?: string | null
}) {
  return (
    <Card
      variant="borderless"
      style={{ background: OC.card }}
      styles={{ header: { borderBottom: `1px solid ${OC.border}`, color: OC.textStrong } }}
      title="本地工作区身份"
    >
      <div
        style={{
          display: 'flex',
          gap: 8,
          marginBottom: 16,
          flexWrap: 'wrap',
        }}
      >
        <Tag icon={<DesktopOutlined />} color="default">
          本地嵌入式模式
        </Tag>
        <Tag icon={<LockOutlined />} color="success">
          无需登录
        </Tag>
      </div>
      <Descriptions column={1} size="small" styles={{ label: { color: OC.muted } }}>
        <Descriptions.Item label="当前身份">
          <span style={{ ...mono, color: OC.textStrong }}>
            {username || '本地用户'}
          </span>
        </Descriptions.Item>
        <Descriptions.Item label="鉴权状态">
          <span style={{ ...mono, color: OC.textStrong }}>{status}</span>
        </Descriptions.Item>
        <Descriptions.Item label="说明">
          <span style={{ color: OC.text }}>
            会话默认存储在进程内存；启用后端登录后，此处可管理显示名、邮箱与密码。
          </span>
        </Descriptions.Item>
      </Descriptions>
    </Card>
  )
}
