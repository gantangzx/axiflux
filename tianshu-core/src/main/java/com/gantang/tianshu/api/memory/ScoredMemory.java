package com.gantang.tianshu.api.memory;

/**
 * A memory hit paired with its retrieval similarity score.
 *
 * @param item  the stored memory
 * @param score cosine similarity in {@code [0,1]} where {@code 1} is an exact
 *              match; {@link #UNSCORED} means the backend could not compute a
 *              score (keyword fallback, non-vector store)
 */
public record ScoredMemory(MemoryItem item, double score) {

    /** Sentinel score for backends that do not return vector similarity. */
    public static final double UNSCORED = -1.0;

    public static ScoredMemory unscored(MemoryItem item) {
        return new ScoredMemory(item, UNSCORED);
    }

    /** True when a real similarity score is present. */
    public boolean hasScore() {
        return score >= 0.0;
    }
}
