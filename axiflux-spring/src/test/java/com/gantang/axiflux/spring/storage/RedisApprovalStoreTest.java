package com.gantang.axiflux.spring.storage;

import com.gantang.reaxon.api.agent.ApprovalDecision;
import com.gantang.reaxon.api.agent.ApprovalManager.ApprovalRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Behavioural coverage for {@link RedisApprovalStore}: pending-request
 * round-trips (JSON + TTL), the cross-instance decision broadcast, and the
 * synchronous session-grant surface. The pub/sub listener container is mocked
 * away — what matters here is the Redis key layout and the sink fan-out, not
 * Spring's listener plumbing. No Docker, so the template is a Mockito stub
 * capturing what would be sent.
 */
class RedisApprovalStoreTest {

    private StringRedisTemplate redis;
    private RedisConnectionFactory cf;
    private org.springframework.data.redis.listener.RedisMessageListenerContainer container;
    private RedisApprovalStore store;

    private static ApprovalRequest req(String callId, String userId) {
        return new ApprovalRequest(callId, "sess-" + callId, userId,
            "file_write", "Write a file", "path=/tmp/x", Instant.now(),
            Instant.now().plusSeconds(300));
    }

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        cf = mock(RedisConnectionFactory.class);
        // Inject a no-subscribe listener container so the pub/sub plumbing (a
        // live-broker concern) stays out of these Redis-key/sink unit tests.
        container = mock(org.springframework.data.redis.listener.RedisMessageListenerContainer.class);
        store = new RedisApprovalStore(redis, cf, container);
    }

    // ===== pending round-trip =====

    @Test
    void savePendingSerializesWithTtl() {
        var value = redis.opsForValue();
        StepVerifier.create(store.savePending(req("c1", "alice"), Duration.ofMinutes(5)))
            .verifyComplete();
        verify(value).set(eq("axiflux:approval:pending:c1"), anyString(), eq(Duration.ofMinutes(5)));
    }

    @Test
    void getPendingDecodes() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .writeValueAsString(req("c1", "alice"));
        when(redis.opsForValue().get("axiflux:approval:pending:c1")).thenReturn(json);

        StepVerifier.create(store.getPending("c1"))
            .assertNext(r -> {
                assertEquals("c1", r.callId());
                assertEquals("alice", r.userId());
                assertEquals("file_write", r.toolName());
            })
            .verifyComplete();
    }

    @Test
    void getPendingAbsentIsEmpty() {
        when(redis.opsForValue().get("axiflux:approval:pending:nope")).thenReturn(null);
        StepVerifier.create(store.getPending("nope")).verifyComplete();
    }

    @Test
    void removePendingReturnsRemoved() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .writeValueAsString(req("c1", "alice"));
        when(redis.opsForValue().getAndDelete("axiflux:approval:pending:c1")).thenReturn(json);

        StepVerifier.create(store.removePending("c1"))
            .assertNext(r -> assertEquals("c1", r.callId()))
            .verifyComplete();
    }

    // ===== decision broadcast =====

    @Test
    void publishDecisionSendsToChannel() {
        var decision = mock(ApprovalDecision.class);
        StepVerifier.create(store.publishDecision(decision)).verifyComplete();
        verify(redis).convertAndSend(eq("axiflux:approval:decisions"), anyString());
    }

    // ===== session grants (synchronous) =====

    @Test
    void grantRoundTrip() {
        var value = redis.opsForValue();
        store.saveGrant("s1", "file_write", Duration.ofHours(1));
        verify(value).set("axiflux:approval:grant:s1:file_write", "1", Duration.ofHours(1));
    }

    @Test
    void hasGrantTrue() {
        when(redis.hasKey("axiflux:approval:grant:s1:file_write")).thenReturn(true);
        assertTrue(store.hasGrant("s1", "file_write"));
    }

    @Test
    void hasGrantNullArgsIsFalse() {
        assertFalse(store.hasGrant(null, "file_write"));
        assertFalse(store.hasGrant("s1", null));
    }

    @Test
    void removeGrantDeletes() {
        when(redis.delete("axiflux:approval:grant:s1:file_write")).thenReturn(true);
        assertTrue(store.removeGrant("s1", "file_write"));
        assertFalse(store.removeGrant(null, "file_write"));
    }

    // ===== lifecycle =====

    @Test
    void destroyCompletesDecisionStream() {
        store.destroy();
        // after destroy the flux must complete rather than hang
        StepVerifier.create(store.decisions()).verifyComplete();
    }

    // ===== P2-7: replay(1) sink semantics =====

    @Test
    void decisionsFlux_replaysLatestDecisionToLateSubscriber() throws Exception {
        // Simulate a decision arriving via the pub/sub listener BEFORE
        // DefaultApprovalManager subscribes to decisions().
        var decision = new com.gantang.reaxon.api.agent.ApprovalDecision(
            "call-1", true, "admin", "looks fine");

        // Emit into the sink directly (as the listener would).
        var sinkField = RedisApprovalStore.class.getDeclaredField("decisionSink");
        sinkField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var sink = (reactor.core.publisher.Sinks.Many<com.gantang.reaxon.api.agent.ApprovalDecision>) sinkField.get(store);
        sink.tryEmitNext(decision);

        // A late subscriber should still receive the last decision (replay semantics).
        StepVerifier.create(store.decisions().take(1))
            .assertNext(d -> assertEquals("call-1", d.callId()))
            .verifyComplete();
    }
}
