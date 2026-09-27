package com.gantang.tianshu.spring.controller;

import com.gantang.tianshu.api.auth.CallerIdentity;
import com.gantang.tianshu.spring.auth.CallerGuard;
import com.gantang.tianshu.spring.service.Membership;
import com.gantang.tianshu.spring.service.OrgDirectorySpi;
import com.gantang.tianshu.spring.service.PlanGateSpi;
import com.gantang.tianshu.spring.service.ScheduledTaskService;
import com.gantang.tianshu.storage.entity.ScheduledTaskEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P0-4 gating on {@link SchedulerController}: task creation goes through the
 * plan gate when one is wired. A free-plan org is refused with 402, a pro org
 * passes, and the compatibility cases — no PlanGate bean (self-hosted) or a
 * caller without an org — behave exactly as before.
 *
 * <p>Uses only open-core types; the closed-source {@code PlanGate} is replaced
 * by a configurable fake {@link PlanGateSpi}.
 */
class SchedulerControllerPlanGateTest {

    /** Configurable in-memory stand-in for the closed-source PlanGate. */
    static final class FakePlanGate implements PlanGateSpi {
        String plan = PlanGateSpi.PLAN_FREE;

        @Override
        public void require(String feature, CallerIdentity caller) {
            // Fail-open for callers without an organization (self-hosted / solo).
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
            return PlanGateSpi.PLAN_PRO;
        }

        private static boolean planCovers(String have, String required) {
            int rank = required.equals(PLAN_PRO) ? 1 : 0;
            int got = switch (have) {
                case PLAN_TEAM -> 2;
                case PLAN_PRO -> 1;
                default -> 0;
            };
            return got >= rank;
        }
    }

    private ScheduledTaskService service;
    private OrgDirectorySpi orgDirectory;
    private CallerGuard guard;
    private FakePlanGate gate;

    private static ObjectProvider<PlanGateSpi> providerOf(PlanGateSpi g) {
        ObjectProvider<PlanGateSpi> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(g);
        return p;
    }

    @BeforeEach
    void setUp() {
        service = mock(ScheduledTaskService.class);
        orgDirectory = mock(OrgDirectorySpi.class);
        guard = new CallerGuard((com.gantang.tianshu.api.session.SessionManager) null, orgDirectory);
        gate = new FakePlanGate();
    }

    private static SchedulerController.TaskRequest cronReq() {
        return new SchedulerController.TaskRequest("nightly", "0 0 * * *",
            null, null, null, null, null);
    }

    private static ScheduledTaskEntity entity(String id, String user) {
        ScheduledTaskEntity e = new ScheduledTaskEntity();
        e.setId(id);
        e.setName("nightly");
        e.setType("CRON");
        e.setSchedule("0 0 * * *");
        e.setUserId(user);
        e.setSessionId("default-" + user);
        e.setEnabled(true);
        e.setNextRun(Instant.now().plusSeconds(3600));
        e.setRunCount(0);
        e.setErrorCount(0);
        e.setPayload(Map.of("kind", "agentTurn"));
        return e;
    }

    @Test
    void freeOrgCallerIsBlockedWith402() {
        when(orgDirectory.primaryMembership("alice"))
            .thenReturn(Optional.of(new Membership("org_1", "MEMBER")));
        gate.plan = "free";
        SchedulerController controller = new SchedulerController(service, guard, providerOf(gate));

        StepVerifier.create(controller.createCron(cronReq(), "alice", null))
            .expectErrorSatisfies(e -> {
                ResponseStatusException rse = assertInstanceOf(ResponseStatusException.class, e);
                assertEquals(402, rse.getStatusCode().value());
                assertTrue(rse.getReason().contains("pro"));
            })
            .verify();
        verify(service, never()).createCron(any(), any(), anyMap(), any(), any());
    }

    @Test
    void freeOrgCallerIsBlockedFromDelayToo() {
        when(orgDirectory.primaryMembership("alice"))
            .thenReturn(Optional.of(new Membership("org_1", "MEMBER")));
        gate.plan = "free";
        SchedulerController controller = new SchedulerController(service, guard, providerOf(gate));

        StepVerifier.create(controller.createDelay(cronReq(), "alice", null))
            .expectErrorSatisfies(e -> {
                ResponseStatusException rse = assertInstanceOf(ResponseStatusException.class, e);
                assertEquals(402, rse.getStatusCode().value());
            })
            .verify();
    }

    @Test
    void proOrgCallerCreatesTask() {
        when(orgDirectory.primaryMembership("alice"))
            .thenReturn(Optional.of(new Membership("org_1", "ADMIN")));
        gate.plan = "pro";
        when(service.createCron(eq("nightly"), eq("0 0 * * *"), anyMap(), eq("alice"), isNull()))
            .thenReturn(entity("t-1", "alice"));
        SchedulerController controller = new SchedulerController(service, guard, providerOf(gate));

        StepVerifier.create(controller.createCron(cronReq(), "alice", null))
            .assertNext(resp -> {
                assertTrue(resp.success());
                assertEquals("t-1", resp.data().get("id"));
            })
            .verifyComplete();
    }

    @Test
    void callerWithoutOrgPassesTheGate() {
        // Self-hosted / single-user: no membership row → orgId null → allowed.
        when(orgDirectory.primaryMembership("solo")).thenReturn(Optional.empty());
        when(service.createCron(eq("nightly"), eq("0 0 * * *"), anyMap(), eq("solo"), isNull()))
            .thenReturn(entity("t-2", "solo"));
        SchedulerController controller = new SchedulerController(service, guard, providerOf(gate));

        StepVerifier.create(controller.createCron(cronReq(), "solo", null))
            .assertNext(resp -> assertEquals("t-2", resp.data().get("id")))
            .verifyComplete();
    }

    @Test
    void noPlanGateBeanSkipsGatingEntirely() {
        // Self-hosted starter: PlanGate never registered — provider is empty.
        ObjectProvider<PlanGateSpi> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        when(service.createCron(eq("nightly"), eq("0 0 * * *"), anyMap(), eq("alice"), isNull()))
            .thenReturn(entity("t-3", "alice"));
        SchedulerController controller = new SchedulerController(service, guard, empty);

        StepVerifier.create(controller.createCron(cronReq(), "alice", null))
            .assertNext(resp -> assertEquals("t-3", resp.data().get("id")))
            .verifyComplete();
        verifyNoInteractions(orgDirectory);   // guard is never consulted either
    }

    @Test
    void legacyConstructorKeepsPreP04Behaviour() {
        when(service.createCron(eq("nightly"), eq("0 0 * * *"), anyMap(), eq("alice"), isNull()))
            .thenReturn(entity("t-4", "alice"));
        SchedulerController controller = new SchedulerController(service);

        StepVerifier.create(controller.createCron(cronReq(), "alice", null))
            .assertNext(resp -> assertEquals("t-4", resp.data().get("id")))
            .verifyComplete();
    }

    @Test
    void teamPlanAlsoPassesSchedulerGate() {
        when(service.createCron(eq("nightly"), eq("0 0 * * *"), anyMap(), eq("alice"), isNull()))
            .thenReturn(entity("t-5", "alice"));
        gate.plan = "team";
        SchedulerController controller = new SchedulerController(service, guard, providerOf(gate));

        StepVerifier.create(controller.createCron(cronReq(), "alice", null))
            .assertNext(resp -> assertEquals("t-5", resp.data().get("id")))
            .verifyComplete();
    }
}
