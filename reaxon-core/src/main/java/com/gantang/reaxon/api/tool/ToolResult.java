package com.gantang.reaxon.api.tool;

import java.util.Map;

/**
 * Tool execution result.
 * The `content` field is plain text that gets injected back into the LLM context.
 */
public record ToolResult(
    String callId,
    boolean success,
    String content,
    String errorMessage,
    Map<String, Object> metadata
) {
    public static ToolResult success(String callId, String content) {
        return new ToolResult(callId, true, content, null, Map.of());
    }

    public static ToolResult success(String callId, String content, Map<String, Object> metadata) {
        return new ToolResult(callId, true, content, null, metadata);
    }

    public static ToolResult failure(String callId, String error) {
        return new ToolResult(callId, false, null, error, Map.of());
    }

    public static ToolResult failure(String callId, String error, Map<String, Object> metadata) {
        return new ToolResult(callId, false, null, error, metadata);
    }

    public String displayContent() {
        if (success) return content != null ? content : "(empty result)";
        return "Error: " + (errorMessage != null ? errorMessage : "unknown");
    }
}
