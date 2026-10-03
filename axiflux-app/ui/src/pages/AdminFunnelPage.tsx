import { Alert, Card, Progress, Space, Statistic, Table, Tag } from 'antd'
import { FilterOutlined } from '@ant-design/icons'
import { api } from '../api'
import { useApi, OC } from '../ui'

interface FunnelStage {
  stage: string
  label: string
  count: number
  stepRate: number
  overallRate: number
}

interface FunnelData {
  stages: FunnelStage[]
  signups: number
}

export default function AdminFunnelPage() {
  const { data, loading, error } = useApi(
    () => api.get<FunnelData>('/api/v1/admin/funnel'),
    [],
  )

  const stages = data?.stages ?? []
  const top = Math.max(1, ...stages.map((s) => s.count))

  return (
    <div style={{ padding: 24, maxWidth: 1000, margin: '0 auto' }}>
      <Card
        title={
          <Space>
            <FilterOutlined style={{ color: OC.accent }} />
            转化漏斗
          </Space>
        }
        variant="borderless"
        style={{ background: OC.card }}
        styles={{ header: { borderBottom: `1px solid ${OC.border}`, color: OC.textStrong } }}
      >
        {error && <Alert type="error" showIcon message="加载失败" description={error} />}
        {!error && (
          <>
            <div style={{ marginBottom: 20 }}>
              <Statistic
                title={<span style={{ color: OC.muted }}>累计注册</span>}
                value={data?.signups ?? 0}
                valueStyle={{ color: OC.textStrong }}
              />
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: 14, marginBottom: 24 }}>
              {stages.map((s) => (
                <div key={s.stage}>
                  <div
                    style={{
                      display: 'flex',
                      justifyContent: 'space-between',
                      marginBottom: 4,
                      color: OC.text,
                      fontSize: 13,
                    }}
                  >
                    <span>
                      <Tag style={{ marginRight: 8 }}>{s.label}</Tag>
                      <strong style={{ color: OC.textStrong }}>{s.count}</strong>
                    </span>
                    <span style={{ color: OC.muted, fontSize: 12 }}>
                      环比 {s.stepRate}% · 对注册 {s.overallRate}%
                    </span>
                  </div>
                  <Progress
                    percent={Math.round((s.count / top) * 100)}
                    showInfo={false}
                    strokeColor={OC.accent}
                    trailColor={OC.border}
                    size="small"
                  />
                </div>
              ))}
            </div>

            <Table
              size="small"
              rowKey="stage"
              loading={loading}
              dataSource={stages}
              pagination={false}
              columns={[
                { title: '阶段', dataIndex: 'label' },
                {
                  title: '人数 / 次数',
                  dataIndex: 'count',
                  render: (v) => <strong style={{ color: OC.textStrong }}>{v}</strong>,
                },
                {
                  title: '环比转化率',
                  dataIndex: 'stepRate',
                  render: (v: number) => `${v}%`,
                },
                {
                  title: '对注册转化率',
                  dataIndex: 'overallRate',
                  render: (v: number) => `${v}%`,
                },
              ]}
            />
          </>
        )}
      </Card>
    </div>
  )
}
