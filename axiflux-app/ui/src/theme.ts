import { theme as antdTheme, type ThemeConfig } from 'antd'

/**
 * Axiflux Control UI 配色（取自 Axiflux dist/control-ui 的 WebAwesome 变量）。
 * dark/light 两套具体色板用于构建 antd token；组件里用的 `OC.*` 是 CSS 变量引用，
 * 随外观设置实时切换。
 */
export const PALETTE = {
  dark: {
    bg: '#0e1015',
    bgElevated: '#191c24',
    card: '#161920',
    hover: '#1f2330',
    border: '#1e2028',
    textStrong: '#f4f4f5',
    text: '#bcbcc0',
    muted: '#8b8b94',
    codeBg: '#1f2330',
    preBg: '#0b0d12',
  },
  light: {
    bg: '#faf9f7',
    bgElevated: '#ffffff',
    card: '#ffffff',
    hover: '#efebe4',
    border: '#e8e4dc',
    textStrong: '#211e1a',
    text: '#403c35',
    muted: '#6e6960',
    codeBg: '#f2efe8',
    preBg: '#f6f4ee',
  },
}

/**
 * 组件内联样式统一用这些 CSS 变量（由 applyCssVars 写入 :root），
 * 这样切换深/浅/主题色时所有页面实时生效，无需逐页改。
 */
export const OC = {
  bg: 'var(--oc-bg)',
  bgElevated: 'var(--oc-elevated)',
  elevated: 'var(--oc-elevated)',
  card: 'var(--oc-card)',
  popover: 'var(--oc-popover)',
  hover: 'var(--oc-hover)',
  border: 'var(--oc-border)',
  borderStrong: 'var(--oc-border-strong)',
  textStrong: 'var(--oc-text-strong)',
  text: 'var(--oc-text)',
  muted: 'var(--oc-muted)',
  accent: 'var(--oc-accent)',
  accentSubtle: 'var(--oc-accent-soft)',
  radiusSm: 6,
  radiusMd: 10,
  radiusLg: 14,
}

/** Axiflux 官方主题（dist/control-ui/themes/*.css 的 accent 色）。 */
export type AccentId =
  | 'claw'
  | 'openknot'
  | 'absolutely'
  | 'dash'
  | 'beacon'
  | 'phosphor'
  | 'tide'

export const ACCENTS: { id: AccentId; name: string; dark: string; light: string }[] = [
  { id: 'claw', name: 'Claw · 珊瑚', dark: '#ff5c5c', light: '#bd4531' },
  { id: 'openknot', name: 'Knot · 绯红', dark: '#f03e52', light: '#d92a3f' },
  { id: 'absolutely', name: 'Absolutely · 赤陶', dark: '#e68b6b', light: '#c0603f' },
  { id: 'dash', name: 'Dash · 橙棕', dark: '#dd9c60', light: '#a86a37' },
  { id: 'beacon', name: 'Beacon · 琥珀', dark: '#ffc233', light: '#b8860b' },
  { id: 'phosphor', name: 'Phosphor · 荧绿', dark: '#4ade80', light: '#2f9e57' },
  { id: 'tide', name: 'Tide · 青蓝', dark: '#5ab6d8', light: '#2f87a8' },
]

export const accentOf = (id: AccentId) => ACCENTS.find((a) => a.id === id) || ACCENTS[0]

export type Mode = 'system' | 'light' | 'dark'

export const effectiveMode = (mode: Mode): 'light' | 'dark' => {
  if (mode === 'system')
    return typeof window !== 'undefined' && window.matchMedia('(prefers-color-scheme: dark)').matches
      ? 'dark'
      : 'light'
  return mode
}

export function hexA(hex: string, alpha: number): string {
  const h = hex.replace('#', '')
  const r = parseInt(h.slice(0, 2), 16)
  const g = parseInt(h.slice(2, 4), 16)
  const b = parseInt(h.slice(4, 6), 16)
  return `rgba(${r},${g},${b},${alpha})`
}

/** Build the antd v6 ThemeConfig for a resolved mode + accent. */
export function buildTheme(eff: 'light' | 'dark', accentId: AccentId): ThemeConfig {
  const p = PALETTE[eff]
  const accent = accentOf(accentId)[eff]
  const isDark = eff === 'dark'
  // Tooltip/Tour use a fixed dark spotlight with light text regardless of the
  // surrounding mode. Reusing bgElevated here made light-mode tooltips white-on-white.
  const spotlightBg = isDark ? '#2a2e38' : 'rgba(0, 0, 0, 0.82)'

  return {
    algorithm: isDark ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
    token: {
      colorPrimary: accent,
      colorBgBase: p.bg,
      colorBgLayout: p.bg,
      colorBgContainer: p.card,
      colorBgElevated: p.bgElevated,
      colorBgSpotlight: spotlightBg,
      colorBorder: p.border,
      colorBorderSecondary: isDark ? '#1a1d25' : '#eeeae2',
      colorText: p.textStrong,
      colorTextSecondary: p.text,
      colorTextTertiary: p.muted,
      colorTextQuaternary: isDark ? '#5a5e6b' : '#a9a49a',
      colorPrimaryHover: isDark ? hexA(accent, 0.85) : accent,
      colorPrimaryActive: accent,
      colorPrimaryBg: hexA(accent, 0.12),
      colorPrimaryBgHover: hexA(accent, 0.2),
      colorPrimaryBorder: hexA(accent, 0.35),
      colorPrimaryBorderHover: hexA(accent, 0.55),
      colorLink: isDark ? hexA(accent, 0.85) : accent,
      borderRadius: 10,
      borderRadiusLG: 14,
      borderRadiusSM: 8,
      controlHeight: 36,
      wireframe: false,
      fontFamily:
        "'Geist', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', 'Noto Sans CJK SC', system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif",
      fontFamilyCode:
        "'Geist Mono', ui-monospace, 'Cascadia Code', 'SF Mono', Menlo, Consolas, monospace",
      boxShadow: isDark
        ? '0 14px 34px rgba(0,0,0,0.60), 0 0 0 1px rgba(255,255,255,0.05)'
        : '0 14px 34px rgba(33,30,26,0.14), 0 2px 8px rgba(33,30,26,0.06)',
      boxShadowSecondary: isDark
        ? '0 6px 18px rgba(0,0,0,0.42)'
        : '0 6px 18px rgba(33,30,26,0.08)',
    },
    components: {
      Layout: { bodyBg: p.bg, headerBg: p.bg, siderBg: p.bg, triggerBg: p.card },
      Menu: {
        darkItemBg: 'transparent',
        darkSubMenuItemBg: 'transparent',
        darkItemSelectedBg: hexA(accent, 0.14),
        darkItemHoverBg: isDark ? 'rgba(31,35,48,0.55)' : 'transparent',
        darkItemColor: p.muted,
        darkItemSelectedColor: p.textStrong,
        darkItemHoverColor: p.textStrong,
        itemColor: p.muted,
        itemSelectedColor: accent,
        itemHoverColor: p.textStrong,
        itemSelectedBg: hexA(accent, 0.12),
        itemHoverBg: p.hover,
        subMenuItemBg: 'transparent',
        itemBorderRadius: OC.radiusMd,
        itemMarginInline: 8,
        itemHeight: 38,
        iconSize: 16,
      },
      Button: {
        primaryShadow: 'none',
        defaultShadow: 'none',
        fontWeight: 600,
        borderRadius: 10,
        controlHeight: 34,
        paddingInline: 18,
      },
      Card: {
        colorBgContainer: p.card,
        colorBorderSecondary: p.border,
        borderRadiusLG: 14,
        // 与 .oc-panel 同一层级语言：静态卡片一层轻阴影，头部 46px
        boxShadowTertiary: isDark
          ? '0 1px 2px rgba(0,0,0,0.30)'
          : '0 1px 2px rgba(33,30,26,0.05)',
        headerHeight: 46,
        headerFontSize: 14.5,
        headerBg: 'transparent',
      },
      Input: { colorBgContainer: p.card, activeBorderColor: accent, hoverBorderColor: accent, borderRadius: 10 },
      InputNumber: { colorBgContainer: p.card, borderRadius: 10, activeBorderColor: accent, hoverBorderColor: accent },
      Select: { colorBgContainer: p.card, borderRadius: 10 },
      Segmented: { borderRadius: 8, itemSelectedBg: hexA(accent, 0.16), itemSelectedColor: accent },
      Table: {
        colorBgContainer: 'transparent',
        headerBg: 'transparent',
        headerColor: p.muted,
        headerSplitColor: 'transparent',
        headerBorderRadius: 0,
        borderColor: p.border,
        rowHoverBg: hexA(accent, 0.06),
        colorText: p.text,
        colorTextHeading: p.textStrong,
        cellPaddingBlock: 11,
        cellPaddingInline: 14,
        fontSize: 13.5,
      },
      Modal: { contentBg: p.card, headerBg: p.card },
      Tooltip: { colorBgSpotlight: spotlightBg, colorTextLightSolid: '#ffffff' },
      Divider: { colorSplit: p.border },
      Descriptions: { colorTextSecondary: p.muted, colorText: p.textStrong },
      Tabs: { itemColor: p.muted, itemSelectedColor: p.textStrong, inkBarColor: accent },
      Tag: { defaultBg: p.bgElevated, defaultColor: p.text },
    },
  }
}

/** Write CSS custom properties so non-antd surfaces (markdown, body, inline styles) follow the theme. */
export function applyCssVars(eff: 'light' | 'dark', accentId: AccentId) {
  const p = PALETTE[eff]
  const accent = accentOf(accentId)[eff]
  const root = document.documentElement
  const set = (k: string, v: string) => root.style.setProperty(k, v)
  set('--oc-bg', p.bg)
  set('--oc-elevated', p.bgElevated)
  set('--oc-card', p.card)
  set('--oc-hover', p.hover)
  set('--oc-border', p.border)
  set('--oc-text-strong', p.textStrong)
  set('--oc-text', p.text)
  set('--oc-muted', p.muted)
  set('--oc-accent', accent)
  set('--oc-accent-soft', hexA(accent, 0.12))
  set('--oc-code-bg', p.codeBg)
  set('--oc-pre-bg', p.preBg)
  const dark = eff === 'dark'
  set('--oc-shadow-card', dark ? '0 1px 2px rgba(0,0,0,0.30)' : '0 1px 2px rgba(33,30,26,0.05)')
  set('--oc-shadow-raise', dark ? '0 6px 18px rgba(0,0,0,0.42)' : '0 6px 18px rgba(33,30,26,0.08)')
  set('--oc-shadow-pop', dark
    ? '0 14px 34px rgba(0,0,0,0.60), 0 0 0 1px rgba(255,255,255,0.05)'
    : '0 14px 34px rgba(33,30,26,0.14), 0 2px 8px rgba(33,30,26,0.06)')
  set('color-scheme', eff)
  document.body.style.background = p.bg
  document.body.style.color = p.textStrong
}
