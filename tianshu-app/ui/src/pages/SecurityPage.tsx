import { Alert, Card, Tag } from 'antd'
import HotSettings from './HotSettings'
import { api } from '../api'
import { OC, useApi, Loading, ErrBox, mono } from '../ui'

type Policy = {
  order: number
  className: string
  key: string
  label: string
  detail: string
  kind: 'deployment' | 'governance' | 'commercial' | 'security'
  planAware: boolean
}

const KIND_META: Record<
  Policy['kind'],
  { color: string; text: string }
> = {
  commercial: { color: 'red', text: '随套餐联动' },
  security: { color: 'blue', text: '安全基线 · 全套餐一致' },
  deployment: { color: 'default', text: '部署级配置' },
  governance: { color: 'purple', text: '人设 / 组织治理' },
}

export default function SecurityPage() {
  const { data, loading, error } = useApi<Policy[]>(
    () => api.get<Policy[]>('/api/v1/tools/policies'),
    [],
  )

  const chain = data || []
  const planCount = chain.filter((p) => p.planAware).length

  return (
    <div style={{ padding: 24, maxWidth: 1000, margin: '0 auto' }}>
      <Alert
        style={{
          marginBottom: 16,
          background: OC.accentSubtle,
          border: `1px solid rgba(255,92,92,0.3)`,
        }}
        type="warning"
        showIcon
        message={`工具策略链 · 实际装配 ${chain.length} 层`}
        description={
          <div style={{ lineHeight: 1.8, fontSize: 13 }}>
            按固定顺序执行，任一策略 DENY 即拒绝、ASK 即转人工，规则异常一律 fail-closed。其中{' '}
            <b style={{ color: OC.textStrong }}>{planCount} 层随套餐联动</b>
            （能力授权属商业变量，升级即时生效）；其余为
            <b style={{ color: OC.textStrong }}> 安全基线，对所有套餐一致</b>
            ，付费也不放宽。DESTRUCTIVE 工具始终要求人工审批，预算自动审批不覆盖。
          </div>
        }
      />

      <Card
        style={{ marginBottom: 16 }}
        title={<span style={{ color: OC.textStrong }}>生效中的工具策略（实时）</span>}
        extra={
          <span style={{ color: OC.muted, fontSize: 12.5 }}>
            来源：运行时真实装配
          </span>
        }
      >
        {loading ? (
          <Loading rows={chain.length || 8} />
        ) : error ? (
          <ErrBox msg={`加载策略链失败：${error}`} />
        ) : (
          chain.map((p, i) => {
            const km = KIND_META[p.kind] || KIND_META.security
            return (
              <div key={p.className}>
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'flex-start',
                    gap: 14,
                    padding: '14px 2px',
                  }}
                >
                  <div
                    style={{
                      ...mono,
                      flex: '0 0 auto',
                      width: 26,
                      height: 26,
                      borderRadius: 8,
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      background: OC.bgElevated,
                      border: `1px solid ${OC.border}`,
                      color: OC.muted,
                    }}
                  >
                    {i + 1}
                  </div>
                  <div style={{ flex: '1 1 auto', minWidth: 0 }}>
                    <div
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: 8,
                        flexWrap: 'wrap',
                        marginBottom: 4,
                      }}
                    >
                      <span style={mono}>{p.key}</span>
                      <span style={{ color: OC.textStrong, fontWeight: 600 }}>
                        · {p.label}
                      </span>
                      <Tag color={km.color} style={{ marginInlineStart: 4 }}>
                        {km.text}
                      </Tag>
                    </div>
                    <div style={{ color: OC.muted, fontSize: 13, lineHeight: 1.7 }}>
                      {p.detail}
                    </div>
                  </div>
                </div>
                {i < chain.length - 1 && (
                  <div style={{ height: 1, background: OC.border }} />
                )}
              </div>
            )
          })
        )}
      </Card>

      <Alert
        style={{ marginBottom: 16 }}
        type="info"
        showIcon
        message="当前为只读视图"
        description="运行时参数属于部署级配置，需要 config:admin 权限。如需调整工具白名单、文件访问根目录或自动审批，请联系你的部署管理员授予该权限。"
      />

      <HotSettings prefix="tools." readOnly />
    </div>
  )
}
