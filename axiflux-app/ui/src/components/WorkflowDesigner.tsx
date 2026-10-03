import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import '@xyflow/react/dist/style.css'
import {
  addEdge,
  Background,
  BackgroundVariant,
  Connection,
  Controls,
  Edge,
  MiniMap,
  Node,
  ReactFlow,
  ReactFlowProvider,
  MarkerType,
  useEdgesState,
  useNodesState,
  useReactFlow,
} from '@xyflow/react'
import {
  Button,
  Dropdown,
  Empty,
  Input,
  Modal,
  Space,
  Tag,
  Tooltip,
  message,
} from 'antd'
import {
  BranchesOutlined,
  CheckOutlined,
  CopyOutlined,
  DeleteOutlined,
  DownloadOutlined,
  HistoryOutlined,
  LayoutOutlined,
  PauseOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  RobotOutlined,
  ShareAltOutlined,
  StopOutlined,
  ThunderboltOutlined,
  ToolOutlined,
  UndoOutlined,
  RedoOutlined,
  UpOutlined,
  UploadOutlined,
  ExclamationCircleFilled,
} from '@ant-design/icons'
import type { WDef, WNode } from '../workflow-types'
import { NodeForm } from './NodeForm'
import { NodeData, NodeStatus, useWorkflowActions } from './useWorkflowActions'

const NODE_TYPES = ['AGENT', 'TOOL', 'CONDITION', 'PARALLEL', 'JOIN', 'PAUSE', 'APPROVAL', 'END'] as const
type NodeType = (typeof NODE_TYPES)[number]
const ADVANCED = new Set(['PARALLEL', 'APPROVAL', 'PAUSE'])

const STATUS_COLORS: Record<NodeStatus, { border: string; glow?: string }> = {
  idle: { border: '#2a2e3a' },
  running: { border: '#ff5c5c', glow: '0 0 0 3px rgba(255,92,92,.35)' },
  ok: { border: '#3fbf7f', glow: '0 0 0 3px rgba(63,191,127,.30)' },
  error: { border: '#ff4d4f', glow: '0 0 0 3px rgba(255,77,79,.35)' },
}

export const nodeTypeIcon = (t: string) => {
  if (t === 'TOOL') return <ToolOutlined />
  if (t === 'CONDITION') return <BranchesOutlined />
  if (t === 'PARALLEL') return <ShareAltOutlined />
  if (t === 'PAUSE') return <PauseOutlined />
  if (t === 'APPROVAL') return <ExclamationCircleFilled />
  if (t === 'END') return <StopOutlined />
  return <RobotOutlined />
}

function nodeVisual(raw: WNode): Node<NodeData> {
  return {
    id: raw.id,
    type: 'workflowNode',
    position: { x: raw.x ?? 0, y: raw.y ?? 0 },
    data: { label: raw.label || raw.id, nodeType: raw.type, raw, status: 'idle' },
    style: {
      padding: '8px 12px',
      borderRadius: 8,
      border: '1px solid #2a2e3a',
      background: '#191c24',
      color: '#f4f4f5',
      minWidth: 130,
    },
  }
}

function edgeVisual(e: WDef['edges'][number], i: number): Edge {
  return {
    id: `e${i}_${e.source}_${e.target}_${e.condition ?? ''}`,
    source: e.source,
    target: e.target,
    label: e.condition,
    data: { condition: e.condition },
    markerEnd: { type: MarkerType.ArrowClosed, color: '#5a6072' },
    style: { stroke: '#5a6072' },
  }
}

function DesignerInner({ definition, onChange, workflowName }: {
  definition: WDef
  onChange: (d: WDef) => void
  workflowName?: string
}) {
  const [nodes, setNodes, onNodesChange] = useNodesState<Node<NodeData>>([])
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const rf = useReactFlow()
  const actions = useWorkflowActions(setNodes)

  const historyRef = useRef<WNode[][]>([])
  const futureRef = useRef<WNode[][]>([])
  const [, bump] = useState(0)
  const skipEmit = useRef(false)

  const buildDef = useCallback((ns: Node<NodeData>[], es: Edge[]): WDef => ({
    nodes: ns.map((n) => ({ ...(n.data.raw as WNode), x: n.position.x, y: n.position.y })),
    edges: es.map((e) => ({
      source: e.source,
      target: e.target,
      condition: (e.data?.condition as string) || undefined,
    })),
  }), [])

  const applyDef = useCallback((def: WDef) => {
    skipEmit.current = true
    setNodes(def.nodes.map(nodeVisual))
    setEdges(def.edges.map(edgeVisual))
  }, [setNodes, setEdges])

  const loadedKey = useRef('')
  useEffect(() => {
    const key = workflowName ?? '__unsaved__'
    if (loadedKey.current === key) return
    loadedKey.current = key
    applyDef(definition)
  }, [definition, workflowName, applyDef])

  useEffect(() => {
    if (skipEmit.current) {
      skipEmit.current = false
      return
    }
    onChange(buildDef(nodes, edges))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [nodes, edges])

  const pushHistory = useCallback(() => {
    historyRef.current.push(nodes.map((n) => ({ ...(n.data.raw as WNode) })))
    if (historyRef.current.length > 100) historyRef.current.shift()
    futureRef.current = []
    bump((v) => v + 1)
  }, [nodes])

  const onConnect = useCallback((conn: Connection) => {
    pushHistory()
    setEdges((es) => addEdge({ ...conn, markerEnd: { type: MarkerType.ArrowClosed } }, es))
  }, [pushHistory, setEdges])

  const addNode = useCallback((type: NodeType) => {
    pushHistory()
    const raw: WNode = {
      id: `node_${Date.now().toString(36)}`,
      type,
      label: type[0] + type.slice(1).toLowerCase(),
      query: type === 'AGENT' ? '' : undefined,
      outputVar: type === 'AGENT' || type === 'TOOL' ? 'result' : undefined,
      x: 140 + Math.random() * 100,
      y: 90 + nodes.length * 16,
    }
    setNodes((ns) => [...ns, nodeVisual(raw)])
    setSelectedId(raw.id)
  }, [pushHistory, nodes.length, setNodes])

  const duplicateNode = useCallback((raw: WNode) => {
    pushHistory()
    const copy: WNode = {
      ...raw,
      id: `node_${Date.now().toString(36)}`,
      label: `${raw.label || raw.id} 副本`,
      x: (raw.x ?? 0) + 44,
      y: (raw.y ?? 0) + 44,
    }
    setNodes((ns) => [...ns, nodeVisual(copy)])
    setSelectedId(copy.id)
  }, [pushHistory, setNodes])

  const removeNode = useCallback((id: string) => {
    pushHistory()
    setNodes((ns) => ns.filter((n) => n.id !== id))
    setEdges((es) => es.filter((e) => e.source !== id && e.target !== id))
    setSelectedId(null)
  }, [pushHistory, setNodes, setEdges])

  const updateNode = useCallback((patch: Partial<WNode>) => {
    if (!selectedId) return
    setNodes((ns) => ns.map((n) => {
      if (n.id !== selectedId) return n
      const raw = { ...(n.data.raw as WNode), ...patch }
      return { ...n, data: { ...n.data, raw, label: raw.label } }
    }))
  }, [selectedId, setNodes])

  const autoLayout = useCallback(() => {
    pushHistory()
    const indeg = new Map(nodes.map((n) => [n.id, 0]))
    const adj = new Map(nodes.map((n) => [n.id, [] as string[]]))
    edges.forEach((e) => {
      if (indeg.has(e.target) && adj.has(e.source)) {
        indeg.set(e.target, (indeg.get(e.target) ?? 0) + 1)
        adj.get(e.source)!.push(e.target)
      }
    })
    const depth = new Map<string, number>()
    const q: string[] = []
    nodes.forEach((n) => {
      if ((indeg.get(n.id) ?? 0) === 0) {
        q.push(n.id)
        depth.set(n.id, 0)
      }
    })
    while (q.length) {
      const cur = q.shift()!
      for (const nx of adj.get(cur) ?? []) {
        depth.set(nx, Math.max(depth.get(nx) ?? 0, (depth.get(cur) ?? 0) + 1))
        indeg.set(nx, (indeg.get(nx) ?? 1) - 1)
        if ((indeg.get(nx) ?? 0) === 0) q.push(nx)
      }
    }
    const byCol = new Map<number, string[]>()
    depth.forEach((d, id) => {
      if (!byCol.has(d)) byCol.set(d, [])
      byCol.get(d)!.push(id)
    })
    setNodes((ns) => ns.map((n) => {
      const d = depth.get(n.id) ?? 0
      const row = (byCol.get(d) ?? []).indexOf(n.id)
      return { ...n, position: { x: 60 + d * 220, y: 60 + row * 110 } }
    }))
    setTimeout(() => rf.fitView({ padding: 0.2 }), 60)
  }, [pushHistory, nodes, edges, setNodes, rf])

  const restoreSnapshot = useCallback((snap: WNode[]) => {
    const pos = new Map(nodes.map((n) => [n.id, n.position]))
    const ids = new Set(snap.map((s) => s.id))
    const merged = snap.map((s) => {
      const p = pos.get(s.id)
      return p ? { ...s, x: p.x, y: p.y } : s
    })
    const keptEdges = edges
      .filter((e) => ids.has(e.source) && ids.has(e.target))
      .map((e) => ({ source: e.source, target: e.target, condition: (e.data?.condition as string) || undefined }))
    applyDef({ nodes: merged, edges: keptEdges })
  }, [nodes, edges, applyDef])

  const undo = useCallback(() => {
    const snap = historyRef.current.pop()
    if (!snap) return
    futureRef.current.push(nodes.map((n) => ({ ...(n.data.raw as WNode) })))
    restoreSnapshot(snap)
    bump((v) => v + 1)
  }, [nodes, restoreSnapshot])

  const redo = useCallback(() => {
    const snap = futureRef.current.pop()
    if (!snap) return
    historyRef.current.push(nodes.map((n) => ({ ...(n.data.raw as WNode) })))
    restoreSnapshot(snap)
    bump((v) => v + 1)
  }, [nodes, restoreSnapshot])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement)?.tagName
      if (tag === 'INPUT' || tag === 'TEXTAREA') return
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z') {
        e.preventDefault()
        if (e.shiftKey) redo()
        else undo()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [undo, redo])

  const selectedNode = nodes.find((n) => n.id === selectedId)?.data.raw as WNode | undefined

  const nodeTypes = useMemo(() => ({
    workflowNode: ({ data }: { data: NodeData }) => (
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontWeight: 500 }}>
        <span style={{ color: ADVANCED.has(data.nodeType) ? '#ff5c5c' : '#8b8b94' }}>
          {nodeTypeIcon(data.nodeType)}
        </span>
        <span>{data.label}</span>
      </div>
    ),
  }), [])

  // YAML modal state
  const [yamlOpen, setYamlOpen] = useState(false)
  const [yamlMode, setYamlMode] = useState<'export' | 'import'>('export')
  const [yamlText, setYamlText] = useState('')
  const [yamlBusy, setYamlBusy] = useState(false)

  const openExport = async () => {
    setYamlMode('export')
    setYamlOpen(true)
    setYamlBusy(true)
    try {
      setYamlText(await actions.yamlExport(buildDef(nodes, edges)))
    } catch (e) {
      message.error('导出失败：' + String((e as Error).message))
    } finally {
      setYamlBusy(false)
    }
  }
  const openImport = () => {
    setYamlMode('import')
    setYamlText('')
    setYamlOpen(true)
  }
  const doImport = async () => {
    setYamlBusy(true)
    try {
      const def = await actions.yamlImport(yamlText)
      pushHistory()
      applyDef(def)
      setYamlOpen(false)
      message.success('YAML 已导入画布（保存后生效）')
    } catch (e) {
      message.error('导入失败：' + String((e as Error).message))
    } finally {
      setYamlBusy(false)
    }
  }

  // Version modal state
  const [histOpen, setHistOpen] = useState(false)
  const [versions, setVersions] = useState<Array<Record<string, unknown>>>([])
  const [histLoading, setHistLoading] = useState(false)
  const openHistory = async () => {
    if (!workflowName) {
      message.info('请先保存工作流后再查看版本历史')
      return
    }
    setHistOpen(true)
    setHistLoading(true)
    try {
      setVersions(await actions.listVersions(workflowName))
    } catch {
      message.error('加载版本失败')
    } finally {
      setHistLoading(false)
    }
  }
  const doRestore = async (v: number) => {
    try {
      const def = await actions.restoreVersion(workflowName!, v)
      applyDef(def)
      setHistOpen(false)
      message.success(`已回滚到 v${v}（作为新版本保存）`)
    } catch (e) {
      message.error('回滚失败：' + String((e as Error).message))
    }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12, height: '100%' }}>
      <Space wrap size={6}>
        <Dropdown menu={{
          items: NODE_TYPES.map((t) => ({
            key: t,
            label: <Space>{nodeTypeIcon(t)}{t}{ADVANCED.has(t) && <Tag color="volcano">高级</Tag>}</Space>,
            onClick: () => addNode(t),
          })),
        }}>
          <Button type="primary" icon={<PlusOutlined />}>添加节点</Button>
        </Dropdown>
        <Tooltip title="自动布局"><Button icon={<LayoutOutlined />} onClick={autoLayout} /></Tooltip>
        <Tooltip title="撤销 Ctrl+Z"><Button icon={<UndoOutlined />} onClick={undo} disabled={!historyRef.current.length} /></Tooltip>
        <Tooltip title="重做 Ctrl+Shift+Z"><Button icon={<RedoOutlined />} onClick={redo} disabled={!futureRef.current.length} /></Tooltip>
        <Tooltip title="复制选中节点"><Button icon={<CopyOutlined />} onClick={() => selectedNode && duplicateNode(selectedNode)} disabled={!selectedNode} /></Tooltip>
        <Tooltip title="删除选中节点"><Button danger icon={<DeleteOutlined />} onClick={() => selectedId && removeNode(selectedId)} disabled={!selectedId} /></Tooltip>
        <span style={{ width: 8 }} />
        {actions.running ? (
          <Button danger icon={<StopOutlined />} onClick={actions.stopRun}>停止</Button>
        ) : (
          <Dropdown menu={{
            items: [
              { key: 'all', icon: <PlayCircleOutlined />, label: '试运行整个工作流', onClick: () => actions.runTest(buildDef(nodes, edges), undefined, STATUS_COLORS) },
              { key: 'single', icon: <ThunderboltOutlined />, label: '试运行选中节点', disabled: !selectedNode, onClick: () => actions.runTest(buildDef(nodes, edges), selectedId!, STATUS_COLORS) },
            ],
          }}>
            <Button style={{ color: '#ff5c5c', borderColor: '#ff5c5c' }} icon={<ThunderboltOutlined />}>试运行</Button>
          </Dropdown>
        )}
        <Button icon={<HistoryOutlined />} onClick={openHistory}>版本</Button>
        <Dropdown menu={{
          items: [
            { key: 'exp', icon: <DownloadOutlined />, label: '导出 YAML', onClick: openExport },
            { key: 'imp', icon: <UploadOutlined />, label: '导入 YAML', onClick: openImport },
          ],
        }}>
          <Button icon={<BranchesOutlined />}>YAML</Button>
        </Dropdown>
      </Space>

      <div style={{
        display: 'grid',
        gridTemplateColumns: selectedNode ? '1fr 320px' : '1fr',
        gap: 12,
        flex: 1,
        minHeight: 0,
      }}>
        <div style={{ height: '100%', border: '1px solid #23262f', borderRadius: 10, overflow: 'hidden', background: '#0e1015' }}>
          <ReactFlow
            nodes={nodes}
            edges={edges}
            nodeTypes={nodeTypes}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            onNodeClick={(_, node) => setSelectedId(node.id)}
            onPaneClick={() => setSelectedId(null)}
            fitView
            proOptions={{ hideAttribution: true }}
          >
            <Background variant={BackgroundVariant.Dots} gap={20} size={1} color="#23262f" />
            <Controls showInteractive={false} />
            <MiniMap
              pannable
              zoomable
              nodeColor={(n) => {
                switch ((n.data as NodeData).status) {
                  case 'running': return '#ff5c5c'
                  case 'ok': return '#3fbf7f'
                  case 'error': return '#ff4d4f'
                  default: return '#2f3340'
                }
              }}
              maskColor="rgba(14,16,21,.7)"
              style={{ background: '#161920', border: '1px solid #23262f', borderRadius: 8 }}
            />
          </ReactFlow>
        </div>

        {selectedNode && (
          <div style={{ overflowY: 'auto', paddingRight: 4 }}>
            <NodeForm node={selectedNode} onChange={updateNode} onRemove={() => removeNode(selectedNode.id)} />
          </div>
        )}
      </div>

      <Modal
        title={yamlMode === 'export' ? '导出为 YAML' : '从 YAML 导入'}
        open={yamlOpen}
        width={680}
        onCancel={() => setYamlOpen(false)}
        footer={yamlMode === 'export' ? [
          <Button key="copy" icon={<CopyOutlined />} onClick={() => { navigator.clipboard.writeText(yamlText); message.success('已复制') }}>复制</Button>,
          <Button key="dl" icon={<DownloadOutlined />} onClick={() => {
            const blob = new Blob([yamlText], { type: 'text/yaml' })
            const a = document.createElement('a')
            a.href = URL.createObjectURL(blob)
            a.download = `${workflowName || 'workflow'}.yml`
            a.click()
          }}>下载 .yml</Button>,
          <Button key="close" onClick={() => setYamlOpen(false)}>关闭</Button>,
        ] : [
          <Button key="cancel" onClick={() => setYamlOpen(false)}>取消</Button>,
          <Button key="ok" type="primary" icon={<UpOutlined />} loading={yamlBusy} onClick={doImport}>导入到画布</Button>,
        ]}
      >
        <Input.TextArea
          rows={18}
          value={yamlText}
          onChange={(e) => setYamlText(e.target.value)}
          style={{ fontFamily: 'monospace', fontSize: 12 }}
          placeholder={yamlMode === 'import' ? '粘贴 nodes/edges 形式的 YAML…' : ''}
        />
      </Modal>

      <Modal
        title={<Space><HistoryOutlined />版本历史{workflowName ? ` · ${workflowName}` : ''}</Space>}
        open={histOpen}
        width={620}
        onCancel={() => setHistOpen(false)}
        footer={<Button onClick={() => setHistOpen(false)}>关闭</Button>}
      >
        {histLoading ? (
          <div style={{ padding: 24, textAlign: 'center', color: '#8b8b94' }}>加载中…</div>
        ) : versions.length === 0 ? (
          <Empty description="暂无历史版本" />
        ) : (
          <Space direction="vertical" style={{ width: '100%' }}>
            {versions.map((v) => (
              <div key={String(v.version)} style={{
                display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                padding: '10px 12px', border: '1px solid #23262f', borderRadius: 8, background: '#161920',
              }}>
                <Space>
                  <Tag color="blue">v{String(v.version)}</Tag>
                  <span>{String(v.description || '（无描述）')}</span>
                  {v.enabled === false && <Tag>已停用</Tag>}
                </Space>
                <Space size={4}>
                  <span style={{ color: '#8b8b94', fontSize: 12 }}>{String(v.createdAt ?? '')}</span>
                  <Button size="small" icon={<CheckOutlined />} onClick={() => doRestore(Number(v.version))}>回滚</Button>
                </Space>
              </div>
            ))}
          </Space>
        )}
      </Modal>
    </div>
  )
}

export function WorkflowDesigner(props: {
  definition: WDef
  onChange: (d: WDef) => void
  workflowName?: string
}) {
  return (
    <ReactFlowProvider>
      <DesignerInner {...props} />
    </ReactFlowProvider>
  )
}
