package com.gantang.axiflux.registry.web;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Best-effort per-client rate limiter for the public install-count endpoint
 * (audit infra P2-5).
 *
 * <p>{@code POST /api/registry/skills/{slug}/install} is intentionally
 * unauthenticated (fire-and-forget from CLI installs), which means anyone can
 * inflate {@code totalInstalls} and poison the popularity sort. This limiter
 * makes bulk inflation expensive: each client key (IP) may record at most
 * {@link #DEFAULT_LIMIT} install events per sliding window
 * ({@link #DEFAULT_WINDOW_MS}); beyond that the endpoint answers 429.
 *
 * <p>Deliberately simple: in-memory, per-node, resets on restart, keyed on the
 * immediate peer address (or the first {@code X-Forwarded-For} hop when the
 * deployment opts into trusting it). NAT'd clients share a budget; a
 * determined attacker with many IPs can still inflate counts — this raises
 * the cost of casual abuse, it is not a proof-of-install. A fuller fix
 * (signed install receipts) is documented as follow-up in the audit.
 */
public final class InstallRateLimiter {

    /** Max install events per client key per window. */
    public static final int DEFAULT_LIMIT = 10;
    /** Window size in milliseconds (1 minute). */
    public static final long DEFAULT_WINDOW_MS = 60_000L;

    private final int limit;
    private final long windowMs;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    private static final class Window {
        volatile long startedAt;
        final AtomicInteger count = new AtomicInteger();
        Window(long startedAt) { this.startedAt = startedAt; }
    }

    public InstallRateLimiter() {
        this(DEFAULT_LIMIT, DEFAULT_WINDOW_MS, Clock.systemUTC());
    }

    /** Test hook: custom limit/window/clock. */
    InstallRateLimiter(int limit, long windowMs, Clock clock) {
        this.limit = Math.max(1, limit);
        this.windowMs = Math.max(1_000L, windowMs);
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * @return true when this event is within budget and recorded; false when
     *         the client has exhausted its window budget (caller should 429).
     */
    public boolean tryRecord(String clientKey) {
        String key = (clientKey == null || clientKey.isBlank()) ? "unknown" : clientKey;
        long now = clock.millis();
        Window w = windows.compute(key, (k, prev) ->
            (prev == null || now - prev.startedAt >= windowMs) ? new Window(now) : prev);
        return w.count.incrementAndGet() <= limit;
    }

    /** Bound memory: drop stale windows (called opportunistically). */
    public void evictExpired() {
        long now = clock.millis();
        windows.entrySet().removeIf(e -> now - e.getValue().startedAt >= windowMs * 2);
    }
}
