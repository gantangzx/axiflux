package com.gantang.reaxon.impl.session;

import com.gantang.reaxon.api.agent.BackgroundSpawner;
import com.gantang.reaxon.api.session.Session;
import com.gantang.reaxon.api.session.SessionDeleteBroadcaster;
import com.gantang.reaxon.api.session.SessionManager;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * {@link SessionManager} that routes sub-agent sessions to an in-memory
 * {@link TransientSessionManager} while passing every other session through to
 * the persistent delegate (JPA / Redis / in-memory fallback).
 *
 * <p>Routing is decided by the session id prefix ({@code "sub:"}, matching
 * {@code BackgroundSpawner} child-session ids) or, on {@link #getOrCreate}, by
 * the {@code kind=subagent} metadata marker. List operations are always
 * delegated: transient sessions are invisible by contract.
 *
 * <p>The transient store is internal to this router and never exposed as a
 * bean, so the rest of the application cannot accidentally persist into or
 * read from it.
 */
public final class RoutingSessionManager implements SessionDeleteBroadcaster {

    /** Session ids with this prefix are disposable sub-agent sessions. */
    public static final String TRANSIENT_PREFIX = BackgroundSpawner.SUBAGENT_SESSION_PREFIX;

    /** Metadata marker on sessions created for sub-agent runs. */
    public static final String META_KIND = "kind";
    public static final String KIND_SUBAGENT = "subagent";

    private final SessionManager persistent;
    private final TransientSessionManager transientSessions = new TransientSessionManager();
    private final String transientPrefix;
    private final java.util.List<Consumer<String>> deleteListeners =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    public RoutingSessionManager(SessionManager persistent) {
        this(persistent, TRANSIENT_PREFIX);
    }

    public RoutingSessionManager(SessionManager persistent, String transientPrefix) {
        this.persistent = Objects.requireNonNull(persistent, "persistent delegate");
        this.transientPrefix = transientPrefix != null ? transientPrefix : TRANSIENT_PREFIX;
    }

    private boolean isTransient(String sessionId) {
        return sessionId != null && sessionId.startsWith(transientPrefix);
    }

    private boolean isTransient(String sessionId, Map<String, Object> metadata) {
        if (isTransient(sessionId)) return true;
        return metadata != null && KIND_SUBAGENT.equals(metadata.get(META_KIND));
    }

    private SessionManager route(String sessionId) {
        return isTransient(sessionId) ? transientSessions : persistent;
    }

    @Override
    public Session getOrCreate(String sessionId, String userId, String agentId,
                               Map<String, Object> metadata) {
        if (isTransient(sessionId, metadata)) {
            return transientSessions.getOrCreate(sessionId, userId, agentId, metadata);
        }
        return persistent.getOrCreate(sessionId, userId, agentId, metadata);
    }

    @Override
    public Mono<Void> save(Session session) {
        return route(session.sessionId()).save(session);
    }

    /**
     * Register a callback fired after a session is deleted through this manager.
     * Used to purge per-session in-memory state (compaction horizons, etc.).
     * Listener exceptions never break the deletion.
     */
    @Override
    public void addDeleteListener(Consumer<String> listener) {
        if (listener != null) deleteListeners.add(listener);
    }

    @Override
    public Mono<Void> delete(String sessionId) {
        return route(sessionId).delete(sessionId)
            .doOnSuccess(v -> deleteListeners.forEach(l -> {
                try {
                    l.accept(sessionId);
                } catch (RuntimeException e) {
                    // Cleanup hooks must not break or roll back deletion.
                }
            }));
    }

    @Override
    public Optional<Session> get(String sessionId) {
        return route(sessionId).get(sessionId);
    }

    /** Transient sessions never appear in listings; delegate everything else. */
    @Override
    public Flux<Session> listByUser(String userId) {
        return persistent.listByUser(userId);
    }

    @Override
    public Flux<Session> listActiveByUser(String userId) {
        return persistent.listActiveByUser(userId);
    }

    @Override
    public Flux<Session> listByUser(String userId, int page, int size) {
        return persistent.listByUser(userId, page, size);
    }

    @Override
    public Flux<Session> listActiveByUser(String userId, int page, int size) {
        return persistent.listActiveByUser(userId, page, size);
    }
}
