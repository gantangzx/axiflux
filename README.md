# AxiFlux Axiflux

**响应式 AI Agent 框架 Java 实现** — 模块化、可扩展、安全优先。

> AxiFlux：北斗第一星，众星之枢 —— 智能体编排中枢。
> 项目代号 / Maven artifactId：`axiflux-agent` / `axiflux-*`

[![Java 25](https://img.shields.io/badge/Java-25-blue.svg)](https://openjdk.org/projects/jdk/25/)
[![Spring Boot 4.1](https://img.shields.io/badge/Spring%20Boot-4.1-green.svg)](https://spring.io/projects/spring-boot)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![GitHub Discussions](https://img.shields.io/badge/chat-Discussions-5865F2.svg)](https://github.com/gantangzx/axiflux-agent/discussions)

**使用问题**：请到 [GitHub Discussions](https://github.com/gantangzx/axiflux-agent/discussions) 发起讨论；缺陷请提 [Issues](https://github.com/gantangzx/axiflux-agent/issues)。

---

## 🎯 核心特性

- **响应式架构**：基于 Spring WebFlux + Reactor，完全非阻塞
- **纵深防御安全**：6层工具策略链 + SSRF防护 + OBO scope授权
- **多代理支持**：每个 agent 可有独立的 persona、工具白名单、风险上限
- **子代理委派**：阻塞/后台双模式，递归深度防护，caller scopes 原样继承
- **成本优化路由**：按价格表自动选择最便宜的 LLM，forcedModel 用户优先
- **向量记忆**：PGVector（推荐）/ Qdrant 双后端，支持 LLM 压缩
- **调度系统**：SimpleCron 解析 + ShedLock 分布式锁，支持 CRON/DELAY/PERIODIC
- **MCP 协议**：stdio/sse 双传输，Streamable HTTP 无状态模式
- **Skill 框架**：Markdown 定义，支持 Sequential/Parallel/LLM-Guided 三种执行模式

---

## 📦 模块结构

```
axiflux-agent/
├── reaxon-core/      # 核心引擎（零 Spring 依赖，契约 api + 默认实现 impl）
├── axiflux-storage/   # JPA 实体 + Repository + Flyway 迁移（PostgreSQL/Redis）
├── axiflux-registry/  # 工具注册中心（独立服务）
├── axiflux-spring/    # Spring Boot 装配：安全、控制器、Provider、计费
├── axiflux-app/       # 可启动单体（启动类 + 托管前端 dist）
└── reaxon-eval/      # 评测（yaml 用例集）
```

**分层纪律**：依赖单向向下，`reaxon-core` 不依赖 Spring/Web，可被非 Spring 宿主复用。
实测后端主代码 356 个 Java 文件（core 184 / spring 128 / storage 31 / registry 13）。

---

## 🚀 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.gantang.axiflux</groupId>
    <artifactId>axiflux-spring</artifactId>
    <version>0.1.0</version>
</dependency>
```

### 2. 配置 application.yml

```yaml
axiflux:
  # LLM 配置
  llm:
    routing:
      strategy: cost_optimized          # capability | cost_optimized
      default-provider: openai
      default-model: gpt-4o-mini
      costs:
        openai:
          input-per-1k: 0.00015
          output-per-1k: 0.0006
        deepseek:
          input-per-1k: 0.0001
          output-per-1k: 0.0002

  # 向量存储（推荐 PGVector）
  vector:
    provider: pgvector
    embed-url: https://api.openai.com/v1/embeddings
    embed-api-key: ${OPENAI_API_KEY}

  # 数据库
  storage:
    pg-url: jdbc:postgresql://localhost:5432/Axiflux
    pg-user: postgres
    pg-password: ${DB_PASSWORD}

  # 安全（生产环境启用）
  auth:
    enabled: true
    secret: ${AUTH_SECRET}              # 必须！dev secret 会 fail-fast
    require-explicit-scopes: true       # strict 模式，空 scope = 无权限

  # 工具安全策略
  tools:
    policy-mode: all                    # all | whitelist | blacklist
    allow-private-network: false        # 禁止访问内网
    auto-approve: "calculator,date_time"  # 部署级免审批工具
```

### 3. 创建 Spring Boot 主类

```java
@SpringBootApplication
@EnableConfigurationProperties(AxifluxProperties.class)
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### 4. 使用 Agent API

```java
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final Agent agent;
    private final SessionManager sessions;

    @PostMapping
    public Flux<AgentEvent> chat(@RequestBody ChatRequest req) {
        AgentContext ctx = AgentContext.builder()
            .sessionId(req.getSessionId())
            .userId(req.getUserId())
            .currentQuery(req.getMessage())
            .build();

        return agent.processStream(ctx);
    }
}
```

---

## 🔒 安全模型

### 工具策略链（6层，固定顺序）

```
ToolListPolicy（部署级开关：ALL/WHITELIST/BLACKLIST）
    ↓
AgentScopePolicy（persona 收窄：allowedTools/riskCeiling）
    ↓
ScopePolicy（OBO scope 映射：tool:exec, tool:db, tool:net, agent:spawn）
    ↓
RiskLevelPolicy（SAFE/READ/NETWORK→ALLOW，WRITE/DESTRUCTIVE→ASK）
    ↓
ToolCallThrottlePolicy（循环防护：60s 内同参数 3 次 deny）
    ↓
NetworkEgressPolicy（SSRF 防护：EgressGuard 逐跳 + DNS-rebinding）
```

### 关键安全特性

- **fail-fast**：auth.enabled=true 时，dev secret 会导致启动失败
- **strict scope**：`require-explicit-scopes=true` 时，空 scope = 无权限（非通配符）
- **DESTRUCTIVE 强制审批**：预算策略永不覆盖，必须人工审批
- **MCP 无交互审批**：requiresApproval 工具直接拒绝
- **子代理继承 caller scopes**：空集传空集，不放大为通配符（防 prompt 注入提权）

---

## 💾 向量存储选择

| 后端 | compress 支持 | 生产推荐 | 说明 |
|------|--------------|---------|------|
| **PGVector** | ✅ 完整 | ⭐⭐⭐⭐⭐ | 推荐。支持 LLM 摘要压缩、HNSW 索引、事务安全 |
| **Qdrant** | ❌ 未实现 | ⭐⭐⭐⭐ | 可用。compress() 返回 0，仅存储和搜索 |
| **Milvus** | ❌ stub | ❌ 不推荐 | 实验性。启动即抛异常拦截 |

**推荐配置**：
```yaml
axiflux:
  vector:
    provider: pgvector
    dimension: 1536
    metric: cosine
    embed-url: https://api.openai.com/v1/embeddings
```

---

## 🔧 内置工具（约22个，按条件装配）

| 工具名 | 风险等级 | 需审批 | 说明 |
|--------|---------|--------|------|
| `calculator` | SAFE | ❌ | 基于 AviatorScript 的安全算术表达式 |
| `date_time` | SAFE | ❌ | 时区感知的日期时间操作 |
| `file_read` | READ | ❌ | PathGuard symlink-safe 校验 |
| `file_write` | WRITE | ✅ | PathGuard 校验 + mode(overwrite/append/create_new) |
| `file_edit` | WRITE | ✅ | 定点文本编辑，受同一文件根目录监禁 |
| `git` | READ/WRITE | 视动作 | 读动作 status/diff/log，写动作受限 |
| `grep_search` | READ | ❌ | 代码内容正则检索 |
| `codebase_search` | READ | ❌ | 基于代码索引的语义检索 |
| `http_client` | NETWORK | ❌ | EgressGuard 逐跳 SSRF 防护 |
| `web_fetch` | NETWORK | ❌ | HTML to text + EgressGuard |
| `web_search` | NETWORK | ❌ | DuckDuckGo（自动重试 + HTML 结构变更检测） |
| `database_query` | READ | ❌ | 只读 SQL（仅 SELECT/WITH），硬 LIMIT |
| `code_executor` | DESTRUCTIVE | ✅ | secret scrubbing + UTF-8/GBK 兜底 + NUL 剥离 |
| `email_send` | WRITE | ✅ | SMTP STARTTLS |
| `image_analyze` | READ | ❌ | OpenAI Vision API |
| `tts` | READ | ❌ | OpenAI TTS API |
| `mcp_client` | WRITE | ❌ | MCP stdio/sse client |
| `spawn_task` | DESTRUCTIVE | ✅（强制） | 子代理委派，depth≤2，并发≤8 |
| `schedule_task` | NETWORK/WRITE | — | 创建定时/周期任务（门禁：Pro） |
| `todo_write` | SAFE | ❌ | 会话级任务清单 |
| `load_skill` | SAFE | ❌ | 加载并执行 Skill |
| `result_read` | SAFE | ❌ | 读取 `ref://` 侧边存储的超大工具结果 |

**自定义工具**：
```java
@Bean
public Tool myCustomTool() {
    return new Tool() {
        @Override public String name() { return "my_tool"; }
        @Override public String description() { return "My custom tool"; }
        @Override public JsonNode parameters() { return SchemaSupport.parse("..."); }
        @Override public ToolResult execute(String callId, Map<String, Object> params, AgentContext ctx) {
            // 实现逻辑
        }
    };
}
```

---

## 📡 API 端点

### REST API

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/v1/chat` | POST | 同步对话 |
| `/api/v1/chat/stream` | POST | SSE 流式对话（20s 心跳） |
| `/api/v1/sessions` | GET/POST | 会话管理（GET 分页：`?page=0&size=100`） |
| `/api/v1/tools` | GET | 工具列表 |
| `/api/v1/tools/{name}/invoke` | POST | 直调工具（绕过 LLM） |
| `/api/v1/agents` | GET/POST/PUT/DELETE | Agent persona 管理 |
| `/api/v1/scheduler/tasks` | GET/POST/DELETE | 调度任务管理 |
| `/api/v1/auth/token` | POST | OBO token 签发（auth.enabled=true 时） |
| `/api/v1/approvals` | GET/POST | 审批队列管理 |
| `/api/v1/config` | GET/PUT | 配置热更 |
| `/api/v1/memory/search` | POST | 长期记忆搜索 |
| `/api/v1/mcp/tools` | GET | MCP tools/list |
| `/api/v1/mcp/call` | POST | MCP tools/call |

> 计费 / 订阅、用量统计、组织成员、运营后台等端点属于闭源商业层，不在开源版中；
> 开源控制台访问这些路由时会显示「企业版功能」引导卡片。

### WebSocket

```javascript
const ws = new WebSocket('ws://localhost:8080/Axiflux/ws', 'Axiflux');
ws.send(JSON.stringify({
    type: 'chat',
    sessionId: 'session-123',
    userId: 'user-456',
    content: 'Hello!'
}));
```

### MCP Streamable HTTP

```bash
POST /mcp
Content-Type: application/json
MCP-Protocol-Version: 2024-11-05

{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": { "name": "calculator", "arguments": { "expression": "2+2" } }
}
```

---

## 🎨 Skill 框架

### 定义 Skill（Markdown）

```markdown
---
name: my-workflow
description: A sample workflow
triggers: ["分析", "analyze"]
requiredTools: ["calculator", "database_query"]
executionMode: sequential
---

## Role
You are a data analyst.

## Steps

### Step 1: Calculate metrics
- type: tool
- target: calculator
- params:
    expression: "${input}"

### Step 2: Summarize
- type: llm
- target: "Based on the result ${step1}, write a summary."
```

### 执行 Skill

```java
SkillExecutor executor = ...;
SkillResult result = executor.execute("my-workflow", AgentContext.of("计算增长率"));
```

---

## 📊 可观测性

- **Micrometer 集成**：`agent.request.count`、`tool.execution.time`、`llm.latency`
- **JPA 审计**：`tool_executions` 表（params 脱敏）
- **健康检查**：`/actuator/health/llm`、`/actuator/health/memory`
- **访问日志**：RequestLoggingFilter（排除 actuator/static）
- **TraceId**：`X-Trace-Id` 响应头 + MDC + Reactor Context 桥接

---

## 🧪 测试

```bash
# 运行所有测试
./mvnw test

# 运行单个模块测试
./mvnw test -pl reaxon-core
./mvnw test -pl axiflux-spring

# 覆盖率报告
./mvnw jacoco:report
```

**测试规模**：全仓库测试方法约 **1140 个**（按 `@Test`/`@ParameterizedTest` 实测，2026-09-22）。

> **注意**：持久层与 Flyway 迁移的验证以单元测试为主；`@Version` 乐观锁及涉及计费/订阅的
> 迁移（V14/V15/V20）建议在真实 Postgres + Testcontainers 上补集成测试，尤其是 Stripe 收款闭环。

> **构建提示**：不要用 `-Dmaven.compiler.useIncrementalCompilation=false` 来"强制全量编译" ——
> 该参数语义相反（只编译变更文件），会让本该失败的构建报 SUCCESS。需要可信结果时用
> `./mvnw clean test`，或删除各模块 `target/classes` 与 `target/maven-status` 后重新构建。
> 同理，`-pl <module>` 会从本地仓库解析上游模块的**旧 jar**，改动了 core/storage 时
> 必须先 `./mvnw install -pl reaxon-core,axiflux-storage -DskipTests`。

---

## 📖 文档

- [评测框架设计](docs/eval-harness-design.md)
- [技能注册中心](docs/registry.md)
- [离线 / 私有化安装](docs/offline-install.md)
- [高可用部署](docs/ha-deployment.md)
- [备份与恢复](docs/backup-restore.md)
- [自助排障](docs/self-service-troubleshooting.md)

---

## 📄 许可证

MIT License — 详见 [LICENSE](LICENSE)

---

## 🙏 致谢

- [LangChain4j](https://github.com/langchain4j/langchain4j) — LLM 集成
- [Qdrant](https://qdrant.tech/) — 向量数据库
- [ShedLock](https://github.com/lukas-krecan/ShedLock) — 分布式锁
- [Spring Boot](https://spring.io/projects/spring-boot) — 应用框架

---

**文档版本**：2026-09-27  
**项目状态**：v0.1.0-SNAPSHOT，开源社区版（框架 / SDK + 单机控制台）
