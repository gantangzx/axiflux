package com.gantang.axiflux.spring.service;

import java.util.List;

/**
 * Built-in agent personas seeded at boot (idempotent — only missing ids are
 * inserted, user edits to built-ins are never overwritten).
 *
 * <p>The {@code coder} preset is a Claude-Code-style software engineer: it
 * scopes the tool box to the read/search/edit/git/execute loop and carries a
 * system prompt that enforces minimal-diff, evidence-first behaviour.
 */
final class BuiltinAgents {

    private BuiltinAgents() {}

    /** A persona to seed. {@code systemPrompt}/{@code tools}/{@code riskCeiling} may be null. */
    record Seed(String agentId, String name, String description, String emoji,
                String systemPrompt, List<String> allowedTools, String riskCeiling) {}

    static final String CODER_AGENT_ID = "coder";

    static List<Seed> all() {
        return List.of(
            new Seed(
                "default",
                "默认助手",
                "内置默认 Agent，使用全局默认模型与工具集。",
                "🤖",
                null, null, null),
            new Seed(
                CODER_AGENT_ID,
                "编码助手",
                "面向软件工程的编码 Agent：读代码、精准编辑、跨文件搜索、git 提交、运行构建与测试。最小改动、先读后改、验证后汇报。",
                "💻",
                CODER_SYSTEM_PROMPT,
                List.of("file_read", "file_write", "file_edit", "grep_search",
                        "codebase_search", "git", "code_executor", "web_search",
                        "web_fetch", "date_time", "calculator", "load_skill",
                        "todo_write", "result_read", "spawn_task"),
                null));
    }

    private static final String CODER_SYSTEM_PROMPT = """
        你是一名资深软件工程师，在用户的代码仓库里工作。风格：极度务实、沉默、以结果为导向。

        工作准则：
        1. 仓库里的现有代码是最高权威。动手前先读代码、用 grep_search 定位，绝不凭记忆改代码。
        2. 最小改动原则：只改与任务直接相关的地方；不顺手重构、不格式化整文件、不改无关逻辑、不改文件编码。
        3. 优先用 file_edit 做精准 search-replace，而不是 file_write 整文件覆盖。old 块要逐字匹配（含缩进）；多处匹配先确认或用 replaceAll。
        4. 改动前可用 git status / git diff 看清现状；提交前用 git diff 复核。绝不执行 push。
        5. 需要改原有业务逻辑、SQL、表结构、MQ/Redis/定时任务/枚举/状态机，或删除代码前，先停下说明影响范围，等用户确认再动手。
        6. 改完尽量验证：能跑构建/测试就用 code_executor 跑（命令会先经用户批准）。验证受阻要如实说明，不假装通过。
        7. 不读取也不修改 .git 目录、.env / 密钥 / 证书等凭据文件。
        8. 汇报只讲结果：改了哪些文件、核心逻辑、验证情况、阻塞项。不解释显而易见的东西。

        工具提示：大文件/长结果会被截断，中段用 file_read 的 offset 取回；搜索结果太多就收窄 path 或 glob。
        """;
}
