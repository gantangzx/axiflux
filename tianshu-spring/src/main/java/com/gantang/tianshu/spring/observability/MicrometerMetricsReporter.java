package com.gantang.tianshu.spring.observability;

import com.gantang.tianshu.api.observability.MetricNames;
import com.gantang.tianshu.api.observability.MetricsReporter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Micrometer-backed {@link MetricsReporter} for Prometheus/Actuator integration.
 *
 * <p>Records counters and timers for agent requests, tool calls, LLM calls,
 * memory operations, and scheduled tasks.
 *
 * <p>Design pattern: <b>Observer</b> — subscribes to metric events from core
 * and records them via Micrometer.
 */
public class MicrometerMetricsReporter implements MetricsReporter, FunnelSink {

    private final MeterRegistry registry;
    private final Map<String, Counter> counterCache = new ConcurrentHashMap<>();

    public MicrometerMetricsReporter(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordAgentRequest(String agentId, String status, Duration duration, int iterations) {
        Timer.builder(MetricNames.AGENT_DURATION)
            .tag(MetricNames.TAG_AGENT, agentId)
            .tag(MetricNames.TAG_STATUS, status)
            .register(registry)
            .record(duration);

        counter(MetricNames.AGENT_REQUESTS,
            MetricNames.TAG_AGENT, agentId,
            MetricNames.TAG_STATUS, status)
            .increment();
    }

    @Override
    public void recordToolCall(String toolName, String status, Duration duration) {
        Timer.builder(MetricNames.TOOL_DURATION)
            .tag(MetricNames.TAG_TOOL, toolName)
            .tag(MetricNames.TAG_STATUS, status)
            .register(registry)
            .record(duration);

        counter(MetricNames.TOOL_CALLS,
            MetricNames.TAG_TOOL, toolName,
            MetricNames.TAG_STATUS, status)
            .increment();
    }

    @Override
    public void recordLlmCall(String model, String provider, String status,
                              Duration duration, int promptTokens, int completionTokens,
                              int cachedPromptTokens) {
        recordLlmCall(model, provider, status, duration, promptTokens, completionTokens);
        if (cachedPromptTokens > 0) {
            Counter.builder(MetricNames.LLM_TOKENS)
                .tag(MetricNames.TAG_MODEL, model)
                .tag("type", "cached")
                .register(registry)
                .increment(cachedPromptTokens);
        }
    }

    @Override
    public void recordToolApproval(String toolName, boolean approved) {
        counter(MetricNames.TOOL_APPROVALS,
            MetricNames.TAG_TOOL, toolName,
            MetricNames.TAG_STATUS, approved ? "approved" : "rejected")
            .increment();
    }

    @Override
    public void recordLlmCall(String model, String provider, String status,
                              Duration duration, int promptTokens, int completionTokens) {
        Timer.builder(MetricNames.LLM_DURATION)
            .tag(MetricNames.TAG_MODEL, model)
            .tag(MetricNames.TAG_PROVIDER, provider)
            .tag(MetricNames.TAG_STATUS, status)
            .register(registry)
            .record(duration);

        counter(MetricNames.LLM_CALLS,
            MetricNames.TAG_MODEL, model,
            MetricNames.TAG_PROVIDER, provider,
            MetricNames.TAG_STATUS, status)
            .increment();

        if (promptTokens > 0 || completionTokens > 0) {
            Counter.builder(MetricNames.LLM_TOKENS)
                .tag(MetricNames.TAG_MODEL, model)
                .tag("type", "prompt")
                .register(registry)
                .increment(promptTokens);
            Counter.builder(MetricNames.LLM_TOKENS)
                .tag(MetricNames.TAG_MODEL, model)
                .tag("type", "completion")
                .register(registry)
                .increment(completionTokens);
        }
    }

    @Override
    public void recordMemoryOp(String operation, String status, Duration duration) {
        Timer.builder(MetricNames.MEMORY_SEARCH_DURATION)
            .tag("operation", operation)
            .tag(MetricNames.TAG_STATUS, status)
            .register(registry)
            .record(duration);
    }

    @Override
    public void recordScheduledTask(String taskName, String status, Duration duration) {
        Timer.builder(MetricNames.SCHEDULER_TASK_DURATION)
            .tag(MetricNames.TAG_TASK, taskName)
            .tag(MetricNames.TAG_STATUS, status)
            .register(registry)
            .record(duration);

        counter(MetricNames.SCHEDULER_TASK_RUNS,
            MetricNames.TAG_TASK, taskName,
            MetricNames.TAG_STATUS, status)
            .increment();
    }

    @Override
    public void recordLockSkip(String taskName) {
        counter(MetricNames.SCHEDULER_LOCK_SKIPS,
            MetricNames.TAG_TASK, taskName)
            .increment();
    }

    @Override
    public void incrementCounter(String name, Map<String, String> tags) {
        if (name == null || name.isBlank()) return;
        String[] flat = new String[tags == null ? 0 : tags.size() * 2];
        if (tags != null) {
            int i = 0;
            for (Map.Entry<String, String> e : tags.entrySet()) {
                flat[i++] = e.getKey();
                flat[i++] = e.getValue() == null ? "" : e.getValue();
            }
        }
        counter(name, flat).increment();
    }

    private Counter counter(String name, String... tags) {
        String key = name + "|" + String.join(",", tags);
        return counterCache.computeIfAbsent(key, k -> {
            var builder = Counter.builder(name);
            for (int i = 0; i < tags.length; i += 2) {
                builder.tag(tags[i], tags[i + 1]);
            }
            return builder.register(registry);
        });
    }

    // ─── Spring configuration ─────────────────────────────────────────

    @Configuration
    @ConditionalOnClass(MeterRegistry.class)
    public static class MetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean(MicrometerMetricsReporter.class)
        public MetricsReporter micrometerMetricsReporter(MeterRegistry registry) {
            return new MicrometerMetricsReporter(registry);
        }
    }
}
