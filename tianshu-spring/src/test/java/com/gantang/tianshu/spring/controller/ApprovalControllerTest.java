package com.gantang.tianshu.spring.controller;

import com.gantang.tianshu.api.agent.ApprovalManager;
import com.gantang.tianshu.api.agent.ApprovalManager.ApprovalRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ApprovalController is the most privileged REST surface: a pending approval is
 * a tool call the risk policy refused to run unattended. The tests pin the two
 * invariants that keep it safe — (1) a caller can only ever see/resolve their
 * own approvals, with 404 for both "absent" and "not yours" so no existence
 * leaks, and (2) the recorded approver id comes from the verified identity,
 * never from a request parameter.
 */
class ApprovalControllerTest {

    private ApprovalManager manager;
    private ApprovalController controller;

    private static ApprovalRequest req(String callId, String userId) {
        return new ApprovalRequest(callId, "sess-" + callId, userId,
            "file_write", "Write a file", "path=/tmp/x", Instant.now(),
            Instant.now().plusSeconds(300));
    }

    @BeforeEach
    void setUp() {
        manager = mock(ApprovalManager.class);
        controller = new ApprovalController(manager);
    }

    // ===== list =====

    @Test
    void listWildcardSeesWholeQueue() {
        var a = req("c1", "alice");
        var b = req("c2", "bob");
        when(manager.listPending(null)).thenReturn(List.of(a, b));

        StepVerifier.create(controller.list("op", "*", null))
            .assertNext(resp -> {
                assertTrue(resp.success());
                assertEquals(2, resp.data().size());
            })
            .verifyComplete();
        verify(manager).listPending(null);
    }

    @Test
    void listNonWildcardIsForcedToOwnUser() {
        // Even if alice asks for bob's queue explicitly, she gets her own.
        when(manager.listPending("alice")).thenReturn(List.of(req("c1", "alice")));

        StepVerifier.create(controller.list("alice", "", "bob"))
            .assertNext(resp -> assertEquals(1, resp.data().size()))
            .verifyComplete();
        verify(manager).listPending("alice");
        verify(manager, never()).listPending("bob");
    }

    // ===== get =====

    @Test
    void getOwnerSeesOwn() {
        when(manager.get("c1")).thenReturn(Optional.of(req("c1", "alice")));
        StepVerifier.create(controller.get("c1", "alice", ""))
            .assertNext(resp -> assertEquals("c1", resp.data().callId()))
            .verifyComplete();
    }

    @Test
    void getForeignReturns404() {
        // bob must not learn that c1 (alice's) exists.
        when(manager.get("c1")).thenReturn(Optional.of(req("c1", "alice")));
        StepVerifier.create(controller.get("c1", "bob", ""))
            .expectErrorSatisfies(e -> {
                assertInstanceOf(ResponseStatusException.class, e);
                assertEquals(404, ((ResponseStatusException) e).getStatusCode().value());
            })
            .verify();
    }

    @Test
    void getMissingReturns404() {
        when(manager.get("nope")).thenReturn(Optional.empty());
        StepVerifier.create(controller.get("nope", "alice", "*"))
            .expectError(ResponseStatusException.class)
            .verify();
    }

    @Test
    void getWildcardSeesAny() {
        when(manager.get("c1")).thenReturn(Optional.of(req("c1", "alice")));
        StepVerifier.create(controller.get("c1", "op", "*"))
            .assertNext(resp -> assertEquals("c1", resp.data().callId()))
            .verifyComplete();
    }

    // ===== approve =====

    @Test
    void approveOnceRecordsVerifiedApprover() {
        when(manager.get("c1")).thenReturn(Optional.of(req("c1", "alice")));
        when(manager.approve("c1", "alice")).thenReturn(true);

        StepVerifier.create(controller.approve("c1", "alice", "", "once"))
            .assertNext(resp -> {
                assertTrue(resp.success());
                assertEquals(true, resp.data().get("success"));
                assertEquals("once", resp.data().get("scope"));
            })
            .verifyComplete();
        // approver id is the verified identity, not anything from the request
        verify(manager).approve("c1", "alice");
        verify(manager, never()).grantSession(any(), any(), any());
    }

    @Test
    void approveSessionScopeGrantsSession() {
        when(manager.get("c1")).thenReturn(Optional.of(req("c1", "alice")));
        when(manager.approve("c1", "alice")).thenReturn(true);

        StepVerifier.create(controller.approve("c1", "alice", "", "session"))
            .assertNext(resp -> assertEquals("session", resp.data().get("scope")))
            .verifyComplete();
        verify(manager).grantSession("sess-c1", "file_write", "alice");
    }

    @Test
    void approveForeignIs404AndNeverResolves() {
        when(manager.get("c1")).thenReturn(Optional.of(req("c1", "alice")));
        StepVerifier.create(controller.approve("c1", "bob", "", "once"))
            .expectError(ResponseStatusException.class)
            .verify();
        // The whole point: a non-owner must not be able to execute the gated tool.
        verify(manager, never()).approve(any(), any());
    }

    // ===== reject =====

    @Test
    void rejectRecordsReasonAndApprover() {
        when(manager.get("c1")).thenReturn(Optional.of(req("c1", "alice")));
        when(manager.reject("c1", "alice", "too risky")).thenReturn(true);

        StepVerifier.create(controller.reject("c1", "alice", "", Map.of("reason", "too risky")))
            .assertNext(resp -> assertEquals("rejected", resp.data().get("action")))
            .verifyComplete();
        verify(manager).reject("c1", "alice", "too risky");
    }

    @Test
    void rejectForeignIs404AndNeverResolves() {
        when(manager.get("c1")).thenReturn(Optional.of(req("c1", "alice")));
        StepVerifier.create(controller.reject("c1", "bob", "", Map.of()))
            .expectError(ResponseStatusException.class)
            .verify();
        verify(manager, never()).reject(any(), any(), any());
    }
}
