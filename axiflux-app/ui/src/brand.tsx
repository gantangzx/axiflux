/**
 * AxiFlux（Axiflux）品牌标识。
 *
 * 意象：北斗七星连线，AxiFlux（北斗第一星，众星之枢）以主色高亮并环绕枢轴环——
 * 见名之意：智能体编排的中枢。
 *
 * 纯内联 SVG，深色印玺底在亮/暗主题下均成立；可作为侧栏品牌标、favicon 等。
 */
export function AxifluxMark({ size = 28, radius = 0.24 }: { size?: number; radius?: number }) {
  const r = 64 * radius
  return (
    <svg width={size} height={size} viewBox="0 0 64 64" fill="none" aria-label="AxiFlux" role="img">
      <defs>
        <linearGradient id="tsBg" x1="8" y1="4" x2="56" y2="60" gradientUnits="userSpaceOnUse">
          <stop offset="0" stopColor="#1c2231" />
          <stop offset="1" stopColor="#0b0d13" />
        </linearGradient>
        <radialGradient
          id="tsPivot"
          cx="0"
          cy="0"
          r="1"
          gradientTransform="translate(50.5 49.5) rotate(90) scale(6.2)"
          gradientUnits="userSpaceOnUse"
        >
          <stop offset="0" stopColor="#ff8a66" />
          <stop offset="1" stopColor="#ff4d4d" />
        </radialGradient>
      </defs>
      <rect x="1.5" y="1.5" width="61" height="61" rx={r} fill="url(#tsBg)" stroke="#ffffff" strokeOpacity=".09" />
      <path
        d="M11.5 47.5 L22 40.5 L32.5 36.5 L42 32.5 L47 43 L39.5 51.5 L50.5 49.5 L42 32.5"
        stroke="#ffffff"
        strokeOpacity=".42"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <circle cx="11.5" cy="47.5" r="2.1" fill="#e9ebf2" fillOpacity=".92" />
      <circle cx="22" cy="40.5" r="2.2" fill="#e9ebf2" fillOpacity=".92" />
      <circle cx="32.5" cy="36.5" r="2.4" fill="#e9ebf2" fillOpacity=".92" />
      <circle cx="42" cy="32.5" r="2.4" fill="#e9ebf2" fillOpacity=".92" />
      <circle cx="47" cy="43" r="2.4" fill="#e9ebf2" fillOpacity=".92" />
      <circle cx="39.5" cy="51.5" r="2.7" fill="#f4f5f9" />
      <circle cx="50.5" cy="49.5" r="8.4" fill="#ff5c5c" fillOpacity=".14" />
      <circle cx="50.5" cy="49.5" r="6.6" stroke="#ff6b5c" strokeOpacity=".55" strokeWidth="1.1" />
      <circle cx="50.5" cy="49.5" r="3.9" fill="url(#tsPivot)" />
    </svg>
  )
}
