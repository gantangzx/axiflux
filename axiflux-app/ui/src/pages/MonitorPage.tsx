import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Card, Col, Row, Progress, Table, Tag } from 'antd'
import { api } from '../api'
import { fmtBytes, fmtUptime, Loading, mono, OC } from '../ui'

type Sample = { name: string; labels: Record<string, string>; value: number }

function parseProm(text: string): Sample[] {
  const out: Sample[] = []
  for (const raw of String(text || '').split('\n')) {
    const ln = raw.trim()
    if (!ln || ln.startsWith('#')) continue
    const m = ln.match(/^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{([^}]*)\})?\s+(-?[0-9.eE+]+)/)
    if (!m) continue
    const labels: Record<string, string> = {}
    if (m[2])
      m[2].split(',').forEach((kv) => {
        const i = kv.indexOf('=')
        if (i < 0) return
        labels[kv.slice(0, i).trim()] = kv
          .slice(i + 1)
          .trim()
          .replace(/^"(.*)"$/, '$1')
          .replace(/\\"/g, '"')
      })
    out.push({ name: m[1], labels, value: parseFloat(m[3]) })
  }
  return out
}
const pSum = (s: Sample[], name: string, f?: (l: Record<string, string>) => boolean) =>
  s.reduce((a, x) => (x.name === name && (!f || f(x.labels)) ? a + x.value : a), 0)
const pOne = (s: Sample[], name: string) => {
  const x = s.find((y) => y.name === name)
  return x ? x.value : null
}

export default function MonitorPage() {
  const [health, setHealth] = useState<any>(null)
  const [samples, setSamples] = useState<Sample[]>([])
  const [updated, setUpdated] = useState('')
  const timer = useRef<ReturnType<typeof setInterval> | undefined>(undefined)

  useEffect(() => {
    const load = async () => {
      try {
        setHealth(await api.get('/actuator/health'))
      } catch {
        setHealth({ status: 'DOWN' })
      }
      try {
        setSamples(parseProm(await api.getText('/actuator/prometheus')))
      } catch {
        /* keep last */
      }
      setUpdated(new Date().toLocaleTimeString('zh-CN'))
    }
    load()
    timer.current = setInterval(load, 5000)
    return () => clearInterval(timer.current)
  }, [])

  if (!health && samples.length === 0) return <Loading />
  const up = health?.status === 'UP'

  const heapUsed = pSum(samples, 'jvm_memory_used_bytes', (l) => l.area === 'heap')
  const heapMax = pSum(samples, 'jvm_memory_max_bytes', (l) => l.area === 'heap' && +l.value > 0)
  const nonHeap = pSum(samples, 'jvm_memory_used_bytes', (l) => l.area === 'nonheap')
  const procCpu = pOne(samples, 'process_cpu_usage')
  const sysCpu = pOne(samples, 'system_cpu_usage')
  const threads = pOne(samples, 'jvm_threads_live_threads')
  const daemon = pOne(samples, 'jvm_threads_daemon_threads')
  const classes = pOne(samples, 'jvm_classes_loaded_classes')
  const uptime = pOne(samples, 'process_uptime_seconds')
  const gcCount = pSum(samples, 'jvm_gc_pause_seconds_count')
  const gcTime = pSum(samples, 'jvm_gc_pause_seconds_sum')
  const diskRow = samples.find((x) => x.name === 'disk_free_bytes')
  const diskFree = diskRow ? diskRow.value : null
  const diskTot = diskRow
    ? pSum(samples, 'disk_total_bytes', (l) => l.path === diskRow.labels.path)
    : null
  const heapPct = heapMax > 0 ? (heapUsed / heapMax) * 100 : 0

  const http = new Map<string, any>()
  for (const s of samples) {
    if (!/^http_server_requests_seconds_(count|sum|max)$/.test(s.name)) continue
    const uri = s.labels.uri || '?'
    if (uri === 'UNKNOWN' || uri.startsWith('/actuator')) continue
    const key = uri + '\0' + (s.labels.method || '')
    const e = http.get(key) || { uri, method: s.labels.method || '—', count: 0, sum: 0, max: 0 }
    if (s.name.endsWith('_count')) e.count += s.value
    else if (s.name.endsWith('_sum')) e.sum += s.value
    else e.max = Math.max(e.max, s.value)
    http.set(key, e)
  }
  const rows = [...http.values()].filter((r) => r.count > 0).sort((a, b) => b.count - a.count).slice(0, 14)

  const statCard = (title: ReactNode, value: ReactNode, sub?: ReactNode, bar?: number, barColor?: string) => (
    <Card variant="borderless" style={{ background: OC.card }}>
      <div style={{ color: OC.muted, fontSize: 13, marginBottom: 6 }}>{title}</div>
      <div style={{ fontSize: 26, fontWeight: 700, color: OC.textStrong }}>{value}</div>
      {sub && <div style={{ color: OC.muted, fontSize: 12, marginTop: 4 }}>{sub}</div>}
      {bar != null && (
        <Progress
          percent={Math.max(0, Math.min(100, bar))}
          size="small"
          showInfo={false}
          strokeColor={barColor}
          style={{ marginTop: 8, marginBottom: 0 }}
        />
      )}
    </Card>
  )

  // ── DB / Redis / Executor metrics ──
  const dbActive = pSum(samples, 'hikaricp_connections_active')
  const dbIdle = pSum(samples, 'hikaricp_connections_idle')
  const dbMax = pSum(samples, 'hikaricp_connections_max')
  const dbPending = pSum(samples, 'hikaricp_connections_pending')
  const dbTimeout = pSum(samples, 'hikaricp_connections_timeout_total')
  // 利用率看活跃连接占最大连接数，不是总连接占最大（空闲连接不算压力）
  const dbPct = dbMax > 0 ? (dbActive / dbMax) * 100 : 0
  const dbUsageMs = pSum(samples, 'hikaricp_connections_usage_seconds_sum') * 1000
  const dbUsageCount = pSum(samples, 'hikaricp_connections_usage_seconds_count')
  const dbAvgUseMs = dbUsageCount > 0 ? dbUsageMs / dbUsageCount : 0

  const redisOps = new Map<string, { count: number; sum: number; max: number }>()
  for (const s of samples) {
    if (!s.name.startsWith('lettuce_seconds_')) continue
    const op = s.labels.db_operation || '?'
    const e = redisOps.get(op) || { count: 0, sum: 0, max: 0 }
    if (s.name.endsWith('_count')) e.count += s.value
    else if (s.name.endsWith('_sum')) e.sum += s.value
    else if (s.name.endsWith('_max')) e.max = Math.max(e.max, s.value)
    redisOps.set(op, e)
  }
  const redisTotalOps = [...redisOps.values()].reduce((a, v) => a + v.count, 0)
  const redisAvgMs = redisTotalOps > 0
    ? [...redisOps.values()].reduce((a, v) => a + v.sum, 0) / redisTotalOps * 1000
    : 0
  const redisErrors = samples.filter((s) => s.name === 'lettuce_seconds_count' && s.labels.error && s.labels.error !== 'none')
    .reduce((a, s) => a + s.value, 0)

  // 线程池按 name label 分组（可能有多个线程池）
  const execPools = new Map<string, { active: number; pool: number; max: number; queued: number; completed: number }>()
  for (const s of samples) {
    if (!s.name.startsWith('executor_')) continue
    const pool = s.labels.name || 'default'
    const e = execPools.get(pool) || { active: 0, pool: 0, max: 0, queued: 0, completed: 0 }
    if (s.name === 'executor_active_threads') e.active += s.value
    else if (s.name === 'executor_pool_size_threads') e.pool += s.value
    else if (s.name === 'executor_pool_max_threads') e.max += s.value
    else if (s.name === 'executor_queued_tasks') e.queued += s.value
    else if (s.name === 'executor_completed_tasks_total') e.completed += s.value
    execPools.set(pool, e)
  }
  // 汇总所有线程池（max 用 Integer.MAX_VALUE 时取实际 pool size 作为参考）
  const execActive = [...execPools.values()].reduce((a, v) => a + v.active, 0)
  const execPoolSize = [...execPools.values()].reduce((a, v) => a + v.pool, 0)
  const execQueued = [...execPools.values()].reduce((a, v) => a + v.queued, 0)
  const execCompleted = [...execPools.values()].reduce((a, v) => a + v.completed, 0)
  // max 超过 1M 视为无界，不显示分母
  const execMaxRaw = [...execPools.values()].reduce((a, v) => a + v.max, 0)
  const execMaxDisplay = execMaxRaw > 1_000_000 ? '∞' : String(execMaxRaw)
  const execPct = execMaxRaw > 0 && execMaxRaw <= 1_000_000 ? (execPoolSize / execMaxRaw) * 100 : 0

  return (
    <div style={{ padding: 24, maxWidth: 1200, margin: '0 auto' }}>
      <Row gutter={[16, 16]}>
        <Col xs={12} md={8}>
          {statCard(
            <span>
              <span style={{ color: up ? '#00ac49' : '#dc3146' }}>●</span> 服务状态
            </span>,
            <span style={{ color: up ? '#00ac49' : '#dc3146', fontSize: 22 }}>{up ? '运行中' : '不可用'}</span>,
            <>已运行 {fmtUptime(uptime)}</>,
          )}
        </Col>
        <Col xs={12} md={8}>
          {statCard(
            '☕ 堆内存',
            heapMax > 0 ? heapPct.toFixed(1) + '%' : '—',
            <>
              {fmtBytes(heapUsed)} / {fmtBytes(heapMax)} · 非堆 {fmtBytes(nonHeap)}
            </>,
            heapPct,
            heapPct > 85 ? '#dc3146' : heapPct > 70 ? '#da7e00' : OC.accent,
          )}
        </Col>
        <Col xs={12} md={8}>
          {statCard(
            '⚡ CPU（进程）',
            procCpu == null ? '—' : (procCpu * 100).toFixed(1) + '%',
            <>系统 {sysCpu == null ? '—' : (sysCpu * 100).toFixed(1) + '%'}</>,
            (procCpu || 0) * 100,
            OC.accent,
          )}
        </Col>
        <Col xs={12} md={8}>
          {statCard(
            '🧵 线程',
            threads == null ? '—' : Math.round(threads),
            <>守护 {daemon == null ? '—' : Math.round(daemon)} · 类 {classes == null ? '—' : Math.round(classes).toLocaleString()}</>,
          )}
        </Col>
        <Col xs={12} md={8}>
          {statCard('♻️ GC 暂停', Math.round(gcCount) + ' 次', <>累计 {(gcTime * 1000).toFixed(0)} ms</>)}
        </Col>
        <Col xs={12} md={8}>
          {statCard(
            '💽 磁盘空闲' + (diskRow?.labels?.path ? `（${diskRow.labels.path}）` : ''),
            fmtBytes(diskFree),
            diskTot ? (
              <>
                总量 {fmtBytes(diskTot)} · 已用 {((1 - diskFree! / diskTot) * 100).toFixed(1)}%
              </>
            ) : null,
            diskTot ? (1 - diskFree! / diskTot) * 100 : undefined,
            diskTot && diskFree! / diskTot < 0.1 ? '#dc3146' : '#00ac49',
          )}
        </Col>
      </Row>

      {/* ── 数据库 / Redis / 线程池 ── */}
      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={12} md={8}>
          {statCard(
            '🗄️ 数据库连接池',
            dbMax > 0 ? `${dbActive}/${dbMax}` : '—',
            <>
              活跃 {dbActive} · 空闲 {dbIdle} · 等待 {dbPending} · 超时 {dbTimeout} · 平均使用 {dbAvgUseMs.toFixed(1)}ms
            </>,
            dbPct,
            dbPct > 85 ? '#dc3146' : dbPct > 70 ? '#da7e00' : '#00ac49',
          )}
        </Col>
        <Col xs={12} md={8}>
          {statCard(
            '⚡ Redis',
            redisTotalOps > 0 ? redisTotalOps + ' 次' : '—',
            <>
              平均 {redisAvgMs.toFixed(1)}ms · 错误 {redisErrors} · 操作类型 {redisOps.size}
            </>,
          )}
        </Col>
        <Col xs={12} md={8}>
          {statCard(
            '🧶 异步线程池',
            `${execPoolSize}/${execMaxDisplay}`,
            <>
              活跃 {execActive} · 队列 {execQueued} · 完成 {execCompleted.toLocaleString()}
            </>,
            execPct > 0 ? execPct : undefined,
            execPct > 85 ? '#dc3146' : execPct > 70 ? '#da7e00' : OC.accent,
          )}
        </Col>
      </Row>

      {/* Redis 操作明细 */}
      {redisOps.size > 0 && (
        <Card
          title="Redis 操作明细"
          variant="borderless"
          style={{ background: OC.card, marginTop: 16 }}
          styles={{ header: { borderBottom: `1px solid ${OC.border}`, color: OC.textStrong } }}
        >
          <Table
            size="small"
            rowKey={(r) => r.op}
            pagination={false}
            dataSource={[...redisOps.entries()].map(([op, v]) => ({
              op,
              count: v.count,
              avgMs: v.count > 0 ? (v.sum / v.count) * 1000 : 0,
              maxMs: v.max * 1000,
            })).sort((a, b) => b.count - a.count)}
            columns={[
              { title: '操作', dataIndex: 'op', render: (v) => <Tag color="geekblue">{v}</Tag> },
              { title: '次数', dataIndex: 'count', render: (v) => Math.round(v) },
              { title: '平均耗时', dataIndex: 'avgMs', render: (v) => v.toFixed(2) + ' ms' },
              { title: '最大耗时', dataIndex: 'maxMs', render: (v) => v.toFixed(1) + ' ms' },
            ]}
          />
        </Card>
      )}

      <Card
        title="HTTP 请求统计"
        variant="borderless"
        style={{ background: OC.card, marginTop: 16 }}
        styles={{ header: { borderBottom: `1px solid ${OC.border}`, color: OC.textStrong } }}
        extra={<span style={{ color: OC.muted, fontSize: 12 }}>更新于 {updated} · 5s 自动刷新</span>}
      >
        <Table
          size="small"
          rowKey={(r) => r.uri + r.method}
          pagination={false}
          dataSource={rows}
          locale={{ emptyText: '暂无 HTTP 请求指标' }}
          columns={[
            { title: '路径', dataIndex: 'uri', render: (v) => <span style={mono}>{v}</span> },
            { title: '方法', dataIndex: 'method', render: (v) => <Tag color="blue">{v}</Tag> },
            { title: '请求数', dataIndex: 'count', render: (v) => Math.round(v) },
            { title: '平均耗时', render: (_, r) => (r.count ? (r.sum / r.count * 1000).toFixed(1) + ' ms' : '—') },
            { title: '最大耗时', render: (_, r) => (r.max * 1000).toFixed(1) + ' ms' },
          ]}
        />
      </Card>
    </div>
  )
}
