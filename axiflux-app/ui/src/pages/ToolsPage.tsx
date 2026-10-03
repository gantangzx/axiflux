import { useMemo, useState } from 'react'
import { Button, Space, Modal, Tag, Input, App as AntApp } from 'antd'
import { PlayCircleOutlined } from '@ant-design/icons'
import { api, getUserId } from '../api'
import { useApi, ErrBox, mono, OC, SkeletonRows, EmptyHint, RiskTag } from '../ui'
import { toolIcon, toolLabel } from '../toolview'
import { PlanLockTag } from '../billing'

const GROUP_LABEL: Record<string, string> = {
  builtin: '内置工具',
  mcp: 'MCP 工具',
  plugin: '插件工具',
}

export default function ToolsPage() {
  const { message } = AntApp.useApp()
  const { data, loading, error } = useApi(() => api.get<any[]>('/api/v1/tools'), [])
  const [schema, setSchema] = useState<any | null>(null)
  const [invoke, setInvoke] = useState<any | null>(null)
  const [args, setArgs] = useState('{}')
  const [result, setResult] = useState<string | null>(null)
  const [running, setRunning] = useState(false)

  const groups = useMemo(() => {
    const m = new Map<string, any[]>()
    for (const t of data || []) {
      const g = t.group || 'builtin'
      if (!m.has(g)) m.set(g, [])
      m.get(g)!.push(t)
    }
    return [...m.entries()]
  }, [data])

  const run = async () => {
    let parsed: unknown
    try {
      parsed = JSON.parse(args.trim() || '{}')
    } catch {
      message.error('参数不是合法 JSON')
      return
    }
    setRunning(true)
    setResult(null)
    try {
      const r: any = await api.post(`/api/v1/tools/${encodeURIComponent(invoke.name)}/invoke`, {
        arguments: parsed,
        userId: getUserId(),
      })
      setResult(r && r.success ? r.content || '(成功)' : r && (r.error || r.content) || '调用失败')
    } catch (e: any) {
      setResult('⚠️ ' + e.message)
    } finally {
      setRunning(false)
    }
  }

  return (
    <div style={{ padding: 24, maxWidth: 960, margin: '0 auto' }}>
      <div className="oc-panel">
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            padding: '13px 16px',
            borderBottom: `1px solid ${OC.border}`,
          }}
        >
          <div style={{ fontWeight: 650, fontSize: 14.5, color: OC.textStrong }}>
            工具清单
            <span style={{ color: OC.muted, fontWeight: 500, fontSize: 12.5, marginLeft: 8 }}>
              {loading ? '加载中…' : `${data?.length || 0} 个 · ${groups.length} 组`}
            </span>
          </div>
        </div>

        {error && <div style={{ padding: 16 }}><ErrBox msg={error} /></div>}
        {loading ? (
          <div style={{ padding: '8px 0' }}>
            <SkeletonRows rows={8} />
          </div>
        ) : !data || data.length === 0 ? (
          <EmptyHint icon={<PlayCircleOutlined />} title="没有可用工具" desc="工具由系统注册或通过 MCP / 插件扩展提供。" />
        ) : (
          groups.map(([g, list]) => (
            <div key={g}>
              <div className="oc-group-label">
                {GROUP_LABEL[g] || g} · {list.length}
              </div>
              <div className="oc-list">
                {list.map((t) => {
                  const TIcon = toolIcon(t.name)
                  return (
                    <div className="oc-row" key={t.name}>
                      <div className="oc-row__icon">
                        <TIcon />
                      </div>
                      <div className="oc-row__main">
                        <div className="oc-row__name">
                          <span style={mono}>{t.name}</span>
                          <span style={{ color: OC.muted, fontWeight: 500, fontFamily: 'Geist, sans-serif' }}>
                            {toolLabel(t.name)}
                          </span>
                        </div>
                        <div className="oc-row__desc">{t.description || '（无描述）'}</div>
                        <div className="oc-row__tags">
                          <RiskTag level={t.riskLevel} />
                          <PlanLockTag feature={t.name} fallback="pro" />
                          {t.requiresApproval && (
                            <Tag color="orange" style={{ marginInlineEnd: 0 }}>
                              需审批
                            </Tag>
                          )}
                        </div>
                      </div>
                      <div className="oc-row__actions">
                        <Button size="small" type="text" onClick={() => setSchema(t)}>
                          Schema
                        </Button>
                        <Button
                          size="small"
                          type="primary"
                          ghost
                          onClick={() => {
                            setInvoke(t)
                            setArgs('{}')
                            setResult(null)
                          }}
                        >
                          调用
                        </Button>
                      </div>
                    </div>
                  )
                })}
              </div>
            </div>
          ))
        )}
      </div>

      <Modal title={schema ? `工具 ${schema.name} · 参数 Schema` : ''} open={!!schema} onCancel={() => setSchema(null)} footer={null} width={640}>
        <pre style={{ ...mono, background: OC.bg, border: `1px solid ${OC.border}`, borderRadius: 10, padding: 14, color: OC.text, whiteSpace: 'pre-wrap', wordBreak: 'break-word', maxHeight: '60vh', overflow: 'auto' }}>
          {JSON.stringify(schema?.parameters || schema?.inputSchema || {}, null, 2)}
        </pre>
      </Modal>

      <Modal
        title={invoke ? `直调工具 ${invoke.name}` : ''}
        open={!!invoke}
        onCancel={() => setInvoke(null)}
        onOk={run}
        confirmLoading={running}
        okText="调用"
        cancelText="取消"
        width={640}
      >
        <div style={{ color: OC.muted, fontSize: 12, marginBottom: 8 }}>
          点击「调用」会直接执行该工具（绕过 LLM）；需审批的工具将被拒绝。
        </div>
        <Input.TextArea
          rows={5}
          value={args}
          onChange={(e) => setArgs(e.target.value)}
          placeholder='{"url": "https://example.com"}'
          style={{ ...mono }}
        />
        {result != null && (
          <div style={{ marginTop: 12 }}>
            <div style={{ color: OC.muted, fontSize: 12, marginBottom: 4 }}>结果</div>
            <pre style={{ ...mono, background: OC.bg, border: `1px solid ${OC.border}`, borderRadius: 10, padding: 12, color: result.startsWith('⚠️') ? '#ff9a9a' : OC.text, whiteSpace: 'pre-wrap', wordBreak: 'break-word', maxHeight: 300, overflow: 'auto' }}>
              {result}
            </pre>
          </div>
        )}
      </Modal>
    </div>
  )
}
