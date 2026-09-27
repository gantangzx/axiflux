package com.gantang.tianshu.impl.agent;

import com.gantang.tianshu.api.tool.ToolResultStore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.agent.AgentEvent;
import com.gantang.tianshu.api.agent.ApprovalManager;
import com.gantang.tianshu.api.session.Session;
import com.gantang.tianshu.api.tool.Tool;
import com.gantang.tianshu.api.tool.ToolCall;
import com.gantang.tianshu.api.tool.ToolResult;
import com.gantang.tianshu.impl.session.InMemorySessionManager;
import com.gantang.tianshu.impl.tool.DefaultToolRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct unit tests for the extracted {@link ToolExecutor} gate-to-result path:
 * policy DENY, ASK-with-no-manager graceful failure, and ASK emitting
 * APPROVAL_REQUIRED while a human decision is pending.
 */
class ToolExecutorDirectTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private static Tool riskyTool(AtomicBoolean executed) {
        return new Tool() {
            @Override public String name() { return "risky"; }
            @Override public String description() { return "a gated tool"; }
            @Override public boolean requiresApproval() { return true; }
            @Override public JsonNode parameters() {
                ObjectNode node = OM.createObjectNode();
                node.set("properties", OM.createObjectNode());
                return node;
            }
            @Override public ToolResult execute(String callId, Map<String, Object> params, AgentContext ctx) {
                executed.set(true);
                return ToolResult.success(callId, "ran");
            }
        };
    }

    private Session newSession(InMemorySessionManager mgr) {
        return mgr.getOrCreate("s1", "u1", "test-agent", Map.of());
    }

    private AgentContext ctx() {
        return AgentContext.builder().sessionId("s1").userId("u1").currentQuery("do it").build();
    }

    @Test
    void askWithNoApprovalManager_failsGracefully_andDoesNotRunTool() {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        AtomicBoolean executed = new AtomicBoolean(false);
        registry.register(riskyTool(executed));

        ToolExecutor executor = new ToolExecutor(registry, OM, "test-agent", null);
        Session session = newSession(new InMemorySessionManager());

        StepVerifier.create(executor.execute(session,
                new ToolCall("c1", "risky", Map.of()), ctx()))
            .assertNext(ev -> assertEquals(AgentEvent.Type.APPROVAL_REQUIRED, ev.type(),
                "gated tool first emits the approval prompt"))
            .assertNext(ev -> {
                assertEquals(AgentEvent.Type.TOOL_RESULT, ev.type());
                String content = ev.content() == null ? "" : ev.content();
                assertTrue(content.contains("Approval required") || content.contains("approval"),
                    "expected graceful approval-missing failure, got: " + content);
            })
            .verifyComplete();

        assertFalse(executed.get(), "the gated tool must never run without approval");
    }

    @Test
    void askWhilePending_emitsApprovalRequired_andDoesNotRunTool() {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        AtomicBoolean executed = new AtomicBoolean(false);
        registry.register(riskyTool(executed));

        // Approval manager that never resolves (human has not answered).
        ApprovalManager pending = new ApprovalManager() {
            @Override public Mono<ToolResult> submit(ApprovalRequest request) { return Mono.never(); }
            @Override public boolean approve(String callId, String approverId) { return false; }
            @Override public boolean reject(String callId, String approverId, String reason) { return false; }
            @Override public Optional<ApprovalRequest> get(String callId) { return Optional.empty(); }
            @Override public List<ApprovalRequest> listPending(String userId) { return List.of(); }
        };

        ToolExecutor executor = new ToolExecutor(registry, OM, "test-agent", null);
        executor.setApprovalManager(pending);
        Session session = newSession(new InMemorySessionManager());

        Flux<AgentEvent> events = executor.execute(session,
            new ToolCall("c1", "risky", Map.of()), ctx());

        StepVerifier.create(events)
            .assertNext(ev -> assertEquals(AgentEvent.Type.APPROVAL_REQUIRED, ev.type(),
                "first event must suspend for human approval"))
            .thenCancel()
            .verify();

        assertFalse(executed.get(), "tool must not run while approval is pending");
    }

    @Test
    void policyDeny_doesNotRunTool() {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        AtomicBoolean executed = new AtomicBoolean(false);
        registry.register(riskyTool(executed));

        ToolExecutor executor = new ToolExecutor(registry, OM, "test-agent", null);
        // Deny everything via a single always-deny policy rule.
        com.gantang.tianshu.api.tool.policy.ToolPolicy denyAll = (tool, args, context) ->
            com.gantang.tianshu.api.tool.policy.PolicyDecision.deny("test deny");
        executor.setPolicyChain(new com.gantang.tianshu.api.tool.policy.ToolPolicyChain(List.of(denyAll)));

        Session session = newSession(new InMemorySessionManager());

        StepVerifier.create(executor.execute(session,
                new ToolCall("c1", "risky", Map.of()), ctx()))
            .assertNext(ev -> {
                assertEquals(AgentEvent.Type.TOOL_RESULT, ev.type());
                String content = ev.content() == null ? "" : ev.content();
                assertTrue(content.contains("denied") || content.contains("deny"),
                    "expected policy-denial failure, got: " + content);
            })
            .verifyComplete();

        assertFalse(executed.get(), "DENY must prevent tool execution");
    }

    private static Tool bigTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "returns large output"; }
            @Override public JsonNode parameters() {
                ObjectNode node = OM.createObjectNode();
                node.set("properties", OM.createObjectNode());
                return node;
            }
            @Override
            public ToolResult execute(String callId, Map<String, Object> params, AgentContext ctx) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= 900; i++) {
                    sb.append("LINE").append(i).append(" ").append("x".repeat(30)).append('\n');
                }
                return ToolResult.success(callId, sb.toString());
            }
        };
    }

    @Test
    void oversizedResult_isParkedInSideStore_andTranscriptCarriesHandle() {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(bigTool("big_echo"));

        com.gantang.tianshu.impl.tool.support.InMemoryToolResultStore store =
            new com.gantang.tianshu.impl.tool.support.InMemoryToolResultStore();
        ToolExecutor executor = new ToolExecutor(registry, OM, "test-agent", null);
        executor.setToolResultStore(store);
        Session session = newSession(new InMemorySessionManager());

        java.util.List<AgentEvent> events = executor.execute(session,
                new ToolCall("c1", "big_echo", Map.of()), ctx())
            .collectList().block();
        assertEquals(1, events.size());
        String content = events.get(0).content();
        assertTrue(content.contains("ref://tool-result/tr_"),
            "pruned transcript must carry the retrieval handle, got: " + content.substring(0, Math.min(300, content.length())));
        assertEquals(1, store.size());
        assertTrue(store.sessionIds("s1").size() == 1);
        // the full content survived outside the transcript
        String id = com.gantang.tianshu.api.tool.ToolResultStore.idOf(
            content.substring(content.indexOf("ref://tool-result/")).split("[\" )]")[0]);
        assertTrue(store.fetch("s1", id).orElseThrow().content().contains("LINE900"));
    }

    @Test
    void parkedUntrustedOutput_staysBoundaryWrappedWhenReadBack() {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(bigTool("web_fetch"));
        com.gantang.tianshu.impl.tool.support.InMemoryToolResultStore store =
            new com.gantang.tianshu.impl.tool.support.InMemoryToolResultStore();
        registry.register(new com.gantang.tianshu.impl.tool.builtin.ResultReadTool(store));

        ToolExecutor executor = new ToolExecutor(registry, OM, "test-agent", null);
        executor.setToolResultStore(store);
        Session session = newSession(new InMemorySessionManager());

        String parked = executor.execute(session,
                new ToolCall("c1", "web_fetch", Map.of("url", "http://example.test")), ctx())
            .map(AgentEvent::content).blockLast();
        assertTrue(parked.contains("<<untrusted_tool_output"));
        int idx = parked.indexOf("ref://tool-result/");
        String handle = parked.substring(idx).split("[\" )]")[0];

        String readBack = executor.execute(session,
                new ToolCall("c2", "result_read", Map.of("ref", handle, "limit", 50)), ctx())
            .map(AgentEvent::content).blockLast();
        assertTrue(readBack.contains("<<untrusted_tool_output"),
            "result_read must inherit the untrusted boundary of its origin");
        assertTrue(readBack.contains("origin=web_fetch"));
        assertTrue(readBack.contains("LINE50"));
    }

    @Test
    void cacheableTool_replaysResultWithoutReExecuting_withinSession() {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        java.util.concurrent.atomic.AtomicInteger runs = new java.util.concurrent.atomic.AtomicInteger();
        registry.register(new Tool() {
            @Override public String name() { return "web_fetch"; }
            @Override public String description() { return "idempotent read"; }
            @Override public boolean requiresApproval() { return false; }
            @Override public JsonNode parameters() {
                ObjectNode node = OM.createObjectNode();
                node.set("properties", OM.createObjectNode());
                return node;
            }
            @Override public ToolResult execute(String callId, Map<String, Object> params, AgentContext ctx) {
                runs.incrementAndGet();
                return ToolResult.success(callId, "body-v1");
            }
        });

        ToolExecutor executor = new ToolExecutor(registry, OM, "test-agent", null);
        executor.setToolResultCache(
            new com.gantang.tianshu.impl.tool.support.InMemoryToolResultCache(
                java.time.Duration.ofMinutes(5), 100, 20_000),
            java.util.Set.of("web_fetch"));
        Session session = newSession(new InMemorySessionManager());

        ToolCall call1 = new ToolCall("c1", "web_fetch", Map.of("url", "https://x/a"));
        ToolCall call2 = new ToolCall("c2", "web_fetch", Map.of("url", "https://x/a"));
        String out1 = executor.execute(session, call1, ctx()).blockLast().content();
        String out2 = executor.execute(session, call2, ctx()).blockLast().content();

        assertTrue(out1.contains("body-v1"), "wrapped output should contain the raw result: " + out1);
        assertTrue(out2.contains("body-v1"), "cached replay should contain the raw result: " + out2);
        assertEquals(1, runs.get(), "identical read-only call must be served from cache");

        ToolCall call3 = new ToolCall("c3", "web_fetch", Map.of("url", "https://x/b"));
        executor.execute(session, call3, ctx()).blockLast();
        assertEquals(2, runs.get(), "different arguments must miss the cache");
    }

    @Test
    void concurrentIdenticalCacheableCalls_singleFlight_oneExecution() throws Exception {
        // P1-8: two identical read-only calls racing in the same session must not
        // double-execute — the in-flight dedupe shares one execution.
        DefaultToolRegistry registry = new DefaultToolRegistry();
        java.util.concurrent.atomic.AtomicInteger runs = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        registry.register(new Tool() {
            @Override public String name() { return "web_fetch"; }
            @Override public String description() { return "idempotent read"; }
            @Override public JsonNode parameters() {
                ObjectNode node = OM.createObjectNode();
                node.set("properties", OM.createObjectNode());
                return node;
            }
            @Override public ToolResult execute(String callId, Map<String, Object> params, AgentContext ctx) {
                runs.incrementAndGet();
                started.countDown();
                try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return ToolResult.success(callId, "body-v1");
            }
        });
        ToolExecutor executor = new ToolExecutor(registry, OM, "test-agent", null);
        executor.setToolResultCache(
            new com.gantang.tianshu.impl.tool.support.InMemoryToolResultCache(
                java.time.Duration.ofMinutes(5), 100, 20_000),
            java.util.Set.of("web_fetch"));
        Session session = newSession(new InMemorySessionManager());

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var f1 = pool.submit(() -> executor.execute(session,
                new ToolCall("c1", "web_fetch", Map.of("url", "https://x/a")), ctx()).blockLast().content());
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS), "first call should be executing");
            var f2 = pool.submit(() -> executor.execute(session,
                new ToolCall("c2", "web_fetch", Map.of("url", "https://x/a")), ctx()).blockLast().content());
            Thread.sleep(300); // let the second call reach the in-flight map
            release.countDown();
            String out1 = f1.get(10, java.util.concurrent.TimeUnit.SECONDS);
            String out2 = f2.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(out1.contains("body-v1"));
            assertTrue(out2.contains("body-v1"));
            assertEquals(1, runs.get(), "concurrent identical calls must single-flight");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void cachePutThrowingError_doesNotAbortTheTurn() {
        // P1-8: a cache implementation throwing an Error (not just RuntimeException)
        // must not interrupt the turn — caching is best-effort.
        DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(new Tool() {
            @Override public String name() { return "web_fetch"; }
            @Override public String description() { return "idempotent read"; }
            @Override public JsonNode parameters() {
                ObjectNode node = OM.createObjectNode();
                node.set("properties", OM.createObjectNode());
                return node;
            }
            @Override public ToolResult execute(String callId, Map<String, Object> params, AgentContext ctx) {
                return ToolResult.success(callId, "body-v1");
            }
        });
        com.gantang.tianshu.api.tool.ToolResultCache evilCache = new com.gantang.tianshu.api.tool.ToolResultCache() {
            @Override public Optional<CachedResult> get(String sessionId, String key) { return Optional.empty(); }
            @Override public void put(String sessionId, String key, CachedResult result) {
                throw new OutOfMemoryError("simulated cache failure");
            }
            @Override public void clearSession(String sessionId) { }
        };
        ToolExecutor executor = new ToolExecutor(registry, OM, "test-agent", null);
        executor.setToolResultCache(evilCache, java.util.Set.of("web_fetch"));
        Session session = newSession(new InMemorySessionManager());

        String out = executor.execute(session,
            new ToolCall("c1", "web_fetch", Map.of("url", "https://x/a")), ctx()).blockLast().content();
        assertTrue(out.contains("body-v1"),
            "cache Error must be swallowed and the tool result still returned: " + out);
    }

    @Test
    void approvalWaitWithoutManagerTimeout_failsClosedAfterFallbackTimeout() {
        // P1-9: an ApprovalManager whose submit Mono never resolves must not park
        // the turn forever — the executor applies a fallback ceiling and denies.
        DefaultToolRegistry registry = new DefaultToolRegistry();
        AtomicBoolean executed = new AtomicBoolean(false);
        registry.register(riskyTool(executed));
        ApprovalManager pending = new ApprovalManager() {
            @Override public Mono<ToolResult> submit(ApprovalRequest request) { return Mono.never(); }
            @Override public boolean approve(String callId, String approverId) { return false; }
            @Override public boolean reject(String callId, String approverId, String reason) { return false; }
            @Override public Optional<ApprovalRequest> get(String callId) { return Optional.empty(); }
            @Override public List<ApprovalRequest> listPending(String userId) { return List.of(); }
        };
        ToolExecutor executor = new ToolExecutor(registry, OM, "test-agent", null);
        executor.setApprovalManager(pending);
        executor.setApprovalTimeout(java.time.Duration.ofMillis(300));
        Session session = newSession(new InMemorySessionManager());

        StepVerifier.create(executor.execute(session,
                new ToolCall("c1", "risky", Map.of()), ctx()))
            .assertNext(ev -> assertEquals(AgentEvent.Type.APPROVAL_REQUIRED, ev.type()))
            .assertNext(ev -> {
                assertEquals(AgentEvent.Type.TOOL_RESULT, ev.type());
                String content = ev.content() == null ? "" : ev.content();
                assertTrue(content.contains("approval timed out"),
                    "expected fail-closed timeout denial, got: " + content);
            })
            .expectComplete()
            .verify(java.time.Duration.ofSeconds(10));

        assertFalse(executed.get(), "a timed-out approval must never run the tool");
    }

    @Test
    void nonCacheableTool_executesEveryTime() {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        java.util.concurrent.atomic.AtomicInteger runs = new java.util.concurrent.atomic.AtomicInteger();
        registry.register(new Tool() {
            @Override public String name() { return "file_write"; }
            @Override public String description() { return "mutating"; }
            @Override public boolean requiresApproval() { return false; }
            @Override public JsonNode parameters() {
                ObjectNode node = OM.createObjectNode();
                node.set("properties", OM.createObjectNode());
                return node;
            }
            @Override public ToolResult execute(String callId, Map<String, Object> params, AgentContext ctx) {
                runs.incrementAndGet();
                return ToolResult.success(callId, "ok");
            }
        });
        ToolExecutor executor = new ToolExecutor(registry, OM, "test-agent", null);
        executor.setToolResultCache(
            new com.gantang.tianshu.impl.tool.support.InMemoryToolResultCache(
                java.time.Duration.ofMinutes(5), 100, 20_000),
            java.util.Set.of("web_fetch"));
        Session session = newSession(new InMemorySessionManager());
        executor.execute(session, new ToolCall("c1", "file_write", Map.of("path", "a")), ctx()).blockLast();
        executor.execute(session, new ToolCall("c2", "file_write", Map.of("path", "a")), ctx()).blockLast();
        assertEquals(2, runs.get());
    }
}
