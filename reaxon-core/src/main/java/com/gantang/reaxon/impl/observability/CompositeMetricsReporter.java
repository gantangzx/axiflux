package com.gantang.reaxon.impl.observability;

import com.gantang.reaxon.api.observability.MetricsReporter;
import com.gantang.reaxon.api.observability.ToolExecutionRecord;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * A {@link MetricsReporter} that fans every event out to a list of delegates.
 *
 * <p>Used by the Spring auto-configuration to combine the Micrometer reporter
 * with persistence reporters (e.g. JPA {@code tool_executions}) without
 * forcing either to depend on the other.
 *
 * <p>Design pattern: <b>Composite</b>.
 */
public final class CompositeMetricsReporter implements MetricsReporter {

    private final List<MetricsReporter> delegates;

    public CompositeMetricsReporter(Collection<MetricsReporter> delegates) {
        this.delegates = List.copyOf(delegates == null ? List.of() : delegates);
    }

    public static MetricsReporter of(MetricsReporter... reporters) {
        List<MetricsReporter> all = new ArrayList<>();
        for (MetricsReporter r : reporters) {
            if (r != null && r != NOOP) all.add(r);
        }
        return all.isEmpty() ? NOOP : new CompositeMetricsReporter(all);
    }

    @Override public void recordAgentRequest(String agentId, String status, Duration duration, int iterations) {
        delegates.forEach(d -> d.recordAgentRequest(agentId, status, duration, iterations));
    }
    @Override public void recordToolCall(String toolName, String status, Duration duration) {
        delegates.forEach(d -> d.recordToolCall(toolName, status, duration));
    }
    @Override public void recordToolExecution(ToolExecutionRecord rec) {
        delegates.forEach(d -> d.recordToolExecution(rec));
    }
    @Override public void recordToolApproval(String toolName, boolean approved) {
        delegates.forEach(d -> d.recordToolApproval(toolName, approved));
    }
    @Override public void recordLlmCall(String model, String provider, String status,
                                       Duration duration, int promptTokens, int completionTokens) {
        delegates.forEach(d -> d.recordLlmCall(model, provider, status, duration, promptTokens, completionTokens));
    }
    @Override public void recordLlmCall(String model, String provider, String status,
                                       Duration duration, int promptTokens, int completionTokens,
                                       int cachedPromptTokens) {
        delegates.forEach(d -> d.recordLlmCall(model, provider, status, duration,
            promptTokens, completionTokens, cachedPromptTokens));
    }
    @Override public void recordMemoryOp(String operation, String status, Duration duration) {
        delegates.forEach(d -> d.recordMemoryOp(operation, status, duration));
    }
    @Override public void recordScheduledTask(String taskName, String status, Duration duration) {
        delegates.forEach(d -> d.recordScheduledTask(taskName, status, duration));
    }
    @Override public void recordLockSkip(String taskName) {
        delegates.forEach(d -> d.recordLockSkip(taskName));
    }
    @Override public void incrementCounter(String name, Map<String, String> tags) {
        delegates.forEach(d -> d.incrementCounter(name, tags));
    }
}
