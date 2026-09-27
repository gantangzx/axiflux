package com.gantang.tianshu.spring.controller;

import com.gantang.tianshu.api.tool.Tool;
import com.gantang.tianshu.api.tool.ToolRegistry;
import com.gantang.tianshu.api.tool.ToolResult;
import com.gantang.tianshu.api.tool.policy.PolicyDecision;
import com.gantang.tianshu.api.tool.policy.ToolPolicyChain;
import com.gantang.tianshu.spring.auth.CallerGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ToolController exposes the tool registry for introspection and direct
 * invocation. The security invariants are: (1) the caller identity is resolved
 * through CallerGuard so a body-supplied userId cannot override the verified
 * header, and (2) when a policy chain exists it can deny or escalate a call;
 * when it does not, requiresApproval() tools are still blocked from direct
 * invocation.
 */
class ToolControllerTest {

    private ToolRegistry registry;
    private ToolPolicyChain policyChain;
    private CallerGuard guard;
    private ToolController controller;

    @BeforeEach
    void setUp() {
        registry = mock(ToolRegistry.class);
        policyChain = mock(ToolPolicyChain.class);
        guard = mock(CallerGuard.class);

        ObjectProvider<com.gantang.tianshu.api.observability.MetricsReporter> metricsProvider =
            mock(ObjectProvider.class);
        when(metricsProvider.getIfAvailable(any())).thenReturn(com.gantang.tianshu.api.observability.MetricsReporter.NOOP);
        ObjectProvider<ToolPolicyChain> policyProvider = mock(ObjectProvider.class);
        when(policyProvider.getIfAvailable()).thenReturn(policyChain);
        ObjectProvider<com.gantang.tianshu.spring.service.PlanGateSpi> gateProvider =
            mock(ObjectProvider.class);
        when(gateProvider.getIfAvailable()).thenReturn(null);

        controller = new ToolController(registry, metricsProvider, policyProvider, guard, gateProvider);
    }

    private Tool tool(String name, String group, boolean requiresApproval, boolean hidden) {
        Tool t = mock(Tool.class);
        when(t.name()).thenReturn(name);
        when(t.description()).thenReturn(name + " tool");
        when(t.group()).thenReturn(group);
        when(t.requiresApproval()).thenReturn(requiresApproval);
        when(t.hidden()).thenReturn(hidden);
        when(t.parameters()).thenReturn(new com.fasterxml.jackson.databind.ObjectMapper().nullNode());
        // riskLevel() is a default method. Mockito mocks default methods too
        // (returns null), but the controller puts it into Map.of(...) -> NPE.
        // Stub with doReturn().when(): when(mock.defaultMethod()) would invoke
        // the real default, which calls requiresApproval() and nests a stubbing.
        doReturn(requiresApproval
                ? com.gantang.tianshu.api.tool.policy.RiskLevel.WRITE
                : com.gantang.tianshu.api.tool.policy.RiskLevel.READ)
            .when(t).riskLevel();
        return t;
    }

    // ===== list =====

    @Test
    void listFiltersHiddenTools() {
        // Hoist mock creation out of thenReturn(...) args — stubbing inside a
        // thenReturn argument nestles an unfinished when() and breaks Mockito.
        Tool visible = tool("file_read", "fs", false, false);
        Tool hiddenTool = tool("secret_tool", "internal", false, true);
        when(registry.getAll()).thenReturn(List.of(visible, hiddenTool));

        StepVerifier.create(controller.list())
            .assertNext(resp -> {
                assertTrue(resp.success());
                assertEquals(1, resp.data().size());
                assertEquals("file_read", resp.data().get(0).get("name"));
            })
            .verifyComplete();
    }

    // ===== groups =====

    @Test
    void groupsReturnsAllGroups() {
        when(registry.getGroups()).thenReturn(List.of("fs", "net", "db"));

        StepVerifier.create(controller.groups())
            .assertNext(resp -> {
                assertTrue(resp.success());
                assertEquals(3, resp.data().size());
            })
            .verifyComplete();
    }

    // ===== get =====

    @Test
    void getKnownToolReturnsDetail() {
        Tool t = tool("file_read", "fs", false, false);
        when(registry.get("file_read")).thenReturn(Optional.of(t));

        StepVerifier.create(controller.get("file_read"))
            .assertNext(resp -> {
                assertTrue(resp.success());
                assertEquals("file_read", resp.data().get("name"));
                assertEquals(false, resp.data().get("requiresApproval"));
            })
            .verifyComplete();
    }

    @Test
    void getUnknownToolReturns404() {
        when(registry.get("nope")).thenReturn(Optional.empty());

        StepVerifier.create(controller.get("nope"))
            .expectErrorSatisfies(e -> {
                assertInstanceOf(ResponseStatusException.class, e);
                assertEquals(404, ((ResponseStatusException) e).getStatusCode().value());
            })
            .verify();
    }

    // ===== invoke =====

    /** toolsec P2-6: with no policy chain wired, WRITE/DESTRUCTIVE tools are refused
     *  even when requiresApproval() is false — nothing else gates the call. */
    @Test
    void invokeWriteToolWithoutPolicyChainReturns403() {
        // Rebuild the controller with an empty policy provider (policyChain == null).
        ObjectProvider<com.gantang.tianshu.api.observability.MetricsReporter> metricsProvider =
            mock(ObjectProvider.class);
        when(metricsProvider.getIfAvailable(any())).thenReturn(com.gantang.tianshu.api.observability.MetricsReporter.NOOP);
        ObjectProvider<ToolPolicyChain> emptyPolicy = mock(ObjectProvider.class);
        when(emptyPolicy.getIfAvailable()).thenReturn(null);
        ObjectProvider<com.gantang.tianshu.spring.service.PlanGateSpi> noGate =
            mock(ObjectProvider.class);
        when(noGate.getIfAvailable()).thenReturn(null);
        ToolController bare = new ToolController(registry, metricsProvider, emptyPolicy, guard, noGate);

        Tool t = tool("file_write", "fs", false, false); // not approval-flagged…
        // …but risk level is WRITE: the P2-6 guard must refuse on risk alone.
        doReturn(com.gantang.tianshu.api.tool.policy.RiskLevel.WRITE).when(t).riskLevel();
        when(registry.get("file_write")).thenReturn(Optional.of(t));
        when(guard.context("alice", null, null, null))
            .thenReturn(new CallerGuard.Caller("alice", "default-alice", null));

        var req = new ToolController.InvokeRequest(null, null, null, Map.of("path", "/tmp/x"));
        StepVerifier.create(bare.invoke("file_write", req, "alice", null))
            .expectErrorSatisfies(e -> {
                assertInstanceOf(ResponseStatusException.class, e);
                assertEquals(403, ((ResponseStatusException) e).getStatusCode().value());
            })
            .verify();
        verify(t, never()).execute(anyString(), anyMap(), any());
    }

    /** toolsec P2-6: a READ tool still executes without a policy chain (dev mode stays usable). */
    @Test
    void invokeReadToolWithoutPolicyChainStillAllowed() {
        ObjectProvider<com.gantang.tianshu.api.observability.MetricsReporter> metricsProvider =
            mock(ObjectProvider.class);
        when(metricsProvider.getIfAvailable(any())).thenReturn(com.gantang.tianshu.api.observability.MetricsReporter.NOOP);
        ObjectProvider<ToolPolicyChain> emptyPolicy = mock(ObjectProvider.class);
        when(emptyPolicy.getIfAvailable()).thenReturn(null);
        ObjectProvider<com.gantang.tianshu.spring.service.PlanGateSpi> noGate =
            mock(ObjectProvider.class);
        when(noGate.getIfAvailable()).thenReturn(null);
        ToolController bare = new ToolController(registry, metricsProvider, emptyPolicy, guard, noGate);

        Tool t = tool("file_read", "fs", false, false);
        when(registry.get("file_read")).thenReturn(Optional.of(t));
        when(guard.context("alice", null, null, null))
            .thenReturn(new CallerGuard.Caller("alice", "default-alice", null));
        when(t.execute(anyString(), anyMap(), any()))
            .thenReturn(ToolResult.success("call-1", "contents"));

        var req = new ToolController.InvokeRequest("call-1", null, null, Map.of());
        StepVerifier.create(bare.invoke("file_read", req, "alice", null))
            .assertNext(resp -> assertEquals("contents", resp.data().get("content")))
            .verifyComplete();
    }

    @Test
    void invokeAllowedToolExecutesAndRecords() {
        Tool t = tool("file_read", "fs", false, false);
        when(registry.get("file_read")).thenReturn(Optional.of(t));
        when(guard.context("alice", null, null, null))
            .thenReturn(new CallerGuard.Caller("alice", "default-alice", null));
        when(policyChain.evaluate(eq(t), anyMap(), any()))
            .thenReturn(PolicyDecision.allow());
        when(t.execute(anyString(), anyMap(), any()))
            .thenReturn(ToolResult.success("call-1", "file contents"));

        var req = new ToolController.InvokeRequest("call-1", null, null, Map.of("path", "/tmp/x"));
        StepVerifier.create(controller.invoke("file_read", req, "alice", null))
            .assertNext(resp -> {
                assertTrue(resp.success());
                assertEquals(true, resp.data().get("success"));
                assertEquals("file contents", resp.data().get("content"));
            })
            .verifyComplete();
        verify(t).execute(eq("call-1"), anyMap(), any());
    }

    @Test
    void invokeDeniedByPolicyReturns403() {
        Tool t = tool("shell", "sys", false, false);
        when(registry.get("shell")).thenReturn(Optional.of(t));
        when(guard.context("alice", null, null, null))
            .thenReturn(new CallerGuard.Caller("alice", "default-alice", null));
        when(policyChain.evaluate(eq(t), anyMap(), any()))
            .thenReturn(PolicyDecision.deny("shell access denied"));

        var req = new ToolController.InvokeRequest(null, null, null, Map.of());
        StepVerifier.create(controller.invoke("shell", req, "alice", null))
            .expectErrorSatisfies(e -> {
                assertInstanceOf(ResponseStatusException.class, e);
                assertEquals(403, ((ResponseStatusException) e).getStatusCode().value());
            })
            .verify();
        verify(t, never()).execute(anyString(), anyMap(), any());
    }

    @Test
    void invokeAskByPolicyReturns403() {
        Tool t = tool("file_write", "fs", true, false);
        when(registry.get("file_write")).thenReturn(Optional.of(t));
        when(guard.context("alice", null, null, null))
            .thenReturn(new CallerGuard.Caller("alice", "default-alice", null));
        when(policyChain.evaluate(eq(t), anyMap(), any()))
            .thenReturn(PolicyDecision.ask("needs human approval"));

        var req = new ToolController.InvokeRequest(null, null, null, Map.of());
        StepVerifier.create(controller.invoke("file_write", req, "alice", null))
            .expectErrorSatisfies(e -> {
                assertInstanceOf(ResponseStatusException.class, e);
                assertEquals(403, ((ResponseStatusException) e).getStatusCode().value());
            })
            .verify();
        verify(t, never()).execute(anyString(), anyMap(), any());
    }

    @Test
    void invokeUnknownToolReturns404() {
        when(registry.get("nope")).thenReturn(Optional.empty());

        var req = new ToolController.InvokeRequest(null, null, null, Map.of());
        StepVerifier.create(controller.invoke("nope", req, "alice", null))
            .expectErrorSatisfies(e -> {
                assertInstanceOf(ResponseStatusException.class, e);
                assertEquals(404, ((ResponseStatusException) e).getStatusCode().value());
            })
            .verify();
    }
}
