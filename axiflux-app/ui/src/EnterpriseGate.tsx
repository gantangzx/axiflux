import type { ReactNode } from 'react'
import { Button, Card } from 'antd'
import { CrownOutlined, ArrowRightOutlined } from '@ant-design/icons'
import { ENTERPRISE_DISCUSSIONS_URL } from './edition'

/**
 * Shown in place of a commercial page on the community (open-source) build.
 * Keeps the route reachable and converts curiosity into a private-deployment
 * inquiry instead of dead-ending on a 404.
 */
export default function EnterpriseGate({
  title,
  children,
}: {
  title: string
  children?: ReactNode
}) {
  return (
    <div style={{ maxWidth: 720, margin: '0 auto', padding: '24px 4px' }}>
      <Card variant="borderless" style={{ textAlign: 'center', padding: '18px 10px' }}>
        <div
          style={{
            width: 56,
            height: 56,
            borderRadius: 16,
            margin: '6px auto 18px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            background: 'var(--oc-accent-soft)',
            color: 'var(--oc-accent)',
            fontSize: 26,
          }}
        >
          <CrownOutlined />
        </div>
        <h2 style={{ margin: '0 0 8px', color: 'var(--oc-text-strong)' }}>
          {title} · 企业版功能
        </h2>
        <p style={{ color: 'var(--oc-text)', marginBottom: 6 }}>
          当前为AxiFlux开源社区版，该能力属于企业版 / 私有化部署方案，开源后端未包含对应服务。
        </p>
        {children ? (
          <div style={{ color: 'var(--oc-muted)', fontSize: 13, margin: '10px 0 18px' }}>
            {children}
          </div>
        ) : (
          <p style={{ color: 'var(--oc-muted)', fontSize: 13, margin: '10px 0 18px' }}>
            企业版提供私有化部署、组织 SSO、配额与计费、离线 License、SLA 及专属技术支持。
          </p>
        )}
        <Button
          type="primary"
          size="large"
          icon={<ArrowRightOutlined />}
          iconPosition="end"
          href={ENTERPRISE_DISCUSSIONS_URL}
          target="_blank"
          rel="noreferrer"
        >
          在 GitHub Discussions 咨询私有化合作
        </Button>
      </Card>
    </div>
  )
}
