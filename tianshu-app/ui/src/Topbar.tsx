import { useEffect, useState } from 'react'
import { Badge, Dropdown, type MenuProps } from 'antd'
import {
  CrownOutlined,
  CreditCardOutlined,
  TeamOutlined,
  SettingOutlined,
  InfoCircleOutlined,
  UserOutlined,
  PlusOutlined,
  IdcardOutlined,
} from '@ant-design/icons'
import { OC, SidebarToggle } from './ui'
import { getToken } from './api'
import { TianshuMark } from './brand'
import { useAuth } from './auth'
import { App as AntApp } from 'antd'
import { LogoutOutlined } from '@ant-design/icons'

type Me = {
  userId?: string
  orgId?: string | null
  planTier?: string
  billingEnabled?: boolean
}

type OrgRow = { orgId?: string; name?: string; planTier?: string }

const PLAN_LABEL: Record<string, string> = { free: 'Free', pro: 'Pro', team: 'Team' }
const PLAN_TINT: Record<string, string> = {
  free: 'var(--oc-muted)',
  pro: OC.accent,
  team: OC.accent,
}

/**
 * 顶栏：常驻页首，提供"折叠侧栏 / 当前页标题 / 全局搜索 / 新对话 / 收件箱 / 套餐 / 用户"等常驻入口。
 * 「新对话」是全局唯一入口（侧栏 CTA 与聊天页页头已合并到这里）。
 * 套餐徽章和用户菜单里展示的 org 从 /api/v1/billing/me 与 /api/v1/orgs/mine 拉取。
 */
export function Topbar({
  collapsed,
  onToggle,
  page,
  onNavigate,
  title,
  subtitle,
  onOpenCommand,
  onNewChat,
  inbox,
}: {
  collapsed: boolean
  onToggle: () => void
  page: string
  onNavigate: (k: string) => void
  title: string
  subtitle?: string
  onOpenCommand: () => void
  onNewChat: () => void
  inbox: React.ReactNode
}) {
  const [me, setMe] = useState<Me>({})
  const [org, setOrg] = useState<OrgRow | null>(null)

  useEffect(() => {
    let alive = true
    const headers: Record<string, string> = { Accept: 'application/json' }
    const token = getToken()
    if (token) headers.Authorization = `Bearer ${token}`
    // billingEnabled=false 也能读：me 返回 200 + planTier=free；fetch 自然成功。
    fetch('/api/v1/billing/me', { headers })
      .then((r) => (r.ok ? r.json() : null))
      .then((j) => {
        if (!alive || !j || !j.success) return
        setMe(j.data as Me)
      })
      .catch(() => {
        /* 401 等不致命：本地工作区降级显示 Free */
      })
    fetch('/api/v1/orgs/mine', { headers })
      .then((r) => (r.ok ? r.json() : null))
      .then((j) => {
        if (!alive || !j || !j.success) return
        const arr = Array.isArray(j.data) ? (j.data as OrgRow[]) : []
        // 当前 org 优先；否则第一个
        const cur = arr.find((o) => me.orgId && o.orgId === me.orgId) ?? arr[0]
        if (cur) setOrg(cur)
      })
      .catch(() => {
        /* 单机部署无 /orgs/mine，忽略 */
      })
    return () => {
      alive = false
    }
  }, [me.orgId])

  const tier = (me.planTier || 'free').toLowerCase()
  const tierLabel = PLAN_LABEL[tier] ?? 'Free'

  return (
    <header className="oc-topbar">
      <div className="oc-topbar__toggle">
        <SidebarToggle onClick={onToggle} title={collapsed ? '展开侧栏' : '折叠侧栏'} />
      </div>
      <div className="oc-topbar__center">
        <div className="oc-topbar__crumbs" aria-label="面包屑">
          <span className="oc-topbar__crumb">{title}</span>
          {subtitle && <span className="oc-topbar__sub">{subtitle}</span>}
        </div>
        <button
          type="button"
          className="oc-topbar__cmd"
          onClick={onOpenCommand}
          title="搜索 (Ctrl+K)"
        >
          <span className="oc-topbar__cmd-icon">⌘K</span>
          <span className="oc-topbar__cmd-text">搜索页面、会话或智能体…</span>
        </button>
        <div className="oc-topbar__right">
          <button
            type="button"
            className="oc-topbar__new oc-topbar__new--icon"
            onClick={onNewChat}
            title="开始新对话 (Ctrl+N)"
            aria-label="新对话"
            aria-keyshortcuts="Control+N"
          >
            <PlusOutlined />
          </button>
          <span className="oc-topbar__divider" />
          {inbox}
          <PlanBadge tier={tier} label={tierLabel} onClick={() => onNavigate('business')} />
          <AccountMenu
            onNavigate={onNavigate}
            me={me}
            org={org}
            tierLabel={tierLabel}
          />
        </div>
      </div>
    </header>
  )
}

function PlanBadge({
  tier,
  label,
  onClick,
}: {
  tier: string
  label: string
  onClick: () => void
}) {
  const tint = PLAN_TINT[tier] ?? PLAN_TINT.free
  return (
    <button
      type="button"
      className={`oc-plan-badge oc-plan-badge--${tier}`}
      onClick={onClick}
      title={`当前套餐：${label} · 点此管理订阅`}
    >
      <CrownOutlined style={{ color: tint, fontSize: 12 }} />
      <span className="oc-plan-badge__label">{label}</span>
    </button>
  )
}

function AccountMenu({
  onNavigate,
  me,
  org,
  tierLabel,
}: {
  onNavigate: (k: string) => void
  me: Me
  org: OrgRow | null
  tierLabel: string
}) {
  const { modal } = AntApp.useApp()
  const { user, status, logout } = useAuth()
  const isAuthed = status === 'authed' && !!user
  const menu: MenuProps = {
    items: [
      // First item: the standalone 个人中心 page (works in every deployment).
      { key: 'profile', icon: <IdcardOutlined />, label: '个人中心' },
      ...(isAuthed
        ? [{ key: 'whoami', icon: <UserOutlined />, label: user!.username, disabled: true }]
        : []),
      { key: 'organization', icon: <TeamOutlined />, label: org?.name || '我的工作空间' },
      { type: 'divider' },
      { key: 'business', icon: <CreditCardOutlined />, label: `套餐与计费 · ${tierLabel}` },
      { key: 'settings', icon: <SettingOutlined />, label: '设置' },
      { type: 'divider' },
      { key: 'about', icon: <InfoCircleOutlined />, label: '关于天枢' },
      ...(isAuthed
        ? [{ type: 'divider' as const }, { key: 'logout', icon: <LogoutOutlined />, label: '退出登录', danger: true }]
        : []),
    ],
    onClick: ({ key }) => {
      if (key === 'about') {
        modal.info({
          title: '关于天枢',
          width: 420,
          okText: '知道了',
          icon: <TianshuMark size={28} />,
          content: (
            <div style={{ fontSize: 13, lineHeight: 1.9, color: OC.text }}>
              <div>
                <b>天枢 TIANSHU</b> —— 北斗第一星，众星之枢；智能体编排中枢。
              </div>
              <div>
                {isAuthed
                  ? <>当前以 <b>{user!.username}</b> 登录，接口调用经自助令牌鉴权。</>
                  : <>当前为 <b>本地嵌入式模式</b>（无登录，接口调用走 OBO token）。</>}
              </div>
              <div style={{ color: OC.muted, marginTop: 8 }}>版本 v0.1.0 · 本地工作区</div>
            </div>
          ),
        })
      } else if (key === 'logout') {
        logout()
      } else if (typeof key === 'string') {
        onNavigate(key)
      }
    },
  }
  return (
    <Dropdown menu={menu} trigger={['click']} placement="bottomRight">
      <button className="oc-account" title={user?.username || me.userId || '账户'}>
        <span className="oc-account__avatar">
          <UserOutlined />
        </span>
        <span className="oc-account__text">
          <span className="oc-account__name">
            {user?.username || org?.name || me.userId || '本地用户'}
          </span>
          <span className="oc-account__sub">{tierLabel} · {me.orgId || isAuthed ? '组织' : '本地工作区'}</span>
        </span>
      </button>
    </Dropdown>
  )
}