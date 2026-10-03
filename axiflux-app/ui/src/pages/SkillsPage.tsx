import { useEffect, useState } from 'react'
import { Button, Space, Modal, Tag, Input, App as AntApp, Popconfirm, Checkbox, Switch, Tooltip, Segmented, Select } from 'antd'
import {
  ReloadOutlined,
  CloudDownloadOutlined,
  DeleteOutlined,
  ThunderboltOutlined,
  SyncOutlined,
  ShopOutlined,
  SearchOutlined,
  CheckCircleOutlined,
  AppstoreOutlined,
  DownloadOutlined,
  StopOutlined,
  CloudSyncOutlined,
  SafetyCertificateOutlined,
  AuditOutlined,
  WarningOutlined,
} from '@ant-design/icons'
import { api, getUserId } from '../api'
import { useApi, Loading, ErrBox, toList, mono, pillStyle, OC, SkeletonRows, EmptyHint } from '../ui'
import { PlanLockTag } from '../billing'

// Trust badge for a market source.
// signed  = registry with a pinned Ed25519 key — every package is signature-verified on install
// scanned = ClawHub — no package signature; trust comes from the platform's server-side security scan
// unsigned= registry reachable without a pinned key — signatures are not verified
function TrustTag({ trust }: { trust?: string }) {
  if (trust === 'signed') {
    return (
      <Tooltip title="已配置签名公钥：安装时会对技能包做 Ed25519 签名校验，包体被篡改会被拒绝">
        <Tag color="success" style={{ marginInlineEnd: 0 }} icon={<SafetyCertificateOutlined />}>签名验证</Tag>
      </Tooltip>
    )
  }
  if (trust === 'scanned') {
    return (
      <Tooltip title="该来源不提供包签名，安全信任来自 ClawHub 平台侧的安全扫描/审核；安装请自行评估风险">
        <Tag color="blue" style={{ marginInlineEnd: 0 }} icon={<AuditOutlined />}>平台扫描</Tag>
      </Tooltip>
    )
  }
  return (
    <Tooltip title="未为该来源配置签名公钥，安装时不校验包签名，请确认来源可信">
      <Tag color="orange" style={{ marginInlineEnd: 0 }} icon={<WarningOutlined />}>未签名</Tag>
    </Tooltip>
  )
}

export default function SkillsPage() {
  const { message } = AntApp.useApp()
  const { data, loading, error, reload } = useApi(() => api.get<any[]>('/api/v1/skills'), [])
  const [detail, setDetail] = useState<any | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [run, setRun] = useState<any | null>(null)
  const [input, setInput] = useState('')
  const [out, setOut] = useState<string | null>(null)
  const [running, setRunning] = useState(false)
  const [reloading, setReloading] = useState(false)
  const [updatingAll, setUpdatingAll] = useState(false)
  const [installOpen, setInstallOpen] = useState(false)
  const [installSrc, setInstallSrc] = useState('')
  const [installForce, setInstallForce] = useState(false)
  const [installing, setInstalling] = useState(false)
  const [toggling, setToggling] = useState<string | null>(null)
  const [updating, setUpdating] = useState<string | null>(null)
  const [tab, setTab] = useState<'installed' | 'market'>('installed')

  const sourceMeta = (s: any): { label: string; color?: string; updatable: boolean } => {
    const src = (s.source as string) || ''
    if (!src || src === 'builtin') return { label: '内置', updatable: false }
    if (src.startsWith('registry:')) return { label: '注册表', color: 'green', updatable: true }
    if (src.startsWith('git:')) return { label: 'Git', color: 'blue', updatable: true }
    if (src.startsWith('local:')) return { label: '本地', updatable: false }
    if (/^https?:/i.test(src)) return { label: 'ZIP/URL', color: 'geekblue', updatable: true }
    return { label: src.slice(0, 10), updatable: false }
  }

  const toggleEnabled = async (s: any, enabled: boolean) => {
    setToggling(s.name)
    try {
      await api.patch(`/api/v1/skills/${encodeURIComponent(s.name)}`, { enabled })
      message.success(enabled ? `已启用 ${s.name}` : `已禁用 ${s.name}（对话与技能目录均不可见）`)
      reload()
    } catch (e: any) {
      message.error('切换失败：' + (e?.message || e))
    } finally {
      setToggling(null)
    }
  }

  const updateSkill = async (s: any) => {
    setUpdating(s.name)
    try {
      const r: any = await api.post(`/api/v1/skills/${encodeURIComponent(s.name)}/update`, {})
      const names = (r?.updated || []).map((x: any) => x.name).join('、')
      message.success(names ? `已更新：${names}` : '已是最新 / 更新完成')
      reload()
    } catch (e: any) {
      message.error('更新失败：' + (e?.message || e))
    } finally {
      setUpdating(null)
    }
  }

  const doInstall = async (srcArg?: string) => {
    const src = (srcArg ?? installSrc).trim()
    if (!src) return
    setInstalling(true)
    try {
      const r: any = await api.post('/api/v1/skills/install', { source: src, force: installForce })
      const names = (r?.installed || []).map((x: any) => x.name).join('、')
      message.success(names ? `安装成功：${names}（共 ${r?.total ?? 0} 个技能）` : '安装完成')
      setInstallOpen(false)
      setInstallSrc('')
      setInstallForce(false)
      reload()
    } catch (e: any) {
      message.error('安装失败：' + (e?.message || e))
      throw e
    } finally {
      setInstalling(false)
    }
  }

  const uninstall = async (name: string) => {
    try {
      await api.del(`/api/v1/skills/${encodeURIComponent(name)}`)
      message.success(`已卸载 ${name}`)
      reload()
    } catch (e: any) {
      message.error('卸载失败：' + (e?.message || e))
    }
  }

  const reloadSkills = async () => {
    setReloading(true)
    try {
      const r: any = await api.post('/api/v1/skills/reload', {})
      const added = (r?.added || []).length
      const updated = (r?.updated || []).length
      const removed = (r?.removed || []).length
      if (added + updated + removed === 0) {
        message.info('技能目录无变化')
      } else {
        message.success(`热加载完成：新增 ${added} · 更新 ${updated} · 移除 ${removed} · 共 ${r?.total ?? 0} 个`)
      }
      reload()
    } catch (e: any) {
      message.error('热加载失败：' + (e?.message || e))
    } finally {
      setReloading(false)
    }
  }

  const updateAllSkills = async () => {
    setUpdatingAll(true)
    try {
      const r: any = await api.post('/api/v1/skills/update-all', {})
      const updated = r?.updated ?? 0
      const failed = r?.failed ?? 0
      const skipped = r?.skipped ?? 0
      if (failed > 0) {
        message.warning(`更新完成：${updated} 个成功 · ${failed} 个失败 · ${skipped} 个跳过`)
      } else if (updated > 0) {
        message.success(`已更新 ${updated} 个技能（跳过 ${skipped} 个）`)
      } else {
        message.info(`没有可更新的技能（跳过 ${skipped} 个）`)
      }
      reload()
    } catch (e: any) {
      message.error('全部更新失败：' + (e?.message || e))
    } finally {
      setUpdatingAll(false)
    }
  }

  const openDetail = async (s: any) => {
    setDetail(s)
    setDetailLoading(true)
    try {
      const full: any = await api.get(`/api/v1/skills/${encodeURIComponent(s.name)}`)
      const f = (full && (full.skill || full)) || full
      setDetail(f)
    } catch (e: any) {
      message.error(e.message)
    } finally {
      setDetailLoading(false)
    }
  }

  const runSkill = async () => {
    setRunning(true)
    setOut(null)
    try {
      const r: any = await api.post(`/api/v1/skills/${encodeURIComponent(run.name)}/execute`, {
        input: input.trim(),
        sessionId: 'http',
        userId: getUserId(),
      })
      const o = r && (r.output ?? r.result ?? r.lastOutput) != null ? r.output ?? r.result ?? r.lastOutput : r
      setOut(typeof o === 'string' ? o : JSON.stringify(o, null, 2))
    } catch (e: any) {
      setOut('⚠️ ' + e.message)
    } finally {
      setRunning(false)
    }
  }

  return (
    <div style={{ padding: 24, maxWidth: 960, margin: '0 auto' }}>
      <div className="oc-panel">
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            padding: '13px 16px',
            borderBottom: `1px solid ${OC.border}`,
            gap: 12,
          }}
        >
          <Space size={12} align="center">
            <Segmented
              value={tab}
              onChange={(v) => setTab(v as 'installed' | 'market')}
              options={[
                { label: '已安装', value: 'installed' },
                { label: '技能市场', value: 'market', icon: <ShopOutlined /> },
              ]}
            />
            <span style={{ color: OC.muted, fontWeight: 500, fontSize: 12.5 }}>
              {tab === 'installed'
                ? loading
                  ? '加载中…'
                  : `${data?.length || 0} 个已加载`
                : '来自技能注册表'}
            </span>
          </Space>
          <Space size={8}>
            {tab === 'installed' && (
              <Button size="small" icon={<ReloadOutlined />} loading={reloading} onClick={reloadSkills}>
                热加载
              </Button>
            )}
            {tab === 'installed' && (
              <Popconfirm
                title="全部更新"
                description="将重新安装所有在线来源（Git / 注册表 / ZIP）且未钉版本的技能。内置、本地、钉版本技能会跳过。继续？"
                okText="全部更新"
                cancelText="取消"
                onConfirm={updateAllSkills}
              >
                <Button size="small" icon={<CloudSyncOutlined />} loading={updatingAll}>
                  全部更新
                </Button>
                <PlanLockTag feature="skill.updateAll" fallback="team" label="TEAM 功能" />
              </Popconfirm>
            )}
            <Button
              size="small"
              type="primary"
              icon={<CloudDownloadOutlined />}
              onClick={() => setInstallOpen(true)}
            >
              安装技能
            </Button>
          </Space>
        </div>

        {tab === 'installed' ? (
          <>
            {error && <div style={{ padding: 16 }}><ErrBox msg={error} /></div>}
            {loading ? (
              <div style={{ padding: '8px 0' }}>
                <SkeletonRows rows={5} />
              </div>
            ) : !data || data.length === 0 ? (
              <EmptyHint
                icon={<CloudDownloadOutlined />}
                title="还没有安装技能"
                desc="去「技能市场」一键安装，或从 Git 仓库、ZIP 链接、本地目录安装 SKILL.md 技能包。"
                action={
                  <Space>
                    <Button type="primary" icon={<ShopOutlined />} onClick={() => setTab('market')}>
                      逛市场
                    </Button>
                    <Button icon={<CloudDownloadOutlined />} onClick={() => setInstallOpen(true)}>
                      自定义安装
                    </Button>
                  </Space>
                }
              />
            ) : (
              <div className="oc-list">
                {data.map((s) => {
                  const triggers = toList(s.triggers).slice(0, 2)
                  const guided = s.executionMode === 'llm_guided'
                  const enabled = s.enabled !== false
                  const src = sourceMeta(s)
                  return (
                    <div className="oc-row" key={s.name} style={enabled ? undefined : { opacity: 0.55 }}>
                      <div className="oc-row__icon">
                        <ThunderboltOutlined />
                      </div>
                      <div className="oc-row__main">
                        <div className="oc-row__name">
                          {s.name}
                          {s.version && <span style={pillStyle}>v{s.version}</span>}
                          {!enabled && <Tag color="default" style={{ marginInlineEnd: 0 }}>已禁用</Tag>}
                        </div>
                        <div className="oc-row__desc">{s.description || '（无描述）'}</div>
                        <div className="oc-row__tags">
                          <Tag color={src.color || 'default'} style={{ marginInlineEnd: 0 }}>{src.label}</Tag>
                          <PlanLockTag feature="skill.install" fallback="pro" label="PRO 技能" />
                          {s.executionMode && (
                            <Tag color={guided ? 'purple' : 'default'} style={{ marginInlineEnd: 0 }}>
                              {guided ? 'LLM 引导' : '顺序执行'}
                            </Tag>
                          )}
                          {triggers.map((t) => (
                            <span key={t} style={pillStyle}>{t}</span>
                          ))}
                        </div>
                      </div>
                      <div className="oc-row__actions">
                        <Tooltip title={enabled ? '禁用后从技能目录隐藏' : '启用'}>
                          <Switch
                            size="small"
                            checked={enabled}
                            loading={toggling === s.name}
                            onChange={(v) => toggleEnabled(s, v)}
                          />
                        </Tooltip>
                        <Tooltip title="从来源重新拉取最新版本">
                          <Button
                            size="small"
                            type="text"
                            icon={<SyncOutlined />}
                            loading={updating === s.name}
                            disabled={!src.updatable}
                            onClick={() => updateSkill(s)}
                          />
                        </Tooltip>
                        <Button size="small" type="text" onClick={() => openDetail(s)} disabled={!enabled}>
                          详情
                        </Button>
                        <Button
                          size="small"
                          type="primary"
                          ghost
                          disabled={!enabled}
                          onClick={() => {
                            setRun(s)
                            setInput('')
                            setOut(null)
                          }}
                        >
                          执行
                        </Button>
                        <Popconfirm
                          title={`卸载技能 ${s.name}？`}
                          description="将删除 skills 目录下该技能文件夹及台账记录"
                          okText="卸载"
                          cancelText="取消"
                          okButtonProps={{ danger: true }}
                          onConfirm={() => uninstall(s.name)}
                        >
                          <Button size="small" type="text" danger icon={<DeleteOutlined />} title="卸载" />
                        </Popconfirm>
                      </div>
                    </div>
                  )
                })}
              </div>
            )}
          </>
        ) : (
          <MarketPane
            installing={installing}
            onInstall={async (source: string) => {
              try {
                await doInstall(source)
                return true
              } catch {
                return false
              }
            }}
            onInstalledChange={() => reload()}
          />
        )}
      </div>

      <Modal
        title="安装技能"
        open={installOpen}
        onCancel={() => setInstallOpen(false)}
        onOk={() => doInstall()}
        confirmLoading={installing}
        okText="安装"
        cancelText="取消"
        width={620}
      >
        <Input.TextArea
          rows={3}
          value={installSrc}
          onChange={(e) => setInstallSrc(e.target.value)}
          placeholder={'支持以下来源：\n· 注册表：registry:slug 或 registry:slug@1.0.0（技能市场一键安装即用此格式）\n· Git：git:owner/repo 或 git:owner/repo@v1.0，或直接粘贴 GitHub/Gitee 仓库 URL\n· ZIP：.zip 下载链接（如 GitHub 仓库的 archive/codeload 链接）\n· 本地目录：服务器上含 SKILL.md 的目录绝对路径'}
        />
        <div style={{ marginTop: 10 }}>
          <Checkbox checked={installForce} onChange={(e) => setInstallForce(e.target.checked)}>
            覆盖同名技能（force）
          </Checkbox>
        </div>
        <div style={{ marginTop: 8, color: OC.muted, fontSize: 12 }}>
          安装后自动热加载；含多个 SKILL.md 的仓库会批量安装。
        </div>
      </Modal>

      <Modal title={detail ? `技能 ${detail.name || ''}` : '技能详情'} open={!!detail} onCancel={() => setDetail(null)} footer={null} width={720}>
        {detailLoading ? (
          <Loading />
        ) : (
          <>
            <Space wrap style={{ marginBottom: 12 }}>
              <Tag>版本 {detail?.version || '—'}</Tag>
              <Tag color="blue">{detail?.executionMode || '—'}</Tag>
              <Tag>步骤 {detail?.stepCount ?? (detail?.steps ? detail.steps.length : '—')}</Tag>
              {toList(detail?.requiredTools).map((t) => (
                <span key={t} style={pillStyle}>{t}</span>
              ))}
            </Space>
            <div style={{ color: OC.muted, fontSize: 12, marginBottom: 4 }}>SKILL.md 内容</div>
            <pre style={{ ...mono, background: OC.bg, border: `1px solid ${OC.border}`, borderRadius: 10, padding: 12, color: OC.text, whiteSpace: 'pre-wrap', wordBreak: 'break-word', maxHeight: '50vh', overflow: 'auto' }}>
              {detail?.readContent || detail?.content || '(不可读)'}
            </pre>
          </>
        )}
      </Modal>

      <Modal
        title={run ? `执行技能 ${run.name}` : ''}
        open={!!run}
        onCancel={() => setRun(null)}
        onOk={runSkill}
        confirmLoading={running}
        okText="执行"
        cancelText="取消"
        width={640}
      >
        <Input.TextArea
          rows={4}
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="要技能处理的内容/指令"
        />
        {out != null && (
          <div style={{ marginTop: 12 }}>
            <div style={{ color: OC.muted, fontSize: 12, marginBottom: 4 }}>执行结果</div>
            <pre style={{ ...mono, background: OC.bg, border: `1px solid ${OC.border}`, borderRadius: 10, padding: 12, color: out.startsWith('⚠️') ? '#ff9a9a' : OC.text, whiteSpace: 'pre-wrap', wordBreak: 'break-word', maxHeight: 300, overflow: 'auto' }}>
              {out}
            </pre>
          </div>
        )}
      </Modal>
    </div>
  )
}

// ===== 技能市场（注册表代理搜索 + 一键安装） =====

function MarketPane({
  installing,
  onInstall,
  onInstalledChange,
}: {
  installing: boolean
  onInstall: (source: string) => Promise<boolean>
  onInstalledChange: () => void
}) {
  const { message } = AntApp.useApp()
  const [q, setQ] = useState('')
  const [items, setItems] = useState<any[] | null>(null)
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busySlug, setBusySlug] = useState<string | null>(null)
  const [searched, setSearched] = useState(false)
  const [sources, setSources] = useState<any[]>([])
  const [sourceFilter, setSourceFilter] = useState<string>('all')

  const search = async (query: string) => {
    setLoading(true)
    setError(null)
    try {
      const r: any = await api.get(
        `/api/v1/skills/market?page=0&size=50${query.trim() ? `&q=${encodeURIComponent(query.trim())}` : ''}`,
      )
      setItems(r?.items || [])
      setTotal(r?.total || 0)
      setSources(r?.sources || [])
      setSearched(true)
    } catch (e: any) {
      setItems(null)
      setError(e?.message || String(e))
    } finally {
      setLoading(false)
    }
  }

  // 首次进入自动加载列表
  useEffect(() => {
    if (!searched && !loading && !error && items === null) {
      void search('')
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searched, loading, error, items])

  const install = async (it: any) => {
    setBusySlug(it.slug)
    const ok = await onInstall(it.installSource || `registry:${it.slug}`)
    setBusySlug(null)
    if (ok) {
      message.success(`${it.name} 安装完成`)
      onInstalledChange()
      void search(q)
    }
  }

  const update = async (it: any) => {
    setBusySlug(it.slug)
    try {
      const r: any = await api.post(`/api/v1/skills/${encodeURIComponent(it.installedName)}/update`, {})
      const names = (r?.updated || []).map((x: any) => x.name).join('、')
      message.success(names ? `已更新：${names}` : '已是最新')
      onInstalledChange()
      void search(q)
    } catch (e: any) {
      message.error('更新失败：' + (e?.message || e))
    } finally {
      setBusySlug(null)
    }
  }

  const visibleItems = (items || []).filter(
    (it) => sourceFilter === 'all' || it.source === sourceFilter,
  )
  const deadSources = sources.filter((s) => !s.available)

  return (
    <div style={{ padding: 16 }}>
      <Space size={8} wrap style={{ width: '100%' }}>
        <Space.Compact style={{ width: '100%', maxWidth: 460 }}>
          <Input
            allowClear
            prefix={<SearchOutlined style={{ color: OC.muted }} />}
            placeholder="搜索技能名称 / 作者 / 描述 / 标签"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            onPressEnter={() => search(q)}
          />
          <Button type="primary" loading={loading} onClick={() => search(q)}>
            搜索
          </Button>
        </Space.Compact>
        <Select
          value={sourceFilter}
          onChange={setSourceFilter}
          style={{ minWidth: 160 }}
          options={[
            { value: 'all', label: '全部来源' },
            ...sources.map((s) => ({
              value: s.name,
              label: `${s.name === 'default' ? '默认源' : s.name}${s.available ? '' : '（不可用）'}`,
              disabled: !s.available,
            })),
          ]}
        />
      </Space>

      <div style={{ marginTop: 14 }}>
        {deadSources.length > 0 && (
          <div style={{ marginBottom: 10 }}>
            <ErrBox
              msg={`部分技能来源不可用：${deadSources.map((s) => `${s.name}（${s.error || '连接失败'}）`).join('；')}。已仅显示可用来源的结果。`}
            />
          </div>
        )}
        {error ? (
          <ErrBox msg={`技能市场暂时不可用：${error}（本地已安装技能不受影响，可正常使用）`} />
        ) : loading && items === null ? (
          <SkeletonRows rows={4} />
        ) : !items || items.length === 0 ? (
          <EmptyHint
            icon={<ShopOutlined />}
            title={searched ? '没有匹配的技能' : '注册表暂无技能'}
            desc={searched ? '换个关键词试试，或稍后再来看看。' : '可以先用 POST /api/registry/publish 发布技能包。'}
          />
        ) : visibleItems.length === 0 ? (
          <EmptyHint icon={<ShopOutlined />} title="该来源下暂无技能" desc="换个来源筛选条件试试。" />
        ) : (
          <div className="oc-list">
            {visibleItems.map((it) => (
              <div className="oc-row" key={`${it.source}/${it.slug}`}>
                <div className="oc-row__icon">
                  <AppstoreOutlined />
                </div>
                <div className="oc-row__main">
                  <div className="oc-row__name">
                    {it.name}
                    <span style={pillStyle}>v{it.latestVersion || '?'}</span>
                    <Tag color={it.source === 'default' ? 'blue' : 'purple'} style={{ marginInlineEnd: 0 }}>
                      {it.source === 'default' ? '默认源' : it.source}
                    </Tag>
                    <TrustTag trust={it.trust} />
                    {it.deprecated && (
                      <Tag color="red" style={{ marginInlineEnd: 0 }} icon={<StopOutlined />}>已下架</Tag>
                    )}
                    {it.installed && (
                      <Tag color={it.updateAvailable ? 'orange' : 'success'} style={{ marginInlineEnd: 0 }}>
                        {it.updateAvailable
                          ? `可更新（已装 v${it.installedVersion}）`
                          : it.installedVersion
                            ? `已安装 v${it.installedVersion}`
                            : '已安装'}
                      </Tag>
                    )}
                    {it.installed && it.installedEnabled === false && (
                      <Tag color="default" style={{ marginInlineEnd: 0 }}>已禁用</Tag>
                    )}
                  </div>
                  <div className="oc-row__desc">
                    {it.deprecated
                      ? '该技能已被作者下架，不再提供安装或更新；已安装的本地副本可继续使用。'
                      : (it.description || '（无描述）')}
                    {!it.deprecated && it.author && <span style={{ color: OC.muted }}> · 作者 {it.author}</span>}
                  </div>
                  <div className="oc-row__tags">
                    <Tag color="green" style={{ marginInlineEnd: 0 }}>
                      <CloudDownloadOutlined /> 下载 {it.totalDownloads ?? 0}
                    </Tag>
                    {typeof it.totalInstalls === 'number' && it.totalInstalls > 0 && (
                      <Tag color="geekblue" style={{ marginInlineEnd: 0 }}>
                        <DownloadOutlined /> 安装 {it.totalInstalls}
                      </Tag>
                    )}
                    {toList(it.tags).slice(0, 4).map((t) => (
                      <span key={t} style={pillStyle}>{t}</span>
                    ))}
                  </div>
                </div>
                <div className="oc-row__actions">
                  {it.deprecated ? (
                    <Tooltip title="技能已下架，无法安装或更新">
                      <Button size="small" disabled icon={<StopOutlined />}>已下架</Button>
                    </Tooltip>
                  ) : it.installed ? (
                    it.updateAvailable ? (
                      <Button
                        size="small"
                        type="primary"
                        icon={<SyncOutlined />}
                        loading={busySlug === it.slug}
                        onClick={() => update(it)}
                      >
                        更新
                      </Button>
                    ) : (
                      <Tooltip title="已是最新版本">
                        <CheckCircleOutlined style={{ color: '#52c41a', fontSize: 17, marginInlineEnd: 8 }} />
                      </Tooltip>
                    )
                  ) : (
                    <Button
                      size="small"
                      type="primary"
                      ghost
                      icon={<CloudDownloadOutlined />}
                      loading={busySlug === it.slug || installing}
                      onClick={() => install(it)}
                    >
                      安装
                    </Button>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
        {!error && items && items.length > 0 && (
          <div style={{ color: OC.muted, fontSize: 12, marginTop: 10 }}>
            共 {total} 个技能 · {sources.filter((s) => s.available).length}/{sources.length} 个来源在线
          </div>
        )}
      </div>
    </div>
  )
}
