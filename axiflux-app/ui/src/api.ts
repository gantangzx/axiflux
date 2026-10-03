// Lightweight fetch wrapper for the Axiflux REST API.
// The console runs against the same origin in production; `vite dev` proxies /api.

const DEV_USER_ID = 'console-user'

/**
 * Effective user id for API calls. When authenticated, use the real internal
 * account id returned at login; fall back to the dev single-user id only in
 * auth-disabled mode.
 */
export function getUserId(): string {
  return getAuthUser()?.userId || DEV_USER_ID
}

// Self-serve auth (SaaS onboarding): the bearer token issued by
// POST /api/v1/auth/login is kept in localStorage and attached to every API
// call. When the backend runs with auth disabled (single-user/dev) no token is
// present and requests go through untouched — the server injects its wildcard
// dev identity in that mode.
const TOKEN_KEY = 'oc.auth.token'
const USER_KEY = 'oc.auth.user'

export type AuthUser = {
  userId: string
  username: string
  email?: string | null
  displayName?: string | null
  scopes?: string[]
}

export function getToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY)
  } catch {
    return null
  }
}

export function getAuthUser(): AuthUser | null {
  try {
    const raw = localStorage.getItem(USER_KEY)
    return raw ? (JSON.parse(raw) as AuthUser) : null
  } catch {
    return null
  }
}

export function saveAuth(token: string, user: AuthUser): void {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(USER_KEY, JSON.stringify(user))
}

/** True when the stored login carries a scope (or a wildcard). No login = open single-user mode. */
export function hasScope(scope: string): boolean {
  const u = getAuthUser()
  if (!u) return true // auth disabled: server injects wildcard identity
  const scopes = u.scopes || []
  return scopes.includes(scope) || scopes.some((s) => s === '*' || s.endsWith(':*'))
}

export function clearAuth(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

/** Fired whenever a call learns the session is no longer valid (401). */
export const AUTH_EXPIRED_EVENT = 'oc:auth-expired'
function emitAuthExpired(): void {
  window.dispatchEvent(new Event(AUTH_EXPIRED_EVENT))
}

/**
 * Cross-cutting navigation without a router dependency: deep components (e.g.
 * the chat error bubble) dispatch {@code oc:navigate} and the shell switches
 * page. {@link navigate} is the helper for callers.
 */
export const NAVIGATE_EVENT = 'oc:navigate'
export function navigate(page: string): void {
  window.dispatchEvent(new CustomEvent<string>(NAVIGATE_EVENT, { detail: page }))
}

function authHeaders(extra?: Record<string, string>): Record<string, string> {
  const headers: Record<string, string> = { ...(extra ?? {}) }
  const token = getToken()
  if (token) headers.Authorization = `Bearer ${token}`
  return headers
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const hadToken = !!getToken()
  const res = await fetch(path, {
    method,
    headers:
      body !== undefined
        ? authHeaders({ 'Content-Type': 'application/json' })
        : authHeaders(),
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  // A 401 only means "session expired" when we actually presented a token.
  // On the public auth edges (login/register) a 401 is a normal business
  // error ("invalid credentials") and must NOT be turned into an auth-expired
  // event, or the login form's own error message gets clobbered.
  const isAuthEdge = path.startsWith('/api/v1/auth/')
  if (res.status === 401 && hadToken && !isAuthEdge) {
    clearAuth()
    emitAuthExpired()
  }
  if (!res.ok) {
    let detail = ''
    try {
      const errBody = await res.json()
      // Uniform error envelope { success:false, error } from GlobalExceptionHandler.
      detail = errBody?.error ? String(errBody.error) : JSON.stringify(errBody)
    } catch {
      /* ignore */
    }
    throw new Error(`${method} ${path} -> ${res.status} ${detail}`.trim())
  }
  if (res.status === 204) return undefined as T
  const ct = res.headers.get('content-type') || ''
  if (!ct.includes('application/json') && !ct.includes('+json')) return (await res.text()) as unknown as T
  const json = await res.json()
  // Uniform success envelope { success:true, data:<payload> } (non-streaming JSON only;
  // SSE streams and the MCP JSON-RPC surface use their own wire format). Unwrap here so
  // call sites keep consuming the payload shape directly.
  if (json && typeof json === 'object' && typeof json.success === 'boolean' && 'data' in json) {
    if (!json.success) {
      throw new Error(`${method} ${path} -> ${json.error ?? 'request failed'}`)
    }
    return json.data as T
  }
  return json as T
}

export const api = {
  get: <T,>(path: string) => request<T>('GET', path),
  post: <T,>(path: string, body?: unknown) => request<T>('POST', path, body ?? {}),
  put: <T,>(path: string, body?: unknown) => request<T>('PUT', path, body ?? {}),
  patch: <T,>(path: string, body?: unknown) => request<T>('PATCH', path, body ?? {}),
  del: <T,>(path: string) => request<T>('DELETE', path),
  getText: (path: string) => request<string>('GET', path),
}

/**
 * 把计费/门禁类错误（P0 402、P1 429）翻译成面向用户的中文说明并标注类型，
 * 便于 Chat 页给出升级入口；非计费错误返回 kind=null、原文不变。
 */
export function explainBillingError(raw?: string | null): {
  kind: 'plan' | 'quota' | null
  text: string
} {
  const msg = String(raw || '').toLowerCase()
  if (msg.includes(' 429 ') || msg.includes('quota exceeded') || msg.includes('upgrade your plan')) {
    return {
      kind: 'quota',
      text: '本月额度已用完，发送已暂停。你可以升级套餐，或等待下个自然月额度重置后继续。',
    }
  }
  if (msg.includes(' 402 ') || msg.includes('payment required') || msg.includes('requires the')) {
    return {
      kind: 'plan',
      text: '当前套餐不包含此能力，升级到更高套餐即可使用。',
    }
  }
  return { kind: null, text: String(raw || '') }
}

// Stream a chat turn over SSE, invoking onEvent for each parsed event.
// signal 可选：abort 时主动关闭底层 reader，使 finally 立刻进入收尾态（不再依赖服务端 DONE）。
export async function streamChat(
  body: unknown,
  onEvent: (ev: Record<string, unknown>) => void,
  signal?: AbortSignal,
): Promise<void> {
  const res = await fetch('/api/v1/chat/stream', {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(body),
    signal,
  })
  if (res.status === 401) {
    clearAuth()
    emitAuthExpired()
  }
  if (!res.ok || !res.body) throw new Error(`chat stream failed: ${res.status}`)
  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buf = ''
  for (;;) {
    if (signal?.aborted) {
      try { await reader.cancel() } catch { /* already closed */ }
      throw new DOMException('The user aborted a request.', 'AbortError')
    }
    const { done, value } = await reader.read()
    if (done) break
    buf += decoder.decode(value, { stream: true })
    let idx
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const chunk = buf.slice(0, idx)
      buf = buf.slice(idx + 2)
      const line = chunk.split('\n').find((l) => l.startsWith('data:'))
      if (!line) continue
      try {
        onEvent(JSON.parse(line.slice(5).trim()))
      } catch {
        /* ignore malformed frame */
      }
    }
  }
}
