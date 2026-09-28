import { lazy, Suspense, useCallback, useEffect, useState, type ReactNode } from 'react'
import { App as AntApp, Layout, Spin } from 'antd'
import { useAuth } from './auth'
import { NAVIGATE_EVENT } from './api'
import LoginPage from './pages/LoginPage'
import Sidebar, { InboxMenu } from './Sidebar'
import { ChatProvider, useChat, sessionLabel } from './chat'
import ChatPage from './pages/ChatPage'
import { Topbar } from './Topbar'
import { CommandPalette, useCommandPaletteHotkey } from './CommandPalette'
import { NAV_META } from './nav'
import { OC } from './ui'
import { COMMERCIAL_ROUTE_KEYS } from './edition'

// Route-level code splitting: only the landing chat page ships in the entry
// chunk; every management page (and its antd/heavy deps) loads on first visit.
const OverviewPage = lazy(() => import('./pages/OverviewPage'))
const MonitorPage = lazy(() => import('./pages/MonitorPage'))
const SessionsPage = lazy(() => import('./pages/SessionsPage'))
const AgentsPage = lazy(() => import('./pages/AgentsPage'))
const ToolsPage = lazy(() => import('./pages/ToolsPage'))
const SkillsPage = lazy(() => import('./pages/SkillsPage'))
const SchedulerPage = lazy(() => import('./pages/SchedulerPage'))
const SubagentsPage = lazy(() => import('./pages/SubagentsPage'))
const ApprovalsPage = lazy(() => import('./pages/ApprovalsPage'))
const MemoryPage = lazy(() => import('./pages/MemoryPage'))
const SecurityPage = lazy(() => import('./pages/SecurityPage'))
const AuditPage = lazy(() => import('./pages/AuditPage'))
const ModelsPage = lazy(() => import('./pages/ModelsPage'))
const SettingsPage = lazy(() => import('./pages/SettingsPage'))
const ProfileCenterPage = lazy(() => import('./pages/ProfileCenterPage'))

// Commercial pages. On the community build a Vite alias redirects this module
// to ./commercial-stub, so no commercial page chunk is emitted.
const CommercialRoutes = lazy(() => import('./commercial'))

const { Content } = Layout

/** Lightweight fallback shown while a route chunk loads on first navigation. */
function RouteFallback() {
  return (
    <div style={{ padding: '18vh 0', textAlign: 'center', color: OC.muted }}>
      <Spin />
    </div>
  )
}

function Shell() {
  const chat = useChat()
  const [collapsed, setCollapsed] = useState(false)
  const [page, setPage] = useState('chat')
  const [cmdOpen, setCmdOpen] = useState(false)
  const isChat = page === 'chat'
  const { message } = AntApp.useApp()

  const toggleCmd = useCallback(() => setCmdOpen((o) => !o), [])
  useCommandPaletteHotkey(toggleCmd)

  // Consume the Stripe Checkout return flag set by the public bridge pages.
  useEffect(() => {
    let outcome: string | null = null
    try {
      outcome = sessionStorage.getItem('oc.billing.return')
      if (outcome) sessionStorage.removeItem('oc.billing.return')
    } catch {
      outcome = null
    }
    if (outcome === 'success') {
      message.success('支付成功，套餐确认后将自动生效')
    } else if (outcome === 'cancel') {
      message.info('已取消支付，套餐保持不变')
    }
  }, [message])

  // Deep components (chat error bubble) navigate without a router dependency.
  useEffect(() => {
    const onNav = (e: Event) => {
      const page = (e as CustomEvent<string>).detail
      if (page) setPage(page)
    }
    window.addEventListener(NAVIGATE_EVENT, onNav)
    return () => window.removeEventListener(NAVIGATE_EVENT, onNav)
  }, [])

  // 对话页承接全局顶栏：标题跟随当前会话（页内不再重复一行页头）。
  const curSession = chat.sessions.find((s) => s.sessionId === chat.currentId)
  const meta = NAV_META[page] ?? NAV_META.overview
  const topbarTitle = isChat ? (curSession ? sessionLabel(curSession) : '新对话') : meta.title
  const topbarSub = isChat ? '天枢 · 智能体编排中枢' : meta.desc

  // 全局唯一「新对话」入口（顶栏右上角）：新建后回到对话页。
  const startNewChat = useCallback(() => {
    void chat.newSession()
    setPage('chat')
  }, [chat])

  const renderPage = (): ReactNode => {
    // Commercial routes resolve to real pages on enterprise or an upsell card
    // on community (the module is alias-swapped at build time).
    if (COMMERCIAL_ROUTE_KEYS.has(page)) {
      return <CommercialRoutes route={page} />
    }
    switch (page) {
      case 'chat':
        return <ChatPage />
      case 'overview':
        return <OverviewPage go={setPage} />
      case 'monitor':
        return <MonitorPage />
      case 'sessions':
        return <SessionsPage onGoChat={() => setPage('chat')} />
      case 'agents':
        return <AgentsPage />
      case 'tools':
        return <ToolsPage />
      case 'skills':
        return <SkillsPage />
      case 'scheduler':
        return <SchedulerPage />
      case 'subagents':
        return <SubagentsPage />
      case 'approvals':
        return <ApprovalsPage />
      case 'memory':
        return <MemoryPage />
      case 'security':
        return <SecurityPage />
      case 'audit':
        return <AuditPage />
      case 'models':
        return <ModelsPage />
      case 'profile':
        return <ProfileCenterPage />
      case 'settings':
        return <SettingsPage />
      default:
        return null
    }
  }

  return (
    <Layout style={{ height: '100vh', overflow: 'hidden', background: OC.bg }}>
      <Sidebar collapsed={collapsed} onCollapse={setCollapsed} page={page} onNavigate={setPage} />
      <Layout style={{ height: '100vh', overflow: 'hidden', background: OC.bg, minWidth: 0 }}>
        <Topbar
          collapsed={collapsed}
          onToggle={() => setCollapsed((c) => !c)}
          page={page}
          onNavigate={setPage}
          title={topbarTitle}
          subtitle={topbarSub}
          onOpenCommand={toggleCmd}
          onNewChat={startNewChat}
          inbox={<InboxMenu onNavigate={setPage} />}
        />
        <Content
          style={{
            background: OC.bg,
            flex: 1,
            minHeight: 0,
            overflowY: isChat ? 'hidden' : 'auto',
          }}
        >
          {isChat ? (
            renderPage()
          ) : (
            <Suspense fallback={<RouteFallback />}>
              <div key={page} className="oc-route">
                <div className="oc-page-body">{renderPage()}</div>
              </div>
            </Suspense>
          )}
        </Content>
      </Layout>
      <CommandPalette
        open={cmdOpen}
        onClose={() => setCmdOpen(false)}
        page={page}
        onNavigate={setPage}
      />
    </Layout>
  )
}

/** Full-screen gate shown while the auth mode is being determined. */
function AuthBoot() {
  return (
    <div style={{ height: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', background: OC.bg }}>
      <Spin size="large" />
    </div>
  )
}

/**
 * Root gate. When the backend enforces login and there is no valid session we
 * render only the login page (data providers stay unmounted, so no unauthenticated
 * requests fire). With auth off (single-user/dev) the shell renders directly.
 */
function Gate() {
  const { status } = useAuth()
  if (status === 'checking') return <AuthBoot />
  if (status === 'login') return <LoginPage />
  return (
    <ChatProvider>
      <Shell />
    </ChatProvider>
  )
}

export default function App() {
  return <Gate />
}