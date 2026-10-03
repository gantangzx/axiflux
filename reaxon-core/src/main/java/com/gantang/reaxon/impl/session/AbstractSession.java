package com.gantang.reaxon.impl.session;

import com.gantang.reaxon.api.session.Message;
import com.gantang.reaxon.api.session.Session;
import com.gantang.reaxon.api.tool.ToolCall;
import com.gantang.reaxon.api.tool.ToolResult;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Template for {@link Session} implementations. Holds identity, lifecycle state
 * and metadata, and implements the message-mutating methods on top of two
 * extension points so persistent backends only override what differs:
 *
 * <ul>
 *   <li>{@link #messages()} — the message store (in-memory mirror or persistent).</li>
 *   <li>{@link #onStateChange(State)} / {@link #onMetadataChange(String, Object)} —
 *       persistence hooks; default no-ops for transient stores.</li>
 * </ul>
 *
 * <p>Lifecycle semantics are unified here: {@link #markDone()} means a turn has
 * finished and the session <b>can be resumed</b> (state SUSPENDED); {@link #close()}
 * means the session is terminated (state CLOSED). User messages always persist
 * their attachments map.
 */
public abstract class AbstractSession implements Session {

    protected final String sessionId;
    protected final String userId;
    protected final String agentId;
    protected final Instant createdAt;
    protected final Map<String, Object> metadata;
    protected volatile State state = State.ACTIVE;
    protected volatile Instant updatedAt;

    protected AbstractSession(String sessionId, String userId, String agentId,
                              Instant createdAt, Map<String, Object> metadata) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.agentId = agentId;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.metadata = metadata;
    }

    @Override public final String sessionId() { return sessionId; }
    @Override public final String userId() { return userId; }
    @Override public final String agentId() { return agentId; }
    @Override public final State state() { return state; }

    @Override
    public Map<String, Object> metadata() {
        return Collections.unmodifiableMap(metadata);
    }

    @Override
    public void updateMetadata(String key, Object value) {
        metadata.put(key, value);
        onMetadataChange(key, value);
    }

    // ===== messages =====

    @Override
    public void addUserMessage(String content, Map<String, Object> attachments) {
        append(Message.user(content, attachments));
    }

    @Override
    public void addAssistantMessage(String content, List<ToolCall> toolCalls) {
        append(Message.assistant(content, toolCalls != null ? toolCalls : List.of()));
    }

    @Override
    public void addAssistantMessage(String content, List<ToolCall> toolCalls, String reasoning) {
        append(Message.assistant(content, toolCalls != null ? toolCalls : List.of(), reasoning));
    }

    @Override
    public void addToolResult(String callId, ToolResult result) {
        append(Message.tool(callId, result.displayContent()));
    }

    @Override
    public void addSystemMessage(String content) {
        append(Message.system(content));
    }

    /** Append a message and bump the updated timestamp. */
    protected void append(Message message) {
        messages().append(message);
        touch();
    }

    protected final void touch() {
        updatedAt = Instant.now();
    }

    @Override
    public List<Message> getHistory(int lastN) {
        return messages().getRecent(lastN);
    }

    @Override
    public List<Message> getHistorySince(Instant since) {
        return messages().getAll().stream()
            .filter(m -> !m.timestamp().isBefore(since))
            .toList();
    }

    // ===== lifecycle =====

    /** Turn finished; the session remains resumable. */
    @Override
    public void markDone() {
        state = State.SUSPENDED;
        onStateChange(State.SUSPENDED);
    }

    /** Session terminated. */
    @Override
    public void close() {
        state = State.CLOSED;
        onStateChange(State.CLOSED);
    }

    // ===== extension hooks =====

    /** Persist the new state, if the backend is durable. Default no-op. */
    protected void onStateChange(State newState) {
    }

    /** Persist a metadata change, if the backend is durable. Default no-op. */
    protected void onMetadataChange(String key, Object value) {
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
