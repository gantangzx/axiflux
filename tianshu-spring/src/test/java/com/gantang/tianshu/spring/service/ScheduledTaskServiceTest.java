package com.gantang.tianshu.spring.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.tianshu.api.scheduler.CronExpressionParser;
import com.gantang.tianshu.api.scheduler.TaskScheduler;
import com.gantang.tianshu.api.session.SessionManager;
import com.gantang.tianshu.impl.scheduler.SimpleCron;
import com.gantang.tianshu.storage.entity.ScheduledTaskEntity;
import com.gantang.tianshu.storage.repository.ScheduledTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ScheduledTaskService is the DB-backed bridge over the in-process scheduler —
 * it owns task persistence, caller scoping and the fire→stats→event pipeline.
 * The TransactionTemplate is stubbed to run its callback inline, so the tests
 * exercise the real orchestration (validation, next-run, runtime registration,
 * ownership) without a database.
 */
class ScheduledTaskServiceTest {

    private ScheduledTaskRepository repo;
    private ApplicationEventPublisher events;
    private TaskScheduler scheduler;
    private TransactionTemplate tx;
    private ScheduledTaskService service;

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T bean) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(bean);
        return p;
    }

    @BeforeEach
    void setUp() {
        repo = mock(ScheduledTaskRepository.class);
        events = mock(ApplicationEventPublisher.class);
        scheduler = mock(TaskScheduler.class);
        tx = mock(TransactionTemplate.class);
        // Run transaction bodies inline so service logic executes without a DB.
        doAnswer(inv -> {
            ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null);
            return null;
        }).when(tx).execute(any(TransactionCallback.class));
        doAnswer(inv -> {
            ((java.util.function.Consumer<org.springframework.transaction.TransactionStatus>) inv.getArgument(0))
                .accept(null);
            return null;
        }).when(tx).executeWithoutResult(any(java.util.function.Consumer.class));

        service = new ScheduledTaskService(repo, events, new ObjectMapper(),
            providerOf(scheduler), SimpleCron::parse, tx, providerOf((SessionManager) null));
        service.setScheduler(scheduler);
    }

    private static ScheduledTaskEntity entity(String id, String userId) {
        var e = new ScheduledTaskEntity();
        e.setId(id);
        e.setUserId(userId);
        e.setType("CRON");
        e.setSchedule("30 3 * * *");
        e.setEnabled(true);
        e.setRunCount(0);
        e.setPayload(Map.of("kind", "agentTurn", "query", "q"));
        return e;
    }

    // ===== create validation =====

    @Test
    void createAtRejectsPastTimestamp() {
        assertThrows(IllegalArgumentException.class, () ->
            service.createAt("t", Instant.now().minusSeconds(60), Map.of(), "alice", null));
    }

    @Test
    void createCronRegistersRuntimeTask() {
        when(scheduler.scheduleCron(anyString(), anyString(), any(java.time.ZoneId.class), any(Runnable.class)))
            .thenReturn("rt-1");
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // registerAfterCommit re-reads the persisted row before registering
        when(repo.findById(anyString())).thenAnswer(inv -> {
            var e = new ScheduledTaskEntity();
            e.setId(inv.getArgument(0));
            e.setType("CRON");
            e.setSchedule("30 3 * * *");
            e.setEnabled(true);
            return Optional.of(e);
        });

        var e = service.createCron("nightly", "30 3 * * *", Map.of(), "alice", null);
        assertNotNull(e.getId());
        assertEquals("CRON", e.getType());
        assertTrue(e.getEnabled());
        verify(scheduler).scheduleCron(eq(e.getId()), eq("30 3 * * *"), any(java.time.ZoneId.class), any(Runnable.class));
    }

    @Test
    void createTaskWithForeignSessionRejected() {
        // A task may only bind to a session the caller owns.
        SessionManager sm = mock(SessionManager.class);
        when(sm.get("sess-1")).thenReturn(Optional.empty());
        var svc = new ScheduledTaskService(repo, events, new ObjectMapper(),
            providerOf(scheduler), SimpleCron::parse, tx, providerOf(sm));
        svc.setScheduler(scheduler);

        assertThrows(IllegalArgumentException.class, () ->
            svc.createCron("x", "30 3 * * *", Map.of(), "alice", "sess-1"));
    }

    // ===== ownership =====

    @Test
    void cancelTaskNonOwnerIsFalse() {
        when(repo.findByIdAndUserId("t1", "bob")).thenReturn(Optional.empty());
        assertFalse(service.cancelTask("t1", "bob", false));
        verify(repo, never()).delete(any());
    }

    @Test
    void cancelTaskOwnerDeletes() {
        when(repo.findByIdAndUserId("t1", "alice")).thenReturn(Optional.of(entity("t1", "alice")));
        assertTrue(service.cancelTask("t1", "alice", false));
        verify(repo).delete(any(ScheduledTaskEntity.class));
    }

    @Test
    void adminSeesAllTasks() {
        when(repo.findAll()).thenReturn(java.util.List.of(entity("t1", "alice"), entity("t2", "bob")));
        assertEquals(2, service.listForCaller("bob", true).size());
        verify(repo).findAll();
    }

    // ===== fire pipeline =====

    @Test
    void onTaskFiredBumpsRunCountAndPublishes() {
        var e = entity("t1", "alice");
        when(repo.findById("t1")).thenReturn(Optional.of(e));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.onTaskFired("t1");

        assertEquals(1, e.getRunCount());
        assertNotNull(e.getLastRun());
        assertNotNull(e.getNextRun(), "cron task must recompute next run");
        verify(events).publishEvent(any(com.gantang.tianshu.spring.event.ScheduledTaskFiredEvent.class));
    }

    @Test
    void onTaskFiredOneShotClearsNextRun() {
        var e = entity("t1", "alice");
        e.setType("AT");
        e.setSchedule(Instant.now().plusSeconds(3600).toString());
        e.setNextRun(Instant.now().plusSeconds(3600));
        when(repo.findById("t1")).thenReturn(Optional.of(e));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.onTaskFired("t1");
        assertNull(e.getNextRun(), "one-shot task has no future run after firing");
    }

    // ===== update =====

    @Test
    void updateTaskTypeSwitchValidatesSchedule() {
        var e = entity("t1", "alice");
        when(repo.findByIdAndUserId("t1", "alice")).thenReturn(Optional.of(e));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // switching to PERIODIC without intervalMs must fail
        var bad = new ScheduledTaskService.TaskUpdate(null, null, null, "periodic",
            null, null, null, null, null);
        assertThrows(IllegalArgumentException.class, () ->
            service.updateTask("t1", "alice", false, bad));
    }

    @Test
    void updateTaskNonOwnerRejected() {
        when(repo.findByIdAndUserId("t1", "bob")).thenReturn(Optional.empty());
        var patch = new ScheduledTaskService.TaskUpdate("new", null, null, null,
            null, null, null, null, null);
        assertThrows(IllegalArgumentException.class, () ->
            service.updateTask("t1", "bob", false, patch));
    }
}
