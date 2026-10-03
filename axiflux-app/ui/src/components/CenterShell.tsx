import type { ReactNode } from 'react'
import { Tabs } from 'antd'
import { OC } from '../theme'

/**
 * One entry in a center page's in-page sub-navigation.
 *
 * A "center" page (个人中心 / 套餐与计费 / 组织 …) owns a single top-level
 * route but groups several related sections. Modelling those sections as data
 * lets us add more without touching routing: append a {@link CenterTab} and the
 * header tab strip — and, if needed later, a deep link — picks it up.
 */
export type CenterTab = {
  key: string
  label: ReactNode
  icon?: ReactNode
  content: ReactNode
}

/**
 * Shared layout for standalone "center" pages.
 *
 * Structure: a page header (icon + title + description + optional extra action)
 * followed by an optional top tab strip. With a single tab or `hideNav` the
 * strip is omitted, so the page behaves as a plain standalone page while still
 * sharing the header treatment.
 *
 * Intentionally route-agnostic — callers render it inside their own route key,
 * which keeps the state-based navigation in {@file App.tsx} unchanged.
 */
export default function CenterShell({
  icon,
  title,
  description,
  extra,
  tabs,
  activeKey,
  defaultKey,
  onTabChange,
  hideNav,
  children,
}: {
  icon?: ReactNode
  title: ReactNode
  description?: ReactNode
  /** Action rendered at the right edge of the header (e.g. a primary button). */
  extra?: ReactNode
  /** Sub-sections; when omitted the page renders `children` directly. */
  tabs?: CenterTab[]
  activeKey?: string
  defaultKey?: string
  onTabChange?: (key: string) => void
  /** Force-hide the tab strip even with multiple tabs. */
  hideNav?: boolean
  children?: ReactNode
}) {
  const showTabs = !hideNav && tabs && tabs.length > 1
  const controlled = activeKey !== undefined

  return (
    <div style={{ padding: '28px 32px 40px', maxWidth: 1080, margin: '0 auto' }}>
      <header
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 16,
          padding: '18px 20px',
          marginBottom: showTabs ? 8 : 20,
          borderRadius: OC.radiusLg,
          border: `1px solid ${OC.border}`,
          background:
            'linear-gradient(135deg, var(--oc-accent-soft) 0%, var(--oc-card) 58%)',
        }}
      >
        {icon && (
          <span
            aria-hidden
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              width: 46,
              height: 46,
              borderRadius: OC.radiusMd,
              fontSize: 22,
              color: 'var(--oc-accent)',
              background: 'var(--oc-card)',
              border: `1px solid ${OC.border}`,
              flex: '0 0 auto',
            }}
          >
            {icon}
          </span>
        )}
        <div style={{ minWidth: 0, flex: 1 }}>
          <div style={{ fontSize: 19, fontWeight: 700, color: OC.textStrong, lineHeight: 1.3 }}>
            {title}
          </div>
          {description && (
            <div style={{ marginTop: 4, fontSize: 13, color: OC.muted }}>{description}</div>
          )}
        </div>
        {extra}
      </header>

      {showTabs ? (
        <Tabs
          size="small"
          tabBarStyle={{ margin: '0 4px', paddingLeft: 4 }}
          activeKey={controlled ? activeKey : undefined}
          defaultActiveKey={defaultKey ?? tabs![0].key}
          onChange={onTabChange}
          items={tabs!.map((t) => ({
            key: t.key,
            label: (
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                {t.icon}
                {t.label}
              </span>
            ),
            children: <div className="oc-center-tab">{t.content}</div>,
          }))}
        />
      ) : (
        children ??
        (tabs && tabs.length === 1 ? <div className="oc-center-tab">{tabs[0].content}</div> : null)
      )}
    </div>
  )
}
