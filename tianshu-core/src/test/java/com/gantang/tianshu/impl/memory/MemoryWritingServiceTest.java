package com.gantang.tianshu.impl.memory;

import com.gantang.tianshu.api.memory.LongTermMemory;
import com.gantang.tianshu.api.memory.MemoryConsolidator;
import com.gantang.tianshu.api.memory.MemoryExtractor;
import com.gantang.tianshu.api.memory.MemoryItem;
import com.gantang.tianshu.api.memory.ScoredMemory;
import reactor.core.publisher.Mono;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryWritingServiceTest {

    /** Records stores; returns canned scored hits for dedup. */
    private static final class FakeMemory implements LongTermMemory {
        final List<MemoryItem> stored = new ArrayList<>();
        final List<MemoryItem> updated = new ArrayList<>();
        final List<String> invalidated = new ArrayList<>();
        List<ScoredMemory> nextHits = List.of();
        @Override public Mono<Void> store(String userId, MemoryItem item) {
            stored.add(item); return Mono.empty();
        }
        @Override public Mono<Boolean> invalidate(String memoryId, String supersededById) {
            invalidated.add(memoryId + "<-" + supersededById); return Mono.just(true);
        }
        @Override public Mono<Boolean> updateMemory(MemoryItem existing, String content,
                                                     String summary, List<String> tags, int importance) {
            updated.add(new MemoryItem(existing.id(), existing.userId(), content, summary,
                tags, importance, existing.createdAt(), java.time.Instant.now()));
            return Mono.just(true);
        }
        @Override public Mono<Void> storeBatch(String userId, List<MemoryItem> items) {
            stored.addAll(items); return Mono.empty();
        }
        @Override public Mono<List<MemoryItem>> search(String userId, String query, int topK) {
            return Mono.just(nextHits.stream().map(ScoredMemory::item).toList());
        }
        @Override public Mono<List<ScoredMemory>> searchScored(String userId, String query, int topK) {
            return Mono.just(nextHits);
        }
        @Override public Mono<List<MemoryItem>> searchByKeyword(String userId, String keyword) {
            return Mono.just(List.of());
        }
        @Override public Mono<List<MemoryItem>> getAll(String userId) { return Mono.just(stored); }
        @Override public Mono<Boolean> delete(String memoryId) { return Mono.just(true); }
        @Override public Mono<Void> deleteAll(String userId) { return Mono.empty(); }
        @Override public Mono<Integer> compress(String userId) { return Mono.just(0); }
    }

    private static MemoryExtractor fixedExtractor(List<MemoryExtractor.ExtractedMemory> items) {
        return capture -> Mono.just(items);
    }

    private static MemoryExtractor.ExtractedMemory mem(String content, int importance) {
        return new MemoryExtractor.ExtractedMemory(content, content, List.of("t"), importance);
    }

    private static ScoredMemory hit(double score) {
        MemoryItem item = new MemoryItem("mem_old", "u1", "existing", "existing",
            List.of(), 8, Instant.now(), Instant.now());
        return new ScoredMemory(item, score);
    }

    @Test
    void storesNovelMemory() {
        FakeMemory mem = new FakeMemory();
        mem.nextHits = List.of(hit(0.40));  // low similarity → novel
        MemoryWritingService svc = new MemoryWritingService(mem, fixedExtractor(List.of(mem("用户喜欢用 Maven 构建", 9))));
        Integer n = svc.captureAndStore(new MemoryExtractor.MemoryCapture("u1", "s1", "用什么构建", "Maven")).block();
        assertEquals(1, n);
        assertEquals(1, mem.stored.size());
        assertEquals("u1", mem.stored.get(0).userId());
    }

    @Test
    void skipsNearDuplicate() {
        FakeMemory mem = new FakeMemory();
        mem.nextHits = List.of(hit(MemoryWritingService.DEDUP_SIMILARITY + 0.05));  // very similar
        MemoryWritingService svc = new MemoryWritingService(mem, fixedExtractor(List.of(mem("用户喜欢用 Maven 构建", 9))));
        Integer n = svc.captureAndStore(new MemoryExtractor.MemoryCapture("u1", "s1", "用什么构建", "Maven")).block();
        assertEquals(0, n);
        assertTrue(mem.stored.isEmpty(), "near-duplicate must not be stored");
    }

    @Test
    void unscoredHitsDoNotBlockStorage() {
        FakeMemory mem = new FakeMemory();
        mem.nextHits = List.of(ScoredMemory.unscored(
            new MemoryItem("x", "u1", "kw", "kw", List.of(), 5, Instant.now(), Instant.now())));
        MemoryWritingService svc = new MemoryWritingService(mem, fixedExtractor(List.of(mem("一条关键词命中的新事实", 7))));
        Integer n = svc.captureAndStore(new MemoryExtractor.MemoryCapture("u1", "s1", "问题内容够长", "回答内容也够长")).block();
        assertEquals(1, n, "keyword-only (unscored) hits must not be treated as duplicates");
    }

    @Test
    void emptyExtractionStoresNothing() {
        FakeMemory mem = new FakeMemory();
        MemoryWritingService svc = new MemoryWritingService(mem, fixedExtractor(List.of()));
        Integer n = svc.captureAndStore(new MemoryExtractor.MemoryCapture("u1", "s1", "随便问问", "随便答答")).block();
        assertEquals(0, n);
        assertTrue(mem.stored.isEmpty());
    }

    @Test
    void extractorFailureYieldsZeroNotError() {
        FakeMemory mem = new FakeMemory();
        MemoryExtractor failing = capture -> Mono.error(new RuntimeException("llm down"));
        MemoryWritingService svc = new MemoryWritingService(mem, failing);
        Integer n = svc.captureAndStore(new MemoryExtractor.MemoryCapture("u1", "s1", "问题内容足够长", "回答内容也足够长")).block();
        assertEquals(0, n, "failures must degrade to no-op, not throw");
    }

    // ---- consolidation (conflict update) ----

    private static MemoryConsolidator fixed(MemoryConsolidator.Decision d) {
        return (stored, cand) -> Mono.just(d);
    }

    private MemoryWritingService serviceWith(FakeMemory mem, MemoryConsolidator cons) {
        return new MemoryWritingService(mem, fixedExtractor(List.of(mem("一条需要判断的事实", 9))), cons,
            MemoryWritingService.DEDUP_SIMILARITY);
    }

    @Test
    void updateDecisionSupersedesExistingMemory() {
        FakeMemory mem = new FakeMemory();
        mem.nextHits = List.of(hit(0.72));  // ambiguous band
        MemoryWritingService svc = serviceWith(mem,
            fixed(MemoryConsolidator.Decision.update("mem_old", "项目统一使用 Java 21", "Java 21")));
        Integer n = svc.captureAndStore(new MemoryExtractor.MemoryCapture("u1", "s1", "我们迁移到新版本了", "好的迁到 21")).block();
        assertEquals(1, n);
        assertEquals(1, mem.stored.size(), "newer fact is inserted as a new row");
        MemoryItem replacement = mem.stored.get(0);
        assertEquals("项目统一使用 Java 21", replacement.content());
        assertEquals(1, mem.invalidated.size());
        assertEquals("mem_old<-" + replacement.id(), mem.invalidated.get(0),
            "the old fact must be stamped valid_until and linked to its replacement");
        assertEquals(0, mem.updated.size(), "supersede must not rewrite the old row in place");
    }

    @Test
    void duplicateDecisionSkips() {
        FakeMemory mem = new FakeMemory();
        mem.nextHits = List.of(hit(0.72));
        MemoryWritingService svc = serviceWith(mem, fixed(MemoryConsolidator.Decision.duplicate()));
        Integer n = svc.captureAndStore(new MemoryExtractor.MemoryCapture("u1", "s1", "换个说法同一件事", "同样的内容")).block();
        assertEquals(0, n);
        assertTrue(mem.stored.isEmpty() && mem.updated.isEmpty() && mem.invalidated.isEmpty());
    }

    @Test
    void novelDecisionFromConsolidatorStores() {
        FakeMemory mem = new FakeMemory();
        mem.nextHits = List.of(hit(0.60));  // in band, but LLM says distinct fact
        MemoryWritingService svc = serviceWith(mem, fixed(MemoryConsolidator.Decision.novel()));
        Integer n = svc.captureAndStore(new MemoryExtractor.MemoryCapture("u1", "s1", "相关但不同的主题", "不同的事实")).block();
        assertEquals(1, n);
        assertEquals(1, mem.stored.size());
    }

    @Test
    void veryHighSimilaritySkipsWithoutConsolidator() {
        FakeMemory mem = new FakeMemory();
        final int[] calls = {0};
        MemoryConsolidator counting = (stored, cand) -> { calls[0]++; return Mono.just(MemoryConsolidator.Decision.duplicate()); };
        mem.nextHits = List.of(hit(MemoryWritingService.DUP_DIRECT + 0.02));
        MemoryWritingService svc = serviceWith(mem, counting);
        Integer n = svc.captureAndStore(new MemoryExtractor.MemoryCapture("u1", "s1", "几乎一模一样的句子", "几乎一模一样")).block();
        assertEquals(0, n);
        assertEquals(0, calls[0], "near-identical must be skipped without an LLM call");
    }
}
