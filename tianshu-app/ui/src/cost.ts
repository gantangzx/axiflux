/**
 * Token 计价：与后端 ModelCost 的成本模型对齐。
 *
 * 为什么钱算在前端：每轮的 token 数只出现在聊天流 DONE 帧的 rawPayload 里，
 * 没有任何接口返回「单轮成本」；而单价表属于配置数据，由
 * GET /api/v1/usage/prices 下发——就是成本路由与 CostAccountingHook 用的同一张表。
 * 所以这里算出的单轮金额与会话累计（后端 usage.estimatedCost）同源。
 *
 * 改本文件请同步核对 tianshu-core 的 ModelCost.estimate。
 */
import { api } from './api'

export type ModelPrice = {
  /** 每 1000 prompt token 的单价 */
  inputPer1k: number
  /** 每 1000 completion token 的单价 */
  outputPer1k: number
  /** 每 1000 命中缓存的 prompt token 单价；null = 按输入价计（保守） */
  cachedInputPer1k: number | null
}

export type PriceTable = Record<string, ModelPrice>

/** 单轮用量，取自 DONE 帧 rawPayload.metadata（后端 TurnUsage 的序列化形态）。 */
export type TurnUsage = {
  inputTokens: number
  outputTokens: number
  cachedInputTokens: number
  totalTokens: number
  modelCalls: number
  model?: string | null
  provider?: string | null
}

/** 会话累计用量（session metadata 的 usage 键，由 CostAccountingHook 维护）。 */
export type SessionUsage = {
  turns?: number
  inputTokens?: number
  outputTokens?: number
  cachedInputTokens?: number
  totalTokens?: number
  cacheHitRatio?: number
  costCurrency?: string
  estimatedCost?: number
}

let pricePromise: Promise<PriceTable> | null = null

/**
 * 拉取部署声明的单价表，进程内缓存一份。
 * 失败或未配置时返回空表而非 reject：没配单价的部署应当照常显示 token 数，
 * 而不是凭空造一个金额出来。
 */
export function loadPrices(force = false): Promise<PriceTable> {
  if (!pricePromise || force) {
    pricePromise = api
      .get<{ prices?: PriceTable }>('/api/v1/usage/prices')
      .then((r) => r?.prices ?? {})
      .catch(() => ({}))
  }
  return pricePromise
}

/**
 * 取某一行的单价，顺序与后端一致：先按 provider 名（单价表以 provider 为键），
 * 再退到 model 名（有些部署直接给具体模型定价）。
 */
export function priceFor(
  table: PriceTable | null | undefined,
  provider?: string | null,
  model?: string | null,
): ModelPrice | null {
  if (!table) return null
  if (provider && table[provider]) return table[provider]
  if (model && table[model]) return table[model]
  return null
}

/**
 * 单轮成本。缓存 token 在声明了缓存价时按缓存价计，否则按输入价计——
 * 与 ModelCost.estimate(prompt, cached, completion) 完全一致。
 */
export function estimateCost(
  price: ModelPrice | null,
  u: { inputTokens?: number; outputTokens?: number; cachedInputTokens?: number },
): number {
  if (!price) return 0
  const input = u.inputTokens ?? 0
  const output = u.outputTokens ?? 0
  const cached = Math.max(0, Math.min(u.cachedInputTokens ?? 0, input))
  const uncached = input - cached
  const cachedRate = price.cachedInputPer1k ?? price.inputPer1k
  return (
    (uncached / 1000) * price.inputPer1k +
    (cached / 1000) * cachedRate +
    (output / 1000) * price.outputPer1k
  )
}

/** 单价单位由部署声明，这里只给展示符号，不断言币种。 */
export const COST_UNIT = '$'

/** token 数：千位以内全显，上万后收敛到 k/M，避免徽章被长数字撑开 */
export function fmtTokens(v: number | null | undefined): string {
  const n = Math.max(0, Math.floor(v || 0))
  if (n < 1000) return String(n)
  if (n < 10_000) return (n / 1000).toFixed(1) + 'k'
  if (n < 1_000_000) return Math.round(n / 1000) + 'k'
  return (n / 1_000_000).toFixed(2) + 'M'
}

/** 金额：单轮常常不到 1 分钱，低于 0.01 时保留 4 位，否则会被四舍五入成 0 */
export function fmtCost(v: number | null | undefined): string {
  const n = v || 0
  if (n <= 0) return COST_UNIT + '0'
  if (n < 0.01) return COST_UNIT + n.toFixed(4)
  if (n < 1) return COST_UNIT + n.toFixed(3)
  return COST_UNIT + n.toFixed(2)
}

/** 占比展示：0 → 0%，其余保留一位小数但不超过 100% */
export function fmtPct(v: number | null | undefined): string {
  const n = Math.max(0, Math.min(1, v || 0))
  return (n * 100).toFixed(n > 0 && n < 0.1 ? 1 : 0) + '%'
}

/**
 * 从 DONE 帧的 rawPayload（序列化后的整个 AgentResponse）里取出本轮用量。
 *
 * 这是单轮用量唯一到达浏览器的路径：transcript 接口存的是消息，不含用量，
 * 所以翻历史时拿不到每轮数字（真要用请看会话累计）。
 */
export function turnUsageFromDone(rawPayload?: string | null): TurnUsage | undefined {
  if (!rawPayload) return undefined
  try {
    const meta = JSON.parse(rawPayload)?.metadata
    if (!meta || typeof meta !== 'object') return undefined
    const inputTokens = Number(meta.inputTokens) || 0
    const outputTokens = Number(meta.outputTokens) || 0
    // 没真正调用模型的回合没有可展示的用量
    if (!inputTokens && !outputTokens) return undefined
    return {
      inputTokens,
      outputTokens,
      cachedInputTokens: Number(meta.cachedInputTokens) || 0,
      totalTokens: Number(meta.totalTokens) || inputTokens + outputTokens,
      modelCalls: Number(meta.modelCalls) || 0,
      model: typeof meta.model === 'string' ? meta.model : null,
      provider: typeof meta.provider === 'string' ? meta.provider : null,
    }
  } catch {
    return undefined
  }
}
