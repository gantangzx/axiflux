package com.gantang.tianshu.impl.scheduler;

import com.gantang.tianshu.api.scheduler.TaskCommand;
import com.gantang.tianshu.api.scheduler.TaskScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process {@link TaskScheduler} using a single {@link ScheduledExecutorService}.
 *
 * <p>Uses:
 *  - Command pattern via {@link TaskCommand}
 *  - Registry pattern for the {@code tasks} map
 *  - The in-house {@link SimpleCron} parser for cron expressions (keeps
 *    tianshu-core free of a Spring dependency; see {@link SimpleCron} for the
 *    supported syntax and the DOM/DOW AND semantics).
 *
 * <p><b>No persistence / no startup recovery (P2-7):</b> all registrations live
 * only in the in-memory {@code tasks} map. A process restart drops every task;
 * ids embed a monotonically increasing sequence number and cannot be rebuilt.
 * Deployments that need durability must layer a persistent scheduler on top
 * (tianshu-spring provides the persistence/recovery fallback).
 *
 * <p>Distributed locking (ShedLock) is layered on top in {@code SchedulerConfig}
 * when Redis is available.
 */
public final class DefaultTaskScheduler implements TaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(DefaultTaskScheduler.class);

    /**
     * Upper bound on how long {@link #scheduleWithPayload} may block a scheduler
     * thread waiting for the handler Mono. Keeps the synchronous contract (the
     * next scheduled run still waits for completion) while preventing a hung
     * handler from pinning the scheduling pool forever (P1-10).
     */
    private volatile Duration payloadTimeout = Duration.ofMinutes(10);

    private final ScheduledExecutorService executor;
    private final Map<String, Entry> tasks = new ConcurrentHashMap<>();
    private final Clock clock;

    public DefaultTaskScheduler() { this(defaultExecutor(), Clock.systemDefaultZone()); }

    public DefaultTaskScheduler(ScheduledExecutorService executor, Clock clock) {
        this.executor = Objects.requireNonNull(executor);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Cap for payload-handler blocking waits; values <= 0 are ignored (default 10 min). */
    public void setPayloadTimeout(Duration timeout) {
        if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
            this.payloadTimeout = timeout;
        }
    }

    private static ScheduledExecutorService defaultExecutor() {
        return Executors.newScheduledThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            r -> {
                Thread t = new Thread(r, "tianshu-scheduler");
                t.setDaemon(true);
                return t;
            });
    }

    @Override
    public String scheduleCron(String name, String cronExpr, Runnable task) {
        return scheduleCron(name, cronExpr, clock.getZone(), task);
    }

    @Override
    public String scheduleCron(String name, String cronExpr, ZoneId zone, Runnable task) {
        SimpleCron expr;
        try {
            expr = SimpleCron.parse(cronExpr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid cron expression: " + cronExpr, e);
        }
        ZoneId z = zone != null ? zone : clock.getZone();
        String id = newId("cron", name);
        Entry entry = new Entry(id, name, ScheduledTask.Type.CRON, cronExpr, task);
        entry.expr = expr;
        entry.zone = z;
        tasks.put(id, entry);
        scheduleNextCron(entry);
        return id;
    }

    @Override
    public String scheduleDelay(String name, long delayMs, Runnable task) {
        if (delayMs < 0) throw new IllegalArgumentException("delayMs must be >= 0");
        String id = newId("delay", name);
        Entry entry = new Entry(id, name, ScheduledTask.Type.DELAY, "delayMs=" + delayMs, task);
        entry.nextRun = clock.instant().plusMillis(delayMs);
        entry.future = executor.schedule(() -> runOnce(entry), delayMs, TimeUnit.MILLISECONDS);
        tasks.put(id, entry);
        return id;
    }

    @Override
    public String schedulePeriodic(String name, long intervalMs, Runnable task) {
        if (intervalMs <= 0) throw new IllegalArgumentException("intervalMs must be > 0");
        String id = newId("periodic", name);
        Entry entry = new Entry(id, name, ScheduledTask.Type.PERIODIC, "intervalMs=" + intervalMs, task);
        entry.nextRun = clock.instant().plusMillis(intervalMs);
        entry.future = executor.scheduleAtFixedRate(
            () -> runPeriodic(entry, intervalMs),
            intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        tasks.put(id, entry);
        return id;
    }

    @Override
    public void cancel(String taskId) {
        Entry e = tasks.remove(taskId);
        if (e != null && e.future != null) e.future.cancel(false);
    }

    @Override
    public List<ScheduledTask> listTasks() {
        List<ScheduledTask> out = new ArrayList<>(tasks.size());
        for (Entry e : tasks.values()) out.add(e.snapshot());
        return out;
    }

    @Override
    public void triggerNow(String taskId) {
        Entry e = tasks.get(taskId);
        if (e == null) return;
        if (e.type == ScheduledTask.Type.DELAY && e.future != null) {
            // One-shot task: cancel the still-pending future so the task body
            // cannot run twice — once now and again when the delay elapses.
            // runOnce's atomic remove below decides the single winner in case
            // the original future already started firing concurrently (P1-6).
            e.future.cancel(false);
        }
        executor.execute(() -> runOnce(e));
    }

    @Override
    public Mono<String> scheduleWithPayload(String name, String cronExpr,
                                            Object payload,
                                            Callable<Mono<Void>> handler) {
        return Mono.fromCallable(() -> scheduleCron(name, cronExpr, () -> {
            try {
                Mono<Void> m = handler.call();
                if (m != null) {
                    // Bound the block: without a timeout a hung handler (network
                    // stall, never-completing Mono) would pin a scheduler pool
                    // thread forever and starve every other task (P1-10).
                    m.timeout(payloadTimeout).block();
                }
            } catch (Exception e) {
                log.warn("scheduled handler error for {}: {}", name, e.getMessage());
            }
        }));
    }

    // === Cron rescheduling loop ===

    private void scheduleNextCron(Entry entry) {
        ZoneId z = entry.zone != null ? entry.zone : clock.getZone();
        LocalDateTime next = entry.expr.next(LocalDateTime.now(z));
        Instant nextInstant = next.atZone(z).toInstant();
        long delay = Math.max(0L, Duration.between(clock.instant(), nextInstant).toMillis());
        entry.nextRun = nextInstant;
        entry.future = executor.schedule(() -> {
            runOnce(entry);
            if (tasks.containsKey(entry.id)) scheduleNextCron(entry);
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void runOnce(Entry entry) {
        if (entry.type == ScheduledTask.Type.DELAY) {
            // One-shot semantics: atomically consume the registration. If the
            // task was already cancelled or consumed by triggerNow, skip — the
            // original scheduled future must never re-run it (P1-6).
            if (!tasks.remove(entry.id, entry)) {
                return;
            }
        } else if (!entry.running.compareAndSet(false, true)) {
            // Reentrancy guard for CRON/PERIODIC: a triggerNow overlapping the
            // natural fire time (or two overlapping triggerNows) must not run
            // the task body twice concurrently (P1-6).
            log.debug("task {}({}) skipped: previous run still in progress", entry.name, entry.id);
            return;
        }
        try {
            entry.lastRun = clock.instant();
            entry.runCount.incrementAndGet();
            try {
                entry.task.run();
            } catch (Throwable t) {
                entry.errorCount.incrementAndGet();
                log.warn("task {}({}) failed: {}", entry.name, entry.id, t.getMessage());
            }
        } finally {
            if (entry.type != ScheduledTask.Type.DELAY) {
                entry.running.set(false);
            }
        }
    }

    private void runPeriodic(Entry entry, long intervalMs) {
        runOnce(entry);
        entry.nextRun = clock.instant().plusMillis(intervalMs);
    }

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static String newId(String prefix, String name) {
        return prefix + "-" + (name == null ? "task" : name.replaceAll("\\s+", "_"))
            + "-" + SEQ.incrementAndGet();
    }

    private static final class Entry {
        final String id;
        final String name;
        final ScheduledTask.Type type;
        final String schedule;
        final Runnable task;
        volatile boolean enabled = true;
        volatile Instant nextRun;
        volatile Instant lastRun;
        final AtomicInteger runCount = new AtomicInteger();
        final AtomicInteger errorCount = new AtomicInteger();
        /** Reentrancy guard for CRON/PERIODIC task bodies (triggerNow vs natural fire). */
        final AtomicBoolean running = new AtomicBoolean();
        SimpleCron expr;
        ZoneId zone;
        Future<?> future;

        Entry(String id, String name, ScheduledTask.Type type, String schedule, Runnable task) {
            this.id = id; this.name = name; this.type = type;
            this.schedule = schedule; this.task = task;
        }

        ScheduledTask snapshot() {
            return new ScheduledTask(id, name, type, schedule, enabled,
                nextRun, lastRun, runCount.get(), errorCount.get());
        }
    }

    public void shutdown() { executor.shutdown(); }
}
