import { useMemo, useState } from 'react'
import {
  Alert,
  Badge,
  Button,
  Card,
  Col,
  InputNumber,
  Modal,
  Row,
  Segmented,
  Space,
  Table,
  Tag,
  App as AntApp,
} from 'antd'
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  CreditCardOutlined,
  CrownOutlined,
  RocketOutlined,
  TeamOutlined,
} from '@ant-design/icons'
import { api } from '../api'
import { useApi, fmt, mono, OC } from '../ui'
import QuotaCard from './QuotaCard'

interface InvoiceRow {
  id: string
  number: string | null
  status: string | null
  amountPaid: number
  currency: string
  created: string | null
  hostedUrl: string | null
  pdfUrl: string | null
}

const INVOICE_STATUS_COLOR: Record<string, string> = {
  paid: 'green',
  open: 'blue',
  void: 'default',
  uncollectible: 'red',
  draft: 'orange',
}

function formatMoney(minor: number, currency: string): string {
  try {
    return new Intl.NumberFormat('zh-CN', {
      style: 'currency',
      currency: (currency || 'usd').toUpperCase(),
    }).format(minor / 100)
  } catch {
    return `${(minor / 100).toFixed(2)} ${(currency || 'usd').toUpperCase()}`
  }
}

function InvoiceHistoryCard() {
  const { data, loading, error } = useApi(
    () => api.get<InvoiceRow[]>('/api/v1/billing/invoices'),
    [],
  )
  const rows = data ?? []
  return (
    <Card
      title={
        <Space>
          <CreditCardOutlined style={{ color: OC.accent }} />
          账单历史
        </Space>
      }
      variant="borderless"
      style={{ background: OC.card, marginTop: 16 }}
      styles={{ header: { borderBottom: `1px solid ${OC.border}`, color: OC.textStrong } }}
      loading={loading}
    >
      {error && <Alert type="error" showIcon message="账单加载失败" description={error} />}
      {!error && rows.length === 0 ? (
        <div style={{ color: OC.muted, fontSize: 13 }}>暂无账单记录。</div>
      ) : (
        <Table
          size="small"
          pagination={false}
          rowKey={(r) => r.id}
          dataSource={rows}
          columns={[
            {
              title: '发票号',
              dataIndex: 'number',
              render: (v, r) =>
                v ? (
                  <a href={r.hostedUrl ?? '#'} target="_blank" rel="noreferrer" style={mono}>
                    {v}
                  </a>
                ) : (
                  <span style={mono}>{r.id}</span>
                ),
            },
            { title: '日期', dataIndex: 'created', render: (v) => fmt(v) },
            {
              title: '金额',
              dataIndex: 'amountPaid',
              render: (v, r) => <span style={{ fontWeight: 600 }}>{formatMoney(v, r.currency)}</span>,
            },
            {
              title: '状态',
              dataIndex: 'status',
              render: (v) => (
                <Tag color={INVOICE_STATUS_COLOR[v ?? ''] ?? 'default'}>{v ?? '—'}</Tag>
              ),
            },
            {
              title: '',
              dataIndex: 'pdfUrl',
              render: (v) =>
                v ? (
                  <a href={v} target="_blank" rel="noreferrer">
                    PDF
                  </a>
                ) : null,
            },
          ]}
        />
      )}
    </Card>
  )
}

type PlanTier = 'free' | 'pro' | 'team'

interface PlanSpec {
  tier: PlanTier
  name: string
  price: string
  tagline: string
  icon: React.ReactNode
  features: { name: string; included: boolean }[]
}

const PLANS: PlanSpec[] = [
  {
    tier: 'free',
    name: '免费版',
    price: '¥0',
    tagline: '本地开发与体验',
    icon: <RocketOutlined />,
    features: [
      { name: '本地 LLM / 自带 API Key', included: true },
      { name: '单用户使用', included: true },
      { name: '基础工具集（5 个内置工具）', included: true },
      { name: '会话历史 7 天', included: true },
      { name: '云端大模型路由', included: false },
      { name: '定时任务（Scheduler）', included: false },
      { name: '技能安装与热加载', included: false },
      { name: '团队组织与协作', included: false },
    ],
  },
  {
    tier: 'pro',
    name: 'Pro',
    price: '¥99 / 月',
    tagline: '个人开发者首选',
    icon: <CrownOutlined />,
    features: [
      { name: '本地 LLM / 自带 API Key', included: true },
      { name: '单用户使用', included: true },
      { name: '全部内置工具 + MCP', included: true },
      { name: '会话历史 90 天', included: true },
      { name: '云端大模型路由', included: true },
      { name: '定时任务（Scheduler）', included: true },
      { name: '技能安装与热加载', included: true },
      { name: '团队组织与协作', included: false },
    ],
  },
  {
    tier: 'team',
    name: 'Team',
    price: '¥299 / 月 / 席位',
    tagline: '小团队协作',
    icon: <TeamOutlined />,
    features: [
      { name: '本地 LLM / 自带 API Key', included: true },
      { name: '多用户协作（含 OWNER/ADMIN/MEMBER/VIEWER 角色）', included: true },
      { name: '全部内置工具 + MCP + 团队级技能库', included: true },
      { name: '会话历史 365 天 + 用量看板', included: true },
      { name: '云端大模型路由', included: true },
      { name: '定时任务（Scheduler）', included: true },
      { name: '技能安装与热加载 + 团队更新', included: true },
      { name: '团队组织与协作', included: true },
    ],
  },
]

interface Subscription {
  status: string
  planTier: string
  seatCount: number
  currentPeriodStart: string | null
  currentPeriodEnd: string | null
  cancelAtPeriodEnd: boolean
  stripeSubscriptionId: string | null
}

interface BillingMe {
  userId: string
  orgId: string | null
  planTier: PlanTier
  subscription: Subscription | null
  billingEnabled: boolean
  features: Record<string, string>
}

const PLAN_TAG_COLOR: Record<PlanTier, string> = {
  free: 'default',
  pro: 'gold',
  team: 'purple',
}

const STATUS_TAG_COLOR: Record<string, string> = {
  active: 'green',
  trialing: 'blue',
  past_due: 'orange',
  canceled: 'red',
}

const PLAN_RANK: Record<PlanTier, number> = { free: 0, pro: 1, team: 2 }

type BillingInterval = 'month' | 'year'

const MONTHLY_MINOR: Record<PlanTier, number> = { free: 0, pro: 99, team: 299 }

function priceFor(plan: PlanSpec, interval: BillingInterval): string {
  if (plan.tier === 'free') return plan.price
  if (interval === 'month') return `¥${MONTHLY_MINOR[plan.tier]} / 月`
  // Annual: pay for 10 months (≈17% off), team remains per-seat.
  const yearly = MONTHLY_MINOR[plan.tier] * 10
  return plan.tier === 'team' ? `¥${yearly} / 年 / 席位` : `¥${yearly} / 年`
}

function PlanCard({
  plan,
  currentTier,
  interval,
  onChoose,
  busy,
}: {
  plan: PlanSpec
  currentTier: PlanTier
  interval: BillingInterval
  onChoose: (t: PlanTier) => void
  busy: boolean
}) {
  const current = currentTier === plan.tier
  const higher = PLAN_RANK[plan.tier] > PLAN_RANK[currentTier]
  const cta = current ? '当前套餐' : `${higher ? '升级到' : '降级到'} ${plan.name}`
  return (
    <Card
      variant="borderless"
      style={{
        background: OC.card,
        height: '100%',
        display: 'flex',
        flexDirection: 'column',
        border: current ? `1px solid ${OC.accent}` : `1px solid ${OC.border}`,
      }}
      styles={{
        header: { borderBottom: `1px solid ${OC.border}`, color: OC.textStrong },
        body: { flex: 1, display: 'flex', flexDirection: 'column', overflow: 'auto' },
      }}
      title={
        <Space>
          <span style={{ color: OC.accent }}>{plan.icon}</span>
          {plan.name}
          {current && <Tag color="red">当前</Tag>}
        </Space>
      }
    >
      <div style={{ marginBottom: 14 }}>
        <div style={{ fontSize: 26, fontWeight: 700, color: OC.textStrong }}>
          {priceFor(plan, interval)}
        </div>
        <div style={{ fontSize: 12, color: OC.muted, marginTop: 2 }}>{plan.tagline}</div>
        {plan.tier !== 'free' && interval === 'year' && (
          <Tag color="red" style={{ marginTop: 6 }}>年付省 2 个月</Tag>
        )}
      </div>
      <div style={{ marginBottom: 14, flex: 1 }}>
        {plan.features.map((f) => (
          <div
            key={f.name}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 8,
              padding: '4px 0',
              fontSize: 13,
              color: f.included ? OC.text : OC.muted,
            }}
          >
            {f.included ? (
              <CheckCircleOutlined style={{ color: OC.accent }} />
            ) : (
              <CloseCircleOutlined style={{ color: OC.muted }} />
            )}
            <span>{f.name}</span>
          </div>
        ))}
      </div>
      <Button
        type={current ? 'default' : 'primary'}
        danger={!current && !higher}
        block
        disabled={current || busy}
        onClick={() => onChoose(plan.tier)}
      >
        {cta}
      </Button>
    </Card>
  )
}

export default function BusinessPage() {
  const { message } = AntApp.useApp()
  const { data: me, reload, loading } = useApi(
    () => api.get<BillingMe>('/api/v1/billing/me'),
    [],
  )
  const [busy, setBusy] = useState<PlanTier | 'portal' | null>(null)
  const [interval, setInterval] = useState<BillingInterval>('month')
  const [seats, setSeats] = useState<number>(Math.max(2, me?.subscription?.seatCount ?? 2))

  const sub = me?.subscription
  const currentTier: PlanTier = me?.planTier ?? 'free'
  const subStatus = sub?.status ?? 'none'

  const openPortal = async () => {
    setBusy('portal')
      const r = await api.post<{ url: string; live: boolean }>('/api/v1/billing/portal', {
        returnUrl: window.location.pathname,
      })
      setBusy(null)
      if (r.live) {
        window.location.href = r.url
      } else {
        Modal.info({
          title: '管理订阅（演示）',
          width: 480,
          icon: <CreditCardOutlined style={{ color: OC.accent }} />,
          content: (
            <div style={{ color: OC.text, lineHeight: 1.9, fontSize: 13 }}>
              <div>
                真实 Stripe Customer Portal 接入待 <b>P1 任务</b>（仅需配置{' '}
                <span style={mono}>tianshu.billing.publishable-key / secret-key</span>）。
              </div>
              <div style={{ marginTop: 8, color: OC.muted }}>
                当前为 <Tag color="orange">mock</Tag> 模式；生产环境只需切换{' '}
                <span style={mono}>tianshu.billing.enabled=true</span> 并补齐密钥即可真正跳转到
                Stripe 托管页面。
              </div>
              <div style={{ marginTop: 8, color: OC.muted, fontSize: 12 }}>
                会话 ID：<span style={mono}>{r.url}</span>
              </div>
            </div>
          ),
        })
      }
  }

  const startCheckout = async (planTier: PlanTier) => {
    // Choosing a lower tier means canceling the current paid plan. We never
    // strip access instantly: the user keeps access until the end of the paid
    // period, so route them to the Stripe customer portal to cancel.
    if (PLAN_RANK[planTier] < PLAN_RANK[currentTier]) {
      Modal.confirm({
        title: `降级到 ${planTier === 'free' ? '免费版' : planTier.toUpperCase()}？`,
        okText: '前往取消订阅',
        cancelText: '再想想',
        okButtonProps: { danger: true },
        content: (
          <div style={{ color: OC.text, fontSize: 13, lineHeight: 1.8 }}>
            <div>降级不会立即生效，你在当前计费周期内仍可继续使用现有套餐。</div>
            <div style={{ color: OC.muted, marginTop: 6 }}>
              取消订阅后，将在本次付费周期结束时自动降为
              {planTier === 'free' ? '免费版' : planTier.toUpperCase()}。
            </div>
          </div>
        ),
        onOk: () => openPortal(),
      })
      return
    }
    setBusy(planTier)
    try {
      const r = await api.post<{ url: string; planTier: string; live: boolean }>(
        '/api/v1/billing/checkout',
        {
          planTier,
          orgId: me?.orgId ?? undefined,
          interval,
          seats: planTier === 'team' ? seats : undefined,
        },
      )
      if (r.live) {
        window.location.href = r.url
      } else {
        Modal.info({
          title: `升级到 ${planTier.toUpperCase()}（演示）`,
          width: 480,
          icon: <CrownOutlined style={{ color: OC.accent }} />,
          content: (
            <div style={{ color: OC.text, lineHeight: 1.9, fontSize: 13 }}>
              <div>
                真实 Stripe Checkout 接入待 <b>P1 任务</b>（仅需配置{' '}
                <span style={mono}>tianshu.billing.publishable-key / secret-key</span>）。
              </div>
              <div style={{ marginTop: 8, color: OC.muted }}>
                当前为 <Tag color="orange">mock</Tag> 模式；切换{' '}
                <span style={mono}>tianshu.billing.enabled=true</span> 即可。
              </div>
              <div style={{ marginTop: 8, color: OC.muted, fontSize: 12 }}>
                会话 ID：<span style={mono}>{r.url}</span>
              </div>
            </div>
          ),
        })
      }
    } catch (e) {
      // 409 = the caller already has a live subscription (or a currency
      // mismatch). Route them to the customer portal instead of a dead-end toast.
      const raw = e instanceof Error ? e.message : String(e)
      if (raw.includes(' 409 ')) {
        const detail = raw.split(' 409 ')[1] ?? raw
        Modal.confirm({
          title: '无法发起新的结账',
          okText: '前往管理订阅',
          cancelText: '关闭',
          content: <div style={{ color: OC.text, fontSize: 13, lineHeight: 1.8 }}>{detail}</div>,
          onOk: () => openPortal(),
        })
      } else {
        message.error(raw)
      }
    } finally {
      setBusy(null)
    }
  }

  const featureRows = useMemo(() => {
    const overrides: Record<string, string> = me?.features ?? {}
    const rows: { feature: string; requiredPlan: string }[] = [
      { feature: 'scheduler', requiredPlan: overrides['scheduler'] ?? 'pro' },
      { feature: 'skill.install', requiredPlan: overrides['skill.install'] ?? 'pro' },
      { feature: 'skill.updateAll', requiredPlan: overrides['skill.updateAll'] ?? 'team' },
    ]
    return rows
  }, [me?.features])

  return (
    <div style={{ padding: 24, maxWidth: 1200, margin: '0 auto' }}>
      {!me?.billingEnabled && (
        <Alert
          type="info"
          showIcon
          message="计费系统尚未启用"
          description={
            <span>
              当前 <Tag>mock</Tag> 模式（{`<tianshu.billing.enabled=false>`}）。
              套餐升降级为前端闭环演示，配置 Stripe 密钥后即生效。
            </span>
          }
          style={{ marginBottom: 16 }}
        />
      )}

      {me?.billingEnabled && (
        <div style={{ marginBottom: 16 }}>
          <QuotaCard />
        </div>
      )}

      <Card
        title={
          <Space>
            <CreditCardOutlined style={{ color: OC.accent }} />
            当前订阅
          </Space>
        }
        variant="borderless"
        style={{ background: OC.card, marginBottom: 16 }}
        styles={{ header: { borderBottom: `1px solid ${OC.border}`, color: OC.textStrong } }}
        extra={
          sub && (
            <Button onClick={openPortal} loading={busy === 'portal'}>
              管理订阅
            </Button>
          )
        }
        loading={loading && !me}
      >
        {me && (
          <Row gutter={[24, 12]}>
            <Col xs={24} md={6}>
              <div style={{ color: OC.muted, fontSize: 12, marginBottom: 4 }}>组织</div>
              <div style={{ ...mono, color: OC.textStrong, fontSize: 13 }}>
                {me.orgId ?? '尚未加入任何组织'}
              </div>
            </Col>
            <Col xs={12} md={4}>
              <div style={{ color: OC.muted, fontSize: 12, marginBottom: 4 }}>套餐</div>
              <Tag color={PLAN_TAG_COLOR[currentTier]} style={{ fontSize: 13 }}>
                {currentTier.toUpperCase()}
              </Tag>
            </Col>
            <Col xs={12} md={4}>
              <div style={{ color: OC.muted, fontSize: 12, marginBottom: 4 }}>状态</div>
              <Badge
                status={
                  subStatus === 'active' ? 'success' : subStatus === 'none' ? 'default' : 'warning'
                }
                text={
                  sub ? (
                    <Tag color={STATUS_TAG_COLOR[sub.status] ?? 'default'}>{sub.status}</Tag>
                  ) : (
                    <span style={{ color: OC.muted }}>无活跃订阅</span>
                  )
                }
              />
            </Col>
            <Col xs={12} md={4}>
              <div style={{ color: OC.muted, fontSize: 12, marginBottom: 4 }}>席位</div>
              <div style={mono}>{sub?.seatCount ?? 1}</div>
            </Col>
            <Col xs={24} md={6}>
              <div style={{ color: OC.muted, fontSize: 12, marginBottom: 4 }}>续期时间</div>
              <div style={{ ...mono, fontSize: 12.5 }}>
                {sub ? fmt(sub.currentPeriodEnd) : '—'}
              </div>
              {sub?.cancelAtPeriodEnd && (
                <Tag color="orange" style={{ marginTop: 4 }}>
                  到期不续费
                </Tag>
              )}
            </Col>
          </Row>
        )}
      </Card>

      <div
        style={{
          display: 'flex',
          flexWrap: 'wrap',
          alignItems: 'center',
          gap: 16,
          margin: '20px 0 12px',
        }}
      >
        <h2 style={{ color: OC.textStrong, fontSize: 16, margin: 0 }}>套餐对比</h2>
        <Segmented
          value={interval}
          onChange={(v) => setInterval(v as BillingInterval)}
          options={[
            { label: '月付', value: 'month' },
            { label: '年付（省 2 个月）', value: 'year' },
          ]}
        />
        <Space style={{ marginLeft: 'auto' }}>
          <span style={{ color: OC.muted, fontSize: 13 }}>Team 席位数</span>
          <InputNumber
            min={2}
            max={200}
            value={seats}
            onChange={(v) => setSeats(v ?? 2)}
            style={{ width: 90 }}
          />
        </Space>
      </div>
      <Row gutter={[16, 16]}>
        {PLANS.map((p) => (
          <Col xs={24} md={8} key={p.tier}>
            <PlanCard
              plan={p}
              currentTier={currentTier}
              interval={interval}
              onChoose={startCheckout}
              busy={busy !== null}
            />
          </Col>
        ))}
      </Row>

      <Card
        title="功能门控矩阵"
        variant="borderless"
        style={{ background: OC.card, marginTop: 16 }}
        styles={{ header: { borderBottom: `1px solid ${OC.border}`, color: OC.textStrong } }}
      >
        <Table
          size="small"
          pagination={false}
          rowKey={(r) => r.feature}
          dataSource={featureRows}
          columns={[
            { title: '功能', dataIndex: 'feature', render: (v) => <span style={mono}>{v}</span> },
            {
              title: '最低套餐',
              dataIndex: 'requiredPlan',
              render: (v) => <Tag color={PLAN_TAG_COLOR[v as PlanTier] ?? 'default'}>{v}</Tag>,
            },
            {
              title: '当前可用',
              render: (_, r) => {
                const order: PlanTier[] = ['free', 'pro', 'team']
                const ok = order.indexOf(currentTier) >= order.indexOf(r.requiredPlan as PlanTier)
                return ok ? (
                  <Tag color="green">是</Tag>
                ) : (
                  <Tag color="default">否</Tag>
                )
              },
            },
          ]}
        />
      </Card>

      <InvoiceHistoryCard />
    </div>
  )
}