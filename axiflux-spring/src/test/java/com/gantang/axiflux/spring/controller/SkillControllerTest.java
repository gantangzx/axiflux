package com.gantang.axiflux.spring.controller;

import com.gantang.reaxon.api.skill.*;
import com.gantang.axiflux.spring.auth.CallerGuard;
import com.gantang.axiflux.spring.service.SkillInstallService;
import com.gantang.axiflux.spring.service.SkillLedgerService;
import com.gantang.axiflux.spring.service.SkillMarketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SkillController exposes the skill registry for browsing, execution, and
 * installation. The security invariant is that execution resolves the caller
 * through CallerGuard so the identity comes from the verified header, and the
 * policy chain evaluates the real principal rather than a service identity.
 */
class SkillControllerTest {

    private SkillRegistry registry;
    private SkillExecutor executor;
    private CallerGuard guard;
    private SkillInstallService installer;
    private SkillLedgerService ledger;
    private SkillMarketService market;
    private SkillController controller;

    @BeforeEach
    void setUp() {
        registry = mock(SkillRegistry.class);
        executor = mock(SkillExecutor.class);
        guard = mock(CallerGuard.class);
        installer = mock(SkillInstallService.class);
        ledger = mock(SkillLedgerService.class);
        market = mock(SkillMarketService.class);

        ObjectProvider<SkillLedgerService> ledgerProvider = mock(ObjectProvider.class);
        when(ledgerProvider.getIfAvailable()).thenReturn(ledger);
        ObjectProvider<SkillMarketService> marketProvider = mock(ObjectProvider.class);
        when(marketProvider.getIfAvailable()).thenReturn(market);

        controller = new SkillController(registry, executor, guard, installer, ledgerProvider, marketProvider);
    }

    private Skill skill(String name, String description) {
        Skill s = mock(Skill.class);
        SkillMetadata meta = SkillMetadata.builder(name)
            .description(description)
            .executionMode("sequential")
            .steps(List.of())
            .build();
        when(s.name()).thenReturn(name);
        when(s.description()).thenReturn(description);
        when(s.triggers()).thenReturn(List.of(name));
        when(s.metadata()).thenReturn(meta);
        return s;
    }

    // ===== list =====

    @Test
    void listReturnsRegisteredSkills() {
        Skill pdf = skill("pdf", "PDF tools");
        when(registry.all()).thenReturn(List.of(pdf));
        when(ledger.ledgerByName()).thenReturn(Map.of());

        StepVerifier.create(controller.list())
            .assertNext(resp -> {
                assertTrue(resp.success());
                assertEquals(1, resp.data().size());
                assertEquals("pdf", resp.data().get(0).get("name"));
            })
            .verifyComplete();
    }

    @Test
    void listMergesDisabledSkillsFromLedger() {
        when(registry.all()).thenReturn(List.of());
        var entity = new com.gantang.axiflux.storage.entity.SkillEntity();
        entity.setName("disabled-skill");
        entity.setDescription("A disabled skill");
        entity.setSource("builtin");
        entity.setEnabled(false);
        when(ledger.ledgerByName()).thenReturn(Map.of("disabled-skill", entity));

        StepVerifier.create(controller.list())
            .assertNext(resp -> {
                assertEquals(1, resp.data().size());
                assertEquals("disabled-skill", resp.data().get(0).get("name"));
                assertEquals(false, resp.data().get(0).get("enabled"));
            })
            .verifyComplete();
    }

    // ===== get =====

    @Test
    void getKnownSkillReturnsDetail() {
        Skill pdf = skill("pdf", "PDF tools");
        when(registry.get("pdf")).thenReturn(Optional.of(pdf));
        when(ledger.ledgerByName()).thenReturn(Map.of());

        StepVerifier.create(controller.get("pdf"))
            .assertNext(resp -> {
                assertTrue(resp.success());
                assertEquals("pdf", resp.data().get("name"));
            })
            .verifyComplete();
    }

    @Test
    void getUnknownSkillReturnsError() {
        when(registry.get("nope")).thenReturn(Optional.empty());

        StepVerifier.create(controller.get("nope"))
            .expectErrorSatisfies(e -> assertInstanceOf(IllegalArgumentException.class, e))
            .verify();
    }

    // ===== execute =====

    @Test
    void executeKnownSkillRunsAndReturnsResult() {
        Skill s = skill("pdf", "PDF tools");
        when(registry.get("pdf")).thenReturn(Optional.of(s));
        when(guard.context("alice", null, null, null))
            .thenReturn(new CallerGuard.Caller("alice", "default-alice", null));
        SkillResult result = new SkillResult("pdf", true, "done", null, 1,
            List.of(), null, null, Map.of());
        when(executor.execute(eq(s), eq("merge these"), any())).thenReturn(Mono.just(result));

        var req = new SkillController.ExecuteRequest("merge these", null, null);
        StepVerifier.create(controller.execute("pdf", req, "alice", null))
            .assertNext(resp -> {
                assertTrue(resp.success());
                assertEquals("done", resp.data().output());
            })
            .verifyComplete();
    }

    @Test
    void executeUnknownSkillReturnsError() {
        when(registry.get("nope")).thenReturn(Optional.empty());
        when(guard.context("alice", null, null, null))
            .thenReturn(new CallerGuard.Caller("alice", "default-alice", null));

        var req = new SkillController.ExecuteRequest("input", null, null);
        StepVerifier.create(controller.execute("nope", req, "alice", null))
            .expectErrorSatisfies(e -> assertInstanceOf(IllegalArgumentException.class, e))
            .verify();
    }

    @Test
    void executePinsCallerToVerifiedHeader() {
        Skill s = skill("pdf", "PDF tools");
        when(registry.get("pdf")).thenReturn(Optional.of(s));
        when(guard.context("alice", "skill:run", null, null))
            .thenReturn(new CallerGuard.Caller("alice", "default-alice", "skill:run"));
        SkillResult result = new SkillResult("pdf", true, "ok", null, 0,
            List.of(), null, null, Map.of());
        when(executor.execute(eq(s), anyString(), any())).thenReturn(Mono.just(result));

        var req = new SkillController.ExecuteRequest("go", null, null);
        StepVerifier.create(controller.execute("pdf", req, "alice", "skill:run"))
            .assertNext(resp -> assertTrue(resp.success()))
            .verifyComplete();
        verify(guard).context("alice", "skill:run", null, null);
    }

    // ===== install =====

    @Test
    void installDelegatesToService() throws Exception {
        var reloadResult = new SkillReloadResult(List.of("new-skill"), List.of(), List.of(), 3);
        var installResult = new SkillInstallService.InstallResult(
            List.of(new SkillInstallService.InstalledSkill("new-skill", "/path", "git:owner/repo")),
            reloadResult);
        when(installer.install("git:owner/repo", false)).thenReturn(installResult);

        var req = new SkillController.InstallRequest("git:owner/repo", false);
        StepVerifier.create(controller.install(req, null, "skill:admin"))
            .assertNext(resp -> {
                assertTrue(resp.success());
                assertEquals(3, resp.data().get("total"));
                @SuppressWarnings("unchecked")
                var installed = (List<Map<String, Object>>) resp.data().get("installed");
                assertEquals("new-skill", installed.get(0).get("name"));
            })
            .verifyComplete();
    }

    @Test
    void installWithForceOverwrites() throws Exception {
        var reloadResult = new SkillReloadResult(List.of(), List.of("s"), List.of(), 2);
        var installResult = new SkillInstallService.InstallResult(
            List.of(new SkillInstallService.InstalledSkill("s", "/p", "zip:http://x")),
            reloadResult);
        when(installer.install("zip:http://x", true)).thenReturn(installResult);

        var req = new SkillController.InstallRequest("zip:http://x", true);
        StepVerifier.create(controller.install(req, null, "skill:admin"))
            .assertNext(resp -> assertTrue(resp.success()))
            .verifyComplete();
        verify(installer).install("zip:http://x", true);
    }

    // ===== reload =====

    @Test
    void reloadReturnsReconcileResult() {
        var reloadResult = new SkillReloadResult(List.of("a"), List.of("b"), List.of("c"), 5);
        when(executor.reloadSkills()).thenReturn(reloadResult);
        when(ledger.reconcile()).thenReturn(
            new SkillLedgerService.ReconcileResult(List.of("a"), List.of("c"), 5));

        StepVerifier.create(controller.reload("skill:admin"))
            .assertNext(resp -> {
                assertTrue(resp.success());
                assertEquals(5, resp.data().get("total"));
            })
            .verifyComplete();
    }
}
