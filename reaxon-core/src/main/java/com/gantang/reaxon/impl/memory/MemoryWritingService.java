package com.gantang.reaxon.impl.memory;

import com.gantang.reaxon.api.memory.LongTermMemory;
import com.gantang.reaxon.api.memory.MemoryConsolidator;
import com.gantang.reaxon.api.memory.MemoryExtractor;
import com.gantang.reaxon.api.memory.MemoryExtractor.ExtractedMemory;
import com.gantang.reaxon.api.memory.MemoryExtractor.MemoryCapture;
import com.gantang.reaxon.api.memory.MemoryItem;
import com.gantang.reaxon.api.memory.ScoredMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;

/**
 * Orchestrates the memory <b>write loop</b>: after a turn finishes, extract
 * durable facts via {@link MemoryExtractor}, then for each candidate decide —
 * against what is already stored — whether it is a duplicate, an update to an
 * existing memory, or a genuinely new fact, and persist accordingly.
 *
 * <p>Decision ladder per candidate (against its single nearest stored memory):
 * <ul>
 *   <li>no near hit (similarity &lt; {@link #RELATE_MIN}) → store new</li>
 *   <li>near-identical (similarity &ge; {@link #DUP_DIRECT}) → skip (duplicate)</li>
 *   <li>in the ambiguous band → ask the {@link MemoryConsolidator} LLM to judge
 *       duplicate / update / novel; update rewrites the existing memory in place</li>
 * </ul>
 *
 * <p>The whole pipeline is best-effort and bounded: it runs off the turn's
 * critical path, every stage degrades to a no-op on failure, and a single turn
 * can never flood the store. A failure here must never surface to the conversation.
 */
public class MemoryWritingService {

    private static final Logger log = LoggerFactory.getLogger(MemoryWritingService.class);

    /**
     * Default cosine threshold above which a candidate is treated as a duplicate
     * without asking the LLM. Tuned against the deployed doubao-embedding-vision
     * model (2026-09-03): the same fact worded differently scores ~0.75, a
     * related-but-distinct fact ~0.44, unrelated ~0.38.
     */
    static final double DEDUP_SIMILARITY = 0.62;
    /** Below this similarity a candidate is always treated as novel (no LLM call). */
    static final double RELATE_MIN = 0.50;
    /** At/above this similarity a candidate is always a duplicate (no LLM call). */
    static final double DUP_DIRECT = 0.88;
    /** Candidates checked for similarity before deciding. */
    static final int DEDUP_TOP_K = 3;

    private final LongTermMemory memory;
    private final MemoryExtractor extractor;
    private final MemoryConsolidator consolidator;
    private final double dedupSimilarity;

    public MemoryWritingService(LongTermMemory memory, MemoryExtractor extractor) {
        this(memory, extractor, null, DEDUP_SIMILARITY);
    }

    public MemoryWritingService(LongTermMemory memory, MemoryExtractor extractor, double dedupSimilarity) {
        this(memory, extractor, null, dedupSimilarity);
    }

    public MemoryWritingService(LongTermMemory memory, MemoryExtractor extractor,
                                MemoryConsolidator consolidator, double dedupSimilarity) {
        this.memory = memory;
        this.extractor = extractor;
        this.consolidator = consolidator;
        this.dedupSimilarity = dedupSimilarity > 0 && dedupSimilarity < 1 ? dedupSimilarity : DEDUP_SIMILARITY;
    }

    /**
     * Extract memories from a finished turn and store / update the novel ones.
     *
     * @return number of memories written (new inserts + updates); 0 on any failure
     */
    public Mono<Integer> captureAndStore(MemoryCapture capture) {
        if (!memory.enabled()) {
            return Mono.just(0);
        }
        String userId = capture.userId();
        return extractor.extract(capture)
            .flatMapMany(Flux::fromIterable)
            .concatMap(cand -> resolveAndPersist(userId, cand))   // sequential so later candidates see earlier writes
            .reduce(0, Integer::sum)
            .doOnNext(n -> {
                if (n > 0) log.info("Wrote {} long-term memory change(s) for user={}", n, userId);
            })
            .onErrorResume(e -> {
                log.warn("Memory capture failed (turn unaffected): {}", e.toString());
                return Mono.just(0);
            });
    }

    /** Returns 1 if this candidate caused a store/update, 0 if skipped. */
    private Mono<Integer> resolveAndPersist(String userId, ExtractedMemory cand) {
        return memory.searchScored(userId, cand.content(), DEDUP_TOP_K)
            .flatMap(hits -> decide(userId, cand, hits))
            .onErrorResume(e -> {
                log.warn("Memory dedup/store skipped for one candidate ({}): {}",
                    abbrev(cand.content()), e.toString());
                return Mono.just(0);
            });
    }

    private Mono<Integer> decide(String userId, ExtractedMemory cand, java.util.List<ScoredMemory> hits) {
        java.util.List<MemoryItem> near = hits.stream()
            .filter(h -> h.hasScore() && h.score() >= RELATE_MIN)
            .sorted(Comparator.comparingDouble(ScoredMemory::score).reversed())
            .map(ScoredMemory::item)
            .toList();

        // No semantically-near memory → novel.
        if (near.isEmpty()) {
            return memory.store(userId, toItem(userId, cand)).thenReturn(1);
        }
        double best = hits.stream().filter(ScoredMemory::hasScore)
            .mapToDouble(ScoredMemory::score).max().orElse(0);
        // Near-identical → duplicate, no LLM call needed.
        if (best >= DUP_DIRECT) {
            log.debug("Skipping near-identical memory: {}", abbrev(cand.content()));
            return Mono.just(0);
        }
        // Ambiguous band: cheap LLM judges duplicate / update / novel against ALL near memories.
        if (consolidator == null) {
            // No classifier: legacy threshold behaviour (>= dedup ⇒ skip, else store).
            if (best >= dedupSimilarity) {
                log.debug("Skipping duplicate memory: {}", abbrev(cand.content()));
                return Mono.just(0);
            }
            return memory.store(userId, toItem(userId, cand)).thenReturn(1);
        }
        return consolidator.classify(near, cand).flatMap(decision -> {
            switch (decision.relation()) {
                case DUPLICATE:
                    log.debug("Consolidator: duplicate skipped: {}", abbrev(cand.content()));
                    return Mono.just(0);
                case UPDATE: {
                    String targetId = decision.targetId() != null ? decision.targetId() : near.get(0).id();
                    MemoryItem target = near.stream().filter(m -> m.id().equals(targetId)).findFirst()
                        .orElse(near.get(0));
                    log.info("Consolidator: superseding memory {} with newer fact", target.id());
                    String content = decision.mergedContent() != null && !decision.mergedContent().isBlank()
                        ? decision.mergedContent() : cand.content();
                    String summary = decision.mergedSummary() != null && !decision.mergedSummary().isBlank()
                        ? decision.mergedSummary() : cand.summary();
                    MemoryItem replacement = new MemoryItem(
                        "mem_" + UUID.randomUUID(), userId, content, summary,
                        cand.tags(), cand.importance(), Instant.now(), Instant.now(), cand.validUntil());
                    // New fact gets its own row; the old row is stamped valid_until/superseded_by
                    // so retrieval only sees the new state while the fact history stays auditable.
                    return memory.supersede(target, replacement).thenReturn(1);
                }
                case NOVEL:
                default:
                    return memory.store(userId, toItem(userId, cand)).thenReturn(1);
            }
        });
    }

    private static MemoryItem toItem(String userId, ExtractedMemory m) {
        Instant now = Instant.now();
        return new MemoryItem(
            "mem_" + UUID.randomUUID(),
            userId,
            m.content(),
            m.summary(),
            m.tags(),
            m.importance(),
            now, now, m.validUntil());
    }

    private static String abbrev(String s) {
        return s.length() <= 60 ? s : s.substring(0, 60) + "…";
    }
}
