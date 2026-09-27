package com.gantang.tianshu.impl.tool.support;

import com.gantang.tianshu.api.tool.ToolResultCache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process {@link ToolResultCache}: per-session maps of key → timestamped
 * entries. Entries expire lazily on access; when the global cap is exceeded
 * the oldest entries across all sessions are evicted. Session-private —
 * keys always include the session id, and {@link #clearSession} drops a
 * whole session on deletion.
 *
 * <p>Process-local by design: a cache hit is an optimization, and a restart
 * simply causes a fresh execution. Not suitable for multi-instance sharing.
 */
public class InMemoryToolResultCache implements ToolResultCache {

    private static final Logger log = LoggerFactory.getLogger(InMemoryToolResultCache.class);

    /** Composite key carrying session isolation. */
    private record ScopedKey(String sessionId, String key) {}

    private record Entry(CachedResult result, long expiresAtEpochMilli) {}

    private final Map<ScopedKey, Entry> entries = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final int maxEntries;
    /** Results larger than this are not cached (memory bound; big outputs use the side store anyway). */
    private final int maxContentChars;

    public InMemoryToolResultCache(java.time.Duration ttl, int maxEntries, int maxContentChars) {
        this.ttlMillis = (ttl == null || ttl.isZero() || ttl.isNegative())
            ? DEFAULT_TTL.toMillis() : ttl.toMillis();
        this.maxEntries = maxEntries > 0 ? maxEntries : 1000;
        this.maxContentChars = maxContentChars > 0 ? maxContentChars : 20_000;
    }

    @Override
    public Optional<CachedResult> get(String sessionId, String key) {
        ScopedKey sk = new ScopedKey(sessionId, key);
        Entry e = entries.get(sk);
        if (e == null) return Optional.empty();
        if (System.currentTimeMillis() > e.expiresAtEpochMilli()) {
            entries.remove(sk);
            return Optional.empty();
        }
        return Optional.of(e.result());
    }

    @Override
    public void put(String sessionId, String key, CachedResult result) {
        if (sessionId == null || key == null || result == null) return;
        if (!result.success() || result.content() == null || result.content().isBlank()) return;
        if (result.content().length() > maxContentChars) return;
        evictIfNeeded();
        entries.put(new ScopedKey(sessionId, key),
            new Entry(result, System.currentTimeMillis() + ttlMillis));
    }

    @Override
    public void clearSession(String sessionId) {
        if (sessionId == null) return;
        entries.keySet().removeIf(k -> k.sessionId().equals(sessionId));
    }

    /** Remove expired entries, then oldest-first until under the cap. */
    private void evictIfNeeded() {
        if (entries.size() < maxEntries) return;
        long now = System.currentTimeMillis();
        entries.entrySet().removeIf(en -> now > en.getValue().expiresAtEpochMilli());
        while (entries.size() >= maxEntries) {
            // Evict the globally nearest-to-expiry entry (≈ LRU under uniform TTL).
            ScopedKey oldest = null;
            long oldestExpiry = Long.MAX_VALUE;
            int scanned = 0;
            for (Iterator<Map.Entry<ScopedKey, Entry>> it = entries.entrySet().iterator();
                 it.hasNext() && scanned < 200; scanned++) {
                Map.Entry<ScopedKey, Entry> en = it.next();
                if (en.getValue().expiresAtEpochMilli() < oldestExpiry) {
                    oldestExpiry = en.getValue().expiresAtEpochMilli();
                    oldest = en.getKey();
                }
            }
            if (oldest == null) break;
            entries.remove(oldest);
        }
        if (log.isDebugEnabled()) {
            log.debug("tool result cache eviction pass finished, size={}", entries.size());
        }
    }
}
