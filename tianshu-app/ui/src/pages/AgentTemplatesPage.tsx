import { useState } from 'react'
import { Button, Card, Space, Tag, Input, App as AntApp, Modal, Form, Tooltip, Empty } from 'antd'
import { RobotOutlined, SearchOutlined, PlusOutlined, CheckCircleOutlined, ThunderboltOutlined } from '@ant-design/icons'
import { api } from '../api'
import { useApi, Loading, ErrBox, OC } from '../ui'

export default function AgentTemplatesPage() {
  const { message } = AntApp.useApp()
  const { data: templates, loading, error, reload } = useApi(() => api.get<any[]>('/api/v1/templates'), [])
  const [detail, setDetail] = useState<any | null>(null)
  const [installOpen, setInstallOpen] = useState(false)
  const [installing, setInstalling] = useState(false)
  const [form] = Form.useForm()

  const install = async (tpl: any) => {
    setDetail(tpl)
    form.resetFields()
    form.setFieldsValue({ name: '我的 ' + tpl.name })
    setInstallOpen(true)
  }

  const submitInstall = async () => {
    const v = await form.validateFields()
    setInstalling(true)
    try {
      const r: any = await api.post(`/api/v1/templates/${detail.templateId}/create`, { name: v.name })
      message.success(`Agent「${r.name}」创建成功，可去代理页查看`)
      setInstallOpen(false)
    } catch (e: any) {
      message.error(e?.message || String(e))
    } finally {
      setInstalling(false)
    }
  }

  return (
    <div style={{ padding: 24, maxWidth: 1200, margin: '0 auto' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 24 }}>
        <RobotOutlined style={{ fontSize: 22, color: OC.accent }} />
        <div>
          <div style={{ fontSize: 16, fontWeight: 600, color: OC.textStrong }}>Agent 模板市场</div>
          <div style={{ fontSize: 12, color: OC.muted }}>系统预置专业角色模板，一键创建专属 Agent</div>
        </div>
        <div style={{ marginLeft: 'auto' }}>
          <Tooltip title="刷新">
            <Button icon={<SearchOutlined />} onClick={reload} />
          </Tooltip>
        </div>
      </div>

      {loading && <Loading />}
      {error && <ErrBox msg={error} />}
      {!loading && !error && (!templates || templates.length === 0) && (
        <Empty description="暂无可用模板" />
      )}
      {!loading && !error && templates && templates.length > 0 && (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(340px, 1fr))', gap: 16 }}>
          {templates.map((tpl) => (
            <Card
              key={tpl.templateId}
              variant="outlined"
              style={{ background: OC.card, border: `1px solid ${OC.border}` }}
              styles={{ body: { padding: 20 } }}
            >
              <div style={{ display: 'flex', alignItems: 'flex-start', gap: 14 }}>
                <div style={{
                  width: 48, height: 48, borderRadius: 10,
                  background: OC.accentSubtle,
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  fontSize: 22, flexShrink: 0,
                }}>
                  {tpl.emoji || '🤖'}
                </div>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontSize: 15, fontWeight: 600, color: OC.textStrong, marginBottom: 4 }}>
                    {tpl.name}
                  </div>
                  <div style={{ fontSize: 12, color: OC.muted, lineHeight: 1.5, marginBottom: 12 }}>
                    {tpl.description || '暂无描述'}
                  </div>
                  <Button
                    type="primary"
                    icon={<PlusOutlined />}
                    size="small"
                    onClick={() => install(tpl)}
                  >
                    基于此模板创建
                  </Button>
                </div>
              </div>
            </Card>
          ))}
        </div>
      )}

      <Modal
        title="创建 Agent"
        open={installOpen}
        onCancel={() => setInstallOpen(false)}
        onOk={submitInstall}
        confirmLoading={installing}
        okText="创建"
        cancelText="取消"
        width={540}
      >
        {detail && (
          <>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 16, padding: 12, background: OC.bg, borderRadius: 8 }}>
              <span style={{ fontSize: 20 }}>{detail.emoji || '🤖'}</span>
              <div>
                <div style={{ fontSize: 13, color: OC.textStrong, fontWeight: 600 }}>{detail.name}</div>
                <div style={{ fontSize: 12, color: OC.muted }}>{detail.description}</div>
              </div>
            </div>
            <Form form={form} layout="vertical">
              <Form.Item
                name="name"
                label="Agent 名称"
                rules={[{ required: true, message: '请输入 Agent 名称' }]}
              >
                <Input placeholder="给我的 Agent 起个名字" maxLength={64} showCount />
              </Form.Item>
            </Form>
          </>
        )}
      </Modal>
    </div>
  )
}
