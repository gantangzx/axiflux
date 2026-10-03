package com.gantang.axiflux.spring.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.reaxon.api.tool.StoredToolResult;
import com.gantang.reaxon.api.tool.ToolResultCache;
import com.gantang.reaxon.api.tool.ToolResultStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Behavioural coverage for the Redis-backed multi-instance state (roadmap
 * follow-up: 进程内态不支持多实例).
 *
 * <p>No Docker on the build host, so instead of skipping coverage entirely these
 * tests drive the real implementations against a fake that backs the exact Redis
 * operations the classes use (value/set ops + expire/delete). That verifies what
 * actually matters here — key layout, session isolation, purge, size ceilings and
 * the degradation contract — rather than only asserting that a mock was called.
 */
class RedisToolStateTest {

    /** Minimal in-memory stand-in for the Redis commands these classes issue. */
    static class FakeRedis {
        final Map<String, String> values = new HashMap<>();
        final Map<String, Set<String>> sets = new HashMap<>();
        final StringRedisTemplate template = mock(StringRedisTemplate.class);

        @SuppressWarnings("unchecked")
        FakeRedis() {
            ValueOperations<String, String> value = mock(ValueOperations.class);
            SetOperations<String, String> set = mock(SetOperations.class);
            when(template.opsForValue()).thenReturn(value);
            when(template.opsForSet()).thenReturn(set);

            doAnswer(inv -> {
                values.put(inv.getArgument(0), inv.getArgument(1));
                return null;
            }).when(value).set(anyString(), anyString(), any(Duration.class));
            when(value.get(anyString())).thenAnswer(inv -> values.get((String) inv.getArgument(0)));

            // Varargs stubbing: any(String.class) matches exactly one vararg element,
            // which is what these classes always pass. any(String[].class) silently
            // fails to match here and made the purge tests vacuous.
            when(set.add(anyString(), any(String.class))).thenAnswer(inv -> {
                String key = inv.getArgument(0);
                String member = inv.getArgument(1);
                sets.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(member);
                return 1L;
            });
            when(set.members(anyString())).thenAnswer(inv -> {
                Set<String> s = sets.get((String) inv.getArgument(0));
                return s == null ? null : new LinkedHashSet<>(s);
            });

            when(template.expire(anyString(), any(Duration.class))).thenReturn(Boolean.TRUE);
            when(template.delete(anyString())).thenAnswer(inv -> {
                String k = inv.getArgument(0);
                sets.remove(k);
                return values.remove(k) != null;
            });
            when(template.delete(any(Collection.class))).thenAnswer(inv -> {
                Collection<String> keys = inv.getArgument(0);
                long n = 0;
                for (String k : new HashSet<>(keys)) if (values.remove(k) != null) n++;
                return n;
            });
        }
    }

    /** A template whose every operation blows up, to exercise degradation paths. */
    static StringRedisTemplate brokenTemplate() {
        StringRedisTemplate t = mock(StringRedisTemplate.class);
        when(t.opsForValue()).thenThrow(new IllegalStateException("redis down"));
        when(t.opsForSet()).thenThrow(new IllegalStateException("redis down"));
        return t;
    }

    private final ObjectMapper om = new ObjectMapper();

    // ==================== side store (correctness-critical) ====================

    @Nested
    @DisplayName("RedisToolResultStore")
    class SideStore {

        FakeRedis fake;
        RedisToolResultStore store;

        @BeforeEach
        void setUp() {
            fake = new FakeRedis();
            store = new RedisToolResultStore(fake.template, om);
        }

        @Test
        void storedResultIsRetrievableByHandle() {
            String handle = store.store("s1", "the full grep output", "grep_search");

            assertTrue(ToolResultStore.isHandle(handle), handle);
            // Guard against a vacuous pass: the record must really be in Redis,
            // not silently served by the local degradation tier.
            assertFalse(fake.values.isEmpty(), "record must go through Redis");
            assertEquals(1, fake.sets.size(), "session index must be maintained");
            Optional<StoredToolResult> got = store.fetch("s1", handle);
            assertTrue(got.isPresent());
            assertEquals("the full grep output", got.get().content());
            assertEquals("grep_search", got.get().originTool());
            assertEquals("s1", got.get().sessionId());
        }

        @Test
        void anotherSessionCannotDereferenceTheHandle() {
            // This is the whole point of session-scoped keys: guessing the id is useless.
            String handle = store.store("s1", "secret", "file_read");

            assertTrue(store.fetch("s2", handle).isEmpty(), "foreign session must not read");
            assertTrue(store.fetch("s1", handle).isPresent(), "owner still reads");
        }

        @Test
        void clearSessionPurgesEveryRecordOfThatSessionOnly() {
            String a = store.store("s1", "one", "grep_search");
            String b = store.store("s1", "two", "grep_search");
            String other = store.store("s2", "keep", "grep_search");

            store.clearSession("s1");

            assertTrue(store.fetch("s1", a).isEmpty());
            assertTrue(store.fetch("s1", b).isEmpty());
            assertTrue(store.fetch("s2", other).isPresent(), "other session untouched");
        }

        @Test
        void oversizedRecordIsCappedWithAnExplicitMarker() {
            RedisToolResultStore small =
                new RedisToolResultStore(fake.template, om, Duration.ofMinutes(5), 100);
            String handle = small.store("s1", "x".repeat(5000), "code_executor");

            String content = small.fetch("s1", handle).orElseThrow().content();
            assertTrue(content.startsWith("x".repeat(100)));
            assertTrue(content.contains("truncated at 100 chars"), content.substring(100));
            assertTrue(content.length() < 5000, "must not store the full 5000 chars");
        }

        @Test
        void redisFailureDegradesToLocalStoreInsteadOfDanglingHandle() {
            // A handle the model cannot dereference is worse than no handle at all,
            // so a broken Redis must fall back rather than fail the turn.
            RedisToolResultStore degraded = new RedisToolResultStore(brokenTemplate(), om);

            String handle = degraded.store("s1", "still works", "grep_search");
            assertTrue(ToolResultStore.isHandle(handle));
            assertEquals("still works", degraded.fetch("s1", handle).orElseThrow().content());
        }

        @Test
        void unknownHandleYieldsEmptyRatherThanError() {
            assertTrue(store.fetch("s1", "ref://tool-result/does-not-exist").isEmpty());
            assertTrue(store.fetch("s1", "").isEmpty());
            assertTrue(store.fetch("s1", null).isEmpty());
        }
    }

    // ==================== cache (cost/hit-rate, never correctness) ====================

    @Nested
    @DisplayName("RedisToolResultCache")
    class Cache {

        FakeRedis fake;
        RedisToolResultCache cache;

        @BeforeEach
        void setUp() {
            fake = new FakeRedis();
            cache = new RedisToolResultCache(fake.template, om, Duration.ofMinutes(10), 20_000);
        }

        private com.gantang.reaxon.api.tool.ToolResultCache.CachedResult ok(String content) {
            return new com.gantang.reaxon.api.tool.ToolResultCache.CachedResult(true, content, null, System.currentTimeMillis());
        }

        @Test
        void cachedResultIsReplayedForTheSameCanonicalKey() {
            String key = com.gantang.reaxon.api.tool.ToolResultCache.key("web_fetch", "{\"url\":\"https://a\"}");
            cache.put("s1", key, ok("page body"));

            Optional<com.gantang.reaxon.api.tool.ToolResultCache.CachedResult> hit = cache.get("s1", key);
            assertTrue(hit.isPresent());
            assertTrue(hit.get().success());
            assertEquals("page body", hit.get().content());
        }

        @Test
        void sessionsDoNotShareEntries() {
            String key = com.gantang.reaxon.api.tool.ToolResultCache.key("grep_search", "{\"pattern\":\"x\"}");
            cache.put("s1", key, ok("hits"));

            assertTrue(cache.get("s2", key).isEmpty(), "cache must stay session-private");
        }

        @Test
        void failedAndOversizedResultsAreNotCached() {
            String failKey = com.gantang.reaxon.api.tool.ToolResultCache.key("web_fetch", "{\"url\":\"https://boom\"}");
            cache.put("s1", failKey, new com.gantang.reaxon.api.tool.ToolResultCache.CachedResult(false, null, "timeout", 1L));
            assertTrue(cache.get("s1", failKey).isEmpty(), "errors must not be replayed");

            String bigKey = com.gantang.reaxon.api.tool.ToolResultCache.key("file_read", "{\"path\":\"big\"}");
            cache.put("s1", bigKey, ok("y".repeat(20_001)));
            assertTrue(cache.get("s1", bigKey).isEmpty(), "oversized payload must be skipped");
        }

        @Test
        void clearSessionDropsTheSessionEntries() {
            String key = com.gantang.reaxon.api.tool.ToolResultCache.key("calculator", "{\"expression\":\"1+1\"}");
            cache.put("s1", key, ok("2"));
            cache.clearSession("s1");

            assertTrue(cache.get("s1", key).isEmpty());
        }

        @Test
        void redisFailureIsAMissNotAnError() {
            RedisToolResultCache degraded =
                new RedisToolResultCache(brokenTemplate(), om, Duration.ofMinutes(1), 20_000);
            String key = com.gantang.reaxon.api.tool.ToolResultCache.key("calculator", "{\"expression\":\"2+2\"}");

            assertDoesNotThrow(() -> degraded.put("s1", key, ok("4")));
            assertTrue(degraded.get("s1", key).isEmpty());
            assertDoesNotThrow(() -> degraded.clearSession("s1"));
        }

        @Test
        void longNonAsciiKeysAreHashedIntoSafeDistinctKeys() {
            // Raw canonical keys carry arbitrary tool arguments; they must never
            // reach Redis verbatim (length + charset hazards).
            String a = com.gantang.reaxon.api.tool.ToolResultCache.key("grep_search", "查询".repeat(5000));
            String b = com.gantang.reaxon.api.tool.ToolResultCache.key("grep_search", "查询".repeat(5000) + "!");

            cache.put("s1", a, ok("A"));
            cache.put("s1", b, ok("B"));

            assertEquals("A", cache.get("s1", a).orElseThrow().content());
            assertEquals("B", cache.get("s1", b).orElseThrow().content());
            for (String k : fake.values.keySet()) {
                assertTrue(k.length() < 200, "key must be bounded: " + k.length());
                assertEquals(k, k.trim());
            }
        }
    }
}
