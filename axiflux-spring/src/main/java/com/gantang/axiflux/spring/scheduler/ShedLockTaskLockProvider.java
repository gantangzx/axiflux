package com.gantang.axiflux.spring.scheduler;

import com.gantang.reaxon.api.scheduler.TaskLockProvider;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Adapts ShedLock's {@link LockProvider} to Axiflux's {@link TaskLockProvider}.
 *
 * <p>This allows our custom {@code ScheduledExecutorService}-based scheduler
 * to use ShedLock's distributed locking (Redis, JDBC, Mongo, etc.) without
 * requiring Spring's {@code @Scheduled} annotation.
 *
 * <p>Design pattern: <b>Adapter</b> — converts ShedLock's lock API to our
 * internal interface, decoupling the core scheduler from ShedLock specifics.
 */
public class ShedLockTaskLockProvider implements TaskLockProvider {

    private static final Logger log = LoggerFactory.getLogger(ShedLockTaskLockProvider.class);

    private final LockProvider shedLock;

    public ShedLockTaskLockProvider(LockProvider shedLock) {
        this.shedLock = shedLock;
    }

    @Override
    public LockHandle tryLock(String taskName, long lockAtMostForMs, long lockAtLeastForMs) {
        Instant now = Instant.now();
        LockConfiguration config = new LockConfiguration(
            now,
            taskName,
            Duration.ofMillis(lockAtMostForMs),
            Duration.ofMillis(lockAtLeastForMs)
        );

        Optional<SimpleLock> lock = shedLock.lock(config);
        if (lock.isEmpty()) {
            log.trace("ShedLock: could not acquire lock for '{}'", taskName);
            return null;
        }

        log.trace("ShedLock: acquired lock for '{}'", taskName);
        return new ShedLockHandle(lock.get(), taskName);
    }

    private record ShedLockHandle(SimpleLock lock, String taskName) implements LockHandle {
        @Override
        public void close() {
            try {
                lock.unlock();
                log.trace("ShedLock: released lock for '{}'", taskName);
            } catch (Exception e) {
                log.warn("Failed to release ShedLock for '{}': {}", taskName, e.getMessage());
            }
        }
    }
}
