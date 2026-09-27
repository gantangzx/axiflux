package com.gantang.tianshu.impl.tool.support;

import com.gantang.tianshu.api.tool.ToolResultCache;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryToolResultCacheTest {

    @Test
    void putThenGet_scopedBySession() {
        InMemoryToolResultCache cache =
            new InMemoryToolResultCache(Duration.ofMinutes(10), 100, 20_000);
        var cr = new ToolResultCache.CachedResult(true, "hello", null, 1L);
        cache.put("s1", "k1", cr);

        assertTrue(cache.get("s1", "k1").isPresent());
        assertEquals("hello", cache.get("s1", "k1").get().content());
        // Same key, other session: isolated.
        assertTrue(cache.get("s2", "k1").isEmpty());
    }

    @Test
    void expiredEntriesAreInvisible_andEvicted() {
        InMemoryToolResultCache cache =
            new InMemoryToolResultCache(Duration.ofMillis(1), 100, 20_000);
        cache.put("s1", "k1", new ToolResultCache.CachedResult(true, "x", null, 1L));
        try { Thread.sleep(15); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        Optional<ToolResultCache.CachedResult> got = cache.get("s1", "k1");
        assertTrue(got.isEmpty(), "expired entries must not be returned");
    }

    @Test
    void oversizedContentIsNotCached() {
        InMemoryToolResultCache cache =
            new InMemoryToolResultCache(Duration.ofMinutes(1), 100, 10);
        cache.put("s1", "big", new ToolResultCache.CachedResult(true, "x".repeat(50), null, 1L));
        assertTrue(cache.get("s1", "big").isEmpty());
    }

    @Test
    void evictsOldestWhenCapacityExceeded() {
        InMemoryToolResultCache cap =
            new InMemoryToolResultCache(Duration.ofMinutes(10), 2, 20_000);
        cap.put("s", "a", new ToolResultCache.CachedResult(true, "1", null, 1L));
        cap.put("s", "b", new ToolResultCache.CachedResult(true, "2", null, 2L));
        // Touch a so b becomes LRU-victim candidate order-wise; cap evicts by nearest expiry (FIFO-ish).
        cap.get("s", "a");
        cap.put("s", "c", new ToolResultCache.CachedResult(true, "3", null, 3L));
        // At least the newest entry survives and total stays bounded.
        assertTrue(cap.get("s", "c").isPresent());
        long present = java.util.stream.Stream.of("a", "b", "c")
            .filter(k -> cap.get("s", k).isPresent()).count();
        assertTrue(present <= 2, "cache must not exceed capacity, present=" + present);
    }

    @Test
    void clearSession_onlyClearsThatSession() {
        InMemoryToolResultCache cache =
            new InMemoryToolResultCache(Duration.ofMinutes(10), 100, 20_000);
        cache.put("s1", "k", new ToolResultCache.CachedResult(true, "1", null, 1L));
        cache.put("s2", "k", new ToolResultCache.CachedResult(true, "2", null, 2L));
        cache.clearSession("s1");
        assertTrue(cache.get("s1", "k").isEmpty());
        assertTrue(cache.get("s2", "k").isPresent());
    }
}
