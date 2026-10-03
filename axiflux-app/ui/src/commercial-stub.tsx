import EnterpriseGate from './EnterpriseGate'

// Community build: replaces ./commercial via a Vite alias. Every commercial
// route renders an enterprise upsell card instead of calling closed endpoints.
const GATE: Record<string, { title: string; desc: string }> = {
  business: { title: '套餐与计费', desc: 'Stripe 订阅、套餐对比、用量配额与自助升级。' },
  organization: { title: '组织', desc: '团队空间、成员与角色管理、组织级 SSO 与治理策略。' },
  usage: { title: '用量', desc: 'Token / 成本按月统计、组织用量看板与导出。' },
  'admin-subscriptions': { title: '订阅管理', desc: '全平台订阅总览、暂停恢复与手动赠额。' },
  'admin-funnel': { title: '转化漏斗', desc: '注册→激活→触限→升级的全链路转化分析。' },
  'agent-templates': { title: '模板', desc: 'Agent 角色模板市场，一键创建专属代理。' },
  'api-keys': { title: 'API Key', desc: '组织级 API 访问密钥管理与用量追踪。' },
  'license-management': { title: '离线授权', desc: '私有化部署 License 的激活、续期与撤销管理。' },
}

export default function CommercialRoutes({ route }: { route: string }) {
  const g = GATE[route]
  if (!g) return null
  return <EnterpriseGate title={g.title}>{g.desc}</EnterpriseGate>
}
