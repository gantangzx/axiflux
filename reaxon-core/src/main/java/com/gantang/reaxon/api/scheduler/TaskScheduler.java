package com.gantang.reaxon.api.scheduler;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import reactor.core.publisher.Mono;

/**
 * Unified task scheduler — CRON + delay + periodic tasks.
 *
 * <p><b>Durability (P2-7):</b> whether tasks survive a process restart is an
 * implementation property, not guaranteed by this contract. The in-core
 * implementation ({@code DefaultTaskScheduler}) keeps registrations in memory
 * only and performs <b>no startup recovery</b> — callers needing persistence must
 * use a durable implementation (e.g. the axiflux-spring layer).
 */
public interface TaskScheduler {

    // === CRUD ===

    /** Create a cron task */
    String scheduleCron(String name, String cronExpr, Runnable task);

    /**
     * Create a cron task evaluated in the given timezone (e.g. "Asia/Shanghai").
     * The expression is written in local wall-clock time of {@code zone}.
     * Default implementation ignores the zone; schedulers that support time
     * zones should override this.
     */
    default String scheduleCron(String name, String cronExpr, java.time.ZoneId zone, Runnable task) {
        return scheduleCron(name, cronExpr, task);
    }

    /** Create a delay task (one-shot) */
    String scheduleDelay(String name, long delayMs, Runnable task);

    /** Create a periodic task */
    String schedulePeriodic(String name, long intervalMs, Runnable task);

    /** Cancel a task */
    void cancel(String taskId);

    /** List all tasks */
    List<ScheduledTask> listTasks();

    /** Trigger a task immediately */
    void triggerNow(String taskId);

    // === Convenience with payload ===

    Mono<String> scheduleWithPayload(String name, String cronExpr,
                                     Object payload, Callable< reactor.core.publisher.Mono<Void>> handler);

    record ScheduledTask(
        String id,
        String name,
        Type type,
        String schedule,
        boolean enabled,
        Instant nextRun,
        Instant lastRun,
        int runCount,
        int errorCount
    ) {
        public enum Type { CRON, DELAY, PERIODIC }
    }
}
