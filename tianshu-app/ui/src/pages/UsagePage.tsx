import { useState, useEffect, useMemo } from 'react'
import {
  Alert,
  Button,
  Card,
  Col,
  DatePicker,
  Empty,
  Row,
  Space,
  Statistic,
  Table,
  Tag,
  App as AntApp,
} from 'antd'
import {
  AreaChartOutlined,
  DollarOutlined,
  ReloadOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons'
import dayjs, { type Dayjs } from 'dayjs'
import { api } from '../api'
import { mono, OC } from '../ui'
import QuotaCard from './QuotaCard'
import { CompositionBar, RankBars, TrendChart, type Segment, type TrendPoint } from '../charts'
import {
  estimateCost,
  fmtCost,
  fmtPct,
  fmtTokens,
  loadPrices,
  priceFor,
  type PriceTable,
} from '../cost'

interface UsageByModel {
  model: string
  /** 计价要按 provider 找单价表，模型 id 单独不足以定位价格 */
  provider?: string | null
  inputTokens: number
  outputTokens: number
  cachedTokens: number
  modelCalls: number
}

interface UsageByUser {
  userId: string
  inputTokens: number
  outputTokens: number
  cachedTokens: number
  modelCalls: number
}

/** 单日单模型的用量切片，后端按 created_at 日粒度聚合而来 */
interface UsageDaily {
  day: string
  provider?: string | null
  model: string
  inputTokens: number
  outputTokens: number
  cachedTokens: number
  modelCalls: number
}

interface UsageSummary {
  period: string
  byModel?: UsageByModel[]
  byUser?: UsageByUser[]
  daily?: UsageDaily[]
  userId?: string
  orgId?: string | null
  totalInputTokens: number
  totalOutputTokens: number
  totalCachedTokens: number
  totalModelCalls: number
}

/** 补零时最多铺这么多天，防脏数据把循环拉爆 */
const MAX_TREND_DAYS = 366

export default function UsagePage() {
  const { message } = AntApp.useApp()
  const [period, setPeriod] = useState<Dayjs>(dayjs().startOf('month'))
  const [me, setMe] = useState<UsageSummary | null>(null)
  const [org, setOrg] = useState<UsageSummary | null>(null)
  const [prices, setPrices] = useState<PriceTable>({})
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    loadPrices().then(setPrices)
  }, [])

  const load = async (p: Dayjs) => {
    setLoading(true)
    const periodStr = p.format('YYYY-MM')
    try {
      const [meR, orgR] = await Promise.all([
        api.get<UsageSummary>(`/api/v1/usage/me?period=${periodStr}`),
        api.get<UsageSummary>(`/api/v1/usage/org?period=${periodStr}`),
      ])
      setMe(meR)
      setOrg(orgR)
    } catch (e: any) {
      message.error(e.message)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load(period)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [period])

  /** 单价表为空 = 部署没声明任何模型价格，此时一律不显示金额（无价 != 免费） */
  const priced = Object.keys(prices).length > 0

  // 逐模型折算成本。总额由各行相加得出，而不是用一个混合单价去乘总 token——
  // 多模型混合时后者会系统性偏离。
  const modelRows = useMemo(() => {
    const rows = (me?.byModel ?? []).map((r) => {
      const price = priceFor(prices, r.provider, r.model)
      return {
        ...r,
        price,
        cost: estimateCost(price, {
          inputTokens: r.inputTokens,
          cachedInputTokens: r.cachedTokens,
          outputTokens: r.outputTokens,
        }),
      }
    })
    return {
      rows,
      total: rows.reduce((a, r) => a + r.cost, 0),
      unpriced: rows.filter((r) => !r.price).map((r) => r.model),
    }
  }, [me, prices])

  // 每日成本：后端只回有量的天，这里补零铺成连续序列，才看得出「哪几天没花钱」
  const trend = useMemo<TrendPoint[]>(() => {
    const days = me?.daily ?? []
    if (days.length === 0) return []
    const byDay = new Map<string, number>()
    for (const d of days) {
      const price = priceFor(prices, d.provider, d.model)
      const c = estimateCost(price, {
        inputTokens: d.inputTokens,
        cachedInputTokens: d.cachedTokens,
        outputTokens: d.outputTokens,
      })
      byDay.set(d.day, (byDay.get(d.day) ?? 0) + c)
    }
    const sorted = [...byDay.keys()].sort()
    const out: TrendPoint[] = []
    let cur = dayjs(sorted[0])
    const last = dayjs(sorted[sorted.length - 1])
    while (!cur.isAfter(last, 'day') && out.length < MAX_TREND_DAYS) {
      out.push({ label: cur.format('M/D'), value: byDay.get(cur.format('YYYY-MM-DD')) ?? 0 })
      cur = cur.add(1, 'day')
    }
    return out
  }, [me, prices])

  const cacheHitRatio =
    me && me.totalInputTokens > 0 ? me.totalCachedTokens / me.totalInputTokens : 0

  const segments: Segment[] = me
    ? [
        {
          label: '未缓存输入',
          value: Math.max(0, me.totalInputTokens - me.totalCachedTokens),
          color: OC.accent,
        },
        {
          label: '命中缓存',
          value: me.totalCachedTokens,
          // 同色系的浅一档：构成条用单一 accent 的浓淡区分，跟主题色联动
          color: 'color-mix(in srgb, var(--oc-accent) 52%, var(--oc-card))',
        },
        { label: '输出', value: me.totalOutputTokens, color: OC.muted },
      ]
    : []

  return (
    <div style={{ padding: 24, maxWidth: 1200, margin: '0 auto' }}>
      <div style={{ marginBottom: 16 }}>
        <QuotaCard />
      </div>
      <Card
        title={
          <Space>
            <AreaChartOutlined style={{ color: OC.accent }} />
            用量看板
          </Space>
        }
        variant="borderless"
        style={{ background: OC.card, marginBottom: 16 }}
        styles={{ header: { borderBottom: `1px solid ${OC.border}`, color: OC.textStrong } }}
        extra={
          <Space>
            <DatePicker
              picker="month"
              value={period}
              onChange={(d) => d && setPeriod(d.startOf('month'))}
              format="YYYY-MM"
              allowClear={false}
            />
            <Button icon={<ReloadOutlined />} onClick={() => load(period)} loading={loading}>
              刷新
            </Button>
          </Space>
        }
      >
        <Row gutter={[24, 16]}>
          <Col xs={12} md={6}>
            <Statistic
              title={
                <span style={{ color: OC.muted, fontSize: 12 }}>
                  <ThunderboltOutlined /> 模型调用
                </span>
              }
              value={me?.totalModelCalls ?? 0}
              valueStyle={{ color: OC.textStrong, fontSize: 22 }}
            />
          </Col>
          <Col xs={12} md={6}>
            <Statistic
              title={
                <span style={{ color: OC.muted, fontSize: 12 }}>
                  <AreaChartOutlined /> 输入 Token
                </span>
              }
              value={me ? fmtTokens(me.totalInputTokens) : '—'}
              valueStyle={{ color: OC.textStrong, fontSize: 22 }}
              suffix={
                me ? (
                  <span style={{ fontSize: 12, color: OC.muted, marginLeft: 4 }}>
                    ({me.totalInputTokens.toLocaleString()})
                  </span>
                ) : null
              }
            />
          </Col>
          <Col xs={12} md={6}>
            <Statistic
              title={
                <span style={{ color: OC.muted, fontSize: 12 }}>
                  <AreaChartOutlined /> 输出 Token
                </span>
              }
              value={me ? fmtTokens(me.totalOutputTokens) : '—'}
              valueStyle={{ color: OC.textStrong, fontSize: 22 }}
              suffix={
                me ? (
                  <span style={{ fontSize: 12, color: OC.muted, marginLeft: 4 }}>
                    ({me.totalOutputTokens.toLocaleString()})
                  </span>
                ) : null
              }
            />
          </Col>
          <Col xs={12} md={6}>
            <Statistic
              title={
                <span style={{ color: OC.muted, fontSize: 12 }}>
                  <DollarOutlined /> 成本
                </span>
              }
              value={priced ? fmtCost(modelRows.total) : '—'}
              valueStyle={{ color: OC.accent, fontSize: 22 }}
              suffix={
                priced ? (
                  <Tag color="default" style={{ marginLeft: 6 }}>
                    按配置单价
                  </Tag>
                ) : null
              }
            />
          </Col>
        </Row>

        {!priced ? (
          <Alert
            type="warning"
            showIcon
            style={{ marginTop: 16 }}
            message={
              <span style={{ fontSize: 12 }}>
                未配置任何模型单价，成本不可用（本页不显示 $0 —— 无价不等于免费）。
                在 <span style={mono}>tianshu.llm.routing.costs</span> 下声明每个单价即可。
              </span>
            }
          />
        ) : (
          <Alert
            type="info"
            showIcon
            style={{ marginTop: 16 }}
            message={
              <span style={{ fontSize: 12 }}>
                成本 = Σ(各模型 token × 单价)，单价取自 <span style={mono}>tianshu.llm.routing.costs</span>
                （与成本路由、会话累计同一张表）。缓存 token 已按缓存价计。
                {modelRows.unpriced.length > 0 && (
                  <>
                    {' '}
                    <span style={{ color: OC.accent }}>
                      {modelRows.unpriced.join('、')} 未配置单价，只统计 token，故总额是下界。
                    </span>
                  </>
                )}
              </span>
            }
          />
        )}
      </Card>

      <Card
        title="每日成本"
        variant="borderless"
        style={{ background: OC.card, marginBottom: 16 }}
        styles={{ header: { borderBottom: `1px solid ${OC.border}`, color: OC.textStrong } }}
        loading={loading && !me}
        extra={
          me && (
            <span style={{ fontSize: 12, color: OC.muted }}>
              {trend.length} 天有记录 · 峰值{' '}
              {fmtCost(trend.reduce((a, p) => Math.max(a, p.value), 0))}
            </span>
          )
        }
      >
        {trend.length > 0 ? (
          <TrendChart points={trend} format={fmtCost} />
        ) : (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={loading ? '加载中…' : '本月暂无使用记录 — 试着在 Chat 页发起一次对话吧'}
          />
        )}
      </Card>

      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={24} lg={12}>
          <Card
            title="成本构成（按模型）"
            variant="borderless"
            style={{ background: OC.card, height: '100%' }}
            styles={{ header: { borderBottom: `1px solid ${OC.border}`, color: OC.textStrong } }}
            loading={loading && !me}
          >
            {modelRows.rows.length > 0 ? (
              <RankBars
                rows={modelRows.rows.map((r) => ({
                  label: r.model,
                  value: r.cost,
                  sub: `${r.modelCalls.toLocaleString()} 次`,
                }))}
                format={fmtCost}
              />
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="本月暂无用量" />
            )}
          </Card>
        </Col>

        <Col xs={24} lg={12}>
          <Card
            title="Token 构成"
            variant="borderless"
            style={{ background: OC.card, height: '100%' }}
            styles={{ header: { borderBottom: `1px solid ${OC.border}`, color: OC.textStrong } }}
            loading={loading && !me}
            extra={
              me && (
                <span style={{ fontSize: 12, color: OC.muted }}>缓存命中 {fmtPct(cacheHitRatio)}</span>
              )
            }
          >
            {me && me.totalModelCalls > 0 ? (
              <CompositionBar segments={segments} />
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="本月暂无用量" />
            )}
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={me?.orgId ? 12 : 24}>
          <Card
            title="我的用量（按模型）"
            variant="borderless"
            style={{ background: OC.card }}
            styles={{ header: { borderBottom: `1px solid ${OC.border}`, color: OC.textStrong } }}
            loading={loading && !me}
          >
            {me && me.byModel && me.byModel.length > 0 ? (
              <Table
                size="small"
                pagination={false}
                rowKey={(r, i) => `${r.provider ?? ''}-${r.model}-${i}`}
                dataSource={me.byModel}
                columns={[
                  {
                    title: '模型',
                    dataIndex: 'model',
                    render: (v, r) => (
                      <div style={{ minWidth: 0 }}>
                        <div style={{ ...mono, color: OC.textStrong }}>{v}</div>
                        {r.provider && (
                          <div style={{ fontSize: 11, color: OC.muted }}>{r.provider}</div>
                        )}
                      </div>
                    ),
                  },
                  {
                    title: '调用',
                    dataIndex: 'modelCalls',
                    align: 'right',
                    render: (v) => v.toLocaleString(),
                  },
                  {
                    title: '输入 Token',
                    dataIndex: 'inputTokens',
                    align: 'right',
                    render: (v) => v.toLocaleString(),
                  },
                  {
                    title: '输出 Token',
                    dataIndex: 'outputTokens',
                    align: 'right',
                    render: (v) => v.toLocaleString(),
                  },
                  {
                    title: '缓存 Token',
                    dataIndex: 'cachedTokens',
                    align: 'right',
                    render: (v) =>
                      v > 0 ? (
                        <Tag color="green">{v.toLocaleString()}</Tag>
                      ) : (
                        <span style={{ color: OC.muted }}>—</span>
                      ),
                  },
                  {
                    title: '成本',
                    key: 'cost',
                    align: 'right',
                    render: (_, r) => {
                      const price = priceFor(prices, r.provider, r.model)
                      if (!price) return <span style={{ color: OC.muted }}>未定价</span>
                      return (
                        <span style={{ color: OC.textStrong, fontVariantNumeric: 'tabular-nums' }}>
                          {fmtCost(
                            estimateCost(price, {
                              inputTokens: r.inputTokens,
                              cachedInputTokens: r.cachedTokens,
                              outputTokens: r.outputTokens,
                            }),
                          )}
                        </span>
                      )
                    },
                  },
                ]}
              />
            ) : (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description={loading ? '加载中…' : '本月暂无使用记录 — 试着在 Chat 页发起一次对话吧'}
              />
            )}
          </Card>
        </Col>

        {me?.orgId && (
          <Col xs={24} lg={12}>
            <Card
              title={`组织用量（${me.orgId}，按用户）`}
              variant="borderless"
              style={{ background: OC.card }}
              styles={{ header: { borderBottom: `1px solid ${OC.border}`, color: OC.textStrong } }}
              loading={loading && !org}
              extra={
                <span style={{ fontSize: 11.5, color: OC.muted }}>该聚合无模型维度，未折算金额</span>
              }
            >
              {org && org.byUser && org.byUser.length > 0 ? (
                <Table
                  size="small"
                  pagination={false}
                  rowKey={(r, i) => `${r.userId}-${i}`}
                  dataSource={org.byUser}
                  columns={[
                    {
                      title: '用户',
                      dataIndex: 'userId',
                      render: (v) => <span style={{ ...mono, color: OC.textStrong }}>{v}</span>,
                    },
                    {
                      title: '调用',
                      dataIndex: 'modelCalls',
                      align: 'right',
                      render: (v) => v.toLocaleString(),
                    },
                    {
                      title: '输入',
                      dataIndex: 'inputTokens',
                      align: 'right',
                      render: (v) => v.toLocaleString(),
                    },
                    {
                      title: '输出',
                      dataIndex: 'outputTokens',
                      align: 'right',
                      render: (v) => v.toLocaleString(),
                    },
                  ]}
                />
              ) : (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description={loading ? '加载中…' : '组织内本月暂无使用记录'}
                />
              )}
            </Card>
          </Col>
        )}
      </Row>
    </div>
  )
}
