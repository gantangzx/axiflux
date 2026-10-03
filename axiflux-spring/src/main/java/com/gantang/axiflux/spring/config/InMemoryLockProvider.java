package com.gantang.axiflux.spring.config;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.support.annotation.NonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A single-process {@link LockProvider} used as a fallback when no distributed
 * store (Redis) is configured — e.g. the standalone {@code local} profile where
 * "out of the box" must work. It keeps lock rows in memory keyed by name and
 * honors {@code lockAtMostFor} (expiry) and {@code lockAtLeastFor} (retention).
 *
 * <p>This is intentionally <b>not</b> cluster-safe; multi-instance deployments
 * get the {@code RedisLockProvider} (which is registered with priority whenever a
 * {@code RedisConnectionFactory} is present). On a single JVM it still prevents
 * overlapping/re-entrant scheduled runs, which is what the local profile needs.
 */
public class InMemoryLockProvider implements LockProvider {

    private record LockRow(Instant lockedAt, Instant lockUntil, Instant unlockTime) { }

    private final Map<String, LockRow> locks = new ConcurrentHashMap<>();
    private final String hostname;

    public InMemoryLockProvider() {
        String h;
        try {
            h = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            h = "local";
        }
        this.hostname = h;
    }

    @Override
    @NonNull
    public Optional<SimpleLock> lock(@NonNull LockConfiguration configuration) {
        Instant now = Instant.now();
        String name = configuration.getName();
        LockRow existing = locks.get(name);
        if (existing != null && existing.lockUntil().isAfter(now)) {
            return Optional.empty();
        }
        LockRow row = new LockRow(now,
            configuration.getLockAtMostUntil(),
            configuration.getLockAtLeastUntil());
        // CAS so concurrent threads within the JVM cannot both acquire.
        LockRow prev = locks.putIfAbsent(name, row);
        if (prev != null && prev.lockUntil().isAfter(now)) {
            return Optional.empty();
        }
        locks.put(name, row);
        return Optional.of(new JvmLock(this, name, configuration));
    }

    private void doUnlock(String name) {
        Instant now = Instant.now();
        locks.computeIfPresent(name, (k, row) -> {
            // Respect lockAtLeastFor: keep the row (still blocking) until that time.
            if (row.unlockTime() != null && row.unlockTime().isAfter(now)) {
                return new LockRow(row.lockedAt(), row.unlockTime(), row.unlockTime());
            }
            return null;
        });
    }

    private void doExtend(String name, Duration lockAtMostFor, Duration lockAtLeastFor) {
        Instant now = Instant.now();
        locks.put(name, new LockRow(now, now.plus(lockAtMostFor), now.plus(lockAtLeastFor)));
    }

    private static final class JvmLock implements SimpleLock {
        private final InMemoryLockProvider provider;
        private final String name;

        JvmLock(InMemoryLockProvider provider, String name, LockConfiguration configuration) {
            this.provider = provider;
            this.name = name;
        }

        @Override
        public void unlock() {
            provider.doUnlock(name);
        }

        @Override
        @NonNull
        public Optional<SimpleLock> extend(@NonNull Duration lockAtMostFor,
                                           @NonNull Duration lockAtLeastFor) {
            provider.doExtend(name, lockAtMostFor, lockAtLeastFor);
            LockConfiguration nextConfig = new LockConfiguration(
                Instant.now(), name, lockAtMostFor, lockAtLeastFor);
            return Optional.of(new JvmLock(provider, name, nextConfig));
        }
    }

    /** Exposed for diagnostics/testing. */
    String owner() { return hostname; }
}
