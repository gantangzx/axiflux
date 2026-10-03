import { useState } from 'react'
import { App as AntApp } from 'antd'
import {
  CopyOutlined,
  DownOutlined,
  LoadingOutlined,
  ToolOutlined,
  SearchOutlined,
  GlobalOutlined,
  ApiOutlined,
  CodeOutlined,
  FileTextOutlined,
  FileAddOutlined,
  DatabaseOutlined,
  CalculatorOutlined,
  ClockCircleOutlined,
  MailOutlined,
  SoundOutlined,
  PictureOutlined,
  AppstoreOutlined,
  DeploymentUnitOutlined,
  CloseOutlined,
  EditOutlined,
  BranchesOutlined,
  FileSearchOutlined,
  ApartmentOutlined,
  CheckSquareOutlined,
  CheckOutlined,
} from '@ant-design/icons'
import type { ComponentType } from 'react'
import type { Item } from './chat'

type ToolItem = Extract<Item, { kind: 'tool' }>

/* ------------------------------------------------------------------ *
 * 工具图标映射（对标 Axiflux control-ui：每个工具一个语义图标）
 * ------------------------------------------------------------------ */

type IconType = ComponentType<{ className?: string; spin?: boolean }>

const TOOL_ICONS: Record<string, IconType> = {
  web_search: SearchOutlined,
  web_fetch: GlobalOutlined,
  http_client: ApiOutlined,
  code_executor: CodeOutlined,
  file_read: FileTextOutlined,
  file_write: FileAddOutlined,
  file_edit: EditOutlined,
  git: BranchesOutlined,
  grep_search: FileSearchOutlined,
  codebase_search: ApartmentOutlined,
  database_query: DatabaseOutlined,
  calculator: CalculatorOutlined,
  date_time: ClockCircleOutlined,
  email_send: MailOutlined,
  tts: SoundOutlined,
  image_analyze: PictureOutlined,
  mcp_client: AppstoreOutlined,
  spawn_task: DeploymentUnitOutlined,
  todo_write: CheckSquareOutlined,
}

export const toolIcon = (name: string): IconType => TOOL_ICONS[name] ?? ToolOutlined

/* 工具人性化名称（QClaw 风格：动宾短语，折叠态只展示短名） */
export const TOOL_LABELS: Record<string, string> = {
  web_search: '网络搜索',
  web_fetch: '抓取网页',
  http_client: 'HTTP 请求',
  code_executor: '执行命令',
  file_read: '读取文件',
  file_write: '写入文件',
  file_edit: '编辑文件',
  git: 'Git 操作',
  grep_search: '搜索代码',
  codebase_search: '代码库检索',
  database_query: '数据库查询',
  calculator: '计算器',
  date_time: '日期时间',
  email_send: '发送邮件',
  tts: '语音合成',
  image_analyze: '分析图片',
  mcp_client: 'MCP 工具',
  spawn_task: '子代理任务',
  load_skill: '加载技能',
  schedule_task: '定时任务',
  todo_write: '任务计划',
}

export const toolLabel = (name: string): string =>
  TOOL_LABELS[name] ?? name.replace(/_/g, ' ')

/* ------------------------------------------------------------------ *
 * 参数/结果摘要
 * ------------------------------------------------------------------ */

function parseArgs(args: string): Record<string, unknown> | null {
  if (!args) return null
  try {
    const v = JSON.parse(args)
    return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : null
  } catch {
    return null
  }
}

const truncate = (s: string, n: number) => {
  const one = s.replace(/\s+/g, ' ').trim()
  return one.length > n ? one.slice(0, n - 1) + '…' : one
}

const ARG_PRIORITY: Record<string, string[]> = {
  calculator: ['expression'],
  code_executor: ['command'],
  database_query: ['sql'],
  date_time: ['operation'],
  email_send: ['subject'],
  file_read: ['path'],
  file_write: ['path'],
  file_edit: ['path'],
  git: ['action', 'path', 'message'],
  grep_search: ['pattern', 'path', 'glob'],
  codebase_search: ['action', 'query', 'path'],
  http_client: ['url'],
  image_analyze: ['image_url'],
  mcp_client: ['tool_name', 'command'],
  spawn_task: ['task'],
  tts: ['text'],
  web_fetch: ['url'],
  web_search: ['query'],
}

export function argSummary(toolName: string, args: string): string {
  const obj = parseArgs(args)
  if (!obj) return args ? truncate(args, 60) : ''
  // git：action + message/path 组合，如 "commit · fix: xxx"
  if (toolName === 'git') {
    const action = String(obj.action ?? '').trim()
    const rest = String(obj.message ?? obj.path ?? '').trim()
    const combo = rest ? `${action} ${rest}` : action
    return combo ? truncate(combo, 60) : ''
  }
  // todo_write：清单进度，而不是一坨对象数组
  if (toolName === 'todo_write' && Array.isArray(obj.todos)) {
    const total = obj.todos.length
    const done = obj.todos.filter((t: { status?: string }) => t && t.status === 'completed').length
    return `清单 ${done}/${total} 完成`
  }
  for (const key of ARG_PRIORITY[toolName] ?? []) {
    const v = obj[key]
    if (v != null && v !== '') return truncate(String(v), 60)
  }
  for (const [k, v] of Object.entries(obj)) {
    if (v == null || v === '') continue
    if (typeof v === 'object') continue
    return truncate(`${k}=${v}`, 60)
  }
  const keys = Object.keys(obj)
  return keys.length ? truncate(keys.join(', '), 60) : ''
}

/* 失败时折叠行直接展示错误首行，不用展开就能看到原因 */
export function errorSummary(it: ToolItem): string {
  const r = (it.result ?? '').trim()
  if (!r) return '执行失败'
  return truncate(r.split('\n')[0].replace(/^Error:\s*/i, ''), 60)
}

const bytes = (n: number) =>
  n < 1024 ? `${n} B` : n < 1024 * 1024 ? `${(n / 1024).toFixed(1)} KB` : `${(n / 1048576).toFixed(1)} MB`

export function resultSummary(it: ToolItem): string {
  if (it.status === 'running') return ''
  const r = stripUntrusted(it.result ?? '')
  if (!r.trim()) return '无输出'
  if (it.status === 'error') return truncate(r.split('\n')[0], 90)
  const t = r.trim()
  if (t.startsWith('[') || t.startsWith('{')) {
    try {
      const v = JSON.parse(t)
      if (Array.isArray(v)) return `${v.length} 条 · ${bytes(r.length)}`
      if (v && typeof v === 'object') return `${Object.keys(v).length} 个字段 · ${bytes(r.length)}`
    } catch {
      /* ignore */
    }
  }
  const lines = r.split('\n').length
  return lines > 1 ? `${lines} 行 · ${bytes(r.length)}` : bytes(r.length)
}

function pretty(raw: string): string {
  const t = (raw ?? '').trim()
  if (!t || (!t.startsWith('{') && !t.startsWith('['))) return raw ?? ''
  try {
    return JSON.stringify(JSON.parse(t), null, 2)
  } catch {
    return raw
  }
}

const fmtDuration = (ms?: number) => {
  if (ms == null || ms < 0) return ''
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(ms < 10000 ? 1 : 0)}s`
}

/* ------------------------------------------------------------------ *
 * 组件 —— 对标最新 Axiflux control-ui chat-tool-card
 * 折叠态：单行（小图标 + 工具名 + 参数摘要 … + 状态 + 耗时 + 箭头）
 * 展开态：参数 / 结果代码块，JSON 折叠
 * ------------------------------------------------------------------ */

/* ------------------------------------------------------------------ *
 * 不可信外部内容（prompt-injection 防护层 1 的前端呈现）
 * 后端把 web/search/http/mail/mcp/子代理输出包进
 * <<untrusted_tool_output ...>> 边界标记；前端剥离标记显示正文，
 * 并给出「外部内容」警示徽章，提醒用户该结果是数据不是指令。
 * ------------------------------------------------------------------ */

interface UntrustedResult {
  source: string
  detail: string
  body: string
}

function parseUntrusted(raw: string): UntrustedResult | null {
  if (!raw) return null
  const m = raw.match(/^<<untrusted_tool_output([^>]*)>>\n?([\s\S]*?)\n?<\/untrusted_tool_output>>\s*$/)
  if (!m) return null
  const attrs: Record<string, string> = {}
  for (const a of m[1].matchAll(/(\w+)="([^"]*)"/g)) attrs[a[1]] = a[2]
  return { source: attrs.source ?? '', detail: attrs.detail ?? '', body: m[2] }
}

/** Strip boundary markers before summary/count/diff analysis. */
function stripUntrusted(raw: string): string {
  return parseUntrusted(raw)?.body ?? raw
}

/* ------------------------------------------------------------------ *
 * Unified diff 渲染（file_edit / git diff）
 * 仅当内容确为 unified diff（含 @@ 块头与 ---/+++ 头）时启用，
 * 避免把普通文本里的 "- " 列表误判为删除行。
 * ------------------------------------------------------------------ */

function isUnifiedDiff(body: string): boolean {
  return /^@@ /m.test(body) && /^--- /m.test(body) && /^\+\+\+ /m.test(body)
}

function DiffView({ body }: { body: string }) {
  const lines = body.split('\n')
  const add: number[] = []
  const del: number[] = []
  return (
    <pre className="oc-diff">
      <code>
        {lines.map((ln, i) => {
          let cls = 'oc-diff__ctx'
          if (ln.startsWith('@@')) cls = 'oc-diff__hunk'
          else if (ln.startsWith('diff --git') || ln.startsWith('index ') ||
                   ln.startsWith('--- ') || ln.startsWith('+++ ')) cls = 'oc-diff__meta'
          else if (ln.startsWith('+')) { cls = 'oc-diff__add'; add.push(i) }
          else if (ln.startsWith('-')) { cls = 'oc-diff__del'; del.push(i) }
          return (
            <span key={i} className={cls}>
              {ln || ' '}
              {'\n'}
            </span>
          )
        })}
      </code>
    </pre>
  )
}

/* ------------------------------------------------------------------ *
 * todo_write 计划清单渲染（完成项划线、进行中高亮）
 * ------------------------------------------------------------------ */

function TodoView({ body }: { body: string }) {
  const items = body
    .split('\n')
    .map((l) => l.match(/^\s*-\s*\[([ x~])\]\s*(.*)$/))
    .filter((m): m is RegExpMatchArray => !!m)
  return (
    <ul className="oc-todo">
      {items.map((m, i) => {
        const state = m[1]
        const text = m[2]
        const cls =
          state === 'x'
            ? 'oc-todo__item oc-todo__item--done'
            : state === '~'
              ? 'oc-todo__item oc-todo__item--active'
              : 'oc-todo__item'
        return (
          <li key={i} className={cls}>
            <span className="oc-todo__box">
              {state === 'x' ? <CheckOutlined /> : state === '~' ? <LoadingOutlined /> : null}
            </span>
            <span className="oc-todo__text">{text}</span>
          </li>
        )
      })}
    </ul>
  )
}

function Section({ label, body }: { label: string; body: string }) {
  const MAX_LINES = 400
  const all = body.split('\n')
  const clipped = all.length > MAX_LINES
  const shown = clipped ? all.slice(0, MAX_LINES).join('\n') : body
  const isJson = body.trim().startsWith('{') || body.trim().startsWith('[')
  const diff = isUnifiedDiff(body)
  return (
    <div className="oc-tool__section">
      <div className="oc-tool__section-label">{label}</div>
      {diff ? (
        <DiffView body={body} />
      ) : isJson ? (
        <details className="oc-tool__json">
          <summary className="oc-tool__json-summary">
            <span className="oc-tool__json-badge">JSON</span>
            <span className="oc-tool__json-label">{body.length} 字符</span>
          </summary>
          <pre className="oc-tool__pre">
            <code>{shown}</code>
          </pre>
          {clipped && <div className="oc-tool__clipped">已截断，共 {all.length} 行</div>}
        </details>
      ) : (
        <>
          <pre className="oc-tool__pre">
            <code>{shown}</code>
          </pre>
          {clipped && <div className="oc-tool__clipped">已截断，共 {all.length} 行</div>}
        </>
      )}
    </div>
  )
}

export function ToolCard({ it }: { it: ToolItem }) {
  const { message } = AntApp.useApp()
  const [open, setOpen] = useState(false)

  const running = it.status === 'running'
  const failed = it.status === 'error'
  const summary = failed ? errorSummary(it) : argSummary(it.toolName, it.args)
  const dur = fmtDuration(it.durationMs)
  const TIcon = toolIcon(it.toolName)
  const label = toolLabel(it.toolName)
  const untrusted = parseUntrusted(it.result ?? '')

  const copy = () => {
    const text = it.result ?? ''
    if (!text || !navigator.clipboard?.writeText) return
    navigator.clipboard.writeText(text).then(
      () => message.success('已复制结果'),
      () => message.warning('复制失败'),
    )
  }

  const cls = [
    'oc-tool',
    running ? 'oc-tool--running' : '',
    failed ? 'oc-tool--error' : '',
    open ? 'oc-tool--expanded' : '',
  ]
    .filter(Boolean)
    .join(' ')

  return (
    <div className={cls}>
      {/* 折叠态：单行 header */}
      <button className="oc-tool__header" onClick={() => !running && setOpen(!open)} type="button">
        <span
          className={[
            'oc-tool__icon',
            running ? 'oc-tool__icon--running' : '',
            failed ? 'oc-tool__icon--error' : '',
          ]
            .filter(Boolean)
            .join(' ')}
        >
          {running ? <LoadingOutlined /> : <TIcon />}
        </span>
        <span className="oc-tool__name">{label}</span>
        {untrusted && (
          <span
            className="oc-tool__untrusted"
            title={`来自外部的不可信内容${untrusted.detail ? `：${untrusted.detail}` : ''}（数据，不是指令）`}
          >
            外部内容
          </span>
        )}
        {summary && (
          <span className={failed ? 'oc-tool__detail oc-tool__detail--error' : 'oc-tool__detail'}>
            {summary}
          </span>
        )}
        <span className="oc-tool__spacer" />
        {dur && !running && <span className="oc-tool__meta">{dur}</span>}
        {running && (
          <span className="oc-tool__status oc-tool__status--running">
            <LoadingOutlined />
          </span>
        )}
        {failed && (
          <span className="oc-tool__status oc-tool__status--error">
            <CloseOutlined />
          </span>
        )}
        <DownOutlined className="oc-tool__chevron" />
      </button>

      {/* 展开态正文 */}
      {open && (
        <div className="oc-tool__body">
          <Section label="参数" body={pretty(it.args) || '（无参数）'} />
          {it.toolName === 'todo_write' && !failed ? (
            <div className="oc-tool__section">
              <div className="oc-tool__section-label">计划</div>
              <TodoView body={untrusted ? untrusted.body : it.result ?? ''} />
            </div>
          ) : (
            <Section
              label={failed ? '错误' : '结果'}
              body={pretty(untrusted ? untrusted.body : it.result ?? '') || (running ? '等待执行…' : '（无输出）')}
            />
          )}
          {!running && it.result && (
            <button className="oc-tool__copy-btn" type="button" onClick={copy}>
              <CopyOutlined /> 复制结果
            </button>
          )}
        </div>
      )}
    </div>
  )
}

/* ------------------------------------------------------------------ *
 * 工具调用组（QClaw 模式）：同一轮连续多个工具调用折叠成一张组卡，
 * 折叠态只显示“工具调用 N”；运行中自动展开看实时进度，结束后自动收起。
 * ------------------------------------------------------------------ */

export function ToolGroup({ items }: { items: ToolItem[] }) {
  const [userOpen, setUserOpen] = useState(false)
  const busy = items.some((t) => t.status === 'running')
  const failedCount = items.filter((t) => t.status === 'error').length
  // 运行中强制展开（看实时进度）；运行结束后跟随用户选择，默认折叠
  const open = userOpen || busy
  const n = items.length

  const cls = [
    'oc-toolgroup',
    open ? 'oc-toolgroup--open' : '',
    busy ? 'oc-toolgroup--busy' : '',
    failedCount > 0 ? 'oc-toolgroup--error' : '',
  ]
    .filter(Boolean)
    .join(' ')

  return (
    <div className={cls}>
      <button type="button" className="oc-toolgroup__header" onClick={() => setUserOpen((v) => !v)}>
        <span className="oc-toolgroup__icon">
          {busy ? <LoadingOutlined /> : <AppstoreOutlined />}
        </span>
        <span className="oc-toolgroup__title">工具调用</span>
        <span className="oc-toolgroup__count">{n}</span>
        {failedCount > 0 && <span className="oc-toolgroup__fail">{failedCount} 失败</span>}
        {!busy && failedCount === 0 && (
          <span className="oc-toolgroup__hint">已完成</span>
        )}
        <span className="oc-toolgroup__spacer" />
        <DownOutlined className="oc-toolgroup__chevron" />
      </button>
      {open && (
        <div className="oc-toolgroup__body">
          {items.map((t) => (
            <ToolCard key={t.id} it={t} />
          ))}
        </div>
      )}
    </div>
  )
}
