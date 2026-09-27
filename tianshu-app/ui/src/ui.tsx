import { useEffect, useState, useCallback, type CSSProperties, type ReactNode } from 'react'
import { Tag } from 'antd'
import { OC } from './theme'

export { OC }

/** Fetch JSON with loading/error state. `deps` triggers reload. */
export function useApi<T>(fn: () => Promise<T>, deps: unknown[] = []) {
  const [data, setData] = useState<T | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [tick, setTick] = useState(0)
  const reload = useCallback(() => setTick((t) => t + 1), [])
  useEffect(() => {
    let alive = true
    setLoading(true)
    setError(null)
    fn()
      .then((d) => alive && setData(d))
      .catch((e) => alive && setError(e?.message || '请求失败'))
      .finally(() => alive && setLoading(false))
    return () => {
      alive = false
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick])
  return { data, loading, error, reload, setData }
}

export const fmt = (v: unknown): string => {
  if (v == null || v === '') return '—'
  const s = String(v)
  const d = new Date(s)
  if (!isNaN(d.getTime()) && /[0-9]/.test(s) && s.length > 8) {
    return d.toLocaleString('zh-CN', { hour12: false })
  }
  return s
}

export const toList = (v: unknown): string[] => {
  if (v == null) return []
  if (Array.isArray(v)) return v.map(String)
  if (typeof v === 'string')
    return v
      .split(/[,;]/)
      .map((s) => s.trim())
      .filter(Boolean)
  return []
}

export const fmtBytes = (v: number | null | undefined): string =>
  v == null
    ? '—'
    : v >= 1024 ** 3
      ? (v / 1024 ** 3).toFixed(2) + ' GB'
      : (v / 1024 ** 2).toFixed(0) + ' MB'

export const fmtUptime = (sec: number | null | undefined): string => {
  sec = Math.floor(sec || 0)
  const d = Math.floor(sec / 86400)
  const h = Math.floor((sec % 86400) / 3600)
  const m = Math.floor((sec % 3600) / 60)
  return d ? `${d} 天 ${h} 小时` : h ? `${h} 小时 ${m} 分` : `${m} 分 ${sec % 60} 秒`
}

const RISK_COLOR: Record<string, string> = {
  SAFE: 'green',
  READ: 'blue',
  NETWORK: 'geekblue',
  WRITE: 'orange',
  DESTRUCTIVE: 'red',
}

export function RiskTag({ level }: { level?: string | null }) {
  if (!level) return <span style={{ color: OC.muted }}>—</span>
  return <Tag color={RISK_COLOR[level] || 'default'}>{level}</Tag>
}

export function BoolTag({ v }: { v: boolean }) {
  return <Tag color={v ? 'green' : 'default'}>{v ? '是' : '否'}</Tag>
}

export function StateTag({ state }: { state?: string }) {
  const s = (state || '').toUpperCase()
  const color = s === 'ACTIVE' || s === '启用' ? 'green' : s === 'CLOSED' || s === '停用' ? 'default' : 'orange'
  return <Tag color={color}>{state || '—'}</Tag>
}

/** List pages: shimmer skeleton rows instead of a bare spinner. */
export function Loading({ label = '加载中…', rows = 6 }: { label?: string; rows?: number }) {
  return (
    <div className="oc-loading">
      <SkeletonRows rows={rows} />
      <div style={{ textAlign: 'center', marginTop: 12, color: OC.muted, fontSize: 12.5 }}>{label}</div>
    </div>
  )
}

export function ErrBox({ msg }: { msg: string }) {
  return (
    <div
      style={{
        border: '1px solid #5c2020',
        background: 'rgba(220,49,70,0.10)',
        color: '#ff9a9a',
        borderRadius: OC.radiusMd,
        padding: '10px 14px',
        fontSize: 13,
      }}
    >
      ⚠️ {msg}
    </div>
  )
}

/** 侧栏折叠/展开按钮（Tianshu 风格：方形描边 + 面板图标） */
export function SidebarToggle({
  onClick,
  title = '折叠 / 展开侧栏',
}: {
  onClick: () => void
  title?: string
}) {
  return (
    <button
      onClick={onClick}
      title={title}
      style={{
        width: 32,
        height: 32,
        flex: '0 0 auto',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'transparent',
        border: `1px solid ${OC.border}`,
        borderRadius: OC.radiusMd,
        color: OC.muted,
        cursor: 'pointer',
        padding: 0,
        transition: 'color 0.15s ease, border-color 0.15s ease',
      }}
      onMouseEnter={(e) => {
        e.currentTarget.style.color = OC.accent
        e.currentTarget.style.borderColor = OC.accent
      }}
      onMouseLeave={(e) => {
        e.currentTarget.style.color = OC.muted
        e.currentTarget.style.borderColor = OC.border
      }}
    >
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
        <rect x="3" y="4" width="18" height="16" rx="2" />
        <rect x="5" y="6" width="4" height="12" rx="1" fill="currentColor" stroke="none" opacity="0.3" />
        <line x1="9.5" y1="4" x2="9.5" y2="20" />
      </svg>
    </button>
  )
}

export const mono: CSSProperties = {
  fontFamily: "'Geist Mono', ui-monospace, 'Cascadia Code', Menlo, Consolas, monospace",
  fontSize: 12.5,
}

/** Shimmering skeleton rows for list pages (replaces bare Spin). */
export function SkeletonRows({ rows = 6 }: { rows?: number }) {
  return (
    <div className="oc-list">
      {Array.from({ length: rows }).map((_, i) => (
        <div className="oc-row" key={i} style={{ pointerEvents: 'none' }}>
          <div className="oc-skel" style={{ width: 34, height: 34, borderRadius: 10, flex: '0 0 auto' }} />
          <div className="oc-row__main">
            <div className="oc-skel" style={{ height: 13, width: 130 + ((i * 47) % 120) }} />
            <div className="oc-skel" style={{ height: 11, width: `${55 + ((i * 13) % 30)}%`, marginTop: 9 }} />
          </div>
        </div>
      ))}
    </div>
  )
}

/** Designed empty state with icon square + CTA. */
export function EmptyHint({
  icon,
  title,
  desc,
  action,
}: {
  icon: ReactNode
  title: string
  desc: string
  action?: ReactNode
}) {
  return (
    <div className="oc-empty">
      <div className="oc-empty__icon">{icon}</div>
      <div className="oc-empty__title">{title}</div>
      <div className="oc-empty__desc">{desc}</div>
      {action}
    </div>
  )
}

export const pillStyle: CSSProperties = {
  ...mono,
  background: OC.bgElevated,
  border: `1px solid ${OC.border}`,
  borderRadius: 6,
  padding: '1px 7px',
  color: OC.text,
  display: 'inline-block',
  margin: 1,
}
