package com.gantang.reaxon.impl.scheduler;

import com.gantang.reaxon.api.observability.MetricsReporter;
import com.gantang.reaxon.api.scheduler.TaskScheduler.ScheduledTask;
import com.gantang.reaxon.api.scheduler.TaskLockProvider;
import com.gantang.reaxon.api.scheduler.TaskScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decorator that wraps a {@link TaskScheduler} with distributed locking.
 *
 * <p>Each task execution attempts to acquire a lock via {@link TaskLockProvider}.
 * If the lock cannot be acquired (another node is running it), the execution
 * is skipped. This prevents duplicate execution in multi-instance deployments.
 *
 * <p>Lock duration defaults:
 * <ul>
 *   <li>{@code lockAtMostForMs}: 5 minutes (safety net if a node crashes)</li>
 *   <li>{@code lockAtLeastForMs}: 0 (no minimum, allow immediate re-execution)</li>
 * </ul>
 *
 * <p>Design patterns:
 * <ul>
 *   <li><b>Decorator</b> — wraps a TaskScheduler, adding locking behavior
 *       without modifying the underlying scheduler's code</li>
 *   <li><b>Strategy</b> — delegates locking to the pluggable {@link TaskLockProvider}</li>
 * </ul>
 */
public class LockableTaskScheduler implements TaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(LockableTaskScheduler.class);

    private static final long DEFAULT_LOCK_AT_MOST_MS = 5 * 60 * 1000;  // 5 minutes
    private static final long DEFAULT_LOCK_AT_LEAST_MS = 0;

    private final TaskScheduler delegate;
    private final TaskLockProvider lockProvider;
    private final long lockAtMostForMs;
    private final long lockAtLeastForMs;

    // Stats for observability
    private final ConcurrentHashMap<String, AtomicLong> lockSkipCount = new ConcurrentHashMap<>();
    private volatile MetricsReporter metricsReporter = MetricsReporter.NOOP;

    public LockableTaskScheduler(TaskScheduler delegate, TaskLockProvider lockProvider) {
        this(delegate, lockProvider, DEFAULT_LOCK_AT_MOST_MS, DEFAULT_LOCK_AT_LEAST_MS);
    }

    public LockableTaskScheduler(TaskScheduler delegate, TaskLockProvider lockProvider,
                                 long lockAtMostForMs, long lockAtLeastForMs) {
        this.delegate = delegate;
        this.lockProvider = lockProvider;
        this.lockAtMostForMs = lockAtMostForMs;
        this.lockAtLeastForMs = lockAtLeastForMs;
    }

    /** Set optional metrics reporter. */
    public void setMetricsReporter(MetricsReporter metricsReporter) {
        this.metricsReporter = metricsReporter != null ? metricsReporter : MetricsReporter.NOOP;
    }

    @Override
    public String scheduleCron(String name, String cronExpr, Runnable task) {
        return delegate.scheduleCron(name, cronExpr, lockedTask(name, task));
    }

    @Override
    public String scheduleCron(String name, String cronExpr, java.time.ZoneId zone, Runnable task) {
        return delegate.scheduleCron(name, cronExpr, zone, lockedTask(name, task));
    }

    @Override
    public String scheduleDelay(String name, long delayMs, Runnable task) {
        return delegate.scheduleDelay(name, delayMs, lockedTask(name, task));
    }

    @Override
    public String schedulePeriodic(String name, long intervalMs, Runnable task) {
        return delegate.schedulePeriodic(name, intervalMs, lockedTask(name, task));
    }

    @Override
    public void cancel(String taskId) {
        delegate.cancel(taskId);
    }

    @Override
    public List<ScheduledTask> listTasks() {
        return delegate.listTasks();
    }

    @Override
    public void triggerNow(String taskId) {
        // Manual triggers are user-initiated, bypass distributed lock.
        // Simply delegate to the underlying scheduler.
        delegate.triggerNow(taskId);
    }

    @Override
    public Mono<String> scheduleWithPayload(String name, String cronExpr,
                                            Object payload,
                                            Callable<Mono<Void>> handler) {
        return delegate.scheduleWithPayload(name, cronExpr, payload, () ->
            // Defer both lock acquisition and handler assembly to subscription
            // time: the caller blocks on the returned Mono, so the lock must span
            // the actual execution. Previously the lock was released as soon as
            // handler.call() returned the (unsubscribed) Mono, which made the
            // distributed lock a no-op for payload tasks on multi-node deploys.
            Mono.defer(() -> {
                TaskLockProvider.LockHandle lock = lockProvider.tryLock(
                    name, lockAtMostForMs, lockAtLeastForMs);
                if (lock == null) {
                    lockSkipCount.computeIfAbsent(name, k -> new AtomicLong()).incrementAndGet();
                    metricsReporter.recordLockSkip(name);
                    log.debug("Skipping task {} — another node holds the lock", name);
                    return Mono.<Void>empty();
                }
                Mono<Void> work;
                try {
                    work = handler.call();
                } catch (Exception e) {
                    closeQuietly(lock);
                    return Mono.error(e);
                }
                if (work == null) {
                    work = Mono.empty();
                }
                return work.doFinally(sig -> closeQuietly(lock));
            }));
    }

    private static void closeQuietly(TaskLockProvider.LockHandle lock) {
        try {
            lock.close();
        } catch (Exception e) {
            log.warn("Failed to release task lock: {}", e.getMessage());
        }
    }

    /**
     * Get the number of times a task execution was skipped due to lock contention.
     */
    public long getLockSkipCount(String taskName) {
        AtomicLong count = lockSkipCount.get(taskName);
        return count != null ? count.get() : 0;
    }

    /** Delegate shutdown to wrapped scheduler. */
    public void shutdown() {
        if (delegate instanceof DefaultTaskScheduler dts) {
            dts.shutdown();
        }
    }

    // ─── Internal ────────────────────────────────────────────────────────

    private Runnable lockedTask(String name, Runnable original) {
        return () -> {
            TaskLockProvider.LockHandle lock = lockProvider.tryLock(
                name, lockAtMostForMs, lockAtLeastForMs);
            if (lock == null) {
                long skips = lockSkipCount.computeIfAbsent(name, k -> new AtomicLong())
                    .incrementAndGet();
                metricsReporter.recordLockSkip(name);
                log.debug("Skipping task {} — another node holds the lock (total skips: {})",
                    name, skips);
                return;
            }
            try (lock) {
                original.run();
            } catch (Exception e) {
                log.warn("Locked task {} failed: {}", name, e.getMessage());
            }
        };
    }
}
