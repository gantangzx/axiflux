import type { ReactNode } from 'react'
import {
  MessageOutlined,
  DashboardOutlined,
  MonitorOutlined,
  ClockCircleOutlined,
  InboxOutlined,
  BranchesOutlined,
  RobotOutlined,
  ToolOutlined,
  ThunderboltOutlined,
  TeamOutlined,
  BulbOutlined,
  SafetyOutlined,
  FileSearchOutlined,
  ApiOutlined,
  SettingOutlined,
  CreditCardOutlined,
  BankOutlined,
  AreaChartOutlined,
  CrownOutlined,
  FilterOutlined,
  AppstoreOutlined,
  NodeIndexOutlined,
  KeyOutlined,
  SafetyCertificateOutlined,
  IdcardOutlined,
} from '@ant-design/icons'

export type NavItem = {
  key: string
  label: string
  desc: string
  icon: ReactNode
  badge?: boolean
  /** If set, the item is only shown to a caller whose scopes include this value. */
  requiredScope?: string
}

/**
 * 分区（section）= 左侧图标轨上的一格。
 *
 * 竞品（VS Code / Cursor / Linear）的一级导航都是「图标轨 + 上下文面板」两层：
 * 图标轨只放 5 个左右的分区，视觉噪声极低；面板按当前分区展示内容，
 * 因此任一时刻屏幕上只出现 3~5 个条目，而不是把十几个页面平铺出来。
 *
 * kind='chat' 的分区没有 items——它的面板本身就是会话列表（高频主功能，
 * 需要独占纵向空间）；kind='pages' 的面板渲染该分区的页面入口。
 */
export type NavSection = {
  key: string
  label: string
  hint: string
  icon: ReactNode
  kind: 'chat' | 'pages'
  items: NavItem[]
}

export const NAV_SECTIONS: NavSection[] = [
  {
    key: 'chat',
    label: '对话',
    hint: '会话与历史记录',
    icon: <MessageOutlined />,
    kind: 'chat',
    items: [],
  },
  {
    key: 'workbench',
    label: '工作台',
    hint: '运行状态与会话数据',
    icon: <DashboardOutlined />,
    kind: 'pages',
    items: [
      { key: 'overview', label: '概览', desc: '系统状态、关键指标与快捷入口', icon: <DashboardOutlined /> },
      { key: 'monitor', label: '监控', desc: '运行时指标、工具调用与延迟', icon: <MonitorOutlined /> },
      { key: 'workflows', label: '工作流', desc: '状态图定义、运行与暂停恢复', icon: <NodeIndexOutlined /> },
      { key: 'scheduler', label: '定时任务', desc: '定时与周期任务的调度管理', icon: <ClockCircleOutlined /> },
      { key: 'sessions', label: '会话管理', desc: '全部会话记录、状态与归档', icon: <BranchesOutlined /> },
      { key: 'memory', label: '记忆', desc: '长期记忆的检索与维护', icon: <BulbOutlined /> },
    ],
  },
  {
    key: 'agent',
    label: '智能体',
    hint: '人格、能力与模型',
    icon: <RobotOutlined />,
    kind: 'pages',
    items: [
      { key: 'agents', label: '代理', desc: '智能体人格、模型绑定与工具权限', icon: <RobotOutlined /> },
      { key: 'tools', label: '工具', desc: '内置 / MCP / 插件工具清单与风险级别', icon: <ToolOutlined /> },
      { key: 'skills', label: '技能', desc: '技能安装、热加载与执行', icon: <ThunderboltOutlined /> },
      { key: 'subagents', label: '子代理', desc: '后台委派任务运行状态', icon: <TeamOutlined /> },
      { key: 'models', label: '模型', desc: 'LLM 供应商注册与成本路由', icon: <ApiOutlined /> },
      { key: 'agent-templates', label: '模板', desc: 'Agent 角色模板市场，一键创建专属代理', icon: <AppstoreOutlined /> },
    ],
  },
  {
    key: 'governance',
    label: '治理',
    hint: '审批、权限与审计',
    icon: <SafetyOutlined />,
    kind: 'pages',
    items: [
      { key: 'approvals', label: '收件箱', desc: '待审批事项与系统通知', icon: <InboxOutlined />, badge: true },
      { key: 'security', label: '安全策略', desc: '工具权限、审批门控与访问控制', icon: <SafetyOutlined /> },
      { key: 'audit', label: '审计', desc: '操作与工具调用审计日志', icon: <FileSearchOutlined /> },
      {
        key: 'admin-subscriptions',
        label: '订阅管理',
        desc: '全平台订阅、暂停恢复与手动赠额',
        icon: <CrownOutlined />,
        requiredScope: 'users:admin',
      },
      {
        key: 'admin-funnel',
        label: '转化漏斗',
        desc: '注册→激活→触限→升级的转化分析',
        icon: <FilterOutlined />,
        requiredScope: 'users:admin',
      },
      {
        key: 'api-keys',
        label: 'API Key',
        desc: '组织级 API 访问密钥管理与用量追踪',
        icon: <KeyOutlined />,
        requiredScope: 'users:admin',
      },
      {
        key: 'license-management',
        label: '离线授权',
        desc: '私有化部署 License 激活与撤销管理',
        icon: <SafetyCertificateOutlined />,
        requiredScope: 'license:admin',
      },
    ],
  },
  {
    key: 'account',
    label: '账户',
    hint: '套餐、组织与偏好',
    icon: <SettingOutlined />,
    kind: 'pages',
    items: [
      { key: 'business', label: '套餐与计费', desc: '当前订阅、套餐对比与升级', icon: <CreditCardOutlined /> },
      { key: 'organization', label: '组织', desc: '团队组织、成员与角色管理', icon: <BankOutlined /> },
      { key: 'usage', label: '用量', desc: 'Token / 成本按月统计', icon: <AreaChartOutlined /> },
      { key: 'settings', label: '设置', desc: '外观主题与系统运行参数', icon: <SettingOutlined /> },
    ],
  },
]

/**
 * Standalone "center" pages.
 *
 * These have their own top-level route but intentionally do NOT appear in a
 * sidebar section — they are reached from contextual affordances (e.g. the
 * first item of the account dropdown). Modelled as data so adding another
 * center page requires no routing/sidebar change.
 */
export type StandalonePage = {
  key: string
  label: string
  desc: string
  icon: ReactNode
  /** Sidebar section whose rail item stays highlighted while on the page. */
  section: string
  requiredScope?: string
}

export const STANDALONE_PAGES: StandalonePage[] = [
  {
    key: 'profile',
    label: '个人中心',
    desc: '身份信息、显示名与账号安全',
    icon: <IdcardOutlined />,
    section: 'account',
  },
]

export const NAV_ITEMS: NavItem[] = NAV_SECTIONS.flatMap((s) => s.items)

export const NAV_META: Record<string, { title: string; desc: string; icon: ReactNode }> =
  NAV_SECTIONS.reduce(
    (acc, s) => {
      for (const it of s.items) acc[it.key] = { title: it.label, desc: it.desc, icon: it.icon }
      return acc
    },
    STANDALONE_PAGES.reduce(
      (acc, p) => {
        acc[p.key] = { title: p.label, desc: p.desc, icon: p.icon }
        return acc
      },
      {} as Record<string, { title: string; desc: string; icon: ReactNode }>,
    ),
  )

export const sectionMeta = (key: string): NavSection | undefined =>
  NAV_SECTIONS.find((s) => s.key === key)

/** 页面 → 所属分区（用于图标轨高亮同步）；对话页恒定落在 chat 分区。 */
export const sectionOfPage = (p: string): string => {
  if (p === 'chat') return 'chat'
  const standalone = STANDALONE_PAGES.find((sp) => sp.key === p)
  if (standalone) return standalone.section
  return NAV_SECTIONS.find((s) => s.items.some((it) => it.key === p))?.key ?? 'workbench'
}
