# 技能生态与注册表技术方案路线图

> ⚠️ **历史快照（2026-09-06），非当前计划。** 技能子系统此后持续演进，部分规划已落地。
> 当前规划请以 [`commercial-roadmap-2026-09-22.md`](commercial-roadmap-2026-09-22.md) 为准。
>
> 版本：v1.0 ｜ 日期：2026-09-06 ｜ 作者：Coder + 架构元
> 范围：tianshu-java 技能子系统（本地技能 → 在线安装 → 腾讯技能注册表）
> 关联代码：`SkillInstallService`、`SkillController`、`WorkflowSkillExecutor`、`SkillDirectoryWatcher`、`MarkdownSkillLoader`、`skills/` 目录

---

## 1. 背景与目标

tianshu-java 的技能子系统已具备「本地目录加载 + 热加载 + 在线安装（Git/ZIP/本地目录）」能力，但技能仍是"装了就散在文件夹里"的状态：没有启用/禁用、没有版本与更新追踪、没有统一的分发市场。

本路线图目标：

1. **可管理**：每个安装的技能有台账（来源、版本、启用状态），支持禁用/启用/更新/卸载。
2. **可分发**：建腾讯技能注册表（Registry），支持发布、搜索、版本化下载。
3. **可发现**：控制台内置技能市场，一键安装、更新提醒。
4. **可信任**（P2）：发布签名 + 客户端校验，防篡改。

非目标：技能内容不入库、不做远程直接执行（架构决策见 §3）。

---

## 2. 现状盘点（Phase 0，已交付 ✅）

| 能力 | 实现 | 状态 |
|---|---|---|
| SKILL.md 加载 | `MarkdownSkillLoader`（snake_case/camelCase 双兼容），递归扫描 rootDir | ✅ |
| 执行模式 | sequential / parallel / llm_guided（策略链 + 拦截器链） | ✅ |
| 热加载 | `SkillDirectoryWatcher`（WatchService 递归、500ms 防抖、SHA-256 签名对账） | ✅ |
| Agent 感知 | `appendSkillCatalog` 注入系统提示 + `load_skill` 工具（list/read） | ✅ |
| 在线安装 | `SkillInstallService`：JGit 浅克隆 / ZIP 下载解压 / 本地目录拷贝；depth-3 扫描支持 bundle 仓库；`.skill-origin.json` 溯源；装后 reload 对账 | ✅ commit 721acd4 |
| REST | `POST /api/v1/skills/install`、`DELETE /api/v1/skills/{name}`、`POST /reload`、`GET /`、`GET /{name}`、`POST /{name}/execute`、`POST /match` | ✅ |
| UI | 技能页：安装 Modal（source+force）、卸载 Popconfirm、热加载按钮 | ✅ |

**已知遗留**：

- 安装的技能无 DB 台账，重启后来源/版本信息只在 `.skill-origin.json` 文件里。
- 无启用/禁用开关（只能卸载）。
- 无 update 链路（force 重装是唯一升级方式）。
- anthropics 等外部技能默认 `execution_mode=sequential`，「执行」按钮自动跑未必适配，LLM `load_skill` 读正文正常。

---

## 3. 总体架构

### 3.1 三层模型

```
┌────────────────────────────────────────────────────────────┐
│  腾讯技能注册表（独立服务，P1）                                │
│  catalog/版本/搜索/下载/签名  ← 发布者上传 zip + 元数据         │
└──────────────▲─────────────────────────────┬───────────────┘
               │ HTTPS（搜索/下载）            │ 发布（管理端）
┌──────────────┴─────────────────────────────▼───────────────┐
│  tianshu-java 客户端                                        │
│  ┌──────────────┐   ┌───────────────────────────────────┐   │
│  │ skill_install│   │ 技能市场 UI（搜索/安装/更新提示）    │   │
│  │ 表（本地台账）│◄──┤ SkillController /install /market  │   │
│  └──────┬───────┘   └──────────────┬────────────────────┘   │
│         │ enabled/版本过滤          │ 物化                    │
│  ┌──────▼──────────────────────────▼────────────────────┐   │
│  │ 文件系统 skills/<name>/（运行时唯一真相）               │   │
│  │ SKILL.md + scripts/ + .skill-origin.json              │   │
│  │ Watcher 热加载 / loader 扫描后查台账决定注册与否         │   │
│  └───────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────┘
```

### 3.2 架构决策记录（ADR）

**ADR-1：技能内容存文件系统，不存数据库。**

- 技能是目录树（SKILL.md + scripts/ + reference 文档），脚本需被 `code_executor` 当真实文件执行；入库后运行时仍需落盘，多一层序列化且丢相对路径。
- 分发单位是 git 仓库：克隆、ref 切版本、watcher 热加载、force 覆盖全部天然围绕 FS。
- 调试闭环：直接改 `skills/xxx/SKILL.md` 0.5s 生效。
- 多实例无共享卷场景：DB/对象存储做**分发源**，安装时物化到本地 FS——运行时真相仍是 FS。

**ADR-2：DB 只存台账与注册表元数据。**

- 本地 `skill_install`：来源、ref/版本、启用状态、安装/更新时间——给"禁用/更新/可见范围"提供落脚点。
- 远程 `skill_catalog / skill_version`：市场浏览搜索、版本管理、下载计数。

**ADR-3：enabled 状态在 DB，loader 扫到后查台账决定是否注册。** 文件不动、禁用可恢复、重装不丢状态。

**ADR-4：注册表为独立服务，不嵌进 tianshu-java 单体。** 发布审核、签名密钥、对象存储与 agent 运行时安全域不同；客户端只依赖注册表的 HTTP API，可降级（注册表不可达时本地技能不受影响）。

---

## 4. 数据模型设计

### 4.1 本地台账：Flyway V10（tianshu-storage）

```sql
-- V10: skill install ledger
-- 台账：每个落到 skills/ 目录的技能一行。运行时内容仍在文件系统，
-- 本表只记录来源、版本、启用状态，供禁用/更新/市场页使用。
CREATE TABLE IF NOT EXISTS skill_install (
    id            BIGSERIAL    PRIMARY KEY,
    name          VARCHAR(128) NOT NULL,                 -- 技能目录名/frontmatter name
    source        VARCHAR(512) NOT NULL,                 -- git:owner/repo | https://zip | local:path | registry:slug
    source_ref    VARCHAR(128),                          -- git ref / registry version
    version       VARCHAR(64),                           -- SKILL.md frontmatter version
    origin        JSONB,                                 -- .skill-origin.json 全量留档
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,    -- 禁用后 loader 不注册
    scope         VARCHAR(16)  NOT NULL DEFAULT 'global',-- global | agent（预留）
    agent_id      VARCHAR(64),                           -- scope=agent 时绑定
    installed_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    UNIQUE (name)
);

COMMENT ON TABLE skill_install IS 'Installed skill ledger; content lives in skills/<name>/, this row tracks source/version/enabled state.';
```

要点：

- JPA 实体 `SkillInstallEntity` + repo（仿 `AgentDefinition` 模式）。
- 安装成功后 upsert（name 唯一键）；卸载删行；`.skill-origin.json` 仍写（离线可读、与表双轨，以表为准）。
- `WorkflowSkillExecutor.reloadSkills()` 对账时：文件扫描结果 ∪ 台账，**enabled=FALSE 的不 register**；台账有记录但目录已没的行保留还是清理？→ 清理（目录是真相），记 removed。

### 4.2 注册表服务端（独立服务，P1）

```sql
-- 注册表库（可与 tianshu 同实例不同 schema，或独立库）
CREATE TABLE skill_catalog (
    slug          VARCHAR(128) PRIMARY KEY,              -- 唯一标识，如 pdf、qclaw-reminder
    name          VARCHAR(128) NOT NULL,
    description   TEXT,
    author        VARCHAR(128),
    tags          VARCHAR(256),                          -- 逗号分隔或 JSONB
    latest_version VARCHAR(32),
    status        VARCHAR(16) NOT NULL DEFAULT 'published', -- draft|published|deprecated
    downloads     BIGINT      NOT NULL DEFAULT 0,
    created_at    TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE TABLE skill_version (
    id            BIGSERIAL    PRIMARY KEY,
    slug          VARCHAR(128) NOT NULL REFERENCES skill_catalog(slug),
    version       VARCHAR(32)  NOT NULL,
    storage_key   VARCHAR(512) NOT NULL,                 -- 对象存储/本地盘 zip 路径
    sha256        VARCHAR(64)  NOT NULL,                 -- 包体摘要
    signature     TEXT,                                  -- P2：发布者签名
    changelog     TEXT,
    min_client    VARCHAR(32),                           -- 兼容的最低 tianshu 版本
    published_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    UNIQUE (slug, version)
);

CREATE INDEX idx_skill_version_slug ON skill_version(slug, published_at DESC);
```

包体存储：MVP 用注册表服务本地盘目录（`registry-data/blobs/<sha256>.zip`）；后续可换 S3/COS，storage_key 抽象不变。

---

## 5. API 设计

### 5.1 tianshu-java 客户端新增/改造

| 方法 | 路径 | 说明 | 阶段 |
|---|---|---|---|
| GET | `/api/v1/skills` | 返回合并台账：enabled、source、version、hasUpdate | P0 改造 |
| PATCH | `/api/v1/skills/{name}` | body `{enabled: bool}` 开关 | P0 |
| POST | `/api/v1/skills/{name}/update` | 按台账 source 重新拉取覆盖（复用 install force 链路） | P0 |
| POST | `/api/v1/skills/update-all` | 所有 tracked 技能批量更新，返回逐条结果 | P2 |
| GET | `/api/v1/skills/market?q=&page=` | 代理注册表搜索（配置 registry-url 后可用） | P1 |
| POST | `/api/v1/skills/install` | source 新增 `registry:<slug>[@version]` 形态 | P1 |

配置项（`TianshuProperties.Skills`）：

```yaml
tianshu:
  skills:
    root-dir: ./skills
    hot-reload: true
    registry-url: https://registry.example.com   # P1，空则市场页隐藏
    registry-token: ${SKILL_REGISTRY_TOKEN:}     # P1，发布/私有源用
```

`SkillInstallService` 扩展：`parseGitSpec` 旁加 `parseRegistrySource` → HTTP 下载 zip（复用现有 ZIP 解压链路），sha256 校验（P2 加签名校验）。

### 5.2 注册表服务端（新服务，Spring WebFlux 或独立轻量服务）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/registry/publish` | multipart：zip 包 + 元数据（slug/version/changelog/tags）；服务端算 sha256、解包校验含 SKILL.md、upsert catalog+version |
| GET | `/api/registry/skills?q=&tag=&page=&size=` | 搜索/列表（published only） |
| GET | `/api/registry/skills/{slug}` | 详情 + 版本列表 |
| GET | `/api/registry/skills/{slug}/latest` | 最新版本元数据（客户端更新检查用） |
| GET | `/api/registry/download/{slug}/{version}` | 302 重定向或直接流返回 zip；downloads+1 |
| POST | `/api/registry/skills/{slug}/deprecate` | 管理端下架 |

发布校验规则：zip 内必须含 ≥1 个 SKILL.md（复用 depth-3 扫描约定）；frontmatter name 与 slug 不符时拒绝（P1 先警告）；单包 ≤ 10MB。

---

## 6. 前端规划

**技能页改造（P0）**：

- 列表加列：来源（git/zip/local/registry 徽标）、版本、开关（Switch，接 PATCH）、更新按钮（hasUpdate 时红点）。
- 卸载保留；禁用态行置灰。

**市场页（P1，新页面「技能市场」）**：

- 卡片网格：名称、作者、描述、tags、下载量、安装/已安装/可更新按钮。
- 搜索框 + tag 过滤；数据源 `/api/v1/skills/market`（注册表不可达时空态提示"未配置 registry-url"）。
- 安装走现有 install Modal 的同一套 toast/刷新逻辑。

侧边栏归入「智能体」组（技能入口已有，市场页与其并列）。

---

## 7. 分阶段路线图

### Sprint A（P0）：本地技能台账 —— 2~3 人日

> **状态：2026-09-07 核心完成（commit 5c1f663）**。落地时未新建 `skill_install` 表，
> 改为复用 V1 已有的孤儿表 `skills`（SkillEntity/SkillRepository），V10 migration 给它加
> `source`/`checksum` 两列（enabled/created_at/updated_at 本就有）。
> ✅ A1 台账表+实体；✅ A2 安装/卸载写台账（SkillLedgerService 全量对账，安装/卸载/启动/reload 四个触发点）；
> ✅ A3 enabled 过滤（WorkflowSkillExecutor disabledNamesSupplier 门控，禁用技能不注册，load_skill/目录不可见）；
> ✅ A4 `PATCH /skills/{name}` 开关 + `POST /skills/{name}/update`（commit c67658f）；✅ A5 前端来源 Tag/Switch/更新按钮；
> ✅ A6 部分（SkillLedgerServiceTest 8 例）。
> 关键设计：对账以**磁盘目录**为存在性权威、**registry** 为启用状态权威（禁用技能磁盘在但 registry 无，台账行保留 enabled=false）；
> GET /skills 融合台账全集（禁用项置灰列出）。已验证：禁用 pptx → registry 20、列表 21；启用恢复 21；内置技能 update 返回 400。
> **Sprint A 全部完成（2026-09-07，commit 5c1f663 + c67658f）**。

> 目标：技能"可管理"，不依赖任何远程服务。

| # | 任务 | 模块 |
|---|---|---|
| A1 | V10 migration `skill_install` + `SkillInstallEntity`/repo | tianshu-storage |
| A2 | `SkillInstallService` 安装成功 upsert 台账、卸载删行 | tianshu-spring |
| A3 | reloadSkills 对账接入 enabled 过滤（台账 disabled 不注册） | tianshu-core/spring |
| A4 | `GET /skills` 合并台账字段；`PATCH /skills/{name}` 开关；`POST /skills/{name}/update` | tianshu-spring |
| A5 | 前端：来源/版本列、Switch、更新按钮 | ui |
| A6 | 测试：安装/禁用/启用/更新/卸载闭环；台账与目录不一致对账 | test |

验收：禁用某技能后 agent 目录与 `load_skill list` 均不可见；改 SKILL.md 版本号后 update 能拉新；卸载后台账行删除。

### Sprint B（P1）：注册表服务 MVP —— 3~4 人日

> 目标：技能能发布、能被搜索、能下载。

| # | 任务 |
|---|---|
| B1 | 独立服务骨架（可放 tianshu-registry 新模块或独立仓库，Spring WebFlux + PG） |
| B2 | `skill_catalog/skill_version` 建表 + publish 接口（zip 上传、sha256、SKILL.md 校验、blob 落盘） |
| B3 | 列表/搜索/详情/最新版本接口（分页、q 走 ILIKE 或全文索引） |
| B4 | download 接口 + 下载计数 |
| B5 | 管理端最简发布方式：curl/脚本 + token 认证（先不做发布 UI） |
| B6 | 部署上线（域名/端口、备份 blobs 目录与 DB） |

验收：curl 发布一个技能包 → 列表能搜到 → download 拿到 zip 且 sha256 一致。

> ✅ **B1–B5 完成（2026-09-07，commit da93d73）**：tianshu-registry 独立模块（WebFlux+JPA+Flyway+PG，端口 8090，库 tianshu_registry）；
> POST /api/registry/publish（Bearer token、zip 校验、SKILL.md frontmatter 解析、内容寻址 blob、幂等/409）；
> GET /skills（分页+ILIKE 搜索）、/skills/{slug}、/skills/{slug}/latest；download 双端点（版本下载/latest 下载）+ 计数 + X-Content-Sha256；
> start-registry.bat（detach jar，setenv-registry.bat 注入 token，均 gitignored）。E2E 全链路 curl 验收通过（含 401/400/404 负例）。
> ⬜ B6 线上部署：配置项全部环境变量化（REGISTRY_PORT/DB_URL/DB_USER/DB_PASSWORD/BLOB_DIR/PUBLISH_TOKEN），待域名/服务器/备份策略确认。
> 踩坑记录：Boot 4 无 Flyway 自动装配（需 AutoConfiguration.imports + BFPP depends-on）；char(64)→varchar(64)；WebFlux 裸 HttpHeaders 参数不解析须 @RequestHeader；version 行 FK 要求 catalog 先落库。

### Sprint C（P1）：客户端对接 + 市场页 —— 2 人日

> 目标：控制台里一键逛市场、装技能。

| # | 任务 |
|---|---|
| C1 | `registry-url/registry-token` 配置 + `registry:slug[@ver]` source 解析下载安装 |
| C2 | `/skills/market` 代理搜索（含已装/可更新标记合并） |
| C3 | 市场页 UI（卡片、搜索、安装、更新提醒） |
| C4 | 安装后台账 source 写 `registry:slug`、version 落库 |
| C5 | E2E：发布 → 市场安装 → 禁用 → 更新 全链路 |

验收：市场页装一个注册表技能 ≤ 3 次点击；注册表停服时本地技能与已装技能零影响。

> ✅ **C1–C5 完成（2026-09-07，commit 329ba82）**：
> - 安装来源新增 `registry:slug[@version]`（未钉版本先查 /latest 解析版本再下载）；`.skill-origin.json` 写 version
> - `GET /api/v1/skills/market` 代理搜索（与台账合并 installed/installedVersion/updateAvailable，按 `registry:` source slug 匹配）；注册表不可达 503 降级
> - 配置 `tianshu.skills.registry-url`（默认 http://localhost:8090）/ `registry-token`；V11 迁移 skills.version VARCHAR(32)
> - 前端 SkillsPage 加「已安装 / 技能市场」Segmented：搜索、卡片、一键安装/更新、橙色可更新标记、503 降级提示
> - 修复：MarkdownSkillLoader 不识别带 UTF-8 BOM 的 frontmatter（PowerShell/Win 编辑器 zip 常见），name/version 全回退默认值
> - E2E 全链路通过：安装 1.0.1 → 禁用隐藏 → 发布 1.2.0 → 市场识别更新（禁用态也能）→ 更新写入 1.2.0 → 重启用；注册表停服 /market 503、本地 22 技能 200 零影响

### Sprint D（P2）：信任与运营 —— 2 人日

| # | 任务 |
|---|---|
| D1 | 发布签名（Ed25519 私钥签名 sha256，公钥内置/配置）+ 客户端 verify |
| D2 | `update-all` 批量更新 + 前端"全部更新" |
| D3 | 下载量/安装量统计在市场页展示 |
| D4 | 技能下架（deprecated）后客户端更新检查提示 |
| D5 | `.skill-origin.json` 与台账自动对齐修复命令（`/skills/reconcile`） |

> ✅ **D1–D5 完成（2026-09-07，commit b754d51）**：
> - **D1** 注册表 `SigningService`：Ed25519 密钥对首次启动自动生成（私钥 `registry-keys/ed25519.key` PKCS#8 + `.pub` X509，只读，已 gitignore），发布时对 sha256 签名存 `skill_version.signature`；`GET /api/registry/public-key` 公钥钉扎、download 带 `X-Signature`。客户端配置 `tianshu.skills.registry-public-key` 后 fail-closed 验签（无签名/验签失败拒绝），未配置向后兼容；下载字节先校验 sha256 再解压
> - **D2** `POST /api/v1/skills/update-all` 批量重装在线来源（registry/git/zip），内置/本地/钉版本跳过，deprecated/克隆失败归 skipped；前端「全部更新」按钮 + 二次确认
> - **D3** `POST /api/registry/skills/{slug}/install` 安装计数（客户端装成功 fire-and-forget 回调），市场卡片「安装 N」标签
> - **D4** V2 迁移 `skill_catalog.status` + `total_installs`、`skill_version.signature`；`PATCH /.../status`（token）发布新版本不复活下架 slug；市场透传 deprecated、强制 updateAvailable=false，客户端安装/更新拦截 + 红色「已下架」
> - **D5** `POST /api/v1/skills/reconcile` 重扫目录对齐台账
> - E2E 全过：签名版安装成功（signature verified 日志）、无签名钉版本被拒；下架后更新 400；update-all 1.3.0→1.3.1（20 个 git 技能网络不通归 skipped，failed=0）；reconcile 22 行；totalInstalls=1

### Sprint E（联邦）：对接三方互联网技能市场 —— 已完成 2026-09-07

> 目标：任何实现 tianshu-registry HTTP 协议的第三方市场改配置即接入。
>
> - **多注册表配置**：`tianshu.skills.registries[]`（name/url/token/public-key），空时回落旧 `registry-url` 单源（合成源名 `default`）；重名启动报错
> - **SkillSource 抽象**：`RegistrySource` 封装单源 HTTP（search/resolve/download/notifyInstall/publicKey），`RegistrySources.from(props)` 工厂；`SkillMarketService` 并行 fan-out（daemon cached pool，25s 超时），单源失败只降级该源、返回 sources 状态列表，全挂才 503
> - **安装语法**：`registry:<source>/<slug>[@version]`（默认源可省略 source）；`.skill-origin.json` 命名空间化；updateAll/更新按来源由路由回正确源
> - **按源信任**：Ed25519 公钥逐源钉扎（不再是全局一个 key），某源无 key 不验签（市场标未签名）、有 key 则 fail-closed；安装计数回调也路由到对应源
> - **跨源同名冲突**：安装时比对目标目录 `.skill-origin.json` 的来源，不同注册表源互覆一律拒绝（force 也拒），提示先卸载；市场本地状态匹配也按源隔离（杜绝 hub2/local 同名 slug 误显已安装）
> - **前端**：市场页来源徽标（默认源/源名 Tag）、来源筛选 Select、不可用源警告条、底部「N/M 个来源在线」
>
> E2E（双注册实例 8090/8091，各自独立 Ed25519 密钥与 DB）：fan-out 3 条结果双源在线；hub2 安装验签成功、origin 命名空间化；跨源覆盖被拒；未知源报已配置列表；停 hub2 后市场 200 降级（local 正常、hub2 标不可用）；update-all 对不可用源 skipped 不崩
>
> **后续**：GitCatalogProvider（GitHub 目录索引让 Git 生态进市场页）、异构市场适配器、B6 部署上线

---

## 8. 建议排期

| 周 | 日期（2026） | Sprint | 工时 |
|---|---|---|---|
| W37 | 9/7（一）– 9/13（六） | A 本地台账 | 2~3 人日，建议 3 个晚上 |
| W38 | 9/14 – 9/20 | B 注册表服务 | 3~4 人日，含部署 |
| W39 | 9/21 – 9/27 | C 客户端+市场页 | 2 人日 |
| W40-W41 | 9/28 – 10/11 | D 信任与运营（国庆周弹性） | 2 人日 |

合计 **9~11 人日**，与"完整版注册表 5~7 人日 + 本地台账 2~3 人日 + 市场页 2 人日"的估算一致。

里程碑：**9/13 技能可管理** → **9/20 注册表可用** → **9/27 市场页闭环** → **10/11 全量收尾**。

---

## 9. 风险与兼容性

1. **外部技能工具名不兼容**：ClawHub/anthropics 技能按 TS 版工具名写（bash/scheduler）。缓解：安装后扫描 `required_tools`，对不上已知映射的给警告横幅；长期维护一份工具名别名映射（bash→code_executor、scheduler→schedule_task 等，安装时自动改写 frontmatter，可开关）。
2. **注册表单点**：市场功能强依赖注册表在线。缓解：所有远程调用超时降级（3s），失败只影响市场页，本地技能链路无注册表依赖。
3. **包体安全**：市场技能含可执行脚本，本质是"第三方代码"。缓解：P0 先私有源（token）；D1 签名后再开放注册；安装页明示来源与作者；执行仍走现有工具审批策略链（DESTRUCTIVE/ASK 闸门不绕过）。
4. **多技能同名**：不同来源同名技能冲突。缓解：name 唯一键 + force 覆盖（现状），市场安装遇 registry 名与本地冲突时弹窗让用户选。
5. **大仓克隆慢**：git 源已是 depth=1 浅克隆；注册表走 zip 包体限制 10MB。
6. **V10 与现有数据**：skill_install 新表无数据迁移负担；已有 reminder/anthropics 技能在 A2 上线后首次 reload 时补登台账（source 未知标 `unknown`，可正常禁用/卸载）。

---

## 10. 未决问题（开发前确认）

1. 注册表服务形态：tianshu-java 新模块（`tianshu-registry`，同仓部署）还是独立仓库独立进程？倾向独立模块同仓起步，降低运维成本。
2. 注册表部署位置：复用现有 yy.wdnmd.wang 服务器还是新环境？对象存储用本地盘还是腾讯云 COS？
3. 发布权限：MVP 仅管理员 token，还是开放注册用户发布？
4. 技能是否支持 agent 级隔离（scope=agent）？表已预留，UI 与 loader 过滤逻辑本期可不做。
