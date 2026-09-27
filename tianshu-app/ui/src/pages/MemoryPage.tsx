import { useState, useEffect } from 'react'
import {
  Button,
  Card,
  Input,
  Space,
  Tag,
  Modal,
  Form,
  InputNumber,
  Popconfirm,
  App as AntApp,
  Row,
  Col,
  Alert,
} from 'antd'
import { PlusOutlined, CompressOutlined, SearchOutlined, LockOutlined } from '@ant-design/icons'
import { api, getUserId, navigate } from '../api'
import { Loading, ErrBox, fmt, toList, mono, OC } from '../ui'
import { useBilling } from '../billing'

const FEATURE = 'long_term_memory'

type MemItem = {
  id?: string
  content?: string
  summary?: string
  tags?: unknown
  importance?: number
  createdAt?: string
}

export default function MemoryPage() {
  const { message } = AntApp.useApp()
  // Long-term memory is a paid feature: resolve the caller's plan BEFORE
  // fetching, so a free user sees an upgrade prompt instead of a raw 402.
  const { tier, billingEnabled, covers, required, loading: billingLoading } = useBilling()
  const unlocked = covers(FEATURE, 'pro')
  const need = required(FEATURE, 'pro')
  const [items, setItems] = useState<MemItem[] | null>(null)
  const [loadErr, setLoadErr] = useState<string | null>(null)
  const [note, setNote] = useState('')
  const [q, setQ] = useState('')
  const [detail, setDetail] = useState<MemItem | null>(null)
  const [storeOpen, setStoreOpen] = useState(false)
  const [form] = Form.useForm()
  const [busy, setBusy] = useState(false)

  const renderItems = (list: MemItem[], n = '') => {
    setItems(list)
    setNote(n)
  }

  const loadAll = async () => {
    if (billingEnabled && !unlocked) {
      setItems([])
      return
    }
    setItems(null)
    setLoadErr(null)
    try {
      const d: any = await api.get(`/api/v1/memory/${getUserId()}`)
      renderItems((d && (d.items || d.results)) || [])
    } catch (e: any) {
      setLoadErr(e.message)
    }
  }
  // initial load (waits for plan resolution)
  useEffect(() => {
    if (billingLoading) return
    loadAll()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [billingLoading, unlocked])

  const search = async () => {
    if (!q.trim()) return
    if (billingEnabled && !unlocked) {
      navigate('business')
      return
    }
    setItems(null)
    setLoadErr(null)
    try {
      const d: any = await api.post('/api/v1/memory/search', { userId: getUserId(), query: q.trim(), topK: 5 })
      const list = (d && (d.results || d.items)) || []
      renderItems(list, `“${q}” 的检索结果（${list.length}）`)
    } catch (e: any) {
      setLoadErr(e.message)
    }
  }

  const compress = async () => {
    try {
      const d: any = await api.post(`/api/v1/memory/${getUserId()}/compress`, {})
      message.success(`压缩完成：${d.compressed ?? '—'} 条`)
      loadAll()
    } catch (e: any) {
      message.error(e.message)
    }
  }

  const del = async (m: MemItem) => {
    await api.del(`/api/v1/memory/${encodeURIComponent(m.id!)}`)
    message.success('已删除')
    loadAll()
  }

  const store = async () => {
    const v = await form.validateFields()
    setBusy(true)
    try {
      await api.post('/api/v1/memory/store', {
        userId: getUserId(),
        content: v.content,
        summary: v.summary || undefined,
        tags: v.tags ? toList(v.tags) : [],
        importance: Math.max(1, Math.min(10, v.importance || 5)),
      })
      message.success('已写入记忆')
      setStoreOpen(false)
      form.resetFields()
      loadAll()
    } catch (e: any) {
      message.error(e.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div style={{ padding: 24, maxWidth: 1200, margin: '0 auto' }}>
      <Card variant="borderless" style={{ background: OC.card, marginBottom: 16 }} styles={{ body: { padding: 14 } }}>
        <Space wrap style={{ width: '100%' }}>
          <Input
            style={{ width: 320 }}
            placeholder="语义搜索记忆…"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            onPressEnter={search}
          />
          <Button type="primary" icon={<SearchOutlined />} onClick={search}>
            搜索
          </Button>
          <Button onClick={loadAll}>全部</Button>
          <div style={{ flex: 1 }} />
          <Button icon={<PlusOutlined />} disabled={billingEnabled && !unlocked} onClick={() => setStoreOpen(true)}>
            写入记忆
          </Button>
          <Button icon={<CompressOutlined />} disabled={billingEnabled && !unlocked} onClick={compress}>
            压缩
          </Button>
        </Space>
      </Card>

      {billingLoading ? (
        <Loading />
      ) : billingEnabled && !unlocked ? (
        <Card variant="borderless" style={{ background: OC.card }} styles={{ body: { padding: 40 } }}>
          <div style={{ textAlign: 'center', maxWidth: 480, margin: '0 auto' }}>
            <div style={{ width: 56, height: 56, borderRadius: 14, margin: '0 auto 16px', display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'rgba(255,92,92,0.12)', color: OC.accent }}>
              <LockOutlined style={{ fontSize: 26 }} />
            </div>
            <div style={{ fontSize: 16, color: OC.textStrong, marginBottom: 8 }}>
              长期记忆是 {need.toUpperCase()} 套餐功能
            </div>
            <div style={{ fontSize: 13, lineHeight: 1.7, color: OC.muted, marginBottom: 20 }}>
              当前为 {tier.toUpperCase()} 套餐。升级后即可让 Agent 跨会话记住偏好与事实，并在此页检索、管理长期记忆。
            </div>
            <Space>
              <Button type="primary" onClick={() => navigate('business')}>查看升级</Button>
            </Space>
          </div>
        </Card>
      ) : (
        <>
      {loadErr && <ErrBox msg={loadErr} />}
      {items === null ? (
        <Loading />
      ) : items.length === 0 ? (
        <Alert type="info" showIcon message={note || '暂无记忆'} style={{ background: OC.card, border: `1px solid ${OC.border}`, color: OC.text }} />
      ) : (
        <Row gutter={[16, 16]}>
          {items.map((m, i) => {
            const text = m.summary || m.content || ''
            return (
              <Col xs={24} md={12} key={m.id || i}>
                <Card variant="borderless" style={{ background: OC.card, height: '100%' }} styles={{ body: { display: 'flex', flexDirection: 'column', gap: 8, height: '100%' } }}>
                  <Space wrap size={4}>
                    <Tag color="purple">importance {m.importance ?? 5}</Tag>
                    {toList(m.tags).map((t) => (
                      <Tag key={t}>#{t}</Tag>
                    ))}
                    <span style={{ color: OC.muted, fontSize: 11, marginLeft: 'auto' }}>{fmt(m.createdAt)}</span>
                  </Space>
                  <div style={{ fontSize: 13.5, lineHeight: 1.6, wordBreak: 'break-word', color: OC.text }}>
                    {text.slice(0, 240)}
                    {text.length > 240 ? '…' : ''}
                  </div>
                  <Space style={{ marginTop: 'auto' }}>
                    <Button size="small" onClick={() => setDetail(m)}>
                      查看全文
                    </Button>
                    <Popconfirm title="删除这条记忆？" onConfirm={() => del(m)}>
                      <Button size="small" danger>
                        删除
                      </Button>
                    </Popconfirm>
                  </Space>
                </Card>
              </Col>
            )
          })}
        </Row>
      )}
        </>
      )}

      <Modal title="记忆详情" open={!!detail} onCancel={() => setDetail(null)} footer={null} width={680}>
        {detail && (
          <>
            <div style={{ ...mono, color: OC.muted, marginBottom: 8 }}>ID: {detail.id}</div>
            <div style={{ marginBottom: 6, color: OC.textStrong }}>
              {detail.summary || '—'}
            </div>
            <Space wrap style={{ marginBottom: 10 }}>
              {toList(detail.tags).map((t) => (
                <Tag key={t}>#{t}</Tag>
              ))}
            </Space>
            <pre style={{ ...mono, background: OC.bg, border: `1px solid ${OC.border}`, borderRadius: 10, padding: 12, color: OC.text, whiteSpace: 'pre-wrap', wordBreak: 'break-word', maxHeight: '50vh', overflow: 'auto' }}>
              {detail.content || ''}
            </pre>
          </>
        )}
      </Modal>

      <Modal title="写入长期记忆" open={storeOpen} onCancel={() => setStoreOpen(false)} onOk={store} confirmLoading={busy} okText="写入" cancelText="取消" width={600}>
        <Form form={form} layout="vertical" initialValues={{ importance: 5 }} style={{ marginTop: 12 }}>
          <Form.Item name="content" label="内容" rules={[{ required: true }]}>
            <Input.TextArea rows={4} placeholder="要记住的内容" />
          </Form.Item>
          <Form.Item name="summary" label="摘要（可选）">
            <Input placeholder="一句话摘要，便于检索" />
          </Form.Item>
          <Space style={{ display: 'flex' }} align="start">
            <Form.Item name="tags" label="标签（逗号分隔）" style={{ flex: 1 }}>
              <Input placeholder="tag1,tag2" />
            </Form.Item>
            <Form.Item name="importance" label="重要性 1-10" style={{ width: 140 }}>
              <InputNumber min={1} max={10} style={{ width: '100%' }} />
            </Form.Item>
          </Space>
        </Form>
      </Modal>
    </div>
  )
}
