# AI Agent 论文与行业报告清单（按 tianshu-java 相关度筛选）

> 整理日期：2026-09-04。所有 arXiv 链接已于当日逐篇核验存在性与内容。
> 项目定位：响应式 AI Agent 框架 / 可嵌入 Spring Boot Starter（**LLM 应用层，不做模型训练**）。

## 一、论文（arXiv）

### 🔴 高相关——直接映射架构决策

| 论文 | 链接 | 对本项目的批注 |
|---|---|---|
| **The Rise and Potential of LLM Based Agents: A Survey**（2023-09） | https://arxiv.org/abs/2309.07864 | 经典综述。提出 Brain（规划/记忆/推理）— Perception — Action 框架，与本项目 core 分层（agent/llm/memory/tool）同构。写文档、对外介绍框架时可直接引用这套术语。 |
| **A Survey on LLM based Autonomous Agents**（2023-08） | https://arxiv.org/abs/2308.11432 | 构建—应用—评估三维综述。"评估"维度正是本项目当前空白（eval harness 设计见 `docs/eval-harness-design.md`）。 |
| **CodeAct: Executable Code Actions Elicit Better LLM Agents**（ICML 2024） | https://arxiv.org/abs/2402.01030 | 核心结论：以**可执行代码**作为动作表示，比 JSON/文本工具调用成功率更高、更灵活。**本项目已部分落地**：`CodeExecutorTool` + `CommandSandbox`/`DockerCommandSandbox`（`impl/tool/support/`）。后续可强化：让 agent 在沙箱内组合多步逻辑（循环、条件、数据处理）而非单工具往返。 |
| **More Agents Is All You Need**（TMLR 2024） | https://arxiv.org/abs/2402.05120 | 简单 fan-out：同一任务多 agent 采样 + 投票/批评，性能随 agent 数近似线性提升，且与复杂 prompt 技巧正交。本项目 `SubAgentRunner` + `SpawnTaskTool`（递归深度 ≤2、并发 ≤8）已是多 agent 底座，加"并行采样+聚合"编排模式成本低。注意 token 成本线性增长，适合高价值任务。 |
| **KC-Bench: Knowledge Conflicts in LLM Agents**（2026-09-03 预印本） | https://arxiv.org/abs/2609.03588 | 测 agent 面对**用户指令 vs 参数化知识 vs 工具观测**三方冲突时如何裁决（238 个多轮任务）。与本项目记忆召回（PGVector）+ 工具结果 + 用户输入的冲突场景直接相关。**更值得借的是方法论**：有状态工具 + 用户模拟器 + 确定性环境断言 + 开源自然语言评估器——eval harness 的场景模板。 |
| **NLIP: Natural Language Interaction Protocol and Standard for AI Agents**（2026-09-03 预印本，Ecma International 标准化） | https://arxiv.org/abs/2609.04135 | agent ↔ agent **互操作通信协议**标准。注意分层：MCP 是 agent ↔ 工具（本项目 P1 正在接 SSE 客户端），NLIP 是 agent ↔ agent。子代理/多框架互联方向值得**跟踪标准、暂不实现**（预印本+标准早期，API 未稳）。 |

### 🟡 了解即可

| 论文 | 链接 | 批注 |
|---|---|---|
| **State of AI Agent Memory 2026**（mem0，2026-04，非 arXiv） | https://mem0.ai/blog/state-of-ai-agent-memory-2026 | LoCoMo / LongMemEval / BEAM 已成记忆架构基准；SOTA 92.5/94.4 分 @ ~6900 tokens/query；开放难题：跨会话身份、时序抽象、记忆过期。对标本项目 LongTermMemory / MemoryWritingService 的演进方向。 |
| **Memory for Autonomous LLM Agents: Mechanisms, Evaluation, and Emerging Frontiers**（2026-03 综述） | https://arxiv.org/abs/2603.07670 | 记忆机制系统综述，配合上一篇看。 |
| **VoltAgent/awesome-ai-agent-papers**（GitHub，持续更新） | https://github.com/VoltAgent/awesome-ai-agent-papers | 2026 年 agent 论文索引（工程/记忆/评估/工作流/自主系统分类），跟踪前沿用。 |

### ⚫ 已核验但不相关（原清单中的误荐）

| 论文 | 链接 | 排除原因 |
|---|---|---|
| DRACO: Fine-Grained Credit Assignment with Dynamic Rubrics | https://arxiv.org/abs/2609.04094 | **模型后训练**（RL 信用分配）。本项目调 LLM API，不训练模型。 |
| Terminal-Universe: Trajectories into Scalable Terminal Environments | https://arxiv.org/abs/2609.04148 | 从 agent 轨迹合成**训练环境**，训练侧。 |
| Environment Evolution for Terminal Agents | https://arxiv.org/abs/2609.04128 | 环境协同演化用于**训练**，训练侧。 |
| FLY-EVAL++: Evidence-Driven Evaluation Protocol for Safety-Constrained **Flight** Prediction | https://arxiv.org/abs/2609.04021 | ⚠️ 原清单描述误导："flight" 是**航空飞行轨迹预测**（物理约束环境），与"agent 飞行/漫游"无关。领域不相关。 |

> 注：2609.* 系列均为 **2026-09-03 提交的预印本**（未经同行评审），只能当风向信号，不能作为技术选型依据。

## 二、行业报告（落地与市场数据）

| 报告 | 链接 | 关键数据 / 对本项目的意义 |
|---|---|---|
| **LangChain — State of Agent Engineering**（2026-06，1300+ 从业者调研） | https://www.langchain.com/state-of-agent-engineering | **57.3% 受访者已有 agent 在生产**（大企业 67%）；**质量是头号落地障碍（32%）**，成本关切下降；可观测性采用率 89% 但 **eval 仅 52%**；多模型并行成常态、微调基本没人用；头部用例：客服 26.5%、研究分析 24.4%。→ 本项目观测侧（AgentHook/审计）已齐，**eval 是最大短板**。 |
| **Stanford HAI — 2026 AI Index Report**（有官方中文版） | https://hai.stanford.edu/ai-index/2026-ai-index-report | Technical Performance 章含 agent 基准走势（SWE-bench/GAIA 等），年度最权威数据集。 |
| **Deloitte — State of AI in the Enterprise 2026**（From Ambition to Activation） | https://www.deloitte.com/us/en/about/press-room/state-of-ai-report-2026.html | 85% 企业预期定制 agent 适配自身业务；agent 从实验转向规模化。专文：<https://www.deloitte.com/us/en/insights/topics/emerging-technologies/ai-agents-scaling-faster.html> |
| **Gartner — 2026 Hype Cycle for Agentic AI** | https://www.gartner.com/en/articles/hype-cycle-for-agentic-ai | 智能体技术成熟度曲线，判断技术爬坡位置（正文部分付费墙）。 |
| **McKinsey — State of AI trust in 2026: Shifting to the agentic era** | https://www.mckinsey.com/capabilities/tech-and-ai/our-insights/tech-forward/state-of-ai-trust-in-2026-shifting-to-the-agentic-era | agentic 时代的信任/治理议题（本项目 ToolPolicyChain/EgressGuard/OBO 授权可对标其框架）。 |
| **2026全球及中国AI智能体发展研究报告**（三个皮匠，中文） | 微信公众号搜"2026AI智能体发展研究报告" | 市场规模：2025 全球 113 亿美元（同比翻倍）→ 2030 预计 471 亿；推理成本两年降约 90%；中美路径对比（美国重基础层，中国"以应用定义技术"）。 |

> Menlo Ventures 企业 AI 报告最新为 2025-12 版，2026 agent 专版截至今日未发布。
> 中文权威侧：信通院公开渠道最新为《人工智能发展报告（2024）》，未检索到 2026 新版智能体报告公开发布。

## 三、由报告导出的本项目行动项

1. **补 eval 能力**（LangChain 数据：质量=头号障碍、eval 采用率仅 52%）→ 见 `docs/eval-harness-design.md`。
2. **记忆评测对齐公开基准**（LoCoMo/LongMemEval 思路），记忆模块改动有回归依据。
3. **多模型并行**已是常态：ModelRouter fallback 链已在，可补"同任务多模型采样+择优"。
4. **NLIP/MCP 跟踪**：MCP SSE 客户端 P1 优先；NLIP 仅跟踪标准进展。
5. CodeAct 方向：`CodeExecutorTool` 已有沙箱底座，可让模型在沙箱代码里组合多步操作。
