// 统一收件箱通知中心：审批（轮询）+ 定时任务失败（轮询比对 errorCount）
// + 子代理完成/失败（SSE 实时）。通知落 localStorage，刷新不丢。
import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from './api'

export type NoticeKind = 'approval' | 'task' | 'system'

export type Notice = {
  id: string
  kind: NoticeKind
  title: string
  desc: string
  time: number
  read?: boolean
  goto?: string
}

const STORE_KEY = 'oc.inbox.notices'
const MAX_KEEP = 50
const TTL_MS = 7 * 24 * 3600 * 1000
const POLL_MS = 20000

function load(): Notice[] {
  try {
    const arr: Notice[] = JSON.parse(localStorage.getItem(STORE_KEY) || '[]')
    const now = Date.now()
    return arr.filter((n) => now - n.time < TTL_MS)
  } catch {
    return []
  }
}

function persist(list: Notice[]) {
  try {
    localStorage.setItem(STORE_KEY, JSON.stringify(list.slice(0, MAX_KEEP)))
  } catch {
    /* ignore */
  }
}

const clip = (s: unknown, n = 120) => String(s ?? '').replace(/\s+/g, ' ').slice(0, n)

export function useInbox() {
  const [notices, setNotices] = useState<Notice[]>(load)
  const errBase = useRef<Record<string, number>>({})
  const tasksInited = useRef(false)

  const upsert = useCallback((ns: Notice[]) => {
    setNotices((prev) => {
      const map = new Map(prev.map((n) => [n.id, n]))
      for (const n of ns) if (!map.has(n.id)) map.set(n.id, n)
      const next = Array.from(map.values()).sort((a, b) => b.time - a.time).slice(0, MAX_KEEP)
      persist(next)
      return next
    })
  }, [])

  // —— 轮询：审批队列 + 定时任务失败 ——
  const poll = useCallback(async () => {
    try {
      const d: any = await api.get('/api/v1/approvals')
      const arr: any[] = Array.isArray(d) ? d : d.items || d.pending || d.results || []
      const ns: Notice[] = arr.map((a) => ({
        id: 'approval:' + (a.callId || a.id),
        kind: 'approval' as NoticeKind,
        title: a.toolName || a.tool || '审批请求',
        desc: clip(a.description || a.summary || '等待人工审批'),
        time: Date.now(),
        goto: 'approvals',
      }))
      setNotices((prev) => {
        // 审批队列是权威状态：队列里消失的（已处理）同步移除通知
        const keep = prev.filter((n) => n.kind !== 'approval')
        const next = [...ns, ...keep].sort((a, b) => b.time - a.time).slice(0, MAX_KEEP)
        persist(next)
        return next
      })
    } catch {
      /* ignore */
    }

    try {
      const tasks: any[] = await api.get('/api/v1/scheduler/tasks')
      const base = errBase.current
      const fresh: Notice[] = []
      for (const t of tasks || []) {
        const id = String(t.id || t.taskId || '')
        const ec = Number(t.errorCount || 0)
        if (!tasksInited.current) {
          base[id] = ec
          continue
        }
        if (ec > (base[id] ?? 0)) {
          fresh.push({
            id: `system:${id}:${ec}`,
            kind: 'system',
            title: `定时任务失败：${t.name || id}`,
            desc: `累计失败 ${ec} 次`,
            time: Date.now(),
            goto: 'scheduler',
          })
        }
        base[id] = ec
      }
      tasksInited.current = true
      if (fresh.length) upsert(fresh)
    } catch {
      /* ignore */
    }
  }, [upsert])

  useEffect(() => {
    poll()
    const t = setInterval(poll, POLL_MS)
    return () => clearInterval(t)
  }, [poll])

  // —— 子代理生命周期事件（SSE 实时） ——
  useEffect(() => {
    const es = new EventSource('/api/v1/subagents/events')
    const onEv = (e: MessageEvent) => {
      try {
        const j = JSON.parse(e.data)
        if (j.type === 'spawn_result') {
          upsert([
            {
              id: `task:${j.taskId}:done`,
              kind: 'task',
              title: '子代理任务完成',
              desc: clip(j.answer) || '后台子任务已完成',
              time: Date.now(),
              goto: 'subagents',
            },
          ])
        } else if (j.type === 'spawn_failed') {
          upsert([
            {
              id: `task:${j.taskId}:fail`,
              kind: 'task',
              title: '子代理任务失败',
              desc: clip(j.error || j.message || '执行失败'),
              time: Date.now(),
              goto: 'subagents',
            },
          ])
        }
      } catch {
        /* ignore malformed */
      }
    }
    es.addEventListener('subagent', onEv as EventListener)
    return () => es.close()
  }, [upsert])

  const markAllRead = useCallback(() => {
    setNotices((prev) => {
      const next = prev.map((n) => ({ ...n, read: true }))
      persist(next)
      return next
    })
  }, [])

  const markRead = useCallback((id: string) => {
    setNotices((prev) => {
      const next = prev.map((n) => (n.id === id ? { ...n, read: true } : n))
      persist(next)
      return next
    })
  }, [])

  const unreadOf = (k: NoticeKind | 'all') =>
    notices.filter((n) => !n.read && (k === 'all' || n.kind === k)).length

  return { notices, unreadOf, markAllRead, markRead, reload: poll }
}
