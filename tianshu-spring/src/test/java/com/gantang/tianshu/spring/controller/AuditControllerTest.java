package com.gantang.tianshu.spring.controller;

import com.gantang.tianshu.storage.entity.ToolExecutionEntity;
import com.gantang.tianshu.storage.repository.ToolExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AuditController serves the tool_executions audit table. The security
 * invariant is tenant isolation: a non-audit-reader caller is pinned to their
 * own rows (userId filter), and a request with no identity at all is refused
 * outright (403) rather than treated as unrestricted.
 */
class AuditControllerTest {

    private ToolExecutionRepository repo;
    private AuditController controller;

    @BeforeEach
    void setUp() {
        repo = mock(ToolExecutionRepository.class);
        ObjectProvider<ToolExecutionRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(repo);
        controller = new AuditController(provider);
    }

    private ToolExecutionEntity entity(Long id, String tool, String userId, boolean success) {
        ToolExecutionEntity e = new ToolExecutionEntity();
        e.setId(id);
        e.setToolName(tool);
        e.setSessionId("sess-1");
        e.setUserId(userId);
        e.setCallId("call-" + id);
        e.setSuccess(success);
        e.setDurationMs(100);
        e.setError(success ? null : "timeout");
        e.setCreatedAt(Instant.now());
        return e;
    }

    // ===== denyAnonymous =====

    @Test
    void anonymousWithoutAuditScopeIsForbidden() {
        StepVerifier.create(controller.list(0, 50, null, null, null, null, null))
            .expectErrorSatisfies(e -> {
                assertInstanceOf(ResponseStatusException.class, e);
                assertEquals(403, ((ResponseStatusException) e).getStatusCode().value());
            })
            .verify();
    }

    @Test
    void blankUserWithoutAuditScopeIsForbidden() {
        StepVerifier.create(controller.list(0, 50, null, null, null, "  ", null))
            .expectError(ResponseStatusException.class)
            .verify();
    }

    // ===== userId pinning =====

    @Test
    void regularUserIsPinnedToOwnRows() {
        Page<ToolExecutionEntity> page = new PageImpl<>(
            List.of(entity(1L, "file_read", "alice", true)),
            PageRequest.of(0, 50), 1);
        when(repo.search(isNull(), isNull(), eq("alice"), isNull(), any(PageRequest.class)))
            .thenReturn(page);

        StepVerifier.create(controller.list(0, 50, null, null, null, "alice", null))
            .assertNext(resp -> {
                assertTrue(resp.success());
                assertEquals("self", resp.data().get("scope"));
                @SuppressWarnings("unchecked")
                var items = (List<java.util.Map<String, Object>>) resp.data().get("items");
                assertEquals(1, items.size());
                assertEquals("alice", items.get(0).get("userId"));
            })
            .verifyComplete();
        // The repository must receive the caller's id as the user filter
        verify(repo).search(isNull(), isNull(), eq("alice"), isNull(), any(PageRequest.class));
    }

    @Test
    void auditReaderSeesDeploymentWide() {
        Page<ToolExecutionEntity> page = new PageImpl<>(
            List.of(entity(1L, "file_read", "alice", true), entity(2L, "shell", "bob", false)),
            PageRequest.of(0, 50), 2);
        when(repo.search(isNull(), isNull(), isNull(), isNull(), any(PageRequest.class)))
            .thenReturn(page);

        StepVerifier.create(controller.list(0, 50, null, null, null, "op", "audit:read"))
            .assertNext(resp -> {
                assertEquals("deployment", resp.data().get("scope"));
                @SuppressWarnings("unchecked")
                var items = (List<java.util.Map<String, Object>>) resp.data().get("items");
                assertEquals(2, items.size());
            })
            .verifyComplete();
        // Audit reader passes null as user filter → sees all rows
        verify(repo).search(isNull(), isNull(), isNull(), isNull(), any(PageRequest.class));
    }

    @Test
    void wildcardCallerSeesDeploymentWide() {
        Page<ToolExecutionEntity> page = new PageImpl<>(
            List.of(entity(1L, "file_read", "alice", true)),
            PageRequest.of(0, 50), 1);
        when(repo.search(isNull(), isNull(), isNull(), isNull(), any(PageRequest.class)))
            .thenReturn(page);

        StepVerifier.create(controller.list(0, 50, null, null, null, "op", "*"))
            .assertNext(resp -> assertEquals("deployment", resp.data().get("scope")))
            .verifyComplete();
    }

    // ===== filters =====

    @Test
    void toolAndSessionFiltersArePassedThrough() {
        Page<ToolExecutionEntity> page = new PageImpl<>(List.of(), PageRequest.of(0, 50), 0);
        when(repo.search(eq("file_read"), eq("sess-9"), eq("alice"), isNull(), any(PageRequest.class)))
            .thenReturn(page);

        StepVerifier.create(controller.list(0, 50, "file_read", "sess-9", null, "alice", null))
            .assertNext(resp -> assertTrue(resp.success()))
            .verifyComplete();
        verify(repo).search(eq("file_read"), eq("sess-9"), eq("alice"), isNull(), any(PageRequest.class));
    }

    @Test
    void blankFiltersBecomeNull() {
        Page<ToolExecutionEntity> page = new PageImpl<>(List.of(), PageRequest.of(0, 50), 0);
        when(repo.search(isNull(), isNull(), eq("alice"), isNull(), any(PageRequest.class)))
            .thenReturn(page);

        StepVerifier.create(controller.list(0, 50, "  ", "  ", null, "alice", null))
            .assertNext(resp -> assertTrue(resp.success()))
            .verifyComplete();
        // blank strings are trimmed to null
        verify(repo).search(isNull(), isNull(), eq("alice"), isNull(), any(PageRequest.class));
    }

    // ===== alerts =====

    @Test
    void alertsRegularUserIsSelfScoped() {
        when(repo.countSince(any(Instant.class), eq("alice"))).thenReturn(5L);
        when(repo.countSinceBySuccess(any(Instant.class), eq(false), eq("alice"))).thenReturn(1L);
        when(repo.toolRollup(any(Instant.class), eq("alice")))
            .thenReturn(java.util.List.<Object[]>of(new Object[]{"file_read", 5L, 1L}));

        StepVerifier.create(controller.alerts(10, "alice", null))
            .assertNext(resp -> {
                assertTrue(resp.success());
                assertEquals("self", resp.data().get("scope"));
                assertEquals(5L, resp.data().get("total"));
                assertEquals(1L, resp.data().get("failures"));
            })
            .verifyComplete();
    }

    @Test
    void alertsHighFailureRateTriggersWarning() {
        when(repo.countSince(any(Instant.class), eq("alice"))).thenReturn(25L);
        when(repo.countSinceBySuccess(any(Instant.class), eq(false), eq("alice"))).thenReturn(15L);
        when(repo.toolRollup(any(Instant.class), eq("alice")))
            .thenReturn(java.util.List.<Object[]>of(new Object[]{"shell", 25L, 15L}));

        StepVerifier.create(controller.alerts(10, "alice", null))
            .assertNext(resp -> {
                assertEquals("warn", resp.data().get("level"));
                @SuppressWarnings("unchecked")
                var alerts = (List<String>) resp.data().get("alerts");
                assertFalse(alerts.isEmpty());
            })
            .verifyComplete();
    }

    // ===== unavailable =====

    @Test
    void noRepositoryReturns503() {
        ObjectProvider<ToolExecutionRepository> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        AuditController noRepo = new AuditController(empty);

        StepVerifier.create(noRepo.list(0, 50, null, null, null, "alice", null))
            .expectErrorSatisfies(e -> {
                assertInstanceOf(ResponseStatusException.class, e);
                assertEquals(503, ((ResponseStatusException) e).getStatusCode().value());
            })
            .verify();
    }
}
