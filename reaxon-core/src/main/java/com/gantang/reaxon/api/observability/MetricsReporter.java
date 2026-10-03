package com.gantang.reaxon.api.observability;

import java.time.Duration;
import java.util.Map;

/**
 * Strategy interface for reporting runtime metrics.
 *
 * <p>Core module defines this interface with a no-op default;
 * the Spring module provides a Micrometer-backed implementation.
 * This keeps the core engine zero-dependency on Micrometer.
 *
 * <p>Design pattern: <b>Observer/Strategy</b> — metrics are pushed
 * to the reporter, which decides how to record them.
 */
public interface MetricsReporter {

    /** No-op reporter used when no metrics system is configured. */
    MetricsReporter NOOP = new MetricsReporter() {};

    /** Record an agent request completion. */
    default void recordAgentRequest(String agentId, String status, Duration duration, int iterations) {}

    /** Record a tool execution. */
    default void recordToolCall(String toolName, String status, Duration duration) {}

    /**
     * Record a fully-detailed tool execution for audit persistence.
     * Default delegates to {@link #recordToolCall(String, String, Duration)};
     * persistence-capable reporters override this to write e.g. {@code tool_executions}.
     */
    default void recordToolExecution(ToolExecutionRecord rec) {
        recordToolCall(rec.toolName(), rec.success() ? "success" : "error", rec.duration());
    }

    /** Record a tool approval decision. */
    default void recordToolApproval(String toolName, boolean approved) {}

    /** Record an LLM call. */
    default void recordLlmCall(String model, String provider, String status,
                               Duration duration, int promptTokens, int completionTokens) {}

    /**
     * Record an LLM call with prompt-cache stats. Default delegates to the
     * 6-arg form, discarding cache tokens; metric backends override to record
     * prefix-cache hit volume (P2-2).
     *
     * @param cachedPromptTokens prompt tokens served from the provider cache (≤ promptTokens)
     */
    default void recordLlmCall(String model, String provider, String status,
                               Duration duration, int promptTokens, int completionTokens,
                               int cachedPromptTokens) {
        recordLlmCall(model, provider, status, duration, promptTokens, completionTokens);
    }

    /** Record a memory operation. */
    default void recordMemoryOp(String operation, String status, Duration duration) {}

    /** Record a scheduled task execution. */
    default void recordScheduledTask(String taskName, String status, Duration duration) {}

    /** Record a lock skip. */
    default void recordLockSkip(String taskName) {}

    /** Increment a generic counter. */
    default void incrementCounter(String name, Map<String, String> tags) {}
}
