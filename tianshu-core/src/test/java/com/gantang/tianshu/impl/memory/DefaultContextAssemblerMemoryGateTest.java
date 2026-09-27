package com.gantang.tianshu.impl.memory;

import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.memory.LongTermMemory;
import com.gantang.tianshu.api.memory.MemoryItem;
import com.gantang.tianshu.api.memory.ScoredMemory;
import com.gantang.tianshu.api.session.Message;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Memory retrieval gating: vector hits below the similarity threshold must not
 * be injected into the turn context; unscored hits (keyword fallback) pass.
 */
class DefaultContextAssemblerMemoryGateTest {

    private static MemoryItem mem(String id, String text) {
        return new MemoryItem(id, "u1", text, text, List.of(), 5,
            Instant.now(), Instant.now());
    }

    /** Stub vector memory returning fixed scored hits. */
    private static LongTermMemory memoryWith(List<ScoredMemory> hits) {
        return new LongTermMemory() {
            @Override public Mono<Void> store(String userId, MemoryItem item) { return Mono.empty(); }
            @Override public Mono<Void> storeBatch(String userId, List<MemoryItem> items) { return Mono.empty(); }
            @Override public Mono<List<MemoryItem>> search(String userId, String query, int topK) {
                return Mono.just(hits.stream().map(ScoredMemory::item).toList());
            }
            @Override public Mono<List<ScoredMemory>> searchScored(String userId, String query, int topK) {
                return Mono.just(hits);
            }
            @Override public Mono<List<MemoryItem>> searchByKeyword(String userId, String keyword) { return Mono.just(List.of()); }
            @Override public Mono<List<MemoryItem>> getAll(String userId) { return Mono.just(List.of()); }
            @Override public Mono<Boolean> delete(String memoryId) { return Mono.just(true); }
            @Override public Mono<Void> deleteAll(String userId) { return Mono.empty(); }
            @Override public Mono<Integer> compress(String userId) { return Mono.just(0); }
        };
    }

    private AgentContext ctx() {
        return AgentContext.builder()
            .sessionId("s1").userId("u1")
            .currentQuery("hello")
            .systemPrompt("system prompt")
            .build();
    }

    @Test
    void weakHitsAreSuppressedStrongHitsInjected() {
        LongTermMemory ltm = memoryWith(List.of(
            new ScoredMemory(mem("m1", "strongly relevant fact about the deployment topology"), 0.62),
            new ScoredMemory(mem("m2", "weak tangent about an unrelated meeting last quarter"), 0.18)
        ));
        DefaultContextAssembler asm = new DefaultContextAssembler(ltm, 50, 5);

        List<Message> out = asm.assembleWithRetrieval(ctx(), List.<Message>of(), null).block();
        assertNotNull(out);
        // system prompt + memory block (only the strong hit) => memory block present
        long sysCount = out.stream().filter(m -> m.role() == Message.Role.SYSTEM).count();
        assertEquals(2, sysCount, "system prompt + one memory block");
        String block = out.get(1).content();
        assertTrue(block.contains("deployment topology"), "strong hit must be injected");
        assertFalse(block.contains("unrelated meeting"), "weak hit must be gated out");
    }

    @Test
    void unscoredHitsAlwaysPassThrough() {
        LongTermMemory ltm = memoryWith(List.of(
            ScoredMemory.unscored(mem("m1", "keyword fallback memory has no score"))
        ));
        DefaultContextAssembler asm = new DefaultContextAssembler(ltm, 50, 5);

        List<Message> out = asm.assembleWithRetrieval(ctx(), List.<Message>of(), null).block();
        assertNotNull(out);
        assertEquals(2, out.size());
        assertTrue(out.get(1).content().contains("keyword fallback"));
    }

    @Test
    void thresholdIsConfigurable() {
        LongTermMemory ltm = memoryWith(List.of(
            new ScoredMemory(mem("m1", "medium relevance hit"), 0.45)
        ));
        DefaultContextAssembler strict = new DefaultContextAssembler(ltm, 50, 5)
            .withMemoryScoreThreshold(0.50);

        List<Message> out = strict.assembleWithRetrieval(ctx(), List.<Message>of(), null).block();
        assertNotNull(out);
        assertEquals(1, out.size(), "0.45 hit must be suppressed under a 0.50 threshold");
        assertEquals(Message.Role.SYSTEM, out.get(0).role());
    }
}
