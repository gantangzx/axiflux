package com.gantang.tianshu.impl.approval;

import com.gantang.tianshu.api.agent.ApprovalManager.ApprovalRequest;
import com.gantang.tianshu.api.tool.ToolResult;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simulates a multi-instance deployment: two {@code ApprovalManager} nodes
 * share one {@link InMemoryApprovalStore} (stand-in for a Redis store).
 * A request submitted on node A must be resolved when approve/reject is
 * invoked on node B — the decision travels through the shared store's
 * broadcast stream.
 */
class ApprovalStoreMultiInstanceTest {

    private static ApprovalRequest request(String callId) {
        return new ApprovalRequest(callId, "sess-1", "user-1",
            "file_write", "write a file", "{\"path\":\"/x\"}",
            Instant.now(), Instant.now().plus(Duration.ofSeconds(30)));
    }

    @Test
    void approveOnDifferentNode_resolvesWaitingTurn() {
        InMemoryApprovalStore sharedStore = new InMemoryApprovalStore();
        DefaultApprovalManager nodeA = new DefaultApprovalManager(sharedStore, Duration.ofSeconds(30), Duration.ofHours(1));
        DefaultApprovalManager nodeB = new DefaultApprovalManager(sharedStore, Duration.ofSeconds(30), Duration.ofHours(1));

        // Node A submits and waits (the agent turn blocks here).
        var waiting = nodeA.submit(request("call-x")).cache();
        // Node B approves after the submit has registered the pending request.
        Mono<ToolResult> approve = Mono.delay(Duration.ofMillis(100))
            .doOnNext(t -> assertTrue(nodeB.approve("call-x", "ops-human"),
                "approve on node B must find the pending request"))
            .then(Mono.empty());

        StepVerifier.create(Flux.merge(waiting, approve))
            .assertNext(result -> {
                assertTrue(result.success());
                assertTrue(result.content().contains("ops-human"));
            })
            .verifyComplete();
    }

    @Test
    void rejectOnDifferentNode_resolvesWithFailure() {
        InMemoryApprovalStore sharedStore = new InMemoryApprovalStore();
        DefaultApprovalManager nodeA = new DefaultApprovalManager(sharedStore, Duration.ofSeconds(30), Duration.ofHours(1));
        DefaultApprovalManager nodeB = new DefaultApprovalManager(sharedStore, Duration.ofSeconds(30), Duration.ofHours(1));

        var waiting = nodeA.submit(request("call-y")).cache();
        Mono<ToolResult> reject = Mono.delay(Duration.ofMillis(100))
            .doOnNext(t -> assertTrue(nodeB.reject("call-y", "ops-human", "not allowed"),
                "reject on node B must find the pending request"))
            .then(Mono.empty());

        StepVerifier.create(Flux.merge(waiting, reject))
            .assertNext(result -> {
                assertFalse(result.success());
                assertTrue(result.errorMessage().contains("not allowed"));
            })
            .verifyComplete();
    }

    @Test
    void grantOnOneNode_visibleOnOtherNode() {
        InMemoryApprovalStore sharedStore = new InMemoryApprovalStore();
        DefaultApprovalManager nodeA = new DefaultApprovalManager(sharedStore, Duration.ofSeconds(30), Duration.ofHours(1));
        DefaultApprovalManager nodeB = new DefaultApprovalManager(sharedStore, Duration.ofSeconds(30), Duration.ofHours(1));

        assertFalse(nodeA.isSessionGranted("sess-1", "file_write"));
        nodeB.grantSession("sess-1", "file_write", "ops-human");
        // Grant written to the shared store is visible on the other node.
        assertTrue(nodeA.isSessionGranted("sess-1", "file_write"));
        assertTrue(nodeA.revokeSessionGrant("sess-1", "file_write"));
        assertFalse(nodeB.isSessionGranted("sess-1", "file_write"));
    }

    @Test
    void pendingRequestVisibleOnBothNodes() {
        InMemoryApprovalStore sharedStore = new InMemoryApprovalStore();
        DefaultApprovalManager nodeA = new DefaultApprovalManager(sharedStore, Duration.ofSeconds(30), Duration.ofHours(1));
        DefaultApprovalManager nodeB = new DefaultApprovalManager(sharedStore, Duration.ofSeconds(30), Duration.ofHours(1));

        nodeA.submit(request("call-z")).subscribe();

        // Node B's console lists the pending request even though A submitted it.
        ApprovalRequest onB = nodeB.get("call-z").orElse(null);
        assertNotNull(onB);
        assertEquals("file_write", onB.toolName());
        assertEquals(1, nodeB.listPending("user-1").size());
    }
}
