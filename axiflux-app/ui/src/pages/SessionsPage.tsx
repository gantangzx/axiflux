import { Button, Card, Popconfirm, Space, Table } from 'antd'
import { PlusOutlined, FolderOpenOutlined } from '@ant-design/icons'
import { api, getUserId } from '../api'
import { useChat } from '../chat'
import { useApi, Loading, ErrBox, StateTag, mono, OC } from '../ui'

export default function SessionsPage({ onGoChat }: { onGoChat: () => void }) {
  const chat = useChat()
  const { data, loading, error, reload } = useApi(
    () => api.get<any[]>(`/api/v1/sessions?userId=${getUserId()}`),
    [],
  )

  const open = async (id: string) => {
    await chat.openSession(id)
    onGoChat()
  }
  const createNew = async () => {
    await chat.newSession()
    onGoChat()
  }

  const closeS = async (id: string) => {
    await api.post(`/api/v1/sessions/${encodeURIComponent(id)}/close`)
    reload()
  }
  const delS = async (id: string) => {
    await api.del(`/api/v1/sessions/${encodeURIComponent(id)}`)
    reload()
  }

  return (
    <div style={{ padding: 24, maxWidth: 1200, margin: '0 auto' }}>
      <Card
        title={`会话列表（${data?.length || 0}）`}
        variant="borderless"
        style={{ background: OC.card }}
        styles={{ header: { borderBottom: `1px solid ${OC.border}`, color: OC.textStrong } }}
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={createNew}>
            新建会话
          </Button>
        }
      >
        {error && <ErrBox msg={error} />}
        {loading ? (
          <Loading />
        ) : (
          <Table
            size="small"
            rowKey={(r) => r.sessionId || r.id}
            dataSource={data || []}
            pagination={{ pageSize: 20, showSizeChanger: false }}
            columns={[
              {
                title: 'Session ID',
                dataIndex: 'sessionId',
                render: (v, r) => <span style={mono}>{v || r.id}</span>,
              },
              { title: '用户', dataIndex: 'userId', render: (v) => v || '—' },
              { title: 'Agent', dataIndex: 'agentId', render: (v) => v || 'default' },
              { title: '状态', dataIndex: 'state', render: (v) => <StateTag state={v} /> },
              { title: '消息数', dataIndex: 'messageCount', render: (v) => v ?? 0 },
              {
                title: 'Token / 成本',
                dataIndex: 'usage',
                render: (u: any) => {
                  if (!u || typeof u !== 'object') return <span style={{ color: OC.muted }}>—</span>
                  const fmt = (n: number) =>
                    n >= 1000 ? `${(n / 1000).toFixed(n >= 10000 ? 0 : 1)}k` : String(n)
                  const ratio = Math.round((u.cacheHitRatio || 0) * 100)
                  const cost = Number(u.estimatedCost || 0)
                  return (
                    <Space size={4} style={{ fontSize: 12 }} wrap>
                      <span style={mono} title={`输入 ${u.inputTokens} / 输出 ${u.outputTokens}`}>
                        {fmt(Number(u.totalTokens || 0))} tok
                      </span>
                      {Number(u.cachedInputTokens || 0) > 0 && (
                        <span style={{ color: '#22c55e' }} title="缓存命中输入 token">
                          缓存{ratio}%
                        </span>
                      )}
                      {cost > 0 && (
                        <span style={{ color: OC.muted }} title="按 axiflux.llm.costs 配置价格估算">
                          ≈{cost.toFixed(4)}
                        </span>
                      )}
                    </Space>
                  )
                },
              },
              {
                title: '',
                align: 'right',
                render: (_, r) => (
                  <Space>
                    <Button
                      size="small"
                      icon={<FolderOpenOutlined />}
                      onClick={() => open(r.sessionId || r.id)}
                    >
                      打开
                    </Button>
                    <Popconfirm title="关闭该会话？" onConfirm={() => closeS(r.sessionId || r.id)}>
                      <Button size="small">关闭</Button>
                    </Popconfirm>
                    <Popconfirm title="永久删除该会话及其消息？" onConfirm={() => delS(r.sessionId || r.id)}>
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
    </div>
  )
}
