import { Card, Segmented, Space } from 'antd'
import { DesktopOutlined, MoonOutlined, SunOutlined, CheckOutlined } from '@ant-design/icons'
import { ACCENTS, OC } from '../theme'
import { useAppearance } from '../appearance'

export default function AppearanceSettings() {
  const { mode, accent, setMode, setAccent, eff } = useAppearance()

  return (
    <Card
      title="外观"
      variant="borderless"
      style={{ background: OC.card, marginBottom: 16 }}
      styles={{ header: { borderBottom: `1px solid ${OC.border}`, color: OC.textStrong } }}
    >
      <div style={{ marginBottom: 20 }}>
        <div style={{ color: OC.muted, fontSize: 13, marginBottom: 10 }}>颜色模式</div>
        <Segmented
          value={mode}
          onChange={(v) => setMode(v as any)}
          options={[
            { label: '系统', value: 'system', icon: <DesktopOutlined /> },
            { label: '浅色', value: 'light', icon: <SunOutlined /> },
            { label: '深色', value: 'dark', icon: <MoonOutlined /> },
          ]}
        />
        <div style={{ color: OC.muted, fontSize: 12, marginTop: 8 }}>
          当前生效：{eff === 'dark' ? '深色' : '浅色'}
          {mode === 'system' ? '（跟随系统）' : ''}
        </div>
      </div>

      <div>
        <div style={{ color: OC.muted, fontSize: 13, marginBottom: 10 }}>主题色</div>
        <Space size={12} wrap>
          {ACCENTS.map((a) => {
            const selected = accent === a.id
            const color = a[eff]
            return (
              <div
                key={a.id}
                onClick={() => setAccent(a.id)}
                title={a.name}
                style={{
                  cursor: 'pointer',
                  width: 88,
                  borderRadius: OC.radiusMd,
                  border: `2px solid ${selected ? color : 'transparent'}`,
                  background: OC.bgElevated,
                  padding: '10px 8px',
                  textAlign: 'center',
                  transition: 'border-color .15s ease, transform .1s ease',
                  transform: selected ? 'translateY(-2px)' : 'none',
                }}
              >
                <div
                  style={{
                    width: 34,
                    height: 34,
                    borderRadius: '50%',
                    background: color,
                    margin: '0 auto 8px',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    color: '#fff',
                    boxShadow: `0 2px 10px ${color}55`,
                  }}
                >
                  {selected && <CheckOutlined style={{ fontSize: 14 }} />}
                </div>
                <div
                  style={{
                    fontSize: 12,
                    color: selected ? OC.textStrong : OC.muted,
                    fontWeight: selected ? 600 : 400,
                    whiteSpace: 'nowrap',
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                  }}
                >
                  {a.name.split(' · ')[0]}
                </div>
              </div>
            )
          })}
        </Space>
      </div>
    </Card>
  )
}
