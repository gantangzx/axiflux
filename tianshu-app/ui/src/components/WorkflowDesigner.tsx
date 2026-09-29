import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  ReactFlow,
  ReactFlowProvider,
  Background,
  Controls,
  MiniMap,
  addEdge,
  useNodesState,
  useEdgesState,
  Handle,
  Position,
  MarkerType,
  type Node,
  type Edge,
  type Connection,
  type NodeProps,
  type NodeTypes,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { OC } from '../ui'
import type { WorkflowDef } from '../workflow-types'

// Reserved terminals.
const START = '__start__'
const END = '__end__'

const NODE_KINDS = [
  { value: 'AGENT', label: 'Agent 节点' },
  { value: 'TOOL', label: '工具调用' },
  { value: 'SKILL', label: '技能' },
  { value: 'DECISION', label: '条件决策' },
  { value: 'PARALLEL', label: '并行' },
  { value: 'PAUSE', label: '暂停' },
  { value: 'APPROVAL', label: '审批' },
  { value: 'PASS', label: '透传' },
] as const

type DesignerNodeData = Record<string, unknown> & {
  label: string
  kind: string
  terminal?: 'start' | 'end'
}

/** Rendered graph node: colored by kind, with connect handles. */
function DesignerNode({ data }: NodeProps<Node<DesignerNodeData>>) {
  if (data.terminal) {
    const color = data.terminal === 'start' ? '#7fd0a4' : OC.accent
    return (
      <div
        style={{
          borderRadius: 20,
          padding: '6px 16px',
          fontSize: 12,
          fontWeight: 600,
          color: OC.textStrong,
          background: OC.popover,
          border: `1.5px solid ${color}`,
        }}
      >
        {data.terminal === 'start' ? '开始' : '结束'}
        {data.terminal === 'start' ? (
          <Handle type="source" position={Position.Right} />
        ) : (
          <Handle type="target" position={Position.Left} />
        )}
      </div>
    )
  }
  return (
    <div
      style={{
        borderRadius: 8,
        padding: '8px 12px',
        minWidth: 120,
        fontSize: 12.5,
        color: OC.textStrong,
        background: OC.popover,
        border: `1.5px solid ${OC.borderStrong}`,
      }}
    >
      <Handle type="target" position={Position.Left} />
      <div style={{ fontWeight: 600, marginBottom: 2 }}>{data.label}</div>
      <div style={{ color: OC.muted, fontSize: 11 }}>{String(data.kind)}</div>
      <Handle type="source" position={Position.Right} />
    </div>
  )
}

const nodeTypes: NodeTypes = { dnode: DesignerNode }

let idSeq = 1
function nextId(kind: string) {
  return `${kind.toLowerCase()}_${idSeq++}`
}

export type WorkflowDesignerProps = {
  initial: WorkflowDef
  onChange?: (def: WorkflowDef) => void
  height?: number
}

function Canvas({ initial, height = 520, onChange }: WorkflowDesignerProps) {
  const [nodes, setNodes, onNodesChange] = useNodesState<Node<DesignerNodeData>>(toFlowNodes(initial))
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>(toFlowEdges(initial))
  const [selectedId, setSelectedId] = useState<string | null>(null)

  useEffect(() => {
    onChange?.(flowToDef(nodes, edges))
  }, [nodes, edges, onChange])

  const onConnect = useCallback(
    (conn: Connection) =>
      setEdges((eds) =>
        addEdge(
          {
            ...conn,
            style: { stroke: OC.muted },
            markerEnd: { type: MarkerType.ArrowClosed, color: OC.muted },
          },
          eds,
        ),
      ),
    [setEdges],
  )

  const addNode = (kind: string) => {
    const id = nextId(kind)
    setNodes((ns) => [
      ...ns,
      {
        id,
        type: 'dnode',
        position: { x: 220 + Math.random() * 120, y: 80 + Math.random() * 200 },
        data: { label: NODE_KINDS.find((k) => k.value === kind)?.label ?? kind, kind },
      },
    ])
    setSelectedId(id)
  }

  const selectedNode = nodes.find((n) => n.id === selectedId && !n.data.terminal) || null

  const updateSelected = (patch: Partial<DesignerNodeData>) => {
    if (!selectedNode) return
    setNodes((ns) =>
      ns.map((n) => (n.id === selectedNode.id ? { ...n, data: { ...n.data, ...patch } } : n)),
    )
  }

  const removeSelected = () => {
    if (!selectedNode) return
    setNodes((ns) => ns.filter((n) => n.id !== selectedNode.id))
    setEdges((es) => es.filter((e) => e.source !== selectedNode.id && e.target !== selectedNode.id))
    setSelectedId(null)
  }

  return (
    <div style={{ display: 'flex', gap: 12, height }}>
      {/* Palette */}
      <div
        style={{
          width: 132,
          flexShrink: 0,
          background: OC.bgElevated,
          border: `1px solid ${OC.border}`,
          borderRadius: 10,
          padding: 10,
        }}
      >
        <div style={{ color: OC.muted, fontSize: 11, marginBottom: 8 }}>节点类型</div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          {NODE_KINDS.map((k) => (
            <button
              key={k.value}
              onClick={() => addNode(k.value)}
              style={{
                textAlign: 'left',
                cursor: 'pointer',
                fontSize: 12,
                padding: '6px 8px',
                borderRadius: 6,
                color: OC.text,
                background: OC.card,
                border: `1px solid ${OC.border}`,
              }}
            >
              + {k.label}
            </button>
          ))}
        </div>
      </div>

      {/* Canvas */}
      <div style={{ flex: 1, minWidth: 0, border: `1px solid ${OC.border}`, borderRadius: 10, overflow: 'hidden' }}>
        <ReactFlow
          nodes={nodes}
          edges={edges}
          nodeTypes={nodeTypes}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onConnect={onConnect}
          onNodeClick={(_, n) => setSelectedId(n.data.terminal ? null : n.id)}
          onPaneClick={() => setSelectedId(null)}
          fitView
          proOptions={{ hideAttribution: true }}
          defaultEdgeOptions={{
            style: { stroke: OC.muted },
            markerEnd: { type: MarkerType.ArrowClosed, color: OC.muted },
          }}
        >
          <Background color={OC.borderStrong} gap={20} />
          <Controls showInteractive={false} />
          <MiniMap
            pannable
            zoomable
            nodeColor={(n) => (n.data.terminal === 'start' ? '#7fd0a4' : OC.accent)}
            maskColor="rgba(0,0,0,0.5)"
            style={{ background: OC.bgElevated }}
          />
        </ReactFlow>
      </div>

      {/* Property panel */}
      <div
        style={{
          width: 230,
          flexShrink: 0,
          background: OC.bgElevated,
          border: `1px solid ${OC.border}`,
          borderRadius: 10,
          padding: 12,
          overflowY: 'auto',
        }}
      >
        {selectedNode ? (
          <NodePropsForm
            node={selectedNode}
            update={updateSelected}
            remove={removeSelected}
          />
        ) : (
          <div style={{ color: OC.muted, fontSize: 12.5, lineHeight: 1.7 }}>
            点击左侧类型添加节点；拖动节点、从节点右侧圆点拉线即可建立连线。点击某个节点可在此编辑属性。
          </div>
        )}
      </div>
    </div>
  )
}

import { Form, Input, Select, Button, Space } from 'antd'

function NodePropsForm({
  node,
  update,
  remove,
}: {
  node: Node<DesignerNodeData>
  update: (patch: Partial<DesignerNodeData>) => void
  remove: () => void
}) {
  const kind = String(node.data.kind)
  return (
    <Form layout="vertical" size="small">
      <Form.Item label="节点 ID">
        <Input value={node.id} disabled style={{ fontFamily: 'monospace' }} />
      </Form.Item>
      <Form.Item label="类型">
        <Select
          value={kind}
          options={NODE_KINDS.map((k) => ({ value: k.value, label: k.label }))}
          onChange={(v) => update({ kind: v })}
        />
      </Form.Item>
      <Form.Item label="显示名称">
        <Input value={node.data.label} onChange={(e) => update({ label: e.target.value })} />
      </Form.Item>

      {(kind === 'AGENT' || kind === 'DECISION') && (
        <Form.Item label="指令 / Query">
          <Input.TextArea
            rows={3}
            value={asStr(node.data.query)}
            onChange={(e) => update({ query: e.target.value })}
            placeholder="处理输入：${input}"
          />
        </Form.Item>
      )}
      {kind === 'AGENT' && (
        <Form.Item label="输出变量 outputVar">
          <Input
            value={asStr(node.data.outputVar)}
            onChange={(e) => update({ outputVar: e.target.value })}
          />
        </Form.Item>
      )}
      {kind === 'TOOL' && (
        <>
          <Form.Item label="工具名 tool">
            <Input
              value={asStr(node.data.tool)}
              onChange={(e) => update({ tool: e.target.value })}
            />
          </Form.Item>
          <Form.Item label="静态参数（JSON 对象）">
            <Input.TextArea
              rows={3}
              value={asStr(node.data.params)}
              onChange={(e) => update({ params: e.target.value })}
              placeholder='{"key":"value"}'
            />
          </Form.Item>
        </>
      )}
      {kind === 'SKILL' && (
        <Form.Item label="技能名 skill">
          <Input
            value={asStr(node.data.skill)}
            onChange={(e) => update({ skill: e.target.value })}
          />
        </Form.Item>
      )}
      {kind === 'PAUSE' && (
        <Form.Item label="等待键 waitFor">
          <Input
            value={asStr(node.data.waitFor)}
            onChange={(e) => update({ waitFor: e.target.value })}
          />
        </Form.Item>
      )}

      <Space style={{ width: '100%', justifyContent: 'flex-end' }}>
        <Button danger size="small" onClick={remove}>
          删除节点
        </Button>
      </Space>
    </Form>
  )
}

export default function WorkflowDesigner(props: WorkflowDesignerProps) {
  return (
    <ReactFlowProvider>
      <Canvas {...props} />
    </ReactFlowProvider>
  )
}

// ===== conversions =====

function toFlowNodes(def: WorkflowDef): Node<DesignerNodeData>[] {
  const out: Node<DesignerNodeData>[] = [
    {
      id: START,
      type: 'dnode',
      position: { x: 0, y: 160 },
      data: { label: '开始', kind: 'START', terminal: 'start' },
      draggable: true,
      connectable: true,
      deletable: false,
    },
    {
      id: END,
      type: 'dnode',
      position: { x: 640, y: 160 },
      data: { label: '结束', kind: 'END', terminal: 'end' },
      deletable: false,
    },
  ]
  for (const n of def.nodes ?? []) {
    out.push({
      id: n.id,
      type: 'dnode',
      position: { x: n.x ?? 200, y: n.y ?? 120 },
      data: {
        label: n.label || n.id,
        kind: n.type,
        query: n.query,
        systemPrompt: n.systemPrompt,
        outputVar: n.outputVar,
        tool: n.tool,
        params: n.params,
        skill: n.skill,
        waitFor: n.waitFor,
      },
    })
  }
  return out
}

function toFlowEdges(def: WorkflowDef): Edge[] {
  return (def.edges ?? []).map((e) => ({
    id: `${e.source}->${e.target}:${e.condition ?? ''}`,
    source: e.source,
    target: e.target,
    label: e.condition || undefined,
    labelStyle: { fill: '#d4a05a', fontSize: 11 },
    style: { stroke: e.condition ? '#d4a05a' : OC.muted, strokeDasharray: e.condition ? '5 4' : undefined },
    markerEnd: {
      type: MarkerType.ArrowClosed,
      color: e.condition ? '#d4a05a' : OC.muted,
    },
  }))
}

/** Read current nodes/edges via a ref-free helper exposed by Canvas through context. */
export function flowToDef(nodes: Node<DesignerNodeData>[], edges: Edge[]): WorkflowDef {
  const wfNodes: WorkflowDef['nodes'] = []
  for (const n of nodes) {
    if (n.data.terminal) continue
    const d = n.data
    wfNodes.push({
      id: n.id,
      type: String(d.kind),
      label: asStr(d.label),
      query: asStr(d.query),
      systemPrompt: asStr(d.systemPrompt),
      outputVar: asStr(d.outputVar),
      tool: asStr(d.tool),
      params: parseMaybeJson(d.params),
      skill: asStr(d.skill),
      waitFor: asStr(d.waitFor),
      x: n.position.x,
      y: n.position.y,
    })
  }
  return {
    nodes: wfNodes,
    edges: edges.map((e) => ({
      source: e.source,
      target: e.target,
      condition: typeof e.label === 'string' && e.label ? e.label : undefined,
    })),
  }
}

function asStr(v: unknown): string {
  return v == null ? '' : typeof v === 'string' ? v : String(v)
}

function parseMaybeJson(v: unknown): unknown {
  if (typeof v !== 'string') return v
  const t = v.trim()
  if (!t) return undefined
  try {
    return JSON.parse(t)
  } catch {
    return v
  }
}
