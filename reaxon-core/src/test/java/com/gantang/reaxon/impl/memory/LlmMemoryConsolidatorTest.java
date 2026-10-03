package com.gantang.reaxon.impl.memory;

import com.gantang.reaxon.api.llm.CompletionRequest;
import com.gantang.reaxon.api.llm.CompletionResponse;
import com.gantang.reaxon.api.llm.LlmClient;
import com.gantang.reaxon.api.llm.ModelRouter;
import com.gantang.reaxon.api.memory.MemoryConsolidator;
import com.gantang.reaxon.api.memory.MemoryExtractor.ExtractedMemory;
import com.gantang.reaxon.api.memory.MemoryItem;
import com.fasterxml.jackson.databind.JsonNode;
import reactor.core.publisher.Flux;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LlmMemoryConsolidatorTest {

    private MemoryConsolidator consolidatorWith(String reply) {
        ModelRouter router = ctx -> new StubClient(reply);
        return new LlmMemoryConsolidator(router, Duration.ofSeconds(10));
    }

    private MemoryItem stored() {
        return new MemoryItem("mem_old", "u1", "项目使用 Java 25 编译", "Java 25",
            List.of("java"), 9, Instant.now(), Instant.now());
    }

    private ExtractedMemory cand() {
        return new ExtractedMemory("项目已迁移到 Java 21", "Java 21", List.of("java"), 9);
    }

    @Test
    void parsesUpdateWithMergedContent() {
        MemoryConsolidator c = consolidatorWith("""
            好的，判断如下：
            ```json
            {"relation":"update","mergedContent":"项目统一使用 Java 21 LTS 编译","mergedSummary":"构建约定：Java 21"}
            ```
            """);
        MemoryConsolidator.Decision d = c.classify(java.util.List.of(stored()), cand()).block(Duration.ofSeconds(10));
        assertEquals(MemoryConsolidator.Relation.UPDATE, d.relation());
        assertEquals("项目统一使用 Java 21 LTS 编译", d.mergedContent());
        assertEquals("构建约定：Java 21", d.mergedSummary());
    }

    @Test
    void parsesDuplicate() {
        MemoryConsolidator c = consolidatorWith("{\"relation\":\"duplicate\"}");
        MemoryConsolidator.Decision d = c.classify(java.util.List.of(stored()), cand()).block(Duration.ofSeconds(10));
        assertEquals(MemoryConsolidator.Relation.DUPLICATE, d.relation());
    }

    @Test
    void parsesNovel() {
        MemoryConsolidator c = consolidatorWith("{\"relation\":\"novel\"}");
        MemoryConsolidator.Decision d = c.classify(java.util.List.of(stored()), cand()).block(Duration.ofSeconds(10));
        assertEquals(MemoryConsolidator.Relation.NOVEL, d.relation());
    }

    @Test
    void garbageDegradesToNovel() {
        MemoryConsolidator c = consolidatorWith("我无法判断这个关系");
        MemoryConsolidator.Decision d = c.classify(java.util.List.of(stored()), cand()).block(Duration.ofSeconds(10));
        assertEquals(MemoryConsolidator.Relation.NOVEL, d.relation(),
            "unparseable output must degrade to NOVEL, never drop a memory");
    }

    @Test
    void llmFailureDegradesToNovel() {
        ModelRouter throwing = ctx -> { throw new RuntimeException("boom"); };
        MemoryConsolidator c = new LlmMemoryConsolidator(throwing, Duration.ofSeconds(10));
        MemoryConsolidator.Decision d = c.classify(java.util.List.of(stored()), cand()).block(Duration.ofSeconds(10));
        assertEquals(MemoryConsolidator.Relation.NOVEL, d.relation());
    }

    private static final class StubClient implements LlmClient {
        private final String reply;
        StubClient(String reply) { this.reply = reply; }
        @Override public String provider() { return "stub"; }
        @Override public String primaryModel() { return "stub-model"; }
        @Override public CompletionResponse complete(CompletionRequest request) {
            return CompletionResponse.builder().content(reply).finishReason("stop").model("stub-model").build();
        }
        @Override public Flux<String> completeStream(CompletionRequest request) { return Flux.empty(); }
        @Override public CompletionResponse completeWithTools(CompletionRequest request, JsonNode tools) {
            return complete(request);
        }
    }
}
