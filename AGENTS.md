# AGENTS.md — tianshu-agent 项目上下文

> 本文件是接手本仓库的 AI agent / 新协作者的"长期记忆"。开工先读我。
> 最后更新：2026-09-23（环境收敛 local/dev/test/prod，详见 docs/environments.md；
> 架构评审 + 商业落地路线图见 docs/commercial-roadmap-2026-09-22.md）

## 一句话定位

Tianshu 的 Java 实现：**响应式 AI Agent 框架**，可嵌入他人应用的 Agent SDK（Spring Boot Starter 形态）。
**非目标**：聊天通道生态（Discord/WhatsApp 等）、heartbeat、网关 cron 自动化、ClawHub、dreaming、canvas/dashboard——这些不做，别往这方向加东西。

## 技术栈

- **Java 25**（本机 JDK：`D:\software\Java\jdk-25\jdk-25.0.2`）
- **Spring Boot 4.1.1** + WebFlux / Reactor——全反应式、非阻塞
- **LangChain4j 1.0.0**；JPA + PGVector（推荐）/ Qdrant / Milvus；ShedLock 分布式锁；Testcontainers
- **Maven 多模块**（本机 Maven：`D:\software\apache-maven-3.9.15\maven-3.9.15`）
- 前端控制台：**React + Ant Design + Vite + TS**（工程在 `tianshu-app/ui/`，版本以 ui/package.json 实测为准，构建产物输出到 static；详见下文"控制台 UI"节）

## 模块结构与分层纪律

```
tianshu-core/       核心引擎，零 Spring 依赖（可被非 Spring 宿主复用）
  com/gantang/tianshu/api/    接口契约（agent/llm/tool/policy/memory/scheduler/mcp/skill/session/auth/observability…）
  com/gantang/tianshu/impl/   实现（agent/tool/llm/router/memory/scheduler/mcp/skill/approval/embed/observability…）
                     skill 子包即 Skill 框架（Markdown SKILL.md 定义，Sequential/Parallel/LLM-Guided 三模式）
tianshu-storage/    JPA 实体（13 个）+ Repository + Flyway 迁移（V1–V20）
tianshu-registry/   技能注册中心（独立服务，端口 8090；详见 docs/registry.md）
tianshu-spring/     Spring Boot 装配 + 20 个 REST 控制器 + auth + providers + 计费
                     （含 LocalAccount/Billing/Usage/StripeWebhook/AdminUser 等商业化端点）
tianshu-eval/       Agent 场景评估 harness（replay/live 双模式，设计见
                     docs/eval-harness-design.md）：YAML 场景 + ReplayLlmClient/RecordingLlmClient
                     + FakeTool（ToolStub 支持 parameters JSON Schema）+ AgentHook 轨迹采集
                     + 声明式断言（finalContains 空白归一、toolCalled argsContains/fromTurn）
                     + 用户模拟器分支（live：branches ifContains/ifNotContains/say）
                     + LlmJudgeGrader（rubric 评分，看完整对话，不门禁）
                     + FreezeWriter（live 失败转 replay YAML，SecretMasker 脱敏，含分支轮次）
                     + EvalReport（md/JSON）+ CLI `com.gantang.tianshu.eval.cli.EvalMain`
                     （--mode live/replay --base-url --model --judge-model --report --freeze-dir；
                     replay 场景 src/test/resources/eval/ 自动发现进 mvn test 门禁，
                     live 场景 eval-live/ 手动/nightly 跑）。
                     live 冒烟用 OpenAiCompatLlmClient（JDK HttpClient，零 Spring，任意
                     OpenAI 兼容端点；ARK Plan /api/plan/v3 已证通）。
tianshu-app/      可部署应用：启动类 com.gantang.tianshu.app.TianshuAppApplication
                     + 前端工程 tianshu-app/ui（React+antd，vite build 输出到 static）
```

**纪律**：`tianshu-core` 不得依赖 Spring。反应式代码不得阻塞订阅线程；`.block()` 只能在 boundedElastic。

## 构建 / 测试 / 运行（本机 = WSL2 gateway，项目在 Windows D 盘，走 interop）

项目根：`/mnt/d/workspace/tianshu-agent`（Windows `D:\workspace\tianshu-agent`）。

### 环境四档（2026-09-23 收敛，权威手册 = docs/environments.md）

环境只有 **local / dev / test / prod** 四档，晋升路径 local→dev→test→prod：

| profile | 定位 | 鉴权 | 工具策略 | Stripe | webhook |
|---|---|---|---|---|---|
| `local`（默认） | 单机开发开箱即用 | 默认关 | all | 默认关 | — |
| `dev` | 共享联调、可排障 | 开 HS256+自签端点 | blacklist | 测试模式 | `stripe listen` CLI 隧道 |
| `test` | 类生产预发（与 prod 同构） | 开，测试 IdP/HS256 | whitelist | 测试模式 | Dashboard 固定端点 |
| `prod` | 生产 | 开，外部 IdP（非对称） | whitelist | 生产模式 | Dashboard 固定端点 |

- 配置文件：`tianshu-app/src/main/resources/application[-<profile>].yml`，基线 + profile 覆盖；
  密钥全部走环境变量，**不再有 application-staging.yml**。
- 统一启动：`scripts\start.bat [local|dev|test|prod] [-f]`（Linux `scripts/start.sh`）；
  dev 的 webhook 隧道 `scripts\stripe-listen.bat`。
- 密钥模板在 `env/env.<profile>.{bat,sh}.example`（提交），实际 `env/env.<profile>.*` 已 gitignore。
- 铁律：只有 test/prod 同构，别用 dev 验证生产加固；dev 用 `sk_test_`、prod 用 `sk_live_`。
- 历史明文密钥（Stripe test key、Redis/PG 口令）已从跟踪文件清除，建议轮换。

```bash
# 测试（WSL 里调 Windows 工具链）
/mnt/c/Windows/System32/cmd.exe /c "set JAVA_HOME=D:\software\Java\jdk-25\jdk-25.0.2&& D:\software\apache-maven-3.9.15\maven-3.9.15\bin\mvn.cmd -B test"
# 打包（跳过测试）
#   同上，把 test 换成 package -DskipTests
# 运行（统一脚本，Windows 侧 detached；等价 java -jar 见 docs/environments.md）
scripts\start.bat local          # 端口 8080；dev/test/prod 传对应 profile
```

- 密钥在 `env/env.<profile>.bat`（**gitignore**），模板见 `env/*.example`。
- **Registry 独立服务**：不走四档 profile，单一配置 + 环境变量；启动 `scripts\registry.bat`，
  密钥模板 `env/env.registry.bat.example`，权威手册 `docs/registry.md`。根目录不再放任何启动脚本。
- 健康检查 `GET /actuator/health`；聊天 `POST /api/v1/chat`，流式 `POST /api/v1/chat/stream`（SSE）。

**重部署流程（顺序不能乱）**：
1. 先确认 8080 无监听：PowerShell `Get-NetTCPConnection -LocalPort 8080 -State Listen`；有则 `taskkill /PID <pid> /F`。**禁止旧服务运行时打包**（Windows jar 文件锁导致包不更新）。
2. 全量 `install -DskipTests`（**不要**只用 `-pl tianshu-app`，也别只 `package`）。
3. 用 PowerShell `Start-Process` detached 启动（**不要**双层 `cmd start`），日志重定向到 `boot-*.log`。
4. 验证 health + 一轮 chat 冒烟。

> **增量构建坑（2026-09-23 实测，曾导致"全部接口 401"）**：Maven 增量构建只看本模块源码；
> 当 `tianshu-spring` 等依赖模块已更新、但 `tianshu-app` 源码没变时，`install`/`package` 会报
> "Nothing to compile / up to date"，`spring-boot:repackage` 不重跑，fat jar 继续内嵌**旧依赖 jar**，
> 即使日志打印 "Replacing main artifact" 也可能不更新产物。表现：新公开端点（register/login）仍 401，
> 而 `/auth/token` 405（旧 `SecurityConfig` 公开列表）。解决：先停服务释放锁，再 **`mvn clean install`**
> （此时日志应出现 "Recompiling the module because of changed dependency"）。验证 fat jar：
> 解出 `BOOT-INF/lib/tianshu-spring-*.jar` 看其中 class 时间戳/字符串。

## 架构要点（改动前必读）

- **安全模型（勿动语义）**：6 层工具策略链 `ToolPolicyChain` fail-closed；`EgressGuard` SSRF 逐跳防护 + DNS rebinding 防护；OBO JWT scope 授权；子代理递归深度 ≤2、并发 ≤8。安全决策归 ToolPolicyChain；`AgentHook` 仅观察性，不得参与安全裁决。
- **M4 长会话**：`CompactionService`（proactive/overflow 双路径，findSplit 防孤儿 TOOL 消息，LLM 摘要故障降级截断）；`DefaultModelRouter.routeChain` fallback（OVERLOAD→压缩重试；AUTH_CONFIG/UNAVAILABLE/OTHER→切 provider；工具失败不重入回合恢复）。
- **M5 会话可靠性**：`TurnSerializer`（per-session FIFO 队列 + QUEUED 状态 + cancel 释放槽位）；`ToolResultPruner`（12000 字符，头 75%/尾 25%，所有落库走 `persistToolResult` 收口）；`AgentHook` SPI（onTurnStart/onBeforeModelCall/onToolResult/onTurnEnd）；LLM 看门狗（默认 timeout 120s，TimeoutException 走 fallback）。
- **成本路由**：按价格表自动选最便宜 LLM；`forcedModel` 用户指定优先。
- **商业化护栏（改动需谨慎）**：`PlanGate`（功能门禁，不足抛 402）与 `QuotaService`（月度 token/轮次配额，
  超额抛 429）都遵循"对存量零侵入"——`billing.enabled=false`、caller 无 org、或 DB 查询异常时一律放行
  （availability > enforcement）。配额读取 `usage_record`（UTC `yyyy-MM`），不要改变其 period 口径。
  默认额度：Free 20 万 token+200 轮，Pro 500 万，Team 2500 万；试用默认 14 天。
  **现状：Stripe 默认关闭。2026-09-23 已在本地用 Stripe CLI + 测试卡（等价 4242）跑通 test mode
  订阅扣款→webhook→升级（free→pro active）全链路；live mode 真实收款尚未在 staging 跑通，勿宣称已在线收款。**
  - **webhook 白名单有两处，缺一即 401（2026-09-23 实测修复）**：`/api/v1/billing/stripe/webhook`
    必须同时加入 `SecurityConfig.PUBLIC_PATHS` **和** `AuthWebFilter.filter` 内硬编码的公开路径判断；
    服务器到服务器调用不带 JWT，鉴权由控制器的 HMAC-SHA256 验签负责。只改一处会表现为 listen
    转发收到 401（Security 放行但 AuthWebFilter 拦，或反之）。
  - **stripe CLI 隧道会僵死**：`stripe listen` 显示 Ready 但不再转发（无到 8080 连接、stdout 无事件），
    需重启；重启会打印新 whsec，要同步 `setenv.bat`。本次出网代理实际端口 **7899**（非旧记的 7890）。
    API 建约要在 subscription 上显式传 `default_payment_method`（只更新 customer invoice_settings
    不会回填已有订阅）；测试卡走 SetupIntent + `pm_card_visa`（raw card/token API 已停用，tok_visa sources 也禁用）。
  - **Webhook 漏送达的三道兜底（2026-09-23 修复，症状=付款生效但无账单、本地缺 stripe_customer_id）**：
    ① `StripeSyncService.reconcile` 回填（Stripe 订阅拉回本地）；② 公开端点 `POST /api/v1/billing/confirm`
    （Checkout 返回页按 session id 主动激活，无 JWT，已在两处白名单放行），建 Checkout 时把 orgId 写进
    `client_reference_id` **和** subscription 顶层 metadata；③ `StripeReconcileJob` 定时对账，cron 由
    `tianshu.billing.reconcile-cron` 配置（默认每 30 分），ShedLock `@SchedulerLock` 防多节点重复；
    `StripeClient.listAllSubscriptions` 传 `status=all`，但 **Stripe 的 status=all 不含 incomplete_expired**。
  - **对账必须按 org 聚合、每 org 只选一个权威订阅（2026-09-23 实测的幂等坑）**：同一 org 在 Stripe 可能有多条
    订阅（重复 checkout、废弃的 incomplete）。逐条 apply 会让后处理的失效订阅把先前 active 的降级成 free，
    表现为每轮都 `applied` 同一订阅、org 权益在 pro/free 间反复跳。修复：`outranks` 授权状态
    （active/trialing/past_due）优先、同级比 created 取最新，只对选中订阅收敛一次；已一致则计 unchanged 不写库。
  - **所有套餐 Price 必须统一币种（2026-09-23 实测）**：Stripe 不允许同一 customer 混用币种，报错 "You cannot
    combine currencies on a single customer..."。本次 pro 价格是 USD、team 却是 SGD，USD 客户去 checkout SGD
    team 即失败。修复=给 team 产品建 USD 价格、把产品 `default_price` 切到新价后才能归档旧价（默认价不可直接归档），
    并同步更新 dev/test/local 三套 YAML 的正反映射。改价/加套餐时务必核对各 Price 的 `currency` 一致。
    Checkout session 的 `status` 只读、不能手动 expire（24h 自动过期）。
  - **checkout 预检（409 引导去门户）**：复用 customer 时先用 `listCustomerLiveSubscriptions` 查是否已有
    active/trialing/past_due 订阅，有则返回 409（含币种不一致），前端弹窗引导"管理订阅"；另兜底把 Stripe 的
    combine-currencies 错误转成中文 409。套餐变更一律走 Stripe Customer Portal，不重复发起 Checkout。
- **企业治理（2026-09-22，P3-7）**：
  - **组织 SSO（OIDC 授权码 + PKCE）**：自实现，**不引** spring-security-oauth2-client / HTTP session，
    契合无状态 HS256 JWT 架构。`/api/v1/sso/start|callback` 公开（已在 `SecurityConfig.PUBLIC_PATHS`
    与 `AuthWebFilter` 白名单放行）；state 用 HMAC 自签名（`SsoStateService`，无状态无需 Redis，
    常量时间比较 + TTL），回调验 ID token（`OidcClient`，RS256 经 RemoteJWKSet，校 issuer/aud/exp/nonce）
    后 JIT 建号（`UserAccountService.provision`）、按 email 归组（已是成员的 409 容忍，满席 402 阻断）、
    换发本地 token，再 302 到静态桥接页 `sso-complete.html`（fragment 携 token，落 localStorage 后进 `/`）。
    总开关 `tianshu.sso.enabled`（默认 false），per-org 配置（issuer/clientId/clientSecret）在 organization 行
    （V24 新增 `sso_client_secret`，治理 GET 不回显密钥只给 `hasSsoClientSecret`）。SAML 仅有配置字段，登录流程未实现。
  - **组织级工具白名单**：`OrgToolWhitelistPolicy`（fail-open，未配置不限制）+ `AgentController` 把
    `allowedTools` 注入 turn metadata，端到端已通；治理端点 `GET/PUT /orgs/{id}/governance`。
  - **数据驻留区域**：organization 行 `data_region` 字段，治理端点可读写（当前为标记/合规元数据，未做物理分区路由）。

## 控制台 UI（React + Ant Design v6）

- **2026-09-03 已从 vanilla JS 重写为 React 19 + antd 6.6.2 + Vite 8 + TS**（提交 `3a4bb51`/`6ec52a0`/`623a2ee`）。旧 `static/assets/app.js`/`app.css`/vendor 字体已删，`static/index.html` 现在是 Vite 产物壳。
- **前端工程目录：`tianshu-app/ui/`**（不在 static 里手写）。源码 `ui/src/`：`App.tsx`（Layout+Menu+路由）、`theme.ts`（Tianshu 深色 token）、`api.ts`（fetch+SSE）、`ui.tsx`（共享 hook/标签/格式化）、`pages/*.tsx`（每页一文件）。
- **布局铁律（2026-09-04 修复）**：外层 `Layout` 必须 `height:'100vh' + overflow:'hidden'`，`Content` 设 `flex:1, minHeight:0, overflowY:'auto'` 作为唯一滚动容器（Chat 页 `overflowY:'hidden'` 内部自滚）；**禁止 `minHeight:'100vh'`**——内容超长时 body 整页滚动，头部和侧栏会跟着滚走且无独立滚动条。Sider body 高度用 `100%` 不是 `100vh`。
- **构建铁律：改 UI 必须先在 `ui/` 跑 `npm run build`，再 `mvn package`。** Vite `build.outDir` 直接输出到 `../src/main/resources/static`（emptyOutDir，会清空 static）；`mvn package` 只打包 static，**不会**自动跑前端构建。dev 用 `npm run dev`（vite `/api` 代理到 :8080）。
- **Windows 工具链**：node/npm 在 Windows 侧（WSL 出站不通 npm registry）；构建走 cmd interop：`cd /d D:\workspace\tianshu-agent\tianshu-app\ui && npm run build`，registry 用 npmmirror。类型检查 `npx tsc --noEmit`（vite build 不查类型，发布前必跑）。antd v6 API 查 `@ant-design/cli`（`npx antd@latest info ...`），别凭记忆。
- **视觉对标 Tianshu Control UI 深色主题**（色板取自 Tianshu dist/control-ui WebAwesome 变量，见 `theme.ts` 的 `OC` 常量）：底 `#0e1015`、卡 `#161920`、弹层 `#191c24`、悬停 `#1f2330`、描边 `#1e2028`、文字 `#f4f4f5`/`#bcbcc0`/弱 `#8b8b94`、**品牌珊瑚红 `#ff5c5c`**、圆角 6/10/14。全局深色 markdown/滚动条在 `ui/src/index.css`。
- **23 个页面**（`ui/src/pages/`，2026-09-22 实测）：在原有 Chat/Overview/Monitor/Sessions/Agents/
  Tools/Skills/Scheduler/Subagents/Approvals/Memory/Security/Audit/Models/Settings 之外，已新增
  **Login（注册登录）、Business（套餐+QuotaCard）、Usage（用量看板+QuotaCard）、Organization（组织成员）**。
  - 商业化 UI：Topbar 套餐徽章；`QuotaCard` 展示 token/轮次进度与超额；Chat 错误气泡识别
    **402 门禁 / 429 超额**并给升级入口；Security 页缺 `config:admin` 时只读降级不报红。
  - 端点数据：`/billing/me`、`/usage`、`/orgs/*`、`/auth/register`。
- 会话菜单（悬停 ⋯，非右键）：复制 ID / 重命名（`PATCH /sessions/{id}` title 存 metadata，免 DDL）/ 置顶（localStorage `oc.pinnedSessions`）/ 归档（`POST /{id}/close`，侧栏只拉 ACTIVE）/ 删除。会话管理页"打开"经 App `openRequest` → ChatPage.openSession。

## 约定与本机坑

- `.gitattributes` 强制 LF（`* text=auto eol=lf`）；提交前警惕 CRLF，必要时 `sed -i 's/\r$//'`。
- Java 测试方法内**不能声明局部方法**（JDK 语法限制），用 lambda 或私有方法。
- `curl` 经 `cmd /c`：URL 带 `&` 时内联引号会被转义吃掉，必须写成 **CRLF 行尾的 .bat 脚本**再执行；脚本里先 `cd /d D:\workspace\tianshu-agent`（cmd 从 WSL 启动时默认目录是 UNC 路径，写文件会静默失败）；输出重定向到 WSL 路径不通，用 stdout 管道回 WSL 再 grep。
- **本机出网走系统代理** `127.0.0.1:7890`（Clash，注册表 ProxyEnable=1）：Java 自动用系统代理（ARK 调用正常）；Windows curl 必须显式 `-x http://127.0.0.1:7890 --ssl-no-revoke`（否则 schannel 报 CRYPT_E_REVOCATION_OFFLINE）。
- 本机无 psql/docker：操作 PG 用 jdk-25 自带 `jshell --class-path <.m2 里的 postgresql-42.7.13.jar>` 跑 JDBC 片段。
- 本机常驻 **3 个 IDEA MCP server 的 java 进程**（JetBrains jbr，PID 不固定），**绝不能误杀**。应用进程特征 = `jdk-25` 路径 + `tianshu-app` jar。排查进程用 `Get-CimInstance Win32_Process`（wmic 已弃用）。
- 工作分支：`main`（已推送 https://github.com/gantangzx/tianshu-agent ）；提交作者 `Coder <coder@tianshu.local>`。
- 仓库根的 `smoke-*.bat`、`tmp/`、`boot-*.log` 是临时物，勿提交（tmp/ 待加 .gitignore）。

## 已知问题 / 待办

- ~~embedding 硬编码 OpenAI~~ **已解决（2026-09-03，3b63ff4）**：接入火山方舟多模态向量 `doubao-embedding-vision-251215`，端点 `/api/v3/embeddings/multimodal`，**2048 维**；`HttpEmbeddingClient` 按路径自动识别多模态协议（请求 input=[{"type","text"}]，响应 data.embedding），维度不匹配显式报错并降级关键词检索。本地配置在 gitignored 的 application-local.yml + setenv.bat（ARK_API_KEY）。换模型维度变了要 DROP memory_items 重建。
- **记忆自动写入闭环已上线（2026-09-03，f30e327 + 866e7f7）**：onTurnEnd 钩子（MemoryCaptureHook，order=100）fire-and-forget → LlmMemoryExtractor（抽取模型 ark-code-latest/便宜 LLM，保守 prompt、JSON 容错、importance<4 弃、每轮≤5 条）→ MemoryWritingService 语义去重（cosine≥0.62 跳过，阈值实测见下）→ PGVector。配置 `tianshu.memory.auto-capture`（默认 true）、min-importance、max-input-chars、dedup-similarity；provider=none 时短路不调 LLM。LongTermMemory.searchScored + ScoredMemory，PGVector 返回 1-distance。单测 13 个。
- **凭证已跑通（2026-09-03）**：ARK 两套产品两把 key——chat 走 Plan 端点 `/api/plan/v3`（`ARK_API_KEY=ark-7d95...b04be`），embedding 走标准 `/api/v3`（`EMBED_API_KEY=ark-f584...be49a`），都写在 gitignored 的 setenv.bat；spring 装配 key 解析链 EMBED_API_KEY→ARK_API_KEY→OPENAI_API_KEY。uuid 格式（ark-+UUID-+5hex）是 Plan key 正常格式。
- **去重阈值（重要实测）**：doubao-embedding-vision-251215 的 cosine 分布偏低——同义不同措辞 ≈0.75，相关不同事实 ≈0.44，不相关 ≈0.38；去重阈值默认 **0.62**（`tianshu.memory.dedup-similarity` 可调），0.90 拦不住任何重复。端到端验证：同义聊天被 `Skipping duplicate` 拦截、新事实正常入库、跨措辞语义召回精准。
- P1：embedding 维度变更不重建表（启动校验列维度，对应 audit-2026-09-14 P1-3；当前 memory_items 实际维度以启动日志 DDL 为准，ARK doubao 为 2048 维）；`McpClientTool` 永远构造 stdio config，反应式 SSE 客户端从 agent 不可达（对应 audit-2026-09-14 P1-2）。
- P2：`ReactiveAgent` 上帝类拆分；`temperature 0.7` 硬编码（obtainResponse 内 CompletionRequest）；审批 pub/sub fire-and-forget 可换 Redis Streams；ContextEngine SPI 化；密钥抽象 SecretRef。
- ~~P1：`DefaultSubAgentService` 拆分~~ **已解决（audit-2026-09-14 P1-1，2026-09-27 复核）**：
  原 919 行已拆为 8 个类，均在 320 行内、单一职责——`DefaultSubAgentService`（317 行薄编排）、
  `CritiqueOrchestrator`（251，best-of-n）、`AggregationRunner`（195）、`SubAgentChildPipeline`（163）、
  `SubAgentTaskInfo`（95）、`SubAgentTaskLedger`（71）、`SingleSpawnOrchestrator`（83）、
  `SubAgentEventBus`（51）。
- ~~P1：api 接口契约收口~~ **基本完成（2026-09-27 复核）**：spring 对 impl 的引用绝大多数是
  `new`/静态工厂/常量等装配耦合，且 impl 类普遍已 implements 对应 api 接口（Agent、SessionManager、
  ToolRegistry、ModelRouter、MetricsReporter、LongTermMemory、SkillRegistry、ApprovalManager 等）。
  唯一真正缺契约且被当类型大量消费的是 `TenantWorkspaces`（9 处 `ObjectProvider` 注入），已抽为
  api 接口 `com.gantang.tianshu.api.tool.Workspaces`（含 `WorkspaceException`/`WorkspaceEscapeException`），
  7 个内置工具与 spring 装配全部改依赖该接口（提交 `6429dd0`）。
  - 保留耦合：spring 的 JPA/Redis 会话存储内部类 `extends AbstractSession`/`InMemoryMessageStore`
    （复用 protected 字段与 `appendSilent`，属 SPI 基类复用而非纯契约泄漏，强拆代价大，暂留）。
- ~~`git rm qdrant_check.java`；`MilvusVectorMemory` stub 实现或删装配~~ **已解决**：两文件均已不存在且无装配，本条仅为清理记录。

## 协作偏好

- 简体中文交流；时间按 Asia/Shanghai。
- 先复现再修复；小步快跑、每步可验证；写完即跑 lint/测试/构建；尊重现有代码风格，不擅自引入新框架/付费依赖。
- 破坏性操作（删文件、改配置、force push、部署）先确认；`trash` 优于 `rm`。
