import { useCallback, useRef, useState } from 'react'
import { message } from 'antd'
import type { Node } from '@xyflow/react'
import type { WDef } from '../workflow-types'
import { getUserId } from '../api'
import { postSse } from '../sse-util'

export type NodeStatus = 'idle' | 'running' | 'ok' | 'error'
export type NodeData = { label: string; nodeType: string; status?: NodeStatus; [k: string]: unknown }

function authToken(): string {
  try {
    return localStorage.getItem('oc.auth.token') ?? ''
  } catch {
    return ''
  }
}

/**
 * Bundles the designer's action layer: test-run status tracking, YAML import /
 * export and version-history loading / restore. Kept separate from the canvas so
 * the view component stays small.
 */
export function useWorkflowActions(
  setNodes: React.Dispatch<React.SetStateAction<Node<NodeData>[]>>,
) {
  const [running, setRunning] = useState(false)
  const abortRef = useRef<AbortController | null>(null)
  const statusMapRef = useRef<Map<string, NodeStatus>>(new Map())

  const setNodeStatus = useCallback(
    (id: string, status: NodeStatus, colors: Record<NodeStatus, { border: string; glow?: string }>) => {
      statusMapRef.current.set(id, status)
      setNodes((ns) =>
        ns.map((n) => {
          if (n.id !== id) return n
          const st = colors[status]
          return {
            ...n,
            data: { ...n.data, status },
            style: { ...n.style, border: `1px solid ${st.border}`, boxShadow: st.glow },
          }
        }),
      )
    },
    [setNodes],
  )

  const clearStatuses = useCallback(
    (colors: Record<NodeStatus, { border: string; glow?: string }>) => {
      statusMapRef.current.clear()
      const idle = colors.idle
      setNodes((ns) =>
        ns.map((n) => ({
          ...n,
          data: { ...n.data, status: 'idle' as NodeStatus },
          style: { ...n.style, border: `1px solid ${idle.border}`, boxShadow: idle.glow },
        })),
      )
    },
    [setNodes],
  )

  const runTest = useCallback(
    async (def: WDef, nodeId: string | undefined, colors: Record<NodeStatus, { border: string; glow?: string }>) => {
      if (running) return
      clearStatuses(colors)
      setRunning(true)
      const ctrl = new AbortController()
      abortRef.current = ctrl
      let failed = false
      try {
        await postSse(
          '/api/v1/admin/workflows/test/stream',
          { definition: def, input: '', nodeId, userId: getUserId(), maxSteps: 50 },
          ({ event, data }) => {
            const ev = data as { nodeId?: string; message?: string }
            if (event === 'node_start' && ev.nodeId) setNodeStatus(ev.nodeId, 'running', colors)
            else if (event === 'node_end' && ev.nodeId) setNodeStatus(ev.nodeId, 'ok', colors)
            else if (event === 'error') {
              if (ev.nodeId) {
                failed = true
                setNodeStatus(ev.nodeId, 'error', colors)
              } else message.error(ev.message || '运行失败')
            } else if (event === 'paused') message.warning(ev.message || '试运行在暂停节点终止')
            else if (event === 'completed') message.success('试运行完成')
          },
          ctrl.signal,
        )
        if (failed) message.error('节点执行失败，已在画布标红')
      } catch (e) {
        message.error('试运行请求失败：' + String((e as Error).message))
      } finally {
        setRunning(false)
        abortRef.current = null
      }
    },
    [running, clearStatuses, setNodeStatus],
  )

  const stopRun = useCallback(() => {
    abortRef.current?.abort()
    setRunning(false)
  }, [])

  // ----- YAML -----

  const yamlExport = useCallback(async (def: WDef): Promise<string> => {
    const res = await fetch('/api/v1/admin/workflows/yaml/export', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${authToken()}` },
      body: JSON.stringify({ definition: def }),
    })
    const body = await res.json()
    const text = body?.data?.yaml ?? body?.yaml
    if (typeof text !== 'string') throw new Error('返回中缺少 yaml')
    return text
  }, [])

  const yamlImport = useCallback(async (text: string): Promise<WDef> => {
    const res = await fetch('/api/v1/admin/workflows/yaml/import', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${authToken()}` },
      body: JSON.stringify({ yaml: text, save: false }),
    })
    const body = await res.json()
    const def = body?.data?.definition ?? body?.definition
    if (!def) throw new Error(body?.error || '返回中缺少 definition')
    return def as WDef
  }, [])

  // ----- Versions -----

  const listVersions = useCallback(async (name: string) => {
    const res = await fetch(`/api/v1/admin/workflows/${encodeURIComponent(name)}/versions`, {
      headers: { Authorization: `Bearer ${authToken()}` },
    })
    const body = await res.json()
    return (body?.data ?? []) as Array<Record<string, unknown>>
  }, [])

  const restoreVersion = useCallback(async (name: string, v: number): Promise<WDef> => {
    const res = await fetch(
      `/api/v1/admin/workflows/${encodeURIComponent(name)}/versions/${v}/restore`,
      { method: 'POST', headers: { Authorization: `Bearer ${authToken()}` } },
    )
    if (!res.ok) throw new Error(String(res.status))
    const body = await res.json()
    const def = body?.data?.definition ?? body?.definition
    if (!def) throw new Error('返回中缺少 definition')
    return def as WDef
  }, [])

  return {
    running,
    runTest,
    stopRun,
    yamlExport,
    yamlImport,
    listVersions,
    restoreVersion,
  }
}
