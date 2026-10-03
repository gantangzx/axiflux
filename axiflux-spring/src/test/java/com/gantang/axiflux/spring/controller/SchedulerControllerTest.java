package com.gantang.axiflux.spring.controller;

import com.gantang.axiflux.spring.service.ScheduledTaskService;
import com.gantang.axiflux.storage.entity.ScheduledTaskEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SchedulerController manages persisted scheduled tasks. Every endpoint
 * delegates to ScheduledTaskService with the caller's effective user and admin
 * flag, so the controller tests pin the delegation pattern and the basic CRUD
 * envelope shape.
 */
class SchedulerControllerTest {

    private ScheduledTaskService service;
    private SchedulerController controller;

    private ScheduledTaskEntity entity(String id, String name, String userId) {
        ScheduledTaskEntity e = new ScheduledTaskEntity();
        e.setId(id);
        e.setName(name);
        e.setType("CRON");
        e.setSchedule("0 * * * *");
        e.setUserId(userId);
        e.setSessionId("sess-1");
        e.setEnabled(true);
        e.setNextRun(Instant.now().plusSeconds(3600));
        e.setLastRun(null);
        e.setRunCount(0);
        e.setErrorCount(0);
        // createdAt has no setter — set by @PrePersist
        e.setPayload(Map.of("kind", "agentTurn"));
        return e;
    }

    @BeforeEach
    void setUp() {
        service = mock(ScheduledTaskService.class);
        controller = new SchedulerController(service);
    }

    // ===== list =====

    @Test
    void listReturnsTasksForCaller() {
        when(service.listForCaller("alice", false))
            .thenReturn(List.of(entity("t1", "backup", "alice")));

        StepVerifier.create(controller.list(null, "alice", null))
            .assertNext(resp -> {
                assertTrue(resp.success());
                assertEquals(1, resp.data().size());
                assertEquals("t1", resp.data().get(0).get("id"));
                assertEquals("backup", resp.data().get(0).get("name"));
            })
            .verifyComplete();
    }

    @Test
    void listAdminSeesAll() {
        when(service.listForCaller("op", true))
            .thenReturn(List.of(entity("t1", "a", "alice"), entity("t2", "b", "bob")));

        StepVerifier.create(controller.list(null, "op", "scheduler:admin"))
            .assertNext(resp -> assertEquals(2, resp.data().size()))
            .verifyComplete();
    }

    // ===== get =====

    @Test
    void getFoundReturnsDetail() {
        when(service.getForCaller("t1", "alice", false))
            .thenReturn(entity("t1", "backup", "alice"));

        StepVerifier.create(controller.get("t1", "alice", null))
            .assertNext(resp -> {
                assertTrue(resp.success());
                assertEquals("t1", resp.data().get("id"));
                assertEquals("alice", resp.data().get("userId"));
            })
            .verifyComplete();
    }

    @Test
    void getMissingReturnsError() {
        when(service.getForCaller("nope", "alice", false)).thenReturn(null);

        StepVerifier.create(controller.get("nope", "alice", null))
            .expectErrorSatisfies(e -> assertInstanceOf(IllegalArgumentException.class, e))
            .verify();
    }

    // ===== create =====

    @Test
    void createCronDelegatesToService() {
        when(service.createCron(eq("nightly"), eq("0 0 * * *"), anyMap(), eq("alice"), isNull()))
            .thenReturn(entity("t-new", "nightly", "alice"));

        var req = new SchedulerController.TaskRequest("nightly", "0 0 * * *", null, null, null, null, null);
        StepVerifier.create(controller.createCron(req, "alice", null))
            .assertNext(resp -> {
                assertTrue(resp.success());
                assertEquals("t-new", resp.data().get("id"));
            })
            .verifyComplete();
    }

    @Test
    void createDelayUsesDefaultWhenDelayMsNull() {
        when(service.createDelay(eq("remind"), eq(0L), anyMap(), eq("alice"), isNull()))
            .thenReturn(entity("t-d", "remind", "alice"));

        var req = new SchedulerController.TaskRequest("remind", null, null, null, null, null, null);
        StepVerifier.create(controller.createDelay(req, "alice", null))
            .assertNext(resp -> assertEquals("t-d", resp.data().get("id")))
            .verifyComplete();
    }

    // ===== trigger / enable / disable / cancel =====

    @Test
    void triggerReturnsConfirmed() {
        doNothing().when(service).triggerNow("t1", "alice", false);

        StepVerifier.create(controller.trigger("t1", "alice", null))
            .assertNext(resp -> {
                assertTrue(resp.success());
                assertEquals(true, resp.data().get("triggered"));
            })
            .verifyComplete();
        verify(service).triggerNow("t1", "alice", false);
    }

    @Test
    void disableDelegatesToService() {
        ScheduledTaskEntity e = entity("t1", "backup", "alice");
        e.setEnabled(false);
        when(service.setEnabled("t1", false, "alice", false)).thenReturn(e);

        StepVerifier.create(controller.disable("t1", "alice", null))
            .assertNext(resp -> {
                assertTrue(resp.success());
                assertEquals(false, resp.data().get("enabled"));
            })
            .verifyComplete();
    }

    @Test
    void cancelReturnsCancelledFlag() {
        when(service.cancelTask("t1", "alice", false)).thenReturn(true);

        StepVerifier.create(controller.cancel("t1", "alice", null))
            .assertNext(resp -> {
                assertTrue(resp.success());
                assertEquals(true, resp.data().get("cancelled"));
            })
            .verifyComplete();
    }
}
