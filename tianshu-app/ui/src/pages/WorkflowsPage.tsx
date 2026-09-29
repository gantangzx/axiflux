import { useRef, useState } from 'react'
import {
  Button,
  Card,
  Table,
  Space,
  Modal,
  Form,
  Input,
  Tag,
  Tabs,
  Descriptions,
  App as AntApp,
  Switch,
  Tooltip,
} from 'antd'
import { PlayCircleOutlined, ReloadOutlined, PlusOutlined, EditOutlined } from '@ant-design/icons'
import { api, getToken } from '../api'
import { useApi, Loading, ErrBox, fmt, mono, OC } from '../ui'
import GraphTopology, {
  type NodeStatus,
  type TopoEdge,
  type TopoNode,
} from '../components/GraphTopology'
import { WorkflowDesigner } from '../components/WorkflowDesigner'
import type { WDef } from '../workflow-types'
import { IS_ENTERPRISE } from '../edition'

type DetailNode = { id: string; type: string; label?: string }
type DetailEdge = {
  source: string
  target: string
  condition?: string | null
  conditional?: boolean
}

type GraphSummary = {
  name: string
  nodeCount: number
  edgeCount: number
  maxSteps: number
  nodes?: DetailNode[]
  edges?: DetailEdge[]
}

type GraphRunResult = {
  runId: string
  status: 'COMPLETED' | 'PAUSED' | 'FAILED'
  state: {
    variables: Record<string, unknown>
    outputs: Record<string, unknown>
    nodeVisits: Record<string, number>
  }
}

type RunSummary = {
  runId: string
  graphName: string
  nodeId: string
  userId: string
  sessionId: string
  reason: string
  createdAt: string
}

type Checkpoint = RunSummary & {
  state: GraphRunResult['state']
  agentId?: string
  forcedModel?: string
}

const STATUS_COLOR: Record<string, string> = {
  COMPLETED: 'green',
  PAUSED: 'orange',
  FAILED: 'red',
}

function Legend({ compact }: { compact?: boolean }) {
  const items: [string, string][] = [
    ['当前执行', OC.accent],
    ['已完成', '#7fd0a4'],
    ['暂停等待', '#e6b66a'],
    ['失败', '#e88585'],
  ]
  return (
    <Space size={14} style={{ marginTop: compact ? 8 : 10, width: '100%' }}>
      {items.map(([label, color]) => (
        <Space key={label} size={6}>
          <span
            style={{
              display: 'inline-block',
              width: 10,
              height: 10,
              borderRadius: 3,
              border: `1.5px solid ${color}`,
              background: OC.card,
            }}
          />
          <span style={{ color: OC.muted, fontSize: 12 }}>{label}</span>
        </Space>
      ))}
      <Space size={6}>
        <span style={{ color: '#d4a05a', fontSize: 14, lineHeight: 1 }}>- -</span>
        <span style={{ color: OC.muted, fontSize: 12 }}>条件边</span>
      </Space>
    </Space>
  )
}

function asJson(v: unknown): string {
  if (v == null) return ''
  if (typeof v === 'string') return v
  try {
    return JSON.stringify(v, null, 2)
  } catch {
    return String(v)
  }
}

export default function WorkflowsPage() {
  const { message } = AntApp.useApp()
  const graphs = useApi<GraphSummary[]>(() => api.get('/api/v1/workflows'), [])
  const runs = useApi<RunSummary[]>(() => api.get('/api/v1/workflows/runs'), [])

  const [detail, setDetail] = useState<GraphSummary | null>(null)
  const [target, setTarget] = useState<GraphSummary | null>(null)
  const [runForm] = Form.useForm()
  const [running, setRunning] = useState(false)
  const [live, setLive] = useState(false)
  const [result, setResult] = useState<GraphRunResult | null>(null)
  const [events, setEvents] = useState<string[]>([])

  const [cp, setCp] = useState<Checkpoint | null>(null)
  const [cpGraph, setCpGraph] = useState<GraphSummary | null>(null)
  const [resumeText, setResumeText] = useState('')
  const [resuming, setResuming] = useState(false)

  // Visual designer (EE): editing an existing graph or creating a new one.
  const [editing, setEditing] = useState<{ name: string; description: string; maxSteps: number; enabled: boolean } | null>(null)
  const [designerDef, setDesignerDef] = useState<WDef | null>(null)
  const [savingDef, setSavingDef] = useState(false)

  // Live node highlighting for the topology view.
  const [hl, setHl] = useState<Record<string, NodeStatus>>({})
  const cpNodeRef = useRef<string | null>(null)

  const resetHighlight = () => {
    cpNodeRef.current = null
    setHl({})
  }

  const applyEvent = (type: string, nodeId?: string) => {
    if (!nodeId) return
    setHl((prev) => {
      const next: Record<string, NodeStatus> = { ...prev }
      if (type === 'NODE_START') {
        if (cpNodeRef.current) next[cpNodeRef.current] = 'done'
        cpNodeRef.current = nodeId
        next[nodeId] = 'active'
      } else if (type === 'NODE_END') {
        next[nodeId] = 'done'
        if (cpNodeRef.current === nodeId) cpNodeRef.current = null
      } else if (type === 'PAUSED') {
        next[nodeId] = 'paused'
        cpNodeRef.current = nodeId
      } else if (type === 'ERROR') {
        if (cpNodeRef.current) next[cpNodeRef.current] = 'failed'
      } else if (type === 'COMPLETED') {
        if (cpNodeRef.current) next[cpNodeRef.current] = 'done'
        cpNodeRef.current = null
      }
      return next
    })
  }

  const openDetail = async (g: GraphSummary) => {
    try {
      const full = await api.get<GraphSummary>(`/api/v1/workflows/${encodeURIComponent(g.name)}`)
      setDetail(full)
    } catch (e: any) {
      message.error(e.message)
    }
  }

  const openRun = async (g: GraphSummary) => {
    setResult(null)
    setEvents([])
    resetHighlight()
    runForm.resetFields()
    try {
      const full = await api.get<GraphSummary>(
        `/api/v1/workflows/${encodeURIComponent(g.name)}`,
      )
      setTarget(full)
    } catch (e: any) {
      message.error(e.message)
    }
  }

  const collectSeed = (): { input?: unknown; variables?: Record<string, unknown> } => {
    const input = runForm.getFieldValue('input')?.trim()
    const rawVars = runForm.getFieldValue('variables')?.trim()
    let variables: Record<string, unknown> | undefined
    if (rawVars) {
      variables = JSON.parse(rawVars) as Record<string, unknown>
    }
    const parsedInput = input ? safeParse(input) : undefined
    return { input: parsedInput, variables }
  }

  const submitRun = async () => {
    if (!target) return
    setRunning(true)
    setResult(null)
    setEvents([])
    resetHighlight()
    try {
      const seed = collectSeed()
      if (live) {
        await streamRun(
          `/api/v1/workflows/${encodeURIComponent(target.name)}/runs/stream`,
          seed,
          (final) => {
            setResult(final)
            if (final.status === 'PAUSED') runs.reload()
          },
        )
      } else {
        const r = await api.post<GraphRunResult>(
          `/api/v1/workflows/${encodeURIComponent(target.name)}/runs`,
          seed,
        )
        setResult(r)
        if (r.status === 'PAUSED') runs.reload()
      }
      message.success('运行结束')
    } catch (e: any) {
      message.error(e.message)
    } finally {
      setRunning(false)
    }
  }

  const streamRun = async (
    path: string,
    body: unknown,
    onDone: (r: GraphRunResult) => void,
  ): Promise<void> => {
    const res = await fetch(path, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(getToken() ? { Authorization: `Bearer ${getToken()}` } : {}),
      },
      body: JSON.stringify(body),
    })
    if (!res.ok || !res.body) throw new Error(`stream failed: ${res.status}`)
    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buf = ''
    let final: GraphRunResult | null = null
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true })
      let idx
      while ((idx = buf.indexOf('\n\n')) >= 0) {
        const chunk = buf.slice(0, idx)
        buf = buf.slice(idx + 2)
        const lines = chunk.split('\n')
        const event = lines.find((l) => l.startsWith('event:'))?.slice(6).trim()
        const dataLine = lines.find((l) => l.startsWith('data:'))
        if (!dataLine) continue
        const payload = JSON.parse(dataLine.slice(5).trim()) as GraphEventWire
        if (event) {
          const detail = payload.message ? ` — ${payload.message}` : ''
          const node = payload.nodeId ? ` [${payload.nodeId}]` : ''
          setEvents((ev) => [...ev, `${event}${node}${detail}`])
          applyEvent(payload.type, payload.nodeId)
        }
        if (payload.type === 'COMPLETED' || payload.type === 'ERROR' || payload.type === 'PAUSED') {
          final = payloadToResult(payload)
        }
      }
    }
    if (final) onDone(final)
  }

  const openCheckpoint = async (r: RunSummary) => {
    try {
      const [full, graphDetail] = await Promise.all([
        api.get<Checkpoint>(`/api/v1/workflows/runs/${encodeURIComponent(r.runId)}`),
        api.get<GraphSummary>(`/api/v1/workflows/${encodeURIComponent(r.graphName)}`),
      ])
      setCp(full)
      setCpGraph(graphDetail)
      cpNodeRef.current = full.nodeId
      setHl({ [full.nodeId]: 'paused' })
      setResumeText('')
    } catch (e: any) {
      message.error(e.message)
    }
  }

  const submitResume = async () => {
    if (!cp) return
    setResuming(true)
    setEvents([])
    resetHighlight()
    try {
      const payload = resumeText.trim() ? safeParse(resumeText.trim()) : null
      if (live) {
        await streamRun(
          `/api/v1/workflows/runs/${encodeURIComponent(cp.runId)}/resume/stream`,
          { payload },
          (final) => {
            setResult(final)
            runs.reload()
          },
        )
      } else {
        const r = await api.post<GraphRunResult>(
          `/api/v1/workflows/runs/${encodeURIComponent(cp.runId)}/resume`,
          { payload },
        )
        setResult(r)
        runs.reload()
      }
      message.success('恢复完成')
      setCp(null)
    } catch (e: any) {
      message.error(e.message)
    } finally {
      setResuming(false)
    }
  }

  const openNewDesigner = async () => {
    try {
      const draft = await api.get<{ definition: WDef }>('/api/v1/admin/workflows/new')
      setEditing({ name: '', description: '', maxSteps: 50, enabled: true })
      setDesignerDef(draft.definition)
    } catch (e: any) {
      message.error(e.message)
    }
  }

  const openEditor = async (g: GraphSummary) => {
    try {
      const full = await api.get<{
        name: string
        description?: string
        maxSteps: number
        enabled: boolean
        definition: WDef
      }>(`/api/v1/admin/workflows/${encodeURIComponent(g.name)}`)
      setEditing({
        name: full.name,
        description: full.description ?? '',
        maxSteps: full.maxSteps,
        enabled: full.enabled,
      })
      setDesignerDef(full.definition)
    } catch (e: any) {
      message.error(e.message)
    }
  }

  const submitDefinition = async () => {
    if (!editing || !designerDef) return
    const name = editing.name.trim()
    if (!name) {
      message.warning('请填写工作流名称')
      return
    }
    if (!/^[a-z0-9][a-z0-9_-]{0,127}$/i.test(name)) {
      message.warning('名称只能含字母、数字、下划线、连字符，且以字母或数字开头')
      return
    }
    setSavingDef(true)
    try {
      await api.put(`/api/v1/admin/workflows/${encodeURIComponent(name)}`, {
        description: editing.description,
        maxSteps: editing.maxSteps,
        enabled: editing.enabled,
        definition: designerDef,
      })
      message.success(`工作流「${name}」已保存`)
      setEditing(null)
      setDesignerDef(null)
      graphs.reload()
      runs.reload()
    } catch (e: any) {
      message.error(e.message)
    } finally {
      setSavingDef(false)
    }
  }

  return (
    <div style={{ padding: 24, maxWidth: 1240, margin: '0 auto' }}>
      <Card
        variant="borderless"
        style={{ background: OC.card }}
        styles={{ header: { borderBottom: `1px solid ${OC.border}`, color: OC.textStrong } }}
        title="工作流编排"
        extra={
          <Space>
            <Tooltip title="实时模式：通过 SSE 展示每个节点事件">
              <Space size={6}>
                <span style={{ color: OC.muted, fontSize: 12.5 }}>实时</span>
                <Switch size="small" checked={live} onChange={setLive} />
              </Space>
            </Tooltip>
            {IS_ENTERPRISE && (
              <Button type="primary" icon={<PlusOutlined />} onClick={openNewDesigner}>
                新建工作流
              </Button>
            )}
            <Button icon={<ReloadOutlined />} onClick={() => { graphs.reload(); runs.reload() }}>
              刷新
            </Button>
          </Space>
        }
      >
        <Tabs
          items={[
            {
              key: 'graphs',
              label: `图定义（${graphs.data?.length ?? 0}）`,
              children: (
                <>
                  {graphs.error && <ErrBox msg={graphs.error} />}
                  {graphs.loading ? (
                    <Loading />
                  ) : (
                    <Table
                      size="small"
                      rowKey="name"
                      dataSource={graphs.data || []}
                      pagination={false}
                      columns={[
                        {
                          title: '名称',
                          dataIndex: 'name',
                          render: (v: string) => (
                            <span style={{ ...mono, fontWeight: 600, color: OC.textStrong }}>{v}</span>
                          ),
                        },
                        { title: '节点', dataIndex: 'nodeCount' },
                        { title: '边', dataIndex: 'edgeCount' },
                        { title: '步数上限', dataIndex: 'maxSteps' },
                        {
                          title: '',
                          align: 'right',
                          render: (_, g) => (
                            <Space>
                              <Button size="small" onClick={() => openDetail(g)}>
                                详情
                              </Button>
                              {IS_ENTERPRISE && (
                                <Tooltip title="在可视化画布中编辑">
                                  <Button size="small" icon={<EditOutlined />} onClick={() => openEditor(g)}>
                                    编辑
                                  </Button>
                                </Tooltip>
                              )}
                              <Button
                                size="small"
                                type="primary"
                                icon={<PlayCircleOutlined />}
                                onClick={() => openRun(g)}
                              >
                                运行
                              </Button>
                            </Space>
                          ),
                        },
                      ]}
                    />
                  )}
                </>
              ),
            },
            {
              key: 'runs',
              label: `暂停中的运行（${runs.data?.length ?? 0}）`,
              children: (
                <>
                  {runs.error && <ErrBox msg={runs.error} />}
                  {runs.loading ? (
                    <Loading />
                  ) : (
                    <Table
                      size="small"
                      rowKey="runId"
                      dataSource={runs.data || []}
                      pagination={false}
                      columns={[
                        {
                          title: 'Run ID',
                          dataIndex: 'runId',
                          render: (v: string) => <span style={mono}>{v.slice(0, 16)}…</span>,
                        },
                        {
                          title: '图',
                          dataIndex: 'graphName',
                          render: (v: string) => <span style={mono}>{v}</span>,
                        },
                        {
                          title: '暂停节点',
                          dataIndex: 'nodeId',
                          render: (v: string) => <Tag color="orange">{v}</Tag>,
                        },
                        { title: '原因/键', dataIndex: 'reason', render: (v: string) => <span style={mono}>{v}</span> },
                        { title: '创建于', dataIndex: 'createdAt', render: (v: string) => fmt(v) },
                        {
                          title: '',
                          align: 'right',
                          render: (_, r) => (
                            <Button size="small" type="primary" onClick={() => openCheckpoint(r)}>
                              查看 / 恢复
                            </Button>
                          ),
                        },
                      ]}
                    />
                  )}
                </>
              ),
            },
          ]}
        />
      </Card>

      {/* Graph detail */}
      <Modal
        title={detail ? `图定义 · ${detail.name}` : ''}
        open={!!detail}
        onCancel={() => setDetail(null)}
        footer={null}
        width={860}
      >
        {detail && (
          <>
            <Descriptions size="small" column={3} style={{ marginBottom: 12 }}>
              <Descriptions.Item label="节点">{detail.nodeCount}</Descriptions.Item>
              <Descriptions.Item label="边">{detail.edgeCount}</Descriptions.Item>
              <Descriptions.Item label="步数上限">{detail.maxSteps}</Descriptions.Item>
            </Descriptions>
            <GraphTopology
              nodes={(detail.nodes || []) as TopoNode[]}
              edges={(detail.edges || []) as TopoEdge[]}
            />
            <Legend />
          </>
        )}
      </Modal>

      {/* Start run */}
      <Modal
        title={target ? `运行 · ${target.name}` : ''}
        open={!!target}
        onCancel={() => setTarget(null)}
        onOk={submitRun}
        confirmLoading={running}
        okText="开始"
        cancelText="取消"
        width={700}
      >
        {target && (
          <GraphTopology
            nodes={(target.nodes || []) as TopoNode[]}
            edges={(target.edges || []) as TopoEdge[]}
            statusById={live ? hl : undefined}
            height={240}
          />
        )}
        <Form form={runForm} layout="vertical" style={{ marginTop: 8 }}>
          <Form.Item name="input" label="初始输入（input，原始文本或 JSON）">
            <Input.TextArea rows={2} style={mono} placeholder="hello workflow" />
          </Form.Item>
          <Form.Item
            name="variables"
            label="额外变量（可选，JSON 对象）"
            extra="会合并进初始 GraphState"
          >
            <Input.TextArea rows={3} style={mono} placeholder='{"key":"value"}' />
          </Form.Item>
        </Form>
        <EventLog events={events} />
        <ResultView result={result} />
      </Modal>

      {/* Inspect / resume checkpoint */}
      <Modal
        title={cp ? `暂停运行 · ${cp.graphName}` : ''}
        open={!!cp}
        onCancel={() => {
          setCp(null)
          setCpGraph(null)
          resetHighlight()
        }}
        footer={null}
        width={780}
      >
        {cp && (
          <>
            {cpGraph && (
              <>
                <GraphTopology
                  nodes={(cpGraph.nodes || []) as TopoNode[]}
                  edges={(cpGraph.edges || []) as TopoEdge[]}
                  statusById={live ? hl : { [cp.nodeId]: 'paused' }}
                  height={230}
                />
                <Legend compact />
              </>
            )}
            <Descriptions size="small" column={2} style={{ marginBottom: 12 }}>
              <Descriptions.Item label="Run ID">
                <span style={mono}>{cp.runId}</span>
              </Descriptions.Item>
              <Descriptions.Item label="暂停节点">
                <Tag color="orange">{cp.nodeId}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="会话">
                <span style={mono}>{cp.sessionId}</span>
              </Descriptions.Item>
              <Descriptions.Item label="原因/恢复键">
                <span style={mono}>{cp.reason}</span>
              </Descriptions.Item>
              <Descriptions.Item label="创建于">{fmt(cp.createdAt)}</Descriptions.Item>
              <Descriptions.Item label="强制模型">{cp.forcedModel || '—'}</Descriptions.Item>
            </Descriptions>

            <div style={{ color: OC.muted, fontSize: 12.5, marginBottom: 4 }}>暂停时状态变量</div>
            <Input.TextArea
              readOnly
              rows={7}
              style={{ ...mono, marginBottom: 14 }}
              value={asJson(cp.state?.variables)}
            />

            <div style={{ color: OC.muted, fontSize: 12.5, marginBottom: 4 }}>
              恢复 payload（写入键 <span style={mono}>{cp.reason}</span>，原始文本或 JSON）
            </div>
            <Input.TextArea
              rows={2}
              style={mono}
              value={resumeText}
              onChange={(e) => setResumeText(e.target.value)}
              placeholder="approved"
            />
            <EventLog events={events} />
            <ResultView result={result} />
            <Space style={{ marginTop: 14, justifyContent: 'flex-end', width: '100%' }}>
              <Button onClick={() => setCp(null)}>取消</Button>
              <Button type="primary" loading={resuming} onClick={submitResume}>
                恢复运行
              </Button>
            </Space>
          </>
        )}
      </Modal>

      {/* Visual designer (EE) */}
      <Modal
        title={editing?.name ? `编辑工作流 · ${editing.name}` : '新建工作流'}
        open={!!editing}
        onCancel={() => { setEditing(null); setDesignerDef(null) }}
        onOk={submitDefinition}
        confirmLoading={savingDef}
        okText="保存"
        cancelText="取消"
        width={1100}
        destroyOnClose
      >
        {editing && designerDef && (
          <>
            <Space size={12} style={{ marginBottom: 12, width: '100%' }} wrap>
              <Input
                addonBefore="名称"
                value={editing.name}
                disabled={!!editing.name}
                style={{ width: 260 }}
                placeholder="my_workflow"
                onChange={(e) => setEditing({ ...editing, name: e.target.value })}
              />
              <Input
                addonBefore="描述"
                value={editing.description}
                style={{ width: 320 }}
                onChange={(e) => setEditing({ ...editing, description: e.target.value })}
              />
              <Input
                addonBefore="步数上限"
                type="number"
                value={editing.maxSteps}
                style={{ width: 150 }}
                onChange={(e) => setEditing({ ...editing, maxSteps: Number(e.target.value) || 50 })}
              />
              <Space size={6}>
                <span style={{ color: OC.muted, fontSize: 12.5 }}>启用</span>
                <Switch
                  checked={editing.enabled}
                  onChange={(v) => setEditing({ ...editing, enabled: v })}
                />
              </Space>
            </Space>
            <div style={{ height: 520 }}>
              <WorkflowDesigner
                definition={designerDef}
                workflowName={editing.name || undefined}
                onChange={setDesignerDef}
              />
            </div>
          </>
        )}
      </Modal>
    </div>
  )
}

function EventLog({ events }: { events: string[] }) {
  if (events.length === 0) return null
  return (
    <Input.TextArea
      readOnly
      rows={Math.min(8, events.length + 1)}
      style={{ ...mono, marginTop: 6, background: OC.bgElevated }}
      value={events.join('\n')}
    />
  )
}

function ResultView({ result }: { result: GraphRunResult | null }) {
  if (!result) return null
  const hasState =
    result.state &&
    (Object.keys(result.state.variables || {}).length > 0 ||
      Object.keys(result.state.outputs || {}).length > 0)
  return (
    <div style={{ marginTop: 12 }}>
      <Space style={{ marginBottom: 6 }}>
        <Tag color={STATUS_COLOR[result.status] || 'default'}>{result.status}</Tag>
        {result.runId ? <span style={mono}>{result.runId}</span> : null}
      </Space>
      {hasState ? (
        <Input.TextArea
          readOnly
          rows={8}
          style={{ ...mono, background: OC.bgElevated }}
          value={[
            '── variables ──',
            asJson(result.state.variables),
            '',
            '── node outputs ──',
            asJson(result.state.outputs),
          ].join('\n')}
        />
      ) : (
        <div style={{ color: OC.muted, fontSize: 12.5 }}>
          实时模式不返回最终状态快照；{result.status === 'PAUSED'
            ? '可在「暂停中的运行」页签查看暂停时状态并恢复。'
            : '如需查看最终状态，请关闭顶部「实时」开关后重跑。'}
        </div>
      )}
    </div>
  )
}

type GraphEventWire = {
  type: keyof typeof EVENT_STATUS
  nodeId?: string
  message?: string
  state?: GraphRunResult['state']
}

const EVENT_STATUS = {
  COMPLETED: true,
  PAUSED: true,
  ERROR: true,
} as const

function payloadToResult(p: GraphEventWire): GraphRunResult {
  const status: GraphRunResult['status'] =
    p.type === 'PAUSED' ? 'PAUSED' : p.type === 'ERROR' ? 'FAILED' : 'COMPLETED'
  return {
    runId: '',
    status,
    state: p.state ?? { variables: {}, outputs: {}, nodeVisits: {} },
  }
}

function safeParse(s: string): unknown {
  try {
    return JSON.parse(s)
  } catch {
    return s
  }
}
