package com.gantang.reaxon.api.session;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

/**
 * Session lifecycle manager.
 */
public interface SessionManager {

    /**
     * Page size used by the unbounded {@link #listByUser(String)} /
     * {@link #listActiveByUser(String)} forms.
     */
    int DEFAULT_PAGE_SIZE = 100;

    /** Get or create a session */
    Session getOrCreate(String sessionId, String userId, String agentId, Map<String, Object> metadata);

    /** Persist session state */
    Mono<Void> save(Session session);

    /** Delete session and all its messages */
    Mono<Void> delete(String sessionId);

    /** Get session by id */
    Optional<Session> get(String sessionId);

    /**
     * List sessions for a user, capped at {@link #DEFAULT_PAGE_SIZE}.
     *
     * <p>A user with more sessions than that will not see all of them. Callers
     * that must cover every session have to page explicitly via
     * {@link #listByUser(String, int, int)}.
     */
    Flux<Session> listByUser(String userId);

    /** As {@link #listByUser(String)}, restricted to ACTIVE sessions. */
    Flux<Session> listActiveByUser(String userId);

    /**
     * One page of a user's sessions.
     *
     * <p>Backends that can push the window into their query should override this;
     * the default reads the capped list and slices it in memory, which cannot
     * reach past {@link #DEFAULT_PAGE_SIZE}.
     *
     * @param page zero-based page index
     * @param size rows per page
     */
    default Flux<Session> listByUser(String userId, int page, int size) {
        return listByUser(userId).skip((long) page * size).take(size);
    }

    /** As {@link #listByUser(String, int, int)}, restricted to ACTIVE sessions. */
    default Flux<Session> listActiveByUser(String userId, int page, int size) {
        return listActiveByUser(userId).skip((long) page * size).take(size);
    }
}
