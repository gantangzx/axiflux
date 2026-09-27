# Tianshu 环境配置与运行手册

> 创建：2026-09-23
> 适用：tianshu-app 的部署、联调、预发与生产。
> 目标：把环境收敛为 **local / dev / test / prod** 四档，每档职责单一、晋升路径清晰，
> 支撑商业化（Stripe 收款、配额、SSO）安全落地。

---

## 1. 环境总览

| Profile | 定位 | 使用者 | 鉴权 | 工具策略 | Stripe | Webhook 形态 | 日志 |
|---|---|---|---|---|---|---|---|
| `local` | 单机开发，开箱即用 | 开发者本人 | 默认关 | `all` | 默认关 | — | DEBUG |
| `dev` | 共享开发联调，可排障 | 研发团队 | 开，HS256 + 自签端点 | `blacklist` | **测试模式** | `stripe listen` CLI 隧道 | DEBUG |
| `test` | 类生产预发，验证加固 | 研发/QA | 开，测试 IdP 或 HS256 | `whitelist` | **测试模式** | Dashboard 固定端点 | INFO |
| `prod` | 生产 | 线上用户 | 开，外部 IdP（非对称） | `whitelist` | **生产模式** | Dashboard 固定端点 | INFO |

**晋升路径（只能向上升）：**

```
local  ──提交/合并──▶  dev  ──发布候选──▶  test  ──验收通过──▶  prod
 本机验证               多人联调             类生产演练            真实收款
```

铁律：

- **只有 `test` 与 `prod` 配置同构**。不要用 `dev` 去验证生产加固（白名单、容器沙箱、固定 webhook）。
- **`dev` 的 Stripe 密钥必须是 `sk_test_`，`prod` 必须是 `sk_live_`**，二者绝不混用。
- 任何环境的密钥/口令只走环境变量或 secret manager，配置文件里不写明文。

---

## 2. 配置文件结构

```
tianshu-app/src/main/resources/
  application.yml         # 基线：所有 profile 的公共默认（含价格表、ARK extra-providers）
  application-local.yml   # 单机开发
  application-dev.yml     # 开发联调
  application-test.yml    # 类生产预发
  application-prod.yml    # 生产

scripts/
  start.bat               # Windows 统一启动： scripts\start.bat [local|dev|test|prod] [-f]
  start.sh                # Linux/macOS 统一启动
  stripe-listen.bat/.sh   # dev 用 Stripe CLI webhook 隧道

env/
  env.<profile>.bat.example   # Windows 密钥模板（提交）
  env.<profile>.sh.example    # bash 密钥模板（提交）
  env.<profile>.bat / .sh     # 实际密钥（gitignored，不提交）

docker-compose.yml        # 本地基础设施（PostgreSQL+Redis）与容器化应用
```

加载顺序：基线 `application.yml` 先加载，被激活 profile 的文件**覆盖**其中的同名键；
`${VAR:default}` 占位符再用环境变量填充。因此 profile 文件只写“与基线不同的部分”。

### 密钥注入约定

| 变量 | 用途 | local | dev | test | prod |
|---|---|---|---|---|---|
| `ARK_API_KEY` | ARK Plan 对话密钥 | 必填* | 必填 | 必填 | 必填 |
| `EMBED_API_KEY` | ARK 向量密钥 | 可选 | 必填 | 必填 | 必填 |
| `PG_URL` / `PG_USER` / `PG_PASSWORD` | PostgreSQL | localhost | 必填 | 必填 | 必填 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | Redis | localhost | 必填 | 必填 | 必填 |
| `AUTH_ENABLED` | 是否开鉴权 | 默认 false | true | true | true |
| `AUTH_SECRET` | HS256 密钥 | 空 | 必填 | 必填 | 可空（用 IdP） |
| `OIDC_ISSUER_URI` | 外部 IdP | — | — | 可选 | 必填 |
| `STRIPE_SECRET_KEY` | Stripe 服务端密钥 | — | `sk_test_` | `sk_test_` | `sk_live_` |
| `STRIPE_PUBLISHABLE_KEY` | Stripe 前端密钥 | — | `pk_test_` | `pk_test_` | `pk_live_` |
| `STRIPE_WEBHOOK_SECRET` | webhook 签名 | — | CLI `whsec` | Dashboard `whsec` | Dashboard `whsec` |
| `TIANSHU_PUBLIC_URL` | 本部署对外地址 | — | localhost | 预发域名 | 生产域名 |
| `VECTOR_PROVIDER` | 向量库 | none | pgvector | pgvector | pgvector |
| `EGRESS_PROXY` | 出口代理 | — | — | 可选 | 推荐 |

\* local 无 LLM key 也能启动（内存会话），只是无法真正回答对话。

---

## 3. local —— 本地开发

### 3.1 准备基础设施（可选）

最简单：什么都不装，`SESSION_PROVIDER=memory` 即用内存会话；需要落库时起容器：

```bash
docker compose up -d postgres redis
```

PostgreSQL 镜像自带 pgvector。如需向量记忆，在库中执行一次 `CREATE EXTENSION IF NOT EXISTS vector;`。

### 3.2 配置密钥（可选）

```bat
copy env\env.local.bat.example env\env.local.bat
:: 编辑填入 ARK_API_KEY
```

local 是唯一“无 env 文件也能启动”的 profile。

### 3.3 启动

```bat
scripts\start.bat                 :: 后台最小化，日志 logs\boot-local.log
scripts\start.bat local -f        :: 前台（看控制台输出）
```

IDE：直接运行 `TianshuAppApplication`，默认即 local profile。

### 3.4 需要本地联调登录/计费时

在 `env\env.local.bat` 设 `AUTH_ENABLED=true`、`AUTH_SECRET=<64 hex>`，重启。
要跑 Stripe 测试模式，则改用 `dev` profile（见下），它已配好 webhook 隧道流程。

---

## 4. dev —— 开发联调

### 4.1 基础设施

共享开发库，或本机：`docker compose up -d postgres redis`。

### 4.2 Stripe CLI 与登录（仅首次）

```bash
winget install --id Stripe.StripeCLI   # Windows
stripe login                           # 浏览器授权（本人操作）
```

### 4.3 配置

```bat
copy env\env.dev.bat.example env\env.dev.bat
:: 填 PG/Redis、AUTH_SECRET、ARK_API_KEY、STRIPE sk_test/pk_test
```

### 4.4 启动 webhook 隧道（独立窗口，常驻）

```bat
scripts\stripe-listen.bat
```

启动后打印 `Your webhook signing secret is whsec_xxx`，把它复制到
`env\env.dev.bat` 的 `STRIPE_WEBHOOK_SECRET`。**每次重启隧道该值可能变化**，需同步并重启应用。

### 4.5 启动应用

```bat
scripts\start.bat dev
```

### 4.6 验证

- `GET /actuator/health` → UP；
- 注册新用户（自动 14 天试用）→ 发起 Checkout → 用测试卡 `4242 4242 4242 4242` 支付；
- webhook 窗口依次出现 `checkout.session.completed`、`customer.subscription.updated`；
- `GET /api/v1/billing/me` 显示套餐已变 `pro`，全程无手改数据库。

dev 特点：`allow-private-network=true`、`code-executor-sandbox=local`、自签端点开启——**仅联调用**。

---

## 5. test —— 类生产预发

### 5.1 目标

在与生产**同构**的配置上演练发布，验证白名单、容器沙箱、SSRF、固定 webhook、（可选）OIDC、
配额强制本身是否正确。

### 5.2 基础设施

独立预发 PG / Redis，与生产、开发数据隔离（`application-test.yml` 默认指向
`test-pg.internal` / `test-redis.internal`，Redis database=1）。

### 5.3 配置固定 webhook 端点

Stripe Dashboard（测试模式）→ Developers → Webhooks → **Add endpoint**：

```
URL:           https://<test-host>/api/v1/billing/stripe/webhook
Events:        checkout.session.completed, customer.subscription.created,
               customer.subscription.updated, customer.subscription.deleted,
               invoice.paid, invoice.payment_failed
```

复制该端点的 **Signing secret（`whsec_...`，固定值）** 填入 `env\env.test.bat`。
这与 dev 的 CLI 临时隧道不同——预发用真实生产形态的固定端点，不需要 `stripe listen`。

### 5.4 （可选）测试 IdP

搭建测试 OIDC IdP 后，在 `application-test.yml` 取消 `spring.security...issuer-uri` 注释，
并设 `OIDC_ISSUER_URI`。无 IdP 时回退 HS256，但 token 自签端点保持关闭（与生产一致）。

### 5.5 启动

```bash
cp env/env.test.sh.example env/env.test.sh        # 填密钥
scripts/start.sh test
```

容器化（需宿主机可跑 Docker 以支持 code_executor 沙箱）：

```bash
SPRING_PROFILES_ACTIVE=test docker compose up -d
```

### 5.6 发布前验收清单

- [ ] 仅 `whitelist` 内工具可用；`code_executor` / `spawn_task` 不可用；
- [ ] `code_executor` 走容器（`docker`），`allow-private-network=false`；
- [ ] 用测试卡跑通 Checkout → 固定 webhook → 套餐收敛；
- [ ] Portal 取消订阅后，期末正确降级 `free`；
- [ ] 重复投递同一 `evt_` 事件为幂等 no-op；
- [ ] 配额超额返回 429、门禁不足返回 402；
- [ ] 无 DEBUG 日志，`/actuator` 细节需认证。

全部通过才允许发布 prod。

---

## 6. prod —— 生产

### 6.1 密钥注入

优先用平台能力（容器编排 secret / 云 secret manager / 环境变量），不要在机器上长期放明文文件。
若用文件，复制 `env/env.prod.sh.example` 并严格限制权限（`chmod 600`）。

### 6.2 创建 Live 产品与价格

Stripe Dashboard 切到 **Live mode**，创建 Pro / Team 的 recurring 价格。
把 Live `price id` 填入 `application-prod.yml` 的 `tier-price` / `price-plan`（正反映射一致），
或经 `SPRING_CONFIG_ADDITIONAL_LOCATION` 指向外部映射文件。`price id` 不是密钥，可提交。

### 6.3 注册生产 webhook 端点

Dashboard（Live）→ Add endpoint：`https://<prod-host>/api/v1/billing/stripe/webhook`，
复制其 `whsec_` 到 `STRIPE_WEBHOOK_SECRET`。

### 6.4 对接外部 IdP

设置 `OIDC_ISSUER_URI`（RS256/JWKS）。启用后本地 HS256 不再验签，token 自签端点关闭。

### 6.5 部署形态：在线 SaaS vs 私有化单机

`prod` profile 同时服务两种形态，因此用 `tianshu.deployment.mode` 区分（默认
`standalone`，保证存量私有化零影响）：

| 模式 | 含义 | 租户工作区隔离 |
|---|---|---|
| `standalone`（默认） | 单租户私有化 / 本地部署 | 不强制 |
| `saas` | 在线多租户 SaaS | **强制开启**，否则启动失败 |

在线 SaaS 必须设置：

```properties
tianshu.deployment.mode=saas
tianshu.tools.workspaces-enabled=true
tianshu.tools.workspaces-root=/var/lib/tianshu/workspaces
```

`saas` 模式下若漏开 `workspaces-enabled`，启动守卫（M2-4）会**立即 fail-fast**
并给出修复指引，避免 file/git/exec 工具静默落回共享全局根目录造成跨租户越权；
同时 `tenantWorkspaces` 健康组件会在根目录不可写时报 DOWN。

### 6.6 启动

容器（推荐，TLS 在网关层终结）：

```bash
docker run -d --name tianshu \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e TIANSHU_DEPLOYMENT_MODE=saas \
  -e TIANSHU_TOOLS_WORKSPACESENABLED=true \
  -e TIANSHU_TOOLS_WORKSPACESROOT=/var/lib/tianshu/workspaces \
  -e PG_URL=... -e PG_PASSWORD=... -e REDIS_HOST=... -e REDIS_PASSWORD=... \
  -e ARK_API_KEY=... -e OIDC_ISSUER_URI=... \
  -e STRIPE_SECRET_KEY=sk_live_... -e STRIPE_WEBHOOK_SECRET=whsec_... \
  -e TIANSHU_PUBLIC_URL=https://app.example.com \
  -p 8080:8080 tianshu:latest
```

> 私有化单机不设 `TIANSHU_DEPLOYMENT_MODE`（即 standalone），无需开启工作区隔离。

### 6.7 上线检查

- `GET /actuator/health` UP（经网关，不暴露细节）；
- 一轮真实对话冒烟；
- 完成一笔真实订阅（可先内部小额）确认 webhook 收敛；
- 监控配额、402/429、日志与告警。

> 现状纪律：Stripe Live 收款在 test 环境全链路验收通过前，不要对外宣称“已在线收款”。

---

## 7. 构建与重部署

本机（JDK 25 + Maven 3.9.15，Windows 工具链）：

```bat
:: 全量构建并安装到本地仓库（多模块跨依赖的可靠做法）
mvn clean install -DskipTests
```

改 UI 后：先在 `tianshu-app/ui` 跑 `npm run build`（产物输出到 `static`），再 `mvn package`。

重部署顺序：

1. 确认 8080 无监听（有则先停进程，**禁止旧服务运行时打包**——Windows jar 文件锁）；
2. `mvn clean install -DskipTests`（依赖模块变更时必须全量，避免 fat jar 内嵌旧依赖）；
3. 用 `scripts/start` 启动；
4. health + chat 冒烟。

详见 `AGENTS.md` 的“增量构建坑”。

---

## 8. 常见问题

| 现象 | 原因 / 处理 |
|---|---|
| 启动报 `SCRAM ... no password` | `PG_PASSWORD` 未注入；检查对应 env 文件 |
| webhook 返回 503 | billing 未启用或 `STRIPE_WEBHOOK_SECRET` 为空 |
| webhook 返回 400 signature | dev：隧道重启后 `whsec` 变了，同步并重启；test/prod：端点密钥填错 |
| Checkout 返回 `live:false` | `STRIPE_SECRET_KEY` 为空或仍为错误模式密钥 |
| `no Stripe price configured` | `tier-price` 未填或 id 错 |
| 支付成功但套餐未变 | 反向 `price-plan` 缺失，或 key 与正向不一致，或隧道未开 |
| 发布到 test 后行为和 dev 不一致 | 预期：test 是白名单 + 容器沙箱；应据此修正，而非放宽 test 配置 |

---

## 9. 独立服务：Skill Registry

`tianshu-registry` 是与主应用解耦的**技能注册中心**（默认 8090），不走 local/dev/test/prod
四档，而是单一 `application.yml` + 环境变量。启动：`scripts\registry.bat`，
密钥模板 `env/env.registry.bat.example`。完整手册见 **[registry.md](registry.md)**。

主应用通过 `tianshu.skills.registry-url=http://localhost:8090` 把它作为"技能市场"后端；
注册中心停服不影响本地与已装技能。

---

## 10. 安全善后

历史上曾有 Stripe 测试密钥、Redis/PG 口令随旧脚本/配置提交进 Git。本次已删除并改为环境变量。
建议：

1. 在 Stripe Dashboard **轮换（roll）被提交过的 test key**；
2. 更换被提交过的 Redis / PG 口令；
3. 用 `git log -p` 复查历史是否还含其他明文密钥；如仓库将公开，考虑清理历史（破坏性操作，先备份）。
