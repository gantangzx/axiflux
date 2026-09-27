# tianshu-java 第二轮架构评审（2026-09-04 深夜）

> 第一轮评审（`architecture-review-2026-09-04.md`）的 P0–P2 已全部闭环并上线：
> 阻塞 JDBC 治理（部分）、MCP 身份透传、Milvus 假实现删除、向量后端诚实化、
> Session 模板收敛、AutoConfiguration 731→265 行、全局异常处理、统一 ApiResponse、
> ReactiveAgent 997→544 行拆分为 `ReactiveAgent` + `LlmRecoveryChain` + `ToolExecutor`
> + `ToolDefinitionMapper`、eval harness（13 replay 场景 + 并发测试）。
>
> 本轮在**重构后代码**上重新评审，3 个子代理并行深读 + 主线程逐条复核。
> 结论：**骨架依旧健康（api/impl 零泄漏、core 零 Spring、依赖单向、390 测试全绿），
> 但深挖出 1 个 P0、5 个 P1**。问题不再是"赘余/假实现"，而是集中在三类
> **更深的边界问题**：①响应式边界不彻底（阻塞调用漏网到 Netty 线程）；
> ②Spring Boot Starter 的隐式装配约定（`@Component` 靠组件扫描，但 starter 不被扫描）；
> ③长生命周期资源的取消/重连/清理语义。

---

## 一、总体结论

| 维度 | 一轮 | 二轮 | 说明 |
|---|---|---|---|
| 架构合理性 | 7 | **8** | ReactiveAgent 拆分后职责清晰，内核可读 |
| 冗余/臃肿 | 6 | **8** | 上帝类消除、装配层瘦身 |
| 正确性 | 7 | **6** | 深挖出定时任务空转、Redis 假持久化、回合脱缰 |
| 响应式纯度 | 6 | **6** | 阻塞治理只覆盖一半路径，入口仍漏 |
| 可维护性 | 7 | **8** | 新协作类内聚、测试网密 |
| 可扩展性 | 7 | **7** | SPI 稳定；MCP/调度的生命周期管理是短板 |
| 安全 | 8 | **7** | 取消回合仍执行工具副作用，需修 |

一句话：**皮肉赘余已治好，骨头里还有几根刺**——都不是结构要重写，而是边界收尾。

---

## 二、问题清单

### 🔴 P0（功能端到端失效，且表面正常）

#### P0-1　定时任务事件监听器不是 Spring Bean，cron/周期任务永不触发 Agent
- **证据**：`tianshu-spring/.../event/ScheduledTaskEventListener.java:28` 标了 `@Component`，
  但 spring 模块**没有 `@ComponentScan`**（应用 `@SpringBootApplication` 在 `com.gantang.tianshu.app`，
  扫不到 `com.gantang.tianshu.spring.event`），全模块也**没有任何 `@Bean` 方法**注册它。
  对照组 `TaskSchedulerLifecycle` 同样标 `@Component`，但在
  `config/TaskSchedulerConfiguration.java:66` 有显式 `@Bean`，所以它能活。
- **链路**：`ScheduledTaskService.onTaskFired()`（`:237`）到点 `publishEvent(ScheduledTaskFiredEvent)`，
  该事件的**唯一消费者**就是这个没出生的 Bean。任务到点触发、`runCount`/`nextRun` 照常更新、
  控制台 UI 一切正常，但 `agentTurn` 从不调用 Agent、`systemEvent` 从不写会话。
- **为何测试没挡住**：`ScheduledTaskEventListenerTest` 直接 `new ScheduledTaskEventListener(...)`
  手动调用，测的是类逻辑，测不出"Bean 根本没进容器"。
- **修法**：在 `TaskSchedulerConfiguration`（或 `TianshuWebConfiguration`）加显式 `@Bean`
  （构造器用 `ObjectProvider<Agent>`/`ObjectProvider<SessionManager>`，加 `@ConditionalOnBean`），
  并补一个 context-loads 断言"该 Bean 存在"。

### 🟠 P1（正确性 / 响应式 / 安全）

#### P1-1　排队中的回合被取消后仍"脱缰"执行，FIFO 串行化失效
- **证据**：`tianshu-core/.../agent/TurnSerializer.java`。回合 B 在队列里等回合 A 时，
  调用方（SSE 客户端）断连取消：`doOnCancel`（`:64-71`）里 `workSubscription.get()` 还是
  `null`（B 未订阅），不 dispose，只 `gate.tryEmitEmpty()`。A 结束后 worker（`:77-91`）
  **无条件** `turn.work().subscribe(...)`——B 的完整 Agent 回合照常跑（hooks、LLM 调用、
  **工具副作用：发邮件/写文件/spawn 子代理**、token 消耗），事件发向已取消的 sink 被丢弃；
  而 `gate` 早已完成，`concatMap` 立刻并发订阅回合 C。
- **影响**：①取消的回合在后台真实执行有副作用的工具，无人看结果（安全面）；
  ②B 与 C 并发写同一 session，TurnSerializer 存在的唯一理由（同会话串行）失效；
  ③边界上 `doFinally` 的 `statusMap.remove` 会误删 C 刚放的状态。
- **修法**：`Turn` record 加 `AtomicBoolean cancelled`；`doOnCancel` 置 true（已订阅则 dispose）；
  worker 订阅前检查 `if (turn.cancelled().get()) return turn.gate().asMono();` 跳过 work。

#### P1-2　SSE 传输的 MCP 连接 5 分钟必死，且无重连
- **证据**：`tianshu-core/.../mcp/SseMcpClient.java:201` 长生命周期 SSE GET 挂了
  `.timeout(Duration.ofMinutes(5))`（JDK HttpClient 请求超时覆盖整个交换体）。5 分钟后
  `readLine` 抛 `HttpTimeoutException`，reader 线程（`:219-243`）catch 后仅 log 退出——
  `connected`/`running` 仍为 true（只在 `close()` `:177` 置 false），**无重连**。
  此后靠 SSE 流回传的 JSON-RPC 响应永远到不了，`pendingRequests` 各自超时失败，
  MCP 工具全灭，只能重启进程。stdio 传输不受影响。
- **修法**：①长连接 GET 去掉 5 分钟 request timeout（POST 侧 15s/30s 超时保留）；
  ②reader 线程退出（EOF/异常）时翻 `connected=false`、失败所有 pending future，并按退避重连
  （重连后重新 `initialize` 握手）；最低限度也要翻状态，让 `McpClientFactory` 能重建。

#### P1-3　聊天入口（intake）的阻塞 JDBC 仍压在 Netty 事件循环上
- **证据**：`ReactiveAgent.processStream()` 在**组装期**（调用线程，`Flux.defer` 之前）
  同步执行 `sessionManager.getOrCreate(...)` 和 `session.addUserMessage(...)`。JPA 后端下
  这分别是一次读事务（findById+count+hydrate，`JpaSessionStore`）和一次写事务
  （`JpaMessageStore.append` 每次 `messageRepo.save` + `sessionRepo.touch`）。
  调用方 `AgentController`（chat/chatStream）与 `AgentWebSocketHandler` 在控制器方法体里
  直接调 `agent.process/processStream`，组装发生在 Netty 线程。
- **影响**：这是第一轮"阻塞 JDBC 压 Netty"的**漏网路径**——第一轮只给 LLM 调用
  （`LlmRecoveryChain` 的 `fromCallable + boundedElastic`）和部分控制器加了调度，
  入口消息持久化没覆盖。**每条聊天消息 = Netty 线程上一次 JDBC INSERT**；内存后端纯 list
  操作测不出来，上 PG/MySQL 高并发才暴露为 SSE/HTTP 卡顿。（core 与 spring 两个评审
  独立发现同一问题，交叉确认。）
- **修法**：把 intake 下沉进回合体（`Flux.defer(() -> { 解析 session、持久化 user 消息; ... return turn; })`），
  并让 TurnSerializer worker 在 boundedElastic 订阅：`turns.asFlux().publishOn(boundedElastic).concatMap(...)`。
  "先持久化保证 transcript 顺序"的意图由 FIFO 队列天然保持。

#### P1-4　Redis 会话后端的消息永不落 Redis
- **证据**：Agent 链路全程不调 `SessionManager.save()`（仅 `SessionController` close/rename、
  `RoutingSessionManager:73` 调）。`RedisSessionStore.save()`（`:68`）是唯一调
  `persistMessages()`（写 `session:memory:*` List）的地方。其内部 `InMemorySession`（`:181`）
  用纯内存 `InMemoryMessageStore`，**没有像 JPA 那样覆盖 append/状态钩子透写 Redis**。
- **影响**：选 `tianshu.session.provider=redis` 时，消息只在进程内 `localCache`——重启即丢、
  多实例另一节点重建时读到的 `KEY_MEMORY` 永远空。JPA 后端靠 append 透写不受影响。
  Redis 被日志宣传为 "Redis-backed" 持久化，实际是"易失缓存 + TTL 空壳"。
- **修法**：对齐 JPA 语义——重写 `RedisSessionStore` 的内部 session，覆盖 append/onStateChange/
  onMetadataChange 钩子透写 Redis（推荐）；或在回合结束显式 `save()`（但多实例时序差）。

#### P1-5　Netty 事件循环上的残留阻塞调用（第一轮治理的其余漏网点）
- **(a) ToolController 直连工具端点漏 `subscribeOn`**：`controller/ToolController.java:82`
  的 `Mono.fromCallable(() -> { guard.context()（JDBC）; ... tool.execute()（shell/HTTP/文件，
  任意时长）; })` **没有 `.subscribeOn(boundedElastic)`**；对照 `McpController.java:62,98` 都有。
  直连调工具会在 Netty 线程跑任意时长的阻塞工具。
- **(b) Persona CRUD 整组同步控制器**：`AgentController.java:85-149` 的
  GET/POST/PUT/DELETE `/api/v1/agents` 直接返回 `ApiResponse`（非 Mono），方法体内
  `JpaAgentDirectory.list/save/get/delete` 全是阻塞 JPA。
- **(c) CallerGuard 同步鉴权打 JDBC/Redis**：`auth/CallerGuard.ownsSession()` 调 `sm.get()`
  （JPA findById+hydrate / Redis 全量拉取），在 `AgentController` interrupt、`SkillController`
  buildContext、`SubAgentController:93`、`SessionController:103` 的方法体/`Mono.justOrEmpty(值)`
  参数位被同步求值。
- **(d) SessionController 状态变更**：`:138` `.doOnNext(Session::close)`、`:156` `updateMetadata`
  在 WebFlux 订阅线程触发 JPA `onStateChange/onMetadataChange` 同步 UPDATE。
- **修法**：统一"阻塞出口一律 `Mono.fromCallable(...).subscribeOn(boundedElastic)` 或
  `Schedulers` 桥接"；Persona CRUD 改返回 Mono 并包调度。建议加一条架构约定/编译期 checklist，
  因为这类漏网靠人眼 review 极易复发。

### 🟡 P2（清理 / 健壮性 / 可维护性）

| # | 问题 | 证据 | 修法 |
|---|---|---|---|
| P2-1 | 空闲时 `interrupt()` 毒化下一回合：无活跃回合也插入 INTERRUPTED 状态，下条消息被立即判"已中断"（只毒一条，但用户可感知） | `ReactiveAgent.interrupt()` 的 `prev==null` 分支 + intake/回合体保留 INTERRUPTED + runToolLoop 开头检查 | `interrupt()` 在 `prev==null` 时 no-op，或记一次性标志在下次 intake 消费 |
| P2-2 | 子代理结果注入父会话绕过空闲门：`onChildDone` 在完成线程立即 `parent.addSystemMessage`，可能插进父回合中途被压缩/组装读到 | `DefaultSubAgentService` 注入点早于 scheduleAggregation 的空闲门 | 注入挪到空闲门之后、聚合回合提交之前，或走 Agent 同一提交通道入队 |
| P2-3 | `CompactionService` 压缩水位 Map 会话删除不清理：`reset(sessionId)` 全项目无调用方，`RoutingSessionManager.delete` 不通知它 | `CompactionService.horizons`（ConcurrentHashMap），`reset()` 零调用 | 会话删除时调 `compactionService.reset(sessionId)`（经 SessionManager 回调或 Agent 暴露） |
| P2-4 | 新拆的 `LlmRecoveryChain`/`ToolExecutor`/`ToolDefinitionMapper` 无直接单元测试（0 引用），完全靠 ReactiveAgentTest + eval 20 场景间接覆盖 | grep 测试目录 | 集成覆盖已较充分；可为 ToolExecutor 审批分支、LlmRecoveryChain fallback 顺序补少量直接单测，固化拆分边界 |
| P2-5 | eval `error: hang` 场景在 boundedElastic 线程 `Thread.sleep(60s)`，看门狗 2s 放弃后线程残留约 1 分钟（daemon，不影响断言，但非干净） | `ReplayLlmClient.maybeThrow` hang 分支 | 改用可中断 `CountDownLatch.await` + 看门狗后 `interrupt`，或缩短到略大于超时即可 |
| P2-6 | `PGVectorLongTermMemory` 601 行混了 embedding HTTP/DDL/SQL/CRUD（响应式桥接本身**正确**，所有 Mono 都 `subscribeOn(boundedElastic)`） | 单类 601 行 | 可选抽 `EmbeddingClient` + `PgVectorSchema`（DDL）；非必须，内聚尚可 |
| P2-7 | `ScheduledTaskEventListener` 依赖 `@Async`（`@EnableAsync` 已在 TianshuAutoConfiguration:69），Bean 修复后需确认异步执行器与异常处理 | `@Async @EventListener` | 修复 P0-1 时一并验证 agentTurn 在异步线程执行、异常不吞 |

> 子代理另报若干细项（日志级别、javadoc 漂移、个别 record 防御性拷贝等），均归入"随手清理"，
> 不单列。

---

## 三、整改顺序建议

**第一批（P0 + 安全 P1，改动小、收益大，建议立即）**
1. P0-1 注册 `ScheduledTaskEventListener` Bean + context-loads 断言（~15 行）
2. P1-1 TurnSerializer 加 `cancelled` 标志（~10 行，配套 eval/单测验证取消不执行）
3. P1-5(a) ToolController 补 `subscribeOn(boundedElastic)`（1 行）

**第二批（响应式边界收尾）**
4. P1-3 intake 下沉回合体 + TurnSerializer worker `publishOn(boundedElastic)`（一处改动同时
   缓解 1-5(c)(d) 的部分调用路径）
5. P1-5(b)(c)(d) Persona CRUD 响应式化、CallerGuard/SessionController 阻塞桥接
6. P1-4 Redis 会话 append 透写（对齐 JPA 模板钩子）

**第三批（生命周期健壮性）**
7. P1-2 SseMcpClient 去 5 分钟超时 + reader 重连/状态翻转
8. P2 随手清理（interrupt 毒化、子代理注入时序、CompactionService.reset、hang 线程）

**测试策略**：每条都可加 eval replay 场景或 core 单测固化——
- P0-1：context-loads Bean 存在断言
- P1-1：TurnSerializerTest 加"排队中取消 → work 不被订阅"用例
- P1-3：ReactiveAgentTest 用阻塞 SessionManager 验证 intake 不卡调用线程
- P1-4：RedisSessionStoreTest 加"append 后 rebuild 能读到消息"

---

## 四、当前架构图（ReactiveAgent 拆分后）

```
                          ┌────────────────────────────────────────────┐
   HTTP / SSE / WS / MCP  │              tianshu-spring (Starter)        │
  ───────────────────────▶│                                            │
                          │  AuthWebFilter → CallerGuard (身份/scope)    │
                          │  13 Controller → ApiResponse 壳              │
                          │  GlobalExceptionHandler                      │
                          │  config/ Llm·Session·Memory Configuration    │
                          │          + LlmClientFactory + LiveSettings…  │
                          └───────────────┬────────────────────────────┘
                                          │ Agent SPI
                          ┌───────────────▼────────────────────────────┐
                          │             tianshu-core (零 Spring)        │
                          │                                              │
                          │   ReactiveAgent (544)  ← 回合编排/状态/FIFO   │
                          │     ├─ TurnSerializer      同会话串行队列     │
                          │     ├─ effectiveContext   persona/身份解析   │
                          │     └─ runToolLoop        工具循环骨架        │
                          │        │                                     │
                          │        ├─▶ LlmRecoveryChain (271)            │
                          │        │     路由/主动压缩/装配/看门狗/fallback│
                          │        │        └─ CompactionService        │
                          │        │                                     │
                          │        └─▶ ToolExecutor (320)                │
                          │              策略链门/审批/执行/超时/metrics  │
                          │                 └─ ApprovalManager          │
                          │                                              │
                          │   ToolDefinitionMapper(64)  Tool→JSON Schema │
                          │   DefaultSubAgentService(400) 子代理 fan-out │
                          │   SseMcpClient(415)/stdio   MCP 客户端 ⚠️    │
                          │   api/ 39 接口 + 22 record SPI               │
                          └───────────────┬────────────────────────────┘
                                          │ SPI 实现
                          ┌───────────────▼────────────────────────────┐
                          │ tianshu-storage        │ tianshu-eval      │
                          │  Jpa/Redis SessionStore │ replay/live harness│
                          │  PGVectorLongTermMemory │ 13 YAML + 并发测试 │
                          │  QdrantVectorMemory     │ ScenarioRunner     │
                          └─────────────────────────┴────────────────────┘
   ⚠️ = 本轮 P1/P2 标记点（MCP 重连、TurnSerializer 取消、intake 调度、Redis 透写）
```

---

## 五、确认健康的点

- **拆分质量**：ReactiveAgent 拆出的三个类同包协作、公开 API/`with*` 签名零变化、
  Spring 自动装配零改动；hooks 经函数式回调保持"观察式、绝不失败回合"契约；390 测试全绿。
- **响应式桥接正面样板**：`PGVectorLongTermMemory` 所有 Mono 方法、`LlmRecoveryChain` 的
  模型调用、McpController/AuditController/ConfigController 都正确 `subscribeOn(boundedElastic)`。
- **安全默认**：MCP 身份 fail-closed、spawn_task DESTRUCTIVE + scope 门、SSRF/egress 策略链完整。
- **测试网**：core 242 / spring 121 / eval 20 / storage 7，eval replay 场景把压缩/fallback/
  审批/截断/子代理/看门狗/并发都固化成了零成本回归。
- **依赖卫生**：根 pom BOM 管理干净，无死依赖（一轮清理后未发现新增）。
