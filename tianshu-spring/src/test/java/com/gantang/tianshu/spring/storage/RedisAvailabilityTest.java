package com.gantang.tianshu.spring.storage;

import io.lettuce.core.resource.ClientResources;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The probe must never claim Redis is usable when it is not: everything downstream
 * (tool-result handles, cancel routing) picks its backend from this answer, and a
 * false positive means every request degrades at runtime instead of at wiring time.
 *
 * <p>Runs without a broker by pointing at a port nothing listens on.
 */
class RedisAvailabilityTest {

    /** A real factory aimed at a dead port — no mocks, so laziness cannot hide. */
    private static LettuceConnectionFactory deadFactory(int port) {
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
            .clientResources(ClientResources.builder().build())
            .commandTimeout(Duration.ofMillis(400))
            .build();
        LettuceConnectionFactory f =
            new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", port), client);
        f.setEagerInitialization(false);
        f.afterPropertiesSet();
        return f;
    }

    @Test
    void unreachableRedisReportsUnavailable() {
        LettuceConnectionFactory f = deadFactory(6399);   // nothing listens here
        try {
            StringRedisTemplate template = new StringRedisTemplate(f);
            template.afterPropertiesSet();

            long start = System.nanoTime();
            assertFalse(RedisAvailability.reachable(template),
                "probe must not report a dead broker as reachable");
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertTrue(ms < 15_000, "probe must fail fast, took " + ms + "ms");
        } finally {
            f.destroy();
        }
    }

    @Test
    void nullTemplateOrFactoryReportsUnavailable() {
        assertFalse(RedisAvailability.reachable(null));
        assertFalse(RedisAvailability.reachable(new StringRedisTemplate()));
    }
}
