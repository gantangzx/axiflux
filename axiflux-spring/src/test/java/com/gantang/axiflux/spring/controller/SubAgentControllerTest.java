package com.gantang.axiflux.spring.controller;

import com.gantang.reaxon.api.agent.BackgroundSpawner;
import com.gantang.reaxon.api.agent.DelegationRequest;
import com.gantang.reaxon.api.agent.SubAgentEventStreamer;
import com.gantang.reaxon.api.agent.SubAgentRunner;
import com.gantang.reaxon.api.agent.SubAgentRunner.SpawnResult;
import com.gantang.axiflux.spring.auth.CallerGuard;
import com.gantang.axiflux.spring.service.SubAgentCancelBridge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SubAgentController exposes the sub-agent lifecycle: spawn (blocking), cancel,
 * and the SSE event stream. The security invariant is that the spawning identity
 * always comes from the verified header, never from the request body, and that
 * cancel is owner-scoped so one tenant cannot terminate another's tasks.
 */
class SubAgentControllerTest {

    private SubAgentRunner runner;
    private BackgroundSpawner spawner;
    private SubAgentEventStreamer events;
    private CallerGuard guard;
    private SubAgentCancelBridge cancelBridge;
    private SubAgentController controller;

    @BeforeEach
    void setUp() {
        runner = mock(SubAgentRunner.class);
        spawner = mock(BackgroundSpawner.class);
        events = mock(SubAgentEventStreamer.class);
        guard = mock(CallerGuard.class);
        cancelBridge = mock(SubAgentCancelBridge.class);
        controller = new SubAgentController(runner, spawner, events, guard, cancelBridge);
    }

    // ===== spawn =====

    @Test
    void spawnRejectsBlankTask() {
        StepVerifier.create(controller.spawn(
                new SubAgentController.SpawnRequest("  ", null), "alice", null))
            .expectErrorSatisfies(e -> {
                assertInstanceOf(ResponseStatusException.class, e);
                assertEquals(400, ((ResponseStatusException) e).getStatusCode().value());
            })
            .verify();
        verifyNoInteractions(runner, events);
    }

    @Test
    void spawnRejectsNullTask() {
        StepVerifier.create(controller.spawn(
                new SubAgentController.SpawnRequest(null, null), "alice", null))
            .expectError(ResponseStatusException.class)
            .verify();
        verifyNoInteractions(runner, events);
    }

    @Test
    void spawnRejectsForeignParentSession() {
        when(guard.ownsSession("parent-sess", "alice", null)).thenReturn(false);
        StepVerifier.create(controller.spawn(
                new SubAgentController.SpawnRequest("do something", "parent-sess"), "alice", null))
            .expectErrorSatisfies(e -> {
                assertInstanceOf(ResponseStatusException.class, e);
                assertEquals(404, ((ResponseStatusException) e).getStatusCode().value());
            })
            .verify();
        verify(runner, never()).spawn(any());
    }

    @Test
    void spawnHappyPathReturnsChildSession() {
        when(guard.ownsSession("api", "alice", null)).thenReturn(true);
        when(runner.spawn(any(DelegationRequest.class)))
            .thenReturn(Mono.just(new SpawnResult("child-123", "task done")));

        StepVerifier.create(controller.spawn(
                new SubAgentController.SpawnRequest("summarize this", null), "alice", null))
            .assertNext(resp -> {
                assertTrue(resp.success());
                assertEquals("child-123", resp.data().get("childSessionId"));
                assertEquals("task done", resp.data().get("answer"));
            })
            .verifyComplete();
        // The caller identity must come from the verified header, never from the body
        verify(runner).spawn(argThat(req ->
            req.caller().userId().equals("alice") && req.parentSessionId().equals("api")));
    }

    @Test
    void spawnNullAnswerBecomesEmptyString() {
        when(guard.ownsSession("api", "alice", null)).thenReturn(true);
        when(runner.spawn(any(DelegationRequest.class)))
            .thenReturn(Mono.just(new SpawnResult("child-456", null)));

        StepVerifier.create(controller.spawn(
                new SubAgentController.SpawnRequest("noop", null), "alice", null))
            .assertNext(resp -> assertEquals("", resp.data().get("answer")))
            .verifyComplete();
    }

    // ===== cancel =====

    @Test
    void cancelKnownTaskSucceeds() {
        when(cancelBridge.cancel("task-1", "alice")).thenReturn(true);

        StepVerifier.create(controller.cancel("task-1", "alice"))
            .assertNext(resp -> {
                assertTrue(resp.success());
                assertEquals(true, resp.data().get("cancelled"));
            })
            .verifyComplete();
    }

    @Test
    void cancelUnknownTaskReturns404() {
        when(cancelBridge.cancel("task-x", "alice")).thenReturn(false);

        StepVerifier.create(controller.cancel("task-x", "alice"))
            .expectErrorSatisfies(e -> {
                assertInstanceOf(ResponseStatusException.class, e);
                assertEquals(404, ((ResponseStatusException) e).getStatusCode().value());
            })
            .verify();
    }

    @Test
    void cancelWithoutBridgeFallsToLocalService() {
        // Single-instance deployment: no Redis bridge, cancel stays local
        SubAgentController local = new SubAgentController(runner, spawner, events, guard, null);
        when(events.cancelTaskForUser("task-1", "alice")).thenReturn(true);

        StepVerifier.create(local.cancel("task-1", "alice"))
            .assertNext(resp -> assertEquals(true, resp.data().get("cancelled")))
            .verifyComplete();
        verify(events).cancelTaskForUser("task-1", "alice");
    }
}