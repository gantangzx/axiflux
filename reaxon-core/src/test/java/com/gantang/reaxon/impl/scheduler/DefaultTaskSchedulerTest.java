package com.gantang.reaxon.impl.scheduler;

import com.gantang.reaxon.api.scheduler.TaskScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DefaultTaskSchedulerTest {

    private final DefaultTaskScheduler scheduler = new DefaultTaskScheduler();

    @AfterEach
    void tearDown() { scheduler.shutdown(); }

    @Test
    void delay_task_fires_once() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        String id = scheduler.scheduleDelay("d", 50, latch::countDown);
        assertNotNull(id);
        assertTrue(latch.await(2, TimeUnit.SECONDS));
    }

    @Test
    void periodic_task_fires_multiple_times() throws Exception {
        AtomicInteger n = new AtomicInteger();
        String id = scheduler.schedulePeriodic("p", 30, n::incrementAndGet);
        Thread.sleep(150);
        scheduler.cancel(id);
        assertTrue(n.get() >= 2, "expected periodic to fire >=2 times, got " + n.get());
    }

    @Test
    void trigger_now_runs_immediately() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        String id = scheduler.scheduleCron("c", "0 0 1 1 *", latch::countDown);
        scheduler.triggerNow(id);
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        scheduler.cancel(id);
    }

    @Test
    void invalid_cron_expression_is_rejected() {
        assertThrows(IllegalArgumentException.class,
            () -> scheduler.scheduleCron("bad", "not a cron", () -> {}));
    }

    @Test
    void list_tasks_reports_all_registered() {
        String a = scheduler.scheduleDelay("a", 10_000, () -> {});
        String b = scheduler.schedulePeriodic("b", 10_000, () -> {});
        String c = scheduler.scheduleCron("c", "*/5 * * * *", () -> {});
        var all = scheduler.listTasks();
        assertEquals(3, all.size());
        assertTrue(all.stream().anyMatch(t -> t.id().equals(a) && t.type() == TaskScheduler.ScheduledTask.Type.DELAY));
        assertTrue(all.stream().anyMatch(t -> t.id().equals(b) && t.type() == TaskScheduler.ScheduledTask.Type.PERIODIC));
        assertTrue(all.stream().anyMatch(t -> t.id().equals(c) && t.type() == TaskScheduler.ScheduledTask.Type.CRON));
        scheduler.cancel(a); scheduler.cancel(b); scheduler.cancel(c);
    }

    // ==== P1-6: triggerNow races ====

    @Test
    void triggerNow_on_delay_task_runs_exactly_once() throws Exception {
        // P1-6: triggering a DELAY task early must cancel its pending future —
        // otherwise the task body runs twice (now, and again when the delay elapses).
        AtomicInteger runs = new AtomicInteger();
        String id = scheduler.scheduleDelay("d", 300, runs::incrementAndGet);
        scheduler.triggerNow(id);
        // Wait past the original fire time so a forgotten future would fire too.
        Thread.sleep(700);
        assertEquals(1, runs.get(), "DELAY task must run exactly once after triggerNow");
    }

    @Test
    void triggerNow_on_cron_task_does_not_reenter_a_running_body() throws Exception {
        // P1-6: overlapping triggerNow calls must not run a CRON task body
        // concurrently — the reentrancy guard skips the overlapping run.
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        String id = scheduler.scheduleCron("c", "0 0 1 1 *", () -> {
            int now = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            runs.incrementAndGet();
            entered.countDown();
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            concurrent.decrementAndGet();
        });
        scheduler.triggerNow(id);
        assertTrue(entered.await(2, TimeUnit.SECONDS), "first run should start");
        // This trigger overlaps the in-flight run and must be skipped.
        scheduler.triggerNow(id);
        Thread.sleep(300);
        release.countDown();
        Thread.sleep(300);
        assertEquals(1, maxConcurrent.get(), "task body must never run concurrently with itself");
        assertEquals(1, runs.get(), "overlapping triggerNow must be skipped, not queued");
        scheduler.cancel(id);
    }

    // ==== P1-10: payload handler block is time-boxed ====

    @Test
    void payload_handler_that_hangs_is_timeboxed_off_the_scheduler_thread() throws Exception {
        // P1-10: a never-completing handler Mono must not pin a scheduler thread
        // forever — the block is wrapped in a timeout, the failure is logged by
        // the payload wrapper (unchanged semantics), and the thread is released.
        DefaultTaskScheduler ts = new DefaultTaskScheduler();
        ts.setPayloadTimeout(java.time.Duration.ofMillis(300));
        try {
            AtomicInteger handlerCalls = new AtomicInteger();
            String id = ts.scheduleWithPayload("p", "0 0 1 1 *", new Object(), () -> {
                handlerCalls.incrementAndGet();
                return reactor.core.publisher.Mono.never();
            }).block(java.time.Duration.ofSeconds(5));
            assertNotNull(id);
            ts.triggerNow(id);
            waitFor(() -> handlerCalls.get() >= 1, 3000);
            assertEquals(1, handlerCalls.get(), "first payload run should have started");
            // Past the 300ms cap the first run is released; a second trigger must
            // be allowed to start (if the block still hung, the reentrancy guard
            // would skip it forever).
            Thread.sleep(1200);
            ts.triggerNow(id);
            waitFor(() -> handlerCalls.get() >= 2, 3000);
            assertEquals(2, handlerCalls.get(),
                "a time-boxed first run must release the thread instead of hanging");
            ts.cancel(id);
        } finally {
            ts.shutdown();
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier cond, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
    }
}
