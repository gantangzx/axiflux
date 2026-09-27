// Shared commercial-plan helpers: a single source for the caller's current
// plan tier plus a lock tag used at feature entry points, so paid features are
// visibly gated BEFORE a user clicks (rather than failing with a 402 after).

import type { ReactNode } from 'react'
import { Tag, Tooltip } from 'antd'
import { LockOutlined } from '@ant-design/icons'
import { api, NAVIGATE_EVENT } from './api'
import { useApi } from './ui'
import { IS_COMMUNITY, ENTERPRISE_DISCUSSIONS_URL } from './edition'

export type PlanTier = 'free' | 'pro' | 'team'

interface BillingMe {
  orgId: string | null
  planTier: PlanTier
  billingEnabled: boolean
  /** feature key → required plan tier (server-resolved, incl. config overrides). */
  features: Record<string, string>
}

const ORDER: PlanTier[] = ['free', 'pro', 'team']

/** Rank of a tier; unknown strings rank as free. */
export function rankOf(tier: string | undefined | null): number {
  const i = ORDER.indexOf((tier || 'free') as PlanTier)
  return i < 0 ? 0 : i
}

/**
 * Loaded once per consuming component; the endpoint is cheap and shared.
 *
 * On the community build the open backend has no billing endpoint, so we
 * short-circuit to a fully-unlocked, billing-off view (no request, no 404).
 */
export function useBilling() {
  const communityApi = useApi<BillingMe>(
    () => api.get<BillingMe>('/api/v1/billing/me'),
    [],
  )
  const { data, loading, reload } = IS_COMMUNITY
    ? { data: null as BillingMe | null, loading: false, reload: () => {} }
    : communityApi
  const tier: PlanTier = data?.planTier ?? 'free'
  const billingEnabled = IS_COMMUNITY ? false : !!data?.billingEnabled
  /** Minimum plan a feature requires, resolved from the server matrix. */
  const required = (feature: string, fallback: PlanTier = 'free'): PlanTier =>
    (data?.features?.[feature] ?? fallback) as PlanTier
  /** True when the current plan covers {@code feature}. */
  const covers = (feature: string, fallback: PlanTier = 'free'): boolean =>
    IS_COMMUNITY || rankOf(tier) >= rankOf(required(feature, fallback))
  return { me: data, tier, billingEnabled, required, covers, loading, reload }
}

const TAG_COLOR: Record<PlanTier, string> = {
  free: 'default',
  pro: 'gold',
  team: 'purple',
}

/**
 * A small "Pro 功能" lock marker. Renders nothing when the feature is already
 * covered by the caller's plan or billing is off (self-hosted users should not
 * see locks), keeping it zero-impact for non-SaaS deployments.
 */
export function PlanLockTag({
  feature,
  fallback = 'pro',
  label,
}: {
  feature: string
  fallback?: PlanTier
  label?: ReactNode
}) {
  // Community build: advertise the capability as an enterprise feature and
  // route the click to the private-deployment inquiry channel.
  if (IS_COMMUNITY) {
    return (
      <Tooltip title="企业版 / 私有化部署能力，点击了解私有化合作">
        <Tag
          icon={<LockOutlined />}
          color="default"
          style={{ marginInlineEnd: 0, cursor: 'pointer' }}
          onClick={() => goEnterpriseDiscussions()}
        >
          {label ?? '企业版功能'}
        </Tag>
      </Tooltip>
    )
  }
  const { tier, billingEnabled, covers, required } = useBilling()
  if (!billingEnabled || covers(feature, fallback)) return null
  const need = required(feature, fallback)
  return (
    <Tooltip title={`当前为 ${tier.toUpperCase()} 套餐，该功能需 ${need.toUpperCase()}，点击查看升级`}>
      <Tag
        icon={<LockOutlined />}
        color={TAG_COLOR[need] ?? 'default'}
        style={{ marginInlineEnd: 0, cursor: 'pointer' }}
        onClick={() => apiGoBusiness()}
      >
        {label ?? `${need.toUpperCase()} 功能`}
      </Tag>
    </Tooltip>
  )
}

/** Cross-cutting navigation to the billing page without a router dependency. */
function apiGoBusiness() {
  window.dispatchEvent(new CustomEvent<string>(NAVIGATE_EVENT, { detail: 'business' }))
}

/** Open the public enterprise / private-deployment inquiry channel. */
function goEnterpriseDiscussions() {
  window.open(ENTERPRISE_DISCUSSIONS_URL, '_blank', 'noopener,noreferrer')
}
