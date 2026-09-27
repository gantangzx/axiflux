# 天枢 信创适配说明（达梦 / 金仓 / 麒麟 / 统信 / 鲲鹏 / 海光）

> 状态标注：✅ 已支持并验证 ｜ 🟡 已支持但需现场验证 ｜ ⛔ 未支持/有计划
> 本文的 🟡/⛔ 项建议在现场实施前确认，避免「信创要求」在验收阶段变成阻塞项。

---

## 1. 技术栈与适配面

| 组件 | 当前 | 信创替换目标 | 状态 |
|---|---|---|---|
| 运行时 | JDK 25（HotSpot） | 毕昇 JDK / 龙井 JDK / 东方通 JDK | 🟡 需现场验证兼容性（class 版本一致即可） |
| 数据库 | PostgreSQL 16 + pgvector | 达梦 DM8 / 人大金仓 KingBaseES | 🟡 见第 2 节（方言与向量能力是主要差异） |
| 缓存 | Redis 7 | 东方通 TongRDS / 腾讯 Tendis / 阿里 Tair | 🟡 协议兼容则可用；`RedisAvailability` 会自动降级 |
| OS | CentOS/Ubuntu | 麒麟 V10 / 统信 UOS / openEuler | 🟡 需系统级联调（glibc、字体、时区） |
| CPU | x86_64 | 鲲鹏 920（aarch64）/ 海光（x86 兼容） | 🟡 需 aarch64 构建与压测 |
| 消息 | 内部事件/异步执行 | 东方通 TongLINK/Q、金蝶 Apusic | ⛔ 当前未接第三方 MQ（无强需求） |
| 中间件 | Spring Boot 内置 Netty | 宝兰德 / 东方通 Web | ⛔ 不建议替换（应用为自包含 jar） |

> 设计上不依赖任何商业中间件容器：应用是自包含 fat jar + PG + Redis 三件套，
> 因此信创改造集中在 **JDK / OS / CPU / 数据库** 四处，改造面可控。

---

## 2. 国产数据库适配（重点）

### 2.1 兼容性现状

| 能力 | PostgreSQL | 达梦 DM8 | 金仓 KingBaseES |
|---|---|---|---|
| JDBC 方言 | ✅ `org.postgresql` | 🟡 `dm.jdbc.driver.DmDriver`，需 `spring.jpa.database-platform` 适配 | 🟡 `com.kingbase8.Driver`，PG 兼容模式 |
| Flyway 迁移脚本 | ✅ V1–V26 | ⛔ **未提供 DM 版迁移脚本**（见 2.3） | 🟡 金仓 PG 兼容模式下多数语法可用，需逐条验证 |
| JSON 类型 | ✅ jsonb | 🟡 DM 有 JSON 支持，`jsonb` 需改写为 `CLOB`/`TEXT` | 🟡 |
| 自增/UUID 主键 | ✅ varchar ULID | 🟡 可保持应用侧生成（无自增依赖） | 🟡 |
| 向量检索（pgvector） | ✅ `vector` 扩展 + `<=>` | ⛔ **无等价扩展** | ⛔ **无等价扩展** |
| 全文检索 | 🟡 PG `ILIKE`/pg_trgm | 🟡 需改写 | 🟡 需改写 |
| 分布式锁 | ShedLock（SQL 表） | 🟡 锁表 SQL 需改写 | 🟡 |

### 2.2 关键结论

1. **长期记忆（向量检索）是国产库适配的最大障碍**：达梦/金仓当前没有 pgvector 等价能力。
   - 方案 A（推荐）：向量部分独立部署 PostgreSQL + pgvector（可与达梦/金仓并存，仅承担向量表）；
   - 方案 B：`VECTOR_PROVIDER=none` 关闭长期记忆，功能降级但业务可用；
   - 方案 C：改用外部向量服务（Qdrant）—— `VECTOR_PROVIDER=qdrant` 已具备接入点（当前为最简实现，需压测）。
2. 关系数据可迁移：应用侧主键为字符串 ULID，无数据库自增/序列依赖，迁移风险主要在 SQL 方言与类型。
3. **迁移脚本需按国产库重新物化一套**（`db/migration-dm/`、`db/migration-kingbase/`），并提供 `Flyway` 校验基线。

### 2.3 国产库适配改造清单

- **方言注入**：`spring.datasource.driver-class-name` + `database-platform` 参数化；`PG_URL` 抽象为 `DB_URL`
- **迁移脚本派生**：从 V1–V26 派生国产库版本（类型/索引/函数改写）
- **向量降级开关**：`vector.provider=none|pgvector|qdrant` 全链路验证（含 `/api/v1/memory/*` 行为）
- **探针脚本**：`scripts/ops/db-probe.sh` 自动探测国产库方言与扩展可用性
- **现场联调**：环境安装、压测、验收

---

## 3. 国产 OS 适配（麒麟 V10 / 统信 UOS / openEuler）

- **强依赖**：`JDK 25`（或客户提供的国产 JDK，需 ≥ class 版本 69 支持；若不行则需回退构建目标）
- **需确认**：glibc 版本、时区数据（license 有效期与审计依赖时钟）、中文字体（导出报表/PDF）、SELinux/等保加固策略对读写目录的限制
- **落地动作**：
  ```bash
  # 目标机快速体检
  uname -m; cat /etc/os-release; java -version; timedatectl; getenforce || true
  # 目录授权（等保加固常见坑）
  semanage fcontext -a -t var_log_t "/opt/tianshu/logs(/.*)?" || true
  restorecon -R /opt/tianshu
  ```
- 启动脚本已统一使用 `EnvironmentFile`，不依赖 bash 特性，可直接在麒麟/统信上用 systemd。

## 4. 国产 CPU 适配（鲲鹏 aarch64 / 海光 x86）

- 海光：x86_64 兼容，与现有构建产物**二进制一致**，风险最低（🟡 仅需现场压测）。
- 鲲鹏：需 aarch64 构建或跨平台运行；`tianshu-app.jar` 为平台无关字节码，**依赖的是 JVM 而非 CPU**，因此只需 aarch64 版 JDK 25 + 无本地库（native lib）依赖。
- 当前依赖中受 CPU 架构影响的部分为：`netty` 传输（自带 native 可选，缺省纯 Java，安全）、PG JDBC（纯 Java）。
- 压测指标建议：并发会话数、首字节时延、向量检索 P95、长连接稳定性（WS/SSE 各 2 h）。

---

## 5. 国产模型适配（不依赖海外 API）

平台 LLM 走 OpenAI 兼容协议，可对接：

| 供应商 | 接入方式 | 状态 |
|---|---|---|
| 火山方舟（Ark） | `ark-code-latest` 等别名，`ARK_API_KEY` | ✅ 已用（默认路由 ark-claude-haiku） |
| DeepSeek | OpenAI 兼容 base-url | 🟡 配置即可（需现场 key 与限流策略） |
| 通义千问 Qwen | DashScope OpenAI 兼容端点 | 🟡 配置即可 |
| 智谱 GLM | OpenAI 兼容端点 | 🟡 配置即可 |
| Kimi（Moonshot） | OpenAI 兼容端点 | 🟡 配置即可 |
| 本地推理 vLLM / Ollama | OpenAI 兼容端点（内网） | 🟡 需现场部署与压测 |

对接动作：在控制台「模型」页或 `sysConfigService` 增加 provider（base-url + key + model），
无需改代码（8 个 provider 已注册，路由默认 `ark-claude-haiku`，可切换）。

> 内网模型时必须同时部署 **embedding 服务**（`EMBED_API_KEY` + `tianshu.vector.embed-url`），否则长期记忆不可用。

## 6. 现场适配检查清单

- [ ] 目标机架构与 OS 版本已记录（`uname -a`、`/etc/os-release`）
- [ ] JDK 版本与 `java -version` 输出（是否 ≥ 25）
- [ ] 数据库选型与版本；**是否有 pgvector 等价能力**
- [ ] Redis 是否协议兼容；AOF 与 `noeviction` 是否可配
- [ ] 时钟同步（NTP）已开启
- [ ] 离线镜像已 `docker load` 或已预置 PG/Redis
- [ ] 防火墙策略（8080、PG 5432、Redis 6379 仅内网）
- [ ] 等保加固对目录权限/端口/日志的影响已确认
- [ ] `scripts/ops/verify-install.sh` 全绿（FAIL=0）
