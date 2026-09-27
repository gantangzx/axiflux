import { useState } from 'react'
import { Button, Card, Table, Space, Modal, Form, Input, Select, Tag, Popconfirm, App as AntApp } from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import { api } from '../api'
import { useApi, Loading, ErrBox, fmt, mono, OC } from '../ui'

const TYPE_COLOR: Record<string, string> = { CRON: 'purple', PERIODIC: 'blue', DELAY: 'default' }

export default function SchedulerPage() {
  const { message } = AntApp.useApp()
  const { data, loading, error, reload } = useApi(() => api.get<any[]>('/api/v1/scheduler/tasks'), [])
  const [open, setOpen] = useState(false)
  const [form] = Form.useForm()
  const type = Form.useWatch('type', form) || 'CRON'
  const [saving, setSaving] = useState(false)

  const act = async (id: string, action: string) => {
    await api.post(`/api/v1/scheduler/tasks/${encodeURIComponent(id)}/${action}`, {})
    message.success('操作成功')
    reload()
  }
  const del = async (id: string) => {
    await api.del(`/api/v1/scheduler/tasks/${encodeURIComponent(id)}`)
    message.success('已删除')
    reload()
  }

  const create = async () => {
    const v = await form.validateFields()
    setSaving(true)
    try {
      let payload: any
      try {
        payload = JSON.parse(v.payload || '{}')
      } catch {
        payload = { raw: v.payload }
      }
      const body: any = { name: v.name.trim(), payload }
      let path: string
      if (v.type === 'CRON') {
        body.cron = v.schedule.trim()
        path = '/api/v1/scheduler/cron'
      } else if (v.type === 'DELAY') {
        body.delayMs = Number(v.schedule)
        path = '/api/v1/scheduler/delay'
      } else {
        body.intervalMs = Number(v.schedule)
        path = '/api/v1/scheduler/periodic'
      }
      if (v.type !== 'CRON' && isNaN(body.delayMs ?? body.intervalMs)) {
        message.error('毫秒数不合法')
        setSaving(false)
        return
      }
      await api.post(path, body)
      message.success('任务已创建')
      setOpen(false)
      form.resetFields()
      reload()
    } catch (e: any) {
      message.error(e.message)
    } finally {
      setSaving(false)
    }
  }

  return (
    <div style={{ padding: 24, maxWidth: 1200, margin: '0 auto' }}>
      <Card
        title={`定时任务（${data?.length || 0}）`}
        variant="borderless"
        style={{ background: OC.card }}
        styles={{ header: { borderBottom: `1px solid ${OC.border}`, color: OC.textStrong } }}
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>
            新建任务
          </Button>
        }
      >
        {error && <ErrBox msg={error} />}
        {loading ? (
          <Loading />
        ) : (
          <Table
            size="small"
            rowKey={(r) => r.id}
            dataSource={data || []}
            pagination={false}
            columns={[
              {
                title: '名称',
                render: (_, t) => <span style={{ ...mono, fontWeight: 600, color: OC.textStrong }}>{t.name || t.id}</span>,
              },
              { title: '类型', dataIndex: 'type', render: (v) => <Tag color={TYPE_COLOR[v] || 'default'}>{v}</Tag> },
              { title: '调度', dataIndex: 'schedule', render: (v) => <span style={mono}>{v || '—'}</span> },
              {
                title: '状态',
                dataIndex: 'enabled',
                render: (v) => <Tag color={v ? 'green' : 'default'}>{v ? '启用' : '停用'}</Tag>,
              },
              { title: '下次运行', dataIndex: 'nextRun', render: (v) => fmt(v) },
              { title: '上次运行', dataIndex: 'lastRun', render: (v) => fmt(v) },
              {
                title: '次数/失败',
                render: (_, t) => (
                  <span style={mono}>
                    {t.runCount ?? 0} /{' '}
                    <span style={{ color: (t.errorCount || 0) > 0 ? '#ff7a7a' : 'inherit' }}>{t.errorCount ?? 0}</span>
                  </span>
                ),
              },
              {
                title: '',
                align: 'right',
                render: (_, t) => (
                  <Space>
                    <Button size="small" type="primary" onClick={() => act(t.id, 'trigger')}>
                      触发
                    </Button>
                    <Button size="small" onClick={() => act(t.id, t.enabled ? 'disable' : 'enable')}>
                      {t.enabled ? '停用' : '启用'}
                    </Button>
                    <Popconfirm title={`删除任务「${t.name || t.id}」？`} onConfirm={() => del(t.id)}>
                      <Button size="small" danger>
                        删除
                      </Button>
                    </Popconfirm>
                  </Space>
                ),
              },
            ]}
          />
        )}
      </Card>

      <Modal title="新建定时任务" open={open} onCancel={() => setOpen(false)} onOk={create} confirmLoading={saving} okText="创建" cancelText="取消" width={600}>
        <Form form={form} layout="vertical" initialValues={{ type: 'CRON', payload: '{"kind":"agentTurn","query":""}' }} style={{ marginTop: 12 }}>
          <Form.Item name="name" label="任务名称" rules={[{ required: true }]}>
            <Input placeholder="如：每日早报" />
          </Form.Item>
          <Space style={{ display: 'flex' }} align="start">
            <Form.Item name="type" label="类型" style={{ flex: 1 }}>
              <Select
                options={[
                  { label: 'CRON 表达式', value: 'CRON' },
                  { label: '延迟（一次性）', value: 'DELAY' },
                  { label: '周期（固定间隔）', value: 'PERIODIC' },
                ]}
              />
            </Form.Item>
            <Form.Item name="schedule" label="调度参数" rules={[{ required: true }]} style={{ flex: 1 }}>
              <Input placeholder={type === 'CRON' ? '0 9 * * *' : '60000（毫秒）'} />
            </Form.Item>
          </Space>
          <Form.Item name="payload" label="Payload（JSON，可选）" extra="kind: agentTurn（执行 Agent）或 systemEvent（系统消息）">
            <Input.TextArea rows={3} style={mono} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
