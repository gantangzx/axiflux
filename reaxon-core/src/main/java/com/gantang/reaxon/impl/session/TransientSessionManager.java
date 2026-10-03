package com.gantang.reaxon.impl.session;

import com.gantang.reaxon.api.session.Message;
import com.gantang.reaxon.api.session.Session;
import com.gantang.reaxon.api.session.SessionManager;
import com.gantang.reaxon.api.tool.ToolCall;
import com.gantang.reaxon.api.tool.ToolResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Purely in-memory {@link SessionManager} for <b>transient sessions</b> —
 * sub-agent runs that exist only as execution containers.
 *
 * <p>Design contract:
 * <ul>
 *   <li>Sessions live in a {@link ConcurrentHashMap} and are evicted on
 *       {@link #delete(String)} (sub-agent runs clean themselves up when the
 *       run finishes, fails, or is cancelled).</li>
 *   <li>{@link #listByUser(String)} / {@link #listActiveByUser(String)} always
 *       return empty: transient sessions are <em>invisible</em> — they never
 *       appear in a user's session list, console sidebar, or history queries.</li>
 *   <li>Nothing is written to JPA/Redis: a gateway restart naturally reclaims
 *       any in-flight state, and there is no durable leak path.</li>
 * </ul>
 *
 * <p>This mirrors how ephemeral sub-agent sessions work in the Node axiflux:
 * the child is a hidden, disposable context; only its final answer crosses back
 * into the parent session (as a tool result), never its message history.
 */
public final class TransientSessionManager implements SessionManager {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @Override
    public Session getOrCreate(String sessionId, String userId, String agentId,
                               Map<String, Object> metadata) {
        Objects.requireNonNull(sessionId, "sessionId");
        return sessions.computeIfAbsent(sessionId, id ->
            new TransientSession(id, userId, agentId, metadata));
    }

    @Override
    public Mono<Void> save(Session session) {
        return Mono.fromRunnable(() -> sessions.put(session.sessionId(), session));
    }

    @Override
    public Mono<Void> delete(String sessionId) {
        return Mono.fromRunnable(() -> sessions.remove(sessionId));
    }

    @Override
    public Optional<Session> get(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /** Transient sessions never appear in user-facing session lists. */
    @Override
    public Flux<Session> listByUser(String userId) {
        return Flux.empty();
    }

    @Override
    public Flux<Session> listActiveByUser(String userId) {
        return Flux.empty();
    }

    /** Minimal in-memory {@link Session} with a synchronized message list. */
    static final class TransientSession implements Session {
        private final String sessionId;
        private final String userId;
        private final String agentId;
        private final List<Message> messages =
            Collections.synchronizedList(new ArrayList<>());
        private final Map<String, Object> metadata;
        private volatile State state = State.ACTIVE;
        private volatile Instant updatedAt = Instant.now();
        /** Single store instance returned by {@link #messages()} (P2-4). */
        private final MessageStore messageStore;

        TransientSession(String sessionId, String userId, String agentId,
                         Map<String, Object> metadata) {
            this.sessionId = sessionId;
            this.userId = userId;
            this.agentId = agentId;
            this.metadata = metadata != null
                ? Collections.synchronizedMap(new HashMap<>(metadata))
                : new ConcurrentHashMap<>();
            this.messageStore = new TransientMessageStore(messages);
        }

        @Override public String sessionId() { return sessionId; }
        @Override public String userId()    { return userId; }
        @Override public String agentId()   { return agentId; }
        @Override public State state()      { return state; }
        /** Unmodifiable view (P2-4): aligns with {@code AbstractSession.metadata()}. */
        @Override public Map<String, Object> metadata() {
            return Collections.unmodifiableMap(metadata);
        }

        @Override
        public void updateMetadata(String key, Object value) {
            metadata.put(key, value);
        }

        @Override
        public MessageStore messages() {
            return messageStore;
        }

        @Override
        public void addUserMessage(String content, Map<String, Object> attachments) {
            // Preserve attachments (P2-4): a sub-agent turn may carry channel
            // attachments; dropping them silently loses data.
            messages.add(Message.user(content, attachments));
            updatedAt = Instant.now();
        }

        @Override
        public void addAssistantMessage(String content, List<ToolCall> toolCalls) {
            messages.add(Message.assistant(content, toolCalls));
            updatedAt = Instant.now();
        }

        @Override
        public void addAssistantMessage(String content, List<ToolCall> toolCalls, String reasoning) {
            messages.add(Message.assistant(content, toolCalls, reasoning));
            updatedAt = Instant.now();
        }

        @Override
        public void addToolResult(String callId, ToolResult result) {
            messages.add(Message.tool(callId, result.displayContent()));
            updatedAt = Instant.now();
        }

        @Override
        public void addSystemMessage(String content) {
            messages.add(Message.system(content));
            updatedAt = Instant.now();
        }

        @Override
        public List<Message> getHistory(int lastN) {
            return messageStore.getRecent(lastN);
        }

        @Override
        public List<Message> getHistorySince(Instant since) {
            return messageStore.getAll().stream()
                .filter(m -> m.timestamp() != null && !m.timestamp().isBefore(since))
                .toList();
        }

        /** Turn finished; the session remains resumable (P2-4: align with AbstractSession). */
        @Override public void markDone() { this.state = State.SUSPENDED; }
        @Override public void close()    { this.state = State.CLOSED; }

        /** Backing store for {@link #messages()}; one instance per session. */
        private static final class TransientMessageStore implements MessageStore {
            private final List<Message> messages;
            TransientMessageStore(List<Message> messages) { this.messages = messages; }
            @Override public void append(Message message) { messages.add(message); }
            @Override public List<Message> getRecent(int count) {
                synchronized (messages) {
                    int from = Math.max(0, messages.size() - count);
                    return new ArrayList<>(messages.subList(from, messages.size()));
                }
            }
            @Override public List<Message> getAll() {
                synchronized (messages) { return new ArrayList<>(messages); }
            }
            @Override public void clear() { messages.clear(); }
            @Override public int size() { return messages.size(); }
        }
    }
}
