package com.gantang.tianshu.spring.storage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.tianshu.impl.tool.support.InMemoryToolResultStore;
import com.gantang.tianshu.api.tool.StoredToolResult;
import com.gantang.tianshu.api.tool.ToolResultStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis-backed side storage for oversized tool results.
 *
 * <p>Why: the {@code ref://tool-result/<id>} handle enters the transcript, but a
 * later turn of the same session may be served by a <em>different</em> instance.
 * With the in-memory store that turn's {@code result_read} finds nothing and the
 * model is left holding a handle it cannot dereference — the truncated content is
 * effectively lost, which defeats the whole point of P1-3.
 *
 * <p>Key layout (session-scoped by construction, so a foreign session can never
 * read another's record even if it guesses the id):
 * <pre>
 *   oc:tool-result:{sessionId}:{id}   -> JSON payload, TTL
 *   oc:tool-result:idx:{sessionId}    -> SET of ids (session purge), TTL
 * </pre>
 *
 * <p>Degradation: Redis is a cache tier here, not the source of truth. Any Redis
 * failure falls back to a process-local store so the turn still works with
 * single-node semantics instead of handing the model a dangling handle.
 */
public class RedisToolResultStore implements ToolResultStore {

    private static final Logger log = LoggerFactory.getLogger(RedisToolResultStore.class);
    private static final String PREFIX = "oc:tool-result:";
    /** Index keys use a separate prefix to avoid collision with data keys (P2-8). */
    private static final String IDX_PREFIX = "oc:tool-result-idx:";
    /** Records outlive a turn but not a working day; handles are session-scoped. */
    public static final Duration DEFAULT_TTL = Duration.ofHours(6);
    /** Hard per-record ceiling; keeps one pathological result from filling Redis. */
    public static final int DEFAULT_MAX_CHARS = 2_000_000;

    private final StringRedisTemplate redis;
    private final ObjectMapper om;
    private final Duration ttl;
    private final int maxChars;
    /** Local degradation tier used only when Redis misbehaves. */
    private final InMemoryToolResultStore fallback = new InMemoryToolResultStore();
    private final AtomicBoolean degradedLogged = new AtomicBoolean();

    public RedisToolResultStore(StringRedisTemplate redis, ObjectMapper om) {
        this(redis, om, DEFAULT_TTL, DEFAULT_MAX_CHARS);
    }

    public RedisToolResultStore(StringRedisTemplate redis, ObjectMapper om, Duration ttl, int maxChars) {
        this.redis = redis;
        this.om = om;
        this.ttl = ttl == null || ttl.isZero() || ttl.isNegative() ? DEFAULT_TTL : ttl;
        this.maxChars = maxChars > 0 ? maxChars : DEFAULT_MAX_CHARS;
        log.info("RedisToolResultStore active (ttl={}, maxChars={})", this.ttl, this.maxChars);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Payload(String content, String originTool, long storedAtEpochMilli) {}

    @Override
    public String store(String sessionId, String content, String originTool) {
        String sid = sessionId == null ? "" : sessionId;
        String body = content == null ? "" : content;
        if (body.length() > maxChars) {
            body = body.substring(0, maxChars)
                + "\n[... truncated at " + maxChars + " chars by the side store ...]";
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        try {
            String json = om.writeValueAsString(
                new Payload(body, originTool == null ? "" : originTool, System.currentTimeMillis()));
            redis.opsForValue().set(key(sid, id), json, ttl);
            String idx = index(sid);
            redis.opsForSet().add(idx, id);
            redis.expire(idx, ttl);
            return HANDLE_PREFIX + id;
        } catch (Exception e) {
            degrade("store", e);
            return fallback.store(sid, content, originTool);
        }
    }

    @Override
    public Optional<StoredToolResult> fetch(String sessionId, String ref) {
        String sid = sessionId == null ? "" : sessionId;
        String id = ToolResultStore.idOf(ref);
        if (id.isEmpty()) return Optional.empty();
        try {
            String json = redis.opsForValue().get(key(sid, id));
            if (json == null) {
                // Written before Redis was reachable (or during a degraded turn).
                // The local tier is keyed by bare id, not by the handle.
                return fallback.fetch(sid, id);
            }
            Payload p = om.readValue(json, Payload.class);
            return Optional.of(new StoredToolResult(
                id, sid, p.content(), p.originTool(), Instant.ofEpochMilli(p.storedAtEpochMilli())));
        } catch (Exception e) {
            degrade("fetch", e);
            return fallback.fetch(sid, id);
        }
    }

    @Override
    public void clearSession(String sessionId) {
        String sid = sessionId == null ? "" : sessionId;
        fallback.clearSession(sid);
        try {
            String idx = index(sid);
            Set<String> ids = redis.opsForSet().members(idx);
            if (ids != null && !ids.isEmpty()) {
                redis.delete(ids.stream().map(i -> key(sid, i)).toList());
            }
            redis.delete(idx);
        } catch (Exception e) {
            degrade("clearSession", e);
        }
    }

    private static String key(String sessionId, String id) {
        return PREFIX + sessionId + ":" + id;
    }

    private static String index(String sessionId) {
        return IDX_PREFIX + sessionId;
    }

    /** Log the first degradation loudly, the rest at debug (no log flood per call). */
    private void degrade(String op, Exception e) {
        if (degradedLogged.compareAndSet(false, true)) {
            log.warn("Redis tool-result store {} failed — degrading to process-local store "
                + "(cross-instance handle retrieval is unavailable until Redis recovers): {}",
                op, e.toString());
        } else {
            log.debug("Redis tool-result store {} failed: {}", op, e.toString());
        }
    }
}
