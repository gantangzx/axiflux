package com.gantang.tianshu.impl.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.gantang.tianshu.api.llm.CompletionRequest;
import com.gantang.tianshu.api.llm.CompletionResponse;
import com.gantang.tianshu.api.llm.LlmClient;
import com.gantang.tianshu.api.memory.ContextAssembler;
import com.gantang.tianshu.api.session.Message;
import com.gantang.tianshu.api.session.Session;
import com.gantang.tianshu.impl.memory.HeuristicTokenCounter;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CompactionService}: horizon view, tool-pair-safe split,
 * proactive vs overflow triggers, and summarizer-failure fallback.
 */
class CompactionServiceTest {

    private final HeuristicTokenCounter counter = HeuristicTokenCounter.INSTANCE;

    private Session sessionWithTurns(int turns, int repeat) {
        var mgr = new ReactiveAgentTest.InMemorySessionManager();
        Session s = mgr.getOrCreate("s1", "u1", "default", Map.of());
        for (int i = 0; i < turns; i++) {
            s.addUserMessage(("用户消息编号" + i + "，包含需要记住的关键事实" + i + "。").repeat(repeat), Map.of());
            s.addAssistantMessage(("助手回复编号" + i + "，给出了一些结论。").repeat(repeat), List.of());
        }
        return s;
    }

    @Test
    void viewWithoutHorizon_returnsFullHistory() {
        CompactionService svc = new CompactionService();
        Session s = sessionWithTurns(3, 3);
        assertEquals(s.getHistory(200).size(), svc.view(s).size());
    }

    @Test
    void findSplit_neverStartsTailOnOrphanToolResult() {
        Message u = Message.user("q");
        Message a1 = Message.assistant("a1", List.of());
        Message aWithToolCall = Message.assistant("calling tool", List.of());
        Message toolResult = Message.tool("call-1", "大块工具输出结果。".repeat(200));
        List<Message> msgs = List.of(u, a1, aWithToolCall, toolResult);

        // Tiny keep budget: token walk would start the tail on the big TOOL result.
        int split = CompactionService.findSplit(msgs, 40, counter);
        assertNotEquals(Message.Role.TOOL, msgs.get(split).role(),
            "tail must not start with an orphan TOOL result");
        // Safety pulled the boundary back over the assistant tool_calls message.
        assertEquals(Message.Role.ASSISTANT, msgs.get(split).role());
        assertTrue(split >= 2, "expected split to land at/before the assistant that issued the call");
    }

    @Test
    void compactForOverflow_summarizesOldMessages_andViewPrependsSummary() {
        CompactionService svc = new CompactionService();
        Session s = sessionWithTurns(20, 6);
        int fullSize = s.getHistory(200).size();

        LlmClient client = new StubSummaryClient("摘要：讨论了编号 0 到 19 的关键事实。");

        StepVerifier.create(svc.compactForOverflow(s, client, 8_000, counter))
            .assertNext(did -> assertTrue(did, "overflow compaction should run"))
            .verifyComplete();

        List<Message> view = svc.view(s);
        assertEquals(Message.Role.SYSTEM, view.get(0).role(), "view should lead with the rolling summary");
        assertTrue(view.get(0).content().contains(CompactionService.SUMMARY_MARKER));
        assertTrue(view.get(0).content().contains("编号 0 到 19"));
        assertTrue(view.size() < fullSize, "compacted view must be shorter than the full transcript");
    }

    @Test
    void maybeCompact_skipsSmallHistory() {
        CompactionService svc = new CompactionService();
        Session s = sessionWithTurns(1, 2);
        LlmClient client = new StubSummaryClient("unused");
        ContextAssembler.Budget budget = new ContextAssembler.Budget(128_000, 4_096, 0, 2_048);

        StepVerifier.create(svc.maybeCompact(s, client, budget, 0, counter))
            .assertNext(did -> assertFalse(did, "tiny history must not trigger compaction"))
            .verifyComplete();
    }

    @Test
    void maybeCompact_firesWhenHistoryExceedsWindow() {
        CompactionService svc = new CompactionService();
        Session s = sessionWithTurns(20, 9);
        LlmClient client = new StubSummaryClient("滚动摘要内容。");
        // Small window → available history budget far below the accumulated history.
        ContextAssembler.Budget budget = new ContextAssembler.Budget(6_000, 800, 0, 400);

        StepVerifier.create(svc.maybeCompact(s, client, budget, 0, counter))
            .assertNext(did -> assertTrue(did, "over-budget history should trigger proactive compaction"))
            .verifyComplete();

        List<Message> view = svc.view(s);
        assertEquals(Message.Role.SYSTEM, view.get(0).role());
    }

    @Test
    void summarizerOutage_stillShrinksContextWithDeterministicFallback() {
        CompactionService svc = new CompactionService();
        Session s = sessionWithTurns(20, 6);
        int fullSize = s.getHistory(200).size();
        LlmClient failing = new StubSummaryClient(null);  // complete() throws

        StepVerifier.create(svc.compactForOverflow(s, failing, 8_000, counter))
            .assertNext(did -> assertTrue(did, "overflow recovery must still shrink even if the summarizer is down"))
            .verifyComplete();

        List<Message> view = svc.view(s);
        assertTrue(view.size() < fullSize, "context must shrink");
        assertTrue(view.get(0).content().contains("已省略"), "fallback summary should mark dropped messages");
    }

    /** Minimal LlmClient used as the summarization model; {@link #complete} returns a fixed summary. */
    static class StubSummaryClient implements LlmClient {
        private final String summary;
        int completeCalls = 0;

        StubSummaryClient(String summary) { this.summary = summary; }

        @Override public String provider() { return "stub-summary"; }
        @Override public String primaryModel() { return "stub-summary-model"; }

        @Override
        public CompletionResponse complete(CompletionRequest request) {
            completeCalls++;
            if (summary == null) throw new RuntimeException("simulated summarizer outage");
            return CompletionResponse.builder().content(summary).build();
        }

        @Override
        public Flux<String> completeStream(CompletionRequest request) {
            return Flux.just(summary == null ? "" : summary);
        }

        @Override
        public CompletionResponse completeWithTools(CompletionRequest request, JsonNode tools) {
            return CompletionResponse.builder().content(summary).toolCalls(List.of()).build();
        }
    }
}
