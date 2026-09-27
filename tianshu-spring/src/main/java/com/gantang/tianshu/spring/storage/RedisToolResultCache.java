package com.gantang.tianshu.spring.storage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.tianshu.api.tool.ToolResultCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis-backed cache for idempotent read-only tool results.
 *
 * <p>Unlike the side store this is pure optimisation: a miss only costs a real
 * tool execution. Sharing it across instances matters because the hit rate is
 * what pays for the feature — with a per-process cache an N-instance deployment
 * dilutes the hit rate roughly N-fold, and the repeated-call case that motivated
 * the cache (model re-issuing the same grep/fetch) frequently lands on a
 * different node than the first call.
 *
 * <p>Keys are session-scoped and the raw canonical key is hashed, so an
 * oversized or non-ASCII argument set can never produce a malformed Redis key:
 * <pre>
 *   oc:tool-cache:{sessionId}:{sha256(key)} -> JSON payload, TTL
 *   oc:tool-cache:idx:{sessionId}           -> SET of hashes (session purge)
 * </pre>
 *
 * <p>Every Redis failure is treated as a cache miss — never an error surfaced to
 * the turn.
 */
public class RedisToolResultCache implements ToolResultCache {

    private static final Logger log = LoggerFactory.getLogger(RedisToolResultCache.class);
    private static final String PREFIX = "oc:tool-cache:";
    /** Index keys use a separate prefix to avoid collision with data keys (P2-8). */
    private static final String IDX_PREFIX = "oc:tool-cache-idx:";

    private final StringRedisTemplate redis;
    private final ObjectMapper om;
    private final Duration ttl;
    private final int maxContentChars;
    private final AtomicBoolean degradedLogged = new AtomicBoolean();

    public RedisToolResultCache(StringRedisTemplate redis, ObjectMapper om,
                                Duration ttl, int maxContentChars) {
        this.redis = redis;
        this.om = om;
        this.ttl = ttl == null || ttl.isZero() || ttl.isNegative() ? DEFAULT_TTL : ttl;
        this.maxContentChars = maxContentChars > 0 ? maxContentChars : 20_000;
        log.info("RedisToolResultCache active (ttl={}, maxContentChars={})", this.ttl, this.maxContentChars);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Payload(boolean success, String content, String errorMessage, long cachedAtEpochMilli) {}

    @Override
    public Optional<CachedResult> get(String sessionId, String key) {
        if (key == null || key.isEmpty()) return Optional.empty();
        try {
            String json = redis.opsForValue().get(key(sessionId, key));
            if (json == null) return Optional.empty();
            Payload p = om.readValue(json, Payload.class);
            return Optional.of(new CachedResult(
                p.success(), p.content(), p.errorMessage(), p.cachedAtEpochMilli()));
        } catch (Exception e) {
            degrade("get", e);
            return Optional.empty();   // a miss, never a turn failure
        }
    }

    @Override
    public void put(String sessionId, String key, CachedResult result) {
        if (key == null || key.isEmpty() || result == null) return;
        // Mirror the in-memory contract: only successful, bounded results are cached.
        if (!result.success()) return;
        String content = result.content();
        if (content == null || content.length() > maxContentChars) return;
        try {
            String json = om.writeValueAsString(new Payload(
                true, content, result.errorMessage(), result.cachedAtEpochMilli()));
            String hash = hash(key);
            redis.opsForValue().set(PREFIX + sid(sessionId) + ":" + hash, json, ttl);
            String idx = index(sessionId);
            redis.opsForSet().add(idx, hash);
            // Index must outlive its entries so clearSession can still find them.
            redis.expire(idx, ttl.plusMinutes(5));
        } catch (Exception e) {
            degrade("put", e);
        }
    }

    @Override
    public void clearSession(String sessionId) {
        try {
            String idx = index(sessionId);
            Set<String> hashes = redis.opsForSet().members(idx);
            if (hashes != null && !hashes.isEmpty()) {
                redis.delete(hashes.stream().map(h -> PREFIX + sid(sessionId) + ":" + h).toList());
            }
            redis.delete(idx);
        } catch (Exception e) {
            degrade("clearSession", e);
        }
    }

    private String key(String sessionId, String canonicalKey) {
        return PREFIX + sid(sessionId) + ":" + hash(canonicalKey);
    }

    private static String sid(String sessionId) {
        return sessionId == null ? "" : sessionId;
    }

    private static String index(String sessionId) {
        return IDX_PREFIX + sid(sessionId);
    }

    /** SHA-256 of the canonical key: bounded length, Redis-safe charset. */
    static String hash(String canonicalKey) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(canonicalKey.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // SHA-256 is mandatory on every JVM; this branch is unreachable in practice.
            return Integer.toHexString(canonicalKey.hashCode());
        }
    }

    private void degrade(String op, Exception e) {
        if (degradedLogged.compareAndSet(false, true)) {
            log.warn("Redis tool-result cache {} failed — treating as cache miss "
                + "(no correctness impact, hit rate degrades until Redis recovers): {}", op, e.toString());
        } else {
            log.debug("Redis tool-result cache {} failed: {}", op, e.toString());
        }
    }
}
