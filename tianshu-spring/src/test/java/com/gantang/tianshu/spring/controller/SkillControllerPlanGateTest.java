package com.gantang.tianshu.spring.controller;

import com.gantang.tianshu.api.auth.CallerIdentity;
import com.gantang.tianshu.api.skill.SkillExecutor;
import com.gantang.tianshu.api.skill.SkillRegistry;
import com.gantang.tianshu.spring.auth.CallerGuard;
import com.gantang.tianshu.spring.service.Membership;
import com.gantang.tianshu.spring.service.OrgDirectorySpi;
import com.gantang.tianshu.spring.service.PlanGateSpi;
import com.gantang.tianshu.spring.service.SkillInstallService;
import com.gantang.tianshu.spring.service.SkillLedgerService;
import com.gantang.tianshu.spring.service.SkillMarketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P0-4 gating on {@link SkillController}: install and update-all enforce the
 * feature's minimum plan (pro / team) for org callers; callers without an org
 * and deployments without a PlanGate bean pass through untouched.
 *
 * <p>Only open-core types are used here: the closed-source {@code PlanGate} is
 * replaced by a configurable fake {@link PlanGateSpi}.
 */
class SkillControllerPlanGateTest {

    private static final String ADMIN_SCOPE = "skill:admin";

    /** Configurable in-memory stand-in for the closed-source PlanGate. */
    static final class FakePlanGate implements PlanGateSpi {
        String plan = PlanGateSpi.PLAN_FREE;
        boolean throw402 = true;

        @Override
        public void require(String feature, CallerIdentity caller) {
            // Fail-open for callers without an organization (self-hosted / solo
            // users): the closed-source PlanGate only enforces org callers.
            if (caller == null || caller.orgId() == null || caller.orgId().isBlank()) return;
            String required = requiredPlan(feature);
            if (!planCovers(plan, required)) {
                throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.PAYMENT_REQUIRED,
                    "upgrade required: " + required);
            }
        }

        @Override public String planOf(CallerIdentity caller) { return plan; }

        @Override
        public String requiredPlan(String feature) {
            if (PlanGateSpi.FEATURE_SKILL_UPDATE_ALL.equals(feature)) return PlanGateSpi.PLAN_TEAM;
            return PlanGateSpi.PLAN_PRO;
        }

        private static boolean planCovers(String have, String required) {
            int rank = switch (required) {
                case PLAN_TEAM -> 2;
                case PLAN_PRO -> 1;
                default -> 0;
            };
            int got = switch (have) {
                case PLAN_TEAM -> 2;
                case PLAN_PRO -> 1;
                default -> 0;
            };
            return got >= rank;
        }
    }

    private SkillRegistry registry;
    private SkillExecutor executor;
    private SkillInstallService installer;
    private OrgDirectorySpi orgDirectory;
    private CallerGuard guard;
    private FakePlanGate gate;
    private SkillController controller;

    private static <T> ObjectProvider<T> providerOf(T instance) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(instance);
        return p;
    }

    @BeforeEach
    void setUp() {
        registry = mock(SkillRegistry.class);
        executor = mock(SkillExecutor.class);
        installer = mock(SkillInstallService.class);
        orgDirectory = mock(OrgDirectorySpi.class);
        guard = new CallerGuard((com.gantang.tianshu.api.session.SessionManager) null, orgDirectory);
        gate = new FakePlanGate();

        controller = new SkillController(registry, executor, guard, installer,
            providerOf(mock(SkillLedgerService.class)),
            providerOf(mock(SkillMarketService.class)),
            providerOf(gate));
    }

    private void memberOf(String user, String orgId, String plan) {
        when(orgDirectory.primaryMembership(user))
            .thenReturn(Optional.of(new Membership(orgId, "MEMBER")));
        gate.plan = plan;
    }

    // ===== install gating (pro) =====

    @Test
    void freeOrgIsBlockedFromInstallWith402() throws Exception {
        memberOf("alice", "org_1", "free");

        StepVerifier.create(controller.install(
                new SkillController.InstallRequest("git:acme/skills", false), "alice", ADMIN_SCOPE))
            .expectErrorSatisfies(e -> {
                ResponseStatusException rse = assertInstanceOf(ResponseStatusException.class, e);
                assertEquals(402, rse.getStatusCode().value());
                assertTrue(rse.getReason().contains("pro"));
            })
            .verify();
        verify(installer, never()).install(anyString(), anyBoolean());
    }

    @Test
    void proOrgInstalls() throws Exception {
        memberOf("alice", "org_1", "pro");
        when(installer.install(anyString(), anyBoolean()))
            .thenReturn(new SkillInstallService.InstallResult(List.of(),
                new com.gantang.tianshu.api.skill.SkillReloadResult(List.of(), List.of(), List.of(), 0)));

        StepVerifier.create(controller.install(
                new SkillController.InstallRequest("git:acme/skills", false), "alice", ADMIN_SCOPE))
            .assertNext(resp -> assertTrue(resp.success()))
            .verifyComplete();
        verify(installer).install("git:acme/skills", false);
    }

    // ===== update-all gating (team) =====

    @Test
    void proOrgIsBlockedFromUpdateAll() throws Exception {
        memberOf("alice", "org_1", "pro");

        StepVerifier.create(controller.updateAll("alice", ADMIN_SCOPE))
            .expectErrorSatisfies(e -> {
                ResponseStatusException rse = assertInstanceOf(ResponseStatusException.class, e);
                assertEquals(402, rse.getStatusCode().value());
                assertTrue(rse.getReason().contains("team"));
            })
            .verify();
        verify(installer, never()).updateAll();
    }

    @Test
    void teamOrgRunsUpdateAll() {
        memberOf("alice", "org_1", "team");
        when(installer.updateAll())
            .thenReturn(new SkillInstallService.UpdateAllResult(List.of(), 0, 0, 0));

        StepVerifier.create(controller.updateAll("alice", ADMIN_SCOPE))
            .assertNext(resp -> assertTrue(resp.success()))
            .verifyComplete();
        verify(installer).updateAll();
    }

    // ===== compatibility =====

    @Test
    void callerWithoutOrgPassesEveryGate() {
        when(orgDirectory.primaryMembership("solo")).thenReturn(Optional.empty());
        gate.plan = PlanGateSpi.PLAN_FREE;
        when(installer.updateAll())
            .thenReturn(new SkillInstallService.UpdateAllResult(List.of(), 0, 0, 0));

        StepVerifier.create(controller.updateAll("solo", ADMIN_SCOPE))
            .assertNext(resp -> assertTrue(resp.success()))
            .verifyComplete();
    }

    @Test
    void noPlanGateBeanKeepsPreP04Behaviour() {
        SkillController ungated = new SkillController(registry, executor, guard, installer,
            providerOf(mock(SkillLedgerService.class)),
            providerOf(mock(SkillMarketService.class)));
        when(installer.updateAll())
            .thenReturn(new SkillInstallService.UpdateAllResult(List.of(), 0, 0, 0));

        StepVerifier.create(ungated.updateAll("alice", ADMIN_SCOPE))
            .assertNext(resp -> assertTrue(resp.success()))
            .verifyComplete();
    }

    @Test
    void missingSkillAdminScopeStillForbiddenRegardlessOfPlan() {
        memberOf("alice", "org_1", "team");
        // requireSkillAdmin is a synchronous guard on the method's first line,
        // so the 403 is thrown before any Mono is returned — assert it
        // synchronously rather than via StepVerifier.
        ResponseStatusException rse = assertThrows(ResponseStatusException.class,
            () -> controller.updateAll("alice", "chat"));
        assertEquals(403, rse.getStatusCode().value());
        verify(installer, never()).updateAll();
    }
}
