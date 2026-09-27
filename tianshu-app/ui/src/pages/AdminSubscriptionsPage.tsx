import { useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  App as AntApp,
} from 'antd'
import { SafetyCertificateOutlined, ThunderboltOutlined } from '@ant-design/icons'
import { api } from '../api'
import { useApi, fmt, mono, OC } from '../ui'

interface SubRow {
  subscriptionId: string
  orgId: string
  orgName?: string
  planTier: string
  status: string
  seatCount: number
  currentPeriodEnd: string | null
  stripeSubscriptionId: string | null
}

interface SubPage {
  page: number
  size: number
  total: number
  totalPages: number
  items: SubRow[]
}

const PLAN_COLOR: Record<string, string> = {
  free: 'default',
  pro: 'gold',
  team: 'purple',
}

const STATUS_COLOR: Record<string, string> = {
  active: 'green',
  trialing: 'blue',
  past_due: 'orange',
  suspended: 'red',
  canceled: 'default',
}

export default function AdminSubscriptionsPage() {
  const { message } = AntApp.useApp()
  const [statusFilter, setStatusFilter] = useState<string | undefined>(undefined)
  const [page, setPage] = useState(0)
  const [busyOrg, setBusyOrg] = useState<string | null>(null)

  const query =
    `/api/v1/admin/subscriptions?page=${page}&size=20` +
    (statusFilter ? `&status=${encodeURIComponent(statusFilter)}` : '')

  const { data, loading, error, reload } = useApi(() => api.get<SubPage>(query), [
    query,
  ])

  const changeStatus = (row: SubRow, status: 'active' | 'suspended') => {
    Modal.confirm({
      title: status === 'suspended' ? `暂停 ${row.orgName ?? row.orgId}？` : '恢复该订阅？',
      okText: status === 'suspended' ? '确认暂停' : '确认恢复',
      okButtonProps: { danger: status === 'suspended' },
      cancelText: '取消',
      content: (
        <div style={{ color: OC.text, fontSize: 13, lineHeight: 1.8 }}>
          {status === 'suspended'
            ? '暂停后该组织将立即失去付费能力，直至管理员恢复。'
            : '恢复后该组织重新获得当前套餐的全部能力。'}
        </div>
      ),
      onOk: async () => {
        setBusyOrg(row.orgId)
        try {
          await api.post(`/api/v1/admin/subscriptions/${row.orgId}/status`, { status })
          message.success('已更新')
          reload()
        } catch (e) {
          message.error(e instanceof Error ? e.message : String(e))
        } finally {
          setBusyOrg(null)
        }
      },
    })
  }

  const [grantOrg, setGrantOrg] = useState<SubRow | null>(null)
  const [grantForm] = Form.useForm()

  const submitGrant = async () => {
    const values = await grantForm.validateFields()
    try {
      await api.post(
        `/api/v1/admin/subscriptions/${grantOrg!.orgId}/quota-grant`,
        values,
      )
      message.success('赠额已生效')
      setGrantOrg(null)
      grantForm.resetFields()
      reload()
    } catch (e) {
      message.error(e instanceof Error ? e.message : String(e))
    }
  }

  const rows = data?.items ?? []

  return (
    <div style={{ padding: 24, maxWidth: 1200, margin: '0 auto' }}>
      <Card
        title={
          <Space>
            <SafetyCertificateOutlined style={{ color: OC.accent }} />
            订阅管理
          </Space>
        }
        variant="borderless"
        style={{ background: OC.card }}
        styles={{ header: { borderBottom: `1px solid ${OC.border}`, color: OC.textStrong } }}
        extra={
          <Select
            allowClear
            placeholder="按状态筛选"
            style={{ width: 160 }}
            value={statusFilter}
            onChange={(v) => {
              setStatusFilter(v)
              setPage(0)
            }}
            options={['active', 'trialing', 'past_due', 'suspended', 'canceled'].map((s) => ({
              label: s,
              value: s,
            }))}
          />
        }
      >
        {error && <Alert type="error" showIcon message="加载失败" description={error} />}
        {!error && (
          <Table
            size="small"
            rowKey={(r) => r.subscriptionId}
            loading={loading}
            dataSource={rows}
            pagination={{
              current: page + 1,
              pageSize: data?.size ?? 20,
              total: data?.total ?? 0,
              showSizeChanger: false,
              onChange: (p) => setPage(p - 1),
            }}
            columns={[
              {
                title: '组织',
                render: (_, r) => (
                  <div>
                    <div style={{ color: OC.textStrong, fontWeight: 600 }}>
                      {r.orgName ?? '—'}
                    </div>
                    <div style={{ ...mono, fontSize: 12, color: OC.muted }}>{r.orgId}</div>
                  </div>
                ),
              },
              {
                title: '套餐',
                dataIndex: 'planTier',
                render: (v) => <Tag color={PLAN_COLOR[v] ?? 'default'}>{v}</Tag>,
              },
              {
                title: '状态',
                dataIndex: 'status',
                render: (v) => <Tag color={STATUS_COLOR[v] ?? 'default'}>{v}</Tag>,
              },
              { title: '席位', dataIndex: 'seatCount' },
              {
                title: '到期时间',
                dataIndex: 'currentPeriodEnd',
                render: (v) => fmt(v),
              },
              {
                title: 'Stripe',
                dataIndex: 'stripeSubscriptionId',
                render: (v) =>
                  v ? <span style={{ ...mono, fontSize: 12 }}>{v}</span> : <Tag>本地</Tag>,
              },
              {
                title: '操作',
                render: (_, r) => (
                  <Space>
                    {r.status === 'suspended' ? (
                      <Button
                        size="small"
                        type="link"
                        loading={busyOrg === r.orgId}
                        onClick={() => changeStatus(r, 'active')}
                      >
                        恢复
                      </Button>
                    ) : (
                      <Button
                        size="small"
                        type="link"
                        danger
                        loading={busyOrg === r.orgId}
                        onClick={() => changeStatus(r, 'suspended')}
                      >
                        暂停
                      </Button>
                    )}
                    <Button
                      size="small"
                      type="link"
                      icon={<ThunderboltOutlined />}
                      onClick={() => {
                        setGrantOrg(r)
                        grantForm.setFieldsValue({
                          dimension: 'tokens',
                          limit: 1000000,
                          ongoing: false,
                          reason: '',
                        })
                      }}
                    >
                      赠额
                    </Button>
                  </Space>
                ),
              },
            ]}
          />
        )}
      </Card>

      <Modal
        title={`手动赠额 · ${grantOrg?.orgName ?? grantOrg?.orgId ?? ''}`}
        open={grantOrg !== null}
        onOk={submitGrant}
        onCancel={() => setGrantOrg(null)}
        okText="生效"
        cancelText="取消"
      >
        <Form form={grantForm} layout="vertical" style={{ marginTop: 8 }}>
          <Form.Item
            name="dimension"
            label="配额维度"
            rules={[{ required: true }]}
          >
            <Select
              options={[
                { label: 'Token 数', value: 'tokens' },
                { label: '轮次数', value: 'turns' },
              ]}
            />
          </Form.Item>
          <Form.Item
            name="limit"
            label="额度（0 = 不限）"
            rules={[{ required: true, message: '请输入非负整数' }]}
          >
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="ongoing" label="长期有效" valuePropName="checked">
            <Select
              options={[
                { label: '仅当前/指定月份', value: false },
                { label: '长期有效（含未来月份）', value: true },
              ]}
            />
          </Form.Item>
          <Form.Item noStyle shouldUpdate>
            {(f) =>
              f.getFieldValue('ongoing') === true ? null : (
                <Form.Item name="period" label="生效月份（yyyy-MM）">
                  <Input placeholder="例如 2026-10" />
                </Form.Item>
              )
            }
          </Form.Item>
          <Form.Item name="reason" label="备注">
            <Input placeholder="例如 大客户补偿 / 活动赠送" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
