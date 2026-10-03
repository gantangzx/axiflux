import { useState } from 'react'
import { Button, Card, Table, Space, Tag, Modal, Form, Input, Select, App as AntApp, Popconfirm, Tooltip, message as msg } from 'antd'
import { KeyOutlined, PlusOutlined, DeleteOutlined, CopyOutlined, ApiOutlined } from '@ant-design/icons'
import { api } from '../api'
import { useApi, Loading, ErrBox, OC } from '../ui'

export default function ApiKeysPage() {
  const { message } = AntApp.useApp()
  const { data: result, loading, error, reload } = useApi(() => api.get<any>('/api/v1/admin/api-keys'), [])
  const keys = result?.items || []
  const [createOpen, setCreateOpen] = useState(false)
  const [creating, setCreating] = useState(false)
  const [newKey, setNewKey] = useState<any | null>(null)
  const [form] = Form.useForm()

  const create = async () => {
    const v = await form.validateFields()
    setCreating(true)
    try {
      const r: any = await api.post('/api/v1/admin/api-keys', v)
      setNewKey(r)
      setCreateOpen(false)
      message.success('API Key 创建成功，请立即复制保存（仅显示一次）')
      reload()
    } catch (e: any) {
      message.error(e?.message || String(e))
    } finally {
      setCreating(false)
    }
  }

  const del = async (id: string) => {
    try {
      await api.del(`/api/v1/admin/api-keys/${id}`)
      message.success('已删除')
      reload()
    } catch (e: any) {
      message.error(e?.message || String(e))
    }
  }

  const copyKey = (text: string) => {
    navigator.clipboard.writeText(text).then(() => message.success('已复制到剪贴板'))
  }

  return (
    <div style={{ padding: 24, maxWidth: 1100, margin: '0 auto' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 24 }}>
        <ApiOutlined style={{ fontSize: 22, color: OC.accent }} />
        <div>
          <div style={{ fontSize: 16, fontWeight: 600, color: OC.textStrong }}>API Key 管理</div>
          <div style={{ fontSize: 12, color: OC.muted }}>创建和管理组织级 API 访问密钥（需要 users:admin 权限）</div>
        </div>
        <div style={{ marginLeft: 'auto' }}>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => { form.resetFields(); setCreateOpen(true); }}>
            新建 API Key
          </Button>
        </div>
      </div>

      {loading && <Loading />}
      {error && <ErrBox msg={error} />}
      {!loading && !error && (
        <Table
          dataSource={keys}
          rowKey="apiKeyId"
          size="middle"
          pagination={{ pageSize: 20 }}
          columns={[
            {
              title: '名称',
              dataIndex: 'name',
              key: 'name',
              render: (v) => <span style={{ fontWeight: 500, color: OC.textStrong }}>{v}</span>,
            },
            {
              title: 'Key（前缀）',
              dataIndex: 'keyPrefix',
              key: 'keyPrefix',
              render: (v) => (
                <Space>
                  <code style={{ background: OC.bg, padding: '2px 6px', borderRadius: 4, fontSize: 12 }}>{v}</code>
                  <Tooltip title="复制">
                    <Button size="small" icon={<CopyOutlined />} onClick={() => copyKey(v)} />
                  </Tooltip>
                </Space>
              ),
            },
            {
              title: '组织',
              dataIndex: 'orgId',
              key: 'orgId',
              render: (v) => <Tag>{v}</Tag>,
            },
            {
              title: '状态',
              dataIndex: 'enabled',
              key: 'enabled',
              render: (v) => <Tag color={v ? 'green' : 'default'}>{v ? '启用' : '禁用'}</Tag>,
            },
            {
              title: '创建时间',
              dataIndex: 'createdAt',
              key: 'createdAt',
              render: (v) => v ? new Date(v).toLocaleString('zh-CN', { hour12: false }) : '-',
            },
            {
              title: '最后使用',
              dataIndex: 'lastUsedAt',
              key: 'lastUsedAt',
              render: (v) => v ? new Date(v).toLocaleString('zh-CN', { hour12: false }) : '从未',
            },
            {
              title: '操作',
              key: 'action',
              width: 100,
              render: (_, r) => (
                <Popconfirm
                  title="删除此 API Key？"
                  description="删除后所有使用此 Key 的请求将无法通过验证"
                  onConfirm={() => del((r as any).apiKeyId)}
                  okText="删除"
                  cancelText="取消"
                  okButtonProps={{ danger: true }}
                >
                  <Button size="small" danger icon={<DeleteOutlined />} />
                </Popconfirm>
              ),
            },
          ]}
        />
      )}

      {/* 新建 Key 弹窗 */}
      <Modal
        title="新建 API Key"
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        onOk={create}
        confirmLoading={creating}
        okText="创建"
        cancelText="取消"
        width={480}
      >
        <Form form={form} layout="vertical" style={{ marginTop: 8 }}>
          <Form.Item name="orgId" label="组织 ID" rules={[{ required: true, message: '请输入组织 ID' }]}>
            <Input placeholder="org_xxxxxxxxxxxxxx" />
          </Form.Item>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入 Key 名称' }]}>
            <Input placeholder="例如：生产环境 API Key" />
          </Form.Item>
          <Form.Item name="scopes" label="权限范围" initialValue="chat:send">
            <Select
              options={[
                { value: 'chat:send', label: 'chat:send — 发送对话消息' },
                { value: 'chat:send,tools:read', label: 'chat:send + tools:read' },
                { value: 'chat:send,tools:read,agents:read', label: 'chat:send + tools:read + agents:read' },
                { value: 'chat:send,agents:manage', label: 'chat:send + agents:manage（全权）' },
              ]}
            />
          </Form.Item>
        </Form>
      </Modal>

      {/* 新建成功显示 raw key */}
      <Modal
        title="API Key 已创建"
        open={!!newKey}
        onCancel={() => { setNewKey(null); form.resetFields(); }}
        footer={
          <Button type="primary" icon={<CopyOutlined />} onClick={() => newKey && copyKey(newKey.rawKey)}>
            复制 Key
          </Button>
        }
        width={560}
      >
        <div style={{ marginBottom: 12, color: OC.muted, fontSize: 13 }}>
          请立即复制并妥善保存。此 Key 仅显示这一次，之后无法找回。
        </div>
        <div style={{
          background: OC.bg, border: `1px solid ${OC.border}`,
          borderRadius: 8, padding: '12px 16px',
          fontFamily: 'monospace', fontSize: 13,
          wordBreak: 'break-all', color: OC.accent,
        }}>
          {newKey?.rawKey}
        </div>
      </Modal>
    </div>
  )
}
