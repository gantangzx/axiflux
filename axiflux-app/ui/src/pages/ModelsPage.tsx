import { useState, useEffect } from 'react'
import { Card, Col, Row, Table, Tag, Button, Select, App as AntApp, Space } from 'antd'
import { api, hasScope } from '../api'
import { useApi, mono, OC } from '../ui'

export default function ModelsPage() {
  const { message } = AntApp.useApp()
  const { data: models } = useApi(() => api.get<any[]>('/api/v1/models'), [])
  // /config requires config:admin; for non-admins treat it as null silently
  // so this page stays a read-only view rather than logging a failed call.
  const { data: cfg, reload } = useApi(
    () => api.get<any>('/api/v1/config').catch(() => null),
    [],
  )
  const [provider, setProvider] = useState<string | undefined>()
  const canAdmin = hasScope('config:admin')

  const rt = (cfg && cfg.config && cfg.config.llm && cfg.config.llm.routing) || {}
  const rs = (cfg && (cfg.runtimeStatus || cfg.runtime)) || {}
  const current = rs.defaultProvider || rt.defaultProvider || ''
  const extra = (cfg && cfg.config && cfg.config.llm && cfg.config.llm.extraProviders) || []
  const providers = [...new Set((models || []).map((m) => m.provider).filter(Boolean))]

  useEffect(() => {
    if (current) setProvider(current)
  }, [current])

  const save = async () => {
    try {
      await api.post('/api/v1/config/routing', { provider })
      message.success(`默认 Provider 已切换为 ${provider}`)
      reload()
    } catch (e: any) {
      message.error(e.message)
    }
  }

  return (
    <div style={{ padding: 24, maxWidth: 1200, margin: '0 auto' }}>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={12}>
          <Card
            title={
              <Space>
                默认路由
                <Tag color="purple">{rt.strategy || '?'}</Tag>
              </Space>
            }
            variant="borderless"
            style={{ background: OC.card, height: '100%' }}
            styles={{ header: { borderBottom: `1px solid ${OC.border}`, color: OC.textStrong } }}
          >
            <div style={{ color: OC.muted, fontSize: 13, marginBottom: 6 }}>默认 Provider</div>
            <Select
              style={{ width: '100%' }}
              value={provider}
              onChange={setProvider}
              disabled={!canAdmin}
              options={providers.map((p) => ({ label: p, value: p }))}
            />
            <div style={{ color: OC.muted, fontSize: 12, marginTop: 8, lineHeight: 1.6 }}>
              切换后立即生效（POST /api/v1/config/routing）。策略：{rt.strategy || '—'}，成本表：
              {Object.keys(rt.costs || {}).join(', ') || '空（能力路由兜底）'}
            </div>
            {canAdmin ? (
              <Button type="primary" style={{ marginTop: 14 }} onClick={save}>
                保存路由
              </Button>
            ) : (
              <div style={{ color: OC.muted, fontSize: 12, marginTop: 12 }}>
                当前账号缺少 <span style={mono}>config:admin</span>，路由切换为只读；如需修改请联系部署管理员。
              </div>
            )}
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card
            title="扩展 Provider（extra-providers）"
            variant="borderless"
            style={{ background: OC.card, height: '100%' }}
            styles={{ header: { borderBottom: `1px solid ${OC.border}`, color: OC.textStrong } }}
          >
            <Table
              size="small"
              rowKey={(r, i) => String(i)}
              pagination={false}
              dataSource={extra}
              locale={{ emptyText: '无' }}
              columns={[
                { title: '名称', dataIndex: 'name', render: (v) => <span style={mono}>{v || '—'}</span> },
                { title: '模型', dataIndex: 'model', render: (v) => <span style={mono}>{v || '—'}</span> },
              ]}
            />
          </Card>
        </Col>
      </Row>

      <Card
        title={`模型清单（${models?.length || 0}）`}
        variant="borderless"
        style={{ background: OC.card, marginTop: 16 }}
        styles={{ header: { borderBottom: `1px solid ${OC.border}`, color: OC.textStrong } }}
      >
        <Table
          size="small"
          rowKey={(r, i) => String(i)}
          pagination={false}
          dataSource={models || []}
          columns={[
            { title: '模型', dataIndex: 'model', render: (v) => <span style={{ ...mono, color: OC.textStrong }}>{v}</span> },
            { title: 'Provider', dataIndex: 'provider', render: (v) => <span style={mono}>{v}</span> },
          ]}
        />
      </Card>
    </div>
  )
}
