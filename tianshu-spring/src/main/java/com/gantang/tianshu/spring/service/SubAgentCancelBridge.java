package com.gantang.tianshu.spring.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.tianshu.api.agent.SubAgentEventStreamer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Cross-instance cancellation for background sub-agent tasks.
 *
 * <p>Why: a spawned task lives in the memory of the instance that started it
 * (its Reactor pipeline, child session ids and slot reservation are all local).
 * A cancel call routed by the load balancer to any other instance finds nothing
 * and answers 404 while the task keeps burning tokens — the user cannot stop a
 * runaway task except by luck.
 *
 * <p>Protocol (two channels, request/ack):
 * <ol>
 *   <li>the receiving node tries a local cancel first — single-instance
 *       deployments never touch Redis and keep their exact former behaviour;</li>
 *   <li>on a local miss it publishes a cancel request tagged with its own node
 *       id and waits briefly for an ack;</li>
 *   <li>every other node attempts an owner-scoped local cancel and, only on
 *       success, publishes an ack. The owner check travels with the request, so
 *       a cross-node cancel cannot escape its tenant.</li>
 * </ol>
 *
 * <p>A missing ack means "nobody owns this task" and the caller still gets 404 —
 * the ack wait only bounds how long we are willing to look for the owner.
 */
public class SubAgentCancelBridge implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SubAgentCancelBridge.class);

    static final String CANCEL_CHANNEL = "tianshu:subagent:cancel";
    static final String ACK_CHANNEL = "tianshu:subagent:cancel-ack";
    /** Bound on how long a cancel call waits for the owning node to answer. */
    public static final Duration DEFAULT_ACK_TIMEOUT = Duration.ofMillis(1500);

    private final StringRedisTemplate redis;
    private final SubAgentEventStreamer service;
    private final ObjectMapper om;
    /** Null in the test constructor, which skips pub/sub subscription. */
    private final RedisMessageListenerContainer listenerContainer;
    private final Duration ackTimeout;
    /** This instance's identity, so it ignores the requests it published itself. */
    private final String nodeId = UUID.randomUUID().toString();
    private final Map<String, CompletableFuture<Boolean>> pendingAcks = new ConcurrentHashMap<>();

    public SubAgentCancelBridge(StringRedisTemplate redis, RedisConnectionFactory connectionFactory,
                                SubAgentEventStreamer service, ObjectMapper om) {
        this(redis, connectionFactory, service, om, DEFAULT_ACK_TIMEOUT);
    }

    public SubAgentCancelBridge(StringRedisTemplate redis, RedisConnectionFactory connectionFactory,
                                SubAgentEventStreamer service, ObjectMapper om, Duration ackTimeout) {
        this.redis = redis;
        this.service = service;
        this.om = om;
        this.ackTimeout = ackTimeout == null || ackTimeout.isNegative() || ackTimeout.isZero()
            ? DEFAULT_ACK_TIMEOUT : ackTimeout;

        this.listenerContainer = new RedisMessageListenerContainer();
        this.listenerContainer.setConnectionFactory(connectionFactory);
        this.listenerContainer.afterPropertiesSet();
        this.listenerContainer.addMessageListener(requestListener(), new ChannelTopic(CANCEL_CHANNEL));
        this.listenerContainer.addMessageListener(ackListener(), new ChannelTopic(ACK_CHANNEL));
        this.listenerContainer.start();
        log.info("SubAgentCancelBridge active (node={}, ackTimeout={})", nodeId, this.ackTimeout);
    }

    /**
     * Test constructor: wires the protocol without subscribing to Redis, so the
     * request/ack handling can be driven directly (no broker needed).
     */
    SubAgentCancelBridge(StringRedisTemplate redis, SubAgentEventStreamer service,
                         ObjectMapper om, Duration ackTimeout) {
        this.redis = redis;
        this.service = service;
        this.om = om;
        this.ackTimeout = ackTimeout == null || ackTimeout.isNegative() || ackTimeout.isZero()
            ? DEFAULT_ACK_TIMEOUT : ackTimeout;
        this.listenerContainer = null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CancelRequest(String taskId, String userId, String from) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CancelAck(String taskId, String from) {}

    /**
     * Cancel a task wherever it lives.
     *
     * @return true when the owning instance confirmed cancellation
     */
    public boolean cancel(String taskId, String userId) {
        if (taskId == null || taskId.isBlank()) return false;
        // Fast path: we own it. No Redis round-trip, no behaviour change for
        // single-instance deployments.
        if (service.cancelTaskForUser(taskId, userId)) return true;

        CompletableFuture<Boolean> ack = new CompletableFuture<>();
        // Losing a race here would strand the first waiter, so keep the winner.
        CompletableFuture<Boolean> existing = pendingAcks.putIfAbsent(taskId, ack);
        if (existing != null) ack = existing;
        try {
            redis.convertAndSend(CANCEL_CHANNEL,
                om.writeValueAsString(new CancelRequest(taskId, userId, nodeId)));
            return Boolean.TRUE.equals(ack.get(ackTimeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (java.util.concurrent.TimeoutException te) {
            // Nobody owns it (or the owner is gone) — caller answers 404.
            return false;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("Cross-instance cancel of {} failed, falling back to local-only result: {}",
                taskId, e.toString());
            return false;
        } finally {
            pendingAcks.remove(taskId);
        }
    }

    private MessageListener requestListener() {
        return (Message message, byte[] pattern) ->
            handleCancelRequest(new String(message.getBody(), StandardCharsets.UTF_8));
    }

    /** Handle one inbound cancel request; package-private so tests can drive it. */
    void handleCancelRequest(String json) {
        try {
            CancelRequest req = om.readValue(json, CancelRequest.class);
            if (req.taskId() == null || nodeId.equals(req.from())) return;  // our own publish
            // Owner scope travels with the request: a foreign tenant's cancel
            // is rejected here exactly as it would be on the local path.
            if (!service.cancelTaskForUser(req.taskId(), req.userId())) return;
            redis.convertAndSend(ACK_CHANNEL,
                om.writeValueAsString(new CancelAck(req.taskId(), nodeId)));
            log.info("Cancelled sub-agent task {} on behalf of node {}", req.taskId(), req.from());
        } catch (Exception e) {
            log.warn("Failed to handle remote cancel request: {}", e.toString());
        }
    }

    private MessageListener ackListener() {
        return (Message message, byte[] pattern) ->
            handleAck(new String(message.getBody(), StandardCharsets.UTF_8));
    }

    /** Handle one inbound ack; package-private so tests can drive it. */
    void handleAck(String json) {
        try {
            CancelAck ack = om.readValue(json, CancelAck.class);
            CompletableFuture<Boolean> waiter = pendingAcks.get(ack.taskId());
            if (waiter != null) waiter.complete(Boolean.TRUE);
        } catch (Exception e) {
            log.warn("Failed to handle remote cancel ack: {}", e.toString());
        }
    }

    /** Visible for tests. */
    String nodeId() {
        return nodeId;
    }

    @Override
    public void destroy() {
        if (listenerContainer == null) return;
        try {
            listenerContainer.stop();
            listenerContainer.destroy();
        } catch (Exception e) {
            log.debug("SubAgentCancelBridge shutdown: {}", e.toString());
        }
    }
}
