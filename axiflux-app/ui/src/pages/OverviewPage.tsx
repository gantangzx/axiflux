import { Card, Col, Row, Button, Table, Tag } from 'antd'
import {
  AppstoreOutlined,
  ToolOutlined,
  MessageOutlined,
  ClockCircleOutlined,
} from '@ant-design/icons'
import { api, getUserId } from '../api'
import { useApi, mono, OC } from '../ui'

export default function OverviewPage({ go }: { go: (k: string) => void }) {
  const { data: cfg, loading: l1 } = useApi(() => api.get<any>('/api/v1/config'), [])
  const { data: models, loading: l2 } = useApi(() => api.get<any[]>('/api/v1/models'), [])
  const { data: sessions, loading: l3 } = useApi(() => api.get<any[]>(`/api/v1/sessions?userId=${getUserId()}`), [])
  const { data: tools, loading: l4 } = useApi(() => api.get<any[]>('/api/v1/tools'), [])
  const { data: tasks } = useApi(() => api.get<any[]>('/api/v1/scheduler/tasks'), [])
  const { data: approvals } = useApi(() => api.get<any[]>('/api/v1/approvals'), [])

  const rs = (cfg && (cfg.runtimeStatus || cfg.runtime)) || {}
  const rt = (cfg && cfg.config && cfg.config.llm && cfg.config.llm.routing) || {}
  const provs = rs.providers || []
  const active = (sessions || []).filter((s) => s.state === 'ACTIVE').length
  const statsLoading = l1 || l2 || l3 || l4

  const stats = [
    { title: '可用模型', value: (models || []).length, sub: `${provs.length || '?'} 个 Provider`, icon: <AppstoreOutlined /> },
    { title: '工具', value: (tools || []).length, sub: '内置 + 扩展', icon: <ToolOutlined /> },
    { title: '会话', value: (sessions || []).length, sub: `${active} 个活跃`, icon: <MessageOutlined /> },
    { title: '定时任务', value: (tasks || []).length, sub: `${(tasks || []).filter((t) => t.enabled).length} 个启用`, icon: <ClockCircleOutlined /> },
  ]

  const kv: [string, string][] = [
    ['路由策略', rt.strategy || '—'],
    ['默认 Provider', rs.defaultProvider || rt.defaultProvider || '—'],
    ['默认模型', rt.defaultModel || '—'],
    ['会话存储', rs.sessionProvider || '—'],
    ['向量记忆', rs.vectorProvider || '—'],
    ['技能目录', rs.skillsRootDir || '—'],
    ['最大迭代', rs.maxIterations ?? '—'],
  ]

  return (
    <div style={{ padding: 24, maxWidth: 1200, margin: '0 auto' }}>
      <Row gutter={[16, 16]}>
        {stats.map((s, i) => (
          <Col xs={12} md={6} key={s.title}>
            <div className="oc-stat" style={{ animationDelay: `${i * 70}ms` }}>
              <div className="oc-stat__icon">{s.icon}</div>
              {statsLoading ? (
                <div className="oc-skel" style={{ height: 30, width: 64, marginTop: 12 }} />
              ) : (
                <div className="oc-stat__value">{s.value}</div>
              )}
              <div className="oc-stat__title">{s.title}</div>
              <div className="oc-stat__sub">{s.sub}</div>
            </div>
          </Col>
        ))}
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} md={12}>
          <Card
            title="路由与存储"
            variant="borderless"
            className="oc-panel"
            style={{ height: '100%' }}
            styles={{ header: { color: OC.textStrong } }}
          >
            {kv.map(([k, v]) => (
              <div
                key={k}
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  padding: '7px 0',
                  borderBottom: `1px solid ${OC.border}`,
                  fontSize: 13,
                }}
              >
                <span style={{ color: OC.muted }}>{k}</span>
                <span style={{ ...mono, color: OC.textStrong }}>{v}</span>
              </div>
            ))}
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card
            title="快捷操作"
            variant="borderless"
            className="oc-panel"
            style={{ height: '100%' }}
            styles={{ header: { color: OC.textStrong } }}
          >
            <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
              <Button type="primary" onClick={() => go('chat')}>💬 开始对话</Button>
              <Button onClick={() => go('scheduler')}>⏰ 定时任务</Button>
              <Button onClick={() => go('approvals')}>
                ✅ 审批队列 {Array.isArray(approvals) && approvals.length > 0 ? `(${approvals.length})` : ''}
              </Button>
              <Button onClick={() => go('audit')}>📊 审计</Button>
            </div>
            <div style={{ color: OC.muted, fontSize: 12.5, marginTop: 16, lineHeight: 1.7 }}>
              直接去对话、管理定时任务，或处理待审批请求。审批队列当前{' '}
              {Array.isArray(approvals) ? approvals.length : '—'} 项。
            </div>
          </Card>
        </Col>
      </Row>

      <Card
        title="Provider 注册表"
        variant="borderless"
        className="oc-panel"
        style={{ marginTop: 16 }}
        styles={{ header: { color: OC.textStrong } }}
      >
        <Table
          size="small"
          rowKey={(r, i) => String(i)}
          pagination={false}
          dataSource={provs}
          columns={[
            { title: 'Provider', dataIndex: 'name', render: (v) => <span style={mono}>{v || '?'}</span> },
            { title: '默认模型', dataIndex: 'model', render: (v) => <span style={mono}>{v || '—'}</span> },
            { title: '来源', dataIndex: 'provider', render: (v) => <Tag>{v || '—'}</Tag> },
          ]}
        />
      </Card>
    </div>
  )
}
