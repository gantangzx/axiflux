import { useState } from 'react'
import {
  Button,
  Card,
  Table,
  Space,
  Modal,
  Form,
  Input,
  Select,
  Popconfirm,
  Tag,
  Divider,
  App as AntApp,
} from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import { api } from '../api'
import { useApi, Loading, ErrBox, RiskTag, BoolTag, toList, mono, pillStyle, OC } from '../ui'
import AgentDetailView from './AgentDetailView'

const RISK_LEVELS = ['SAFE', 'READ', 'NETWORK', 'WRITE', 'DESTRUCTIVE']

export default function AgentsPage() {
  const { message } = AntApp.useApp()
  const { data, loading, error, reload } = useApi(() => api.get<any[]>('/api/v1/agents'), [])
  const { data: models } = useApi(() => api.get<any[]>('/api/v1/models'), [])
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<any | null>(null)
  const [detail, setDetail] = useState<any | null>(null)
  const [form] = Form.useForm()
  const [saving, setSaving] = useState(false)

  const providers = [...new Set((models || []).map((m) => m.provider).filter(Boolean))]

  const openCreate = () => {
    setEditing(null)
    form.resetFields()
    setOpen(true)
  }
  const openEdit = (a: any) => {
    setEditing(a)
    form.setFieldsValue({
      ...a,
      allowedTools: toList(a.allowedTools).join(','),
    })
    setOpen(true)
  }

  const submit = async () => {
    const v = await form.validateFields()
    setSaving(true)
    const payload = {
      ...v,
      allowedTools: v.allowedTools ? String(v.allowedTools) : undefined,
    }
    try {
      if (editing) await api.put(`/api/v1/agents/${encodeURIComponent(editing.agentId || editing.id)}`, payload)
      else await api.post('/api/v1/agents', payload)
      message.success(editing ? '已保存' : '已创建')
      setOpen(false)
      reload()
    } catch (e: any) {
      message.error(e.message)
    } finally {
      setSaving(false)
    }
  }

  const toggle = async (a: any) => {
    await api.put(`/api/v1/agents/${encodeURIComponent(a.agentId || a.id)}`, { enabled: a.enabled === false })
    reload()
  }
  const del = async (a: any) => {
    await api.del(`/api/v1/agents/${encodeURIComponent(a.agentId || a.id)}`)
    message.success('已删除')
    reload()
  }

  // Detail view takes over the whole page when an agent is selected.
  if (detail) {
    return (
      <AgentDetailView
        agent={detail}
        onBack={() => {
          setDetail(null)
          reload()
        }}
        onChanged={reload}
      />
    )
  }

  return (
    <div style={{ padding: 24, maxWidth: 1200, margin: '0 auto' }}>
      <Card
        title={`Agent 列表（${data?.length || 0}）`}
        variant="borderless"
        style={{ background: OC.card }}
        styles={{ header: { borderBottom: `1px solid ${OC.border}`, color: OC.textStrong } }}
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            新建 Agent
          </Button>
        }
      >
        {error && <ErrBox msg={error} />}
        {loading ? (
          <Loading />
        ) : (
          <Table
            size="small"
            rowKey={(r) => r.agentId || r.id}
            dataSource={data || []}
            pagination={false}
            columns={[
              { title: 'ID', dataIndex: 'agentId', render: (v, r) => <span style={mono}>{v || r.id}</span> },
              {
                title: '名称',
                render: (_, a) => (
                  <div onClick={() => setDetail(a)} style={{ cursor: 'pointer' }} title="点击查看详情">
                    <span style={{ color: OC.accent }}>{a.emoji || '🤖'} {a.name || '—'}</span>
                    {a.description && (
                      <div style={{ color: OC.muted, fontSize: 11, maxWidth: 240, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {a.description}
                      </div>
                    )}
                  </div>
                ),
              },
              { title: 'Provider', dataIndex: 'provider', render: (v) => v || '默认' },
              {
                title: '工具白名单',
                dataIndex: 'allowedTools',
                render: (v) => {
                  const t = toList(v)
                  return t.length ? (
                    t.slice(0, 4).map((x) => (
                      <span key={x} style={pillStyle}>
                        {x}
                      </span>
                    ))
                  ) : (
                    <span style={{ color: OC.muted }}>不限</span>
                  )
                },
              },
              { title: '风险上限', dataIndex: 'riskCeiling', render: (v) => <RiskTag level={v} /> },
              { title: '内置', dataIndex: 'builtin', render: (v) => <BoolTag v={!!v} /> },
              { title: '启用', dataIndex: 'enabled', render: (_, a) => <BoolTag v={a.enabled !== false} /> },
              {
                title: '',
                align: 'right',
                render: (_, a) => (
                  <Space>
                    <Button size="small" type="primary" ghost onClick={() => setDetail(a)}>
                      详情
                    </Button>
                    {a.builtin !== true && (
                      <Button size="small" onClick={() => openEdit(a)}>
                        编辑
                      </Button>
                    )}
                    <Button size="small" onClick={() => toggle(a)}>
                      {a.enabled === false ? '启用' : '禁用'}
                    </Button>
                    {a.builtin !== true && (
                      <Popconfirm title={`删除 Agent「${a.name}」？`} onConfirm={() => del(a)}>
                        <Button size="small" danger>
                          删除
                        </Button>
                      </Popconfirm>
                    )}
                  </Space>
                ),
              },
            ]}
          />
        )}
      </Card>

      <Modal
        title={editing ? '编辑 Agent' : '新建 Agent'}
        open={open}
        onCancel={() => setOpen(false)}
        onOk={submit}
        confirmLoading={saving}
        okText={editing ? '保存' : '创建'}
        cancelText="取消"
        width={640}
      >
        <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
          <Form.Item name="name" label="名称" rules={[{ required: true }]}>
            <Input placeholder="如：代码审查助手" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input placeholder="人设一句话描述" />
          </Form.Item>
          <Form.Item name="emoji" label="Emoji">
            <Input placeholder="🤖" style={{ width: 120 }} />
          </Form.Item>
          <Divider style={{ margin: '8px 0 4px', fontSize: 13, color: OC.muted }}>
            身份文件（对齐原生 Axiflux workspace）
          </Divider>
          <Form.Item
            name="operatingInstructions"
            label={<span style={mono}>AGENTS.md <span style={{ color: OC.muted, fontWeight: 400 }}>操作指令 / 行为规则</span></span>}
          >
            <Input.TextArea rows={3} placeholder="该 Agent 的操作规则、优先级、如何行事（可选）" />
          </Form.Item>
          <Form.Item
            name="soul"
            label={<span style={mono}>SOUL.md <span style={{ color: OC.muted, fontWeight: 400 }}>人格 / 语气 / 边界</span></span>}
          >
            <Input.TextArea rows={3} placeholder="语气、观点、简洁度、幽默、边界（可选）" />
          </Form.Item>
          <Form.Item
            name="userProfile"
            label={<span style={mono}>USER.md <span style={{ color: OC.muted, fontWeight: 400 }}>用户画像 / 称呼</span></span>}
          >
            <Input.TextArea rows={3} placeholder="用户是谁、如何称呼、偏好（可选）" />
          </Form.Item>
          <Form.Item name="systemPrompt" label={<span style={mono}>systemPrompt <span style={{ color: OC.muted, fontWeight: 400 }}>自由补充（最后拼接）</span></span>}>
            <Input.TextArea rows={3} placeholder="额外的系统提示（拼接在上述身份文件之后，可选）" />
          </Form.Item>
          <Space style={{ display: 'flex' }} align="start">
            <Form.Item name="provider" label="Provider" style={{ flex: 1 }}>
              <Select
                allowClear
                placeholder="（默认）"
                options={providers.map((p) => ({ label: p, value: p }))}
              />
            </Form.Item>
            <Form.Item name="riskCeiling" label="风险上限 RiskCeiling" style={{ flex: 1 }}>
              <Select
                allowClear
                placeholder="（不限）"
                options={RISK_LEVELS.map((r) => ({ label: r, value: r }))}
              />
            </Form.Item>
          </Space>
          <Form.Item name="allowedTools" label="允许工具白名单（逗号分隔，空 = 不限制）">
            <Input placeholder="http_client,calculator" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
