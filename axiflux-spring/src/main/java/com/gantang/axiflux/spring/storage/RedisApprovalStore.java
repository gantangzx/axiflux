package com.gantang.axiflux.spring.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gantang.reaxon.api.agent.ApprovalDecision;
import com.gantang.reaxon.api.agent.ApprovalManager.ApprovalRequest;
import com.gantang.reaxon.api.agent.ApprovalStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.Topic;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Redis-backed {@link ApprovalStore}: pending requests and session grants
 * persist in Redis (with TTL), and decisions are fanned out over pub/sub so
 * the waiting agent turn is woken on whichever instance received the
 * approve/reject call.
 *
 * <p>Blocking {@link StringRedisTemplate} calls are bridged onto
 * boundedElastic behind the reactive contract; grant reads/writes follow the
 * same blocking style as {@code RedisSessionStore}.
 */
public class RedisApprovalStore implements ApprovalStore, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RedisApprovalStore.class);

    static final String PENDING_PREFIX = "axiflux:approval:pending:";
    static final String GRANT_PREFIX = "axiflux:approval:grant:";
    static final String DECISION_CHANNEL = "axiflux:approval:decisions";

    private final StringRedisTemplate redis;
    private final ObjectMapper om;
    /**
     * Decision sink with replay(1) semantics: a subscriber that attaches after
     * the listener container starts still receives the latest decision. This
     * closes the startup window where the pub/sub listener is active but
     * DefaultApprovalManager hasn't subscribed yet (P2-7). The unbounded
     * buffer from multicast().onBackpressureBuffer() is replaced by a
     * bounded replay of exactly one element.
     */
    private final Sinks.Many<ApprovalDecision> decisionSink =
        Sinks.many().replay().limit(1);
    private final RedisMessageListenerContainer listenerContainer;

    public RedisApprovalStore(StringRedisTemplate redis, RedisConnectionFactory connectionFactory) {
        this(redis, connectionFactory, defaultContainer(connectionFactory));
    }

    private static RedisMessageListenerContainer defaultContainer(RedisConnectionFactory cf) {
        var c = new RedisMessageListenerContainer();
        c.setConnectionFactory(cf);
        c.afterPropertiesSet();
        return c;
    }

    /**
     * Test-friendly constructor: the pub/sub listener container is injected
     * rather than built+started inline, so unit tests can substitute a
     * no-subscribe stub and still exercise the Redis key layout and sink
     * fan-out without a live broker.
     */
    RedisApprovalStore(StringRedisTemplate redis, RedisConnectionFactory connectionFactory,
                       RedisMessageListenerContainer container) {
        this.redis = redis;
        this.om = new ObjectMapper().registerModule(new JavaTimeModule());

        // Subscribe to the decision channel and bridge messages into the hot sink.
        Topic topic = new ChannelTopic(DECISION_CHANNEL);
        MessageListener listener = (Message message, byte[] pattern) -> {
            try {
                String json = new String(message.getBody(), StandardCharsets.UTF_8);
                ApprovalDecision decision = om.readValue(json, ApprovalDecision.class);
                var result = decisionSink.tryEmitNext(decision);
                if (result.isFailure()) {
                    log.warn("Approval decision emit failed ({}) for callId={} — "
                        + "pending request may hang until TTL", result, decision.callId());
                }
            } catch (Exception e) {
                log.warn("Failed to decode approval decision message: {}", e.getMessage());
            }
        };

        this.listenerContainer = container;
        this.listenerContainer.addMessageListener(listener, topic);
        this.listenerContainer.start();
        log.info("RedisApprovalStore active (decision channel: {})", DECISION_CHANNEL);
    }

    // ── Pending requests ───────────────────────────────────────────────

    @Override
    public Mono<Void> savePending(ApprovalRequest request, Duration ttl) {
        return Mono.fromCallable(() -> {
                redis.opsForValue().set(PENDING_PREFIX + request.callId(),
                    om.writeValueAsString(request), ttl);
                return null;
            })
            .subscribeOn(Schedulers.boundedElastic())
            .then();
    }

    @Override
    public Mono<ApprovalRequest> removePending(String callId) {
        return Mono.fromCallable(() -> {
                String json = redis.opsForValue().getAndDelete(PENDING_PREFIX + callId);
                return json == null ? null : om.readValue(json, ApprovalRequest.class);
            })
            .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<ApprovalRequest> getPending(String callId) {
        return Mono.fromCallable(() -> {
                String json = redis.opsForValue().get(PENDING_PREFIX + callId);
                return json == null ? null : om.readValue(json, ApprovalRequest.class);
            })
            .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<ApprovalRequest> listPending(String userId) {
        return Mono.fromCallable(this::scanPendingKeys)
            .subscribeOn(Schedulers.boundedElastic())
            .flatMapMany(Flux::fromIterable)
            .flatMap(key -> Mono.fromCallable(() -> {
                    String json = redis.opsForValue().get(key);
                    return json == null ? null : om.readValue(json, ApprovalRequest.class);
                })
                .subscribeOn(Schedulers.boundedElastic()))
            .filter(r -> r != null && (userId == null || userId.equals(r.userId())));
    }

    private List<String> scanPendingKeys() {
        List<String> keys = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions()
            .match(PENDING_PREFIX + "*").count(100).build();
        redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
            try (Cursor<byte[]> c = connection.scan(options)) {
                while (c.hasNext()) {
                    keys.add(new String(c.next(), StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                log.warn("Pending approval scan failed: {}", e.getMessage());
            }
            return null;
        });
        return keys;
    }

    // ── Decision broadcast ─────────────────────────────────────────────

    @Override
    public Mono<Void> publishDecision(ApprovalDecision decision) {
        return Mono.fromCallable(() -> {
                redis.convertAndSend(DECISION_CHANNEL, om.writeValueAsString(decision));
                return null;
            })
            .subscribeOn(Schedulers.boundedElastic())
            .then();
    }

    @Override
    public Flux<ApprovalDecision> decisions() {
        return decisionSink.asFlux();
    }

    // ── Session grants (synchronous, blocking template) ────────────────

    @Override
    public void saveGrant(String sessionId, String toolName, Duration ttl) {
        if (sessionId == null || toolName == null) return;
        redis.opsForValue().set(grantKey(sessionId, toolName), "1", ttl);
    }

    @Override
    public boolean hasGrant(String sessionId, String toolName) {
        if (sessionId == null || toolName == null) return false;
        return Boolean.TRUE.equals(redis.hasKey(grantKey(sessionId, toolName)));
    }

    @Override
    public boolean removeGrant(String sessionId, String toolName) {
        if (sessionId == null || toolName == null) return false;
        return Boolean.TRUE.equals(redis.delete(grantKey(sessionId, toolName)));
    }

    @Override
    public int removeAllGrants(String sessionId) {
        if (sessionId == null) return 0;
        List<String> keys = new ArrayList<>();
        redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
            try (Cursor<byte[]> c = connection.scan(ScanOptions.scanOptions()
                    .match(GRANT_PREFIX + sessionId + ":*").count(100).build())) {
                while (c.hasNext()) keys.add(new String(c.next(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                log.warn("Grant scan for session {} failed: {}", sessionId, e.getMessage());
            }
            return null;
        });
        if (keys.isEmpty()) return 0;
        Long deleted = redis.delete(keys);
        return deleted != null ? deleted.intValue() : 0;
    }

    private static String grantKey(String sessionId, String toolName) {
        return GRANT_PREFIX + sessionId + ":" + toolName;
    }

    /**
     * Stop the pub/sub listener container and close the decision stream on
     * context shutdown so the listener thread and Redis subscription don't leak
     * across restarts / hot reloads.
     */
    @Override
    public void destroy() {
        try {
            listenerContainer.stop();
        } catch (Exception e) {
            log.warn("Failed to stop approval decision listener container: {}", e.getMessage());
        }
        decisionSink.tryEmitComplete();
    }
}
