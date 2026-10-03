import { lazy, Suspense, type ComponentType, type LazyExoticComponent } from 'react'
import { Spin } from 'antd'

// Enterprise build: each commercial page is its own lazy chunk.
const PAGES: Record<string, LazyExoticComponent<ComponentType>> = {
  business: lazy(() => import('./pages/BusinessPage')),
  organization: lazy(() => import('./pages/OrganizationPage')),
  usage: lazy(() => import('./pages/UsagePage')),
  'admin-subscriptions': lazy(() => import('./pages/AdminSubscriptionsPage')),
  'admin-funnel': lazy(() => import('./pages/AdminFunnelPage')),
  'agent-templates': lazy(() => import('./pages/AgentTemplatesPage')),
  'api-keys': lazy(() => import('./pages/ApiKeysPage')),
  'license-management': lazy(() => import('./pages/LicenseManagementPage')),
}

/** Resolves a commercial route to its real page (enterprise build only). */
export default function CommercialRoutes({ route }: { route: string }) {
  const Page = PAGES[route]
  if (!Page) return null
  return (
    <Suspense
      fallback={
        <div style={{ padding: '18vh 0', textAlign: 'center' }}>
          <Spin />
        </div>
      }
    >
      <Page />
    </Suspense>
  )
}
