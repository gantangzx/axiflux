package com.gantang.axiflux.spring.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.reaxon.api.agent.SubAgentEventStreamer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Cross-instance cancel protocol.
 *
 * <p>The bridge is built with its test constructor (no Redis subscription) and the
 * inbound handlers are driven directly, so the request/ack state machine is covered
 * without a broker: local fast path, remote ack, ack timeout, tenant scoping on the
 * remote side, and self-message suppression.
 *
 * <p>Publishes are asserted with captors rather than answer-callbacks so a stubbing
 * mismatch cannot turn into a silent pass.
 */
class SubAgentCancelBridgeTest {

    private final ObjectMapper om = new ObjectMapper();
    private SubAgentEventStreamer service;
    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        service = mock(SubAgentEventStreamer.class);
        redis = mock(StringRedisTemplate.class);
    }

    private SubAgentCancelBridge bridge(Duration ackTimeout) {
        return new SubAgentCancelBridge(redis, service, om, ackTimeout);
    }

    private String requestJson(String taskId, String userId, String from) {
        try {
            return om.writeValueAsString(new SubAgentCancelBridge.CancelRequest(taskId, userId, from));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String ackJson(String taskId, String from) {
        try {
            return om.writeValueAsString(new SubAgentCancelBridge.CancelAck(taskId, from));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void localOwnerCancelsWithoutTouchingRedis() {
        when(service.cancelTaskForUser("t1", "alice")).thenReturn(true);
        SubAgentCancelBridge b = bridge(Duration.ofMillis(200));

        assertTrue(b.cancel("t1", "alice"));
        verify(redis, never()).convertAndSend(anyString(), any());
    }

    @Test
    void remoteOwnerAckMakesTheCancelSucceed() {
        when(service.cancelTaskForUser("t2", "alice")).thenReturn(false);   // not ours
        SubAgentCancelBridge b = bridge(Duration.ofSeconds(3));

        // Stand-in for the owning instance answering on the ack channel.
        Thread owner = new Thread(() -> {
            try {
                Thread.sleep(60);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            b.handleAck(ackJson("t2", "other-node"));
        });
        owner.setDaemon(true);
        owner.start();

        assertTrue(b.cancel("t2", "alice"), "ack from the owning node must resolve the call");

        ArgumentCaptor<String> channel = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(redis).convertAndSend(channel.capture(), payload.capture());
        assertEquals(SubAgentCancelBridge.CANCEL_CHANNEL, channel.getValue());
        String sent = String.valueOf(payload.getValue());
        assertTrue(sent.contains("\"taskId\":\"t2\""), sent);
        assertTrue(sent.contains("\"userId\":\"alice\""), sent);
        assertTrue(sent.contains(b.nodeId()), "request must carry the origin node id");
    }

    @Test
    void missingAckStillReports404WithinTheTimeout() {
        when(service.cancelTaskForUser(anyString(), anyString())).thenReturn(false);
        SubAgentCancelBridge b = bridge(Duration.ofMillis(150));

        long start = System.nanoTime();
        assertFalse(b.cancel("ghost", "alice"), "nobody owns it -> caller answers 404");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 2000, "must not block the request thread: " + elapsedMs + "ms");
        verify(redis).convertAndSend(eq(SubAgentCancelBridge.CANCEL_CHANNEL), any());
    }

    @Test
    void remoteRequestFromAnotherTenantIsRejectedWithoutAck() {
        // Owner scope is re-checked on the node that owns the task.
        when(service.cancelTaskForUser("t3", "mallory")).thenReturn(false);
        SubAgentCancelBridge b = bridge(Duration.ofMillis(200));

        b.handleCancelRequest(requestJson("t3", "mallory", "some-other-node"));

        verify(service).cancelTaskForUser("t3", "mallory");
        verify(redis, never()).convertAndSend(anyString(), any());
    }

    @Test
    void remoteRequestForAnOwnedTaskIsAcked() {
        when(service.cancelTaskForUser("t4", "alice")).thenReturn(true);
        SubAgentCancelBridge b = bridge(Duration.ofMillis(200));

        b.handleCancelRequest(requestJson("t4", "alice", "some-other-node"));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(redis).convertAndSend(eq(SubAgentCancelBridge.ACK_CHANNEL), payload.capture());
        assertTrue(String.valueOf(payload.getValue()).contains("\"taskId\":\"t4\""));
    }

    @Test
    void ourOwnBroadcastIsIgnored() {
        SubAgentCancelBridge b = bridge(Duration.ofMillis(200));

        b.handleCancelRequest(requestJson("t5", "alice", b.nodeId()));

        verify(service, never()).cancelTaskForUser(anyString(), anyString());
        verify(redis, never()).convertAndSend(anyString(), any());
    }

    @Test
    void malformedMessagesAreSwallowed() {
        SubAgentCancelBridge b = bridge(Duration.ofMillis(200));

        assertDoesNotThrow(() -> b.handleCancelRequest("not json"));
        assertDoesNotThrow(() -> b.handleAck("{\"nope\":1}"));
        verify(service, never()).cancelTaskForUser(anyString(), anyString());
    }
}
