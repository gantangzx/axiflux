# tianshu-java 第三轮架构评审（2026-09-05）：二轮遗留项逐条核验

> 方法：以第二轮评审（`architecture-review-2026-09-04-round2.md`）的 1 P0 + 5 P1 + 7 P2
> 为基线，在当前 HEAD（f85d262，之后仅 UI/打包改动，无 Java 变更）上逐条对照源码核验。
> 结论：**二轮问题全部未修，代码原样在位**。另有 2 个仓库卫生问题。
> 08-27 时期的历史欠账（WebSocket 帧协议、MCP 服务端、内置工具 stub）经核验**已闭环**。

---

## 一、核验结果总表

| 编号 | 问题 | 状态 | 当前证据（2026-09-05） |
|---|---|---|---|
| P0-1 | ScheduledTaskEventListener 不是 Bean，cron/周期任务永不触发 Agent | 🔴 未修 | 全项目仅类自身 + 测试引用它；TaskSchedulerConfiguration 3 个 @Bean 无其一；类上仍只有 `@Component`（spring 模块无组件扫描） |
| P1-1 | 排队中回合被取消后仍脱缰执行（工具副作用/并发写） | 🔴 未修 | TurnSerializer.java：Turn record 无 cancelled 标志；doOnCancel 仅 dispose+tryEmitEmpty；worker concatMap 无条件 `turn.work().subscribe(...)` |
| P1-2 | SSE 传输 MCP 连接 5 分钟必死、无重连 | 🔴 未修 | SseMcpClient.java:201 仍 `.timeout(Duration.ofMinutes(5))`；全类无 reconnect/backoff/retry；reader 退出不翻 connected=false |
| P1-3 | 聊天入口阻塞 JDBC 压在 Netty 线程 | 🔴 未修 | ReactiveAgent.java:325 `sessionManager.getOrCreate(...)`、:336 `session.addUserMessage(...)` 在组装期同步执行，Flux.defer 在 :354；TurnSerializer worker 无 publishOn(boundedElastic) |
| P1-4 | Redis 会话后端消息永不落 Redis | 🔴 未修 | RedisSessionStore.java:183 InMemorySession 仍用裸 InMemoryMessageStore；persistMessages(:129) 唯一调用点是 save()(:70)，Agent 链路不调 save() |
| P1-5a | ToolController 直连工具端点漏 subscribeOn | 🔴 未修 | ToolController.java:81 `Mono.fromCallable(...)`（内含 guard.context JDBC + tool.execute 任意时长阻塞），链尾无 subscribeOn |
| P1-5b | AgentController Persona CRUD 整组同步 | 🔴 未修 | :85/:97/:118/:142 仍直接返回 ApiResponse（非 Mono），方法体阻塞 JPA |
| P1-5c | CallerGuard 同步鉴权打 JDBC/Redis | 🔴 未修 | guard.context(...) 同步调用点：AgentController:348、SessionController:80、SkillController:78、SubAgentController、ToolController:88、McpController:80、McpStreamableController:66 |
| P1-5d | SessionController 状态变更在 WebFlux 线程同步 UPDATE | 🔴 未修 | :138 `.doOnNext(Session::close)`、:156/:158 `updateMetadata(...)` |
| P2-1 | 空闲时 interrupt() 毒化下一回合 | 🔴 未修 | ReactiveAgent.interrupt()：prev==null 分支仍 `new AgentStatus(..., INTERRUPTED, 0, null, 0)` |
| P2-2 | 子代理结果注入绕过父会话空闲门 | 🔴 未修 | DefaultSubAgentService.java：onChildDone :264 `parent.addSystemMessage(...)` 先于 :272 scheduleAggregation(ti) |
| P2-3 | CompactionService 水位 Map 会话删除不清理 | 🔴 未修 | 全项目无 compactionService.reset(...) 调用方 |
| P2-4 | LlmRecoveryChain/ToolExecutor/ToolDefinitionMapper 无直接单测 | 🔴 未修 | test 目录无同名测试类，仅 ReactiveAgentTest + eval 间接覆盖 |
| P2-5 | eval hang 场景 boundedElastic 线程 sleep 60s 残留 | 🔴 未修 | ReplayLlmClient.java:137 `Thread.sleep(60_000)` |
| P2-6 | PGVectorLongTermMemory 601 行混 embedding/DDL/SQL/CRUD | 🟡 可选 | 内聚尚可，非必须拆 |
| P2-7 | @Async 执行器/异常处理待 P0-1 修复后验证 | 🟡 待验 | SchedulerConfig 提供 ThreadPoolTaskScheduler bean（@Async 可能复用它），listener 修复后需实测 agentTurn 异步执行与异常不吞 |

## 二、已闭环的历史欠项（核验确认，无需再动）

- **WebSocket 帧协议**：AgentWebSocketHandler 已实现 JSON text frame 协议（帧类型分发、握手身份优先于帧内 userId、malformed frame 不再误报客户端错误）。
- **MCP 服务端**：McpController + McpStreamableController + McpJsonRpcHandler 均在，身份经 CallerGuard 透传。
- **内置工具 14/14 全部真实实现**：tts（OpenAiTtsProvider）、email_send、image_analyze、web_search/fetch 等均无 stub/TODO 标记。
- **Milvus 假实现**：已 fail-fast 不装配（二轮确认）。
- **一轮 P0–P2**：二轮报告头部声明全部闭环，本轮抽查无回退。

## 三、仓库卫生问题（新发现）

1. `tianshu-app/ui/_extract.cjs`、`tianshu-app/ui/patch-upload.cjs`：未跟踪的临时脚本，建议删除或移出版本库目录。
2. 静态资源目录 `target/classes/static/assets` 与 `src/main/resources/static/assets` 堆积 30+ 个历史 hash bundle（vite emptyOutDir 只清 src 侧）；jar 打包只带 src 侧，不影响产物，可随手清 target 侧。

## 四、修复批次建议（同二轮，按改动小/收益大排序）

**第一批（P0 + 安全 P1，约 30 行改动）**
1. P0-1：TaskSchedulerConfiguration 显式 @Bean 注册 ScheduledTaskEventListener（ObjectProvider 惰性注入 + @ConditionalOnBean），补 context-loads Bean 存在断言。
2. P1-1：Turn record 加 AtomicBoolean cancelled；doOnCancel 置位；worker 订阅前检查跳过。补 TurnSerializerTest「排队中取消 → work 不被订阅」。
3. P1-5a：ToolController.invoke 链尾补 `.subscribeOn(boundedElastic)`（1 行）。

**第二批（响应式边界收尾）**
4. P1-3：intake 下沉 Flux.defer 回合体；TurnSerializer worker `publishOn(boundedElastic)`。
5. P1-5b/c/d：Persona CRUD 改 Mono + 调度；CallerGuard 调用点包 fromCallable+subscribeOn；SessionController close/metadata 桥接。
6. P1-4：RedisSessionStore 内部 session 覆盖 append/状态钩子透写 Redis（对齐 JPA 模板），补 rebuild 读回测试。

**第三批（生命周期 + P2 清理）**
7. P1-2：SseMcpClient 长连接 GET 去掉 5 分钟 request timeout；reader 退出翻状态、失败 pending、退避重连+重新 initialize。
8. P2-1/2/3/5：interrupt 空操作 no-op、子代理注入挪到空闲门后、会话删除调 compactionService.reset、eval hang 改可中断等待。
9. P2-4：补 ToolExecutor 审批分支、LlmRecoveryChain fallback 顺序直接单测。

**运行时影响提示**：P0-1 意味着当前线上实例的定时任务（控制台看到 restored N tasks）到点只会空转更新 runCount/nextRun，agentTurn/systemEvent 永远不执行——定时任务功能目前是端到端失效的。


---

# 修复落地记录（2026-09-05 晚）

三批全部落地。全量 `mvn test` BUILD SUCCESS：core / eval / storage(7 tests) / spring(122 tests) 全绿；新增直接单测 ToolExecutorDirectTest(3)、LlmRecoveryChainDirectTest(2)、ToolDefinitionMapperDirectTest(2)，TurnSerializerTest 新增 1 例（共 5）。

| 项 | 状态 | 落地方式 |
|---|---|---|
| P0-1 定时任务端到端失效 | ✅ | ScheduledTaskEventListener 去掉 @Component（starter 不在组件扫描范围），TaskSchedulerConfiguration 显式 @Bean（ObjectProvider 惰性注入 Agent/SessionManager/ObjectMapper，@ConditionalOnMissingBean）；SmokeTest 新增 bean 存在断言 |
| P1-1 排队回合取消后仍执行 | ✅ | Turn record 加 AtomicBoolean cancelled；doOnCancel 先置位再 dispose workSubscription 再放行队列槽；worker concatMap 订阅前检查 cancelled 直接放行 gate；TurnSerializerTest 新增「排队中取消→work 不被订阅」（A 卡死/B 排队取消/C 正常 FIFO） |
| P1-2 SSE 5 分钟超时无重连 | ✅ | SseMcpClient 长连 GET 删除 .timeout(5min)；reader 线程改为重连循环（指数退避 1s→30s，连接成功后重置）；断流/连接失败时 markDisconnected()（connected=false + pending 全部异常完成，调用方快速失败而非等满超时）；endpoint 事件在重连流上触发独立 reinit 线程 replay initialize 握手 |
| P1-3 intake 阻塞 Netty 线程 | ✅ | ReactiveAgent.processStream 的 getOrCreate/effectiveContext/addSystemMessage/addUserMessage/toolDefs 全部下沉进 Flux.defer 回合体（FIFO 保证 transcript 顺序）；QUEUED 状态保留调用线程（纯内存）；TurnSerializer worker 链加 publishOn(boundedElastic) |
| P1-4 Redis 会话重启丢消息 | ✅ | RedisSessionStore 内部 InMemorySession 重写为 RedisSession + RedisMessageStore：append 逐条透写 Redis List 并滑动 TTL（对齐 JPA 写穿模式），onStateChange/onMetadataChange 透写 Hash，rebuild 用 hydrate() 静默装载不回写；save() 全量快照保留作兜底 |
| P1-5a ToolController 阻塞 | ✅ | invoke 链尾补 .subscribeOn(boundedElastic)（内含 CallerGuard 阻塞鉴权 + 任意时长工具执行） |
| P1-5b AgentController persona CRUD | ✅ | agents/createAgent/updateAgent/deleteAgent 四个同步返回改 Mono.fromCallable + subscribeOn；chat/chatStream/interrupt 的 authorizeSession+buildContext 桥接（validate 纯内存校验保持同步，保证畸形请求快速失败、heartbeat 测试语义不变） |
| P1-5c CallerGuard 同步鉴权 | ✅ | 所有 guard.context/ownsSession 调用点随控制器桥接移入 boundedElastic：SessionController(create/ownedSession/rename/delete)、SkillController(execute/executeMatching)、SubAgentController(spawn)；Mcp 两个控制器此前已有 subscribeOn |
| P1-5d SessionController close/metadata | ✅ | close 的 Session::close（触发阻塞状态钩子）、rename 的 updateMetadata（触发阻塞元数据钩子）均在 ownedSession 的 boundedElastic 链上执行 |
| P2-1 空闲 interrupt 毒化下一回合 | ✅ | ReactiveAgent.interrupt 由 statusMap.compute 改 computeIfPresent：无活动回合时 no-op，不再写入 INTERRUPTED；新增 forgetSession(sessionId) 清理 statusMap |
| P2-2 子代理结果注入时序 | ✅ | onChildDone 不再直接写父会话；结果注入移到 runAggregation：空闲门通过后、聚合回合排队前 addSystemMessage，FIFO 保证不可能插进活动用户回合 |
| P2-3 删会话不清理 compaction | ✅ | RoutingSessionManager 新增 addDeleteListener（CopyOnWriteArrayList，异常不阻断删除）；TianshuAutoConfiguration 注册 agent::forgetSession → ReactiveAgent.forgetSession 清 statusMap + LlmRecoveryChain.resetSession → CompactionService.reset |
| P2-4 拆分类无直接单测 | ✅ | ToolExecutorDirectTest（DENY 不执行 / 无审批管理器优雅失败 / 待审批发 APPROVAL_REQUIRED 且不执行）、LlmRecoveryChainDirectTest（UNAVAILABLE 落备用 provider / 空链显式报错）、ToolDefinitionMapperDirectTest（hidden 过滤、null 参数降级、字段映射） |
| P2-5 eval hang 睡 60s | ✅ | ReplayLlmClient 的 hang 由 Thread.sleep(60_000) 改 CountDownLatch.await(10s)：watchdog 场景 2s 即放弃，遗留 daemon 线程 10s 内退出（远大于场景超时、有界） |
| P2-6 PGVectorLongTermMemory 拆分 | ⏸ 评估后不动 | 现 545 行，全部内聚于单一 pgvector 后端（DDL+CRUD+embedding HTTP+向量格式化），JdbcTemplate/ObjectMapper/embed 配置强共享；拆分类只增间接层、无复用收益、无新测试 seam，storage 7 测试已覆盖。记为接受的结构债 |
| P2-7 @Async 执行器 | ✅ 复查无问题 | SchedulerConfig 已 @EnableScheduling + @EnableAsync + @EnableSchedulerLock，@Async 复用 tianshu-scheduler ThreadPoolTaskScheduler（自定义 errorHandler，前缀 tianshu-scheduler-）；ScheduledTaskEventListener 的 block() 在调度线程上安全 |

**验证**：全量 reactor `mvn test` 六模块 BUILD SUCCESS；`mvn package -DskipTests` 全量打包（jar 137,534,208 bytes）；start-app.bat 重启，/actuator/health 200 UP（PID 36536）。

**注意**：P0-1 修复意味着线上实例的定时任务（控制台可见 restored N tasks）此前到点只空转更新 runCount/nextRun，agentTurn/systemEvent 从未执行——本次重启后定时任务才真正端到端可用。
