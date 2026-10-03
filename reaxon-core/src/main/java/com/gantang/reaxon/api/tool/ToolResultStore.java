package com.gantang.reaxon.api.tool;

import java.time.Instant;
import java.util.Optional;

/**
 * Session-scoped side storage for oversized tool results (context engineering,
 * roadmap P1-3).
 *
 * <p>When a tool result exceeds the {@code ToolResultPruner} cap, the full
 * content is parked here and only a head/tail summary plus a
 * {@code ref://tool-result/<id>} handle enters the transcript. The model
 * retrieves slices on demand via the {@code result_read} tool, so truncation
 * no longer destroys information while the context window stays small.
 *
 * <p>Records are private to their session: {@link #fetch} must never return a
 * record stored under another session. Stores are responsible for eviction and
 * for purging a session's records when that session is deleted.
 */
public interface ToolResultStore {

    /** Handle scheme understood by {@code result_read}. */
    String HANDLE_PREFIX = "ref://tool-result/";

    /**
     * Park one full tool result.
     *
     * @param sessionId owning session
     * @param content   full, unpruned result text
     * @param originTool name of the tool that produced the content (trust provenance)
     * @return retrieval handle, {@code ref://tool-result/<id>}
     */
    String store(String sessionId, String content, String originTool);

    /** Fetch a record owned by {@code sessionId}; empty when absent or foreign. */
    Optional<StoredToolResult> fetch(String sessionId, String id);

    /** Purge every record of a session (session-deletion hook). */
    void clearSession(String sessionId);

    /** True when {@code ref} is a syntactically valid result handle. */
    static boolean isHandle(String ref) {
        return ref != null && ref.startsWith(HANDLE_PREFIX)
            && ref.length() > HANDLE_PREFIX.length();
    }

    /** Extract the bare record id from a handle (or accept a bare id). */
    static String idOf(String ref) {
        if (ref == null) return "";
        return ref.startsWith(HANDLE_PREFIX) ? ref.substring(HANDLE_PREFIX.length()) : ref;
    }
}
