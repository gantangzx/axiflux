package com.gantang.tianshu.impl.session;

import com.gantang.tianshu.api.session.Session;
import com.gantang.tianshu.api.session.SessionManager;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Purely in-memory {@link SessionManager} — no database, no Spring. Used as the
 * default fallback when no persistent backend (JPA/Redis) is wired, and by
 * tests/scenario runners.
 */
public class InMemorySessionManager implements SessionManager {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @Override
    public Session getOrCreate(String sessionId, String userId, String agentId, Map<String, Object> metadata) {
        return sessions.computeIfAbsent(sessionId, id -> new InMemorySession(
            id, userId, agentId, Instant.now(), new HashMap<>(metadata)));
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

    @Override
    public Flux<Session> listByUser(String userId) {
        return Flux.fromIterable(sessions.values().stream()
            .filter(s -> s.userId().equals(userId))
            .toList());
    }

    @Override
    public Flux<Session> listActiveByUser(String userId) {
        return Flux.fromIterable(sessions.values().stream()
            .filter(s -> s.userId().equals(userId) && s.state() == Session.State.ACTIVE)
            .toList());
    }

    /** Transient in-memory session backed by {@link InMemoryMessageStore}. */
    public static class InMemorySession extends AbstractSession {

        private final InMemoryMessageStore messageStore = new InMemoryMessageStore();

        public InMemorySession(String sessionId, String userId, String agentId,
                               Instant createdAt, Map<String, Object> metadata) {
            super(sessionId, userId, agentId, createdAt,
                metadata != null ? new ConcurrentHashMap<>(metadata) : new ConcurrentHashMap<>());
        }

        @Override
        public InMemoryMessageStore messages() {
            return messageStore;
        }
    }
}
