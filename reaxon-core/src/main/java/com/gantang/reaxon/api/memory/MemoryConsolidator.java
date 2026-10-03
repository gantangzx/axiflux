package com.gantang.reaxon.api.memory;

import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Decides how a newly extracted memory relates to the memories already stored
 * that are semantically close to it. Used by the write loop to tell apart a
 * harmless duplicate, a fact that <b>supersedes</b> an older one ("we migrated
 * from Java 25 to Java 21" must update the Java memory, not sit beside it), and
 * a genuinely new fact.
 *
 * <p>The classifier receives <b>all</b> near memories (not just the single
 * nearest): a candidate that merges two facts already stored separately (e.g.
 * "React + Zustand") must still be recognised as fully covered.
 *
 * <p>Implementations must never throw: on any failure they return
 * {@link Decision#novel()} so the candidate is stored rather than dropped.
 */
public interface MemoryConsolidator {

    /**
     * Classify {@code candidate} against the semantically-near {@code near} memories.
     *
     * @param near      stored memories within the ambiguous similarity band (never empty)
     * @param candidate the newly extracted candidate memory
     */
    Mono<Decision> classify(List<MemoryItem> near, MemoryExtractor.ExtractedMemory candidate);

    /** How a candidate relates to the existing near memories. */
    enum Relation {
        /** Every fact in the candidate is already covered by the near memories — skip. */
        DUPLICATE,
        /** The candidate supersedes / contradicts one of the near memories. */
        UPDATE,
        /** The candidate states a fact not covered by any near memory — store new. */
        NOVEL
    }

    /**
     * Consolidation verdict. For {@link Relation#UPDATE}, {@code targetId} is the
     * stored memory id to rewrite and {@code mergedContent}/{@code mergedSummary}
     * are its replacement.
     */
    record Decision(Relation relation, String targetId, String mergedContent, String mergedSummary) {
        public static Decision duplicate() { return new Decision(Relation.DUPLICATE, null, null, null); }
        public static Decision novel() { return new Decision(Relation.NOVEL, null, null, null); }
        public static Decision update(String targetId, String content, String summary) {
            return new Decision(Relation.UPDATE, targetId, content, summary);
        }
    }
}
