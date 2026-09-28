import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { Modal } from 'antd'
import { BranchesOutlined, RightOutlined, RobotOutlined } from '@ant-design/icons'
import { useChat, sessionLabel } from './chat'
import { useAuth } from './auth'
import { NAV_SECTIONS, NAV_META, STANDALONE_PAGES } from './nav'
import { OC } from './ui'

export type CommandKind = 'page' | 'session' | 'agent'
export type CommandItem = {
  kind: CommandKind
  key: string
  title: string
  sub: string
  icon: ReactNode
  group: string
  requiredScope?: string
}

const PAGE_INDEX: CommandItem[] = [
  ...NAV_SECTIONS.flatMap((s) =>
    s.items.map<CommandItem>((it) => ({
      kind: 'page',
      key: it.key,
      title: it.label,
      sub: it.desc,
      icon: it.icon,
      group: s.label,
      requiredScope: it.requiredScope,
    })),
  ),
  // Standalone center pages (reached from contextual menus) are searchable too.
  ...STANDALONE_PAGES.map<CommandItem>((p) => ({
    kind: 'page',
    key: p.key,
    title: p.label,
    sub: p.desc,
    icon: p.icon,
    group: '账户',
    requiredScope: p.requiredScope,
  })),
]

const AGENT_ICON = (emoji: string) => (
  <span
    aria-hidden
    style={{
      display: 'inline-flex',
      alignItems: 'center',
      justifyContent: 'center',
      width: 20,
      height: 20,
      fontSize: 13,
      lineHeight: 1,
    }}
  >
    {emoji || '🤖'}
  </span>
)

/**
 * Ctrl/Cmd+K 唤起的全局命令面板：模糊搜索页面、会话、智能体，回车/上下键导航。
 * 对标 LangSmith / Linear / Raycast；搜索用目标 zh 标题 + 描述全文包含（不做拼音）。
 */
export function CommandPalette({
  open,
  onClose,
  page,
  onNavigate,
}: {
  open: boolean
  onClose: () => void
  page: string
  onNavigate: (k: string) => void
}) {
  const chat = useChat()
  const { user } = useAuth()
  const scopes = user?.scopes ?? []
  const [q, setQ] = useState('')
  const [sel, setSel] = useState(0)
  const listRef = useRef<HTMLDivElement | null>(null)
  const inputRef = useRef<HTMLInputElement | null>(null)

  // 动态条目：会话 + 智能体（页面是静态）；首屏数量收敛避免内存爆炸。
  const sessionItems: CommandItem[] = useMemo(
    () =>
      chat.sessions.slice(0, 30).map<CommandItem>((s) => ({
        kind: 'session',
        key: s.sessionId,
        title: sessionLabel(s),
        sub: '会话',
        icon: <BranchesOutlined />,
        group: '最近会话',
      })),
    [chat.sessions],
  )

  const agentItems: CommandItem[] = useMemo(
    () =>
      chat.agents.slice(0, 20).map<CommandItem>((a) => ({
        kind: 'agent',
        key: a.agentId,
        title: a.name || a.agentId,
        sub: a.description || '智能体',
        icon: a.agentId === 'default' ? <RobotOutlined /> : AGENT_ICON(a.emoji || ''),
        group: '智能体',
      })),
    [chat.agents],
  )

  // Pages the current caller may access (scope-gated); unrestricted mode has
  // no scope list, so scope-gated items stay hidden there too (they need auth).
  const visiblePages = useMemo(
    () =>
      PAGE_INDEX.filter((p) => !p.requiredScope || scopes.includes(p.requiredScope)),
    [scopes],
  )

  const all: CommandItem[] = useMemo(
    () => [...visiblePages, ...sessionItems, ...agentItems],
    [visiblePages, sessionItems, agentItems],
  )

  const filtered: CommandItem[] = useMemo(() => {
    const needle = q.trim().toLowerCase()
    if (!needle) {
      // 空查询：页面置顶 + 当前页优先；会话/智能体各取头部前几个。
      const cur = visiblePages.findIndex((p) => p.key === page)
      const pages =
        cur > 0
          ? [visiblePages[cur], ...visiblePages.slice(0, cur), ...visiblePages.slice(cur + 1)]
          : visiblePages
      return [...pages, ...sessionItems.slice(0, 8), ...agentItems.slice(0, 6)]
    }
    // 多关键词 AND：拆空格逐 token 包含，便于"代理 工具"这种交叉意图。
    const tokens = needle.split(/\s+/).filter(Boolean)
    return all.filter((it) =>
      tokens.every((t) =>
        (it.title + ' ' + it.sub + ' ' + it.key + ' ' + it.group).toLowerCase().includes(t),
      ),
    )
  }, [q, all, visiblePages, sessionItems, agentItems, page])

  // 按类型分段渲染：每段内连续索引，selection 跨段统一。
  const sections = useMemo(() => {
    const order: CommandKind[] = ['page', 'session', 'agent']
    const buckets: Record<CommandKind, CommandItem[]> = { page: [], session: [], agent: [] }
    for (const it of filtered) buckets[it.kind].push(it)
    return order
      .filter((k) => buckets[k].length > 0)
      .map((k) => ({
        kind: k,
        title: k === 'page' ? '页面' : k === 'session' ? '最近会话' : '智能体',
        items: buckets[k],
      }))
  }, [filtered])

  // 仅 item（不计 header），sel 直接对应下标。
  const itemRows = useMemo(
    () => sections.flatMap((s) => s.items),
    [sections],
  )

  useEffect(() => {
    if (open) {
      setSel(0)
      setQ('')
      const t = setTimeout(() => inputRef.current?.focus(), 40)
      return () => clearTimeout(t)
    }
  }, [open])

  useEffect(() => {
    if (sel >= itemRows.length) setSel(Math.max(0, itemRows.length - 1))
  }, [itemRows.length, sel])

  // 选中项进入可视区
  useEffect(() => {
    if (!listRef.current) return
    const el = listRef.current.querySelector<HTMLElement>(`[data-cmdp-idx="${sel}"]`)
    if (el) el.scrollIntoView({ block: 'nearest' })
  }, [sel])

  const pick = (it: CommandItem) => {
    if (it.kind === 'page') onNavigate(it.key)
    else if (it.kind === 'session') {
      void chat.openSession(it.key)
      onNavigate('chat')
    } else {
      void chat.selectAgent(it.key)
      onNavigate('chat')
    }
    onClose()
  }

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setSel((i) => Math.min(i + 1, itemRows.length - 1))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setSel((i) => Math.max(i - 1, 0))
    } else if (e.key === 'Enter') {
      e.preventDefault()
      const it = itemRows[sel]
      if (it) pick(it)
    }
  }

  return (
    <Modal
      open={open}
      onCancel={onClose}
      footer={null}
      closable={false}
      width={620}
      centered
      styles={{
        body: { padding: 0 },
        container: { background: OC.bgElevated, border: `1px solid ${OC.border}` },
        mask: { backdropFilter: 'blur(2px)' },
      }}
      destroyOnHidden
    >
      <div className="oc-cmdp" onKeyDown={onKeyDown}>
        <div className="oc-cmdp__input">
          <span className="oc-cmdp__prompt">›</span>
          <input
            ref={inputRef}
            value={q}
            onChange={(e) => {
              setQ(e.target.value)
              setSel(0)
            }}
            placeholder="搜索页面、会话或智能体…"
            spellCheck={false}
            autoComplete="off"
            className="oc-cmdp__field"
          />
          <span className="oc-cmdp__hint">Esc · ↑↓ · ↵</span>
        </div>
        <div className="oc-cmdp__list" ref={listRef}>
          {sections.length === 0 ? (
            <div className="oc-cmdp__empty">没有匹配项</div>
          ) : (
            sections.map((s) => (
              <div className="oc-cmdp__section" key={s.kind}>
                <div className="oc-cmdp__section-title">{s.title}</div>
                {s.items.map((it) => {
                  const realIdx = itemRows.indexOf(it)
                  const active = realIdx === sel
                  return (
                    <div
                      key={`${s.kind}:${it.key}`}
                      data-cmdp-idx={realIdx}
                      className={`oc-cmdp__item ${active ? 'is-active' : ''}`}
                      onMouseEnter={() => setSel(realIdx)}
                      onClick={() => pick(it)}
                    >
                      <span className="oc-cmdp__icon">{it.icon}</span>
                      <span className="oc-cmdp__text">
                        <span className="oc-cmdp__title">{highlight(it.title, q)}</span>
                        <span className="oc-cmdp__sub">{it.sub}</span>
                      </span>
                      <RightOutlined className="oc-cmdp__chev" />
                    </div>
                  )
                })}
              </div>
            ))
          )}
        </div>
        <div className="oc-cmdp__foot">
          <span>{itemRows.length} 项结果</span>
          <span style={{ flex: 1 }} />
          {NAV_META[page] && (
            <span className="oc-cmdp__crumb">当前页 · {NAV_META[page].title}</span>
          )}
        </div>
      </div>
    </Modal>
  )
}

/** 简单加亮匹配片段；非匹配字符保留纯文本。 */
function highlight(text: string, query: string): ReactNode {
  const q = query.trim()
  if (!q) return text
  const tokens = q.split(/\s+/).filter(Boolean)
  if (tokens.length === 0) return text
  const re = new RegExp(`(${tokens.map(escapeReg).join('|')})`, 'gi')
  const parts = text.split(re)
  return parts.map((p, i) =>
    re.test(p) ? (
      <mark key={i} className="oc-cmdp__mark">
        {p}
      </mark>
    ) : (
      <span key={i}>{p}</span>
    ),
  )
}

function escapeReg(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/** 全局 Ctrl/Cmd+K 钩子；挂在 App.tsx 顶层，避免每个组件重复监听。 */
export function useCommandPaletteHotkey(onToggle: () => void) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const k = e.key?.toLowerCase()
      if (k === 'k' && (e.metaKey || e.ctrlKey) && !e.altKey) {
        e.preventDefault()
        onToggle()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onToggle])
}