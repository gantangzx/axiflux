package com.gantang.tianshu.api.observability;

/**
 * Centralized metric names and tag keys for Tianshu observability.
 *
 * <p>Using constants ensures consistent naming across all modules,
 * making Prometheus queries reliable.
 */
public final class MetricNames {

    private MetricNames() {}

    // ─── Agent ────────────────────────────────────────────────────────
    public static final String AGENT_REQUESTS = "tianshu.agent.requests";
    public static final String AGENT_DURATION = "tianshu.agent.duration";
    public static final String AGENT_ITERATIONS = "tianshu.agent.iterations";
    public static final String AGENT_ERRORS = "tianshu.agent.errors";

    // ─── Tools ────────────────────────────────────────────────────────
    public static final String TOOL_CALLS = "tianshu.tool.calls";
    public static final String TOOL_DURATION = "tianshu.tool.duration";
    public static final String TOOL_ERRORS = "tianshu.tool.errors";
    public static final String TOOL_APPROVALS = "tianshu.tool.approvals";
    /** Read-only tool result session-cache hits/misses (tag {@code status}=hit|miss). */
    public static final String TOOL_CACHE = "tianshu.tool.cache";

    // ─── LLM ──────────────────────────────────────────────────────────
    public static final String LLM_CALLS = "tianshu.llm.calls";
    public static final String LLM_DURATION = "tianshu.llm.duration";
    public static final String LLM_TOKENS = "tianshu.llm.tokens";
    public static final String LLM_ERRORS = "tianshu.llm.errors";

    // ─── Memory ───────────────────────────────────────────────────────
    public static final String MEMORY_STORE = "tianshu.memory.store";
    public static final String MEMORY_SEARCH = "tianshu.memory.search";
    public static final String MEMORY_SEARCH_DURATION = "tianshu.memory.search.duration";

    // ─── Scheduler ────────────────────────────────────────────────────
    public static final String SCHEDULER_TASK_RUNS = "tianshu.scheduler.task.runs";
    public static final String SCHEDULER_TASK_DURATION = "tianshu.scheduler.task.duration";
    public static final String SCHEDULER_LOCK_SKIPS = "tianshu.scheduler.lock.skips";

    // ─── Tag keys ─────────────────────────────────────────────────────
    public static final String TAG_TOOL = "tool";
    public static final String TAG_MODEL = "model";
    public static final String TAG_STATUS = "status";
    public static final String TAG_AGENT = "agent";
    public static final String TAG_TASK = "task";
    public static final String TAG_ERROR = "error";
    public static final String TAG_PROVIDER = "provider";
}
