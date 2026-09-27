import {
  Layout,
  Dropdown,
  Badge,
  Popover,
  Tabs,
  Spin,
  Input,
  Button,
  App as AntApp,
  type MenuProps,
} from 'antd'
import { useEffect, useMemo, useState } from 'react'
import {
  RightOutlined,
  CheckOutlined,
  DownOutlined,
  MoreOutlined,
  CopyOutlined,
  EditOutlined,
  PushpinOutlined,
  PushpinFilled,
  InboxOutlined,
  DeleteOutlined,
  AuditOutlined,
  TeamOutlined,
  ClockCircleOutlined,
  BranchesOutlined,
  SearchOutlined,
  ReloadOutlined,
  CloseCircleFilled,
  PlusOutlined,
} from '@ant-design/icons'
import { useChat, sessionLabel, type AgentInfo, type SessionSummary } from './chat'
import { useAuth } from './auth'
import { TianshuMark } from './brand'
import { OC } from './ui'
import { useInbox, type Notice } from './inbox'
import { NAV_SECTIONS, sectionMeta, sectionOfPage } from './nav'

const { Sider } = Layout

/** 图标轨宽度：一级导航常驻，折叠后也保留。 */
const RAIL_W = 56
/** 上下文面板宽度：随折叠显隐，内容由当前分区决定。 */
const PANEL_W = 232

// ===================== 图标轨（一级导航）=====================

/**
 * 只放分区图标的 56px 常驻轨道，对标 VS Code / Cursor 的 Activity Bar。
 * 文字一律走 tooltip，避免侧栏出现「分组标题 + 条目」层层嵌套的视觉噪声。
 */
function NavRail({
  section,
  page,
  unread,
  onSelect,
}: {
  section: string
  page: string
  unread: number
  onSelect: (key: string) => void
}) {
  return (
    <nav className="oc-rail" aria-label="主导航">
      <button
        type="button"
        className="oc-rail__brand"
        title="天枢 · 智能体平台"
        onClick={() => onSelect(sectionOfPage('overview'))}
      >
        <TianshuMark size={30} />
      </button>
      <span className="oc-rail__sep" />
      {NAV_SECTIONS.map((s) => {
        const active = section === s.key
        // 治理分区承载审批红点：不在收件箱页时用环境点提示。
        const dot = s.key === 'governance' && unread > 0 && page !== 'approvals'
        return (
          <button
            type="button"
            key={s.key}
            className={`oc-rail__item ${active ? 'is-active' : ''}`}
            title={`${s.label} · ${s.hint}`}
            aria-current={active ? 'page' : undefined}
            onClick={() => onSelect(s.key)}
          >
            {s.icon}
            {dot && <span className="oc-rail__dot" />}
          </button>
        )
      })}
    </nav>
  )
}

// ===================== 上下文面板 ======================

/**
 * 分区面板：对话分区直接渲染会话列表（高频主功能独占纵向空间），
 * 其余分区渲染该分区的页面入口（3~5 条，带一句说明，不再是十几行平铺）。
 */
function NavPanel({
  section,
  page,
  unread,
  onNavigate,
  onOpenSession,
  onNewSessionWithAgent,
}: {
  section: string
  page: string
  unread: number
  onNavigate: (k: string) => void
  onOpenSession: (id: string) => void
  onNewSessionWithAgent: (agentId: string) => Promise<void>
}) {
  const sec = sectionMeta(section) ?? NAV_SECTIONS[1]
  const { user } = useAuth()
  const scopes = user?.scopes ?? []
  const visibleItems = sec.items.filter(
    (it) => !it.requiredScope || scopes.includes(it.requiredScope),
  )

  if (sec.kind === 'chat') {
    return <SessionList page={page} onOpen={onOpenSession} onNewWithAgent={onNewSessionWithAgent} />
  }

  return (
    <div className="oc-panel">
      <div className="oc-panel__head">
        <span className="oc-panel__title">{sec.label}</span>
        <span className="oc-panel__hint">{sec.hint}</span>
      </div>
      <div className="oc-panel__nav">
        {visibleItems.map((it) => (
          <button
            type="button"
            key={it.key}
            className={`oc-pnav ${page === it.key ? 'is-active' : ''}`}
            onClick={() => onNavigate(it.key)}
          >
            <span className="oc-pnav__icon">{it.icon}</span>
            <span className="oc-pnav__text">
              <span className="oc-pnav__name">{it.label}</span>
              <span className="oc-pnav__desc">{it.desc}</span>
            </span>
            {it.badge && unread > 0 && <Badge count={unread} size="small" color={OC.accent} />}
          </button>
        ))}
      </div>
    </div>
  )
}

// ===================== Agent 切换器 ======================

/** 可选 Agent 列表：接口未返回/未加载时退化为默认助手。 */
function useAgentOptions(agents: AgentInfo[]): AgentInfo[] {
  return useMemo(
    () =>
      agents.length
        ? agents
        : [{ agentId: 'default', name: '默认助手', emoji: '🦊', description: '通用智能体' }],
    [agents],
  )
}

/** Agent 圆形 emoji 容器（触发器/选项/轨道共用）。 */
function AgentAvatar({ a, size = 30 }: { a: AgentInfo; size?: number }) {
  return (
    <span
      className="oc-agent__avatar"
      style={{ width: size, height: size, fontSize: Math.round(size * 0.53) }}
      aria-hidden
    >
      {a.emoji || '🤖'}
    </span>
  )
}

// —— Agent 切换器：卡片触发器 + Popover 面板（替代原生 Select）——
// 2026-09-14：从侧栏迁移到聊天页 composer 下方工具条（QClaw 布局），
// chip 模式渲染紧凑触发器（emoji + 名称 + chevron），供 ChatPage 复用。
export function AgentSwitcher({ collapsed, chip }: { collapsed: boolean; chip?: boolean }) {
  const chat = useChat()
  const [open, setOpen] = useState(false)
  const options = useAgentOptions(chat.agents)
  const cur = options.find((a) => a.agentId === (chat.newAgentId || 'default')) ?? options[0]

  const pick = (id: string) => {
    setOpen(false)
    if (id !== (chat.newAgentId || 'default')) void chat.selectAgent(id)
  }

  const panel = (
    <div className="oc-agent-panel">
      <div className="oc-agent-panel__head">切换智能体</div>
      {options.map((a) => {
        const active = a.agentId === cur.agentId
        return (
          <div
            key={a.agentId}
            className={`oc-agent-opt ${active ? 'is-active' : ''}`}
            onClick={() => pick(a.agentId)}
          >
            <AgentAvatar a={a} />
            <div className="oc-agent-opt__text">
              <div className="oc-agent-opt__name">{a.name || a.agentId}</div>
              <div className="oc-agent-opt__desc">{a.description || (a.agentId === 'default' ? '通用智能体' : '—')}</div>
            </div>
            {active && <CheckOutlined className="oc-agent-opt__check" />}
          </div>
        )
      })}
    </div>
  )

  return (
    <Popover
      trigger="click"
      placement={collapsed ? 'rightTop' : chip ? 'topLeft' : 'bottomLeft'}
      open={open}
      onOpenChange={setOpen}
      content={panel}
      arrow={false}
      styles={{ content: { padding: 0 } }}
      overlayInnerStyle={{ minWidth: collapsed ? 232 : undefined, width: collapsed ? undefined : 240 }}
    >
      {collapsed ? (
        <button
          type="button"
          className="oc-agent oc-agent--rail"
          title={`${cur.name || '默认助手'} · 点击切换智能体`}
        >
          <AgentAvatar a={cur} size={34} />
        </button>
      ) : chip ? (
        <button
          type="button"
          className={`oc-agent oc-agent--chip ${open ? 'is-open' : ''}`}
          title={`${cur.name || '默认助手'} · 点击切换智能体`}
        >
          <AgentAvatar a={cur} size={20} />
          <span className="oc-agent__chip-name">{cur.name || '默认助手'}</span>
          <DownOutlined className="oc-agent__chevron" />
        </button>
      ) : (
        <button type="button" className={`oc-agent ${open ? 'is-open' : ''}`}>
          <AgentAvatar a={cur} />
          <span className="oc-agent__text">
            <span className="oc-agent__name">{cur.name || '默认助手'}</span>
            <span className="oc-agent__hint">切换智能体</span>
          </span>
          <DownOutlined className="oc-agent__chevron" />
        </button>
      )}
    </Popover>
  )
}

// ===================== 会话列表（对话分区的面板）=====================

// —— 会话行 ——
function SessionRow({
  s,
  active,
  isPinned,
  onOpen,
}: {
  s: SessionSummary
  active: boolean
  isPinned: boolean
  onOpen: () => void
}) {
  const { message } = AntApp.useApp()
  const chat = useChat()
  const isBusy = chat.busySids.has(s.sessionId)

  const copyId = (id: string) => {
    if (navigator.clipboard?.writeText)
      navigator.clipboard.writeText(id).then(
        () => message.success('已复制会话 ID'),
        () => message.warning('复制失败，请手动选择'),
      )
  }

  const menu: MenuProps = {
    items: [
      { key: 'copy', icon: <CopyOutlined />, label: '复制会话 ID' },
      { key: 'rename', icon: <EditOutlined />, label: '重命名…' },
      { key: 'pin', icon: <PushpinOutlined />, label: isPinned ? '取消置顶' : '置顶' },
      { type: 'divider' },
      { key: 'archive', icon: <InboxOutlined />, label: '归档' },
      { key: 'delete', icon: <DeleteOutlined />, label: '删除', danger: true },
    ],
    onClick: ({ key, domEvent }) => {
      domEvent.stopPropagation()
      if (key === 'copy') copyId(s.sessionId)
      else if (key === 'rename') chat.renameSession(s)
      else if (key === 'pin') chat.togglePin(s.sessionId)
      else if (key === 'archive') chat.archiveSession(s)
      else if (key === 'delete') chat.deleteSession(s)
    },
  }

  const ag = chat.agentOf(s)

  return (
    <div className={`oc-sess ${active ? 'is-active' : ''}`} onClick={onOpen}>
      <span className={`oc-sess__state ${s.state === 'ACTIVE' ? 'is-live' : ''}`} />
      {isPinned && <PushpinFilled className="oc-sess__pin" />}
      {ag && ag.agentId !== 'default' && (
        <span className="oc-sess__emoji" title={ag.name}>
          {ag.emoji || '🤖'}
        </span>
      )}
      <span className="oc-sess__title">{sessionLabel(s)}</span>
      {isBusy && <Spin size="small" className="oc-sess__busy" />}
      <Dropdown menu={menu} trigger={['click']} placement="bottomRight">
        <button
          className="oc-sess__more"
          onClick={(e) => e.stopPropagation()}
          title="会话操作"
        >
          <MoreOutlined />
        </button>
      </Dropdown>
    </div>
  )
}

// —— 会话列表（面板主体）：过滤 + 置顶区 + 按 Agent 分组的最近会话 ——
function SessionList({
  page,
  onOpen,
  onNewWithAgent,
}: {
  page: string
  onOpen: (id: string) => void
  onNewWithAgent: (agentId: string) => Promise<void>
}) {
  const chat = useChat()
  const [filterOpen, setFilterOpen] = useState(false)
  const [filter, setFilter] = useState('')

  const q = filter.trim().toLowerCase()
  const all = chat.sessions.filter((s) => !q || sessionLabel(s).toLowerCase().includes(q))
  const pinned = all.filter((s) => chat.pinned.includes(s.sessionId))
  const rest = all.filter((s) => !chat.pinned.includes(s.sessionId))

  // 按 Agent 分组（组内保持服务端/pin 排序后的相对顺序）：
  // 当前选中 Agent 的组排最前，其余按组内会话数降序（再按名称稳定次序）。
  const groups = useMemo(() => {
    const byAgent = new Map<string, SessionSummary[]>()
    for (const s of rest) {
      const k = s.agentId || 'default'
      const l = byAgent.get(k)
      if (l) l.push(s)
      else byAgent.set(k, [s])
    }
    const cur = chat.newAgentId || 'default'
    return Array.from(byAgent.entries())
      .map(([agentId, list]) => ({
        agentId,
        list,
        info: chat.agentOf(agentId),
        current: agentId === cur,
      }))
      .sort((a, b) => {
        if (a.current !== b.current) return a.current ? -1 : 1
        if (a.list.length !== b.list.length) return b.list.length - a.list.length
        return (a.info?.name || a.agentId).localeCompare(b.info?.name || b.agentId, 'zh-CN')
      })
  }, [rest, chat.agents, chat.newAgentId]) // eslint-disable-line react-hooks/exhaustive-deps

  const renderRow = (s: SessionSummary) => (
    <SessionRow
      key={s.sessionId}
      s={s}
      active={page === 'chat' && chat.currentId === s.sessionId}
      isPinned={chat.pinned.includes(s.sessionId)}
      onOpen={() => onOpen(s.sessionId)}
    />
  )

  const groupHeader = (g: {
    agentId: string
    list: SessionSummary[]
    info: AgentInfo | undefined
  }) => {
    // 在该 Agent 下建新会话：默认走当前 newSession 路径（空白草稿会被原地复用，
    // 切业务走 newSession(agentId)）。onNewWithAgent 由 Sidebar 容器注入，
    // 负责切到 chat 页，让这条入口在 Agents/Tools 等非 chat 页也生效。
    const newHere = (e: React.MouseEvent) => {
      e.stopPropagation()
      void onNewWithAgent(g.agentId)
    }
    const label = `在${g.agentId === 'default' ? '默认助手' : g.info?.name || g.agentId}下开新对话`
    return (
      <div className="oc-sess-group oc-sess-group--has-add" key={`g-${g.agentId}`} title={g.info?.description || ''}>
        <span className="oc-sess-group__emoji">{g.info?.emoji || '🤖'}</span>
        <span className="oc-sess-group__name">
          {g.agentId === 'default' ? '默认助手' : g.info?.name || g.agentId}
        </span>
        <span className="oc-sess-group__count">{g.list.length}</span>
        <button
          type="button"
          className="oc-sess-group__add"
          onClick={newHere}
          title={label}
          aria-label={label}
        >
          <PlusOutlined />
        </button>
      </div>
    )
  }

  return (
    <div className="oc-panel">
      <div className="oc-panel__head">
        <div className="oc-panel__headrow">
          <span className="oc-panel__title">会话</span>
          <span className="oc-panel__spacer" />
          <button
            className={`oc-iconbtn ${filterOpen ? 'is-on' : ''}`}
            title="过滤会话"
            onClick={() => {
              setFilterOpen((o) => !o)
              if (filterOpen) setFilter('')
            }}
          >
            <SearchOutlined />
          </button>
          <button className="oc-iconbtn" title="刷新" onClick={() => chat.loadSessions()}>
            <ReloadOutlined />
          </button>
        </div>
        <span className="oc-panel__hint">{chat.sessions.length} 个活跃会话</span>
      </div>

      {filterOpen && (
        <div className="oc-sess-filter">
          <Input
            autoFocus
            allowClear={{ clearIcon: <CloseCircleFilled style={{ fontSize: 11 }} /> }}
            size="small"
            placeholder="过滤会话标题…"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
          />
        </div>
      )}

      <div className="oc-sess-scroll">
        {chat.sessions.length === 0 ? (
          <div className="oc-sess-empty">
            <div className="oc-sess-empty__icon">
              <BranchesOutlined />
            </div>
            <div className="oc-sess-empty__title">还没有会话</div>
            <div className="oc-sess-empty__desc">在智能体旁点 ⊕ 开始</div>
          </div>
        ) : all.length === 0 ? (
          <div className="oc-sess-empty">
            <div className="oc-sess-empty__title">没有匹配的会话</div>
          </div>
        ) : (
          <>
            {pinned.length > 0 && (
              <>
                <div className="oc-sess-group oc-sess-group--static">置顶</div>
                {pinned.map(renderRow)}
              </>
            )}
            {groups.map((g) => (
              <div className="oc-sess-agentgroup" key={g.agentId}>
                {groupHeader(g)}
                {g.list.map(renderRow)}
              </div>
            ))}
          </>
        )}
      </div>
    </div>
  )
}

// ===================== 收件箱 ======================

// —— 收件箱浮层：全部/审批/任务/系统 统一通知中心 ——
function relTime(ts: number) {
  const m = Math.floor((Date.now() - ts) / 60000)
  if (m < 1) return '刚刚'
  if (m < 60) return `${m} 分钟前`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h} 小时前`
  return new Date(ts).toLocaleDateString('zh-CN')
}

const KIND_ICON: Record<Notice['kind'], React.ReactNode> = {
  approval: <AuditOutlined />,
  task: <TeamOutlined />,
  system: <ClockCircleOutlined />,
}

function inboxTabLabel(text: string, count: number) {
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
      {text}
      {count > 0 && <Badge count={count} size="small" color={OC.accent} />}
    </span>
  )
}

/**
 * 收件箱浮层。导出给 Topbar 使用——常驻入口统一收在顶栏，侧栏不再挂账户/收件箱。
 */
export function InboxMenu({ onNavigate }: { onNavigate: (k: string) => void }) {
  const [open, setOpen] = useState(false)
  const [tab, setTab] = useState('all')
  const { notices, unreadOf, markAllRead, markRead } = useInbox()
  const unread = unreadOf('all')
  const list = notices.filter((n) => tab === 'all' || n.kind === tab)

  const content = (
    <div style={{ width: 380 }}>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          padding: '10px 14px 10px 16px',
          borderBottom: `1px solid ${OC.border}`,
        }}
      >
        <InboxOutlined style={{ color: unread > 0 ? OC.accent : OC.muted, fontSize: 15 }} />
        <span style={{ fontWeight: 700, fontSize: 14, color: OC.textStrong }}>收件箱</span>
        <span style={{ flex: 1 }} />
        <Button
          type="text"
          size="small"
          onClick={markAllRead}
          style={{ color: OC.muted, fontSize: 12, height: 26, padding: '0 8px' }}
        >
          忽略已显示项
        </Button>
      </div>
      <Tabs
        size="small"
        activeKey={tab}
        onChange={setTab}
        tabBarGutter={22}
        style={{ margin: '0 10px' }}
        items={[
          { key: 'all', label: inboxTabLabel('全部', unreadOf('all')) },
          { key: 'approval', label: inboxTabLabel('审批', unreadOf('approval')) },
          { key: 'task', label: inboxTabLabel('任务', unreadOf('task')) },
          { key: 'system', label: inboxTabLabel('系统', unreadOf('system')) },
        ]}
      />
      <div style={{ maxHeight: 320, overflowY: 'auto', padding: '2px 8px 8px' }}>
        {list.length === 0 ? (
          <div className="oc-inbox-empty">没有待处理事项</div>
        ) : (
          list.map((n) => (
            <div
              key={n.id}
              className="oc-inbox-item"
              onClick={() => {
                markRead(n.id)
                setOpen(false)
                if (n.goto) onNavigate(n.goto)
              }}
            >
              <span className="oc-ii-icon">{KIND_ICON[n.kind]}</span>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div className="oc-ii-title">{n.title}</div>
                <div className="oc-ii-sub">{n.desc}</div>
              </div>
              <div style={{ flex: '0 0 auto', display: 'flex', alignItems: 'center', gap: 8 }}>
                <span style={{ fontSize: 11, color: OC.muted, whiteSpace: 'nowrap' }}>{relTime(n.time)}</span>
                {!n.read && <span style={{ width: 7, height: 7, borderRadius: '50%', background: OC.accent, flex: '0 0 auto' }} />}
                <RightOutlined style={{ color: OC.muted, fontSize: 12 }} />
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  )

  return (
    <Popover
      trigger="click"
      placement="topLeft"
      open={open}
      onOpenChange={setOpen}
      content={content}
      styles={{ content: { padding: 0 }, title: { display: 'none' } }}
      overlayStyle={{ maxWidth: '92vw' }}
    >
      <Badge count={unread} size="small" offset={[-4, 4]} color={OC.accent}>
        <button className="oc-iconbtn oc-iconbtn--lg" title="收件箱">
          <InboxOutlined style={{ fontSize: 15, color: unread > 0 ? OC.accent : OC.muted }} />
        </button>
      </Badge>
    </Popover>
  )
}

// ===================== 侧栏容器 ======================

export default function Sidebar({
  collapsed,
  onCollapse,
  page,
  onNavigate,
}: {
  collapsed: boolean
  onCollapse: (c: boolean) => void
  page: string
  onNavigate: (k: string) => void
}) {
  const chat = useChat()
  const { unreadOf } = useInbox()
  const unread = unreadOf('all')
  const [section, setSection] = useState<string>(() => sectionOfPage(page))

  // 页面变化（含命令面板/收件箱/概览快捷入口跳转）时同步图标轨高亮。
  useEffect(() => {
    setSection(sectionOfPage(page))
  }, [page])

  const openChatSession = async (id: string) => {
    await chat.openSession(id)
    onNavigate('chat')
  }

  // 在指定 Agent 下新建会话并跳到 Chat 页：让 Sidebar Agent 旁 hover + 在任何页都可达。
  const openChatWithAgent = async (agentId: string) => {
    await chat.newSession(agentId)
    onNavigate('chat')
  }

  // 面板折起时点图标轨：切换分区并顺带把面板展开，避免"点了没反应"。
  const pickSection = (key: string) => {
    setSection(key)
    if (collapsed) onCollapse(false)
  }

  return (
    <Sider
      collapsible
      collapsed={collapsed}
      onCollapse={onCollapse}
      trigger={null}
      width={RAIL_W + PANEL_W}
      collapsedWidth={RAIL_W}
      theme="dark"
      className="oc-sider"
      style={{ background: OC.bg, borderRight: `1px solid ${OC.border}`, flex: '0 0 auto' }}
      styles={{
        body: {
          display: 'flex',
          flexDirection: 'row',
          height: '100%',
          overflow: 'hidden',
          padding: 0,
        },
      }}
    >
      <NavRail section={section} page={page} unread={unread} onSelect={pickSection} />
      {!collapsed && (
        <NavPanel
          section={section}
          page={page}
          unread={unread}
          onNavigate={onNavigate}
          onOpenSession={openChatSession}
          onNewSessionWithAgent={openChatWithAgent}
        />
      )}
    </Sider>
  )
}
