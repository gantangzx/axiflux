package com.gantang.tianshu.api.agent;

import java.util.Map;

/**
 * Streaming event emitted during processStream().
 * Discriminated by the `type` field.
 */
public record AgentEvent(
    Type type,
    String content,        // text_token | error
    String callId,         // tool_call | tool_result
    String toolName,       // tool_call
    Map<String, Object> arguments,  // tool_call
    String rawPayload      // raw JSON for extensibility
) {
    public enum Type {
        THINKING_TOKEN,
        TEXT_TOKEN,
        TOOL_CALL,
        TOOL_RESULT,
        APPROVAL_REQUIRED,
        DONE,
        ERROR
    }

    // === Factory methods ===

    /** A delta of the model's chain-of-thought ("deep thinking") stream; UI-only, not persisted. */
    public static AgentEvent thinkingToken(String text) {
        return new AgentEvent(Type.THINKING_TOKEN, text, null, null, null, null);
    }

    public static AgentEvent textToken(String text) {
        return new AgentEvent(Type.TEXT_TOKEN, text, null, null, null, null);
    }

    public static AgentEvent toolCall(String callId, String name, Map<String, Object> args) {
        return new AgentEvent(Type.TOOL_CALL, null, callId, name, args, null);
    }

    public static AgentEvent toolResult(String callId, String result) {
        return new AgentEvent(Type.TOOL_RESULT, result, callId, null, null, null);
    }

    public static AgentEvent approvalRequired(String callId, String toolName, String description) {
        return new AgentEvent(Type.APPROVAL_REQUIRED, description, callId, toolName, null, null);
    }

    public static AgentEvent done(AgentResponse response, String json) {
        return new AgentEvent(Type.DONE, response != null ? response.content() : null, null, null, null, json);
    }

    public static AgentEvent error(String message) {
        return new AgentEvent(Type.ERROR, message, null, null, null, null);
    }
}
