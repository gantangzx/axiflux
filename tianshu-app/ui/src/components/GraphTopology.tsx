import { useMemo } from 'react'
import { OC } from '../ui'

export type TopoNode = { id: string; type: string; label?: string }
export type TopoEdge = {
  source: string
  target: string
  condition?: string | null
  conditional?: boolean
}

export type NodeStatus = 'active' | 'done' | 'paused' | 'failed'

const START_ID = '__start__'
const END_ID = '__end__'

const NODE_W = 156
const NODE_H = 46
const COL_GAP = 84 // horizontal gap between columns
const ROW_GAP = 64 // vertical gap between nodes in a column
const PAD = 28

type Positioned = TopoNode & { x: number; y: number; col: number; virtual: boolean }

/**
 * Pure-SVG directed graph renderer with a longest-path layered (left-to-right)
 * layout. The reserved start/end terminals are synthesised from edges when the
 * node list does not include them.
 */
export default function GraphTopology({
  nodes,
  edges,
  statusById,
  height = 360,
}: {
  nodes: TopoNode[]
  edges: TopoEdge[]
  statusById?: Record<string, NodeStatus>
  height?: number
}) {
  const layout = useMemo(() => layoutGraph(nodes, edges), [nodes, edges])

  if (layout.nodes.length === 0) {
    return (
      <div style={{ color: OC.muted, fontSize: 13, padding: 12 }}>暂无可渲染的节点</div>
    )
  }

  const width = layout.width
  const byId = new Map(layout.nodes.map((n) => [n.id, n]))

  return (
    <div
      style={{
        overflow: 'auto',
        background: OC.bgElevated,
        border: `1px solid ${OC.border}`,
        borderRadius: 10,
      }}
    >
      <svg width={width} height={height} style={{ display: 'block' }}>
        <defs>
          <marker
            id="arrow"
            viewBox="0 0 10 10"
            refX="9"
            refY="5"
            markerWidth="7"
            markerHeight="7"
            orient="auto-start-reverse"
          >
            <path d="M 0 0 L 10 5 L 0 10 z" fill={OC.muted} />
          </marker>
          <marker
            id="arrow-active"
            viewBox="0 0 10 10"
            refX="9"
            refY="5"
            markerWidth="7"
            markerHeight="7"
            orient="auto-start-reverse"
          >
            <path d="M 0 0 L 10 5 L 0 10 z" fill={OC.accent} />
          </marker>
        </defs>

        {edges.map((e, i) => {
          const s = byId.get(e.source)
          const t = byId.get(e.target)
          if (!s || !t) return null
          const x1 = s.x + NODE_W / 2
          const y1 = s.y
          const x2 = t.x - NODE_W / 2
          const y2 = t.y
          const mx = (x1 + x2) / 2
          const path = `M ${x1} ${y1} C ${mx} ${y1}, ${mx} ${y2}, ${x2} ${y2}`
          const targetStatus = statusById?.[e.target]
          const stroke =
            e.conditional ? '#d4a05a' : targetStatus === 'active' ? OC.accent : OC.muted
          return (
            <g key={i}>
              <path
                d={path}
                fill="none"
                stroke={stroke}
                strokeWidth={targetStatus === 'active' ? 2 : 1.3}
                strokeDasharray={e.conditional ? '5 4' : undefined}
                markerEnd={`url(#${targetStatus === 'active' ? 'arrow-active' : 'arrow'})`}
                opacity={0.9}
              />
              {e.conditional && e.condition && (
                <EdgeLabel x={mx} y={(y1 + y2) / 2} text={shorten(e.condition, 22)} />
              )}
            </g>
          )
        })}

        {layout.nodes.map((n) => (
          <NodeBox key={n.id} node={n} status={statusById?.[n.id]} />
        ))}
      </svg>
    </div>
  )
}

function NodeBox({ node, status }: { node: Positioned; status?: NodeStatus }) {
  const x = node.x - NODE_W / 2
  const y = node.y - NODE_H / 2
  const fill = node.virtual ? OC.bgElevated : OC.card
  let stroke = OC.border
  let textColor = node.virtual ? OC.muted : OC.textStrong
  if (status === 'active') {
    stroke = OC.accent
    textColor = OC.accent
  } else if (status === 'done') {
    stroke = '#3f7d5b'
    textColor = '#7fd0a4'
  } else if (status === 'paused') {
    stroke = '#d4a05a'
    textColor = '#e6b66a'
  } else if (status === 'failed') {
    stroke = '#c25b5b'
    textColor = '#e88585'
  }
  const label = node.virtual
    ? node.id === START_ID
      ? '开始'
      : '结束'
    : node.label || node.id
  return (
    <g>
      <rect
        x={x}
        y={y}
        width={NODE_W}
        height={NODE_H}
        rx={9}
        fill={fill}
        stroke={stroke}
        strokeWidth={status ? 2 : 1.2}
      />
      <text
        x={node.x}
        y={node.y - (node.virtual ? 0 : 6)}
        textAnchor="middle"
        fontSize={12.5}
        fontWeight={600}
        fill={textColor}
      >
        {truncate(label, 18)}
      </text>
      {!node.virtual && (
        <text
          x={node.x}
          y={node.y + 10}
          textAnchor="middle"
          fontSize={9.5}
          fill={OC.muted}
        >
          {node.type}
        </text>
      )}
    </g>
  )
}

function EdgeLabel({ x, y, text }: { x: number; y: number; text: string }) {
  const w = text.length * 6.2 + 10
  return (
    <g>
      <rect
        x={x - w / 2}
        y={y - 8}
        width={w}
        height={16}
        rx={4}
        fill={OC.bgElevated}
        stroke="#5a4a30"
        strokeWidth={0.8}
      />
      <text x={x} y={y + 3.5} textAnchor="middle" fontSize={9.5} fill="#d4a05a">
        {text}
      </text>
    </g>
  )
}

function layoutGraph(nodes: TopoNode[], edges: TopoEdge[]) {
  const real = new Map<string, TopoNode>()
  nodes.forEach((n) => real.set(n.id, n))

  // Synthesise terminals referenced by edges but absent from the node list.
  const ids = new Set<string>(nodes.map((n) => n.id))
  edges.forEach((e) => {
    ;[e.source, e.target].forEach((id) => {
      if (!ids.has(id)) {
        ids.add(id)
        if (id === START_ID || id === END_ID) {
          real.set(id, { id, type: id, label: id })
        }
      }
    })
  })

  // Longest-path column assignment via Kahn topological order.
  const indeg = new Map<string, number>()
  const adj = new Map<string, string[]>()
  ids.forEach((id) => {
    indeg.set(id, 0)
    adj.set(id, [])
  })
  edges.forEach((e) => {
    adj.get(e.source)?.push(e.target)
    indeg.set(e.target, (indeg.get(e.target) || 0) + 1)
  })

  const order: string[] = []
  const queue = [...ids].filter((id) => (indeg.get(id) || 0) === 0)
  while (queue.length) {
    const id = queue.shift()!
    order.push(id)
    for (const next of adj.get(id) || []) {
      const d = (indeg.get(next) || 0) - 1
      indeg.set(next, d)
      if (d === 0) queue.push(next)
    }
  }
  // Defensive: if a cycle leaves nodes unordered, append the remainder.
  if (order.length < ids.size) ids.forEach((id) => order.includes(id) || order.push(id))

  const col = new Map<string, number>()
  ids.forEach((id) => col.set(id, 0))
  order.forEach((id) => {
    for (const next of adj.get(id) || []) {
      col.set(next, Math.max(col.get(next) || 0, (col.get(id) || 0) + 1))
    }
  })

  const columns = new Map<number, string[]>()
  order.forEach((id) => {
    const c = col.get(id) || 0
    if (!columns.has(c)) columns.set(c, [])
    columns.get(c)!.push(id)
  })

  const positioned: Positioned[] = []
  const maxRows = Math.max(...[...columns.values()].map((c) => c.length), 1)
  const colStep = NODE_W + COL_GAP
  const rowStep = NODE_H + ROW_GAP
  const colCount = columns.size

  columns.forEach((members, c) => {
    members.forEach((id, r) => {
      const realNode = real.get(id)
      if (!realNode) return
      positioned.push({
        ...realNode,
        col: c,
        virtual: id === START_ID || id === END_ID,
        x: PAD + NODE_W / 2 + c * colStep,
        y: PAD + NODE_H / 2 + r * rowStep + ((maxRows - members.length) * rowStep) / 2,
      })
    })
  })

  const width = PAD * 2 + colCount * NODE_W + (colCount - 1) * COL_GAP
  return { nodes: positioned, width }
}

function truncate(s: string, n: number): string {
  return s.length > n ? `${s.slice(0, n - 1)}…` : s
}

function shorten(s: string, n: number): string {
  const compact = s.replace(/\s+/g, ' ').trim()
  return truncate(compact, n)
}
