package com.gantang.tianshu.impl.agent;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Registry of live (and recently finished) background sub-agent tasks, plus the
 * process-wide fork-bomb capacity gate.
 *
 * <p>Two responsibilities:
 * <ul>
 *   <li><b>Ledger</b> — taskId → {@link SubAgentTaskInfo}. Finished records are
 *       retained briefly ({@link #FINISHED_TASK_RETENTION}) for late cancels and
 *       event replays, then evicted by {@link #purgeFinishedTasks()} so the map
 *       does not grow monotonically for the process lifetime.</li>
 *   <li><b>Capacity gate</b> — {@link #tryReserve(int)} atomically reserves child
 *       -session slots before a spawn (a critique group reserves N answerers + 1
 *       critic up front); slots are released in each pipeline's doFinally.</li>
 * </ul>
 *
 * <p>Extracted from {@code DefaultSubAgentService} (audit-2026-09-14 P1-1) with no
 * behavioural change.
 */
final class SubAgentTaskLedger {

    /** Hard cap on concurrently running child sessions across the whole process (fork-bomb guard). */
    static final int MAX_CONCURRENT_BACKGROUND_SPAWNS = 8;

    /** Finished task records are retained briefly for late cancels/event replays, then evicted. */
    private static final Duration FINISHED_TASK_RETENTION = Duration.ofHours(1);

    private final Map<String, SubAgentTaskInfo> tasks = new ConcurrentHashMap<>();

    /** Number of child runs (branches + critic counted) currently in flight. */
    private final AtomicInteger activeSpawns = new AtomicInteger(0);

    void put(SubAgentTaskInfo ti) {
        tasks.put(ti.taskId, ti);
    }

    SubAgentTaskInfo get(String taskId) {
        return tasks.get(taskId);
    }

    /** Test/ops visibility: whether a task record is currently retained. */
    boolean hasTask(String taskId) {
        return tasks.containsKey(taskId);
    }

    /** Atomic capacity reservation; returns false without mutating when full. */
    boolean tryReserve(int reserve) {
        while (true) {
            int cur = activeSpawns.get();
            if (cur + reserve > MAX_CONCURRENT_BACKGROUND_SPAWNS) return false;
            if (activeSpawns.compareAndSet(cur, cur + reserve)) return true;
        }
    }

    /** Release previously reserved slots (negative delta); called from pipeline doFinally. */
    void release(int reserve) {
        activeSpawns.addAndGet(-reserve);
    }

    /**
     * Evict records of runs finished more than {@link #FINISHED_TASK_RETENTION} ago.
     * Runs on the doFinally worker; concurrent removal only races with lookups that
     * legitimately return "task not found" after the retention window.
     */
    void purgeFinishedTasks() {
        purgeFinishedTasksOlderThan(FINISHED_TASK_RETENTION);
    }

    /** Test seam: evict finished records older than a caller-supplied retention. */
    void purgeFinishedTasksOlderThan(Duration retention) {
        long cutoff = System.currentTimeMillis() - retention.toMillis();
        tasks.entrySet().removeIf(e -> {
            SubAgentTaskInfo v = e.getValue();
            return v != null && v.finished.get()
                && v.finishedAtMillis > 0 && v.finishedAtMillis < cutoff;
        });
    }
}
