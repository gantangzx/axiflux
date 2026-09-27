import { useEffect, useRef, useState } from 'react'
import { BulbOutlined, DownOutlined, LoadingOutlined } from '@ant-design/icons'
import type { Item } from './chat'
import { renderMarkdown } from './markdown'

type ThinkItem = Extract<Item, { kind: 'thinking' }>

const fmtDuration = (ms?: number) => {
  if (ms == null || ms < 0) return ''
  return ms < 1000 ? '不到 1 秒' : `用时 ${(ms / 1000).toFixed(ms < 10000 ? 1 : 0)} 秒`
}

/**
 * 深度思考块（对标 QClaw / Tianshu 的思考过程卡片）：
 * - 流式期间标题「深度思考中…」+ spinner，默认展开实时滚动
 * - 回答开始（DONE）后自动折叠为「已深度思考 · 用时 Ns」，可手动展开回看
 */
export function ThinkingCard({ it }: { it: ThinkItem }) {
  const [open, setOpen] = useState(!!it.streaming)
  const wasStreaming = useRef(!!it.streaming)
  const bodyRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (wasStreaming.current && !it.streaming) {
      wasStreaming.current = false
      setOpen(false)
    }
  }, [it.streaming])

  // 流式输出时把思考正文钉在底部，新内容不被折叠行遮住
  useEffect(() => {
    if (open && it.streaming && bodyRef.current) {
      bodyRef.current.scrollTop = bodyRef.current.scrollHeight
    }
  }, [it.text, open, it.streaming])

  const dur = fmtDuration(it.durationMs)

  return (
    <div
      className={[
        'oc-think',
        open ? 'oc-think--open' : '',
        it.streaming ? 'oc-think--active' : '',
      ].filter(Boolean).join(' ')}
    >
      <button className="oc-think__header" onClick={() => setOpen(!open)} type="button">
        <span className={`oc-think__icon${it.streaming ? ' oc-think__icon--running' : ''}`}>
          {it.streaming ? <LoadingOutlined /> : <BulbOutlined />}
        </span>
        <span className="oc-think__title">
          {it.streaming ? '深度思考中' : '已深度思考'}
          {it.streaming && <span className="oc-think__dots"><i /><i /><i /></span>}
        </span>
        {!it.streaming && dur && <span className="oc-think__meta">{dur}</span>}
        <span className="oc-think__spacer" />
        <DownOutlined className="oc-think__chevron" />
      </button>
      {open && (
        <div className="oc-think__body" ref={bodyRef}>
          <div
            className="oc-think__text md-body"
            dangerouslySetInnerHTML={{ __html: renderMarkdown(it.text || '') }}
          />
        </div>
      )}
    </div>
  )
}
