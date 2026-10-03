const fs = require('fs')
const F = 'src/pages/ChatPage.tsx'
let s = fs.readFileSync(F, 'utf8')
const log = []

// ---- 1. 替换 import，加 UploadOutlined / Image / Upload ----
const oldImport = `import { Layout, Button, Input, Select, Spin, Typography } from 'antd'`
const newImport = `import { Layout, Button, Input, Select, Spin, Typography, Image, Upload } from 'antd'`
if (s.includes(oldImport)) { s = s.replace(oldImport, newImport); log.push('OK  antd import + Image/Upload') }
else log.push('!! antd import anchor not found')

const oldIcons = `import { ArrowUpOutlined, SafetyCertificateOutlined, PlusOutlined } from '@ant-design/icons'`
const newIcons = `import { ArrowUpOutlined, SafetyCertificateOutlined, PlusOutlined, PaperClipOutlined, CloseCircleOutlined } from '@ant-design/icons'`
if (s.includes(oldIcons)) { s = s.replace(oldIcons, newIcons); log.push('OK  icons + PaperClip/CloseCircle') }
else log.push('!! icons anchor not found')

// ---- 2. 在组件函数内加 attachments 状态 ----
// 找到 useRef 声明块，在其后插入 attachments state
const stateAnchor = `  const anchorRef = useRef<number | null>(null)`
const newState = `  const anchorRef = useRef<number | null>(null)
  // 附件（图片/文件）
  const [attachments, setAttachments] = useState<{ name: string; url: string; type: string }[]>([])
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [uploading, setUploading] = useState(false)`
if (s.includes(stateAnchor)) { s = s.replace(stateAnchor, newState); log.push('OK  attachments state added') }
else log.push('!! stateAnchor not found')

// ---- 3. send 时传 attachments ----
const oldSend = `      await streamChat(
        { sessionId: sid, userId: USER_ID, content: text, forcedModel: model },`
const newSend = `      const attachPayload = attachments.length > 0
        ? attachments.map(a => ({ type: a.type, url: a.url, name: a.name }))
        : undefined
      await streamChat(
        { sessionId: sid, userId: USER_ID, content: text, forcedModel: model, attachments: attachPayload },`
if (s.includes(oldSend)) { s = s.replace(oldSend, newSend); log.push('OK  send includes attachments') }
else log.push('!! send anchor not found')

// ---- 4. send 后清空 attachments ----
const oldClear = `    if (busySidsRef.current.has(sid)) return
    setInput('')`
const newClear = `    if (busySidsRef.current.has(sid)) return
    setInput('')
    setAttachments([])`
if (s.includes(oldClear)) { s = s.replace(oldClear, newClear); log.push('OK  send clears attachments') }
else log.push('!! clear anchor not found')

// ---- 5. composer 区域：加文件选择按钮和附件预览 ----
// 找到 model select 那块
const oldModelSelect = `          <div style={{ position: 'absolute', left: 14, bottom: 10 }}>
            <Select
              allowClear
              showSearch
              variant="borderless"
              placeholder="默认模型"
              size="small"
              style={{ width: 170, color: OC.muted }}
              value={chat.model}
              onChange={chat.setModel}
              options={Array.from(new Set(chat.models.map((m) => m.model))).map((m) => ({
                label: m,
                value: m,
              }))}
            />
          </div>`
const newModelSelect = `          <div style={{ position: 'absolute', left: 14, bottom: 10, display: 'flex', alignItems: 'center', gap: 4 }}>
            <button
              className="oc-composer-btn"
              type="button"
              title="上传图片或文件"
              onClick={() => fileInputRef.current?.click()}
              disabled={chat.busy}
            >
              <PaperClipOutlined />
            </button>
            <input
              ref={fileInputRef}
              type="file"
              multiple
              accept="image/*,.pdf,.txt,.csv,.json,.md"
              style={{ display: 'none' }}
              onChange={async (e) => {
                const files = Array.from(e.target.files || [])
                if (files.length === 0) return
                setUploading(true)
                const loaded: { name: string; url: string; type: string }[] = []
                for (const f of files) {
                  const type = f.type.startsWith('image/') ? 'image' : 'file'
                  const url = await new Promise<string>((resolve, reject) => {
                    const r = new FileReader()
                    r.onload = () => resolve(r.result as string)
                    r.onerror = reject
                    r.readAsDataURL(f)
                  })
                  loaded.push({ name: f.name, url, type })
                }
                setAttachments((prev) => [...prev, ...loaded])
                setUploading(false)
                // 重置 input 以便重复选同一文件
                e.target.value = ''
              }}
            />
            <Select
              allowClear
              showSearch
              variant="borderless"
              placeholder="默认模型"
              size="small"
              style={{ width: 170, color: OC.muted }}
              value={chat.model}
              onChange={chat.setModel}
              options={Array.from(new Set(chat.models.map((m) => m.model))).map((m) => ({
                label: m,
                value: m,
              }))}
            />
          </div>`
if (s.includes(oldModelSelect)) {
  s = s.replace(oldModelSelect, newModelSelect)
  log.push('OK  composer file input + upload button added')
} else log.push('!! model select anchor not found')

// ---- 6. 在 composer 上方加附件预览条 ----
const composerAnchor = `      {/* composer：输入框与发送按钮一体（按钮内嵌右下角） */}`
const attachPreview = `      {/* 附件预览条 */}
      {attachments.length > 0 && (
        <div style={{ padding: '0 24px', maxWidth: 768, margin: '0 auto' }}>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: 8 }}>
            {attachments.map((a, i) => (
              <div
                key={i}
                style={{
                  position: 'relative',
                  width: 64,
                  height: 64,
                  borderRadius: OC.radiusMd,
                  overflow: 'hidden',
                  border: `1px solid ${OC.border}`,
                  background: OC.card,
                  flex: '0 0 auto',
                }}
              >
                {a.type === 'image' ? (
                  <Image
                    src={a.url}
                    preview={false}
                    style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                    fallback="data:image/svg+xml;base64,PHN2ZyB3aWR0aD0iNjQiIGhlaWdodD0iNjQiIHZpZXdCb3g9IjAgMCA2NCA2NCIgZmlsbD0ibm9uZSIgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAvc3ZnIj48cmVjdCB3aWR0aD0iNjQiIGhlaWdodD0iNjQiIHJ4PSI4IiBmaWxsPSIjMjkyOTMyIi8+PHRleHQgeD0iMzIiIHk9IjM2IiB0ZXh0LWFuY2hvcj0ibWlkZGxlIiBmaWxsPSIjOGI4Yjk0IiBmb250LXNpemU9IjEwIiBmb250LWZhbWlseT0ibW9ub3NwYWNlIj7lm77niYc8L3RleHQ+PC9zdmc+"
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
                      fontSize: 20,
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
                    top: 2,
                    right: 2,
                    width: 18,
                    height: 18,
                    borderRadius: '50%',
                    border: 0,
                    background: 'rgba(0,0,0,0.7)',
                    color: '#fff',
                    cursor: 'pointer',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    fontSize: 11,
                    lineHeight: 1,
                    padding: 0,
                  }}
                  onClick={() => setAttachments((prev) => prev.filter((_, j) => j !== i))}
                >
                  ✕
                </button>
              </div>
            ))}
            {uploading && <Spin style={{ alignSelf: 'center' }} />}
          </div>
        </div>
      )}
`
if (s.includes(composerAnchor)) {
  s = s.replace(composerAnchor, attachPreview + '\n      ' + composerAnchor)
  log.push('OK  attachment preview bar added')
} else log.push('!! composer anchor not found')

fs.writeFileSync(F, s)
console.log(log.join('\n'))