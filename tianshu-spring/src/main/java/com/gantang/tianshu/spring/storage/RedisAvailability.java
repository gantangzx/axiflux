package com.gantang.tianshu.spring.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * One-shot reachability probe used to decide whether the distributed backends
 * (tool-result side store, tool result cache, sub-agent cancel bridge) should be
 * wired at all.
 *
 * <p>Why this exists: {@code spring-boot-starter-data-redis} is a hard dependency,
 * so a {@code StringRedisTemplate} bean is present even in a single-node dev run
 * with no broker anywhere. Treating "bean exists" as "Redis available" would make
 * every default run log degradation warnings and pay a failed round-trip per tool
 * call. A single PING at startup keeps the wiring honest.
 *
 * <p>Trade-off accepted: a broker that appears <em>after</em> startup is not picked
 * up until the next restart. That is the right bias here — the alternative
 * (optimistically assuming Redis) silently degrades every request instead.
 */
public final class RedisAvailability {

    private static final Logger log = LoggerFactory.getLogger(RedisAvailability.class);

    private RedisAvailability() {}

    /** @return true when a PING succeeded, false for any absence or failure */
    public static boolean reachable(StringRedisTemplate template) {
        if (template == null || template.getConnectionFactory() == null) return false;
        String where = describe(template);
        try (RedisConnection conn = template.getConnectionFactory().getConnection()) {
            String pong = conn.ping();
            log.info("Redis reachable at {} (ping={}): tool-result handles, tool result cache and "
                + "sub-agent cancel are shared across instances", where, pong);
            return true;
        } catch (Exception e) {
            log.info("Redis not reachable at {} ({}), using process-local state: tool-result handles, "
                + "tool result cache and sub-agent cancel stay single-instance",
                where, e.getClass().getSimpleName());
            return false;
        }
    }

    /** Best-effort "host:port" for the log line; never throws. */
    private static String describe(StringRedisTemplate template) {
        try {
            Object cf = template.getConnectionFactory();
            if (cf instanceof org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory l) {
                return l.getHostName() + ":" + l.getPort();
            }
            return cf == null ? "unknown" : cf.getClass().getSimpleName();
        } catch (Exception ignored) {
            return "unknown";
        }
    }
}
