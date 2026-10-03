package com.gantang.reaxon.api.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Immutable snapshot of a single tool execution, used for audit/observability
 * persistence (e.g. the {@code tool_executions} table).
 *
 * @param sessionId  owning session
 * @param userId     owning user (may be null for system/internal calls)
 * @param toolName   tool name, e.g. {@code code_executor}
 * @param callId     tool call correlation id
 * @param params     arguments passed to the tool (may be null)
 * @param success    whether the tool returned a successful result
 * @param duration   wall-clock duration of the execution
 * @param error      error message if failed (null on success)
 * @param executedAt completion timestamp
 */
public record ToolExecutionRecord(
    String sessionId,
    String userId,
    String toolName,
    String callId,
    Map<String, Object> params,
    boolean success,
    Duration duration,
    String error,
    Instant executedAt
) {
    public static ToolExecutionRecord of(String sessionId, String userId, String toolName,
                                         String callId, Map<String, Object> params,
                                         boolean success, Duration duration, String error) {
        return new ToolExecutionRecord(sessionId, userId, toolName, callId, params,
            success, duration, error, Instant.now());
    }
}
