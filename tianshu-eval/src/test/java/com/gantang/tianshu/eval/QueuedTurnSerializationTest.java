package com.gantang.tianshu.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.agent.AgentEvent;
import com.gantang.tianshu.api.agent.AgentHook;
import com.gantang.tianshu.api.agent.AgentResponse;
import com.gantang.tianshu.api.session.Session;
import com.gantang.tianshu.api.tool.Tool;
import com.gantang.tianshu.api.tool.ToolResult;
import com.gantang.tianshu.eval.engine.ReplayLlmClient;
import com.gantang.tianshu.eval.scenario.Scenario;
import com.gantang.tianshu.impl.agent.ReactiveAgent;
import com.gantang.tianshu.impl.memory.DefaultContextAssembler;
import com.gantang.tianshu.impl.session.InMemorySessionManager;
import com.gantang.tianshu.impl.tool.DefaultToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Same-session turn serialization regression ({@code TurnSerializer}): a second
 * turn submitted while the first is still mid-flight must queue FIFO — its body
 * ({@code onTurnStart}) must not be subscribed until the first turn terminated
 * ({@code onTurnEnd}).
 *
 * <p>This cannot be a declarative YAML scenario: it needs two concurrent
 * {@code processStream} submissions coordinated by a barrier tool. It still
 * runs at zero API cost against the fully wired {@link ReactiveAgent} with the
 * scripted {@link ReplayLlmClient}.
 */
class QueuedTurnSerializationTest {

    @Test
    void secondTurnForSameSessionQueuesUntilFirstFinishes() throws Exception {
        // ── Barrier tool: signals entry, then blocks until the test proves the
        //    second turn was submitted while this one is still running. ──
        CountDownLatch toolEntered = new CountDownLatch(1);
        CountDownLatch secondTurnSubmitted = new CountDownLatch(1);
        Tool barrier = new Tool() {
            @Override public String name() { return "barrier_tool"; }
            @Override public String description() { return "blocks until the test releases it"; }
            @Override public JsonNode parameters() {
                ObjectNode schema = JsonNodeFactory.instance.objectNode();
                schema.put("type", "object");
                schema.set("properties", JsonNodeFactory.instance.objectNode());
                schema.put("additionalProperties", true);
                return schema;
            }
            @Override public ToolResult execute(String callId, Map<String, Object> params, AgentContext context) {
                toolEntered.countDown();
                try {
                    if (!secondTurnSubmitted.await(10, TimeUnit.SECONDS)) {
                        return ToolResult.failure(callId, "second turn was never submitted");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return ToolResult.failure(callId, "interrupted");
                }
                return ToolResult.success(callId, "barrier released");
            }
        };

        // ── Turn-boundary moments (start#1, end#1, start#2, end#2 expected). ──
        List<String> moments = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger turnCounter = new AtomicInteger();
        AgentHook momentHook = new AgentHook() {
            @Override public void onTurnStart(AgentContext context, Session session) {
                moments.add("start#" + turnCounter.incrementAndGet());
            }
            @Override public void onTurnEnd(AgentContext context, AgentResponse response) {
                moments.add("end#" + turnCounter.get());
            }
        };

        // ── Scripted model: turn 1 calls the barrier then answers; turn 2 answers.
        //    With FIFO serialization the flat cursor order is deterministic;
        //    concurrent subscription would race the cursor and/or interleave turns. ──
        List<Scenario.ModelStep> script = List.of(
                new Scenario.ModelStep(null,
                        List.of(new Scenario.ToolCallRef("barrier_tool", Map.of())), null),
                new Scenario.ModelStep("FIRST-TURN-FINAL-MARKER", List.of(), null),
                new Scenario.ModelStep("SECOND-TURN-FINAL-MARKER", List.of(), null)
        );
        ReplayLlmClient replay = new ReplayLlmClient(script);

        DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(barrier);
        InMemorySessionManager sessions = new InMemorySessionManager();
        ReactiveAgent agent = new ReactiveAgent(
                ctx -> replay,
                registry,
                new DefaultContextAssembler(null, 50, 5),
                null,
                sessions,
                new ObjectMapper())
                .withHooks(List.of(momentHook));

        String sessionId = "serialization-test";
        List<AgentEvent> events1 = Collections.synchronizedList(new ArrayList<>());
        List<AgentEvent> events2 = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done1 = new CountDownLatch(1);
        CountDownLatch done2 = new CountDownLatch(1);
        AtomicReference<Throwable> error1 = new AtomicReference<>();
        AtomicReference<Throwable> error2 = new AtomicReference<>();

        agent.processStream(ctx(sessionId, "first user message"))
                .subscribe(events1::add,
                        e -> { error1.set(e); done1.countDown(); },
                        done1::countDown);

        assertTrue(toolEntered.await(10, TimeUnit.SECONDS),
                "first turn never reached the barrier tool");

        // Second turn submitted while the first is blocked mid-tool.
        agent.processStream(ctx(sessionId, "second user message"))
                .subscribe(events2::add,
                        e -> { error2.set(e); done2.countDown(); },
                        done2::countDown);
        secondTurnSubmitted.countDown();

        assertTrue(done1.await(15, TimeUnit.SECONDS), "first turn never finished");
        assertTrue(done2.await(15, TimeUnit.SECONDS), "queued second turn never finished");
        assertNull(error1.get(), () -> "first turn errored: " + error1.get());
        assertNull(error2.get(), () -> "second turn errored: " + error2.get());

        // FIFO invariant: the second turn body started only AFTER the first
        // terminated — even though it was submitted while the first was blocked
        // inside the barrier tool. If turns ran concurrently, start#2 would land
        // before end#1 (or the scripted cursor would desync).
        int start2 = moments.indexOf("start#2");
        int end1 = moments.indexOf("end#1");
        assertTrue(end1 >= 0, "first turn end not recorded, moments: " + moments);
        assertTrue(start2 >= 0, "second turn start not recorded, moments: " + moments);
        assertTrue(start2 > end1,
                "second turn started before the first finished — TurnSerializer broken: " + moments);
        assertEquals(List.of("start#1", "end#1", "start#2", "end#2"), moments,
                "unexpected turn-boundary sequence");

        // Each turn produced its own scripted final answer.
        assertTrue(containsDone(events1, "FIRST-TURN-FINAL-MARKER"),
                "first turn final answer missing: " + events1);
        assertTrue(containsDone(events2, "SECOND-TURN-FINAL-MARKER"),
                "second turn final answer missing: " + events2);
        assertTrue(events1.stream().noneMatch(e -> e.type() == AgentEvent.Type.ERROR),
                "first turn emitted ERROR: " + events1);
        assertTrue(events2.stream().noneMatch(e -> e.type() == AgentEvent.Type.ERROR),
                "second turn emitted ERROR: " + events2);
    }

    private static AgentContext ctx(String sessionId, String query) {
        return AgentContext.builder()
                .sessionId(sessionId)
                .userId("eval-user")
                .currentQuery(query)
                .systemPrompt("")
                .build();
    }

    private static boolean containsDone(List<AgentEvent> events, String marker) {
        return events.stream().anyMatch(e -> e.type() == AgentEvent.Type.DONE
                && e.content() != null && e.content().contains(marker));
    }
}
