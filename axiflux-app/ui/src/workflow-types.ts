/** Structured workflow definition exchanged with the EE designer admin API. */
export type WorkflowDef = {
  nodes: WorkflowNode[]
  edges: WorkflowEdge[]
}

export type WorkflowRoute = { target: string; description?: string }
export type WorkflowApproval = { onApprove?: string; onReject?: string }

export type WorkflowNode = {
  id: string
  type: string
  label?: string
  query?: string
  systemPrompt?: string
  outputVar?: string
  tool?: string
  params?: unknown
  skill?: string
  routes?: WorkflowRoute[]
  branches?: unknown
  waitFor?: string
  approval?: unknown
  x?: number
  y?: number
}

export type WorkflowEdge = {
  source: string
  target: string
  condition?: string
}

// Short aliases used across the designer components.
export type WDef = WorkflowDef
export type WNode = WorkflowNode
