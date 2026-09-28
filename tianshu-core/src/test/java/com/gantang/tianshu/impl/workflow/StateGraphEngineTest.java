package com.gantang.tianshu.impl.workflow;

import com.gantang.tianshu.api.workflow.GraphRunResult;
import com.gantang.tianshu.api.workflow.GraphState;
import com.gantang.tianshu.api.workflow.NodeKind;
import com.gantang.tianshu.api.workflow.NodeSpec;
import com.gantang.tianshu.api.workflow.StateGraph;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the native state-graph engine, all driven through
 * {@link DefaultGraphRunner} with in-memory fakes (no Spring / DB).
 */
class StateGraphEngineTest {

    private DefaultGraphRunner runner(GraphTestFixtures.ScriptAgent agent) {
        GraphServices services = GraphServices.builder().agent(agent).build();
        return new DefaultGraphRunner(services, new InMemoryCheckpointStore());
    }

    @Test
    void runsTwoAgentNodesInOrder() {
        StateGraph graph = StateGraph.builder()
            .name("seq")
            .addNode(NodeSpec.builder("a", NodeKind.AGENT).query("first").build())
            .addNode(NodeSpec.builder("b", NodeKind.AGENT).query("second").build())
            .edge(StateGraph.START, "a")
            .edge("a", "b")
            .edge("b", StateGraph.END)
            .build();

        GraphTestFixtures.ScriptAgent agent = new GraphTestFixtures.ScriptAgent();
        StepVerifier.create(runner(agent).start(graph, GraphTestFixtures.baseContext(), "hi"))
            .assertNext(result -> {
                assertEquals(GraphRunResult.Status.COMPLETED, result.status());
                assertEquals("second", result.output());
                assertTrue(result.state().outputs().containsKey("a"));
                assertTrue(result.state().outputs().containsKey("b"));
            })
            .verifyComplete();

        assertEquals(List.of("first", "second"), agent.queries());
    }

    @Test
    void conditionalEdgeSelectsBigBranch() {
        StateGraph graph = conditionalGraph();
        DefaultGraphRunner r = conditionalRunner();

        GraphState bigState = GraphState.ofInput("x").withVariable("n", 42);
        StepVerifier.create(r.startFrom(graph, GraphTestFixtures.baseContext(), bigState))
            .assertNext(res -> assertEquals("BIG", res.output()))
            .verifyComplete();
    }

    @Test
    void conditionalEdgeFallsBackToSmall() {
        StateGraph graph = conditionalGraph();
        DefaultGraphRunner r = conditionalRunner();

        GraphState smallState = GraphState.ofInput("x").withVariable("n", 3);
        StepVerifier.create(r.startFrom(graph, GraphTestFixtures.baseContext(), smallState))
            .assertNext(res -> assertEquals("SMALL", res.output()))
            .verifyComplete();
    }

    private StateGraph conditionalGraph() {
        return StateGraph.builder()
            .name("cond")
            .addNode(NodeSpec.builder("set", NodeKind.PASS).build())
            .addNode(NodeSpec.builder("big", NodeKind.AGENT).query("BIG").build())
            .addNode(NodeSpec.builder("small", NodeKind.AGENT).query("SMALL").build())
            .edge(StateGraph.START, "set")
            .edgeWhen("set", "big", "n > 10")
            .edge("set", "small")
            .edge("big", StateGraph.END)
            .edge("small", StateGraph.END)
            .build();
    }

    private DefaultGraphRunner conditionalRunner() {
        GraphServices services = GraphServices.builder()
            .agent(new GraphTestFixtures.ScriptAgent()).build();
        return new DefaultGraphRunner(services, new InMemoryCheckpointStore());
    }

    @Test
    void loopFailsWhenStepBudgetExceeded() {
        StateGraph graph = StateGraph.builder()
            .name("loop")
            .maxSteps(5)
            .addNode(NodeSpec.builder("tick", NodeKind.AGENT).query("tick").build())
            .edge(StateGraph.START, "tick")
            .edge("tick", "tick")
            .build();

        GraphServices services = GraphServices.builder()
            .agent(new GraphTestFixtures.ScriptAgent()).build();
        DefaultGraphRunner r = new DefaultGraphRunner(services, new InMemoryCheckpointStore());

        StepVerifier.create(r.start(graph, GraphTestFixtures.baseContext(), null))
            .verifyErrorSatisfies(e -> assertTrue(e.getMessage().contains("step budget")));
    }

    @Test
    void decisionNodeRoutesToChosenNode() {
        StateGraph graph = StateGraph.builder()
            .name("decision")
            .addNode(NodeSpec.builder("route", NodeKind.DECISION)
                .routes(Map.of("left", "go left", "right", "go right"))
                .build())
            .addNode(NodeSpec.builder("left", NodeKind.AGENT).query("L").build())
            .addNode(NodeSpec.builder("right", NodeKind.AGENT).query("R").build())
            .edge(StateGraph.START, "route")
            .edge("left", StateGraph.END)
            .edge("right", StateGraph.END)
            .build();

        GraphTestFixtures.ScriptAgent agent =
            new GraphTestFixtures.ScriptAgent("left", "L");
        StepVerifier.create(runner(agent).start(graph, GraphTestFixtures.baseContext(), null))
            .assertNext(res -> {
                assertEquals(GraphRunResult.Status.COMPLETED, res.status());
                assertEquals("L", res.output());
            })
            .verifyComplete();
    }

    @Test
    void parallelRunsBranchesAndMerges() {
        StateGraph graph = StateGraph.builder()
            .name("parallel")
            .addNode(NodeSpec.builder("fan", NodeKind.PARALLEL)
                .branches(List.of("p1", "p2"))
                .build())
            .addNode(NodeSpec.builder("p1", NodeKind.AGENT).query("one").build())
            .addNode(NodeSpec.builder("p2", NodeKind.AGENT).query("two").build())
            .edge(StateGraph.START, "fan")
            .edge("fan", StateGraph.END)
            .build();

        GraphTestFixtures.ScriptAgent agent = new GraphTestFixtures.ScriptAgent();
        StepVerifier.create(runner(agent).start(graph, GraphTestFixtures.baseContext(), null))
            .assertNext(res -> {
                assertEquals(GraphRunResult.Status.COMPLETED, res.status());
                assertEquals("one", res.state().var("fan.p1"));
                assertEquals("two", res.state().var("fan.p2"));
            })
            .verifyComplete();
    }

    @Test
    void pausePersistsCheckpointAndResumeContinues() {
        StateGraph graph = StateGraph.builder()
            .name("pause")
            .addNode(NodeSpec.builder("wait", NodeKind.PAUSE).waitFor("userReply").build())
            .addNode(NodeSpec.builder("done", NodeKind.AGENT).query("finish").build())
            .edge(StateGraph.START, "wait")
            .edge("wait", "done")
            .edge("done", StateGraph.END)
            .build();

        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        GraphServices services = GraphServices.builder()
            .agent(new GraphTestFixtures.ScriptAgent()).build();
        DefaultGraphRunner r = new DefaultGraphRunner(services, store);

        GraphRunResult paused = r.start(graph, GraphTestFixtures.baseContext(), null).block();
        assertNotNull(paused);
        assertEquals(GraphRunResult.Status.PAUSED, paused.status());

        StepVerifier.create(r.resume(paused.runId(), "the answer"))
            .assertNext(finished -> {
                assertEquals(GraphRunResult.Status.COMPLETED, finished.status());
                assertEquals("the answer", finished.state().var("userReply"));
                assertEquals("finish", finished.output());
            })
            .verifyComplete();
    }

    @Test
    void approvalRoutesOnApprove() {
        StateGraph graph = buildApprovalGraph();
        GraphServices services = GraphServices.builder()
            .agent(new GraphTestFixtures.ScriptAgent())
            .approvalManager(new GraphTestFixtures.AutoApprovalManager(true))
            .build();
        DefaultGraphRunner r = new DefaultGraphRunner(services, new InMemoryCheckpointStore());

        StepVerifier.create(r.start(graph, GraphTestFixtures.baseContext(), null))
            .assertNext(res -> assertEquals("PROCEED", res.output()))
            .verifyComplete();
    }

    @Test
    void approvalRoutesOnReject() {
        StateGraph graph = buildApprovalGraph();
        GraphServices services = GraphServices.builder()
            .agent(new GraphTestFixtures.ScriptAgent())
            .approvalManager(new GraphTestFixtures.AutoApprovalManager(false))
            .build();
        DefaultGraphRunner r = new DefaultGraphRunner(services, new InMemoryCheckpointStore());

        StepVerifier.create(r.start(graph, GraphTestFixtures.baseContext(), null))
            .assertNext(res -> assertEquals("STOP", res.output()))
            .verifyComplete();
    }

    private StateGraph buildApprovalGraph() {
        return StateGraph.builder()
            .name("approval")
            .addNode(NodeSpec.builder("check", NodeKind.APPROVAL)
                .approval(com.gantang.tianshu.api.workflow.ApprovalSpec.builder()
                    .subject("deploy")
                    .description("may I deploy?")
                    .onApprove("go")
                    .onReject("halt")
                    .build())
                .build())
            .addNode(NodeSpec.builder("go", NodeKind.AGENT).query("PROCEED").build())
            .addNode(NodeSpec.builder("halt", NodeKind.AGENT).query("STOP").build())
            .edge(StateGraph.START, "check")
            .edge("go", StateGraph.END)
            .edge("halt", StateGraph.END)
            .build();
    }

    @Test
    void loadsGraphFromYaml() {
        String yaml = """
            name: from-yaml
            nodes:
              - id: a
                type: agent
                query: "hi ${input}"
              - id: b
                type: pass
            edges:
              - { from: __start__, to: a }
              - { from: a, to: b, when: "x == 1" }
              - { from: a, to: __end__ }
              - { from: b, to: __end__ }
            """;

        StateGraph graph = new YamlStateGraphLoader().load(yaml);
        assertEquals("from-yaml", graph.name());
        assertTrue(graph.nodes().containsKey("a"));
        assertTrue(graph.nodes().containsKey("b"));
        assertEquals(2, graph.outgoing("a").size());
    }

    @Test
    void rejectsGraphWithoutStartEdge() {
        assertThrows(IllegalArgumentException.class, () -> StateGraph.builder()
            .name("bad")
            .addNode(NodeSpec.builder("a", NodeKind.PASS).build())
            .build());
    }
}
