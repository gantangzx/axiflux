package com.gantang.reaxon.impl.memory;

import com.gantang.reaxon.api.llm.CompletionRequest;
import com.gantang.reaxon.api.llm.CompletionResponse;
import com.gantang.reaxon.api.llm.LlmClient;
import com.gantang.reaxon.api.llm.ModelRouter;
import com.gantang.reaxon.api.llm.RoutingContext;
import com.gantang.reaxon.api.memory.MemoryExtractor.ExtractedMemory;
import com.gantang.reaxon.api.memory.MemoryExtractor.MemoryCapture;
import com.fasterxml.jackson.databind.JsonNode;
import reactor.core.publisher.Flux;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LlmMemoryExtractorTest {

    /** Router that returns a stub client echoing a fixed completion (or throwing). */
    private static ModelRouter routerWith(String reply) {
        return ctx -> new StubClient(reply);
    }

    private static ModelRouter routerThrowing(RuntimeException e) {
        return ctx -> { throw e; };
    }

    private static ModelRouter routerNull() {
        return ctx -> null;
    }

    private LlmMemoryExtractor extractor(ModelRouter router) {
        return new LlmMemoryExtractor(router, 6000, 4, Duration.ofSeconds(10));
    }

    @Test
    void parsesCleanJsonArray() {
        LlmMemoryExtractor ex = extractor(routerWith("""
            [{"content":"用户偏好使用 Java 25","summary":"偏好 Java 25","tags":["java"],"importance":8}]
            """));
        List<ExtractedMemory> out = ex.extract(capture("我们项目用什么 Java 版本", "建议用 Java 25")).block(Duration.ofSeconds(10));
        assertEquals(1, out.size());
        assertEquals("用户偏好使用 Java 25", out.get(0).content());
        assertEquals(8, out.get(0).importance());
        assertEquals(List.of("java"), out.get(0).tags());
    }

    @Test
    void toleratesMarkdownFencesAndProse() {
        LlmMemoryExtractor ex = extractor(routerWith("""
            好的，以下是抽取结果：
            ```json
            [{"content":"项目决定不做聊天通道功能","summary":"不做聊天通道","tags":["scope"],"importance":9}]
            ```
            """));
        List<ExtractedMemory> out = ex.extract(capture("这个项目做 discord 吗", "不做，聊天通道不在范围内")).block(Duration.ofSeconds(10));
        assertEquals(1, out.size());
        assertTrue(out.get(0).content().contains("聊天通道"));
    }

    @Test
    void emptyArrayYieldsNothing() {
        LlmMemoryExtractor ex = extractor(routerWith("[]"));
        List<ExtractedMemory> out = ex.extract(capture("今天天气怎么样啊", "今天晴天，适合出门散步哦")).block(Duration.ofSeconds(10));
        assertTrue(out.isEmpty());
    }

    @Test
    void filtersLowImportance() {
        LlmMemoryExtractor ex = extractor(routerWith("""
            [{"content":"一条弱信息","summary":"弱","tags":[],"importance":2},
             {"content":"一条重要决定","summary":"重要","tags":["x"],"importance":9}]
            """));
        List<ExtractedMemory> out = ex.extract(capture("请记住这个重要决定", "好的，已经确认这个重要决定了")).block(Duration.ofSeconds(10));
        assertEquals(1, out.size());
        assertEquals(9, out.get(0).importance());
    }

    @Test
    void shortTurnSkipsWithoutCallingLlm() {
        final int[] calls = {0};
        ModelRouter counting = ctx -> { calls[0]++; return new StubClient("[]"); };
        LlmMemoryExtractor ex = extractor(counting);
        List<ExtractedMemory> out = ex.extract(capture("嗨", "你好")).block(Duration.ofSeconds(10));
        assertTrue(out.isEmpty());
        assertEquals(0, calls[0], "trivial turns must not invoke the LLM");
    }

    @Test
    void llmFailureDegradesToEmpty() {
        LlmMemoryExtractor ex = extractor(routerThrowing(new RuntimeException("boom")));
        List<ExtractedMemory> out = ex.extract(capture("我们的构建约定是什么来着", "用 Maven 多模块，Java 25")).block(Duration.ofSeconds(10));
        assertNotNull(out);
        assertTrue(out.isEmpty());
    }

    @Test
    void noRoutedClientYieldsEmpty() {
        LlmMemoryExtractor ex = extractor(routerNull());
        List<ExtractedMemory> out = ex.extract(capture("生产环境用什么数据库", "生产用 PostgreSQL 加 pgvector")).block(Duration.ofSeconds(10));
        assertTrue(out.isEmpty());
    }

    @Test
    void capsCandidatesPerTurn() {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < 12; i++) {
            if (i > 0) json.append(",");
            json.append("{\"content\":\"重要事实编号").append(i).append("内容足够长\",\"summary\":\"s\",\"tags\":[],\"importance\":9}");
        }
        json.append("]");
        LlmMemoryExtractor ex = extractor(routerWith(json.toString()));
        List<ExtractedMemory> out = ex.extract(capture("告诉我一堆重要的项目约定", "好的，以下是一堆重要的项目约定内容")).block(Duration.ofSeconds(10));
        assertTrue(out.size() <= LlmMemoryExtractor.MAX_PER_TURN);
    }

    private static MemoryCapture capture(String q, String a) {
        return new MemoryCapture("u1", "s1", q, a);
    }

    /** Minimal LlmClient stub: returns a fixed text completion; unused methods are inert. */
    @Test
    void parsesValidUntilHoursForTemporaryFacts() {
        LlmMemoryExtractor ex = extractor(routerNull());
        List<ExtractedMemory> out = ex.parse("""
            [{"content":"用户本周在上海出差","summary":"本周上海出差","tags":["travel"],"importance":5,"validUntilHours":72}]
            """);
        assertEquals(1, out.size());
        Instant vu = out.get(0).validUntil();
        assertNotNull(vu);
        Instant expectedFloor = Instant.now().plus(Duration.ofHours(70));
        Instant expectedCeil = Instant.now().plus(Duration.ofHours(74));
        assertTrue(vu.isAfter(expectedFloor) && vu.isBefore(expectedCeil),
            "validUntil should be ~72h out, got " + vu);
    }

    @Test
    void parsesIsoValidUntilAndToleratesGarbage() {
        LlmMemoryExtractor ex = extractor(routerNull());
        List<ExtractedMemory> iso = ex.parse(
            "[{\"content\":\"临时事实\",\"importance\":6,\"validUntil\":\"2030-01-02T03:04:05Z\"}]");
        assertEquals(Instant.parse("2030-01-02T03:04:05Z"), iso.get(0).validUntil());
        // garbage / zero / negative TTL → no known expiry, entry survives
        for (String bad : List.of(
                "[{\"content\":\"临时事实\",\"importance\":6,\"validUntil\":\"not-a-date\"}]",
                "[{\"content\":\"临时事实\",\"importance\":6,\"validUntilHours\":0}]",
                "[{\"content\":\"临时事实\",\"importance\":6,\"validUntilHours\":-5}]")) {
            List<ExtractedMemory> parsed = ex.parse(bad);
            assertEquals(1, parsed.size());
            assertNull(parsed.get(0).validUntil());
        }
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
