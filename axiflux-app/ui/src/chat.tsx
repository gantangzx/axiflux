import { createContext, useContext, useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { App as AntApp, Input } from 'antd'
import { api, streamChat, getUserId } from './api'
import { turnUsageFromDone, type SessionUsage, type TurnUsage } from './cost'

const PIN_KEY = 'oc.pinnedSessions'
const PIN_KEY_LEGACY = '***' // 早期版本被污染的 key，读取时兼容

export type SessionSummary = {
  sessionId: string
  title: string
  state: string
  messageCount: number
  agentId?: string
  /** 会话累计用量与成本：CostAccountingHook 写进 session metadata，列表接口已带（空则不下发） */
  usage?: SessionUsage
}

export type AgentInfo = {
  agentId: string
  name: string
  emoji?: string
  description?: string
  enabled?: boolean
  builtin?: boolean
}

export type ChatAttachment = { name: string; url: string; type: string }

export type Item =
  | { id: string; mid?: string; kind: 'user'; text: string; attachments?: ChatAttachment[] }
  | {
      id: string
      mid?: string
      kind: 'assistant'
      text: string
      streaming?: boolean
      /** 本轮用量；只有直播回合有（DONE 帧带回），翻历史时为 undefined */
      usage?: TurnUsage
    }
  | {
      id: string
      mid?: string
      kind: 'thinking'
      text: string
      streaming?: boolean
      startedAt?: number
      durationMs?: number
    }
  | {
      id: string
      mid?: string
      kind: 'tool'
      callId: string
      toolName: string
      args: string
      status: 'running' | 'done' | 'error'
      result?: string
      startedAt?: number
      durationMs?: number
    }
  | {
      id: string
      mid?: string
      kind: 'approval'
      /** 后端 /api/v1/approvals/{callId}/approve|reject 所需的 id；丢失就只能去列表页兜底 */
      callId: string
      toolName: string
      description: string
      /** 'pending' (刚收到) → 'approved' / 'rejected' (用户处理后)。SSE-state，不持久化 */
      status?: 'pending' | 'approved' | 'rejected'
      /** scope 留下回执能让卡片显示"批准一次"还是"本会话放行" */
      scope?: 'once' | 'session'
      decidedAt?: number
    }
  | { id: string; mid?: string; kind: 'error'; text: string }

let seq = 0
export const nextId = () => `i${Date.now()}_${seq++}`

function getPinned(): string[] {
  try {
    const raw = localStorage.getItem(PIN_KEY) || localStorage.getItem(PIN_KEY_LEGACY) || '[]'
    return JSON.parse(raw)
  } catch {
    return []
  }
}
function setPinned(a: string[]) {
  try {
    localStorage.setItem(PIN_KEY, JSON.stringify(a))
  } catch {
    /* ignore */
  }
}

export const sessionLabel = (s: SessionSummary) => {
  const id = s.sessionId || ''
  const t = (s.title || '').trim()
  if (t) return t
  return id.length > 18 ? id.slice(0, 10) + '…' + id.slice(-4) : id
}

export type ChatCtx = {
  /** 全量会话（侧栏按 Agent 分组展示；不再按当前 Agent 硬过滤） */
  sessions: SessionSummary[]
  /** 与 sessions 相同（历史兼容别名，供按 id 查标题/agent 等） */
  allSessions: SessionSummary[]
  currentId: string | null
  items: Item[]
  input: string
  setInput: (v: string) => void
  busy: boolean
  busySids: Set<string>
  models: { provider: string; model: string }[]
  model: string | undefined
  setModel: (m: string | undefined) => void
  pinned: string[]
  loadingSession: boolean
  loadSessions: () => Promise<void>
  newSession: (agentId?: string) => Promise<string>
  openSession: (id: string) => Promise<void>
  loadEarlier: (id: string) => Promise<boolean>
  hasEarlier: (id: string) => boolean
  loadingEarlier: Set<string>
  send: () => Promise<void>
  /** 中断当前（或指定）会话正在进行的流式回合 */
  stop: (sid?: string) => void
  attachments: ChatAttachment[]
  setAttachments: (v: ChatAttachment[]) => void
  renameSession: (s: SessionSummary) => void
  togglePin: (id: string) => void
  archiveSession: (s: SessionSummary) => Promise<void>
  deleteSession: (s: SessionSummary) => void
  /**
   * 把一条审批卡片在当前会话的 store 里打上已决定状态。HTTP /approve /reject 由
   * ChatPage 自己发；这里只翻本地状态（保留卡片作为可回执的依据）。
   */
  decideApproval: (
    itemId: string,
    decision: 'approved' | 'rejected',
    scope?: 'once' | 'session',
  ) => void
  agents: AgentInfo[]
  newAgentId: string
  setNewAgentId: (id: string) => void
  selectAgent: (id: string) => Promise<void>
  agentOf: (s: SessionSummary | string | null | undefined) => AgentInfo | undefined
}

const Ctx = createContext<ChatCtx | null>(null)

type HistoryMsg = {
  id?: string
  role: string
  content: string
  toolCallId?: string
  reasoning?: string
  /** 后端 Message.attachments：{ type: url } 的 map（如 {"image":"data:..."}） */
  attachments?: Record<string, unknown>
  toolCalls?: { callId?: string; toolName?: string; arguments?: unknown }[]
}

/** 后端附件 map（type -> url）转展示层数组；非字符串值丢弃。 */
function historyAttachments(a: Record<string, unknown> | undefined): ChatAttachment[] | undefined {
  if (!a) return undefined
  const list = Object.entries(a)
    .filter(([, v]) => typeof v === 'string' && (v as string).length > 0)
    .map(([type, v]) => ({ type, url: v as string, name: type === 'image' ? '图片' : '附件' }))
  return list.length > 0 ? list : undefined
}

const stringifyArgs = (a: unknown): string => {
  if (a == null) return ''
  if (typeof a === 'string') return a
  try {
    return JSON.stringify(a, null, 2)
  } catch {
    return String(a)
  }
}

/**
 * 历史消息转展示项；mid 保留后端消息 id 用于翻页去重。
 * ASSISTANT.toolCalls 还原成 tool 卡片，随后的 TOOL 消息按 callId 回填结果——
 * 否则翻看历史时全部工具调用都会消失（只有实时流式才可见）。
 */
function historyToItems(msgs: HistoryMsg[]): Item[] {
  const loaded: Item[] = []
  const pending = new Map<string, Extract<Item, { kind: 'tool' }>>()
  // 同一轮（一条 USER 之后的所有 ASSISTANT/TOOL 消息）的多段 reasoning
  // （工具调用轮 + 最终回答轮）合并为一个思考块，刷新后也只显示一张卡片
  let turnItems: Item[] = []
  let turnReasoning: string[] = []
  const flushTurn = () => {
    if (turnReasoning.length) {
      turnItems.unshift({
        id: nextId(),
        kind: 'thinking',
        text: turnReasoning.join('\n\n'),
        streaming: false,
      })
    }
    loaded.push(...turnItems)
    turnItems = []
    turnReasoning = []
  }
  for (const m of msgs || []) {
    if (m.role === 'USER') {
      flushTurn()
      loaded.push({
        id: nextId(),
        mid: m.id,
        kind: 'user',
        text: m.content || '',
        attachments: historyAttachments(m.attachments),
      })
    } else if (m.role === 'ASSISTANT') {
      if ((m.reasoning || '').trim()) turnReasoning.push((m.reasoning || '').trim())
      if ((m.content || '').trim())
        turnItems.push({ id: nextId(), mid: m.id, kind: 'assistant', text: m.content })
      for (const c of m.toolCalls || []) {
        if (!c?.callId) continue
        const item: Extract<Item, { kind: 'tool' }> = {
          id: nextId(),
          mid: m.id ? `${m.id}:${c.callId}` : undefined,
          kind: 'tool',
          callId: c.callId,
          toolName: c.toolName || 'tool',
          args: stringifyArgs(c.arguments) || '—',
          status: 'done',
        }
        pending.set(c.callId, item)
        turnItems.push(item)
      }
    } else if (m.role === 'TOOL') {
      const target = m.toolCallId ? pending.get(m.toolCallId) : undefined
      if (target) {
        target.result = m.content || '（无输出）'
        target.status = /^Error:/.test(m.content || '') ? 'error' : 'done'
      } else {
        // 孤儿 TOOL 消息（对应的 ASSISTANT 轮已被压缩/翻页截断）：单独成卡
        turnItems.push({
          id: nextId(),
          mid: m.id,
          kind: 'tool',
          callId: m.toolCallId || nextId(),
          toolName: 'tool',
          args: '—',
          status: /^Error:/.test(m.content || '') ? 'error' : 'done',
          result: m.content || '（无输出）',
        })
      }
    }
  }
  flushTurn()
  return loaded
}

export function ChatProvider({ children }: { children: ReactNode }) {
  const { message, modal } = AntApp.useApp()
  const [sessions, setSessions] = useState<SessionSummary[]>([])
  const [currentId, setCurrentId] = useState<string | null>(null)
  // 消息按会话分桶：流事件永远写自己会话的桶，与当前查看哪个会话无关
  const [store, setStore] = useState<Record<string, Item[]>>({})
  const [input, setInput] = useState('')
  const [attachments, setAttachments] = useState<ChatAttachment[]>([])
  const [models, setModels] = useState<{ provider: string; model: string }[]>([])
  const [model, setModel] = useState<string | undefined>(undefined)
  const [agents, setAgents] = useState<AgentInfo[]>([])
  // 新建会话使用的 Agent（后端在会话创建时绑定，切换 Agent = 用其建新会话）
  const [newAgentId, setNewAgentId] = useState<string>('default')
  // 本地草稿会话（尚未 POST 持久化，首条消息发出时才建库，避免空会话残留）
  const pendingSidsRef = useRef<Set<string>>(new Set())
  // 每个会话绑定的 agentId（草稿会话按创建时选择记录，历史会话从列表同步）
  const sidAgentRef = useRef<Record<string, string>>({})
  const sessionsRef = useRef<SessionSummary[]>([])
  sessionsRef.current = sessions
  const [pinned, setPinnedState] = useState<string[]>(getPinned)
  // 正在拉取会话历史（打开旧会话时给 loading 反馈，避免空白/欢迎屏闪烁）
  const [loadingSession, setLoadingSession] = useState(false)
  // 每个会话是否还有更早的历史可翻（后端 hasEarlier）
  const [hasEarlierMap, setHasEarlierMap] = useState<Record<string, boolean>>({})
  const hasEarlierRef = useRef<Record<string, boolean>>({})
  hasEarlierRef.current = hasEarlierMap
  // 正在翻页加载的会话集合（防重复点击）
  const [loadingEarlier, setLoadingEarlier] = useState<Set<string>>(new Set())
  const loadingEarlierRef = useRef<Set<string>>(new Set())

  const currentRef = useRef<string | null>(null)
  currentRef.current = currentId
  const storeRef = useRef(store)
  storeRef.current = store
  // 正在流式中的会话集合
  const [busySids, setBusySids] = useState<Set<string>>(new Set())
  const busySidsRef = useRef<Set<string>>(new Set())
  const markBusy = (sid: string, on: boolean) => {
    const s = busySidsRef.current
    on ? s.add(sid) : s.delete(sid)
    setBusySids(new Set(s))
  }
  const busy = currentId ? busySids.has(currentId) : false
  // 每个流式会话一个 AbortController：停止键先中断本地读取，再通知后端 interrupt
  const abortRef = useRef<Map<string, AbortController>>(new Map())

  // 针对某个会话的桶做更新
  const patchSid = useCallback((sid: string, fn: (l: Item[]) => Item[]) => {
    setStore((prev) => ({ ...prev, [sid]: fn(prev[sid] ?? []) }))
  }, [])

  const decideApproval = useCallback(
    (itemId: string, decision: 'approved' | 'rejected', scope?: 'once' | 'session') => {
      const sid = currentRef.current
      if (!sid) return
      setStore((prev) => ({
        ...prev,
        [sid]: (prev[sid] ?? []).map((it) =>
          it.id === itemId && it.kind === 'approval'
            ? { ...it, status: decision, scope: scope ?? it.scope, decidedAt: Date.now() }
            : it,
        ),
      }))
    },
    [],
  )

  const items: Item[] = currentId ? store[currentId] ?? [] : []

  const loadSessions = useCallback(async () => {
    try {
      const list = await api.get<SessionSummary[]>(
        `/api/v1/sessions?userId=${getUserId()}&state=ACTIVE&size=50`,
      )
      const p = getPinned()
      list.sort((a, b) => {
        const ia = p.indexOf(a.sessionId)
        const ib = p.indexOf(b.sessionId)
        if (ia === -1 && ib === -1) return 0
        if (ia === -1) return 1
        if (ib === -1) return -1
        return ia - ib
      })
      setSessions(list)
    } catch {
      /* ignore */
    }
  }, [])

  const loadModels = useCallback(async () => {
    try {
      setModels(await api.get<{ provider: string; model: string }[]>('/api/v1/models'))
    } catch {
      /* ignore */
    }
  }, [])

  const loadAgents = useCallback(async () => {
    try {
      const list = await api.get<AgentInfo[]>('/api/v1/agents')
      setAgents(Array.isArray(list) ? list.filter((a) => a.enabled !== false) : [])
    } catch {
      /* agents page will show the error; picker just falls back to default */
    }
  }, [])

  useEffect(() => {
    loadSessions()
    loadModels()
    loadAgents()
  }, [loadSessions, loadModels, loadAgents])

  // 新建会话仅建本地草稿：不写库，首条消息发送时才带 agentId 持久化，
  // 避免点“+”/切 Agent 产生 0 消息空会话。
  // 当前已是空白草稿（无消息且非忙）时直接复用，防止反复切人堆积孤儿草稿。
  const newSession = useCallback(async (agentId?: string) => {
    const aid = agentId || newAgentId || 'default'
    const cur = currentRef.current
    if (cur && pendingSidsRef.current.has(cur) && !busySidsRef.current.has(cur)) {
      const items = storeRef.current[cur] ?? []
      const empty = !items.some((it) => it.kind === 'user' || it.kind === 'assistant')
      if (empty) {
        sidAgentRef.current[cur] = aid
        setNewAgentId(aid)
        setCurrentId(cur)
        setInput('')
        return cur
      }
    }
    const id = 'sess-' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6)
    sidAgentRef.current[id] = aid
    pendingSidsRef.current.add(id)
    setCurrentId(id)
    setStore((prev) => ({ ...prev, [id]: [] }))
    setInput('')
    return id
  }, [setInput, newAgentId])

  // Agent 选择器统一入口：切换 Agent = 开新对话。
  // 后端会话创建时绑定 agentId 且不可变更，换人后继续在原会话说话会用旧人设；
  // 且空白草稿会被 newSession 原地复用，所以这里一律走 newSession 切走。
  const selectAgent = useCallback(
    async (agentId: string) => {
      setNewAgentId(agentId)
      const cur = currentRef.current
      const curAgent = cur ? sidAgentRef.current[cur] : undefined
      if (cur && (curAgent || 'default') === agentId) return
      await newSession(agentId)
    },
    [newSession],
  )

  const agentOf = useCallback(
    (s: SessionSummary | string | null | undefined): AgentInfo | undefined => {
      const id = !s ? undefined : typeof s === 'string' ? s : s.agentId
      if (!id) return agents.find((a) => a.agentId === 'default')
      return agents.find((a) => a.agentId === id)
    },
    [agents],
  )

  const openSession = useCallback(async (id: string) => {
    setCurrentId(id)
    // 历史会话已在库：退出草稿态，并把选择器同步为该会话的 Agent
    pendingSidsRef.current.delete(id)
    const sum = sessionsRef.current.find((s) => s.sessionId === id)
    const aid = sum?.agentId || sidAgentRef.current[id] || 'default'
    sidAgentRef.current[id] = aid
    setNewAgentId(aid)
    // 已有缓冲（直播中或已加载过）直接展示，绝不用历史覆盖直播内容
    if (storeRef.current[id]) return
    setLoadingSession(true)
    try {
      const data = await api.get<{
        messages: HistoryMsg[]
        total: number
        hasEarlier: boolean
      }>(`/api/v1/sessions/${encodeURIComponent(id)}/messages?lastN=50`)
      const loaded = historyToItems(data.messages)
      // 函数式 guard：await 期间若该会话已开始直播/已加载，不覆盖
      setStore((prev) => (prev[id] ? prev : { ...prev, [id]: loaded }))
      setHasEarlierMap((prev) => ({ ...prev, [id]: data.hasEarlier }))
    } catch {
      setStore((prev) => (prev[id] ? prev : { ...prev, [id]: [] }))
    } finally {
      setLoadingSession(false)
    }
  }, [])

  // 加载更早的历史窗口：skip 掉桶里最新的 N 条，把更旧的一页前置拼接到桶首
  const loadEarlier = useCallback(async (id: string): Promise<boolean> => {
    if (loadingEarlierRef.current.has(id)) return false
    const cur = storeRef.current[id]
    if (!cur || !hasEarlierRef.current[id]) return false
    loadingEarlierRef.current.add(id)
    setLoadingEarlier(new Set(loadingEarlierRef.current))
    try {
      const data = await api.get<{
        messages: HistoryMsg[]
        total: number
        hasEarlier: boolean
      }>(`/api/v1/sessions/${encodeURIComponent(id)}/messages?lastN=50&skip=${cur.length}`)
      const older = historyToItems(data.messages)
      setStore((prev) => {
        const existing = prev[id] ?? []
        const have = new Set(existing.map((i) => i.mid).filter(Boolean) as string[])
        const fresh = older.filter((i) => !i.mid || !have.has(i.mid))
        return { ...prev, [id]: [...fresh, ...existing] }
      })
      setHasEarlierMap((prev) => ({ ...prev, [id]: data.hasEarlier }))
      return data.messages.length > 0
    } catch {
      return false
    } finally {
      loadingEarlierRef.current.delete(id)
      setLoadingEarlier(new Set(loadingEarlierRef.current))
    }
  }, [])

  const send = useCallback(async () => {
    const text = input.trim()
    if (!text && attachments.length === 0) return
    let sid = currentRef.current
    if (!sid) {
      sid = await newSession()
    }
    if (busySidsRef.current.has(sid)) return
    // 发送时快照附件：state 清空后用户气泡仍要展示图片
    const sentAttachments = attachments
    setInput('')
    setAttachments([])
    markBusy(sid, true)
    // 不预插空 assistant 气泡：思考块(THINKING_TOKEN)按到达顺序排在答案上方；
    // 正文气泡在首个 TEXT_TOKEN 到达时才懒创建，历史顺序即 "思考 → 工具 → 结论"。
    patchSid(sid, (l) => [
      ...l,
      { id: nextId(), kind: 'user', text, attachments: sentAttachments.length > 0 ? sentAttachments : undefined },
    ])
    try {
      // 草稿会话：首条消息发送时才以绑定的 agentId 持久化
      if (pendingSidsRef.current.has(sid)) {
        try {
          await api.post('/api/v1/sessions', {
            sessionId: sid,
            agentId: sidAgentRef.current[sid] || newAgentId || 'default',
            userId: getUserId(),
          })
        } catch {
          /* stream endpoint still lazy-creates */
        }
        pendingSidsRef.current.delete(sid)
        loadSessions()
      }
      const attachPayload = attachments.length > 0
        ? attachments.map(a => ({ type: a.type, url: a.url, name: a.name }))
        : undefined
      const ac = new AbortController()
      abortRef.current.set(sid, ac)
      await streamChat(
        { sessionId: sid, userId: getUserId(), content: text, forcedModel: model, attachments: attachPayload },
        (ev) => {
          const j = ev as Record<string, any>
          switch (ev.type as string) {
            case 'THINKING_TOKEN': {
              const delta = (j.content as string) || ''
              if (!delta) break
              // 同一轮（最后一条 user 消息之后）的所有 reasoning 合并到同一块思考卡片，
              // 即使中间夹了工具调用（工具轮之间模型继续思考，reasoning 持续流入）
              patchSid(sid!, (l) => {
                let lastUserIdx = -1
                for (let i = l.length - 1; i >= 0; i--) {
                  if (l[i].kind === 'user') { lastUserIdx = i; break }
                }
                let targetIdx = -1
                for (let i = l.length - 1; i > lastUserIdx; i--) {
                  if (l[i].kind === 'thinking') { targetIdx = i; break }
                }
                if (targetIdx >= 0) {
                  return l.map((it, idx) =>
                    idx === targetIdx && it.kind === 'thinking'
                      ? { ...it, text: it.text + delta, streaming: true }
                      : it,
                  )
                }
                return [
                  ...l,
                  { id: nextId(), kind: 'thinking', text: delta, streaming: true, startedAt: Date.now() },
                ]
              })
              break
            }
            case 'TEXT_TOKEN':
              // 首个正文 token 才懒创建 assistant 气泡（排在思考块/工具卡之后）；
              // 同时把未收尾的思考块置为 done，触发其自动折叠（思考在结论上方）
              patchSid(sid!, (l) => {
                const settled = l.map((it) =>
                  it.kind === 'thinking' && it.streaming
                    ? { ...it, streaming: false, durationMs: it.startedAt ? Date.now() - it.startedAt : it.durationMs }
                    : it,
                )
                const streamingAssistant = settled.find(
                  (it) => it.kind === 'assistant' && it.streaming,
                )
                if (streamingAssistant) {
                  return settled.map((it) =>
                    it.id === streamingAssistant.id && it.kind === 'assistant'
                      ? { ...it, text: it.text + (j.content || '') }
                      : it,
                  )
                }
                return [
                  ...settled,
                  { id: nextId(), kind: 'assistant', text: j.content || '', streaming: true },
                ]
              })
              break
            case 'TOOL_CALL': {
              if (!j.callId) break
              const args =
                typeof j.arguments === 'string' ? j.arguments : JSON.stringify(j.arguments, null, 2)
              // 工具开始：不收尾思考块（同一轮 reasoning 会继续追加合并），工具卡排在思考块之后
              patchSid(sid!, (l) => [
                ...l,
                {
                  id: nextId(),
                  kind: 'tool',
                  callId: j.callId,
                  toolName: j.toolName || 'tool',
                  args: args || '—',
                  status: 'running',
                  startedAt: Date.now(),
                },
              ])
              break
            }
            case 'TOOL_RESULT': {
              let content = j.content
              if (content == null && j.rawPayload) {
                try {
                  content = JSON.parse(j.rawPayload).content
                } catch {
                  content = ''
                }
              }
              if (typeof content === 'object') content = JSON.stringify(content, null, 2)
              patchSid(sid!, (l) =>
                l.map((it) =>
                  it.kind === 'tool' && it.callId === j.callId
                    ? {
                        ...it,
                        status: j.isError ? 'error' : 'done',
                        result: content || '（无输出）',
                        durationMs: it.startedAt ? Date.now() - it.startedAt : it.durationMs,
                      }
                    : it,
                ),
              )
              break
            }
            case 'APPROVAL':
              patchSid(sid!, (l) => [
                ...l,
                {
                  id: nextId(),
                  kind: 'approval',
                  callId: j.callId || '',
                  toolName: j.toolName || 'tool',
                  description: j.description || j.content || '该工具需要人工确认后才会执行。',
                  status: 'pending',
                },
              ])
              break
            case 'DONE': {
              const final = ((j.content || '') as string).trim()
              // DONE 的 rawPayload 是序列化后的整个 AgentResponse，本轮 token 用量挂在它的
              // metadata 上——这是单轮用量到达前端的唯一通道（见 cost.ts）。
              const turnUsage = turnUsageFromDone(j.rawPayload)
              patchSid(sid!, (l) => {
                const streamingAssistant = l.find(
                  (it) => it.kind === 'assistant' && it.streaming,
                )
                if (streamingAssistant) {
                  return l
                    .map((it) =>
                      it.id === streamingAssistant.id && it.kind === 'assistant'
                        ? { ...it, text: final || it.text, streaming: false, usage: turnUsage }
                        : it,
                    )
                    .filter(
                      (it) =>
                        !(it.id === streamingAssistant.id && it.kind === 'assistant' && !(it as any).text),
                    )
                }
                // 未流式下发过正文（例如纯工具回合后服务端汇总）：补建结论气泡
                return final
                  ? [...l, { id: nextId(), kind: 'assistant', text: final, streaming: false, usage: turnUsage }]
                  : l
              })
              break
            }
            case 'ERROR':
              patchSid(sid!, (l) => [
                ...l,
                { id: nextId(), kind: 'error', text: j.content || j.error || '请求出错' },
              ])
              break
          }
        },
        ac.signal,
      )
    } catch (e: any) {
      // 用户主动中断：不弹错误卡片，finally 会收尾流式气泡
      if (!(e instanceof DOMException && e.name === 'AbortError')) {
        patchSid(sid!, (l) => [
          ...l,
          { id: nextId(), kind: 'error', text: e?.message || '请求失败' },
        ])
      }
    } finally {
      abortRef.current.delete(sid!)
      markBusy(sid!, false)
      patchSid(sid!, (l) =>
        l
          .map((it) => {
            if (it.kind === 'tool' && it.status === 'running')
              return {
                ...it,
                status: 'done' as const,
                durationMs: it.startedAt ? Date.now() - it.startedAt : it.durationMs,
              }
            // 流终止（含异常）后关掉 assistant 气泡的直播态，避免永久"思考中…"
            if (it.kind === 'assistant' && it.streaming) return { ...it, streaming: false }
            // 思考块收尾：结束直播态并计算耗时
            if (it.kind === 'thinking' && it.streaming)
              return {
                ...it,
                streaming: false,
                durationMs: it.startedAt ? Date.now() - it.startedAt : it.durationMs,
              }
            return it
          })
          // 没接到任何文字的空占位气泡（报错/中断且无 DONE 内容）直接移除
          .filter((it) => !(it.kind === 'assistant' && !(it as { text?: string }).text))
          // 空思考块（未收到任何 reasoning delta）一并移除
          .filter((it) => !(it.kind === 'thinking' && !(it as { text?: string }).text)),
      )
      loadSessions()
    }
  }, [input, model, newSession, loadSessions, setInput, patchSid, newAgentId, attachments])

  // 中断：abort 本地 SSE 读取（立刻进入 finally 收尾）+ 通知后端打断 agent 回合。
  // 两者尽力而为：后端回合可能在切换会话后仍在跑，故 interrupt 失败不能影响 UI 收尾。
  const stop = useCallback((sidArg?: string) => {
    const sid = sidArg ?? currentRef.current
    if (!sid) return
    abortRef.current.get(sid)?.abort()
    abortRef.current.delete(sid)
    api
      .post(`/api/v1/sessions/${encodeURIComponent(sid)}/interrupt`, {})
      .catch(() => { /* agent 已结束/会话未落库都无妨，本地流已收尾 */ })
  }, [])

  const renameSession = useCallback(
    (s: SessionSummary) => {
      let val = s.title || ''
      modal.confirm({
        title: '重命名会话',
        content: (
          <Input
            defaultValue={val}
            placeholder="会话名称（留空恢复成 ID）"
            onChange={(e) => (val = e.target.value)}
          />
        ),
        onOk: async () => {
          await api.patch(`/api/v1/sessions/${encodeURIComponent(s.sessionId)}`, { title: val.trim() })
          message.success('已重命名')
          loadSessions()
        },
      })
    },
    [modal, message, loadSessions],
  )

  const togglePin = useCallback(
    (id: string) => {
      const p = getPinned()
      const i = p.indexOf(id)
      if (i >= 0) p.splice(i, 1)
      else p.unshift(id)
      setPinned(p)
      setPinnedState(p)
      loadSessions()
    },
    [loadSessions],
  )

  const archiveSession = useCallback(
    async (s: SessionSummary) => {
      await api.post(`/api/v1/sessions/${encodeURIComponent(s.sessionId)}/close`)
      message.success('已归档')
      if (currentRef.current === s.sessionId) await newSession()
      loadSessions()
    },
    [message, newSession, loadSessions],
  )

  const deleteSession = useCallback(
    (s: SessionSummary) => {
      modal.confirm({
        title: '删除会话',
        content: `会话 ${sessionLabel(s)} 及其全部消息将被永久删除，确定？`,
        okText: '删除',
        okButtonProps: { danger: true },
        onOk: async () => {
          await api.del(`/api/v1/sessions/${encodeURIComponent(s.sessionId)}`)
          message.success('已删除')
          setStore((prev) => {
            const n = { ...prev }
            delete n[s.sessionId]
            return n
          })
          if (currentRef.current === s.sessionId) await newSession()
          loadSessions()
        },
      })
    },
    [modal, message, newSession, loadSessions],
  )

  // 会话列表返回全量：侧栏改为按 Agent 分组展示，切换 Agent 不再"隐藏"其他会话，
  // 避免用户体感"会话记录丢了"。allSessions 保留为兼容别名。

  return (
    <Ctx.Provider
      value={{
        sessions,
        allSessions: sessions,
        currentId,
        items,
        input,
        setInput,
        attachments,
        setAttachments,
        busy,
        busySids,
        models,
        model,
        setModel,
        pinned,
        loadingSession,
        loadSessions,
        newSession,
        openSession,
        loadEarlier,
        hasEarlier: (id: string) => !!hasEarlierMap[id],
        loadingEarlier,
        send,
        stop,
        renameSession,
        togglePin,
        archiveSession,
        deleteSession,
        decideApproval,
        agents,
        newAgentId,
        setNewAgentId,
        selectAgent,
        agentOf,
      }}
    >
      {children}
    </Ctx.Provider>
  )
}

export function useChat(): ChatCtx {
  const c = useContext(Ctx)
  if (!c) throw new Error('useChat must be used within ChatProvider')
  return c
}
