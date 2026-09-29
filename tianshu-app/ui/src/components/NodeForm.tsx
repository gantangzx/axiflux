import { Button, Divider, Form, Input, InputNumber, Select, Space, Switch, Tag } from 'antd'
import { DeleteOutlined } from '@ant-design/icons'
import type { WNode } from '../workflow-types'

const asText = (v: unknown): string => {
  if (v == null) return ''
  return typeof v === 'string' ? v : JSON.stringify(v)
}

/**
 * Property editor for the node selected on the designer canvas. Only fields
 * relevant to the node's type are shown. Edits flow up through onChange.
 */
export function NodeForm({
  node,
  onChange,
  onRemove,
}: {
  node: WNode
  onChange: (patch: Partial<WNode>) => void
  onRemove: () => void
}) {
  const t = node.type
  const showQuery = t === 'AGENT'
  const showTool = t === 'TOOL'
  const showOutput = t === 'AGENT' || t === 'TOOL'
  const showCondition = t === 'CONDITION'
  const showApproval = t === 'APPROVAL'
  const showWait = t === 'PAUSE'

  return (
    <Form layout="vertical" size="small">
      <Space style={{ justifyContent: 'space-between', width: '100%' }}>
        <Tag color="blue">{t}</Tag>
        <Button danger size="small" icon={<DeleteOutlined />} onClick={onRemove}>
          删除节点
        </Button>
      </Space>
      <Divider style={{ margin: '10px 0' }} />

      <Form.Item label="节点 ID">
        <Input value={node.id} disabled />
      </Form.Item>
      <Form.Item label="显示名称">
        <Input value={node.label} onChange={(e) => onChange({ label: e.target.value })} />
      </Form.Item>

      {showQuery && (
        <>
          <Form.Item label="指令 / Query">
            <Input.TextArea
              rows={3}
              value={node.query ?? ''}
              onChange={(e) => onChange({ query: e.target.value })}
              placeholder="支持 ${input} 变量"
            />
          </Form.Item>
          <Form.Item label="系统提示词">
            <Input.TextArea
              rows={2}
              value={node.systemPrompt ?? ''}
              onChange={(e) => onChange({ systemPrompt: e.target.value })}
            />
          </Form.Item>
        </>
      )}

      {showTool && (
        <>
          <Form.Item label="工具名称">
            <Input
              value={node.tool ?? ''}
              onChange={(e) => onChange({ tool: e.target.value })}
              placeholder="注册的工具名"
            />
          </Form.Item>
          <Form.Item label="工具参数 (JSON)">
            <Input.TextArea
              rows={3}
              value={asText(node.params)}
              onChange={(e) => onChange({ params: e.target.value })}
              placeholder='{"key":"value"}'
            />
          </Form.Item>
        </>
      )}

      {showCondition && (
        <Form.Item label="条件分支 (JSON)">
          <Input.TextArea
            rows={4}
            value={asText(node.branches)}
            onChange={(e) => onChange({ branches: e.target.value })}
            placeholder='[{"when":"${x} == 1","to":"node_a"},{"otherwise":true,"to":"node_b"}]'
          />
        </Form.Item>
      )}

      {showApproval && (
        <Form.Item label="审批配置 (JSON)">
          <Input.TextArea
            rows={3}
            value={asText(node.approval)}
            onChange={(e) => onChange({ approval: e.target.value })}
            placeholder='{"approvers":["u_1"],"timeoutSeconds":3600}'
          />
        </Form.Item>
      )}

      {showWait && (
        <Form.Item label="等待变量名">
          <Input value={node.waitFor ?? ''} onChange={(e) => onChange({ waitFor: e.target.value })} />
        </Form.Item>
      )}

      {showOutput && (
        <Form.Item label="输出变量名">
          <Input value={node.outputVar ?? ''} onChange={(e) => onChange({ outputVar: e.target.value })} />
        </Form.Item>
      )}
    </Form>
  )
}
