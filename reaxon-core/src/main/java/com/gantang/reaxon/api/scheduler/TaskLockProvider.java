package com.gantang.reaxon.api.scheduler;

/**
 * Abstraction for distributed task locks.
 *
 * <p>Implementations may use Redis (ShedLock), database locks, ZooKeeper, etc.
 *
 * <p>Design pattern: <b>Strategy</b> — the locking mechanism is pluggable.
 * The {@link LockableTaskScheduler} decorator uses this interface without
 * depending on any specific lock provider.
 */
public interface TaskLockProvider {

    /**
     * Attempt to acquire a distributed lock for the given task.
     *
     * @param taskName  unique task name
     * @param lockAtMostForMs  maximum lock duration (prevents deadlocks if node crashes)
     * @param lockAtLeastForMs minimum lock duration (prevents rapid re-execution)
     * @return a {@link LockHandle} if acquired, or {@code null} if another node holds the lock
     */
    LockHandle tryLock(String taskName, long lockAtMostForMs, long lockAtLeastForMs);

    /**
     * Handle representing an acquired lock. Must be closed to release.
     * Implements {@link AutoCloseable} for try-with-resources.
     */
    interface LockHandle extends AutoCloseable {
        @Override
        void close();
    }
}
