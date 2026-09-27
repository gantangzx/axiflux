import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import type { ThemeConfig } from 'antd'
import {
  ACCENTS,
  applyCssVars,
  buildTheme,
  effectiveMode,
  type AccentId,
  type Mode,
} from './theme'

const MODE_KEY = 'oc.ui.mode'
const ACCENT_KEY = 'oc.ui.accent'

const loadMode = (): Mode => {
  const m = localStorage.getItem(MODE_KEY)
  return m === 'light' || m === 'dark' || m === 'system' ? m : 'dark'
}
const loadAccent = (): AccentId => {
  const a = localStorage.getItem(ACCENT_KEY) as AccentId | null
  return a && ACCENTS.some((x) => x.id === a) ? a : 'claw'
}

type AppearanceCtx = {
  mode: Mode
  accent: AccentId
  eff: 'light' | 'dark'
  themeConfig: ThemeConfig
  setMode: (m: Mode) => void
  setAccent: (a: AccentId) => void
}

const Ctx = createContext<AppearanceCtx | null>(null)

export function AppearanceProvider({ children }: { children: ReactNode }) {
  const [mode, setModeState] = useState<Mode>(loadMode)
  const [accent, setAccentState] = useState<AccentId>(loadAccent)
  const [eff, setEff] = useState<'light' | 'dark'>(() => effectiveMode(loadMode()))

  // react to OS theme changes while in "system" mode
  useEffect(() => {
    const mq = window.matchMedia('(prefers-color-scheme: dark)')
    const onChange = () => {
      if (loadMode() === 'system') setEff(effectiveMode('system'))
    }
    mq.addEventListener('change', onChange)
    return () => mq.removeEventListener('change', onChange)
  }, [])

  useEffect(() => {
    const e = effectiveMode(mode)
    setEff(e)
    applyCssVars(e, accent)
  }, [mode, accent])

  const setMode = (m: Mode) => {
    localStorage.setItem(MODE_KEY, m)
    setModeState(m)
  }
  const setAccent = (a: AccentId) => {
    localStorage.setItem(ACCENT_KEY, a)
    setAccentState(a)
  }

  return (
    <Ctx.Provider
      value={{ mode, accent, eff, themeConfig: buildTheme(eff, accent), setMode, setAccent }}
    >
      {children}
    </Ctx.Provider>
  )
}

export function useAppearance(): AppearanceCtx {
  const c = useContext(Ctx)
  if (!c) throw new Error('useAppearance must be used within AppearanceProvider')
  return c
}
