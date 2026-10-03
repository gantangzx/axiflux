package com.gantang.reaxon.api.observability;

/**
 * Centralized metric names and tag keys for Axiflux observability.
 *
 * <p>Using constants ensures consistent naming across all modules,
 * making Prometheus queries reliable.
 */
public final class MetricNames {

    private MetricNames() {}

    // ─── Agent ────────────────────────────────────────────────────────
    public static final String AGENT_REQUESTS = "axiflux.agent.requests";
    public static final String AGENT_DURATION = "axiflux.agent.duration";
    public static final String AGENT_ITERATIONS = "axiflux.agent.iterations";
    public static final String AGENT_ERRORS = "axiflux.agent.errors";

    // ─── Tools ────────────────────────────────────────────────────────
    public static final String TOOL_CALLS = "axiflux.tool.calls";
    public static final String TOOL_DURATION = "axiflux.tool.duration";
    public static final String TOOL_ERRORS = "axiflux.tool.errors";
    public static final String TOOL_APPROVALS = "axiflux.tool.approvals";
    /** Read-only tool result session-cache hits/misses (tag {@code status}=hit|miss). */
    public static final String TOOL_CACHE = "axiflux.tool.cache";

    // ─── LLM ──────────────────────────────────────────────────────────
    public static final String LLM_CALLS = "axiflux.llm.calls";
    public static final String LLM_DURATION = "axiflux.llm.duration";
    public static final String LLM_TOKENS = "axiflux.llm.tokens";
    public static final String LLM_ERRORS = "axiflux.llm.errors";

    // ─── Memory ───────────────────────────────────────────────────────
    public static final String MEMORY_STORE = "axiflux.memory.store";
    public static final String MEMORY_SEARCH = "axiflux.memory.search";
    public static final String MEMORY_SEARCH_DURATION = "axiflux.memory.search.duration";

    // ─── Scheduler ────────────────────────────────────────────────────
    public static final String SCHEDULER_TASK_RUNS = "axiflux.scheduler.task.runs";
    public static final String SCHEDULER_TASK_DURATION = "axiflux.scheduler.task.duration";
    public static final String SCHEDULER_LOCK_SKIPS = "axiflux.scheduler.lock.skips";

    // ─── Tag keys ─────────────────────────────────────────────────────
    public static final String TAG_TOOL = "tool";
    public static final String TAG_MODEL = "model";
    public static final String TAG_STATUS = "status";
    public static final String TAG_AGENT = "agent";
    public static final String TAG_TASK = "task";
    public static final String TAG_ERROR = "error";
    public static final String TAG_PROVIDER = "provider";
}
