# tianshu-java 架构体检与路线图（2026-09-14）

> 方法：基于当前工作区（含未提交 Redis 改动）的 317 个 main java 做静态扫描——
> 分层纪律 / 依赖方向 / 包内聚 / 规模热点 / 响应式纯度 / .block() 合规性，
> 并对照 AGENTS.md、round3 评审、三条路线图的既有记录逐条核验。
> 用途：供其他 agent 复核对照；结论后可附复核意见。
> 相关提交：Redis 多实例改动未提交（工作区 5 改 + 7 新文件）。

---

## 0. 复核指引

本报告的每一条结论都附了**可独立验证的证据**（文件:行、import 计数、行数）。
复核 agent 请重点核对：

1. 规模数字是否与实际一致（行数/import 数可能随提交漂移）；
2. "已解决"判定是否有代码证据支撑，还是我误判了陈旧文档；
3. 第 5 节优先级排序你是否认同（收益÷工作量）；
4. 第 4 节"不建议做"清单里有没有你认为该做的。

---

## 1. 分层纪律（扫描结果）

### 1.1 core 零 Spring 依赖 — ✅ 通过

317 个 main java 中仅 1 处 `javax.*` import：

- `tianshu-core/.../tool/builtin/DatabaseQueryTool.java` → `javax.sql.DataSource`

这是 JDK `java.sql` 标准接口，非 Spring 框架，**不构成纪律违规**，但 `javax.*`
命名空间在 JDK 17+ 建议改 `java.sql`，属笔误级。

### 1.2 模块依赖方向 — ✅ 无环、单向

| 依赖边 | import 次数 |
|---|---|
| spring → core | 220 |
| spring → storage | 25 |
| storage → core | 8 |
| app → core | 9 |
| app → spring | 3 |
| eval → core | 53 |

无反向依赖（core 不知道 spring/storage 存在）。

### 1.3 core/impl 子包互相渗透 — ✅ 健康

`agent`/`tool`/`memory`/`mcp`/`skill` 等子包间 cross-import **全部 <8 次**，
没有打成一团的迹象。

### 1.4 spring 绕过 api 直接消费 impl — ⚠️ 见第 3.2 节

spring import 了 **39 个不同的 impl 类**（vs 64 个 api 类）。其中大部分是装配
实现类的合理行为，但存在接口契约未收口的问题（详见 §3.2）。

---

## 2. 响应式纪律（扫描结果）

### 2.1 `.block()` / `Thread.sleep` — ✅ 全部合规

main 源码共 20 处，逐条核验无压在 Netty event loop 的情况：

| 位置 | 处数 | 性质 |
|---|---|---|
| `DefaultApprovalManager` | 6 | 同步 API 供非反应式调用方 |
| `SseMcpClient` | 3 | 重连退避 + idle 看门狗守护线程 |
| `ScheduledTaskEventListener` / `MemoryMaintenanceJob` | 2 | 调度线程（round3 已验证） |
| `LlmGuidedWorkflowStrategy` / `DefaultTaskScheduler` | 2 | 技能策略/任务执行线程 |
| `QdrantVectorMemory` / `HttpEmbeddingClient` | 3 | 存储写路径 / 重试退避 |
| `WebSearchTool` | 1 | 重试退避 |
| `eval/` harness | 3 | 测试 |

### 2.2 Controller 响应式纯度 — ✅ 58/60

12 个 Controller 约 60+ 端点，仅 2 个同步返回：

| 端点 | 判定 |
|---|---|
| `AgentController.models()` :72 | 纯内存读 `DefaultModelRouter` provider 列表，**合理** |
| `AuthController.issue()` :60 | 走 `AuthTokenService` 业务逻辑，**应桥接**（漏网之鱼，见 §5 P2-8） |

---

## 3. 结构热点

### 3.1 规模 Top（行数/方法数）

| 类 | 行数 | 方法数 | 判定 |
|---|---|---|---|
| `DefaultSubAgentService` [core] | **998** | 23 | 🔴 临破 1000，仍在持续增长 |
| `LangChain4jLlmClientAdapter` [spring] | **955** | 21 | 🟡 7 处 temperature 硬编码 |
| `ReactiveAgent` [core] | 846 | 38 | 🟢 平均每方法 22 行，收敛 |
| `PGVectorLongTermMemory` [storage] | 800 | 35 | 🟢 round3 判定"接受的结构债" |
| `SkillInstallService` [spring] | 671 | 33 | 🟢 |
| `ToolExecutor` [core] | 590 | 15 | 🟢 |
| `SseMcpClient` [core] | 567 | 19 | 🟢 |

**更正**：`TianshuProperties` 已拆分为 `config/props/` 下 18 个独立文件
（最大 `LlmProperties` 159L），不再是 42KB 上帝类。AGENTS.md 记录陈旧。

### 3.2 api 接口契约未收口 — ⚠️ P2

`com.gantang.tianshu.api` 定位为"可被非 Spring 宿主复用"的契约层，但 spring 消费的
39 个 impl 类中有一批**只有实现、没有 api 接口**：

`NoOpLongTermMemory`、`WorkflowSkillExecutor`、`CostOptimizedRouter`、
`DefaultContextAssembler`、`HeuristicTokenCounter`、`LlmMemoryExtractor`、
`MemoryWritingService`、`MemoryCaptureHook`、`ScopePolicy`、`NetworkEgressPolicy` 等。

这些类以 `Default*`/`NoOp*` 命名，说明设计时留了"可替换"意图，但替换点没被
接口固定。非 Spring 宿主想换实现得读 spring 装配代码才知道该实现什么。

**建议**：对照 spring 实际 import 的 impl 清单，凡被 `ObjectProvider<X>` 或
构造注入消费的，在 `com.gantang.tianshu.api` 补最小接口。先补消费最多的前 10 个。

---

## 4. 功能完成度

| 维度 | 状态 | 证据 |
|---|---|---|
| coding-agent-roadmap | ✅ 全交付 | file_edit/git/grep_search/codebase_search/DockerCommandSandbox/coder agent |
| agent-frontier-roadmap | ✅ P0–P3 全闭环 | injection 三层/todo/子代理隔离/结果句柄化/记忆时序/best-of-n/cache+成本/多租户工作区 |
| skill-registry-roadmap | ✅ Sprint A–E | 台账/独立注册表/市场页/Ed25519/联邦多注册表 |
| 三轮架构评审 | ✅ 1 P0+5 P1+7 P2 全修 | round3 报告末尾落地记录 |
| 巡检主动发现项 | ✅ 闭环 | 任务 TTL(5dd7332)/SSE idle 看门狗(86f6bb3)/安全响应头+CORS(18b979d) |
| eval 回归网 | ✅ 92 replay 进 CI | 安全 31/工具 20/编码 20/记忆 21 |
| 多实例部署 | 🔄 **唯一在途** | Redis 共享状态改动代码完成、21 新测试绿，**未提交** |

**没有"功能没做完"，只有"做完没交"。**

---

## 5. 待办路线图（按 收益÷工作量 排序）

### P0 — 立即做（在途工作收口）

| # | 事项 | 工作量 | 验证标准 |
|---|---|---|---|
| P0-1 | **全量回归后提交 Redis 多实例改动** | 0.5 天 | `mvn test` 六模块全绿；补验无 Redis 环境冷启动退化到本地（`RedisAvailability.reachable` false 分支目前只有单测，没实测启动） |
| P0-2 | **更新 AGENTS.md 待办列表** | 10 分钟 | 删 3 条已解决（qdrant_check / MilvusVectorMemory / vector(1536)），加 Redis 多实例进展。陈旧信息会造成复核 agent 重复核验（本次已浪费 5 次 grep） |
| P0-3 | `tmp/` 加 `.gitignore` | 1 行 | round3 + AGENTS.md 各记一次，两轮未做 |

### P1 — 本周值得做（真实缺口）

| # | 事项 | 工作量 | 说明 |
|---|---|---|---|
| P1-1 | **拆 `DefaultSubAgentService`** | 1~1.5 天 | 998 行且持续增长。建议拆 `SubAgentTaskLedger`（台账+TTL）/ `SingleSpawnOrchestrator` / `CritiqueOrchestrator` / `SubAgentEventBus`。best-of-n 刚上线热乎着拆成本最低 |
| P1-2 | **`McpClientTool` 支持 transport 参数** | 0.5 天 | schema 加 `transport`(stdio/sse，默认 stdio 兼容)+`url`；`doExecute`/`doExecuteReactive` 两条路径按参数构造 config。当前 Agent 自主调 MCP 永远走 stdio 子进程，`SseMcpClient` 重连+看门狗 Agent 用不上 |
| P1-3 | **PGVector 启动校验 DB 列维度** | 0.5 天 | 查 `information_schema` 拿 `memory_items.embedding` 实际维度，与配置不符则启动失败并提示 DROP 表。现在的失败点在第一次 INSERT |

### P2 — 结构债（排期做）

| # | 事项 | 工作量 | 说明 |
|---|---|---|---|
| P2-1 | `temperature` 收口到配置 | 0.5~1 天 | 10 处硬编码（`LangChain4jLlmClientAdapter` 占 7 处），走 `LlmProperties` + persona 级覆盖 |
| P2-2 | api 接口补齐（§3.2） | 1~2 天 | 先补 spring 消费最多的前 10 个 impl 类接口 |
| P2-3 | `AuthController.issue()` 桥接离 event loop | 1 行 | `subscribeOn(boundedElastic)`，保持纪律一致性 |
| P2-4 | `DatabaseQueryTool` `javax.sql` → `java.sql` | 1 行 | 笔误级 |

### 不建议现在做

| 事项 | 理由 |
|---|---|
| 拆 `LangChain4jLlmClientAdapter`(955L) | 7 个 provider 工厂高度同构，拆开只是 7 小文件+公共基类，间接层收益低。等 P2-1 temperature 收口后再评估 |
| `ContextEngine` SPI 化 / `SecretRef` / 审批换 Redis Streams | 无当下痛点驱动，按"不为假想需求过度设计"（round3 对 PGVectorLongTermMemory 的判定标准）搁置 |

---

## 6. 依赖关系

```
P0-1 提交 Redis 改动 ──┬─→ P1-1 拆 DefaultSubAgentService（避免与在途改动冲突）
                        └─→ 一切后续工作的干净基线

P0-2 / P0-3 独立，随时可做

P1-2 McpClientTool transport ── 独立
P1-3 PGVector 维度校验 ── 独立

P2-1 temperature 收口 ── 建议在拆 LangChain4jLlmClientAdapter 之前做
P2-2 api 接口补齐 ── 独立，可分批
P2-3 / P2-4 独立，一行级
```

---

## 7. 一句话结论

**架构骨架健康（分层无环、依赖单向、响应式纪律执行到位），功能完成度 ~95%，
无架构级病灶。最优先两件事：①把 Redis 多实例已完成的代码跑回归后提交；
②在 `DefaultSubAgentService` 破 1000 行前拆掉它。其余为接口收口与配置散落的
常规技术债，不阻塞。**

---

## 8. 复核记录（供其他 agent 填写）

| 复核人 | 日期 | 结论 | 异议/补充 |
|---|---|---|---|
| （待填） | | | |
