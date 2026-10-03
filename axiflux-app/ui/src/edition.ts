// Edition detection. The community (open-source) build bakes in `community`;
// the commercial build sets VITE_EDITION=enterprise at build time. Detection is
// build-time so no extra runtime probe / 404 is incurred on the open backend.
export type Edition = 'community' | 'enterprise'

const raw = (import.meta.env.VITE_EDITION ?? 'community').toString().toLowerCase()

export const EDITION: Edition = raw === 'enterprise' ? 'enterprise' : 'community'

export const IS_COMMUNITY = EDITION === 'community'
export const IS_ENTERPRISE = EDITION === 'enterprise'

/** Public channel used to capture private-deployment / enterprise inquiries. */
export const ENTERPRISE_DISCUSSIONS_URL =
  'https://github.com/gantangzx/axiflux-agent/discussions'

/** Route keys that belong to the commercial (enterprise) console. */
export const COMMERCIAL_ROUTES = [
  'business',
  'organization',
  'usage',
  'admin-subscriptions',
  'admin-funnel',
  'admin-accounts',
  'agent-templates',
  'api-keys',
  'license-management',
] as const

export const COMMERCIAL_ROUTE_KEYS = new Set<string>(COMMERCIAL_ROUTES)
