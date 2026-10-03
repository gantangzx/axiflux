import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'
import {
  api,
  AUTH_EXPIRED_EVENT,
  clearAuth,
  getAuthUser,
  getToken,
  saveAuth,
  type AuthUser,
} from './api'

type LoginResponse = {
  token: string
  userId: string
  username: string
  email?: string | null
  displayName?: string | null
  scopes?: string[]
  expiresInSeconds?: number
}

type RegisterResponse = {
  userId: string
  username: string
  email?: string | null
  displayName?: string | null
  organization?: {
    orgId: string
    name: string
    slug: string
    planTier: string
    status: string
    trialEndsAt?: string | null
  }
}

type AuthStatus = 'checking' | 'unrestricted' | 'login' | 'authed'

type AuthContextValue = {
  status: AuthStatus
  user: AuthUser | null
  /** True once we know whether the backend enforces login. */
  ready: boolean
  login: (loginName: string, password: string) => Promise<void>
  register: (
    username: string,
    email: string,
    password: string,
  ) => Promise<RegisterResponse>
  logout: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

/**
 * Probe a lightweight authenticated GET endpoint to learn whether the backend
 * enforces login. A 401 means the resource server requires a bearer token; any
 * other outcome (200 with auth off / a still-valid stored token, or even 4xx)
 * means we do not need to block the shell — the page layer handles its own
 * errors. No token is attached here so the result reflects the auth mode rather
 * than a possibly stale token.
 */
async function probeLoginRequired(): Promise<boolean> {
  try {
    const res = await fetch('/api/v1/billing/me', { method: 'GET' })
    return res.status === 401
  } catch {
    // Network down is not an auth problem; let the shell render and surface
    // per-request errors rather than trapping the user on a login screen.
    return false
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('checking')
  const [user, setUser] = useState<AuthUser | null>(() => getAuthUser())

  useEffect(() => {
    let alive = true
    probeLoginRequired().then((required) => {
      if (!alive) return
      if (!required) {
        // Backend runs with auth disabled (single-user / dev): no gate.
        setStatus('unrestricted')
        return
      }
      // Login required. The probe deliberately omits the token, so a stored
      // token is still potentially valid — verify it with a token-bearing call.
      if (getToken()) {
        api
          .get<unknown>('/api/v1/billing/me')
          .then(() => alive && setStatus('authed'))
          .catch(() => {
            if (!alive) return
            clearAuth()
            setUser(null)
            setStatus('login')
          })
      } else {
        setStatus('login')
      }
    })
    return () => {
      alive = false
    }
  }, [])

  // Any API call returning 401 bounces back to the login gate.
  useEffect(() => {
    const onExpired = () => {
      setUser(null)
      setStatus('login')
    }
    window.addEventListener(AUTH_EXPIRED_EVENT, onExpired)
    return () => window.removeEventListener(AUTH_EXPIRED_EVENT, onExpired)
  }, [])

  const login = useCallback(async (loginName: string, password: string) => {
    const res = await api.post<LoginResponse>('/api/v1/auth/login', {
      login: loginName,
      password,
    })
    const u: AuthUser = {
      userId: res.userId,
      username: res.username,
      email: res.email,
      displayName: res.displayName,
      scopes: res.scopes,
    }
    saveAuth(res.token, u)
    setUser(u)
    setStatus('authed')
  }, [])

  const register = useCallback(
    async (username: string, email: string, password: string) => {
      const res = await api.post<RegisterResponse>('/api/v1/auth/register', {
        username,
        email: email || undefined,
        password,
      })
      // Registration creates the account + trial org but does not issue a
      // token; the login page immediately logs the user in afterwards.
      return res
    },
    [],
  )

  const logout = useCallback(() => {
    clearAuth()
    setUser(null)
    setStatus('login')
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({ status, user, ready: status !== 'checking', login, register, logout }),
    [status, user, login, register, logout],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within <AuthProvider>')
  return ctx
}
