import { Marked, type Tokens } from 'marked'
import DOMPurify from 'dompurify'

/**
 * 单换行 → <br> 的 tokenizer 扩展（GFM 语义，段落内的 "\n" 视为硬换行）。
 * 用扩展而非全局 breaks:true：后者会把表格/代码块内合法换行也吃掉，破坏 GFM 表格。
 * 优先级 9999：跑在内建 inline tokenizer 之后，先认领 "\n" 让它落不到 text 里。
 */
const softBreakExt = {
  name: 'softBreak',
  level: 'inline' as const,
  start(src: string) {
    return src.indexOf('\n')
  },
  tokenizer(src: string) {
    const m = /^\n/.exec(src)
    if (m) return { type: 'softBreak', raw: m[0], tokens: [] }
    return undefined
  },
  renderer() {
    return '<br>\n'
  },
}

const esc = (s: string) =>
  s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

/** 实例级配置（避免改全局 marked 影响其他调用方）。 */
function makeMarked(): Marked {
  const m = new Marked({ gfm: true, breaks: false })
  m.use({ extensions: [softBreakExt] })
  const renderer = {
    link(this: any, { href, title, tokens }: Tokens.Link) {
      const text = this.parser.parseInline(tokens)
      const t = title ? ` title="${esc(title)}"` : ''
      // target 属性也经 DOMPurify ADD_ATTR 白名单放行
      return `<a href="${esc(href ?? '')}"${t} target="_blank" rel="noopener noreferrer">${text}</a>`
    },
    code(this: any, { text, lang, escaped }: Tokens.Code) {
      const body = escaped ? text : esc(text)
      const cls = lang ? ` class="lang-${esc(lang)}"` : ''
      return `<pre class="md-pre"><code${cls}>${body}</code></pre>`
    },
  }
  m.use({ renderer })
  return m
}

/** 完成消息的解析器。 */
const mdFinal = makeMarked()
/** 流式解析器：独立实例，避免扩展/状态交叉污染（虽然当前扩展是无状态的，隔离更稳）。 */
const mdStream = makeMarked()

/** 数字实体，&#x58; 与 &#88; 两种进制都覆盖。 */
const NUMERIC_ENTITY = /&#x[\da-f]{1,6};|&#[0-9]{1,7};/gi
const SPLIT_ENCODE: [RegExp, string][] = [
  [/&lt;/g, '<'],
  [/&gt;/g, '>'],
  [/&quot;/g, '"'],
  [/&amp;/g, '&'],
]

/**
 * marked 对多行 fenced code 的 lex 输出里 "\n" 是 "&newline;"实体，直接再喂回去会被
 * 当普通文本，行间多出空行（每个实体自成段落）。这里只解码数字/命名实体，
 * 不解 &lt;/&gt;/&amp; —— 后者是源码字符的转义，解码会破坏 round-trip 幂等。
 */
function decodeLexed(s: string): string {
  return s.replace(NUMERIC_ENTITY, (m) => {
    const code = m.toLowerCase().startsWith('&#x')
      ? parseInt(m.slice(3, -1), 16)
      : parseInt(m.slice(2, -1), 10)
    return Number.isFinite(code) && code >= 0 && code <= 0x10ffff
      ? String.fromCodePoint(code)
      : m
  })
}

/** marked 把 list item 里的 "\n" 编码成 &newline; 实体，双重解析前先还原成换行。 */
function decodeNewlines(s: string): string {
  return s.replace(/&newline;/g, '\n')
}

/**
 * fenced code 行内拆开成「围栏 + 首行代码」后再喂给 marked，会被当成同一个
 * info string（语言标记）而吃掉首行。补一个换行把内容顶下去。
 */
function splitFenceFirstLine(seg: string): string {
  const m = /^(```[^\n`]|\s*~~~[^\n~])/.exec(seg)
  if (!m) return seg
  const fence = m[0].trimStart()
  const rest = seg.slice(m[0].length)
  if (!rest || rest.startsWith('\n')) return seg // 已是 "围栏\n内容"，无需处理
  return fence + '\n' + rest
}

/**
 * 把流式文本拆成「已完成（可安全 parse 且结果稳定）」与「尾部待续」两段。
 * 规则（沿 ANSI 终端的保守策略）：
 *  - 未闭合的 fenced code block：整块及其内容都属于尾部；
 *  - 尾部最后一个 "\n\n" 之前的段落已完成；双换行本身是边界标记，也并入已完成段。
 */
function splitComplete(src: string): { done: string; tail: string } {
  const re = /(```|~~~)/g
  let m: RegExpExecArray | null
  let count = 0
  let third: RegExpExecArray | null = null
  while ((m = re.exec(src))) {
    count++
    if (count === 3) {
      third = m
      break
    }
  }
  let upto = src.length
  if (count >= 2) {
    const secondFence = (third ?? re.exec(src))!
    upto = secondFence.index
  }
  const cut = src.lastIndexOf('\n\n', upto - 1)
  return { done: src.slice(0, cut + 2), tail: src.slice(cut + 2) }
}

/**
 * 尾部：把每个「单行段落 / 标题 / 列表行」单独 parse 再拼回，天然吃掉
 * marked 在段落外包裹的 <p>…</p>，避免流式重渲染时闪跳。
 */
function renderTailFragment(tail: string): string {
  if (!tail) return ''
  const lines = tail.split('\n')
  return lines
    .map((line) => {
      if (!line.trim()) return ''
      try {
        const html = mdStream.parse(line, { async: false }) as string
        return html.replace(/^<p>/, '').replace(/<\/p>\s*$/, '')
      } catch {
        return esc(line)
      }
    })
    .join('<br>\n')
}

/** 流式期间：逐 token 重渲染，done 段一次性 parse，tail 段逐行 parse。 */
function renderStreaming(src: string): string {
  const { done, tail } = splitComplete(src)
  let doneHtml = ''
  if (done.trim()) {
    try {
      doneHtml = mdStream.parse(decodeNewlines(decodeLexed(done)), { async: false }) as string
    } catch {
      doneHtml = esc(done).replace(/\n/g, '<br>')
    }
  }
  const tailHtml = renderTailFragment(splitFenceFirstLine(decodeNewlines(decodeLexed(tail))))
  return doneHtml + tailHtml
}

/**
 * Render markdown to sanitized HTML（GFM + 段落内单换行 → <br>）。
 * @param streaming 流式渲染时用 true：把文本拆成 done/tail 两段分别 parse，
 *                  避免未闭合的 ``` 代码块每次重 parse 都闪成裸文本。
 */
export function renderMarkdown(src: string, streaming = false): string {
  if (!src) return ''
  try {
    const html = streaming
      ? renderStreaming(src)
      : (mdFinal.parse(src, { async: false }) as string)
    return DOMPurify.sanitize(html, {
      USE_PROFILES: { html: true },
      ADD_ATTR: ['target', 'rel'],
    })
  } catch {
    return esc(src).replace(/\n/g, '<br>')
  }
}
