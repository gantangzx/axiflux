package com.gantang.tianshu.spring.controller;

import com.gantang.tianshu.api.workflow.Checkpoint;
import com.gantang.tianshu.api.workflow.CheckpointStore;
import com.gantang.tianshu.api.workflow.GraphRunResult;
import com.gantang.tianshu.api.workflow.GraphRunner;
import com.gantang.tianshu.api.workflow.NodeKind;
import com.gantang.tianshu.api.workflow.NodeSpec;
import com.gantang.tianshu.api.workflow.StateGraph;
import com.gantang.tianshu.impl.workflow.DefaultGraphRunner;
import com.gantang.tianshu.impl.workflow.InMemoryCheckpointStore;
import com.gantang.tianshu.spring.service.GraphCatalog;
import com.gantang.tianshu.spring.auth.CallerGuard;
import com.gantang.tianshu.spring.controller.WorkflowController.ResumeRequest;
import com.gantang.tianshu.spring.controller.WorkflowController.RunRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * End-to-end tests for the workflow REST surface using the <em>real</em> engine but
 * a graph made solely of PASS/PAUSE nodes, so no LLM or Agent bean is required.
 * Covers catalog listing, the start → pause → inspect → resume loop, the 404 contract
 * for unknown graphs/runs, and the rule that only the owning caller may resume.
 */
class WorkflowControllerTest {

    private GraphCatalog catalog;
    private CheckpointStore checkpointStore;
    private WorkflowController controller;

    @BeforeEach
    void setUp() {
        catalog = new GraphCatalog();
        checkpointStore = new InMemoryCheckpointStore();
        // No collaborators: the test graph uses only pass/pause nodes.
        GraphRunner runner = new DefaultGraphRunner(
            null, null, null, null, null, checkpointStore);
        // Null SessionManager: ownership check becomes a permissive no-op.
        CallerGuard guard = new CallerGuard((com.gantang.tianshu.api.session.SessionManager) null);
        controller = new WorkflowController(runner, catalog, guard, checkpointStore);
        catalog.register(reviewGraph());
    }

    /** start → before → review(pause) → after → end. */
    private static StateGraph reviewGraph() {
        NodeSpec before = NodeSpec.builder("before", NodeKind.PASS).build();
        NodeSpec review = NodeSpec.builder("review", NodeKind.PAUSE).waitFor("humanReview").build();
        NodeSpec after = NodeSpec.builder("after", NodeKind.PASS).build();
        return StateGraph.builder()
            .name("review-flow")
            .addNode(before)
            .addNode(review)
            .addNode(after)
            .edge(StateGraph.START, "before")
            .edge("before", "review")
            .edge("review", "after")
            .edge("after", StateGraph.END)
            .build();
    }

    @Test
    void listAndDescribe() {
        StepVerifier.create(controller.list())
            .assertNext(resp -> {
                assertEquals(1, resp.data().size());
                assertEquals("review-flow", resp.data().get(0).get("name"));
                assertEquals(3, resp.data().get(0).get("nodeCount"));
            })
            .verifyComplete();

        StepVerifier.create(controller.describe("review-flow"))
            .assertNext(resp -> assertEquals(4, resp.data().get("edgeCount")))
            .verifyComplete();
    }

    @Test
    void describeUnknownIs404() {
        StepVerifier.create(controller.describe("nope"))
            .expectErrorSatisfies(e -> assert404(e))
            .verify();
    }

    @Test
    void runPausesThenResumes() {
        // Start: should run start→before then pause at review.
        GraphRunResult paused = controller
            .run("review-flow", new RunRequest(null, null, null, null), "alice", "")
            .block().data();
        assertEquals(GraphRunResult.Status.PAUSED, paused.status());
        String runId = paused.runId();

        // Inspect the paused checkpoint.
        StepVerifier.create(controller.getRun(runId, "alice", ""))
            .assertNext(resp -> {
                Checkpoint cp = resp.data();
                assertEquals("review", cp.nodeId());
                assertEquals("humanReview", cp.reason());
                assertEquals("alice", cp.userId());
            })
            .verifyComplete();

        // A different caller must not see/resume it — 404 (no existence leak).
        StepVerifier.create(controller.getRun(runId, "bob", ""))
            .expectErrorSatisfies(WorkflowControllerTest::assert404)
            .verify();

        // Owner resumes with a payload: review → after → end.
        StepVerifier.create(controller.resume(runId, new ResumeRequest("looks good"), "alice", ""))
            .assertNext(resp -> {
                assertEquals(GraphRunResult.Status.COMPLETED, resp.data().status());
                assertEquals(runId, resp.data().runId());
            })
            .verifyComplete();
    }

    @Test
    void runUnknownGraphIs404() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
            controller.run("missing", new RunRequest(null, null, null, null), "alice", ""));
        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    void resumeUnknownRunIs404() {
        StepVerifier.create(controller.resume("no-such-run", new ResumeRequest("x"), "alice", ""))
            .expectErrorSatisfies(WorkflowControllerTest::assert404)
            .verify();
    }

    private static void assert404(Throwable e) {
        assertInstanceOf(ResponseStatusException.class, e);
        assertEquals(404, ((ResponseStatusException) e).getStatusCode().value());
    }
}
