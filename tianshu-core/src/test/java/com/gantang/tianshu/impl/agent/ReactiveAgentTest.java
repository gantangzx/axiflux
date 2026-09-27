package com.gantang.tianshu.impl.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.agent.AgentEvent;
import com.gantang.tianshu.api.agent.AgentHook;
import com.gantang.tianshu.api.agent.AgentResponse;
import com.gantang.tianshu.api.agent.AgentStatus;
import com.gantang.tianshu.api.llm.CompletionRequest;
import com.gantang.tianshu.api.llm.CompletionResponse;
import com.gantang.tianshu.api.llm.LlmClient;
import com.gantang.tianshu.api.llm.ModelRouter;
import com.gantang.tianshu.api.llm.RoutingContext;
import com.gantang.tianshu.api.memory.LongTermMemory;
import com.gantang.tianshu.api.session.Session;
import com.gantang.tianshu.api.session.SessionManager;
import com.gantang.tianshu.api.tool.Tool;
import com.gantang.tianshu.api.tool.ToolCall;
import com.gantang.tianshu.api.tool.ToolRegistry;
import com.gantang.tianshu.api.tool.ToolResult;
import com.gantang.tianshu.impl.memory.DefaultContextAssembler;
import com.gantang.tianshu.impl.tool.DefaultToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for ReactiveAgent tool-call loop.
 * Uses a stub LlmClient that can simulate text-only or tool-call-then-text responses.
 */
class ReactiveAgentTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private ToolRegistry toolRegistry;
    private SessionManager sessionManager;
    private StubLlmClient llmClient;
    private ReactiveAgent agent;

    @BeforeEach
    void setUp() {
        toolRegistry = new DefaultToolRegistry();
        sessionManager = new InMemorySessionManager();
        llmClient = new StubLlmClient();

        ModelRouter router = ctx -> llmClient;
        DefaultContextAssembler assembler = new DefaultContextAssembler(null, 50, 5);

        agent = new ReactiveAgent(router, toolRegistry, assembler, null, sessionManager, OM);
    }

    @Test
    void textOnlyResponse_returnsSuccess() {
        llmClient.setTextResponse("Hello from LLM");

        AgentContext ctx = AgentContext.builder()
                .sessionId("s1").userId("u1")
                .currentQuery("Hi").systemPrompt("You are a helper.")
                .build();

        StepVerifier.create(agent.process(ctx))
                .assertNext(resp -> {
                    assertEquals(AgentResponse.Status.SUCCESS, resp.status());
                    assertEquals("Hello from LLM", resp.content());
                })
                .verifyComplete();
    }

    @Test
    void sessionOwnedByAnotherUser_refusesTurnBeforeWriting() {
        // Audit authz P2-2 (TOCTOU): the session already exists but is owned by
        // somebody else (another caller won the create race). The turn must be
        // refused with an error — and crucially, nothing may be appended to the
        // foreign session's history.
        sessionManager.getOrCreate("foreign-s", "owner-u", null, Map.of());

        AgentContext ctx = AgentContext.builder()
                .sessionId("foreign-s").userId("attacker-u")
                .currentQuery("inject into your history")
                .build();

        StepVerifier.create(agent.process(ctx))
                .assertNext(resp -> {
                    assertEquals(AgentResponse.Status.ERROR, resp.status());
                    assertTrue(resp.content() != null && resp.content().contains("session conflict"),
                        "expected conflict explanation, got: " + resp.content());
                })
                .verifyComplete();

        Session foreign = sessionManager.get("foreign-s").orElseThrow();
        assertEquals(0, foreign.messages().size(),
            "a refused turn must not write into the foreign session's history");
    }

    @Test
    void toolCallLoop_executesToolAndReturnsFinalText() {
        // Register a simple echo tool
        toolRegistry.register(new Tool() {
            @Override public String name() { return "echo"; }
            @Override public String description() { return "Echoes input"; }
            @Override public JsonNode parameters() {
                ObjectNode node = OM.createObjectNode();
                ObjectNode props = OM.createObjectNode();
                ObjectNode textProp = OM.createObjectNode();
                textProp.put("type", "string");
                textProp.put("description", "Text to echo");
                props.set("text", textProp);
                node.set("properties", props);
                var required = OM.createArrayNode();
                required.add("text");
                node.set("required", required);
                return node;
            }
            @Override public ToolResult execute(String callId, Map<String, Object> params, AgentContext ctx) {
                return ToolResult.success(callId, "echo: " + params.get("text"));
            }
        });

        // First call: LLM requests tool; second call: LLM returns text
        AtomicInteger callCount = new AtomicInteger(0);
        llmClient.setHandler((req, tools) -> {
            int n = callCount.incrementAndGet();
            if (n == 1) {
                return CompletionResponse.builder()
                        .content("")
                        .toolCalls(List.of(new ToolCall("call_1", "echo", Map.of("text", "hello"))))
                        .finishReason("tool_calls")
                        .build();
            }
            return CompletionResponse.builder()
                    .content("The echo says hello")
                    .toolCalls(List.of())
                    .finishReason("stop")
                    .build();
        });

        AgentContext ctx = AgentContext.builder()
                .sessionId("s2").userId("u1")
                .currentQuery("Echo hello")
                .build();

        StepVerifier.create(agent.process(ctx))
                .assertNext(resp -> {
                    assertEquals(AgentResponse.Status.SUCCESS, resp.status());
                    assertEquals("The echo says hello", resp.content());
                })
                .verifyComplete();

        // Verify LLM was called twice
        assertEquals(2, callCount.get());

        // Verify session history has user, assistant(tool_call), tool, assistant(text)
        Session session = sessionManager.get("s2").orElseThrow();
        List<com.gantang.tianshu.api.session.Message> history = session.getHistory(50);
        assertTrue(history.size() >= 4, "Expected at least 4 messages, got " + history.size());
        assertEquals(com.gantang.tianshu.api.session.Message.Role.USER, history.get(0).role());
        assertEquals(com.gantang.tianshu.api.session.Message.Role.ASSISTANT, history.get(1).role());
        assertEquals(com.gantang.tianshu.api.session.Message.Role.TOOL, history.get(2).role());
        assertEquals(com.gantang.tianshu.api.session.Message.Role.ASSISTANT, history.get(3).role());
    }

    @Test
    void toolCallLoop_sendsToolDefinitionsWithNameAndDescription() {
        toolRegistry.register(new Tool() {
            @Override public String name() { return "calculator"; }
            @Override public String description() { return "Does math"; }
            @Override public JsonNode parameters() {
                ObjectNode node = OM.createObjectNode();
                node.set("properties", OM.createObjectNode());
                node.set("required", OM.createArrayNode());
                return node;
            }
            @Override public ToolResult execute(String callId, Map<String, Object> params, AgentContext ctx) {
                return ToolResult.success(callId, "42");
            }
        });

        AtomicInteger callCount = new AtomicInteger(0);
        llmClient.setHandler((req, tools) -> {
            int n = callCount.incrementAndGet();
            if (n == 1) {
                // Verify tools JSON has name and description
                assertNotNull(tools);
                assertTrue(tools.isArray());
                JsonNode firstTool = tools.get(0);
                assertEquals("calculator", firstTool.get("name").asText());
                assertEquals("Does math", firstTool.get("description").asText());
                assertNotNull(firstTool.get("properties"));
                assertNotNull(firstTool.get("required"));

                return CompletionResponse.builder()
                        .content("")
                        .toolCalls(List.of(new ToolCall("c1", "calculator", Map.of())))
                        .finishReason("tool_calls")
                        .build();
            }
            return CompletionResponse.builder()
                    .content("Answer is 42")
                    .toolCalls(List.of())
                    .finishReason("stop")
                    .build();
        });

        AgentContext ctx = AgentContext.builder()
                .sessionId("s3").userId("u1")
                .currentQuery("What is 6*7?")
                .build();

        StepVerifier.create(agent.process(ctx))
                .assertNext(resp -> assertEquals("Answer is 42", resp.content()))
                .verifyComplete();
    }

    @Test
    void toolCallLoop_asyncToolOverrideRunsThroughReactiveSpi() {
        // Tool that ONLY implements the reactive path; sync execute() must never be invoked.
        toolRegistry.register(new Tool() {
            @Override public String name() { return "async_echo"; }
            @Override public String description() { return "Echoes via a non-blocking path"; }
            @Override public JsonNode parameters() {
                ObjectNode node = OM.createObjectNode();
                ObjectNode props = OM.createObjectNode();
                ObjectNode textProp = OM.createObjectNode();
                textProp.put("type", "string");
                props.set("text", textProp);
                node.set("properties", props);
                var required = OM.createArrayNode();
                required.add("text");
                node.set("required", required);
                return node;
            }
            @Override
            public ToolResult execute(String callId, Map<String, Object> params, AgentContext ctx) {
                throw new AssertionError("sync execute must not be called for a reactive tool");
            }
            @Override
            public Mono<ToolResult> executeReactive(String callId, Map<String, Object> params, AgentContext ctx) {
                return Mono.delay(java.time.Duration.ofMillis(10))
                    .map(t -> ToolResult.success(callId, "async: " + params.get("text")));
            }
        });

        AtomicInteger callCount = new AtomicInteger(0);
        llmClient.setHandler((req, tools) -> {
            int n = callCount.incrementAndGet();
            if (n == 1) {
                return CompletionResponse.builder()
                        .content("")
                        .toolCalls(List.of(new ToolCall("call_1", "async_echo", Map.of("text", "hello"))))
                        .finishReason("tool_calls")
                        .build();
            }
            return CompletionResponse.builder()
                    .content("Async tool finished")
                    .toolCalls(List.of())
                    .finishReason("stop")
                    .build();
        });

        AgentContext ctx = AgentContext.builder()
                .sessionId("s-async").userId("u1")
                .currentQuery("Echo hello asynchronously")
                .build();

        StepVerifier.create(agent.process(ctx))
                .assertNext(resp -> {
                    assertEquals(AgentResponse.Status.SUCCESS, resp.status());
                    assertEquals("Async tool finished", resp.content());
                })
                .verifyComplete();

        // Tool result persisted to history came from the reactive path.
        Session session = sessionManager.get("s-async").orElseThrow();
        List<com.gantang.tianshu.api.session.Message> history = session.getHistory(50);
        assertTrue(history.stream().anyMatch(m -> m.role() == com.gantang.tianshu.api.session.Message.Role.TOOL
                && m.content() != null && m.content().contains("async: hello")),
            "reactive tool result must be persisted to session history");
    }

    @Test
    void llmAuthFailure_returnsErrorResponseWithAuthKind() {
        // Simulate the provider rejecting the request with an authentication error.
        llmClient.setHandler((req, tools) -> {
            throw new RuntimeException("401 Unauthorized: invalid or missing api key");
        });

        AgentContext ctx = AgentContext.builder()
                .sessionId("s-auth").userId("u1")
                .currentQuery("hi")
                .build();

        StepVerifier.create(agent.process(ctx))
                .assertNext(resp -> {
                    assertEquals(AgentResponse.Status.ERROR, resp.status());
                    assertEquals("AUTH_CONFIG", resp.metadata().get("errorKind"),
                        "auth failures must be classified for the 503 mapping");
                    assertTrue(resp.content().contains("API key"));
                })
                .verifyComplete();
    }

    @Test
    void llmUnavailable_returnsErrorResponseWithUnavailableKind() {
        llmClient.setHandler((req, tools) -> {
            throw new RuntimeException(new java.net.ConnectException("Connection refused"));
        });

        AgentContext ctx = AgentContext.builder()
                .sessionId("s-down").userId("u1")
                .currentQuery("hi")
                .build();

        StepVerifier.create(agent.process(ctx))
                .assertNext(resp -> {
                    assertEquals(AgentResponse.Status.ERROR, resp.status());
                    assertEquals("UNAVAILABLE", resp.metadata().get("errorKind"));
                })
                .verifyComplete();
    }

    @Test
    void contextOverflow_compactsAndRetriesUntilSuccess() {
        // Pre-seed a long history so overflow compaction has old turns to summarize.
        Session pre = sessionManager.getOrCreate("s-overflow", "u1", "default", Map.of());
        for (int i = 0; i < 16; i++) {
            pre.addUserMessage(("历史问题内容编号" + i + "，包含关键事实。").repeat(40), Map.of());
            pre.addAssistantMessage(("历史回答内容编号" + i + "，给出结论。").repeat(40), List.of());
        }

        llmClient.setContextWindowTokens(8_000);
        llmClient.setTextResponse("历史摘要：涵盖编号 0-15 的讨论与结论。");  // used by the summarizer
        AtomicInteger toolCalls = new AtomicInteger();
        llmClient.setHandler((req, tools) -> {
            int n = toolCalls.incrementAndGet();
            if (n == 1) {
                throw new RuntimeException("400: This model's maximum context length is 8000 tokens. "
                    + "Your messages resulted in 12000 tokens. Please reduce the length.");
            }
            return CompletionResponse.builder()
                    .content("recovered-after-compaction")
                    .toolCalls(List.of())
                    .finishReason("stop")
                    .build();
        });

        AgentContext ctx = AgentContext.builder()
                .sessionId("s-overflow").userId("u1")
                .currentQuery("继续刚才的话题")
                .build();

        StepVerifier.create(agent.process(ctx))
                .assertNext(resp -> {
                    assertEquals(AgentResponse.Status.SUCCESS, resp.status(),
                        "overflow must be recovered by compaction+retry, not surface as error");
                    assertEquals("recovered-after-compaction", resp.content());
                })
                .verifyComplete();
        assertTrue(toolCalls.get() >= 2, "expected the overflowed call to be retried; was " + toolCalls.get());
    }

    @Test
    void providerUnavailable_fallsBackToNextProvider() {
        StubLlmClient down = new StubLlmClient();
        down.setHandler((req, tools) -> {
            throw new RuntimeException(new java.net.ConnectException("Connection refused"));
        });
        StubLlmClient backup = new StubLlmClient();
        backup.setTextResponse("answer-from-backup");

        ModelRouter chainRouter = new ModelRouter() {
            @Override public LlmClient route(RoutingContext c) { return down; }
            @Override public java.util.List<LlmClient> routeChain(RoutingContext c) {
                return java.util.List.of(down, backup);
            }
        };
        DefaultContextAssembler assembler = new DefaultContextAssembler(null, 50, 5);
        ReactiveAgent chainedAgent = new ReactiveAgent(chainRouter, toolRegistry, assembler, null, sessionManager, OM);

        AgentContext ctx = AgentContext.builder()
                .sessionId("s-fallback").userId("u1")
                .currentQuery("hi")
                .build();

        StepVerifier.create(chainedAgent.process(ctx))
                .assertNext(resp -> {
                    assertEquals(AgentResponse.Status.SUCCESS, resp.status(),
                        "a transient failure on the primary must fall through to the backup provider");
                    assertEquals("answer-from-backup", resp.content());
                })
                .verifyComplete();
    }

    @Test
    void lifecycleHooks_fireAroundTurnAndIsolateFailures() {
        List<String> fired = Collections.synchronizedList(new ArrayList<>());
        AgentHook good = new AgentHook() {
            @Override public void onTurnStart(AgentContext c, Session s) { fired.add("start"); }
            @Override public void onBeforeModelCall(AgentContext c, List<com.gantang.tianshu.api.session.Message> m,
                                                    List<JsonNode> t) { fired.add("model:" + m.size()); }
            @Override public void onTurnEnd(AgentContext c, AgentResponse r) { fired.add("end:" + r.status()); }
        };
        AgentHook bad = new AgentHook() {
            @Override public void onTurnStart(AgentContext c, Session s) { throw new RuntimeException("boom"); }
        };
        ReactiveAgent hooked = new ReactiveAgent(ctx -> llmClient, toolRegistry,
                new DefaultContextAssembler(null, 50, 5), null, sessionManager, OM)
                .withHooks(List.of(bad, good));
        llmClient.setTextResponse("hooked-ok");

        AgentContext ctx = AgentContext.builder()
                .sessionId("s-hook").userId("u1").currentQuery("hi").build();

        StepVerifier.create(hooked.process(ctx))
                .assertNext(resp -> assertEquals(AgentResponse.Status.SUCCESS, resp.status()))
                .verifyComplete();
        assertTrue(fired.contains("start"), "start hook must fire even after an earlier hook threw: " + fired);
        assertTrue(fired.stream().anyMatch(s -> s.startsWith("model:")), "before-model hook fired");
        assertTrue(fired.stream().anyMatch(s -> s.startsWith("end:SUCCESS")), "end hook fired");
    }

    @Test
    void modelCallTimeout_fallsBackToNextProvider() {
        StubLlmClient wedged = new StubLlmClient();
        wedged.setHandler((req, tools) -> {
            try { Thread.sleep(5_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return CompletionResponse.builder()
                    .content("too-late").toolCalls(List.of()).finishReason("stop").build();
        });
        StubLlmClient backup = new StubLlmClient();
        backup.setTextResponse("fast-backup");
        ModelRouter chainRouter = new ModelRouter() {
            @Override public LlmClient route(RoutingContext c) { return wedged; }
            @Override public List<LlmClient> routeChain(RoutingContext c) { return List.of(wedged, backup); }
        };
        ReactiveAgent chained = new ReactiveAgent(chainRouter, toolRegistry,
                new DefaultContextAssembler(null, 50, 5), null, sessionManager, OM)
                .withLlmCallTimeout(java.time.Duration.ofMillis(300));

        AgentContext ctx = AgentContext.builder()
                .sessionId("s-wdt").userId("u1").currentQuery("hi").build();

        StepVerifier.create(chained.process(ctx))
                .assertNext(resp -> {
                    assertEquals(AgentResponse.Status.SUCCESS, resp.status(),
                        "a wedged model call must time out and fall back");
                    assertEquals("fast-backup", resp.content());
                })
                .verifyComplete();
    }

    @Test
    void sameSession_turnsAreSerialized() {
        List<String> trace = Collections.synchronizedList(new ArrayList<>());
        AgentHook tracing = new AgentHook() {
            @Override public void onTurnStart(AgentContext c, Session s) { trace.add("start:" + c.currentQuery()); }
            @Override public void onTurnEnd(AgentContext c, AgentResponse r) { trace.add("end:" + c.currentQuery()); }
        };
        AtomicInteger calls = new AtomicInteger();
        llmClient.setHandler((req, tools) -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                try { Thread.sleep(400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return CompletionResponse.builder()
                    .content(n == 1 ? "slow-done" : "fast-done")
                    .toolCalls(List.of()).finishReason("stop").build();
        });
        ReactiveAgent traced = new ReactiveAgent(ctx -> llmClient, toolRegistry,
                new DefaultContextAssembler(null, 50, 5), null, sessionManager, OM)
                .withHooks(List.of(tracing));

        AgentContext slow = AgentContext.builder()
                .sessionId("s-ser").userId("u1").currentQuery("slow").build();
        AgentContext fast = AgentContext.builder()
                .sessionId("s-ser").userId("u1").currentQuery("fast").build();

        reactor.core.Disposable d1 = traced.processStream(slow).subscribe();
        try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        reactor.core.Disposable d2 = traced.processStream(fast).subscribe();
        try { Thread.sleep(900); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        d1.dispose();
        d2.dispose();

        assertEquals(List.of("start:slow", "end:slow", "start:fast", "end:fast"), trace,
            "the queued turn must not start until the earlier turn finished");
    }

    @Test
    void interrupt_cascadesToQueuedTurnsUntilQueueDrains() throws Exception {
        // Turn1 runs slowly; turn2 queues behind it. An interrupt while turn1 is
        // mid-flight must abort turn1 AND turn2 (stop covers pending work), but a
        // fresh turn3 submitted after the queue drained must run normally.
        AtomicInteger calls = new AtomicInteger();
        java.util.concurrent.CountDownLatch turn1InModel = new java.util.concurrent.CountDownLatch(1);
        llmClient.setHandler((req, tools) -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                turn1InModel.countDown();
                try { Thread.sleep(600); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return CompletionResponse.builder()
                    .content("done-" + n).toolCalls(List.of()).finishReason("stop").build();
        });

        AgentContext c1 = AgentContext.builder().sessionId("s-int").userId("u1").currentQuery("one").build();
        AgentContext c2 = AgentContext.builder().sessionId("s-int").userId("u1").currentQuery("two").build();

        java.util.List<AgentResponse> r1 = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.List<AgentResponse> r2 = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.concurrent.CountDownLatch done1 = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done2 = new java.util.concurrent.CountDownLatch(1);

        agent.process(c1).subscribe(r -> { r1.add(r); done1.countDown(); });
        assertTrue(turn1InModel.await(5, java.util.concurrent.TimeUnit.SECONDS),
            "turn1 must reach the model call");
        // turn2 queued behind turn1
        agent.process(c2).subscribe(r -> { r2.add(r); done2.countDown(); });
        try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // stop the session: covers the running turn and everything queued behind it
        agent.interrupt("s-int");

        assertTrue(done1.await(5, java.util.concurrent.TimeUnit.SECONDS), "turn1 must terminate");
        assertTrue(done2.await(5, java.util.concurrent.TimeUnit.SECONDS), "turn2 must terminate");
        // The interrupt lands while turn1's blocking model call is in flight; the
        // loop can only observe the flag at the next iteration boundary, and this
        // stub returns a terminal stop on the first call, so turn1 completes
        // normally. The regression being pinned is the QUEUED turn: its flag must
        // survive turn1's doFinally instead of being silently removed.
        assertEquals(AgentResponse.Status.SUCCESS, r1.get(0).status());
        assertEquals(AgentResponse.Status.INTERRUPTED, r2.get(0).status(),
            "queued turn must inherit the interrupt instead of running (previously swallowed)");

        // queue drained → the flag must be cleared; a fresh message runs normally
        AgentResponse r3 = agent.process(
                AgentContext.builder().sessionId("s-int").userId("u1").currentQuery("three").build())
            .block(java.time.Duration.ofSeconds(5));
        assertEquals(AgentResponse.Status.SUCCESS, r3.status(),
            "after the queue drained, a new turn must not be poisoned by a stale interrupt");
    }

    @Test
    void unknownTool_returnsErrorAndContinues() {
        AtomicInteger callCount = new AtomicInteger(0);
        llmClient.setHandler((req, tools) -> {
            int n = callCount.incrementAndGet();
            if (n == 1) {
                return CompletionResponse.builder()
                        .content("")
                        .toolCalls(List.of(new ToolCall("c1", "nonexistent_tool", Map.of())))
                        .finishReason("tool_calls")
                        .build();
            }
            return CompletionResponse.builder()
                    .content("Tool not available")
                    .toolCalls(List.of())
                    .finishReason("stop")
                    .build();
        });

        AgentContext ctx = AgentContext.builder()
                .sessionId("s4").userId("u1")
                .currentQuery("Call a tool")
                .build();

        StepVerifier.create(agent.process(ctx))
                .assertNext(resp -> {
                    assertEquals(AgentResponse.Status.SUCCESS, resp.status());
                    assertEquals("Tool not available", resp.content());
                })
                .verifyComplete();
    }

    @Test
    void maxIterations_returnsMaxIterationsStatus() {
        // Always return tool calls, never text
        llmClient.setHandler((req, tools) -> CompletionResponse.builder()
                .content("")
                .toolCalls(List.of(new ToolCall("c1", "echo", Map.of("text", "x"))))
                .finishReason("tool_calls")
                .build());

        toolRegistry.register(new Tool() {
            @Override public String name() { return "echo"; }
            @Override public String description() { return "Echo"; }
            @Override public JsonNode parameters() {
                ObjectNode node = OM.createObjectNode();
                ObjectNode props = OM.createObjectNode();
                ObjectNode textProp = OM.createObjectNode();
                textProp.put("type", "string");
                props.set("text", textProp);
                node.set("properties", props);
                var required = OM.createArrayNode();
                required.add("text");
                node.set("required", required);
                return node;
            }
            @Override public ToolResult execute(String callId, Map<String, Object> params, AgentContext ctx) {
                return ToolResult.success(callId, "done");
            }
        });

        AgentContext ctx = AgentContext.builder()
                .sessionId("s5").userId("u1")
                .currentQuery("Loop forever")
                .build();

        StepVerifier.create(agent.process(ctx))
                .assertNext(resp ->
                    assertEquals(AgentResponse.Status.MAX_ITERATIONS, resp.status()))
                .verifyComplete();
    }

    @Test
    void tokenUsageAccumulatesAcrossToolLoopIterations() {
        toolRegistry.register(new Tool() {
            @Override public String name() { return "echo"; }
            @Override public String description() { return "Echoes input"; }
            @Override public JsonNode parameters() {
                ObjectNode node = OM.createObjectNode();
                node.set("properties", OM.createObjectNode());
                return node;
            }
            @Override public ToolResult execute(String callId, Map<String, Object> params, AgentContext ctx) {
                return ToolResult.success(callId, "echo: " + params.get("text"));
            }
        });

        AtomicInteger callCount = new AtomicInteger(0);
        llmClient.setHandler((req, tools) -> {
            int n = callCount.incrementAndGet();
            if (n == 1) {
                return CompletionResponse.builder()
                        .content("").toolCalls(List.of(new ToolCall("call_1", "echo", Map.of("text", "hi"))))
                        .finishReason("tool_calls")
                        .inputTokens(100).outputTokens(20)
                        .build();
            }
            return CompletionResponse.builder()
                    .content("done").toolCalls(List.of()).finishReason("stop")
                    .inputTokens(30).outputTokens(50)
                    .build();
        });

        AgentContext ctx = AgentContext.builder()
                .sessionId("s-usage").userId("u1").currentQuery("go").build();

        StepVerifier.create(agent.process(ctx))
                .assertNext(resp -> {
                    assertEquals(AgentResponse.Status.SUCCESS, resp.status());
                    // P1-2: per-call usage sums across the multi-iteration tool loop.
                    assertEquals(130, resp.metadata().get("inputTokens"));
                    assertEquals(70, resp.metadata().get("outputTokens"));
                    assertEquals(200, resp.metadata().get("totalTokens"));
                    assertEquals(2, resp.metadata().get("modelCalls"));
                })
                .verifyComplete();
    }

    private Tool noopTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name + " tool"; }
            @Override public JsonNode parameters() {
                ObjectNode node = OM.createObjectNode();
                node.set("properties", OM.createObjectNode());
                return node;
            }
            @Override public ToolResult execute(String callId, Map<String, Object> params, AgentContext ctx) {
                return ToolResult.success(callId, "ok");
            }
        };
    }

    @Test
    void delegationGuideIsInjected_whenSpawnTaskAvailable() {
        toolRegistry.register(noopTool("spawn_task"));
        AtomicBoolean sawGuide = new AtomicBoolean(false);
        llmClient.setHandler((req, tools) -> {
            sawGuide.set(req.messages().stream().anyMatch(m -> m.content() != null
                && m.content().contains("任务委派原则")
                && m.content().contains("spawn_task")));
            return CompletionResponse.builder().content("ok").toolCalls(List.of()).build();
        });

        AgentContext ctx = AgentContext.builder()
                .sessionId("s-spawn-guide").userId("u1").currentQuery("hi").build();
        StepVerifier.create(agent.process(ctx))
                .assertNext(resp -> assertEquals(AgentResponse.Status.SUCCESS, resp.status()))
                .verifyComplete();
        assertTrue(sawGuide.get(), "system prompt must teach the delegation pattern when spawn_task exists");
    }

    @Test
    void delegationGuideIsOmitted_whenSpawnTaskAbsent() {
        AtomicBoolean sawGuide = new AtomicBoolean(false);
        llmClient.setHandler((req, tools) -> {
            sawGuide.set(req.messages().stream().anyMatch(m -> m.content() != null
                && m.content().contains("任务委派原则")));
            return CompletionResponse.builder().content("ok").toolCalls(List.of()).build();
        });

        AgentContext ctx = AgentContext.builder()
                .sessionId("s-no-spawn").userId("u1").currentQuery("hi").build();
        StepVerifier.create(agent.process(ctx)).expectNextCount(1).verifyComplete();
        assertFalse(sawGuide.get(), "delegation guide must not be injected for agents without spawn_task");
    }

    // ==== P2-3: assembling processStream must not mark QUEUED before subscription ====

    @Test
    void processStream_doesNotMarkQueued_untilSubscribed() {
        // P2-3: processStream previously marked the session QUEUED at assembly
        // time (caller thread). If the caller assembled but never subscribed, the
        // stale QUEUED flag lingered. Now the flag is only set on subscription.
        llmClient.setTextResponse("ok");
        AgentContext ctx = AgentContext.builder()
                .sessionId("s-nosub").userId("u1").currentQuery("hi").build();

        agent.processStream(ctx); // assembled, never subscribed
        assertEquals(AgentStatus.State.IDLE, agent.getStatus("s-nosub").state(),
            "assembling processStream without subscribing must not mark QUEUED (P2-3)");

        // Once subscribed, the turn runs and completes normally.
        StepVerifier.create(agent.processStream(ctx).filter(e -> e.type() == AgentEvent.Type.DONE).next())
            .expectNextCount(1)
            .verifyComplete();
        // After completion the queue drains and the flag is cleared.
        assertEquals(AgentStatus.State.IDLE, agent.getStatus("s-nosub").state());
    }

    // ===== Stub LLM Client =====

    interface ResponseHandler {
        CompletionResponse handle(CompletionRequest request, JsonNode tools);
    }

    static class StubLlmClient implements LlmClient {
        private String textResponse;
        private ResponseHandler handler;
        private int contextWindow = 128_000;

        void setTextResponse(String text) { this.textResponse = text; }
        void setHandler(ResponseHandler h) { this.handler = h; }
        void setContextWindowTokens(int tokens) { this.contextWindow = tokens; }

        @Override public String provider() { return "stub"; }
        @Override public String primaryModel() { return "stub-model"; }
        @Override public int contextWindowTokens() { return contextWindow; }

        @Override
        public CompletionResponse complete(CompletionRequest request) {
            return CompletionResponse.builder().content(textResponse != null ? textResponse : "ok").build();
        }

        @Override
        public Flux<String> completeStream(CompletionRequest request) {
            return Flux.just(textResponse != null ? textResponse : "ok");
        }

        @Override
        public CompletionResponse completeWithTools(CompletionRequest request, JsonNode tools) {
            if (handler != null) return handler.handle(request, tools);
            return CompletionResponse.builder()
                    .content(textResponse != null ? textResponse : "ok")
                    .toolCalls(List.of())
                    .build();
        }
    }

    // ===== In-memory Session Manager (lightweight) =====

    static class InMemorySessionManager implements SessionManager {
        private final Map<String, Session> sessions = new ConcurrentHashMap<>();

        @Override
        public Session getOrCreate(String sessionId, String userId, String agentId, Map<String, Object> metadata) {
            return sessions.computeIfAbsent(sessionId, id -> new SimpleSession(id, userId, agentId, metadata));
        }

        @Override public Mono<Void> save(Session session) { return Mono.empty(); }
        @Override public Mono<Void> delete(String sessionId) { sessions.remove(sessionId); return Mono.empty(); }
        @Override public Optional<Session> get(String sessionId) { return Optional.ofNullable(sessions.get(sessionId)); }
        @Override public Flux<Session> listByUser(String userId) { return Flux.fromIterable(sessions.values()); }
        @Override public Flux<Session> listActiveByUser(String userId) { return Flux.fromIterable(sessions.values()); }
    }

    static class SimpleMessageStore implements Session.MessageStore {
        private final List<com.gantang.tianshu.api.session.Message> list = Collections.synchronizedList(new ArrayList<>());
        @Override public void append(com.gantang.tianshu.api.session.Message m) { list.add(m); }
        @Override public List<com.gantang.tianshu.api.session.Message> getRecent(int count) {
            int from = Math.max(0, list.size() - count);
            return new ArrayList<>(list.subList(from, list.size()));
        }
        @Override public List<com.gantang.tianshu.api.session.Message> getAll() { return new ArrayList<>(list); }
        @Override public void clear() { list.clear(); }
        @Override public int size() { return list.size(); }
    }

    static class SimpleSession implements Session {
        private final String sessionId, userId, agentId;
        private final Instant createdAt = Instant.now();
        private final Session.MessageStore messages = new SimpleMessageStore();
        private final Map<String, Object> metadata;
        private State state = State.ACTIVE;

        SimpleSession(String id, String uid, String aid, Map<String, Object> meta) {
            this.sessionId = id; this.userId = uid; this.agentId = aid;
            this.metadata = meta != null ? new ConcurrentHashMap<>(meta) : new ConcurrentHashMap<>();
        }

        @Override public String sessionId() { return sessionId; }
        @Override public String userId() { return userId; }
        @Override public String agentId() { return agentId; }
        @Override public State state() { return state; }
        @Override public Session.MessageStore messages() { return messages; }
        @Override public Map<String, Object> metadata() { return metadata; }
        @Override public void updateMetadata(String k, Object v) { metadata.put(k, v); }
        @Override public List<com.gantang.tianshu.api.session.Message> getHistory(int n) { return messages.getRecent(n); }
        @Override public List<com.gantang.tianshu.api.session.Message> getHistorySince(Instant since) { return messages.getAll(); }
        @Override public void addUserMessage(String content, Map<String, Object> att) {
            messages.append(com.gantang.tianshu.api.session.Message.user(content));
        }
        @Override public void addAssistantMessage(String content, List<ToolCall> toolCalls) {
            messages.append(com.gantang.tianshu.api.session.Message.assistant(content, toolCalls));
        }
        @Override public void addToolResult(String callId, ToolResult result) {
            messages.append(com.gantang.tianshu.api.session.Message.tool(callId, result.displayContent()));
        }
        @Override public void addSystemMessage(String content) {
            messages.append(com.gantang.tianshu.api.session.Message.system(content));
        }
        @Override public void markDone() { state = State.CLOSED; }
        @Override public void close() { state = State.CLOSED; }
    }
}
