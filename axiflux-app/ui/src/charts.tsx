/**
 * 轻量 SVG 图表原语。
 *
 * 为什么不引图表库：用量页只需要「趋势线 / 排名条 / 构成条」三种形态，手写 SVG
 * 能直接吃 OC 主题变量（深浅色 + accent 实时切换）、不新增依赖，也不必跟第三方库
 * 的深色默认样式打架。
 *
 * 一条约定：几何画在 SVG 里（容器宽度靠 preserveAspectRatio="none" 自适应，
 * 用 vector-effect="non-scaling-stroke" 保证线宽不被横向拉伸），文字一律用 HTML —— 
 * 否则 "none" 缩放会把字压扁变形。
 */
import { useId, useState } from 'react'
import { mono, OC } from './ui'

/** 从 n 个点里挑至多 k 个下标（必含首尾），用于 x 轴标签防拥挤 */
function pickIndices(n: number, k: number): Set<number> {
  if (n <= k) return new Set(Array.from({ length: n }, (_, i) => i))
  const out = new Set<number>()
  for (let i = 0; i < k; i++) out.add(Math.round((i * (n - 1)) / (k - 1)))
  return out
}

export type TrendPoint = { label: string; value: number }

/**
 * 单序列趋势面积图。每个数据点占一格等宽 hover 热区，指针进入即在顶部出明细；
 * 一个月的日粒度（≈30 点）足够，不需要做虚拟化或采样。
 */
export function TrendChart({
  points,
  height = 150,
  format,
  accent = OC.accent,
  tickCount = 7,
}: {
  points: TrendPoint[]
  height?: number
  format: (v: number) => string
  accent?: string
  tickCount?: number
}) {
  const [hover, setHover] = useState<number | null>(null)
  const gid = 'oc-trend-' + useId().replace(/[^a-zA-Z0-9]/g, '')
  if (points.length === 0) return null

  // 逻辑坐标系：宽 600，横向由 viewBox 拉伸到容器实际宽度
  const W = 600
  const padT = 12
  const padB = 2
  const innerH = height - padT - padB
  // 全 0 序列会除零，用极小值兜底（线贴在底边，仍是合法图形）
  const max = Math.max(...points.map((p) => p.value), Number.EPSILON)
  const stepX = points.length > 1 ? W / (points.length - 1) : 0
  const px = (i: number) => (points.length > 1 ? i * stepX : W / 2)
  const py = (v: number) => padT + innerH - (v / max) * innerH

  const line = points
    .map((p, i) => `${i === 0 ? 'M' : 'L'}${px(i).toFixed(2)},${py(p.value).toFixed(2)}`)
    .join(' ')
  const base = padT + innerH
  const area = `${line} L${px(points.length - 1).toFixed(2)},${base} L${px(0).toFixed(2)},${base} Z`
  const ticks = pickIndices(points.length, tickCount)

  return (
    <div style={{ position: 'relative' }}>
      <svg
        viewBox={`0 0 ${W} ${height}`}
        preserveAspectRatio="none"
        style={{ width: '100%', height, display: 'block' }}
      >
        <defs>
          <linearGradient id={gid} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor={accent} stopOpacity={0.3} />
            <stop offset="100%" stopColor={accent} stopOpacity={0} />
          </linearGradient>
        </defs>
        {[0, 0.5, 1].map((f) => (
          <line
            key={f}
            x1={0}
            x2={W}
            y1={padT + innerH * f}
            y2={padT + innerH * f}
            stroke={OC.border}
            strokeWidth={1}
            vectorEffect="non-scaling-stroke"
          />
        ))}
        <path d={area} fill={`url(#${gid})`} />
        <path
          d={line}
          fill="none"
          stroke={accent}
          strokeWidth={2}
          strokeLinejoin="round"
          strokeLinecap="round"
          vectorEffect="non-scaling-stroke"
        />
        {hover != null && (
          <>
            <line
              x1={px(hover)}
              x2={px(hover)}
              y1={padT}
              y2={base}
              stroke={OC.muted}
              strokeWidth={1}
              strokeDasharray="3 3"
              vectorEffect="non-scaling-stroke"
            />
            <circle
              cx={px(hover)}
              cy={py(points[hover].value)}
              r={6}
              fill={OC.card}
              stroke={accent}
              strokeWidth={2}
              vectorEffect="non-scaling-stroke"
            />
          </>
        )}
      </svg>

      {/* hover 热区：等宽铺开，比在 SVG 里做命中测试更省事，也不受缩放影响 */}
      <div style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, display: 'flex' }}>
        {points.map((p, i) => (
          <div
            key={p.label}
            style={{ flex: 1 }}
            onMouseEnter={() => setHover(i)}
            onMouseLeave={() => setHover(null)}
          />
        ))}
      </div>

      {hover != null && (
        <div
          style={{
            position: 'absolute',
            top: 0,
            left: `${(px(hover) / W) * 100}%`,
            transform: 'translateX(-50%)',
            pointerEvents: 'none',
            background: OC.bgElevated,
            border: `1px solid ${OC.border}`,
            borderRadius: 8,
            padding: '5px 9px',
            fontSize: 11,
            lineHeight: 1.5,
            whiteSpace: 'nowrap',
            boxShadow: 'var(--oc-shadow-raise)',
          }}
        >
          <div style={{ color: OC.muted }}>{points[hover].label}</div>
          <div style={{ color: OC.textStrong, fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>
            {format(points[hover].value)}
          </div>
        </div>
      )}

      <div style={{ display: 'flex', marginTop: 4 }}>
        {points.map((p, i) => (
          <div
            key={p.label}
            style={{
              flex: 1,
              textAlign: 'center',
              fontSize: 10,
              color: OC.muted,
              whiteSpace: 'nowrap',
              fontVariantNumeric: 'tabular-nums',
            }}
          >
            {ticks.has(i) ? p.label : ''}
          </div>
        ))}
      </div>
    </div>
  )
}

export type RankRow = { label: string; value: number; sub?: string }

/**
 * 横向排名条。数值用右对齐文字给精确读数，条长只承担「相对大小」——
 * 比纯表格多一眼看出头部，比饼图少一堆比较用的角度。
 */
export function RankBars({
  rows,
  format,
  accent = OC.accent,
}: {
  rows: RankRow[]
  format: (v: number) => string
  accent?: string
}) {
  if (rows.length === 0) return null
  const max = Math.max(...rows.map((r) => r.value), Number.EPSILON)

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 13 }}>
      {rows.map((r) => (
        <div key={r.label}>
          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'baseline',
              gap: 12,
              marginBottom: 5,
              fontSize: 12,
            }}
          >
            <span
              style={{
                ...mono,
                color: OC.textStrong,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              }}
            >
              {r.label}
            </span>
            <span style={{ color: OC.text, whiteSpace: 'nowrap', fontVariantNumeric: 'tabular-nums' }}>
              {format(r.value)}
              {r.sub && <span style={{ color: OC.muted }}> · {r.sub}</span>}
            </span>
          </div>
          <div style={{ height: 6, borderRadius: 3, background: OC.hover, overflow: 'hidden' }}>
            <div
              style={{
                width: `${Math.max((r.value / max) * 100, 1)}%`,
                height: '100%',
                borderRadius: 3,
                background: accent,
              }}
            />
          </div>
        </div>
      ))}
    </div>
  )
}

export type Segment = { label: string; value: number; color: string }

/**
 * 单条构成条 + 图例：回答「这堆 token 里多少是命中缓存的」。
 * 全 0 时退化成一个空槽，不画假宽度。
 */
export function CompositionBar({ segments }: { segments: Segment[] }) {
  const total = segments.reduce((a, s) => a + s.value, 0)
  return (
    <div>
      <div
        style={{
          display: 'flex',
          height: 10,
          borderRadius: 5,
          overflow: 'hidden',
          background: OC.hover,
        }}
      >
        {total > 0 &&
          segments.map((s) =>
            s.value > 0 ? (
              <div
                key={s.label}
                style={{ width: `${(s.value / total) * 100}%`, height: '100%', background: s.color }}
                title={`${s.label} ${s.value.toLocaleString()}`}
              />
            ) : null,
          )}
      </div>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px 16px', marginTop: 10 }}>
        {segments.map((s) => (
          <span key={s.label} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 11.5 }}>
            <span style={{ width: 8, height: 8, borderRadius: 2, background: s.color, flex: '0 0 auto' }} />
            <span style={{ color: OC.muted }}>{s.label}</span>
            <span style={{ color: OC.text, fontVariantNumeric: 'tabular-nums' }}>
              {total > 0 ? ((s.value / total) * 100).toFixed(0) + '%' : '—'}
            </span>
          </span>
        ))}
      </div>
    </div>
  )
}
