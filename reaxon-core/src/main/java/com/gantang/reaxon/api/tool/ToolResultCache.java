package com.gantang.reaxon.api.tool;

import java.time.Duration;
import java.util.Optional;

/**
 * Session-scoped, short-lived cache for idempotent tool results.
 *
 * <p>Purpose: within one session the model frequently re-issues the same
 * read-only call (identical calculator expression, refetch of an unchanged
 * URL, repeated grep). Replaying a cached result skips execution and its
 * latency/cost. Entries expire by TTL (freshness) and are private to their
 * session (never cross users/sessions).
 *
 * <p>Only tools the deployment explicitly allowlists are cached; write/exec
 * side-effecting tools must never be. The cache stores RAW (unpruned,
 * unwrapped) results — pruning/trust wrapping still happen at persistence so
 * cached replays follow the same single exit as fresh executions.
 */
public interface ToolResultCache {

    /** A stored raw result. */
    record CachedResult(boolean success, String content, String errorMessage, long cachedAtEpochMilli) {}

    /**
     * Look up a fresh entry for the canonical key.
     *
     * @param sessionId owning session
     * @param key       canonical cache key (tool name + normalized arguments)
     */
    Optional<CachedResult> get(String sessionId, String key);

    /** Store a result; implementations enforce TTL eviction and size caps. */
    void put(String sessionId, String key, CachedResult result);

    /** Purge a session's entries (session-deletion hook). */
    void clearSession(String sessionId);

    /** Build the canonical per-call key. */
    static String key(String toolName, String canonicalArgs) {
        return toolName + "|" + (canonicalArgs == null ? "" : canonicalArgs);
    }

    /** Default TTL for cacheable tool results. */
    Duration DEFAULT_TTL = Duration.ofMinutes(10);
}
