package com.gantang.tianshu.api.memory;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

/**
 * Long-term memory backed by a vector store.
 */
public interface LongTermMemory {

    /**
     * Store a memory item.
     * Generates vector embedding and stores both text and vector.
     */
    Mono<Void> store(String userId, MemoryItem item);

    /** Batch store */
    Mono<Void> storeBatch(String userId, List<MemoryItem> items);

    /**
     * Semantic search — retrieve topK items most relevant to the query.
     */
    Mono<List<MemoryItem>> search(String userId, String query, int topK);

    /**
     * Semantic search returning each hit with its cosine similarity score in
     * {@code [0,1]} (1 = identical). Backends that cannot compute a score
     * (keyword fallback, non-vector stores) return {@link ScoredMemory#UNSCORED}.
     * Used by the auto memory-writing pipeline to suppress near-duplicate memories.
     * Default delegates to {@link #search} without scores; vector backends override.
     */
    default Mono<List<ScoredMemory>> searchScored(String userId, String query, int topK) {
        return search(userId, query, topK)
            .map(list -> list.stream().map(ScoredMemory::unscored).toList());
    }

    /**
     * Keyword search (exact match on tags / full-text on content).
     */
    Mono<List<MemoryItem>> searchByKeyword(String userId, String keyword);

    /**
     * Get all memories for a user (for management UI).
     */
    Mono<List<MemoryItem>> getAll(String userId);

    /** Delete a memory by id */
    Mono<Boolean> delete(String memoryId);

    /** Delete all memories for a user */
    Mono<Void> deleteAll(String userId);

    /**
     * Update an existing memory in place (content changed by a consolidation /
     * conflict-resolution step). The embedding is regenerated for the new
     * content and {@code updated_at} is bumped; id and created_at are kept.
     * Default falls back to delete + re-store (new id); vector backends override
     * with an in-place UPDATE.
     */
    default Mono<Boolean> updateMemory(MemoryItem existing, String content,
                                       String summary, List<String> tags, int importance) {
        MemoryItem updated = new MemoryItem(
            "mem_" + java.util.UUID.randomUUID(), existing.userId(),
            content, summary, tags, importance, existing.createdAt(), java.time.Instant.now());
        return delete(existing.id()).then(store(existing.userId(), updated)).thenReturn(true);
    }

    /** Distinct user ids that have at least one memory (used by periodic maintenance). */
    default Mono<List<String>> listUserIds() {
        return Mono.just(List.of());
    }

    /**
     * Mark an existing fact as no longer valid as of now (superseded by a newer
     * fact or explicitly expired). The row is kept for audit (with
     * {@code valid_until}/{@code superseded_by}) and must never come back from
     * the search methods. Backends without validity tracking default to a hard
     * delete, which preserves retrieval correctness but loses history.
     *
     * @return whether a row was affected
     */
    default Mono<Boolean> invalidate(String memoryId, String supersededById) {
        return delete(memoryId);
    }

    /**
     * Replace an outdated fact with a fresh one. The replacement is stored as a
     * new row (new embedding, new id) and the old row is invalidated in its
     * favour, so the fact history stays auditable while retrieval only sees the
     * newest state. Default orchestration works on every backend; vector stores
     * override to make both writes transactional.
     *
     * @return the new memory id
     */
    default Mono<String> supersede(MemoryItem existing, MemoryItem replacement) {
        return store(existing.userId(), replacement)
            .then(invalidate(existing.id(), replacement.id()))
            .thenReturn(replacement.id());
    }

    /** Compress memories — LLM generates summaries for high-importance items */
    Mono<Integer> compress(String userId);

    /**
     * Whether this backend is "live" — i.e. actually persisting to a vector
     * store / DB. A no-op backend (e.g. {@code NoOpLongTermMemory} used when
     * no vector store is wired) returns {@code false} so periodic maintenance
     * and write paths can short-circuit cleanly without an {@code instanceof}
     * probe on the impl class.
     *
     * <p>P1-4 contract: callers depend on this boolean, never on the impl type.
     */
    default boolean enabled() {
        return true;
    }
}
