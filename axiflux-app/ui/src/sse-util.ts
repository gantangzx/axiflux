// Minimal Server-Sent-Events POST helper: open a stream, parse `event:` / `data:`
// frames and invoke onEvent. Returns when the stream ends; abort via `signal`.

export type SseFrame = { event: string; data: unknown }

export async function postSse(
  url: string,
  body: unknown,
  onEvent: (frame: SseFrame) => void,
  signal?: AbortSignal,
): Promise<void> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  try {
    const token = localStorage.getItem('oc.auth.token')
    if (token) headers.Authorization = `Bearer ${token}`
  } catch {
    /* ignore */
  }
  const res = await fetch(url, {
    method: 'POST',
    headers,
    body: JSON.stringify(body),
    signal,
  })
  if (!res.ok || !res.body) throw new Error(`SSE request failed: ${res.status}`)
  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buf = ''
  for (;;) {
    if (signal?.aborted) {
      try {
        await reader.cancel()
      } catch {
        /* already closed */
      }
      return
    }
    const { done, value } = await reader.read()
    if (done) break
    buf += decoder.decode(value, { stream: true })
    let idx
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const chunk = buf.slice(0, idx)
      buf = buf.slice(idx + 2)
      let event = 'message'
      const dataLines: string[] = []
      for (const rawLine of chunk.split('\n')) {
        const line = rawLine.replace(/\r$/, '')
        if (line.startsWith('event:')) event = line.slice(6).trim()
        else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
      }
      if (!dataLines.length) continue
      let data: unknown
      try {
        data = JSON.parse(dataLines.join('\n'))
      } catch {
        data = dataLines.join('\n')
      }
      onEvent({ event, data })
    }
  }
}
