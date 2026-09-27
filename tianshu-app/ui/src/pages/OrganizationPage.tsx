import { useEffect, useState } from 'react'
import {
  Alert,
  Badge,
  Button,
  Card,
  Col,
  Dropdown,
  Empty,
  Form,
  Input,
  Modal,
  Row,
  Select,
  Space,
  Table,
  Tag,
  App as AntApp,
} from 'antd'
import {
  CrownOutlined,
  DeleteOutlined,
  DownloadOutlined,
  PlusOutlined,
  ReloadOutlined,
  TeamOutlined,
  UserAddOutlined,
} from '@ant-design/icons'
import { api, getToken } from '../api'
import { fmt, mono, useApi, OC } from '../ui'

interface OrgSummary {
  orgId: string
  name: string
  slug: string
  planTier: string
  status: string
  billingEmail?: string | null
  createdAt?: string | null
  role?: string | null
}

interface OrgMember {
  orgId: string
  userId: string
  role: string
  joinedAt?: string | null
}

interface OrgDetail extends OrgSummary {
  members: OrgMember[]
}

const ROLE_COLORS: Record<string, string> = {
  OWNER: 'red',
  ADMIN: 'gold',
  MEMBER: 'blue',
  VIEWER: 'default',
}

const ROLE_OPTIONS = [
  { label: 'OWNER · 所有者', value: 'OWNER' },
  { label: 'ADMIN · 管理员', value: 'ADMIN' },
  { label: 'MEMBER · 成员', value: 'MEMBER' },
  { label: 'VIEWER · 只读', value: 'VIEWER' },
]

export default function OrganizationPage() {
  const { message, modal } = AntApp.useApp()
  const { data: orgs, reload: reloadOrgs, loading: loadingOrgs } = useApi(
    () => api.get<OrgSummary[]>('/api/v1/orgs/mine'),
    [],
  )
  const [selectedOrgId, setSelectedOrgId] = useState<string | null>(null)

  useEffect(() => {
    if (!selectedOrgId && orgs && orgs.length > 0) {
      setSelectedOrgId(orgs[0].orgId)
    }
  }, [orgs, selectedOrgId])

  return (
    <div style={{ padding: 24, maxWidth: 1200, margin: '0 auto' }}>
      <Card
        title={
          <Space>
            <TeamOutlined style={{ color: OC.accent }} />
            我的组织
            <Button
              size="small"
              type="text"
              icon={<ReloadOutlined />}
              onClick={() => reloadOrgs()}
            />
          </Space>
        }
        variant="borderless"
        style={{ background: OC.card, marginBottom: 16 }}
        styles={{ header: { borderBottom: `1px solid ${OC.border}`, color: OC.textStrong } }}
        extra={
          <CreateOrgButton
            onCreated={(id) => {
              reloadOrgs()
              setSelectedOrgId(id)
            }}
          />
        }
      >
        {loadingOrgs && !orgs ? (
          <div style={{ color: OC.muted, padding: '12px 4px' }}>加载中…</div>
        ) : !orgs || orgs.length === 0 ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description="尚未加入任何组织"
            style={{ padding: '24px 0' }}
          />
        ) : (
          <Space wrap>
            {(orgs || []).map((o) => (
              <Button
                key={o.orgId}
                type={selectedOrgId === o.orgId ? 'primary' : 'default'}
                onClick={() => setSelectedOrgId(o.orgId)}
                style={{ height: 'auto', padding: '6px 14px' }}
              >
                <Space direction="vertical" size={2} align="start">
                  <Space>
                    <b>{o.name}</b>
                    {o.role && <Tag color={ROLE_COLORS[o.role] ?? 'default'}>{o.role}</Tag>}
                  </Space>
                  <span style={{ fontSize: 11, color: OC.muted }}>{o.slug}</span>
                </Space>
              </Button>
            ))}
          </Space>
        )}
      </Card>

      {selectedOrgId && (
        <OrgDetailPanel
          orgId={selectedOrgId}
          currentRole={
            (orgs || []).find((o) => o.orgId === selectedOrgId)?.role ?? null
          }
          onMutated={() => {
            reloadOrgs()
          }}
        />
      )}
    </div>
  )
}

function OrgDetailPanel({
  orgId,
  currentRole,
  onMutated,
}: {
  orgId: string
  currentRole: string | null
  onMutated: () => void
}) {
  const { message, modal } = AntApp.useApp()
  const { data: detail, reload, loading, error } = useApi(
    () => api.get<OrgDetail>(`/api/v1/orgs/${orgId}`),
    [orgId],
  )

  const canAdmin = currentRole === 'OWNER' || currentRole === 'ADMIN'
  const canChangeRole = currentRole === 'OWNER'

  const remove = (userId: string) => {
    modal.confirm({
      title: '移除成员',
      content: `确认从组织中移除 ${userId}？`,
      okText: '移除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await api.del(`/api/v1/orgs/${orgId}/members/${userId}`)
          message.success(`已移除 ${userId}`)
          reload()
          onMutated()
        } catch (e: any) {
          message.error(e.message)
        }
      },
    })
  }

  const changeRole = async (userId: string, role: string) => {
    try {
      await api.put(`/api/v1/orgs/${orgId}/members/${userId}/role`, { role })
      message.success(`已将 ${userId} 角色改为 ${role}`)
      reload()
    } catch (e: any) {
      message.error(e.message)
    }
  }

  return (
    <Card
      title={
        <Space>
          <CrownOutlined style={{ color: OC.accent }} />
          {detail?.name ?? '加载中…'}
          <span style={mono}>{detail?.slug}</span>
          {detail?.planTier && (
            <Tag color={detail.planTier === 'free' ? 'default' : 'gold'}>
              {detail.planTier.toUpperCase()}
            </Tag>
          )}
          {detail?.role && <Tag color={ROLE_COLORS[detail.role]}>{detail.role}</Tag>}
        </Space>
      }
      variant="borderless"
      style={{ background: OC.card }}
      styles={{ header: { borderBottom: `1px solid ${OC.border}`, color: OC.textStrong } }}
      extra={
        <Button size="small" type="text" icon={<ReloadOutlined />} onClick={reload} />
      }
    >
      {error && (
        <Alert
          type="error"
          showIcon
          message="加载组织详情失败"
          description={error}
          style={{ marginBottom: 12 }}
        />
      )}
      {detail && (
        <Row gutter={[24, 12]} style={{ marginBottom: 16 }}>
          <Col xs={12} md={6}>
            <div style={{ color: OC.muted, fontSize: 12 }}>组织 ID</div>
            <div style={mono}>{detail.orgId}</div>
          </Col>
          <Col xs={12} md={6}>
            <div style={{ color: OC.muted, fontSize: 12 }}>状态</div>
            <Badge
              status={detail.status === 'active' ? 'success' : 'default'}
              text={detail.status}
            />
          </Col>
          <Col xs={12} md={6}>
            <div style={{ color: OC.muted, fontSize: 12 }}>计费邮箱</div>
            <div style={{ ...mono, fontSize: 12 }}>
              {detail.billingEmail || <span style={{ color: OC.muted }}>—</span>}
            </div>
          </Col>
          <Col xs={12} md={6}>
            <div style={{ color: OC.muted, fontSize: 12 }}>创建时间</div>
            <div style={{ ...mono, fontSize: 12 }}>{fmt(detail.createdAt)}</div>
          </Col>
        </Row>
      )}

      <div style={{ display: 'flex', alignItems: 'center', marginBottom: 12 }}>
        <h3 style={{ color: OC.textStrong, fontSize: 14, margin: 0, flex: 1 }}>
          成员 ({detail?.members?.length ?? 0})
        </h3>
        {canAdmin && detail && (
          <Space>
            <Dropdown
              menu={{
                items: [
                  { key: 'csv', label: '导出 CSV（近 90 天）' },
                  { key: 'json', label: '导出 JSON（近 90 天）' },
                ],
                onClick: ({ key }) => exportAudit(orgId, key as 'csv' | 'json', message),
              }}
            >
              <Button size="small" icon={<DownloadOutlined />}>
                审计导出
              </Button>
            </Dropdown>
            <AddMemberButton orgId={orgId} onAdded={() => { reload(); onMutated() }} />
          </Space>
        )}
      </div>

      <Table
        size="small"
        pagination={false}
        rowKey={(r) => r.userId}
        dataSource={detail?.members ?? []}
        loading={loading && !detail}
        locale={{ emptyText: '尚无成员' }}
        columns={[
          {
            title: '用户',
            dataIndex: 'userId',
            render: (v) => <span style={{ ...mono, color: OC.textStrong }}>{v}</span>,
          },
          {
            title: '角色',
            dataIndex: 'role',
            render: (v, r: OrgMember) =>
              canChangeRole ? (
                <Select
                  size="small"
                  value={v}
                  style={{ width: 140 }}
                  options={ROLE_OPTIONS}
                  onChange={(nv) => changeRole(r.userId, nv)}
                />
              ) : (
                <Tag color={ROLE_COLORS[v] ?? 'default'}>{v}</Tag>
              ),
          },
          {
            title: '加入时间',
            dataIndex: 'joinedAt',
            render: (v) => <span style={{ ...mono, fontSize: 12 }}>{fmt(v)}</span>,
          },
          {
            title: '操作',
            render: (_, r: OrgMember) =>
              canAdmin ? (
                <Button
                  size="small"
                  danger
                  type="text"
                  icon={<DeleteOutlined />}
                  onClick={() => remove(r.userId)}
                >
                  移除
                </Button>
              ) : null,
          },
        ]}
      />

      {!canAdmin && (
        <Alert
          type="info"
          showIcon
          message="您当前角色无权管理成员（仅 OWNER/ADMIN 可添加/移除，OWNER 可改角色）"
          style={{ marginTop: 12 }}
        />
      )}
    </Card>
  )
}

async function exportAudit(
  orgId: string,
  format: 'csv' | 'json',
  message: ReturnType<typeof AntApp.useApp>['message'],
) {
  try {
    const token = getToken()
    const headers: Record<string, string> = {}
    if (token) headers.Authorization = `Bearer ${token}`
    const resp = await fetch(
      `/api/v1/orgs/${encodeURIComponent(orgId)}/audit-export?format=${format}`,
      { headers },
    )
    if (!resp.ok) {
      throw new Error(`导出失败：HTTP ${resp.status}`)
    }
    const blob = await resp.blob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `tianshu-audit-${orgId}.${format}`
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
    message.success('已开始下载')
  } catch (e: any) {
    message.error(e.message)
  }
}

function CreateOrgButton({ onCreated }: { onCreated: (orgId: string) => void }) {
  const [open, setOpen] = useState(false)
  const [form] = Form.useForm()
  const { message } = AntApp.useApp()
  const [busy, setBusy] = useState(false)

  const submit = async () => {
    try {
      const v = await form.validateFields()
      setBusy(true)
      const org = await api.post<OrgSummary>('/api/v1/orgs', v)
      message.success(`已创建组织 ${org.name}`)
      setOpen(false)
      form.resetFields()
      onCreated(org.orgId)
    } catch (e: any) {
      if (e?.errorFields) return
      message.error(e.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>
        新建组织
      </Button>
      <Modal
        title="新建组织"
        open={open}
        onCancel={() => setOpen(false)}
        onOk={submit}
        confirmLoading={busy}
        okText="创建"
        cancelText="取消"
      >
        <Form form={form} layout="vertical">
          <Form.Item label="组织名称" name="name" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="例如：Acme 团队" />
          </Form.Item>
          <Form.Item label="Slug（URL 短标识，可选）" name="slug">
            <Input placeholder="留空将自动由名称生成" />
          </Form.Item>
          <Form.Item label="计费邮箱（可选）" name="billingEmail">
            <Input placeholder="用于接收账单通知" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}

function AddMemberButton({
  orgId,
  onAdded,
}: {
  orgId: string
  onAdded: () => void
}) {
  const [open, setOpen] = useState(false)
  const [form] = Form.useForm()
  const { message } = AntApp.useApp()
  const [busy, setBusy] = useState(false)

  const submit = async () => {
    try {
      const v = await form.validateFields()
      setBusy(true)
      await api.post(`/api/v1/orgs/${orgId}/members`, v)
      message.success(`已添加成员 ${v.userId}`)
      setOpen(false)
      form.resetFields()
      onAdded()
    } catch (e: any) {
      if (e?.errorFields) return
      message.error(e.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <Button type="primary" icon={<UserAddOutlined />} onClick={() => setOpen(true)}>
        添加成员
      </Button>
      <Modal
        title="添加成员"
        open={open}
        onCancel={() => setOpen(false)}
        onOk={submit}
        confirmLoading={busy}
        okText="添加"
        cancelText="取消"
      >
        <Form form={form} layout="vertical" initialValues={{ role: 'MEMBER' }}>
          <Form.Item
            label="用户 ID"
            name="userId"
            rules={[{ required: true, message: '请输入用户 ID' }]}
            extra={
              <span style={{ color: OC.muted, fontSize: 12 }}>
                当前为 P0-1 阶段：需要 OWNER/ADMIN 知晓对方 ID 后手动添加。
                自动邮箱邀请（invite link）将在后续 P2 任务加入。
              </span>
            }
          >
            <Input placeholder="例如：alice@example.com 或 authId" />
          </Form.Item>
          <Form.Item label="角色" name="role" rules={[{ required: true }]}>
            <Select options={ROLE_OPTIONS} />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}