import { useRef, useState } from 'react'
import { Button, Card, Col, Row, Input, Tag, App as AntApp, Tooltip } from 'antd'
import {
  SendOutlined,
  CheckCircleFilled,
  CloseCircleFilled,
  LoadingOutlined,
  StopOutlined,
  CrownFilled,
} from '@ant-design/icons'
import { api } from '../api'
import { Loading, ErrBox, mono, OC } from '../ui'

type Ev = { t: string; type: string; data: string }

type BranchState = {
  status: 'running' | 'success' | 'failed'
  sessionId?: string
  answerLength?: number
  inputTokens?: number
  outputTokens?: number
  error?: string
}

type Group = {
  taskId: string
  mode: string
  n?: number
  status: string
  branches: Record<number, BranchState>
  criticRunning?: boolean
  critic?: { chosen: number; degraded: boolean; inputTokens?: number; outputTokens?: number }
  answer?: string
  inputTokens?: number
  outputTokens?: number
  totalTokens?: number
  cachedInputTokens?: number
  modelCalls?: number
  error?: string
}

export default function SubagentsPage() {
  const { message } = AntApp.useApp()
  const [task, setTask] = useState('')
  const [parent, setParent] = useState('api')
  const [running, setRunning] = useState(false)
  const [result, setResult] = useState<string | null>(null)
  const [resultId, setResultId] = useState<string>('')
  const [err, setErr] = useState<string | null>(null)
  const [listening, setListening] = useState(false)
  const [events, setEvents] = useState<Ev[]>([])
  const [groups, setGroups] = useState<Record<string, Group>>({})
  const [cancelling, setCancelling] = useState<string>('')
  const groupOrder = useRef<string[]>([])

  const spawn = async () => {
    if (!task.trim()) {
      message.error('任务描述不能为空')
      return
    }
    setRunning(true)
    setResult(null)
    setErr(null)
    try {
      const r: any = await api.post('/api/v1/subagents/spawn', {
        task: task.trim(),
        parentSessionId: parent.trim() || 'api',
      })
      setResultId(r.childSessionId || '—')
      setResult(typeof r.answer === 'string' ? r.answer : JSON.stringify(r, null, 2))
    } catch (e: any) {
      setErr(e.message)
    } finally {
      setRunning(false)
    }
  }

  /** Fold one lifecycle event payload into the best-of-n group card state. */
  const mergeGroup = (j: any) => {
    const tid = j?.taskId as string | undefined
    if (!tid) return
    if (!['critique'].includes(String(j.mode ?? '')) &&
        !['spawn_branch_start', 'spawn_branch_done', 'spawn_critic_start',
          'spawn_result', 'spawn_cancelled', 'spawn_failed'].includes(String(j.type))) return
    setGroups((prev) => {
      const exist: Group = prev[tid] ?? {
        taskId: tid,
        mode: j.mode === 'critique' ? 'critique' : 'critique',
        n: j.n,
        status: 'running',
        branches: {},
      }
      const g: Group = {
        ...exist,
        branches: { ...exist.branches },
        critic: exist.critic ? { ...exist.critic } : undefined,
      }
      if (j.mode) g.mode = String(j.mode)
      if (typeof j.n === 'number') g.n = j.n
      const setBranch = (idx: number, patch: Partial<BranchState>) => {
        g.branches[idx] = { status: 'running', ...g.branches[idx], ...patch }
      }
      switch (j.type) {
        case 'spawn_started':
          g.status = 'running'
          if (Array.isArray(j.branches)) {
            j.branches.forEach((sid: string, i: number) =>
              setBranch(i, { status: 'running', sessionId: sid }))
          }
          break
        case 'spawn_branch_start':
          setBranch(Number(j.index), { status: 'running', sessionId: j.childSessionId })
          break
        case 'spawn_branch_done':
          setBranch(Number(j.index), j.status === 'failed'
            ? { status: 'failed', error: j.error }
            : { status: 'success', answerLength: j.answerLength,
                inputTokens: j.inputTokens, outputTokens: j.outputTokens })
          break
        case 'spawn_critic_start':
          g.criticRunning = true
          break
        case 'spawn_result':
          g.status = 'done'
          g.criticRunning = false
          g.answer = j.answer
          g.inputTokens = j.inputTokens
          g.outputTokens = j.outputTokens
          g.totalTokens = j.totalTokens
          g.cachedInputTokens = j.cachedInputTokens
          g.modelCalls = j.modelCalls
          if (Array.isArray(j.branches)) {
            // Authoritative branch table (includes failed branches).
            g.branches = {}
            for (const b of j.branches) {
              g.branches[Number(b.index)] = b.status === 'failed'
                ? { status: 'failed', error: b.error }
                : { status: 'success', answerLength: b.answerLength,
                    inputTokens: b.inputTokens, outputTokens: b.outputTokens }
            }
          }
          if (j.critic) g.critic = { chosen: j.critic.chosen, degraded: !!j.critic.degraded,
            inputTokens: j.critic.inputTokens, outputTokens: j.critic.outputTokens }
          break
        case 'spawn_cancelled':
          g.status = 'cancelled'
          g.criticRunning = false
          break
        case 'spawn_failed':
          g.status = 'failed'
          g.criticRunning = false
          g.error = j.error
          break
      }
      if (!groupOrder.current.includes(tid)) groupOrder.current = [...groupOrder.current, tid]
      return { ...prev, [tid]: g }
    })
  }

  const toggleListen = () => {
    if (listening) {
      ;(window as any).__saES?.close()
      ;(window as any).__saES = null
      setListening(false)
      return
    }
    const es = new EventSource('/api/v1/subagents/events')
    ;(window as any).__saES = es
    const add = (type: string, data: string) =>
      setEvents((prev) => [...prev.slice(-119), { t: new Date().toLocaleTimeString(), type, data }])
    es.onopen = () => add('connected', 'SSE 已连接')
    es.addEventListener('subagent', (e: MessageEvent) => {
      try {
        const j = JSON.parse(e.data)
        mergeGroup(j)
        add(j.type || 'event', j.data ? (typeof j.data === 'string' ? j.data : JSON.stringify(j.data)) : e.data)
      } catch {
        add('event', e.data)
      }
    })
    es.onerror = () => add('error', '连接断开（重连中…）')
    setListening(true)
  }

  const cancelTask = async (tid: string) => {
    setCancelling(tid)
    try {
      await api.post(`/api/v1/subagents/tasks/${tid}/cancel`, {})
      message.success('已发送取消信号')
    } catch (e: any) {
      message.error('取消失败：' + e.message)
    } finally {
      setCancelling('')
    }
  }

  const live = (g: Group) => g.status === 'running' || g.status === 'awaiting_approval'

  return (
    <div style={{ padding: 24, maxWidth: 1200, margin: '0 auto' }}>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={12}>
          <Card
            title="发起子代理"
            variant="borderless"
            style={{ background: OC.card, height: '100%' }}
            styles={{ header: { borderBottom: `1px solid ${OC.border}`, color: OC.textStrong } }}
          >
            <div style={{ color: OC.muted, fontSize: 13, marginBottom: 6 }}>任务描述 *</div>
            <Input.TextArea
              rows={5}
              value={task}
              onChange={(e) => setTask(e.target.value)}
              placeholder="给子代理的独立任务说明（子代理看不到本对话上下文）"
            />
            <div style={{ color: OC.muted, fontSize: 12, margin: '8px 0 2px' }}>
              并行编排（best-of-n）请在对话中让 Agent 使用 spawn_task 并指定
              <code style={{ color: OC.accent, margin: '0 4px' }}>mode=parallel_critique</code>
              （默认 N=3，约 N+1× token，结果在此页组卡展示）
            </div>
            <div style={{ color: OC.muted, fontSize: 13, margin: '10px 0 6px' }}>父会话 ID</div>
            <Input value={parent} onChange={(e) => setParent(e.target.value)} style={mono} />
            <Button type="primary" icon={<SendOutlined />} loading={running} onClick={spawn} style={{ marginTop: 14 }}>
              委派（阻塞等待结果）
            </Button>
            {running && <Loading label="子代理运行中…（可能耗时较长）" />}
            {err && <ErrBox msg={err} />}
            {result != null && (
              <div style={{ marginTop: 14 }}>
                <div style={{ color: OC.muted, fontSize: 13, marginBottom: 6 }}>
                  结果（childSessionId: {resultId}）
                </div>
                <pre style={{ ...mono, background: OC.bg, border: `1px solid ${OC.border}`, borderRadius: 10, padding: 12, color: OC.text, whiteSpace: 'pre-wrap', wordBreak: 'break-word', maxHeight: 360, overflow: 'auto' }}>
                  {result}
                </pre>
              </div>
            )}
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card
            title="生命周期事件"
            variant="borderless"
            style={{ background: OC.card, height: '100%' }}
            styles={{ header: { borderBottom: `1px solid ${OC.border}`, color: OC.textStrong } }}
            extra={
              <Button size="small" onClick={toggleListen}>
                {listening ? '停止监听' : '开始监听'}
              </Button>
            }
          >
            <div
              style={{
                ...mono,
                background: OC.bg,
                border: `1px solid ${OC.border}`,
                borderRadius: 10,
                padding: 12,
                height: 420,
                overflowY: 'auto',
                lineHeight: 1.7,
                fontSize: 11.5,
              }}
            >
              {events.length === 0 ? (
                <span style={{ color: OC.muted }}>（未监听 — 点击「开始监听」接收 SSE 事件）</span>
              ) : (
                events.map((e, i) => (
                  <div key={i}>
                    <span style={{ color: OC.muted }}>[{e.t}]</span>{' '}
                    <b style={{ color: '#f0c36d' }}>{e.type}</b> <span style={{ color: OC.text }}>{String(e.data).slice(0, 220)}</span>
                  </div>
                ))
              )}
            </div>
          </Card>
        </Col>
      </Row>

      {groupOrder.current.length > 0 && (
        <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
          {groupOrder.current.map((tid) => {
            const g = groups[tid]
            if (!g || g.mode !== 'critique') return null
            const n = g.n ?? Object.keys(g.branches).length
            return (
              <Col xs={24} lg={12} key={tid}>
                <Card
                  size="small"
                  variant="borderless"
                  style={{ background: OC.card, border: `1px solid ${OC.border}`, borderRadius: OC.radiusLg }}
                  title={
                    <span style={{ color: OC.textStrong, fontSize: 13 }}>
                      并行编排 <span style={mono}>{tid}</span>{' '}
                      <Tag color="purple" style={{ marginLeft: 4 }}>best-of-{n}</Tag>
                      {g.status === 'done' && <Tag color="success">已完成</Tag>}
                      {g.status === 'cancelled' && <Tag>已取消</Tag>}
                      {g.status === 'failed' && <Tag color="error">失败</Tag>}
                      {live(g) && <Tag color="processing">运行中</Tag>}
                    </span>
                  }
                  extra={live(g) && (
                    <Button size="small" danger icon={<StopOutlined />}
                      loading={cancelling === tid} onClick={() => cancelTask(tid)}>
                      取消全部
                    </Button>
                  )}
                >
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                    {Array.from({ length: n }, (_, i) => {
                      const b = g.branches[i]
                      return (
                        <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12.5, color: OC.text }}>
                          {!b || b.status === 'running'
                            ? <LoadingOutlined style={{ color: OC.accent }} />
                            : b.status === 'success'
                              ? <CheckCircleFilled style={{ color: '#22c55e' }} />
                              : <CloseCircleFilled style={{ color: '#ff6b6b' }} />}
                          <span style={{ minWidth: 52 }}>分支 {i + 1}</span>
                          {b?.status === 'failed'
                            ? <Tooltip title={b.error}><span style={{ color: '#ff6b6b' }}>失败：{String(b.error).slice(0, 60)}</span></Tooltip>
                            : b?.status === 'success'
                              ? <span style={{ color: OC.muted }}>
                                  {b.answerLength ?? 0} 字 · ↓{b.inputTokens ?? 0} ↑{b.outputTokens ?? 0}
                                </span>
                              : <span style={{ color: OC.muted }}>执行中…</span>}
                          {g.critic?.chosen === i + 1 && (
                            <Tag color="gold" icon={<CrownFilled />} style={{ marginLeft: 'auto' }}>critic 选中</Tag>
                          )}
                        </div>
                      )
                    })}
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12.5, color: OC.text, borderTop: `1px dashed ${OC.border}`, paddingTop: 6 }}>
                      {g.criticRunning
                        ? <><LoadingOutlined style={{ color: OC.accent }} /><span>critic 评选择优中…</span></>
                        : g.critic
                          ? <><CrownFilled style={{ color: '#d4a017' }} />
                              <span>
                                critic 结论：{g.critic.degraded
                                  ? <Tag color="orange">降级（取最长分支）</Tag>
                                  : <>选中方案 {g.critic.chosen}</>}
                              </span></>
                          : <span style={{ color: OC.muted }}>critic 待启动</span>}
                    </div>
                    {g.answer && (
                      <pre style={{ ...mono, margin: '4px 0 0', background: OC.bg, border: `1px solid ${OC.border}`, borderRadius: 8, padding: 8, color: OC.text, fontSize: 11.5, maxHeight: 160, overflow: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
                        {g.answer}
                      </pre>
                    )}
                    {g.error && <div style={{ color: '#ff6b6b', fontSize: 12 }}>{g.error}</div>}
                    {typeof g.totalTokens === 'number' && g.totalTokens > 0 && (
                      <div style={{ color: OC.muted, fontSize: 11.5 }}>
                        合计 ↓{g.inputTokens}（缓存 {g.cachedInputTokens ?? 0}）/ ↑{g.outputTokens} ·
                        {' '}总 {g.totalTokens} tokens · {g.modelCalls} 次模型调用 · 约 {n + 1}× 单跑成本
                      </div>
                    )}
                  </div>
                </Card>
              </Col>
            )
          })}
        </Row>
      )}
    </div>
  )
}
