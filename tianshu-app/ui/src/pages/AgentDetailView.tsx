import { useEffect, useState } from 'react'
import {
  Button,
  Card,
  Form,
  Input,
  Select,
  Space,
  Tabs,
  App as AntApp,
} from 'antd'
import {
  ArrowLeftOutlined,
  SaveOutlined,
  FileTextOutlined,
  LockOutlined,
} from '@ant-design/icons'
import { api } from '../api'
import { useApi, Loading, ErrBox, RiskTag, BoolTag, toList, mono, OC } from '../ui'

const RISK_LEVELS = ['SAFE', 'READ', 'NETWORK', 'WRITE', 'DESTRUCTIVE']

interface AgentFile {
  name: string
  content: string
  readOnly: boolean
  updatedAt: string | null
  size: number
}

/** Per-file metadata: Chinese blurb + native-Tianshu semantic, keyed by file name. */
const FILE_META: Record<string, { blurb: string; hint: string }> = {
  'AGENTS.md': {
    blurb: '操作指令',
    hint: '该 Agent 的操作规则、优先级、如何行事。原生 Tianshu 每个会话加载。',
  },
  'SOUL.md': {
    blurb: '人格 / 语气',
    hint: '人格、语气、观点、简洁度、幽默、边界。原生 Tianshu 每个会话加载。',
  },
  'USER.md': {
    blurb: '用户画像',
    hint: '用户是谁、如何称呼、偏好。原生 Tianshu 每个会话加载。',
  },
  'IDENTITY.md': {
    blurb: '身份卡（只读）',
    hint: '由名称 / Emoji / 描述字段合成，不可直接编辑。要改请到「配置」页。',
  },
}

const FILE_ORDER = ['AGENTS.md', 'SOUL.md', 'USER.md', 'IDENTITY.md']

/** Order files canonically and tolerate extra files the backend may add later. */
function orderFiles(files: AgentFile[]): AgentFile[] {
  const byName = new Map(files.map((f) => [f.name, f]))
  const known = FILE_ORDER.map((n) => byName.get(n)).filter(Boolean) as AgentFile[]
  const extra = files.filter((f) => !FILE_ORDER.includes(f.name))
  return [...known, ...extra]
}

function IdentityFilesTab({ agentId }: { agentId: string }) {
  const { message } = AntApp.useApp()
  const { data, loading, error, reload } = useApi(
    () => api.get<AgentFile[]>(`/api/v1/agents/${encodeURIComponent(agentId)}/files`),
    [agentId],
  )
  const [active, setActive] = useState<string>('AGENTS.md')
  const [draft, setDraft] = useState<string>('')
  const [dirty, setDirty] = useState(false)
  const [saving, setSaving] = useState(false)

  const files = orderFiles(data || [])
  const current = files.find((f) => f.name === active)

  // Sync the editor when the file list loads or the active file changes, unless the
  // user has unsaved edits (don't clobber their draft on a background reload).
  useEffect(() => {
    if (current && !dirty) setDraft(current.content)
  }, [active, data]) // eslint-disable-line react-hooks/exhaustive-deps

  const select = (name: string) => {
    if (dirty && current && name !== current.name) {
      if (!window.confirm(`「${current.name}」有未保存的修改，切换将丢弃。继续？`)) return
    }
    setDirty(false)
    setActive(name)
  }

  const save = async () => {
    if (!current) return
    setSaving(true)
    try {
      await api.put(
        `/api/v1/agents/${encodeURIComponent(agentId)}/files/${encodeURIComponent(current.name)}`,
        { content: draft },
      )
      message.success(`${current.name} 已保存`)
      setDirty(false)
      reload()
    } catch (e: any) {
      message.error(e.message)
    } finally {
      setSaving(false)
    }
  }

  if (error) return <ErrBox msg={error} />
  if (loading && !data) return <Loading />

  return (
    <div style={{ display: 'flex', gap: 16, minHeight: 420 }}>
      {/* File tree */}
      <div
        style={{
          width: 210,
          flexShrink: 0,
          border: `1px solid ${OC.border}`,
          borderRadius: 8,
          overflow: 'hidden',
          background: OC.bg,
        }}
      >
        {files.map((f) => {
          const meta = FILE_META[f.name]
          const isActive = f.name === active
          return (
            <div
              key={f.name}
              onClick={() => select(f.name)}
              style={{
                padding: '10px 12px',
                cursor: 'pointer',
                background: isActive ? OC.card : 'transparent',
                borderLeft: isActive ? `2px solid ${OC.accent}` : '2px solid transparent',
                borderBottom: `1px solid ${OC.border}`,
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <FileTextOutlined style={{ color: isActive ? OC.textStrong : OC.muted, fontSize: 13 }} />
                <span style={{ ...mono, fontSize: 13, color: isActive ? OC.textStrong : OC.text }}>
                  {f.name}
                </span>
                {f.readOnly && <LockOutlined style={{ color: OC.muted, fontSize: 11, marginLeft: 'auto' }} />}
              </div>
              {meta && (
                <div style={{ color: OC.muted, fontSize: 11, marginTop: 3, paddingLeft: 19 }}>
                  {meta.blurb}
                </div>
              )}
            </div>
          )
        })}
      </div>

      {/* Editor */}
      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
        {current && (
          <>
            <div style={{ display: 'flex', alignItems: 'center', marginBottom: 8, gap: 8 }}>
              <span style={{ ...mono, fontSize: 14, color: OC.textStrong, fontWeight: 600 }}>
                {current.name}
              </span>
              {current.readOnly ? (
                <span style={{ color: OC.muted, fontSize: 12 }}>
                  <LockOutlined /> 只读 · 由基础字段合成
                </span>
              ) : (
                dirty && <span style={{ color: '#faad14', fontSize: 12 }}>● 未保存</span>
              )}
              <span style={{ flex: 1 }} />
              {!current.readOnly && (
                <Button
                  type="primary"
                  size="small"
                  icon={<SaveOutlined />}
                  onClick={save}
                  loading={saving}
                  disabled={!dirty}
                >
                  保存
                </Button>
              )}
            </div>
            {FILE_META[current.name] && (
              <div style={{ color: OC.muted, fontSize: 12, marginBottom: 8 }}>
                {FILE_META[current.name].hint}
              </div>
            )}
            <Input.TextArea
              value={current.readOnly ? current.content : draft}
              readOnly={current.readOnly}
              onChange={(e) => {
                if (current.readOnly) return
                setDraft(e.target.value)
                setDirty(true)
              }}
              placeholder={
                current.readOnly
                  ? ''
                  : `填写 ${current.name} 内容（Markdown），留空则不注入该文件`
              }
              style={{
                flex: 1,
                minHeight: 320,
                fontFamily: 'JetBrains Mono, ui-monospace, monospace',
                fontSize: 13,
                background: current.readOnly ? OC.bg : undefined,
                resize: 'vertical',
              }}
            />
          </>
        )}
      </div>
    </div>
  )
}

function ConfigTab({ agent, onSaved }: { agent: any; onSaved: () => void }) {
  const { message } = AntApp.useApp()
  const [form] = Form.useForm()
  const [saving, setSaving] = useState(false)
  const { data: models } = useApi(() => api.get<any[]>('/api/v1/models'), [])
  const providers = [...new Set((models || []).map((m) => m.provider).filter(Boolean))]

  const submit = async () => {
    const v = await form.validateFields()
    setSaving(true)
    const payload = {
      ...v,
      allowedTools: v.allowedTools ? String(v.allowedTools) : undefined,
    }
    try {
      await api.put(`/api/v1/agents/${encodeURIComponent(agent.agentId || agent.id)}`, payload)
      message.success('配置已保存')
      onSaved()
    } catch (e: any) {
      message.error(e.message)
    } finally {
      setSaving(false)
    }
  }

  const readOnly = agent.builtin === true

  return (
    <Form
      form={form}
      layout="vertical"
      style={{ maxWidth: 640 }}
      initialValues={{ ...agent, allowedTools: toList(agent.allowedTools).join(',') }}
      disabled={readOnly}
    >
      {readOnly && (
        <div style={{ color: OC.muted, fontSize: 12, marginBottom: 12 }}>
          内置 Agent 仅可查看，不可编辑配置。身份文件可在「身份文件」页编辑。
        </div>
      )}
      <Form.Item name="name" label="名称" rules={[{ required: true }]}>
        <Input />
      </Form.Item>
      <Form.Item name="description" label="描述">
        <Input />
      </Form.Item>
      <Form.Item name="emoji" label="Emoji">
        <Input style={{ width: 120 }} />
      </Form.Item>
      <Form.Item
        name="systemPrompt"
        label={<span style={mono}>systemPrompt <span style={{ color: OC.muted, fontWeight: 400 }}>自由补充（拼接在身份文件之后）</span></span>}
      >
        <Input.TextArea rows={4} />
      </Form.Item>
      <Space style={{ display: 'flex' }} align="start">
        <Form.Item name="provider" label="Provider" style={{ flex: 1, minWidth: 200 }}>
          <Select allowClear placeholder="（默认）" options={providers.map((p) => ({ label: p, value: p }))} />
        </Form.Item>
        <Form.Item name="riskCeiling" label="风险上限" style={{ flex: 1, minWidth: 200 }}>
          <Select allowClear placeholder="（不限）" options={RISK_LEVELS.map((r) => ({ label: r, value: r }))} />
        </Form.Item>
      </Space>
      <Form.Item name="allowedTools" label="允许工具白名单（逗号分隔，空 = 不限制）">
        <Input placeholder="http_client,calculator" />
      </Form.Item>
      {!readOnly && (
        <Button type="primary" icon={<SaveOutlined />} onClick={submit} loading={saving}>
          保存配置
        </Button>
      )}
    </Form>
  )
}

export default function AgentDetailView({
  agent,
  onBack,
  onChanged,
}: {
  agent: any
  onBack: () => void
  onChanged: () => void
}) {
  const agentId = agent.agentId || agent.id
  return (
    <div style={{ padding: 24, maxWidth: 1200, margin: '0 auto' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={onBack}>
          返回列表
        </Button>
        <span style={{ fontSize: 20 }}>{agent.emoji || '🤖'}</span>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: 16, fontWeight: 600, color: OC.textStrong }}>
            {agent.name || agentId}
            <span style={{ ...mono, fontSize: 12, color: OC.muted, fontWeight: 400, marginLeft: 10 }}>
              {agentId}
            </span>
          </div>
          {agent.description && (
            <div style={{ color: OC.muted, fontSize: 12, marginTop: 2 }}>{agent.description}</div>
          )}
        </div>
        <Space>
          <BoolTag v={agent.enabled !== false} />
          <RiskTag level={agent.riskCeiling} />
        </Space>
      </div>

      <Card variant="borderless" style={{ background: OC.card }}>
        <Tabs
          defaultActiveKey="files"
          items={[
            {
              key: 'files',
              label: '身份文件',
              children: <IdentityFilesTab agentId={agentId} />,
            },
            {
              key: 'config',
              label: '配置',
              children: <ConfigTab agent={agent} onSaved={onChanged} />,
            },
          ]}
        />
      </Card>
    </div>
  )
}
