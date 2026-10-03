import { useState } from 'react'
import { Button, Card, Table, Space, Popconfirm, Modal, Input, Empty, App as AntApp } from 'antd'
import { api } from '../api'
import { useApi, Loading, ErrBox, fmt, mono, OC } from '../ui'

export default function ApprovalsPage() {
  const { message } = AntApp.useApp()
  const { data, loading, error, reload } = useApi<any[]>(async () => {
    const d: any = await api.get('/api/v1/approvals')
    return Array.isArray(d) ? d : d.items || d.pending || d.results || []
  }, [])
  const [rejectTarget, setRejectTarget] = useState<any | null>(null)
  const [reason, setReason] = useState('')

  const approve = async (a: any, scope: string) => {
    await api.post(`/api/v1/approvals/${encodeURIComponent(a.callId || a.id)}/approve`, { scope })
    message.success('已批准')
    reload()
  }
  const reject = async () => {
    await api.post(`/api/v1/approvals/${encodeURIComponent(rejectTarget.callId || rejectTarget.id)}/reject`, {
      reason: reason || undefined,
    })
    message.success('已拒绝')
    setRejectTarget(null)
    setReason('')
    reload()
  }

  return (
    <div style={{ padding: 24, maxWidth: 1200, margin: '0 auto' }}>
      <Card
        title="人工审批队列"
        variant="borderless"
        style={{ background: OC.card }}
        styles={{ header: { borderBottom: `1px solid ${OC.border}`, color: OC.textStrong } }}
      >
        {error && <ErrBox msg={error} />}
        {loading ? (
          <Loading />
        ) : !data || data.length === 0 ? (
          <Empty description="🎉 没有待审批的请求" />
        ) : (
          <Table
            size="small"
            rowKey={(r) => r.callId || r.id}
            dataSource={data}
            pagination={false}
            columns={[
              {
                title: '工具',
                render: (_, a) => <span style={{ ...mono, fontWeight: 600, color: OC.textStrong }}>{a.toolName || a.tool}</span>,
              },
              {
                title: '说明',
                render: (_, a) => <span style={{ fontSize: 12.5, color: OC.text }}>{(a.description || a.summary || '').slice(0, 100)}</span>,
              },
              { title: '会话', dataIndex: 'sessionId', render: (v) => <span style={mono}>{v || '—'}</span> },
              { title: '用户', dataIndex: 'userId', render: (v) => v || '—' },
              { title: '请求时间', render: (_, a) => fmt(a.requestedAt || a.createdAt || a.requested_at) },
              {
                title: '',
                width: 260,
                align: 'right',
                render: (_, a) => (
                  <Space>
                    <Popconfirm title="批准执行？（scope: once）" onConfirm={() => approve(a, 'once')}>
                      <Button size="small" type="primary">
                        批准一次
                      </Button>
                    </Popconfirm>
                    <Popconfirm title="批准本会话内都放行？（scope: session）" onConfirm={() => approve(a, 'session')}>
                      <Button size="small">
                        批准会话
                      </Button>
                    </Popconfirm>
                    <Button size="small" danger onClick={() => { setRejectTarget(a); setReason('') }}>
                      拒绝
                    </Button>
                  </Space>
                ),
              },
            ]}
          />
        )}
      </Card>

      <Modal
        title={rejectTarget ? `拒绝 ${rejectTarget.toolName || rejectTarget.tool}` : ''}
        open={!!rejectTarget}
        onCancel={() => setRejectTarget(null)}
        onOk={reject}
        okText="拒绝"
        okButtonProps={{ danger: true }}
        cancelText="取消"
      >
        <Input
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          placeholder="拒绝原因（可选），如：风险过高"
        />
      </Modal>
    </div>
  )
}
