import { useEffect, useState, type CSSProperties } from 'react'
import { Alert, Card, Progress, Tag, Tooltip } from 'antd'
import { LockOutlined } from '@ant-design/icons'
import { api } from '../api'
import { ErrBox, Loading, OC, fmt } from '../ui'

interface QuotaStatus {
  enforced: boolean
  period: string
  plan: string
  usedTokens: number
  tokenLimit: number
  remainingTokens: number
  usedTurns: number
  turnLimit: number
  remainingTurns: number
}

/**
 * 本月配额卡片（P1）。读取 {@code GET /usage/quota}，用两条进度条展示
 * token / 轮次用量；billing 关闭时仅统计不拦截；触顶时告警并引导升级。
 */
export default function QuotaCard({ compact }: { compact?: boolean }) {
  const [q, setQ] = useState<QuotaStatus | null>(null)
  const [err, setErr] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    api
      .get<any>('/api/v1/usage/quota')
      .then((d) => alive && setQ(d))
      .catch((e) => alive && setErr(e.message))
    return () => {
      alive = false
    }
  }, [])

  if (err) return <ErrBox msg={err} />
  if (!q) return <Loading label="加载配额…" />

  const tokensUnlimited = q.tokenLimit === 0
  const turnsUnlimited = q.turnLimit === 0
  const tokenPct = tokensUnlimited ? 0 : Math.min(100, (q.usedTokens / q.tokenLimit) * 100)
  const turnPct = turnsUnlimited ? 0 : Math.min(100, (q.usedTurns / q.turnLimit) * 100)
  const tokenExceeded = !tokensUnlimited && q.usedTokens >= q.tokenLimit
  const turnExceeded = !turnsUnlimited && q.usedTurns >= q.turnLimit
  const anyExceeded = tokenExceeded || turnExceeded

  return (
    <Card
      size={compact ? 'small' : 'default'}
      variant="borderless"
      style={{ background: OC.card }}
      styles={{ header: { borderBottom: `1px solid ${OC.border}`, color: OC.textStrong } }}
      title={
        <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          {q.enforced ? '本月配额' : '本月用量'}
          <Tag style={tagStyle(OC.accent, 'rgba(255,92,92,0.14)')}>{q.plan}</Tag>
          <span style={{ fontSize: 11.5, color: OC.muted }}>周期 {q.period}</span>
          {!q.enforced && (
            <Tooltip title="billing.enabled=false，当前仅统计用量、不拦截发送">
              <Tag style={tagStyle(OC.muted, 'rgba(139,139,148,0.15)')}>未强制</Tag>
            </Tooltip>
          )}
        </span>
      }
    >
      {q.enforced && anyExceeded && (
        <Alert
          style={{ marginBottom: 14 }}
          type="error"
          showIcon
          icon={<LockOutlined />}
          message="已达本月额度"
          description="本周期内的发送已被暂停，升级套餐或等待下个自然月重置后恢复。"
        />
      )}
      <DimensionBar
        label="Token 用量"
        used={q.usedTokens}
        limit={q.tokenLimit}
        unlimited={tokensUnlimited}
        pct={tokenPct}
        exceeded={tokenExceeded}
      />
      <DimensionBar
        label="对话轮次"
        used={q.usedTurns}
        limit={q.turnLimit}
        unlimited={turnsUnlimited}
        pct={turnPct}
        exceeded={turnExceeded}
      />
    </Card>
  )
}

function DimensionBar({
  label,
  used,
  limit,
  unlimited,
  pct,
  exceeded,
}: {
  label: string
  used: number
  limit: number
  unlimited: boolean
  pct: number
  exceeded: boolean
}) {
  const color = exceeded ? OC.accent : pct >= 85 ? '#f0a050' : '#3aa6ff'
  return (
    <div style={{ marginBottom: 16 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
        <span style={{ fontSize: 12.5, color: OC.text }}>{label}</span>
        <span style={{ fontSize: 12, color: OC.muted }}>
          {fmt(used)}
          {unlimited ? ' / 不限' : ` / ${fmt(limit)}`}
        </span>
      </div>
      <Progress
        percent={pct}
        showInfo={false}
        strokeColor={color}
        trailColor="rgba(255,255,255,0.06)"
        size={{ height: 8 }}
      />
    </div>
  )
}

function tagStyle(color: string, bg: string): CSSProperties {
  return {
    color,
    background: bg,
    border: 'none',
    borderRadius: 999,
    fontSize: 11,
    marginInlineEnd: 0,
  }
}
