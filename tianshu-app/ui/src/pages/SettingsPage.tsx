import { Card, Col, Row } from 'antd'
import HotSettings from './HotSettings'
import AppearanceSettings from './AppearanceSettings'
import { api } from '../api'
import { useApi, mono, OC } from '../ui'

export default function SettingsPage() {
  const { data: cfg } = useApi(() => api.get<any>('/api/v1/config'), [])
  const rs = (cfg && (cfg.runtimeStatus || cfg.runtime)) || {}

  const info: [string, string][] = [
    ['版本', rs.version || '0.1.0'],
    ['默认 Provider', rs.defaultProvider || '—'],
    ['会话存储', rs.sessionProvider || '—'],
    ['向量记忆', rs.vectorProvider || '—'],
    ['技能目录', rs.skillsRootDir || '—'],
    ['最大迭代', rs.maxIterations ?? '—'],
  ]

  return (
    <div style={{ padding: 24, maxWidth: 1000, margin: '0 auto' }}>
      <AppearanceSettings />
      <Card
        title="系统信息"
        variant="borderless"
        style={{ background: OC.card, marginBottom: 16 }}
        styles={{ header: { borderBottom: `1px solid ${OC.border}`, color: OC.textStrong } }}
      >
        <Row gutter={[12, 8]}>
          {info.map(([k, v]) => (
            <Col xs={24} sm={12} key={k}>
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13, padding: '6px 0' }}>
                <span style={{ color: OC.muted }}>{k}</span>
                <span style={{ ...mono, color: OC.textStrong }}>{v}</span>
              </div>
            </Col>
          ))}
        </Row>
      </Card>
      <HotSettings />
    </div>
  )
}
