package com.gantang.tianshu.impl.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.tianshu.api.agent.Agent;
import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.agent.AgentEvent;
import com.gantang.tianshu.api.agent.AgentResponse;
import com.gantang.tianshu.api.agent.AgentStatus;
import com.gantang.tianshu.api.agent.DelegationRequest;
import com.gantang.tianshu.api.auth.CallerIdentity;
import com.gantang.tianshu.api.session.Session;
import com.gantang.tianshu.api.session.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the two properties that make background delegation usable and safe:
 * a child stopped on a human-approval gate surfaces that gate on the owner's
 * stream (otherwise it hangs on a child session nobody watches until it times
 * out and auto-rejects), and one caller's stream never carries another's events.
 */
class DefaultSubAgentServiceTest {

    private SessionManager sessionManager;
    private ObjectMapper om;

    @BeforeEach
    void setUp() {
        om = new ObjectMapper();
        sessionManager = mock(SessionManager.class);
        Session child = mock(Session.class);
        when(sessionManager.getOrCreate(anyString(), any(), anyString(), any())).thenReturn(child);
        // Parent session absent: result injection and auto-aggregation are skipped,
        // keeping this test focused on the event channel.
        when(sessionManager.get(anyString())).thenReturn(Optional.empty());
    }

    /** Agent whose child run stops on an approval gate, then completes. */
    private static Agent agentEmitting(AgentEvent... events) {
        return new Agent() {
            @Override public String getAgentId() { return "test"; }
            @Override public Mono<AgentResponse> process(AgentContext ctx) { return Mono.empty(); }
            @Override public Flux<AgentEvent> processStream(AgentContext ctx) { return Flux.just(events); }
            @Override public void interrupt(String sessionId) { }
            @Override public AgentStatus getStatus(String sessionId) {
                return new AgentStatus(sessionId, AgentStatus.State.IDLE, 0, null, 0L);
            }
        };
    }

    private static DelegationRequest request(String userId) {
        return DelegationRequest.topLevel("do the thing",
            new CallerIdentity(userId, Set.of("tool:net")), "parent-1");
    }

    @Test
    void approvalGateInAChildRunSurfacesOnTheOwnersStream() {
        Agent agent = agentEmitting(
            AgentEvent.approvalRequired("call-9", "shell_exec", "rm -rf /tmp/x"),
            AgentEvent.done(AgentResponse.builder()
                .status(AgentResponse.Status.SUCCESS).content("finished").build(), null));
        var service = new DefaultSubAgentService(agent, sessionManager, om);

        List<String> events = collect(service.eventStream("u1"), 3,
            () -> service.spawnAsync(request("u1")));

        String approval = events.stream()
            .filter(e -> e.contains("spawn_approval_required"))
            .findFirst()
            .orElseGet(() -> fail("no spawn_approval_required event, got: " + events));
        assertTrue(approval.contains("call-9"), approval);
        assertTrue(approval.contains("shell_exec"), approval);
        // The parent session id is what lets a client route the approval back to a
        // human: the gate itself only knows the child session.
        assertTrue(approval.contains("parent-1"), approval);
    }

    @Test
    void anotherUsersStreamSeesNothing() {
        Agent agent = agentEmitting(
            AgentEvent.approvalRequired("call-9", "shell_exec", "secret command"),
            AgentEvent.done(AgentResponse.builder()
                .status(AgentResponse.Status.SUCCESS).content("secret answer").build(), null));
        var service = new DefaultSubAgentService(agent, sessionManager, om);

        List<String> otherUsersEvents = collect(service.eventStream("attacker"), 1,
            () -> service.spawnAsync(request("victim")));

        // Events carry the child's full answer, so an unfiltered stream would be a
        // straight read of another user's sub-agent output.
        assertTrue(otherUsersEvents.isEmpty(), "leaked to another user: " + otherUsersEvents);
    }

    @Test
    void childMetadataCarriesTheCallersScopesAndDepth() {
        DelegationRequest req = request("u1");
        Map<String, Object> meta = req.childMetadata();

        assertEquals(Set.of("tool:net"), meta.get(CallerIdentity.META_SCOPES));
        assertEquals(1, meta.get(com.gantang.tianshu.api.agent.BackgroundSpawner.META_SPAWN_DEPTH));
    }

    @Test
    void childResultCarriesTokenUsageIntoSpawnResultEvent() {
        AgentResponse done = AgentResponse.builder()
            .status(AgentResponse.Status.SUCCESS).content("finished").build();
        done = AgentResponse.builder()
            .status(AgentResponse.Status.SUCCESS).content("finished")
            .metadata(Map.of("inputTokens", 1234, "outputTokens", 56,
                "totalTokens", 1290, "modelCalls", 3))
            .build();
        AgentEvent doneEvent;
        try {
            doneEvent = AgentEvent.done(done, om.writeValueAsString(done));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Agent agent = agentEmitting(doneEvent);
        var service = new DefaultSubAgentService(agent, sessionManager, om);

        List<String> events = collect(service.eventStream("u1"), 2,
            () -> service.spawnAsync(request("u1")));

        String result = events.stream().filter(e -> e.contains("spawn_result")).findFirst().orElseThrow();
        assertTrue(result.contains("\"inputTokens\":1234"), result);
        assertTrue(result.contains("\"outputTokens\":56"), result);
        assertTrue(result.contains("\"modelCalls\":3"), result);
    }

    @Test
    void aggregatedChildResult_isBoundaryWrapped_andReportsIsolationSavings() {
        AgentResponse done = AgentResponse.builder()
            .status(AgentResponse.Status.SUCCESS).content("child conclusion XYZ")
            .metadata(Map.of("inputTokens", 800, "outputTokens", 200,
                "totalTokens", 1000, "modelCalls", 2))
            .build();
        final AgentEvent doneEvent;
        try {
            doneEvent = AgentEvent.done(done, om.writeValueAsString(done));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Agent agent = mock(Agent.class);
        when(agent.processStream(any())).thenReturn(Flux.just(doneEvent));
        when(agent.getStatus(anyString()))
            .thenReturn(new AgentStatus("x", AgentStatus.State.IDLE, 0, null, 0L));

        Session parent = mock(Session.class);
        when(sessionManager.get("parent-1")).thenReturn(Optional.of(parent));
        var service = new DefaultSubAgentService(agent, sessionManager, om);

        // started + result + summary_start + summary_done; aggregation waits ~1s for the idle gate.
        collect(service.eventStream("u1"), 4, () -> service.spawnAsync(request("u1")));

        org.mockito.ArgumentCaptor<String> note = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(parent).addSystemMessage(note.capture());
        String persisted = note.getValue();
        assertTrue(persisted.contains("<<untrusted_tool_output"),
            "child result entering the parent transcript must be boundary-wrapped: " + persisted);
        assertTrue(persisted.contains("source=\"spawn_task\""), persisted);
        assertTrue(persisted.contains("child conclusion XYZ"), persisted);
        assertTrue(persisted.contains("800") && persisted.contains("200"),
            "persisted note must carry the child's token spend: " + persisted);

        org.mockito.ArgumentCaptor<AgentContext> ctxCap =
            org.mockito.ArgumentCaptor.forClass(AgentContext.class);
        verify(agent, org.mockito.Mockito.atLeastOnce()).processStream(ctxCap.capture());
        String aggregationPrompt = ctxCap.getAllValues().stream()
            .map(AgentContext::systemPrompt)
            .filter(s -> s != null && s.contains("子代理结果"))
            .findFirst().orElseThrow(() -> new AssertionError("no aggregation prompt captured"));
        assertTrue(aggregationPrompt.contains("<<untrusted_tool_output"),
            "aggregation prompt must keep the untrusted boundary");
        assertTrue(aggregationPrompt.contains("隔离在子会话内"),
            "parent model must be told about the context-isolation saving: " + aggregationPrompt);
    }

    @Test
    @SuppressWarnings("unchecked")
    void critiqueTreatsDoneWithErrorStatusAsBranchFailure() {
        // Branch 1 emits a DONE event carrying terminal ERROR status (e.g. upstream
        // model failure surfaced through recovery): it must NOT count as a usable answer.
        Agent agent = new Agent() {
            @Override public String getAgentId() { return "test"; }
            @Override public Mono<AgentResponse> process(AgentContext ctx) { return Mono.empty(); }
            @Override public Flux<AgentEvent> processStream(AgentContext ctx) {
                String q = ctx.currentQuery() != null ? ctx.currentQuery() : "";
                java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("第\\s*(\\d+)\\s*个").matcher(q);
                int k = m.find() ? Integer.parseInt(m.group(1)) : 1;
                if (k == 1) {
                    AgentResponse err = AgentResponse.builder()
                        .status(AgentResponse.Status.ERROR).content("upstream model unavailable")
                        .metadata(Map.of("inputTokens", 10, "outputTokens", 0, "modelCalls", 1))
                        .build();
                    try { return Flux.just(AgentEvent.done(err, om.writeValueAsString(err))); }
                    catch (Exception e) { throw new RuntimeException(e); }
                }
                return Flux.just(doneEvent("唯一存活分支的完整结论", 100, 10, 0));
            }
            @Override public void interrupt(String sessionId) { }
            @Override public AgentStatus getStatus(String sessionId) {
                return new AgentStatus(sessionId, AgentStatus.State.IDLE, 0, null, 0L);
            }
        };
        var service = new DefaultSubAgentService(agent, sessionManager, om);
        // started + 2 branch_start + 2 branch_done + result; single survivor skips critic.
        List<String> events = collect(service.eventStream("u1"), 6,
            () -> service.spawnAsync(critiqueRequest(2)));

        java.util.Map<String, Object> result = findResult(events);
        List<java.util.Map<String, Object>> branches =
            (List<java.util.Map<String, Object>>) result.get("branches");
        assertEquals(1, branches.stream().filter(b -> "failed".equals(b.get("status"))).count(),
            branches.toString());
        java.util.Map<String, Object> critic = (java.util.Map<String, Object>) result.get("critic");
        assertEquals(Boolean.TRUE, critic.get("degraded"), "single survivor degrades, no critic");
        assertEquals(2, critic.get("chosen"));
        assertTrue(String.valueOf(result.get("answer")).contains("唯一存活分支"));
        // Failed branch tokens are still accounted for, but no critic tokens.
        assertEquals(110, result.get("inputTokens"));
    }

    @Test
    void singleRunDoneWithErrorStatusEmitsFailureNotResult() {
        AgentResponse err = AgentResponse.builder()
            .status(AgentResponse.Status.ERROR).content("model exploded").build();
        final AgentEvent errEvent;
        try {
            errEvent = AgentEvent.done(err, om.writeValueAsString(err));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        var service = new DefaultSubAgentService(agentEmitting(errEvent), sessionManager, om);
        List<String> events = collect(service.eventStream("u1"), 2,
            () -> service.spawnAsync(request("u1")));

        assertTrue(events.stream().anyMatch(e -> e.contains("spawn_failed")), events.toString());
        assertTrue(events.stream().noneMatch(e -> e.contains("spawn_result")), events.toString());
    }

    @Test
    void finishedTaskRecordsAreEvictedAfterRetention() throws Exception {
        Agent agent = agentEmitting(
            AgentEvent.done(AgentResponse.builder()
                .status(AgentResponse.Status.SUCCESS).content("done").build(), null));
        var service = new DefaultSubAgentService(agent, sessionManager, om);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        service.eventStream("u1")
            .filter(e -> e.contains("spawn_result") || e.contains("spawn_failed"))
            .take(1).subscribe(e -> done.countDown());
        String taskId = service.spawnAsync(request("u1")).taskId();
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS), "run never settled");
        // Wait for the pipeline's doFinally to mark the record finished.
        long deadline = System.currentTimeMillis() + 5000;
        while (service.hasTask(taskId) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(service.hasTask(taskId), "finished record retained for late cancels");
        // Within the 1h window it stays; a negative retention treats it as expired and evicts.
        service.purgeFinishedTasksOlderThan(Duration.ofHours(1));
        assertTrue(service.hasTask(taskId));
        service.purgeFinishedTasksOlderThan(Duration.ofMillis(-1));
        assertFalse(service.hasTask(taskId), "expired record must be evicted");
    }

    // ==================== parallel_critique (best-of-n) ====================

    /** Build a terminal DONE event carrying content + token metadata. */
    private static AgentEvent doneEvent(String content, int in, int out, int cached) {
        java.util.Map<String, Object> meta = new java.util.HashMap<>();
        meta.put("inputTokens", in);
        meta.put("outputTokens", out);
        meta.put("cachedInputTokens", cached);
        meta.put("totalTokens", in + out);
        meta.put("modelCalls", 1);
        AgentResponse resp = AgentResponse.builder()
            .status(AgentResponse.Status.SUCCESS).content(content).metadata(meta).build();
        try {
            return AgentEvent.done(resp, new ObjectMapper().writeValueAsString(resp));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Fake agent for critique groups: answerer branches are identified by the
     * “第 k 个” nudge in their query; the critic by the “严格的评审者” prompt.
     */
    private static Agent critiqueAgent(int failBranch /*1-based, 0=none*/,
                                       boolean criticFails) {
        return new Agent() {
            @Override public String getAgentId() { return "test"; }
            @Override public Mono<AgentResponse> process(AgentContext ctx) { return Mono.empty(); }
            @Override public Flux<AgentEvent> processStream(AgentContext ctx) {
                String q = ctx.currentQuery() != null ? ctx.currentQuery() : "";
                if (q.contains("严格的评审者")) {
                    if (criticFails) return Flux.error(new RuntimeException("critic boom"));
                    return Flux.just(doneEvent(
                        "[[CHOSEN]]\n2\n[[ANSWER]]\ncritic 综合后的最终答案", 50, 5, 40));
                }
                int k = 1;
                java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("第\\s*(\\d+)\\s*个").matcher(q);
                if (m.find()) k = Integer.parseInt(m.group(1));
                if (failBranch == k) return Flux.error(new RuntimeException("branch boom"));
                String ans = ("方案" + k + "答案").repeat(k);
                return Flux.just(doneEvent(ans, 100 * k, 10 + k, 0));
            }
            @Override public void interrupt(String sessionId) { }
            @Override public AgentStatus getStatus(String sessionId) {
                return new AgentStatus(sessionId, AgentStatus.State.IDLE, 0, null, 0L);
            }
        };
    }

    private static DelegationRequest critiqueRequest(int n) {
        return DelegationRequest.critique("解决一个高价值设计问题",
            new CallerIdentity("u1", Set.of("tool:net")), "parent-1", n);
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> findResult(List<String> events) {
        String json = events.stream().filter(e -> e.contains("spawn_result")).findFirst()
            .orElseThrow(() -> new AssertionError("no spawn_result: " + events));
        try {
            return new ObjectMapper().readValue(json, java.util.Map.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void critiqueRunsNParallelBranchesAndCriticSelectsBest() {
        var service = new DefaultSubAgentService(critiqueAgent(0, false), sessionManager, om);
        // started + 3 branch_start + 3 branch_done + critic_start + result
        List<String> events = collect(service.eventStream("u1"), 9,
            () -> service.spawnAsync(critiqueRequest(3)));

        java.util.Map<String, Object> result = findResult(events);
        assertEquals("critique", result.get("mode"));
        assertTrue(String.valueOf(result.get("answer")).contains("critic 综合后的最终答案"),
            String.valueOf(result.get("answer")));
        java.util.Map<String, Object> critic = (java.util.Map<String, Object>) result.get("critic");
        assertEquals(2, critic.get("chosen"));
        assertEquals(Boolean.FALSE, critic.get("degraded"));
        List<java.util.Map<String, Object>> branches =
            (List<java.util.Map<String, Object>>) result.get("branches");
        assertEquals(3, branches.size());
        assertTrue(branches.stream().allMatch(b -> "success".equals(b.get("status"))));
        // Aggregated token spend across 3 branches (100+200+300, out 11+12+13) + critic (50/5)
        assertEquals(650, result.get("inputTokens"));
        assertEquals(41, result.get("outputTokens"));
        assertEquals(4, result.get("modelCalls"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void critiqueToleratesOneFailedBranch() {
        var service = new DefaultSubAgentService(critiqueAgent(2, false), sessionManager, om);
        List<String> events = collect(service.eventStream("u1"), 9,
            () -> service.spawnAsync(critiqueRequest(3)));

        java.util.Map<String, Object> result = findResult(events);
        List<java.util.Map<String, Object>> branches =
            (List<java.util.Map<String, Object>>) result.get("branches");
        long failed = branches.stream().filter(b -> "failed".equals(b.get("status"))).count();
        assertEquals(1, failed, "exactly one branch failed: " + branches);
        assertTrue(String.valueOf(result.get("answer")).contains("critic 综合后的最终答案"));
        // Failed branch contributes no tokens; critic still ran on the two survivors.
        assertEquals(450, result.get("inputTokens")); // 100 + 300 + 50
    }

    @Test
    @SuppressWarnings("unchecked")
    void critiqueDegradesToLongestBranchWhenCriticFails() {
        var service = new DefaultSubAgentService(critiqueAgent(0, true), sessionManager, om);
        List<String> events = collect(service.eventStream("u1"), 9,
            () -> service.spawnAsync(critiqueRequest(3)));

        java.util.Map<String, Object> result = findResult(events);
        java.util.Map<String, Object> critic = (java.util.Map<String, Object>) result.get("critic");
        assertEquals(Boolean.TRUE, critic.get("degraded"));
        // 方案3答案 repeated 3 times is the longest branch → chosen=3, answer is its raw text
        assertEquals(3, critic.get("chosen"));
        assertTrue(String.valueOf(result.get("answer")).contains("方案3答案"));
    }

    @Test
    void anotherUserCannotCancelSomeoneElsesTask() throws Exception {
        // Branch streams never complete, so the group stays live until cancelled.
        Agent neverEnding = new Agent() {
            @Override public String getAgentId() { return "test"; }
            @Override public Mono<AgentResponse> process(AgentContext ctx) { return Mono.never(); }
            @Override public Flux<AgentEvent> processStream(AgentContext ctx) { return Flux.never(); }
            @Override public void interrupt(String sessionId) { }
            @Override public AgentStatus getStatus(String sessionId) {
                return new AgentStatus(sessionId, AgentStatus.State.RUNNING, 1, null, 0L);
            }
        };
        var service = new DefaultSubAgentService(neverEnding, sessionManager, om);
        java.util.concurrent.LinkedBlockingQueue<String> queue = new java.util.concurrent.LinkedBlockingQueue<>();
        service.eventStream("u1").subscribe(queue::add, e -> { });
        service.eventStream("attacker").subscribe(queue::add, e -> { });

        var handle = service.spawnAsync(critiqueRequest(2));
        // Wait for branches to be in flight.
        Thread.sleep(600);
        assertFalse(service.cancelTaskForUser(handle.taskId(), "attacker"),
            "cross-tenant cancel must be rejected");
        assertTrue(service.cancelTaskForUser(handle.taskId(), "u1"));
        String cancel = queue.poll(5, java.util.concurrent.TimeUnit.SECONDS) == null ? null : pollUntil(queue);
        assertNotNull(cancel, "owner should receive spawn_cancelled");
        assertTrue(cancel.contains("spawn_cancelled") && cancel.contains(handle.taskId()), cancel);
    }

    private static String pollUntil(java.util.concurrent.LinkedBlockingQueue<String> queue)
            throws InterruptedException {
        String first = null;
        String e;
        while ((e = queue.poll(2, java.util.concurrent.TimeUnit.SECONDS)) != null) {
            if (first == null) first = e;
            if (e.contains("spawn_cancelled")) return e;
        }
        return first;
    }

    // ==== P1-3: spawn_started must not expose a task that cannot yet be cancelled ====

    @Test
    void cancelImmediatelyOnSpawnStarted_actuallyStopsTheRun() throws Exception {
        // P1-3: the spawn_started event exposes the taskId; a subscriber cancelling
        // in that instant must hit a non-null disposable, not lose the cancel.
        java.util.concurrent.atomic.AtomicBoolean interruptCalled = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.Set<String> interrupted = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        Agent neverEnding = new Agent() {
            @Override public String getAgentId() { return "test"; }
            @Override public Mono<AgentResponse> process(AgentContext ctx) { return Mono.never(); }
            @Override public Flux<AgentEvent> processStream(AgentContext ctx) { return Flux.never(); }
            @Override public void interrupt(String sessionId) {
                interruptCalled.set(true);
                interrupted.add(sessionId);
            }
            @Override public AgentStatus getStatus(String sessionId) {
                return new AgentStatus(sessionId, AgentStatus.State.RUNNING, 1, null, 0L);
            }
        };
        var service = new DefaultSubAgentService(neverEnding, sessionManager, om);
        java.util.concurrent.LinkedBlockingQueue<String> events = new java.util.concurrent.LinkedBlockingQueue<>();
        // Cancel synchronously on the very first event (spawn_started) — the exact
        // window where the disposable used to be null.
        service.eventStream("u1").subscribe(e -> {
            events.add(e);
            if (e.contains("spawn_started")) {
                try {
                    String taskId = new ObjectMapper().readTree(e).get("taskId").asText();
                    service.cancelTask(taskId);
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
        });
        var handle = service.spawnAsync(request("u1"));

        String cancelled = pollUntil(events);
        assertNotNull(cancelled, "owner should receive spawn_cancelled");
        assertTrue(cancelled.contains("spawn_cancelled"), cancelled);
        // The child run must actually be signalled — a lost cancel would leave the
        // never-ending child streaming tokens forever.
        long deadline = System.currentTimeMillis() + 3000;
        while (!interruptCalled.get() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertTrue(interruptCalled.get(), "cancel must interrupt the child session");
        assertTrue(interrupted.contains(handle.childSessionId()),
            "interrupted sessions: " + interrupted + " expected to contain " + handle.childSessionId());
    }

    // ==== P1-4: cancel stops the aggregation wait immediately ====

    @Test
    void cancelDuringAggregationWait_stopsImmediatelyWithoutWaitingForTheCap() throws Exception {
        // P1-4: while the result waits (up to 10 min) for the parent to idle, a
        // cancel must dispose the interval polling at once instead of hanging on.
        Agent agent = new Agent() {
            @Override public String getAgentId() { return "test"; }
            @Override public Mono<AgentResponse> process(AgentContext ctx) { return Mono.empty(); }
            @Override public Flux<AgentEvent> processStream(AgentContext ctx) {
                AgentResponse done = AgentResponse.builder()
                    .status(AgentResponse.Status.SUCCESS).content("child answer").build();
                try { return Flux.just(AgentEvent.done(done, om.writeValueAsString(done))); }
                catch (Exception e) { throw new RuntimeException(e); }
            }
            @Override public void interrupt(String sessionId) { }
            @Override public AgentStatus getStatus(String sessionId) {
                // Parent stays busy forever, so the aggregation never passes the idle gate.
                return new AgentStatus(sessionId, AgentStatus.State.RUNNING, 1, null, 0L);
            }
        };
        Session parent = mock(Session.class);
        when(sessionManager.get("parent-1")).thenReturn(Optional.of(parent));
        var service = new DefaultSubAgentService(agent, sessionManager, om);

        java.util.concurrent.LinkedBlockingQueue<String> events = new java.util.concurrent.LinkedBlockingQueue<>();
        service.eventStream("u1").subscribe(events::add, e -> { });
        var handle = service.spawnAsync(request("u1"));

        // Wait until the run finished and the aggregation wait has started polling.
        String result = null;
        long pollDeadline = System.currentTimeMillis() + 10_000;
        while (result == null && System.currentTimeMillis() < pollDeadline) {
            String e = events.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (e != null && e.contains("spawn_result")) result = e;
        }
        assertNotNull(result, "child run should emit spawn_result");
        Thread.sleep(2500); // the idle gate is now polling (1s initial delay)

        long t0 = System.currentTimeMillis();
        assertTrue(service.cancelTask(handle.taskId()));
        String cancelled = null;
        while (System.currentTimeMillis() - t0 < 5_000) {
            String e = events.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (e != null && e.contains("spawn_cancelled")) { cancelled = e; break; }
        }
        long elapsed = System.currentTimeMillis() - t0;
        assertNotNull(cancelled, "owner should receive spawn_cancelled");
        assertTrue(elapsed < 4_000,
            "cancel must settle immediately, not hang on the 10-minute aggregation wait: " + elapsed + "ms");
        org.mockito.Mockito.verify(parent, org.mockito.Mockito.never()).addSystemMessage(anyString());
    }

    // ==== P1-9: aggregation turn is headless; APPROVAL_REQUIRED is denied, not dropped ====

    @Test
    void aggregationTurn_isHeadless_andApprovalRequiredIsDeniedNotDropped() throws Exception {
        // P1-9: the internal aggregation turn must carry META_HEADLESS so gated
        // tools fail fast, and an APPROVAL_REQUIRED event must be surfaced/denied
        // rather than silently swallowed.
        java.util.List<AgentContext> seenCtx = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Agent agent = new Agent() {
            @Override public String getAgentId() { return "test"; }
            @Override public Mono<AgentResponse> process(AgentContext ctx) { return Mono.empty(); }
            @Override public Flux<AgentEvent> processStream(AgentContext ctx) {
                seenCtx.add(ctx);
                if (ctx.currentQuery() != null && ctx.currentQuery().contains("汇总已完成的后台子任务")) {
                    // The aggregation (headless) turn: model asks for a gated tool.
                    return Flux.just(
                        AgentEvent.approvalRequired("call-1", "shell_exec", "rm -rf /"),
                        AgentEvent.textToken("汇总完成"));
                }
                AgentResponse done = AgentResponse.builder()
                    .status(AgentResponse.Status.SUCCESS).content("child answer").build();
                try { return Flux.just(AgentEvent.done(done, om.writeValueAsString(done))); }
                catch (Exception e) { throw new RuntimeException(e); }
            }
            @Override public void interrupt(String sessionId) { }
            @Override public AgentStatus getStatus(String sessionId) {
                return new AgentStatus(sessionId, AgentStatus.State.IDLE, 0, null, 0L);
            }
        };
        Session parent = mock(Session.class);
        when(sessionManager.get("parent-1")).thenReturn(Optional.of(parent));
        var service = new DefaultSubAgentService(agent, sessionManager, om);

        java.util.concurrent.LinkedBlockingQueue<String> events = new java.util.concurrent.LinkedBlockingQueue<>();
        service.eventStream("u1").subscribe(events::add, e -> { });
        service.spawnAsync(request("u1"));

        // Collect until the aggregation completes (summary_done) or time out.
        String denied = null;
        boolean summaryDone = false;
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline && !summaryDone) {
            String e = events.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (e == null) continue;
            if (e.contains("spawn_summary_approval_denied")) denied = e;
            if (e.contains("spawn_summary_done")) summaryDone = true;
        }
        assertTrue(summaryDone, "aggregation should complete");
        assertNotNull(denied, "APPROVAL_REQUIRED in the aggregation turn must be surfaced as denied, got: " + events);
        assertTrue(denied.contains("shell_exec"), denied);

        // The aggregation turn itself must be marked headless.
        AgentContext aggCtx = seenCtx.stream()
            .filter(c -> c.currentQuery() != null && c.currentQuery().contains("汇总已完成的后台子任务"))
            .findFirst().orElseThrow(() -> new AssertionError("no aggregation context captured"));
        assertEquals(Boolean.TRUE, aggCtx.metadata().get(CallerIdentity.META_HEADLESS),
            "aggregation turn must run headless so ASK tools fail fast");
    }

    /**
     * Subscribe first, then trigger: {@code spawnAsync} publishes into a multicast
     * sink with no replay, so a late subscriber misses the events. Neither
     * {@code doOnSubscribe} nor {@code Flux.defer} is safe here — both run their
     * callback BEFORE the subscription has propagated the last hop to the sink, so
     * under load the synchronous/near-immediate emissions hit a zero-subscriber
     * sink and are dropped. An explicit {@code subscribe()} completes the whole
     * synchronous chain before the trigger fires.
     */
    private static List<String> collect(Flux<String> stream, int expected, Runnable trigger) {
        java.util.concurrent.LinkedBlockingQueue<String> queue =
            new java.util.concurrent.LinkedBlockingQueue<>();
        reactor.core.Disposable d = stream.subscribe(queue::add, e -> { });
        try {
            trigger.run();
            List<String> got = new java.util.ArrayList<>();
            long deadline = System.currentTimeMillis() + 10_000;
            while (got.size() < expected && System.currentTimeMillis() < deadline) {
                String e = queue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (e != null) got.add(e);
            }
            return got;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } finally {
            d.dispose();
        }
    }

    // ==== P2-12: a failing single run must not leak Reactor error noise ====

    @Test
    void singleRun_errorIsRecorded_andPipelineTerminatesCleanly() {
        // P2-12: doOnError records/emits the failure; onErrorResume then swallows
        // the error so the root subscribe() sees a clean completion (no
        // errorCallbackNotImplemented). The observable contract: spawn_failed is
        // emitted and the stream subscriber's error handler is never invoked.
        Agent failing = new Agent() {
            @Override public String getAgentId() { return "test"; }
            @Override public Mono<AgentResponse> process(AgentContext ctx) { return Mono.empty(); }
            @Override public Flux<AgentEvent> processStream(AgentContext ctx) {
                return Flux.error(new RuntimeException("boom"));
            }
            @Override public void interrupt(String sessionId) { }
            @Override public AgentStatus getStatus(String sessionId) {
                return new AgentStatus(sessionId, AgentStatus.State.IDLE, 0, null, 0L);
            }
        };
        var service = new DefaultSubAgentService(failing, sessionManager, om);
        List<String> events = collect(service.eventStream("u1"), 2,
            () -> service.spawnAsync(request("u1")));
        assertTrue(events.stream().anyMatch(e -> e.contains("spawn_failed")),
            "failure must be emitted as spawn_failed: " + events);
    }

    // ==== P2-13: blocking spawn honours the capacity gate ====

    @Test
    void blockingSpawn_respectsTheCapacityGate() throws Exception {
        // Saturate all slots with never-ending async runs; a blocking spawn must
        // then be rejected by the shared tryReserve gate instead of starting an
        // unbounded child session (P2-13).
        Agent neverEnding = new Agent() {
            @Override public String getAgentId() { return "test"; }
            @Override public Mono<AgentResponse> process(AgentContext ctx) { return Mono.never(); }
            @Override public Flux<AgentEvent> processStream(AgentContext ctx) { return Flux.never(); }
            @Override public void interrupt(String sessionId) { }
            @Override public AgentStatus getStatus(String sessionId) {
                return new AgentStatus(sessionId, AgentStatus.State.RUNNING, 1, null, 0L);
            }
        };
        var service = new DefaultSubAgentService(neverEnding, sessionManager, om);
        java.util.List<com.gantang.tianshu.api.agent.BackgroundSpawner.SpawnHandle> handles = new java.util.ArrayList<>();
        for (int i = 0; i < DefaultSubAgentService.MAX_CONCURRENT_BACKGROUND_SPAWNS; i++) {
            handles.add(service.spawnAsync(request("u1")));
        }
        Thread.sleep(300); // let the async runs claim their slots
        try {
            var result = service.spawn(request("u1")).block(Duration.ofSeconds(10));
            assertNotNull(result);
            assertTrue(result.answer().contains("Too many concurrent"),
                "blocking spawn must be rejected when slots are exhausted (P2-13): " + result.answer());
        } finally {
            for (var h : handles) service.cancelTask(h.taskId());
        }
    }

    // ==== P2-14: cancelTaskForUser guards a null caller userId ====

    @Test
    void cancelTaskForUser_doesNotThrow_onNullCallerUserId() {
        // P2-14: a task whose caller has a null userId must not NPE the cancel
        // lookup; it simply does not match and returns false.
        Agent agent = agentEmitting(AgentEvent.done(AgentResponse.builder()
            .status(AgentResponse.Status.SUCCESS).content("ok").build(), null));
        var service = new DefaultSubAgentService(agent, sessionManager, om);
        // Spawn with a null-userId caller (internal/unauthenticated path).
        DelegationRequest req = DelegationRequest.topLevel("t",
            new CallerIdentity(null, Set.of()), "parent-1");
        var handle = service.spawnAsync(req);
        assertDoesNotThrow(() -> service.cancelTaskForUser(handle.taskId(), "someone"));
        assertFalse(service.cancelTaskForUser(handle.taskId(), "someone"),
            "a null-caller task never matches a concrete userId (P2-14)");
    }
}
