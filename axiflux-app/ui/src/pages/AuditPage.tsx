import { useState } from 'react'
import { Button, Card, Table, Tag, Alert, Space } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { api } from '../api'
import { useApi, Loading, ErrBox, fmt, mono, OC } from '../ui'

export default function AuditPage() {
  const [page, setPage] = useState(0)
  const { data, loading, error, reload } = useApi(
    () => api.get<any>(`/api/v1/audits/tool-executions?page=${page}&size=50`),
    [page],
  )
  const { data: alerts } = useApi(() => api.get<any>('/api/v1/audits/alerts?windowMinutes=60').catch(() => null), [])

  const rows = data && (data.items || data.content || data.rows || (Array.isArray(data) ? data : null)) || []
  const total = data && data.total != null ? data.total : rows.length
  const hasAlert = alerts && (alerts.level === 'warn' || (alerts.alerts && alerts.alerts.length))

  return (
    <div style={{ padding: 24, maxWidth: 1200, margin: '0 auto' }}>
      {alerts && (
        <Alert
          style={{ marginBottom: 16, background: OC.card, border: `1px solid ${hasAlert ? 'rgba(218,126,0,.5)' : OC.border}` }}
          type={hasAlert ? 'warning' : 'success'}
          showIcon
          message={`告警（最近 60 分钟）：${hasAlert ? '有风险' : '正常'}`}
          description={
            <pre style={{ ...mono, margin: 0, color: OC.text, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
              {JSON.stringify(alerts, null, 2)}
            </pre>
          }
        />
      )}
      <Card
        title={`工具执行记录（共 ${total} 条）`}
        variant="borderless"
        style={{ background: OC.card }}
        styles={{ header: { borderBottom: `1px solid ${OC.border}`, color: OC.textStrong } }}
        extra={
          <Button size="small" icon={<ReloadOutlined />} onClick={reload}>
            刷新
          </Button>
        }
      >
        {error && <ErrBox msg={error} />}
        {loading ? (
          <Loading />
        ) : (
          <Table
            size="small"
            rowKey={(r, i) => String(i)}
            dataSource={rows}
            pagination={{
              current: page + 1,
              pageSize: 50,
              total,
              showSizeChanger: false,
              onChange: (p) => setPage(p - 1),
            }}
            columns={[
              { title: '时间', dataIndex: 'createdAt', render: (v) => fmt(v) },
              { title: '工具', dataIndex: 'toolName', render: (v) => <span style={mono}>{v}</span> },
              {
                title: '结果',
                dataIndex: 'success',
                render: (v) => <Tag color={v ? 'green' : 'red'}>{v ? '成功' : '失败'}</Tag>,
              },
              { title: '耗时', dataIndex: 'durationMs', render: (v) => <span style={mono}>{v != null ? v + 'ms' : '—'}</span> },
              { title: '会话', dataIndex: 'sessionId', render: (v) => <span style={mono}>{(v || '').slice(0, 22)}</span> },
              {
                title: '错误',
                dataIndex: 'error',
                render: (v) => (
                  <span style={{ fontSize: 12, color: v ? '#ff7a7a' : OC.muted }}>{v ? String(v).slice(0, 80) : '—'}</span>
                ),
              },
            ]}
          />
        )}
      </Card>
    </div>
  )
}
