# 编码专用 Agent 开发路线图

> **✅ 状态：已交付（2026-09-07 对账）**
> P0（file_edit/GitTool/diff 渲染）、P1（grep_search、编码上下文裁剪、文件安全加固）、
> P2.1（codebase_search 语义索引，PGVector）、P2.2（DockerCommandSandbox：`--rm --network=none --cap-drop=ALL`
> + CPU/内存限制，docker 不可用时回退本地）、P2.3（coder 预置 Agent）均已落地。
> 剩余衍生项见 `agent-frontier-roadmap.md` P1-3（工具结果句柄化）、P2-3（CodeAct 引导 + per-user 工作区）。
>
> 基于 Tianshu ReactiveAgent 架构，补齐与 Claude Code / Cursor 的核心差距。
> 目标：让 Agent 能真正「干活」——读代码、改代码、提交代码，而不是只能聊天。

---

## 总体架构

```
┌──────────────────────────────────────────────┐
│                 Console UI                    │
│  ToolCard（diff 渲染）| 附件上传 | 会话管理    │
└──────────────┬───────────────────────────────┘
               │ SSE / REST
┌──────────────▼───────────────────────────────┐
│           ReactiveAgent (已有)                │
│  LLM 路由 → 工具调用 → 审批门 → 执行 → 循环   │
└──────┬──────────┬──────────┬─────────────────┘
       │          │          │
  ┌────▼───┐ ┌───▼───┐ ┌───▼────────┐
  │ 文件操作│ │ Git   │ │ 代码搜索    │
  │ file_edit│ │ status │ │ grep_search │
  │ file_read│ │ diff   │ │ codebase_   │
  │ file_write│ │ commit │ │ search(P2)  │
  └─────────┘ └───────┘ └────────────┘
```

---

## P0：立刻做（2 天，不做没法用）

### 0.1 智能文件编辑工具 `file_edit`

**现状**：只有 `file_write`，只能整体覆盖文件。LLM 每次要输出整个文件，token 浪费 10 倍，且容易出错。

**目标**：实现 search-replace 模式的编辑工具，对标 Claude Code 的 `EditBlock`。

```
工具名：file_edit
参数：
  path: string       ← 文件路径
  old: string        ← 要替换的原文段（必须唯一匹配）
  new: string        ← 替换后的内容
  options?: {
    confirmBefore?: boolean  ← 高危操作先展示 diff
  }
返回：
  success: true
  diff: unified diff 格式的变更内容
  matchedLines: 匹配的行号范围
```

**实现步骤**：

1. 新建 `FileEditTool.java`，实现 `Tool` 接口
2. 核心逻辑：
   - 读文件 → 在内容中查找 `old` 字符串（精确匹配）
   - 唯一匹配 → 替换 → 写回
   - 多匹配 → 报错要求 LLM 提供更多上下文
   - 无匹配 → 报错，返回文件末尾几行辅助定位
3. 风险等级：`DESTRUCTIVE`（需要审批）
4. 注册到 `DefaultToolRegistry`
5. 前端 `ToolCard` 检测 `file_edit` 结果，自动渲染 diff（绿色+红色行）

**文件清单**：

| 文件 | 操作 | 说明 |
|---|---|---|
| `tianshu-core/.../tool/builtin/FileEditTool.java` | 新建 | 核心工具 |
| `tianshu-core/.../tool/builtin/FileEditToolTest.java` | 新建 | 单元测试 |
| `tianshu-spring/.../config/BuiltInToolsConfiguration.java` | 修改 | 注册 Bean |
| `tianshu-app/ui/src/toolview.tsx` | 修改 | diff 渲染分支 |

### 0.2 Git 集成工具（3 个）

**现状**：完全没有 Git 工具，Agent 无法感知版本状态。

**目标**：让 Agent 能查看状态、对比差异、提交代码。

#### 0.2.1 `git_status`

```
工具名：git_status
参数：
  workingDir?: string  ← 项目目录（默认当前）
返回：
  branch: string
  ahead: number
  behind: number
  unstaged: string[]   ← 未暂存的文件列表
  staged: string[]     ← 已暂存的文件列表
  conflicts: string[]  ← 冲突文件
```

#### 0.2.2 `git_diff`

```
工具名：git_diff
参数：
  path?: string        ← 指定文件（可选，不指定则全量）
  staged?: boolean     ← 是否查看已暂存的 diff
  context?: number     ← 上下文行数（默认 3）
返回：unified diff 字符串
```

#### 0.2.3 `git_commit`

```
工具名：git_commit
参数：
  message: string      ← 提交信息
  files?: string[]     ← 指定文件（可选，不指定则全部）
返回：
  commitHash: string
  summary: string
```

**文件清单**：

| 文件 | 操作 | 说明 |
|---|---|---|
| `tianshu-core/.../tool/builtin/GitStatusTool.java` | 新建 | |
| `tianshu-core/.../tool/builtin/GitDiffTool.java` | 新建 | |
| `tianshu-core/.../tool/builtin/GitCommitTool.java` | 新建 | |
| `tianshu-spring/.../config/BuiltInToolsConfiguration.java` | 修改 | 注册 3 个 Bean |

### 0.3 前端 diff 渲染

**现状**：`ToolCard` 对 `file_edit`/`git_diff` 的结果直接显示原始文本，用户看不清变更。

**目标**：检测结果中 `---`/`+++` 开头的 unified diff 格式，自动渲染为对比视图。

**实现**：在 `toolview.tsx` 的 `Section` 组件中，如果内容匹配 diff 格式，切换到 diff 渲染模式：

```
- 旧代码行（红色背景）
+ 新代码行（绿色背景）
  上下文行（正常）
@@ 块头（灰色斜体）
```

**文件清单**：

| 文件 | 操作 | 说明 |
|---|---|---|
| `tianshu-app/ui/src/toolview.tsx` | 修改 | 加 `DiffSection` 组件 |
| `tianshu-app/ui/src/index.css` | 修改 | diff 颜色样式 |

---

## P1：一周内（体验飞跃）

### 1.1 代码搜索工具 `grep_search`

**现状**：Agent 只能 `file_read` 一个个文件找代码，跨文件搜索靠猜。

**目标**：基于 ripgrep 的全文搜索，毫秒级返回结果。

```
工具名：grep_search
参数：
  pattern: string      ← 搜索模式（支持正则）
  path?: string        ← 限定目录
  fileTypes?: string[] ← 限定文件类型，如 ["java", "ts"]
  maxResults?: number  ← 最多返回结果（默认 50）
  context?: number     ← 上下文行数
返回：
  results: [{
    path: string
    line: number
    column: number
    match: string
    contextBefore: string[]
    contextAfter: string[]
  }]
  total: number
```

**文件清单**：

| 文件 | 操作 | 说明 |
|---|---|---|
| `tianshu-core/.../tool/builtin/GrepSearchTool.java` | 新建 | |
| `tianshu-spring/.../config/BuiltInToolsConfiguration.java` | 修改 | 注册 |

### 1.2 上下文裁剪优化

**现状**：编码会话 token 消耗大，`CompactionService` 可能不够激进。

**目标**：编码场景专属的上下文策略：

- 工具调用历史 → 压缩为摘要，保留最后一次调用的完整参数
- 文件内容 → 只保留最近读过的 3 个文件，其余摘要
- Git diff 输出 → 截断到 200 行，标记「共 N 行变更」

**文件清单**：

| 文件 | 操作 | 说明 |
|---|---|---|
| `tianshu-core/.../agent/llm/CompactionService.java` | 修改 | 加编码专用策略 |

### 1.3 文件操作安全增强

**现状**：`FileWriteTool` 只有白名单目录检查，没有文件类型限制。

**目标**：

- 禁止写入二进制文件（`.class`、`.jar`、`.exe`）
- 禁止写入 `.git` 目录
- 文件大小限制（读 max 1MB，写 max 100KB）
- 敏感文件保护（`application-*.yml`、`secrets.*` 需要额外审批）

---

## P2：下个月（长期价值）

### 2.1 代码向量索引

**现状**：有 `QdrantVectorMemory`，但只用于对话记忆，不索引代码。

**目标**：让 Agent 能理解整个代码库的结构。

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│ 文件变更检测  │────▶│  Chunk 分片   │────▶│  嵌入向量化   │
└──────────────┘     └──────────────┘     └───────┬──────┘
                                                  │
                                           ┌──────▼──────┐
                                           │  Qdrant 存储  │
                                           │ code_index   │
                                           └──────┬──────┘
                                                  │
┌──────────────┐     ┌──────────────┐     ┌──────▼──────┐
│  用户提问     │────▶│  语义搜索     │────▶│ 返回相关代码  │
└──────────────┘     └──────────────┘     └─────────────┘
```

**实现步骤**：

1. `CodebaseIndexer.java`：扫描项目目录，分片（每 50 行 + 文件名 + 包名作为元数据）
2. `CodebaseIndexer.java`：调用 LLM Provider 的 embeddings API 生成向量
3. `CodebaseIndexer.java`：存入 Qdrant `code_index` collection
4. `CodebaseSearchTool.java`：语义搜索工具，返回 Top-K 相关代码段
5. 定时任务：文件变更时增量更新索引

**文件清单**：

| 文件 | 操作 | 说明 |
|---|---|---|
| `tianshu-core/.../coding/CodebaseIndexer.java` | 新建 | 索引构建 |
| `tianshu-core/.../coding/CodebaseSearchTool.java` | 新建 | 搜索工具 |
| `tianshu-spring/.../config/CodingAgentConfiguration.java` | 新建 | Spring 配置 |
| `tianshu-core/.../coding/ChunkStrategy.java` | 新建 | 分片策略 |

### 2.2 Docker 沙箱

**现状**：`CommandSandbox` 接口已定义，`DockerCommandSandbox` 未实现，`code_executor` 默认用 `LocalCommandSandbox` 裸跑。

**目标**：容器化执行，隔离恶意命令。

**实现**：

```java
public class DockerCommandSandbox implements CommandSandbox {
    @Override
    public String name() { return "docker"; }

    @Override
    public ProcessBuilderSpec build(boolean hostWindows, String command, Path workDir) {
        // docker exec -i <containerId> sh -c "<command>"
        List<String> argv = List.of(
            "docker", "exec", "-i",
            "--workdir", workDir.toString(),
            codingContainerId,
            "sh", "-c", command
        );
        return new ProcessBuilderSpec(argv, workDir);
    }

    @Override
    public boolean isAvailable() {
        // 检查 docker CLI 是否存在
    }
}
```

容器管理：
- 按会话 ID 创建容器（`docker run -d --name coding-<sessionId>`）
- 挂载项目目录为只读
- 限制：CPU 2 核、内存 4G、无网络、`--read-only`、`--cap-drop ALL`
- 会话关闭时销毁容器

**文件清单**：

| 文件 | 操作 | 说明 |
|---|---|---|
| `tianshu-spring/.../tool/support/DockerCommandSandbox.java` | 新建 | |
| `tianshu-spring/.../config/CodingAgentConfiguration.java` | 修改 | 注册沙箱 |

### 2.3 编码 Agent 预置定义

**现状**：只有一个 `default` agent，没有专门的编码 Agent。

**目标**：系统预置一个 `coder` Agent，开箱即用。

```json
{
  "agentId": "coder",
  "name": "编码助手",
  "emoji": "💻",
  "systemPrompt": "你是一个编码助手...",
  "allowedTools": [
    "file_read", "file_edit", "file_write",
    "git_status", "git_diff", "git_commit",
    "grep_search", "codebase_search",
    "code_executor"
  ],
  "riskCeiling": "DESTRUCTIVE",
  "builtin": true
}
```

**文件清单**：

| 文件 | 操作 | 说明 |
|---|---|---|
| `tianshu-spring/.../config/CodingAgentConfiguration.java` | 修改 | 预置定义 |

---

## 依赖关系图

```
P0 ─────────────────────────────────────────────
  │
  ├── file_edit ─────── 无依赖，可以直接做
  │
  ├── git_status ────── 需要 git CLI（系统自带）
  ├── git_diff ──────── 需要 git CLI
  ├── git_commit ────── 需要 git CLI + 审批门（已有）
  │
  └── diff 渲染 ─────── 依赖 file_edit + git_diff 的输出格式

P1 ─────────────────────────────────────────────
  │
  ├── grep_search ───── 需要 ripgrep（scoop install ripgrep）
  │
  ├── 上下文裁剪 ────── 依赖 P0 工具调用模式稳定
  │
  └── 安全增强 ──────── 独立

P2 ─────────────────────────────────────────────
  │
  ├── 代码向量索引 ──── 需要 Qdrant（已有）+ embeddings API
  ├── Docker 沙箱 ───── 需要 Docker（系统安装）
  └── 编码 Agent 预置 ─ 依赖所有 P0/P1 工具
```

---

## 技术选型明细

| 需求 | 选型 | 理由 |
|---|---|---|
| 文件编辑 | search-replace 模式 | Claude Code 验证过的最稳定方式 |
| 全文搜索 | ripgrep | 比 grep 快 100 倍，正则支持好 |
| 向量数据库 | Qdrant（已有） | 不用额外部署 |
| 嵌入模型 | LLM Provider 的 embeddings API | 不需要额外 GPU |
| 代码沙箱 | Docker | 通用，轻量 |
| diff 格式 | Unified diff | 标准格式，前端渲染简单 |
| 前端框架 | React + antd（已有） | 不用引入新依赖 |

---

## 验收标准

### P0 验收

- [ ] `file_edit` 能精准替换文件中指定代码段
- [ ] 多匹配时给出明确报错
- [ ] 无匹配时返回文件上下文辅助定位
- [ ] `git_status` 返回分支、变更文件列表
- [ ] `git_diff` 返回 unified diff
- [ ] `git_commit` 能成功提交并返回 hash
- [ ] 前端 diff 渲染正确显示绿/红行
- [ ] 所有工具通过 `npx tsc --noEmit` + `npm run build`
- [ ] 所有工具通过 `mvn test`

### P1 验收

- [ ] `grep_search` 搜索 10 万行项目 < 1s
- [ ] 长上下文编码会话 token 消耗降低 40%+
- [ ] 文件操作安全策略生效，写入 .git 目录被拒绝

### P2 验收

- [ ] 代码向量索引覆盖项目全部源文件
- [ ] `codebase_search("用户登录逻辑")` 返回正确文件
- [ ] Docker 沙箱内执行命令，宿主机不受影响
- [ ] 编码 Agent 开箱即用，无需额外配置

---

## 附录：QClaw / Tianshu 已有基础设施

| 组件 | 已有 | 备注 |
|---|---|---|
| ReactiveAgent | ✅ | 核心循环 |
| 15 个内置工具 | ✅ | file_read/write, code_executor, web_search 等 |
| CommandSandbox 接口 | ✅ | 已定义，LocalCommandSandbox 实现存在 |
| DockerCommandSandbox | ❌ | 接口有，实现为空 |
| 审批门 | ✅ | ToolExecutor + ApprovalManager |
| 上下文压缩 | ✅ | CompactionService |
| 模型路由 | ✅ | ModelRouter + routeChain |
| 向量记忆 | ✅ | QdrantVectorMemory（对话记忆） |
| 代码向量索引 | ❌ | 需要新建 CodebaseIndexer |
| Session 持久化 | ✅ | JPA + H2/PostgreSQL |
| 流式 SSE | ✅ | 前端 + 后端完整 |
| Console UI | ✅ | React + antd + ToolCard |
| AgentDefinition | ✅ | 支持 systemPrompt、allowedTools、riskCeiling |
| Git 工具 | ❌ | 完全不存在 |
| file_edit 工具 | ❌ | 只有 file_write 整体覆盖 |
| grep_search 工具 | ❌ | 不存在 |
| diff 前端渲染 | ❌ | 不存在 |