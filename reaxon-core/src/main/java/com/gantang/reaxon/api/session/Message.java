package com.gantang.reaxon.api.session;

import com.gantang.reaxon.api.tool.ToolCall;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A single message in a session.
 */
public record Message(
    String id,
    Role role,
    String content,
    List<ToolCall> toolCalls,  // present only for ASSISTANT role
    String toolCallId,         // present only for TOOL role, references the callId
    Map<String, Object> attachments,
    Instant timestamp,
    String reasoning           // ASSISTANT only: persisted deep-thinking (reasoning_content), null otherwise
) {
    public enum Role {
        SYSTEM, USER, ASSISTANT, TOOL
    }

    public static Message user(String content) {
        return new Message(generateId(), Role.USER, content, List.of(), null, Map.of(), Instant.now(), null);
    }

    /** User message carrying channel attachments (images, files, …). */
    public static Message user(String content, Map<String, Object> attachments) {
        return new Message(generateId(), Role.USER, content, List.of(), null,
            attachments != null ? attachments : Map.of(), Instant.now(), null);
    }

    public static Message system(String content) {
        return new Message(generateId(), Role.SYSTEM, content, List.of(), null, Map.of(), Instant.now(), null);
    }

    public static Message assistant(String content, List<ToolCall> toolCalls) {
        return assistant(content, toolCalls, null);
    }

    /** Assistant message with optional persisted reasoning/thinking text. */
    public static Message assistant(String content, List<ToolCall> toolCalls, String reasoning) {
        return new Message(generateId(), Role.ASSISTANT, content,
            toolCalls != null ? toolCalls : List.of(), null, Map.of(), Instant.now(), reasoning);
    }

    public static Message tool(String callId, String content) {
        return new Message(generateId(), Role.TOOL, content, List.of(), callId, Map.of(), Instant.now(), null);
    }

    private static String generateId() {
        // UUID-based ids guarantee uniqueness even when many messages are
        // appended in the same millisecond across parallel tool branches.
        // CompactionService locates summary boundaries by message id, so a
        // collision would hang the horizon on the wrong message (P2-5).
        return "msg_" + java.util.UUID.randomUUID().toString().replace("-", "");
    }
}
