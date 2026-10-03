import { useState } from 'react'
import { Button, Card, Tag, Space, Modal, Form, Input, App as AntApp, Descriptions, Alert, Popconfirm, Divider } from 'antd'
import { SafetyCertificateOutlined, CheckCircleOutlined, ExclamationCircleOutlined, DeleteOutlined, CopyOutlined } from '@ant-design/icons'
import { api } from '../api'
import { useApi, Loading, ErrBox, OC } from '../ui'

/** Runtime license snapshot: axiflux-spring LicenseStatus record. */
type LicenseStatus = {
  state?: string
  valid?: boolean
  enforced?: boolean
  requestAllowed?: boolean
  customer?: string
  deploymentId?: string
  edition?: string
  seats?: number
  entitlements?: string[]
  notBefore?: string
  expiresAt?: string
  graceEndsAt?: string
  reason?: string
}

const STATE_META: Record<string, { color: string; label: string }> = {
  VALID: { color: 'green', label: '有效' },
  GRACE: { color: 'orange', label: '宽限期' },
  EXPIRED: { color: 'red', label: '已过期' },
  INVALID: { color: 'red', label: '无效' },
  MISSING: { color: 'default', label: '未安装' },
  DISABLED: { color: 'default', label: '未启用' },
}

const fmt = (v?: string) => (v ? new Date(v).toLocaleString('zh-CN') : '-')

export default function LicenseManagementPage() {
  const { message } = AntApp.useApp()
  const { data: lic, loading, error, reload } = useApi<LicenseStatus>(() => api.get<LicenseStatus>('/api/v1/admin/license'), [])
  const [applyOpen, setApplyOpen] = useState(false)
  const [applying, setApplying] = useState(false)
  const [form] = Form.useForm()

  const active = !!lic?.valid
  const stateMeta = STATE_META[lic?.state || 'DISABLED'] || { color: 'default', label: lic?.state || '未知' }

  const copy = async (text: string) => {
    try {
      await navigator.clipboard.writeText(text)
      message.success('已复制部署 ID')
    } catch {
      message.error('复制失败，请手动选择复制')
    }
  }

  const apply = async () => {
    const v = await form.validateFields()
    setApplying(true)
    try {
      await api.post('/api/v1/admin/license', v)
      message.success('License 应用成功')
      setApplyOpen(false)
      reload()
    } catch (e: any) {
      message.error(e?.message || String(e))
    } finally {
      setApplying(false)
    }
  }

  const revoke = async () => {
    try {
      await api.del('/api/v1/admin/license')
      message.success('License 已撤销')
      reload()
    } catch (e: any) {
      message.error(e?.message || String(e))
    }
  }

  return (
    <div style={{ padding: 24, maxWidth: 900, margin: '0 auto' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 24 }}>
        <SafetyCertificateOutlined style={{ fontSize: 22, color: OC.accent }} />
        <div>
          <div style={{ fontSize: 16, fontWeight: 600, color: OC.textStrong }}>离线 License 管理</div>
          <div style={{ fontSize: 12, color: OC.muted }}>私有化部署的离线授权管理（需要 license:admin 权限）</div>
        </div>
      </div>

      {loading && <Loading />}
      {error && <ErrBox msg={error} />}

      {!loading && !error && (
        <>
          {/* 状态卡片 */}
          <Card
            variant="outlined"
            style={{ background: OC.card, border: `1px solid ${OC.border}`, marginBottom: 20 }}
            styles={{ body: { padding: 24 } }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginBottom: 20 }}>
              <div style={{
                width: 56, height: 56, borderRadius: 12,
                background: active ? OC.accentSubtle : 'rgba(255,77,77,0.1)',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
              }}>
                {active
                  ? <CheckCircleOutlined style={{ fontSize: 28, color: OC.accent }} />
                  : <ExclamationCircleOutlined style={{ fontSize: 28, color: '#ff4d4f' }} />
                }
              </div>
              <div>
                <div style={{ fontSize: 18, fontWeight: 700, color: OC.textStrong, marginBottom: 4 }}>
                  {active ? 'License 有效' : 'License 不可用'}
                </div>
                <div style={{ fontSize: 13, color: OC.muted }}>
                  {active
                    ? `${lic?.edition || '标准版'} · 客户 ${lic?.customer || '-'} · 到期 ${fmt(lic?.expiresAt)}`
                    : `当前状态：${stateMeta.label}${lic?.reason ? ' · ' + lic.reason : ''}`}
                </div>
              </div>
            </div>

            {lic?.reason && (
              <Alert
                type={active ? 'warning' : 'error'}
                showIcon
                message={lic.reason}
                style={{ marginBottom: 16 }}
              />
            )}

            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 16 }}
              message="如何申请 / 续签 License"
              description={
                <ol style={{ margin: 0, paddingLeft: 18 }}>
                  <li>复制下方「部署 ID」（即部署指纹，License 与此绑定）；</li>
                  <li>将部署 ID（及客户名称、所需版本/席位）发送给AxiFlux厂商，离线获取新的 License Key；</li>
                  <li>点击「应用 License」，粘贴 Key 即可。有效期内或宽限期内均可应用，无需停机。</li>
                </ol>
              }
            />

            <Descriptions size="small" column={2} style={{ marginBottom: 20 }}
              labelStyle={{ color: OC.muted }}
              contentStyle={{ color: OC.textStrong }}>
              <Descriptions.Item label="状态">
                <Tag color={stateMeta.color}>{stateMeta.label}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="执行模式">
                <Tag color={lic?.enforced ? 'red' : 'green'}>
                  {lic?.enforced ? '强制拦截' : '不拦截'}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="授权客户">{lic?.customer || '-'}</Descriptions.Item>
              <Descriptions.Item label="部署 ID">
                {lic?.deploymentId ? (
                  <Space size={4}>
                    <span>{lic.deploymentId}</span>
                    <Button size="small" type="text" icon={<CopyOutlined />}
                      onClick={() => copy(lic.deploymentId)} />
                  </Space>
                ) : '-'}
              </Descriptions.Item>
              <Descriptions.Item label="版本">{lic?.edition || '-'}</Descriptions.Item>
              <Descriptions.Item label="授权席位">{lic?.seats ?? '不限'}</Descriptions.Item>
              <Descriptions.Item label="生效时间">{fmt(lic?.notBefore)}</Descriptions.Item>
              <Descriptions.Item label="到期时间">{fmt(lic?.expiresAt)}</Descriptions.Item>
              <Descriptions.Item label="宽限截止">{fmt(lic?.graceEndsAt)}</Descriptions.Item>
              <Descriptions.Item label="请求放行">{lic?.requestAllowed ? '是' : '否'}</Descriptions.Item>
            </Descriptions>

            <div style={{ fontSize: 13, color: OC.muted, marginBottom: 8 }}>授权能力</div>
            <Space size={[6, 8]} wrap style={{ marginBottom: 20 }}>
              {(lic?.entitlements && lic.entitlements.length > 0)
                ? lic.entitlements.map((e) => <Tag key={e} color="blue">{e}</Tag>)
                : <span style={{ fontSize: 13, color: OC.muted }}>无</span>
              }
            </Space>

            <Divider style={{ margin: '12px 0' }} />

            <Space>
              <Button type="primary" onClick={() => { form.resetFields(); setApplyOpen(true); }}>
                应用 License
              </Button>
              {lic?.state !== 'DISABLED' && (
                <Popconfirm
                  title="撤销 License？"
                  description="删除本地 License 文件。强制拦截模式下撤销后所有受保护接口将立即不可用（503）。"
                  onConfirm={revoke}
                  okText="撤销"
                  cancelText="取消"
                  okButtonProps={{ danger: true }}
                >
                  <Button danger icon={<DeleteOutlined />}>撤销 License</Button>
                </Popconfirm>
              )}
            </Space>
          </Card>

          {/* 说明 */}
          <Card
            variant="outlined"
            style={{ background: OC.card, border: `1px solid ${OC.border}` }}
            styles={{ body: { padding: 20 } }}
          >
            <div style={{ fontSize: 14, fontWeight: 600, color: OC.textStrong, marginBottom: 12 }}>
              说明
            </div>
            <Space direction="vertical" size={8} style={{ width: '100%' }}>
              <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start' }}>
                <Tag color="default">未启用</Tag>
                <span style={{ fontSize: 13, color: OC.muted }}>
                  配置 <code>axiflux.license.enabled=false</code> 时完全跳过校验，自托管/自定义 IdP 部署行为不变
                </span>
              </div>
              <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start' }}>
                <Tag color="green">不拦截</Tag>
                <span style={{ fontSize: 13, color: OC.muted }}>
                  <code>enforcement=off|warn</code>：仅展示状态与告警，不影响请求放行
                </span>
              </div>
              <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start' }}>
                <Tag color="red">强制拦截</Tag>
                <span style={{ fontSize: 13, color: OC.muted }}>
                  <code>enforcement=enforce</code>：License 无效/过期且超出宽限期时，身份面请求返回 503；管理端点始终可访问以便排障
                </span>
              </div>
              <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start' }}>
                <Tag color="blue">文件位置</Tag>
                <span style={{ fontSize: 13, color: OC.muted }}>
                  由 <code>axiflux.license.path</code> 与 <code>public-key-path</code> 指定；应用时会写入 path 并立即重载
                </span>
              </div>
            </Space>
          </Card>
        </>
      )}

      <Modal
        title="应用 License"
        open={applyOpen}
        onCancel={() => setApplyOpen(false)}
        onOk={apply}
        confirmLoading={applying}
        okText="应用"
        cancelText="取消"
        width={500}
      >
        <Alert
          message="粘贴离线签发的 License 内容，将写入配置的 license 路径并立即生效"
          type="info"
          style={{ marginBottom: 16 }}
        />
        <Form form={form} layout="vertical">
          <Form.Item
            name="licenseKey"
            label="License Key"
            rules={[{ required: true, message: '请输入 License Key' }]}
          >
            <Input.TextArea
              rows={5}
              placeholder="粘贴签名后的 License 内容"
              style={{ fontFamily: 'monospace' }}
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
