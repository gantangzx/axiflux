package com.gantang.reaxon.impl.scheduler;

import com.gantang.reaxon.api.scheduler.TaskLockProvider;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LockableTaskSchedulerTest {

    private final DefaultTaskScheduler base = new DefaultTaskScheduler();

    @Test
    void runsTaskWhenLockAcquired() throws Exception {
        AtomicInteger executed = new AtomicInteger();
        TaskLockProvider lock = new AlwaysLock();

        LockableTaskScheduler scheduler = new LockableTaskScheduler(base, lock);
        scheduler.scheduleDelay("test", 0, executed::incrementAndGet);

        Thread.sleep(200);
        assertEquals(1, executed.get());
    }

    @Test
    void skipsTaskWhenLockNotAcquired() throws Exception {
        AtomicInteger executed = new AtomicInteger();
        TaskLockProvider lock = new NeverLock();

        LockableTaskScheduler scheduler = new LockableTaskScheduler(base, lock);
        scheduler.scheduleDelay("test", 0, executed::incrementAndGet);

        Thread.sleep(200);
        assertEquals(0, executed.get());
        assertEquals(1, scheduler.getLockSkipCount("test"));
    }

    @Test
    void periodicTaskRunsMultipleTimesWhenLockHeld() throws Exception {
        AtomicInteger executed = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(3);
        TaskLockProvider lock = new AlwaysLock();

        LockableTaskScheduler scheduler = new LockableTaskScheduler(base, lock);
        scheduler.schedulePeriodic("periodic", 50, () -> {
            executed.incrementAndGet();
            latch.countDown();
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(executed.get() >= 3);
    }

    @Test
    void lockIsReleasedAfterExecution() {
        AtomicInteger locksHeld = new AtomicInteger();
        AtomicInteger locksReleased = new AtomicInteger();

        TaskLockProvider lock = new TaskLockProvider() {
            @Override
            public LockHandle tryLock(String taskName, long atMost, long atLeast) {
                locksHeld.incrementAndGet();
                return () -> locksReleased.incrementAndGet();
            }
        };

        LockableTaskScheduler scheduler = new LockableTaskScheduler(base, lock);
        scheduler.scheduleDelay("release-test", 0, () -> {});

        // Wait for execution
        long deadline = System.currentTimeMillis() + 2000;
        while (locksReleased.get() == 0 && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(20); } catch (InterruptedException ignored) {}
        }

        assertEquals(1, locksHeld.get());
        assertEquals(1, locksReleased.get());
    }

    // ─── Test locks ────────────────────────────────────────────────────

    @Test
    void payloadLockIsHeldUntilMonoCompletes() throws Exception {
        // Regression: previously the lock was released as soon as handler.call()
        // returned the unsubscribed Mono, so the actual (blocked) execution ran
        // AFTER the lock was already closed — ShedLock was a no-op for payload tasks.
        java.util.concurrent.atomic.AtomicBoolean lockOpen = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean executedWhileLocked = new java.util.concurrent.atomic.AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(1);

        TaskLockProvider lock = (taskName, atMost, atLeast) -> {
            lockOpen.set(true);
            return () -> lockOpen.set(false);
        };

        LockableTaskScheduler scheduler = new LockableTaskScheduler(base, lock);
        String id = scheduler.scheduleWithPayload("payload-lock", "0 0 1 1 *", null,
            () -> Mono.fromRunnable(() -> {
                executedWhileLocked.set(lockOpen.get());
                done.countDown();
            })).block();
        assertNotNull(id);
        scheduler.triggerNow(id);

        assertTrue(done.await(5, TimeUnit.SECONDS), "payload task should have executed");
        assertTrue(executedWhileLocked.get(), "lock must still be held while the handler Mono executes");
        // and released afterwards
        long deadline = System.currentTimeMillis() + 2000;
        while (lockOpen.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(lockOpen.get(), "lock must be released after the Mono completes");
    }

    @Test
    void payloadSkipWhenLockNotAcquired() {
        TaskLockProvider lock = new NeverLock();
        LockableTaskScheduler scheduler = new LockableTaskScheduler(base, lock);
        String id = scheduler.scheduleWithPayload("payload-skip", "0 0 1 1 *", null,
            () -> Mono.fromRunnable(() -> fail("must not execute without the lock"))).block();
        assertNotNull(id);
        scheduler.triggerNow(id);
        long deadline = System.currentTimeMillis() + 1500;
        while (scheduler.getLockSkipCount("payload-skip") == 0 && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(20); } catch (InterruptedException ignored) {}
        }
        assertEquals(1, scheduler.getLockSkipCount("payload-skip"));
    }

    // ─── Test locks ────────────────────────────────────────────────────

    private static class AlwaysLock implements TaskLockProvider {
        @Override
        public LockHandle tryLock(String taskName, long atMost, long atLeast) {
            return () -> {};
        }
    }

    private static class NeverLock implements TaskLockProvider {
        @Override
        public LockHandle tryLock(String taskName, long atMost, long atLeast) {
            return null;
        }
    }
}
