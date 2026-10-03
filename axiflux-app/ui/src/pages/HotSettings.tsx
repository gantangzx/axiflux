import { useState, useEffect } from 'react'
import { Card, Input, InputNumber, Select, Button, Space, Tag, Segmented, App as AntApp } from 'antd'
import { api } from '../api'
import { Loading, ErrBox, OC } from '../ui'

/** tools.auto-approve 专用：点击弹出全部工具，已选为 tag 带 ✕ */
function ToolAutoApproveSelect({ value, onChange }: { value: string[]; onChange: (v: string[]) => void }) {
  const [tools, setTools] = useState<{ name: string; description?: string }[]>([])
  useEffect(() => {
    api.get<any>('/api/v1/tools').then((d) => {
      const arr = Array.isArray(d) ? d : d.tools || d.items || []
      setTools(arr.map((t: any) => ({ name: t.name || t, description: t.description })))
    }).catch(() => {})
  }, [])
  return (
    <Select
      mode="multiple"
      variant="borderless"
      style={{ flex: 1, minWidth: 0 }}
      placeholder="点击选择免审批工具"
      value={value}
      onChange={onChange}
      options={tools.map((t) => ({ label: t.name, value: t.name, title: t.description }))}
      popupMatchSelectWidth={false}
      allowClear
      showSearch
      filterOption={(input, opt) => (opt?.value as string)?.toLowerCase().includes(input.toLowerCase())}
    />
  )
}

type SettingItem = {
  key: string
  type: string
  description?: string
  hotReload?: boolean
  options?: string[]
  value: unknown
  overridden?: boolean
}

function Control({ item, onSaved }: { item: SettingItem; onSaved: () => void }) {
  const { message } = AntApp.useApp()
  const [val, setVal] = useState<unknown>(item.value)
  const [saving, setSaving] = useState(false)

  useEffect(() => setVal(item.value), [item.value])

  const save = async () => {
    setSaving(true)
    try {
      await api.put('/api/v1/config/live', { key: item.key, value: val })
      message.success('已应用')
      onSaved()
    } catch (e: any) {
      message.error(e.message)
    } finally {
      setSaving(false)
    }
  }
  const reset = async () => {
    setSaving(true)
    try {
      await api.del(`/api/v1/config/override/${encodeURIComponent(item.key)}`)
      message.success('已重置')
      onSaved()
    } catch (e: any) {
      message.error(e.message)
    } finally {
      setSaving(false)
    }
  }

  // 一体化输入控件：控件与保存/重置按钮同处一个圆润容器，按钮内嵌右侧
  let body: React.ReactNode
  let multiline = false
  if (item.type === 'enum' || (item.options && item.options.length)) {
    body = (
      <Select
        variant="borderless"
        style={{ flex: 1, minWidth: 0 }}
        value={val as string}
        onChange={setVal}
        options={(item.options || []).map((o) => ({ label: o, value: o }))}
      />
    )
  } else if (item.type === 'bool') {
    body = (
      <div style={{ flex: 1, minWidth: 0, padding: '3px 0 3px 10px' }}>
        <Segmented
          value={String(val)}
          onChange={(v) => setVal(v === 'true')}
          options={[
            { label: '是', value: 'true' },
            { label: '否', value: 'false' },
          ]}
        />
      </div>
    )
  } else if (item.type === 'int' || item.type === 'long') {
    body = (
      <InputNumber
        variant="borderless"
        style={{ flex: 1, minWidth: 0, width: '100%' }}
        value={val as number}
        onChange={(v) => setVal(v ?? undefined)}
      />
    )
  } else if (item.type === 'json') {
    multiline = true
    body = (
      <Input.TextArea
        variant="borderless"
        rows={4}
        style={{ fontFamily: 'monospace', fontSize: 12.5 }}
        value={typeof val === 'object' ? JSON.stringify(val, null, 2) : (val as string) ?? ''}
        onChange={(e) => {
          try {
            setVal(JSON.parse(e.target.value))
          } catch {
            setVal(e.target.value)
          }
        }}
      />
    )
  } else if (item.key === 'tools.auto-approve') {
    // 专用多选：点击弹出全部工具，已选 tag 带 ✕
    const csv = val == null ? '' : Array.isArray(val) ? val.join(',') : String(val)
    const selected = csv ? csv.split(',').map((s: string) => s.trim()).filter(Boolean) : []
    body = (
      <ToolAutoApproveSelect
        value={selected}
        onChange={(v) => setVal(v.join(','))}
      />
    )
  } else {
    body = (
      <Input
        variant="borderless"
        style={{ flex: 1, minWidth: 0 }}
        value={val == null ? '' : Array.isArray(val) ? val.join(',') : String(val)}
        onChange={(e) => setVal(e.target.value)}
      />
    )
  }

  return (
    <div className={multiline ? 'oc-field oc-field-multi' : 'oc-field'}>
      {body}
      <div className="oc-field-actions">
        {item.overridden && (
          <Button type="text" danger disabled={saving} onClick={reset}>
            重置
          </Button>
        )}
        <Button type="primary" loading={saving} onClick={save}>
          保存
        </Button>
      </div>
    </div>
  )
}

export default function HotSettings({ prefix, readOnly }: { prefix?: string; readOnly?: boolean }) {
  const [items, setItems] = useState<SettingItem[] | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const [forbidden, setForbidden] = useState(false)
  const [tick, setTick] = useState(0)

  useEffect(() => {
    let alive = true
    setItems(null)
    setForbidden(false)
    api
      .get<any>('/api/v1/config/settings')
      .then((d) => {
        if (!alive) return
        const arr: SettingItem[] = Array.isArray(d) ? d : d.items || d.settings || []
        setItems(arr.filter((i) => !prefix || (i.key || '').startsWith(prefix)))
      })
      .catch((e) => {
        if (!alive) return
        if (String(e?.message).includes(' 403 ')) {
          setForbidden(true)
        } else {
          setErr(e.message)
        }
      })
    return () => {
      alive = false
    }
  }, [prefix, tick])

  // 安全策略页（readOnly）：调用方无 config:admin 时由 SecurityPage 统一展示
  // 只读说明，这里不重复渲染；非只读场景仍给出红框错误。
  if (forbidden) {
    if (readOnly) return null
    return <ErrBox msg="无 config:admin 权限，无法查看运行时配置。" />
  }
  if (err) return <ErrBox msg={err} />
  if (!items) return <Loading label="加载设置…" />
  if (items.length === 0) return <ErrBox msg="无匹配设置项" />

  const byGroup: Record<string, SettingItem[]> = {}
  items.forEach((i) => {
    const g = i.key.split('.')[0] || 'other'
    ;(byGroup[g] = byGroup[g] || []).push(i)
  })

  return (
    <>
      {Object.entries(byGroup).map(([g, list]) => (
        <Card
          key={g}
          variant="borderless"
          style={{ background: OC.card, marginBottom: 16 }}
          styles={{ header: { borderBottom: `1px solid ${OC.border}`, color: OC.textStrong } }}
          title={
            <Space>
              <span style={{ textTransform: 'capitalize' }}>{g}</span>
              <Tag>{list.length}</Tag>
              <span style={{ color: OC.muted, fontSize: 11, fontWeight: 400 }}>修改即时热生效</span>
            </Space>
          }
        >
          {list.map((item, idx) => (
            <div
              key={item.key}
              style={{
                padding: '14px 0',
                borderBottom: idx === list.length - 1 ? 'none' : `1px solid ${OC.border}`,
              }}
            >
              <Space wrap size={6} style={{ marginBottom: 6 }}>
                <span style={{ fontFamily: 'monospace', fontSize: 12.5, fontWeight: 600, color: OC.textStrong }}>
                  {item.key}
                </span>
                {item.overridden && <Tag color="purple">已覆盖</Tag>}
                {item.hotReload && <Tag color="green">热更</Tag>}
              </Space>
              {item.description && (
                <div style={{ fontSize: 12.5, color: OC.muted, marginBottom: 10, lineHeight: 1.6 }}>{item.description}</div>
              )}
              <Control item={item} onSaved={() => setTick((t) => t + 1)} />
            </div>
          ))}
        </Card>
      ))}
    </>
  )
}
