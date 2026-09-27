package com.gantang.tianshu.impl.approval;

import com.gantang.tianshu.api.agent.ApprovalManager;
import com.gantang.tianshu.api.tool.ToolResult;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class DefaultApprovalManagerTest {

    private ApprovalManager.ApprovalRequest sampleRequest(String callId) {
        return new ApprovalManager.ApprovalRequest(
            callId, "sess-1", "user-1",
            "code_executor", "Execute shell command",
            "{\"command\":\"ls\"}",
            Instant.now(), null
        );
    }

    @Test
    void approve_resolvesWithSuccess() {
        DefaultApprovalManager mgr = new DefaultApprovalManager(Duration.ofSeconds(30));
        String callId = "call-approve-1";

        var mono = mgr.submit(sampleRequest(callId));

        // Approve from another "thread" after a short delay
        new Thread(() -> {
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            assertTrue(mgr.approve(callId, "admin"));
        }).start();

        StepVerifier.create(mono)
            .assertNext(result -> {
                assertTrue(result.success());
                assertTrue(result.content().contains("Approved"));
            })
            .verifyComplete();
    }

    @Test
    void reject_resolvesWithFailure() {
        DefaultApprovalManager mgr = new DefaultApprovalManager(Duration.ofSeconds(30));
        String callId = "call-reject-1";

        var mono = mgr.submit(sampleRequest(callId));

        new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            mgr.reject(callId, "admin", "too dangerous");
        }).start();

        StepVerifier.create(mono)
            .assertNext(result -> {
                assertFalse(result.success());
                assertTrue(result.errorMessage().contains("too dangerous"));
            })
            .verifyComplete();
    }

    @Test
    void timeout_autoRejects() {
        DefaultApprovalManager mgr = new DefaultApprovalManager(Duration.ofMillis(200));
        String callId = "call-timeout-1";

        // Set explicit expiry in the past-ish to force quick timeout
        ApprovalManager.ApprovalRequest req = new ApprovalManager.ApprovalRequest(
            callId, "sess-1", "user-1",
            "tool", "desc", "{}",
            Instant.now(), Instant.now().plusMillis(200));

        StepVerifier.create(mgr.submit(req))
            .assertNext(result -> {
                assertFalse(result.success());
                assertTrue(result.errorMessage().contains("timed out"));
            })
            .verifyComplete();
    }

    @Test
    void listPending_returnsOutstandingRequests() {
        DefaultApprovalManager mgr = new DefaultApprovalManager(Duration.ofSeconds(30));
        mgr.submit(sampleRequest("call-list-1")).subscribe();
        mgr.submit(sampleRequest("call-list-2")).subscribe();

        assertEquals(2, mgr.listPending(null).size());
        assertEquals(2, mgr.listPending("user-1").size());
        assertTrue(mgr.listPending("other-user").isEmpty());
    }

    @Test
    void approve_nonexistent_returnsFalse() {
        DefaultApprovalManager mgr = new DefaultApprovalManager(Duration.ofSeconds(10));
        assertFalse(mgr.approve("nonexistent", "admin"));
    }

    @Test
    void get_existing_returnsRequest() {
        DefaultApprovalManager mgr = new DefaultApprovalManager(Duration.ofSeconds(10));
        mgr.submit(sampleRequest("call-get-1")).subscribe();

        assertTrue(mgr.get("call-get-1").isPresent());
        assertEquals("code_executor", mgr.get("call-get-1").get().toolName());
        assertTrue(mgr.get("nonexistent").isEmpty());
    }
}
