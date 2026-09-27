import { useEffect, useRef, useState, type ReactNode } from 'react'
import {
  Layout,
  Button,
  Input,
  Select,
  Spin,
  Tooltip,
  Typography,
  Image,
  Upload,
  App as AntApp,
  Modal,
} from 'antd'
import {
  SendOutlined,
  StopOutlined,
  SafetyCertificateOutlined,
  PlusOutlined,
  DownOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
} from '@ant-design/icons'
import { useChat, type Item, type ChatCtx } from '../chat'
import { api, explainBillingError, navigate } from '../api'
import {
  estimateCost,
  fmtCost,
  fmtPct,
  fmtTokens,
  loadPrices,
  priceFor,
  type PriceTable,
  type TurnUsage,
} from '../cost'
import { renderMarkdown } from '../markdown'
import { OC } from '../theme'
import { ToolCard, ToolGroup } from '../toolview'
import { ThinkingCard } from '../thinkingview'
import { TianshuMark } from '../brand'
import { AgentSwitcher } from '../Sidebar'

const { TextArea } = Input

const SUGGESTIONS = ['用一句话介绍你自己', '帮我写一个快速排序', '解释一下 Reactor 的背压', '帮我总结今天要做的事']

export default function ChatPage() {
  const chat = useChat()
  const scrollRef = useRef<HTMLDivElement>(null)
  // 用户是否停在底部附近：只有这时新 token/新消息才自动滚底；翻页前置历史时不打断
  const nearBottomRef = useRef(true)
  // 翻页加载前置内容后保持视口位置（锚定距底部距离）
  const anchorRef = useRef<number | null>(null)
  // 是否贴底（控制“回到底部”悬浮按钮显隐）
  const [atBottom, setAtBottom] = useState(true)
  // 附件走 chat 全局 state：send() 会读它组请求体并在发送后清空
  const attachments = chat.attachments
  const setAttachments = chat.setAttachments
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [dragOver, setDragOver] = useState(false)

  // 图片压缩：截图/相机原图动辑几 MB（base64 再 +33%），超过阈值时缩到最长边 2048、JPEG 0.85，
  // 既防 413 也省 vision token。小图/非位图（svg/gif）不动。
  const compressImage = (f: File): Promise<Blob> =>
    new Promise((resolve) => {
      if (f.size < 1.5 * 1024 * 1024 || f.type === 'image/svg+xml' || f.type === 'image/gif') {
        resolve(f)
        return
      }
      const img = new window.Image()
      const objUrl = URL.createObjectURL(f)
      img.onload = () => {
        URL.revokeObjectURL(objUrl)
        const MAX = 2048
        const scale = Math.min(1, MAX / Math.max(img.width, img.height))
        const w = Math.round(img.width * scale)
        const h = Math.round(img.height * scale)
        const canvas = document.createElement('canvas')
        canvas.width = w
        canvas.height = h
        canvas.getContext('2d')!.drawImage(img, 0, 0, w, h)
        canvas.toBlob((b) => resolve(b ?? f), 'image/jpeg', 0.85)
      }
      img.onerror = () => {
        URL.revokeObjectURL(objUrl)
        resolve(f)
      }
      img.src = objUrl
    })

  // 图片/文件 → dataURL 附件（上传按钮、粘贴、拖拽共用）
  const addFiles = async (files: File[]) => {
    if (files.length === 0) return
    const loaded: { name: string; url: string; type: string }[] = []
    for (const f of files) {
      const type = f.type.startsWith('image/') ? 'image' : 'file'
      const blob = type === 'image' ? await compressImage(f) : f
      const url = await new Promise<string>((resolve, reject) => {
        const r = new FileReader()
        r.onload = () => resolve(r.result as string)
        r.onerror = reject
        r.readAsDataURL(blob)
      })
      loaded.push({ name: f.name || `pasted-${Date.now()}.png`, url, type })
    }
    setAttachments([...attachments, ...loaded])
  }

  // 截图粘贴：仅当剪贴板含图片时拦截（否则放行文本粘贴）
  const onPaste = (e: React.ClipboardEvent) => {
    const items = Array.from(e.clipboardData?.items ?? [])
    const images = items
      .filter((it) => it.type.startsWith('image/'))
      .map((it) => it.getAsFile())
      .filter((f): f is File => !!f)
    if (images.length > 0) {
      e.preventDefault()
      void addFiles(images)
    }
  }

  // 拖拽图片/文件到输入区
  const onDrop = (e: React.DragEvent) => {
    e.preventDefault()
    setDragOver(false)
    const files = Array.from(e.dataTransfer?.files ?? [])
    if (files.length > 0) void addFiles(files)
  }

  const scrollToBottom = (smooth = false) => {
    const el = scrollRef.current
    if (!el) return
    if (smooth) el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' })
    else el.scrollTop = el.scrollHeight
  }

  const onScroll = () => {
    const el = scrollRef.current
    if (!el) return
    const near = el.scrollHeight - el.scrollTop - el.clientHeight < 120
    nearBottomRef.current = near
    setAtBottom(near)
    // 距顶 < 50px 且还有更早历史且未加载中 → 自动翻页（先记下锚点，前置后保持视口不跳）
    if (el.scrollTop < 50 && chat.hasEarlier(chat.currentId) && !chat.loadingEarlier.has(chat.currentId)) {
      anchorRef.current = el.scrollHeight - el.scrollTop
      nearBottomRef.current = false
      chat.loadEarlier(chat.currentId)
    }
  }

  useEffect(() => {
    const el = scrollRef.current
    if (!el) return
    if (anchorRef.current != null) {
      // 前置了更早的消息：保持视口内容不跳
      el.scrollTop = el.scrollHeight - anchorRef.current
      anchorRef.current = null
    } else if (nearBottomRef.current) {
      // 流式输出期间用瞬时滚动：逐 token 的 smooth 动画会和用户抢滚动条（拉不动/回弹）
      el.scrollTop = el.scrollHeight
    }
  }, [chat.items])

  // 切换会话：重置贴底状态并直接滚到最新消息
  useEffect(() => {
    nearBottomRef.current = true
    setAtBottom(true)
    requestAnimationFrame(() => scrollToBottom(false))
  }, [chat.currentId])

  const empty = chat.items.length === 0

  // 单价表只为「每轮成本徽章」服务；加载失败时退化成只显示 token 数，不阻断任何交互
  const [prices, setPrices] = useState<PriceTable>({})
  useEffect(() => {
    loadPrices().then(setPrices)
  }, [])

  // 会话累计用量：会话列表接口已经带上（CostAccountingHook 写进 session metadata），
  // 所以翻历史会话时同样有数，不需要额外请求。
  const curUsage = chat.sessions.find((s) => s.sessionId === chat.currentId)?.usage

  return (
    <Layout style={{ height: '100%', background: OC.bg }}>
      <div style={{ position: 'relative', flex: 1, minHeight: 0 }}>
        <div
          ref={scrollRef}
          onScroll={onScroll}
          className="oc-chat-scroll"
          style={{ position: 'absolute', inset: 0, overflowY: 'auto', overscrollBehavior: 'contain' }}
        >
          <div style={{ maxWidth: 768, margin: '0 auto', padding: '20px 24px 12px' }}>
            {empty ? (
              chat.loadingSession ? (
                <div style={{ textAlign: 'center', marginTop: '18vh' }}>
                  <Spin size="large" />
                  <div style={{ color: OC.muted, fontSize: 13, marginTop: 12 }}>加载历史消息…</div>
                </div>
              ) : (
              <div style={{ textAlign: 'center', marginTop: '10vh' }}>
              <div style={{ width: 60, height: 60, margin: '0 auto 18px' }}>
                <TianshuMark size={60} />
              </div>
              <div style={{ fontSize: 22, fontWeight: 700, color: OC.textStrong, marginBottom: 6 }}>你好，我是天枢</div>
              <div style={{ color: OC.muted, fontSize: 14, marginBottom: 26 }}>有什么可以帮你的？试试下面的问题，或直接输入。</div>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, justifyContent: 'center', maxWidth: 620, margin: '0 auto' }}>
                {SUGGESTIONS.map((s) => (
                  <button
                    key={s}
                    onClick={() => chat.setInput(s)}
                    style={{
                      cursor: 'pointer',
                      background: OC.card,
                      border: `1px solid ${OC.border}`,
                      color: OC.text,
                      borderRadius: 999,
                      padding: '7px 14px',
                      fontSize: 13,
                    }}
                  >
                    {s}
                  </button>
                ))}
              </div>
            </div>
              )
            ) : (
              <>
                {chat.loadingEarlier.has(chat.currentId) && (
                  <div style={{ textAlign: 'center', padding: '12px 0 4px' }}>
                    <Spin size="small" />
                    <span style={{ color: 'var(--oc-muted)', fontSize: 12, marginLeft: 8 }}>加载更早消息…</span>
                  </div>
                )}
                {renderItems(chat.items, prices, chat.decideApproval)}
              </>
            )}
          </div>
        </div>
        {!atBottom && (
          <button
            type="button"
            className="oc-scroll-bottom"
            title="到达最新"
            aria-label="到达最新"
            onClick={() => {
              nearBottomRef.current = true
              setAtBottom(true)
              scrollToBottom(true)
            }}
          >
            <DownOutlined />
          </button>
        )}
      </div>

      {/* composer：单张卡片——文字区/附件预览/工具条一体（对标 QClaw） */}
      <div style={{ padding: '10px 24px 10px' }}>
        <div
          className="oc-composer"
          onDrop={onDrop}
          onDragOver={(e) => {
            e.preventDefault()
            setDragOver(true)
          }}
          onDragLeave={() => setDragOver(false)}
          style={{
            position: 'relative',
            maxWidth: 768,
            margin: '0 auto',
            padding: '8px 14px 6px',
            outline: dragOver ? `2px dashed ${OC.accent}` : 'none',
            outlineOffset: -2,
          }}
        >
          <TextArea
            value={chat.input}
            onChange={(e) => chat.setInput(e.target.value)}
            onPaste={onPaste}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault()
                chat.send()
              }
            }}
            autoSize={{ minRows: 1, maxRows: 6 }}
            placeholder="可以描述任务或提问任何问题"
            variant="borderless"
            styles={{
              textarea: {
                background: 'transparent',
                color: OC.textStrong,
                fontSize: 14.5,
                padding: '6px 8px 2px',
              },
            }}
          />
          {/* 附件预览：内嵌卡片内，文字区和工具条之间 */}
          {attachments.length > 0 && (
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, padding: '0 8px 4px' }}>
              {attachments.map((a, i) => (
                <div
                  key={i}
                  style={{
                    position: 'relative',
                    width: 48,
                    height: 48,
                    borderRadius: 8,
                    overflow: 'hidden',
                    border: '1px solid ' + OC.border,
                    background: OC.card,
                    flex: '0 0 auto',
                  }}
                >
                  {a.type === 'image' ? (
                    <Image
                      src={a.url}
                      preview={false}
                      style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                    />
                  ) : (
                    <div
                      style={{
                        width: '100%',
                        height: '100%',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        background: OC.card,
                        color: OC.muted,
                        fontSize: 16,
                        fontWeight: 600,
                      }}
                    >
                      📄
                    </div>
                  )}
                  <button
                    type="button"
                    style={{
                      position: 'absolute',
                      top: 1,
                      right: 1,
                      width: 14,
                      height: 14,
                      borderRadius: '50%',
                      border: 0,
                      background: 'rgba(0,0,0,0.6)',
                      color: '#fff',
                      cursor: 'pointer',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      fontSize: 9,
                      lineHeight: 1,
                      padding: 0,
                    }}
                    onClick={() => setAttachments(attachments.filter((_, j) => j !== i))}
                  >
                    ✕
                  </button>
                </div>
              ))}
            </div>
          )}
          {/* 卡片内嵌工具条：左 +附件 / Agent chip，右 模型选择 / 圆形发送键 */}
          <div className="oc-composer-bar">
            <button
              className="oc-composer-btn"
              type="button"
              title="上传图片或文件"
              onClick={() => fileInputRef.current?.click()}
              disabled={chat.busy}
            >
              <PlusOutlined />
            </button>
            <input
              ref={fileInputRef}
              type="file"
              multiple
              accept="image/*,.pdf,.txt,.csv,.json,.md"
              style={{ display: 'none' }}
              onChange={async (e) => {
                await addFiles(Array.from(e.target.files || []))
                e.target.value = ''
              }}
            />
            <AgentSwitcher collapsed={false} chip />
            <div style={{ flex: 1 }} />
            <Select
              allowClear
              showSearch
              variant="borderless"
              placeholder="默认模型"
              size="small"
              className="oc-composer-model"
              style={{ width: 168, color: OC.muted }}
              value={chat.model}
              onChange={chat.setModel}
              options={Array.from(new Set(chat.models.map((m) => m.model))).map((m) => ({
                label: m,
                value: m,
              }))}
            />
            <Tooltip title={chat.busy ? '停止生成' : '发送（Enter）'} placement="top">
              <Button
                type="primary"
                shape="circle"
                className={chat.busy ? 'oc-send-btn oc-stop-btn' : 'oc-send-btn'}
                icon={chat.busy ? <StopOutlined /> : <SendOutlined />}
                onClick={() => (chat.busy ? chat.stop() : chat.send())}
                disabled={!chat.busy && !chat.input.trim() && attachments.length === 0}
                style={{ width: 34, height: 34, flex: '0 0 auto' }}
                aria-label={chat.busy ? '停止生成' : '发送'}
              />
            </Tooltip>
          </div>
        </div>

        {/* 会话累计成本：与上方单轮徽章同源（后端 CostAccountingHook 写的 session metadata），
            翻历史会话同样有数。没跑过带用量的回合就不渲染，免得一排 0 占版面。 */}
        {curUsage && (curUsage.turns ?? 0) > 0 && (
          <div className="oc-chat-usage">
            <span>本会话</span>
            <span>{fmtTokens(curUsage.totalTokens)} tok</span>
            {(curUsage.estimatedCost ?? 0) > 0 && <span>{fmtCost(curUsage.estimatedCost)}</span>}
            {(curUsage.cacheHitRatio ?? 0) > 0 && <span>缓存命中 {fmtPct(curUsage.cacheHitRatio)}</span>}
            <span>{curUsage.turns} 轮</span>
          </div>
        )}
      </div>
    </Layout>
  )
}

function Avatar() {
  return (
    <div
      style={{
        flex: '0 0 auto',
        width: 28,
        height: 28,
        borderRadius: 8,
        background: OC.accent,
        color: '#fff',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        fontSize: 14,
        fontWeight: 800,
        marginTop: 2,
      }}
    >
      ❯
    </div>
  )
}

/**
 * 连续的工具调用（≥2 个）折叠成 ToolGroup 组卡（QClaw 模式）；
 * 单个工具调用仍直接渲染 ToolCard；审批/思考/消息会打断分段。
 */
function renderItems(
  items: Item[],
  prices: PriceTable,
  decideApproval: ChatCtx['decideApproval'],
) {
  const nodes: ReactNode[] = []
  let i = 0
  while (i < items.length) {
    const it = items[i]
    if (it.kind === 'tool') {
      const run: Item[] = []
      while (i < items.length && items[i].kind === 'tool') {
        run.push(items[i])
        i++
      }
      if (run.length >= 2) {
        const tools = run.filter((x): x is Extract<Item, { kind: 'tool' }> => x.kind === 'tool')
        nodes.push(<ToolGroup key={`tg-${run[0].id}`} items={tools} />)
      } else {
        nodes.push(
          <ItemView key={run[0].id} it={run[0]} prices={prices} decideApproval={decideApproval} />,
        )
      }
    } else {
      nodes.push(
        <ItemView key={it.id} it={it} prices={prices} decideApproval={decideApproval} />,
      )
      i++
    }
  }
  return nodes
}

/**
 * 每轮成本徽章：挂在助手气泡下方，一行放 token 数 / 金额 / 模型。
 *
 * 金额按 cost.ts 的前端计价算（用 /usage/prices 下发的那张表），明细放 tooltip。
 * 未配置单价的模型只显示 token —— 这正是后端「无价 ≠ 免费」的同一立场。
 */
function TurnBadge({ usage, prices }: { usage: TurnUsage; prices: PriceTable }) {
  const price = priceFor(prices, usage.provider, usage.model)
  const cost = estimateCost(price, usage)
  const cachedRatio = usage.inputTokens > 0 ? usage.cachedInputTokens / usage.inputTokens : 0

  const rows: [string, string][] = [
    ['输入', `${fmtTokens(usage.inputTokens)} tok`],
    ['输出', `${fmtTokens(usage.outputTokens)} tok`],
    ['命中缓存', `${fmtTokens(usage.cachedInputTokens)} tok（${fmtPct(cachedRatio)}）`],
    ['模型调用', String(usage.modelCalls)],
  ]
  if (usage.model) rows.push(['模型', usage.model])
  if (usage.provider) rows.push(['提供方', usage.provider])

  return (
    <Tooltip
      placement="bottomLeft"
      title={
        <div style={{ fontSize: 12, minWidth: 190 }}>
          <div style={{ fontWeight: 600, marginBottom: 5 }}>本轮用量</div>
          {rows.map(([k, v]) => (
            <div key={k} style={{ display: 'flex', gap: 16, justifyContent: 'space-between' }}>
              <span style={{ opacity: 0.65 }}>{k}</span>
              <span>{v}</span>
            </div>
          ))}
          <div style={{ marginTop: 6, opacity: 0.65 }}>
            {price ? '按部署声明的单价估算' : '该模型未配置单价，仅统计 token'}
          </div>
        </div>
      }
    >
      <span className="oc-turnbadge">
        {fmtTokens(usage.totalTokens)} tok
        {price && <> · {fmtCost(cost)}</>}
        {usage.model && <> · {usage.model}</>}
        {cachedRatio > 0 && <> · 缓存 {fmtPct(cachedRatio)}</>}
      </span>
    </Tooltip>
  )
}

/**
 * 审批卡片：竞品（ChatGPT/Copilot/Cursor/Claude/Manus）的基线——
 * 永远嵌在产生它的聊天流中决定，禁用回头跳转独立列表页。
 *
 * SSE 已经把 callId 推到 ItemObject 字段，但这里只翻 UI 状态；卡片一旦离 server
 * 不报（404 / 410）保留为审计，其它终端设备依然管理同一套队列。
 */
function ApprovalCard({
  it,
  onDecide,
}: {
  it: Extract<Item, { kind: 'approval' }>
  onDecide: (decision: 'approved' | 'rejected', scope?: 'once' | 'session') => void
}) {
  const { message } = AntApp.useApp()
  const [busy, setBusy] = useState(false)
  const [rejectOpen, setRejectOpen] = useState(false)
  const [rejectReason, setRejectReason] = useState('')
  const callId = it.callId
  const decided = it.status === 'approved' || it.status === 'rejected'

  const act = async (
    decision: 'approved' | 'rejected',
    scope?: 'once' | 'session',
    reason?: string,
  ) => {
    if (!callId || decided || busy) return
    setBusy(true)
    try {
      if (decision === 'approved') {
        await api.post(`/api/v1/approvals/${encodeURIComponent(callId)}/approve`, { scope })
        message.success(scope === 'session' ? '已批准本会话放行' : '已批准')
      } else {
        await api.post(`/api/v1/approvals/${encodeURIComponent(callId)}/reject`, {
          reason: reason || undefined,
        })
        message.success('已拒绝')
      }
      onDecide(decision, scope)
    } catch (e: any) {
      // 该审批可能已被超时/被其他设备处理：转成软成功，避免 UI 仍被高亮
      message.warning(
        e?.message
          ? `审批已处理：${e.message}`
          : '该审批已不在队列中（可能被超时/其他设备处理）。',
      )
      onDecide(decision, scope)
    } finally {
      setBusy(false)
      setRejectOpen(false)
      setRejectReason('')
    }
  }

  if (decided) {
    const isApproved = it.status === 'approved'
    return (
      <div
        style={{
          border: `1px solid ${isApproved ? '#1f4d2f' : '#5c2020'}`,
          background: isApproved ? 'rgba(82,196,26,0.08)' : 'rgba(220,49,70,0.08)',
          borderRadius: OC.radiusLg,
          padding: '10px 14px',
          marginBottom: 12,
          fontSize: 13,
          color: isApproved ? '#9be58f' : '#ff9a9a',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          {isApproved ? <CheckCircleOutlined /> : <CloseCircleOutlined />}
          <b style={{ color: OC.textStrong }}>{it.toolName}</b>
          <span>
            {isApproved
              ? it.scope === 'session'
                ? '已批准本会话放行'
                : '已批准一次'
              : '已拒绝'}
          </span>
        </div>
        <div style={{ marginTop: 6, whiteSpace: 'pre-wrap', color: OC.muted }}>
          {it.description}
        </div>
      </div>
    )
  }

  return (
    <div
      style={{
        border: '1px solid #4d3b12',
        background: 'rgba(212,160,23,0.10)',
        borderRadius: OC.radiusLg,
        padding: '10px 14px',
        marginBottom: 12,
        fontSize: 13,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: '#f0c36d' }}>
        <SafetyCertificateOutlined />
        <b style={{ color: OC.textStrong }}>{it.toolName}</b>
        <Typography.Text style={{ color: '#f0c36d' }}>等待人工审批</Typography.Text>
      </div>
      <div style={{ marginTop: 6, whiteSpace: 'pre-wrap', color: '#d8c4a0' }}>
        {it.description}
      </div>
      <div style={{ display: 'flex', gap: 8, marginTop: 12, flexWrap: 'wrap' }}>
        <Button
          size="small"
          type="primary"
          loading={busy}
          disabled={!callId}
          onClick={() => act('approved', 'once')}
        >
          批准一次
        </Button>
        <Button
          size="small"
          loading={busy}
          disabled={!callId}
          onClick={() => act('approved', 'session')}
        >
          批准本会话
        </Button>
        <Button
          size="small"
          danger
          loading={busy}
          disabled={!callId}
          onClick={() => setRejectOpen(true)}
        >
          拒绝
        </Button>
        {!callId && (
          <span style={{ fontSize: 11, color: OC.muted, alignSelf: 'center' }}>
            未拿到 callId（可能为子代理遗留事件），请到「审批」页处理
          </span>
        )}
      </div>
      <Modal
        title={`拒绝 ${it.toolName}`}
        open={rejectOpen}
        onCancel={() => {
          setRejectOpen(false)
          setRejectReason('')
        }}
        onOk={() => act('rejected', undefined, rejectReason)}
        okText="拒绝"
        okButtonProps={{ danger: true }}
        cancelText="取消"
      >
        <Input
          value={rejectReason}
          onChange={(e) => setRejectReason(e.target.value)}
          placeholder="拒绝原因（可选），如：风险过高"
          autoFocus
        />
      </Modal>
    </div>
  )
}

function ItemView({
  it,
  prices,
  decideApproval,
}: {
  it: Item
  prices: PriceTable
  decideApproval: ChatCtx['decideApproval']
}) {
  if (it.kind === 'user') {
    const imgs = (it.attachments ?? []).filter((a) => a.type === 'image')
    const files = (it.attachments ?? []).filter((a) => a.type !== 'image')
    return (
      <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 16 }}>
        <div
          style={{
            maxWidth: '78%',
            background: OC.bgElevated,
            border: `1px solid ${OC.border}`,
            color: OC.textStrong,
            padding: '9px 14px',
            borderRadius: '14px 14px 6px 14px',
            whiteSpace: 'pre-wrap',
            fontSize: 14.5,
            lineHeight: 1.6,
          }}
        >
          {imgs.length > 0 && (
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: it.text ? 8 : 0 }}>
              {imgs.map((a, i) => (
                <Image
                  key={i}
                  src={a.url}
                  alt={a.name || '图片'}
                  style={{ maxWidth: 240, maxHeight: 240, borderRadius: 10, objectFit: 'cover' }}
                />
              ))}
            </div>
          )}
          {files.length > 0 && (
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginBottom: it.text ? 8 : 0 }}>
              {files.map((a, i) => (
                <span
                  key={i}
                  style={{
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: 6,
                    border: `1px solid ${OC.border}`,
                    borderRadius: 999,
                    padding: '3px 10px',
                    fontSize: 12.5,
                    color: OC.muted,
                    background: OC.card,
                    maxWidth: 240,
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    whiteSpace: 'nowrap',
                  }}
                >
                  📄 {a.name || '附件'}
                </span>
              ))}
            </div>
          )}
          {it.text}
        </div>
      </div>
    )
  }
  if (it.kind === 'assistant') {
    return (
      <div style={{ display: 'flex', gap: 10, marginBottom: 16 }}>
        <Avatar />
        <div style={{ maxWidth: '85%', minWidth: 0, paddingTop: 3 }}>
          {it.text ? (
            <div className="md-body" dangerouslySetInnerHTML={{ __html: renderMarkdown(it.text) }} />
          ) : it.streaming ? (
            <span className="oc-typing">
              <span className="oc-typing__dots">
                <i />
                <i />
                <i />
              </span>
              思考中…
            </span>
          ) : null}
          {!it.streaming && it.usage && <TurnBadge usage={it.usage} prices={prices} />}
        </div>
      </div>
    )
  }
  if (it.kind === 'thinking') {
    return <ThinkingCard it={it} />
  }
  if (it.kind === 'tool') {
    return <ToolCard it={it} />
  }
  if (it.kind === 'approval') {
    return <ApprovalCard it={it} onDecide={(decision, scope) => decideApproval(it.id, decision, scope)} />
  }
  return <ErrorCard text={it.text} />
}

/** 错误气泡：识别 P0 门禁(402)/P1 超额(429)，给出中文说明与升级入口。 */
function ErrorCard({ text }: { text: string }) {
  const info = explainBillingError(text)
  if (info.kind) {
    return (
      <div
        style={{
          border: `1px solid ${info.kind === 'quota' ? 'rgba(240,160,80,0.4)' : '#5c2020'}`,
          background:
            info.kind === 'quota' ? 'rgba(240,160,80,0.10)' : 'rgba(220,49,70,0.10)',
          color: info.kind === 'quota' ? '#f5c089' : '#ff9a9a',
          borderRadius: OC.radiusLg,
          padding: '12px 14px',
          marginBottom: 12,
          fontSize: 13,
        }}
      >
        <div style={{ marginBottom: 8 }}>
          {info.kind === 'quota' ? '🔒 ' : '💎 '}
          {info.text}
        </div>
        <Button
          size="small"
          type="primary"
          style={{ borderRadius: OC.radiusSm }}
          onClick={() => navigate('business')}
        >
          查看 / 升级套餐
        </Button>
      </div>
    )
  }
  return (
    <div
      style={{
        border: '1px solid #5c2020',
        background: 'rgba(220,49,70,0.10)',
        color: '#ff9a9a',
        borderRadius: OC.radiusLg,
        padding: '8px 14px',
        marginBottom: 12,
        fontSize: 13,
      }}
    >
      ⚠️ {text}
    </div>
  )
}
