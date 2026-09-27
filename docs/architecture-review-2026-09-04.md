# tianshu-java 架构评审报告（2026-09-04）

> **整改进展**：
> - **P0 全部修复**（`cd0d65e`，380 测试全绿）：①AuditController/McpController 阻塞调用移到 boundedElastic；②MCP REST 通道经 CallerGuard 透传调用者身份（McpEndpoint 新增 3 参 callTool）；③MilvusVectorMemory 假实现连同依赖/配置整体删除；④向量后端不再静默默认 qdrant（未配置→no-op+提示，qdrant 显式选择时 WARN，非法值启动失败）；⑤死依赖/死配置清理（httpclient5、redis 依赖归位 spring、StorageProperties、websocket 幽灵配置补 WebsocketProperties）。
> - **P1 全部完成**（`bf96fa5`、`4ab267f`、`ea48866`，全量测试绿 core 242/spring 121/eval 19/storage 4）：全局异常处理 GlobalExceptionHandler（IllegalArgumentException→400/404、统一错误体）；Session 抽象模板 AbstractSession + InMemoryMessageStore + InMemorySessionManager 下沉 core，四份拷贝收敛为一套（统一 markDone=SUSPENDED 语义、修复 Redis 丢 attachments、eval getHistorySince 失效 bug）；ReactiveAgent 抽取 invokeTool 消除执行链重复并补齐审批路径指标；DefaultSubAgentService 下沉 core；**P1 收尾（ea48866）**：AutoConfiguration 610→265 行（LLM/会话/记忆拆为三个域配置 + LlmClientFactory 收拢 key 链/上下文窗口/价格表）；MCP streamable HTTP（/mcp）tools/call 经 CallerGuard 透传真实调用者身份；LiveSettingsCatalog 热更配置单事实源（LIVE_KEYS/HOT_SETTINGS 双清单合并）；ConfigController 全响应式化（阻塞 JPA 上 boundedElastic）。
> - **P2 清理已完成主体**（`19aa4a1`）：删 tianshu-skills 空壳模块（reminder 示例存档 docs/example-skills/）；删根目录 console/ 旧前端（45 文件，零引用）；删入库杂物 qdrant_check.java；ScheduledTaskService 事务注解误导修正（onTaskFired 的 @Transactional 自调用不生效，实际靠 TransactionTemplate，javadoc 同步更正）；storage 补 PGVectorLongTermMemory 契约测试（Config 归一化 + DDL 容错，无需 Docker）。
> - **P2 遗留已全部处理并部署**（2026-09-04 晚，`eb6f8ba`/`136b4cd`）：① examples 改名 **tianshu-app**（目录/artifactId/包名 com.gantang.tianshu.app/jar 名/start-app.bat/Dockerfile，主人授权改部署脚本）；② **统一 ApiResponse 响应壳**——新增 web/ApiResponse，13 个控制器约 50 个非流式端点全部包装，前端 api.ts 封装层统一解包（页面零改动），SSE/MCP-JSON-RPC 协议面不动；③ storage 新增 testcontainers 集成测试（pgvector 真库 + stub embedding，无 Docker 环境自动跳过）。已 package + 重启部署，线上验证：tools/models 壳正常、错误体统一、新前端 bundle 200。
>
> 范围：6 个 Maven 模块、297 个主代码 Java 文件、约 3.5 万行。
> 方法：全量包结构/LOC/依赖分析 + 核心类逐行阅读 + grep 取证（死代码/重复/阻塞调用）。

## 一、总体结论

**架构骨架是健康的，皮肉有赘余。**

- **分层干净**：`api/` 包 39 个接口 + 22 个 record，api 包零处 import impl；core 零 Spring 依赖；模块依赖方向单向无循环（core ← storage ← spring ← examples；eval → core）。这在同类项目里属于上游水平。小瑕疵：api 里有 4 个具体 final 类——`ToolPolicyChain`（含评估逻辑的组合类，ToolPolicyChain.java:14）、`SecretMasker`（静态工具类）、`LiveSettings`（配置中枢）、`MetricNames`（常量）；前两者是实现性质，宜下沉 impl。另审批 SPI（ApprovalManager/ApprovalStore/AutoApprovalPolicy/ApprovalDecision）放在 `api/agent/` 而实现有独立 `impl/approval/` 包，建议提 `api/approval` 对齐。
- **SPI 面完整**：Tool / LlmClient / ModelRouter+RoutingStrategy / LongTermMemory / ContextAssembler / SkillLoader / ToolPolicyChain / AgentHook / ApprovalManager / SessionManager / McpClient 全部接口化，扩展点齐全。
- **core 测试扎实**：40 个测试文件、239 个 @Test，ReactiveAgent、六层策略链、压缩、fallback、审批、路由器都有覆盖。
- **主要问题集中在"装配层"和"存储层"**：上帝类、三份 Session 拷贝、假实现伪装成功、死模块/死依赖、控制器阻塞事件循环。这些不影响框架能力本身，但会在演进时持续征税。

## 二、整体架构图

```
┌────────────────────────────────────────────────────────────────────────┐
│                    tianshu-examples（名 "examples"，实为唯一可部署应用） │
│  @SpringBootApplication · ExampleController(228行，live 端点非 demo)    │
│  ui/ React+antd 控制台(28 ts/tsx, 4355行) → build → resources/static/  │
│  application*.yml · Dockerfile 打这个 jar · 0 测试                       │
└───────────────────────────────────┬────────────────────────────────────┘
                                     │ 引入 starter
┌───────────────────────────────────▼────────────────────────────────────┐
│                    tianshu-spring（Spring Boot Starter，77 文件）       │
│                                                                        │
│  Web 层：12 个 @RestController（/api/v1/**）+ WebSocket + SSE           │
│  安全：auth/（JWT/OBO 身份透传 H_USER/H_SCOPES）+ spring-security       │
│  装配：TianshuAutoConfiguration(731行, 配置上帝类) + 17 个 config 类   │
│  服务：DefaultSubAgentService(400) / ScheduledTaskService(353) /       │
│        RuntimeConfigService(257)                                        │
│  适配：LangChain4jLlmClientAdapter(445) · providers/（OpenAI TTS/      │
│        Vision、SMTP Email）                                             │
│  存储适配：JpaSessionStore(450) · RedisSessionStore(251) ·             │
│        QdrantVectorMemory(289) · MilvusVectorMemory(stub)              │
└──────────────┬───────────────────────────────────┬─────────────────────┘
               │                                   │
┌──────────────▼─────────────────┐  ┌──────────────▼──────────────────────┐
│      tianshu-storage（17 文件）│  │         tianshu-core（178 文件，SDK 内核）│
│  entity/ 7 个 JPA 实体          │  │                                       │
│  repository/ 7 个 Spring Data   │  │  api/  39 接口 + 22 record + 4 final 类（SPI 面） │
│  pgvector/ PGVectorLongTerm     │  │   agent · tool · tool.policy · llm   │
│    Memory(601行)                │  │   memory · skill · mcp · session     │
│  embedding/ HttpEmbeddingClient │  │   approval · scheduler · observability│
│  Flyway 迁移                    │  │   vision · tts · email · config       │
│  ⚠ 仅 4 个 @Test                │  │                                       │
└─────────────────────────────────┘  │  impl/ ReactiveAgent(1001行, 上帝类)  │
                                     │   tool/builtin 15 个内置工具          │
                                     │   tool/policy 六层安全策略链          │
                                     │   llm/router 模型路由（策略可插拔）    │
                                     │   memory 压缩/装配/巩固               │
                                     │   skill Markdown 加载+工作流策略      │
                                     │   mcp SSE(415)/Stdio(224) 客户端     │
                                     │   approval · scheduler · session      │
                                     │  依赖：reactor / jackson / snakeyaml  │
                                     │   （零 Spring；httpclient5/aviator   │
                                     │    为死/边缘依赖）                    │
                                     └──────────────┬───────────────────────┘
                                                    │ 仅测试侧依赖
┌───────────────────────────────────────────────────▼──────────────────────┐
│  tianshu-eval（21 文件）评估 harness：replay（CI 门禁）+ live（真实模型  │
│  + LLM judge + freeze），19 个测试全绿                                    │
└──────────────────────────────────────────────────────────────────────────┘

  tianshu-skills：空壳模块——0 源码、SKILL.md 不进 jar、无模块依赖它（死模块）
  根目录 console/：旧 React 前端（33 ts/tsx, 4033 行，2026-08-26 停更，被 ui/ 取代）
```

### core 内部一次请求的流

```
AgentContext ──▶ ReactiveAgent.processStream
                 │  1. SessionManager 获取/创建会话（状态机 RUNNING…）
                 │  2. ContextAssembler 装配（短期历史 + 长期记忆检索，token 预算裁剪）
                 │  3. ToolRegistry 收集工具 → JSON Schema
                 │  4. ModelRouter 选 LlmClient（RoutingStrategy 可插拔）
                 │  5. 工具循环 ◀──────────────────────────────┐
                 │     ├─ ToolPolicyChain 六层判定             │
                 │     │   DENY→失败 / ASK→ApprovalManager 挂起 │
                 │     │   ALLOW→执行（reactive SPI，超时预算） │
                 │     ├─ 工具结果入会话 → AgentHook 广播       │
                 │     └─ 超预算→CompactionService 压缩→fallback│
                 │  6. 终态：AgentEvent 流（TEXT_TOKEN/TOOL_*/  │
                 │        APPROVAL/DONE/ERROR）                 │
                 └─▶ Flux<AgentEvent>
```

## 三、问题清单（按严重度）

### 高（正确性 / 安全 / 会骗人的假实现）

1. **阻塞 JDBC 调用压在 Netty 事件循环上**。
   `AuditController` 的 list/alerts 是同步 `ResponseEntity` 直接调 Repository（AuditController.java:84、124-130）；`McpController` 用 `Mono.fromCallable` 但没 `subscribeOn(boundedElastic)`（McpController.java:37-49）。同模块 ApprovalController.java:53 有正确写法——两种风格并存，说明没有统一约定。高并发下事件循环被 JDBC 卡住会拖垮整个服务。
2. **McpController 无 OBO 身份透传**：不读 `H_USER/H_SCOPES`，经 MCP 执行工具不带调用者身份（对比 ToolController.java:80-95 有 guard+policyChain）。等于 MCP 通道绕过了工具安全链。
3. **MilvusVectorMemory 是伪装成功的 stub**：`store()` 返回 `Mono.empty()`、`delete()` 无操作返回 true、embedding 永远零向量（MilvusVectorMemory.java:46-50、99、118-120）；装配处启动直接抛异常（TianshuAutoConfiguration.java:306-313），类永不实例化，milvus-sdk 依赖纯属死重。建议删类删依赖。
4. **Qdrant 后端残缺却是代码级默认**：`compress()` 直接返回 0（QdrantVectorMemory.java:223-250 "NOT YET IMPLEMENTED"），未覆写 `searchScored/updateMemory/listUserIds`，退回接口默认导致评分退化、记忆维护任务空转；但装配 `matchIfMissing=true`（TianshuAutoConfiguration.java:300）+ 属性默认 `"qdrant"`（TianshuProperties.java:247），与 README 推荐 PGVector 矛盾。默认改 pgvector/none，或补齐三方法。
5. **Session 三份拷贝且语义分歧**：JpaSessionStore（:327-449）、RedisSessionStore（:171-251）、AutoConfiguration 内 SimpleSession（:591-690）各写一遍消息追加/历史/状态机；`markDone()` JPA/Redis 置 SUSPENDED 而 Simple 置 CLOSED；Redis 版 `addUserMessage` 丢弃 attachments（RedisSessionStore.java:201-203）。应在 core 抽 `AbstractSession`/`AbstractMessageStore` 模板。
6. **测试洼地与体量倒挂**：storage 17 个主文件仅 4 个 @Test（PGVector 601 行、7 个 Repository、7 实体零测试，testcontainers 依赖已备好却无 IT）；examples 作为 shipped 应用 0 测试。

### 中（可维护性 / 扩展性税）

7. **ReactiveAgent 是 1001 行上帝类**：会话生命周期、上下文装配、工具循环、策略判定、审批挂起、LLM fallback、压缩、工具 JSON Schema 转换、指标上报全在一个类。且 `executeTool` 与 `waitForApproval` 中"执行+超时+错误处理+持久化"整段重复（ReactiveAgent.java 约 820-900 vs 860-920）。建议拆：ToolExecutor（含审批分支）、ContextPreparation、ToolDefinitionMapper、LlmRecoveryChain。
8. **TianshuAutoConfiguration 731 行配置上帝类**：内含 ~140 行存储实现（SimpleMessageStore/SimpleSession，应移 storage 包）、模型上下文窗口启发式（:177-187）、价格表（:207-228）、API key 环境链（:84-92）等业务逻辑。17 个 config 类中它一个占了全模块 6% 代码。
9. **控制器层厚且无统一约定**：AgentController 里写 agent CRUD 校验/DTO 拼装（:95-205）；ConfigController 含 key 校验大 switch（:283-356）、脱敏、liveValue 映射；无全局异常处理（仅 AgentController 有 @ExceptionHandler，:317-335），IllegalArgumentException 裸 500；返回类型同步/Mono 混用、错误体两种格式、DTO 全手工 Map 拼装。
10. **DefaultSubAgentService(400行) 放错模块**：零 Spring 依赖（只用 ObjectMapper+reactor），implements core 的 SubAgentRunner/BackgroundSpawner，编排逻辑应下沉 core；另含硬编码中文提示词（:240-247、290-300）。
11. **向量后端分居两模块**：PGVector 在 storage，Qdrant/Milvus 在 spring/storage，spring 还要 @EntityScan 反向扫 storage 包（TianshuJpaAutoConfiguration.java:31-32）。同层适配器应同模块。
12. **ScheduledTaskService 事务注解自调用失效**：`@EventListener` 内调 `this.onTaskFired`（:176→190），`@Transactional` 不生效，实际靠内部 `tx.executeWithoutResult` 工作；javadoc 声称 `@TransactionalEventListener(AFTER_COMMIT)` 与代码不符，误导维护者。
13. **热更 key 双清单**：RuntimeConfigService.LIVE_KEYS（:52-65）与 ConfigController.HOT_SETTINGS（:70-84）是两份目录，必然漂移。
14. **死/错位依赖与配置**：core 的 `httpclient5` 全项目零引用（HTTP 全走 JDK HttpClient）；storage 声明 redis 依赖但自己零使用（使用者 RedisSessionStore 在 spring，靠传递依赖）；storage 声明 lombok/testcontainers 无使用；`StorageProperties` 整类零引用且与 spring.datasource.* 重复；`tianshu.websocket.enabled` 被引用但 Properties 无此字段（幽灵配置）；SchedulerProperties.enabled/lockProvider、RoutingProperties.defaultModel 等死配置。

### 低（清理项）

15. **tianshu-skills 空壳死模块**：0 源码、SKILL.md 在模块根不进 jar、无任何模块依赖；技能实际从外部目录加载（application*.yml `skills.root-dir`）。删除或把 reminder 示例移到 examples/skills/。
16. **根目录 console/ 旧前端 4033 行**停更（2026-08-26），已被 examples/ui/ 取代，确认后删除。
17. **examples 名实不符**：模块叫 examples 却是 Dockerfile 唯一打包对象（ExampleController.java:21-27 自述 live endpoints），建议改名 tianshu-app/tianshu-console。
18. 根目录散落入库杂物：`qdrant_check.java` 反射探针、6 个 boot-*.log、logs/*.gz 10 个。
19. ScheduledTaskService 6 个单参重载死方法（:97-155）；RedisSessionStore 自建 `new ObjectMapper()`（:39）而非注入；AgentController.models() 与 availableModels() 逐行重复（:69-80 vs :148-158）；SSE 心跳两处写法（一处读配置一处硬编码 20s）；temperature 等生成参数硬编码。
20. TianshuProperties 554 行手写 getter/setter、13 个内嵌类扁平堆放（可考虑 record/拆分）；注释错位（:296 MemoryProperties 上方标 Scheduler）。
21. core 残留空包 `impl/embed/`（无类）；api/agent 下审批 SPI 与 impl/approval 包不对称（建议 api/approval）。

## 四、分维度评分

| 维度 | 评分 | 说明 |
|---|---|---|
| 分层合理性 | ★★★★☆ | api/impl 分离干净、依赖方向正确；扣分项：向量后端跨模块、SubAgentService 放错层、AutoConfig 塞存储实现 |
| 扩展性（SPI） | ★★★★☆ | 核心能力全接口化、路由策略可插拔；扣分：MCP 工具只暴露 stdio 参数、热更 key 双清单、新增存储后端要改装配 |
| 可维护性 | ★★★☆☆ | 两个上帝类（ReactiveAgent/AutoConfiguration）、三份 Session 拷贝、控制器无统一约定、事务注解误导 |
| 易理解性 | ★★★★☆ | 包命名清晰、core 关键类有结构化 javadoc（工具循环图）、文档齐全；扣分：examples 名实不符、死模块/旧前端干扰 |
| 易用性 | ★★★☆☆ | Starter 引入即用是优点；但默认指向残缺 Qdrant、幽灵配置、Milvus 假成功会误导使用者；API 响应格式不统一 |
| 冗余控制 | ★★★☆☆ | 死依赖 3 处、死模块 1 个、旧前端 4000 行、拷贝代码块多处、死配置 ~6 项 |
| 测试 | ★★★★☆（core）/ ★☆☆☆☆（storage/examples） | core 239 个测试扎实；storage/examples 近乎裸奔 |

## 五、建议的整改顺序

**P0（正确性/安全，改动小收益大）**
1. AuditController/McpController 阻塞调用统一 `subscribeOn(boundedElastic)`；McpController 补身份透传与策略链。
2. 删除 MilvusVectorMemory + milvus 依赖；向量默认改 pgvector（或 none），Qdrant compress 等三方法要么补齐要么启动告警。
3. 清理死依赖：core httpclient5、storage redis/lombok/testcontainers 归位或删除；删 StorageProperties 死配置与 websocket 幽灵配置。

**P1（结构债，建议排期）**
4. core 抽 `AbstractSession`/`AbstractMessageStore`，三份 Session 实现收敛；统一 markDone 语义。
5. 拆 ReactiveAgent：ToolExecutor（含审批/超时/错误处理，消除重复块）、ToolDefinitionMapper、LlmRecoveryChain。
6. 拆 TianshuAutoConfiguration：存储实现移 storage 模块，价格/key/上下文窗口逻辑归 adapter，配置类按域分包。
7. 加全局异常处理器 + 统一 ApiResponse；控制器只做编排。
8. DefaultSubAgentService 下沉 core。

**P2（清理）**
9. 删 tianshu-skills 模块、console/ 旧前端、根目录日志/探针；examples 改名 app/console。
10. 补 storage 集成测试（testcontainers 已在依赖里）：PGVector/JpaSessionStore 语义一致性。
11. LIVE_KEYS/HOT_SETTINGS 合并为单一事实源；修 ScheduledTaskService 事务注解或删注解留编程式事务。
