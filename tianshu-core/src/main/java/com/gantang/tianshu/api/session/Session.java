package com.gantang.tianshu.api.session;

import com.gantang.tianshu.api.tool.ToolCall;
import com.gantang.tianshu.api.tool.ToolResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A conversation session.
 * Holds short-term memory (message history) and session metadata.
 */
public interface Session {

    String sessionId();
    String userId();
    String agentId();
    State state();

    enum State { ACTIVE, SUSPENDED, CLOSED }

    /** Short-term memory accessor */
    MessageStore messages();

    /** Custom metadata (channel, model, etc.) */
    Map<String, Object> metadata();

    void updateMetadata(String key, Object value);

    /** Add a user message */
    void addUserMessage(String content, Map<String, Object> attachments);

    /** Add an assistant message (LLM text or tool_call) */
    void addAssistantMessage(String content, List<ToolCall> toolCalls);

    /** Add an assistant message with persisted deep-thinking/reasoning text (nullable). */
    default void addAssistantMessage(String content, List<ToolCall> toolCalls, String reasoning) {
        addAssistantMessage(content, toolCalls);
    }

    /** Add a tool execution result */
    void addToolResult(String callId, ToolResult result);

    /** Add a system message (injected into context) */
    void addSystemMessage(String content);

    /** Recent N messages */
    List<Message> getHistory(int lastN);

    /** Messages within a time window */
    List<Message> getHistorySince(Instant since);

    void markDone();
    void close();

    /** Interface for message persistence */
    interface MessageStore {
        void append(Message message);
        List<Message> getRecent(int count);
        List<Message> getAll();
        void clear();
        int size();
    }
}
