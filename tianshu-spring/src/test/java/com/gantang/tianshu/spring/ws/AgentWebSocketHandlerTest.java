package com.gantang.tianshu.spring.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.tianshu.api.agent.*;
import com.gantang.tianshu.api.session.Session;
import com.gantang.tianshu.api.session.SessionManager;
import com.gantang.tianshu.spring.auth.AuthWebFilter;
import com.gantang.tianshu.spring.auth.CallerGuard;
import com.gantang.tianshu.spring.config.props.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit test for {@link AgentWebSocketHandler} frame protocol.
 *
 * <p>Drives a mock {@link WebSocketSession} from a background thread and captures
 * outbound frames from the send() Publisher. The critical fix vs a naive mock is
 * that {@code session.textMessage(String)} must return a real message (it returns
 * null by default with Mockito, which NPEs inside the flatMap mapper).
 */
class AgentWebSocketHandlerTest {

    private Agent agent;
    private SessionManager sessionManager;
    private AgentWebSocketHandler handler;
    private final ObjectMapper om = new ObjectMapper();

    private Sinks.Many<String> inboundSink;
    private List<String> outbound;

    @BeforeEach
    void setUp() {
        agent = mock(Agent.class);
        sessionManager = mock(SessionManager.class);
        // Unknown session is the default: chat creates sessions on demand, so the
        // ownership check only has something to compare against once one exists.
        when(sessionManager.get(anyString())).thenReturn(Optional.empty());

        AgentProperties props = new AgentProperties();

        handler = new AgentWebSocketHandler();
        handler.setAgent(agent);
        handler.setSessionManager(sessionManager);
        handler.setObjectMapper(om);
        handler.setAgentProperties(props);
        handler.setCallerGuard(new CallerGuard(sessionManager));

        inboundSink = Sinks.many().multicast().directBestEffort();
        outbound = new CopyOnWriteArrayList<>();
    }

    /** Register an existing session with the given owner. */
    private void existingSession(String sessionId, String ownerUserId) {
        Session session = mock(Session.class);
        when(session.sessionId()).thenReturn(sessionId);
        when(session.userId()).thenReturn(ownerUserId);
        when(sessionManager.get(sessionId)).thenReturn(Optional.of(session));
    }

    private WebSocketSession mockSession(String id) {
        return mockSession(id, null);
    }

    /**
     * @param authUser identity as {@code AuthWebFilter} would have injected it into the
     *                 upgrade request, or null for an unauthenticated handshake
     */
    private WebSocketSession mockSession(String id, String authUser) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);

        if (authUser != null) {
            HttpHeaders headers = new HttpHeaders();
            headers.set(AuthWebFilter.H_USER, authUser);
            HandshakeInfo info = mock(HandshakeInfo.class);
            when(info.getHeaders()).thenReturn(headers);
            when(session.getHandshakeInfo()).thenReturn(info);
        }

        when(session.receive()).thenReturn(
            inboundSink.asFlux().map(text -> {
                WebSocketMessage msg = mock(WebSocketMessage.class);
                when(msg.getPayloadAsText()).thenReturn(text);
                return msg;
            }));

        // Outbound capture
        when(session.send(any())).thenAnswer((Answer<Mono<Void>>) inv -> {
            org.reactivestreams.Publisher<WebSocketMessage> pub = inv.getArgument(0);
            Flux.from(pub)
                .doOnNext(msg -> outbound.add(msg.getPayloadAsText()))
                .onErrorContinue((e, o) -> {})
                .subscribe();
            return Mono.empty();
        });

        // CRITICAL: textMessage factory must return a message carrying the payload
        when(session.textMessage(anyString())).thenAnswer((Answer<WebSocketMessage>) inv -> {
            String text = inv.getArgument(0);
            WebSocketMessage msg = mock(WebSocketMessage.class);
            when(msg.getPayloadAsText()).thenReturn(text);
            return msg;
        });

        return session;
    }

    private void drive(String... frames) {
        new Thread(() -> {
            try {
                Thread.sleep(200);
                for (String frame : frames) {
                    inboundSink.tryEmitNext(frame);
                    Thread.sleep(150);
                }
                Thread.sleep(400);
                inboundSink.tryEmitComplete();
            } catch (InterruptedException ignored) {}
        }, "ws-driver").start();
    }

    @Test
    void ping_respondsWithPong() throws Exception {
        WebSocketSession session = mockSession("ws-ping");
        drive("{\"type\":\"ping\"}");

        handler.handle(session).block(java.time.Duration.ofSeconds(5));

        assertTrue(outbound.stream().anyMatch(f -> f.contains("\"pong\"")),
            "Expected pong frame, got: " + outbound);
    }

    @Test
    void invalidJson_sendsErrorFrame() throws Exception {
        WebSocketSession session = mockSession("ws-badjson");
        drive("not-json{");

        handler.handle(session).block(java.time.Duration.ofSeconds(5));

        assertTrue(outbound.stream().anyMatch(f ->
                f.contains("\"error\"") && f.contains("Invalid JSON")),
            "Expected error frame, got: " + outbound);
    }

    @Test
    void chat_invokesAgentAndStreamsDone() throws Exception {
        WebSocketSession session = mockSession("ws-chat");

        when(agent.processStream(any(AgentContext.class))).thenReturn(Flux.just(
            AgentEvent.textToken("Hello"),
            AgentEvent.done(AgentResponse.builder()
                .status(AgentResponse.Status.SUCCESS)
                .content("Hello")
                .build(), "{}")
        ));

        drive("{\"type\":\"chat\",\"sessionId\":\"sess-1\",\"userId\":\"u1\",\"content\":\"hi\"}");

        handler.handle(session).block(java.time.Duration.ofSeconds(5));

        verify(agent).processStream(argThat(ctx ->
            "sess-1".equals(ctx.sessionId()) && "hi".equals(ctx.currentQuery())));

        assertTrue(outbound.stream().anyMatch(f -> f.contains("text_token")),
            "Expected text_token frame, got: " + outbound);
        assertTrue(outbound.stream().anyMatch(f -> f.contains("\"done\"")),
            "Expected done frame, got: " + outbound);
    }

    @Test
    void chat_blankContent_sendsError() throws Exception {
        WebSocketSession session = mockSession("ws-blank");
        drive("{\"type\":\"chat\",\"content\":\"\"}");

        handler.handle(session).block(java.time.Duration.ofSeconds(5));

        verify(agent, never()).processStream(any());
        assertTrue(outbound.stream().anyMatch(f -> f.contains("content is required")),
            "Expected 'content is required', got: " + outbound);
    }

    @Test
    void interrupt_callsAgentInterrupt() throws Exception {
        // AuthWebFilter always injects an identity on /tianshu/ws, so the owner
        // interrupting their own session arrives with H_USER set to that owner.
        WebSocketSession session = mockSession("ws-intr", "u1");
        existingSession("sess-intr", "u1");
        drive("{\"type\":\"interrupt\",\"sessionId\":\"sess-intr\"}");

        handler.handle(session).block(java.time.Duration.ofSeconds(5));

        verify(agent, timeout(2000)).interrupt("sess-intr");
    }

    @Test
    void interrupt_unauthenticatedHandshake_isRejected() throws Exception {
        // A handshake with no injected identity can only mean the request bypassed
        // AuthWebFilter; ownership is unprovable, so it must be refused.
        WebSocketSession session = mockSession("ws-intr-anon");
        existingSession("sess-anon", "u1");
        drive("{\"type\":\"interrupt\",\"sessionId\":\"sess-anon\"}");

        handler.handle(session).block(java.time.Duration.ofSeconds(5));

        verify(agent, never()).interrupt(anyString());
        assertTrue(outbound.stream().anyMatch(f -> f.contains("session not found")),
            "Expected 'session not found', got: " + outbound);
    }

    @Test
    void interrupt_unknownSession_isRejected() throws Exception {
        WebSocketSession session = mockSession("ws-intr-unknown");
        drive("{\"type\":\"interrupt\",\"sessionId\":\"nope\"}");

        handler.handle(session).block(java.time.Duration.ofSeconds(5));

        verify(agent, never()).interrupt(anyString());
        assertTrue(outbound.stream().anyMatch(f -> f.contains("session not found")),
            "Expected 'session not found', got: " + outbound);
    }

    @Test
    void chat_againstAnotherUsersSession_isRejected() throws Exception {
        // The frame names a session owned by someone else. Without this check a
        // verified token could stream into any session id it could guess, so the
        // reply must be indistinguishable from a nonexistent session.
        WebSocketSession session = mockSession("ws-idor", "attacker");
        existingSession("victim-session", "victim");
        drive("{\"type\":\"chat\",\"sessionId\":\"victim-session\",\"content\":\"hi\"}");

        handler.handle(session).block(java.time.Duration.ofSeconds(5));

        verify(agent, never()).processStream(any());
        assertTrue(outbound.stream().anyMatch(f -> f.contains("session not found")),
            "Expected 'session not found', got: " + outbound);
    }

    @Test
    void interrupt_againstAnotherUsersSession_isRejected() throws Exception {
        WebSocketSession session = mockSession("ws-idor-intr", "attacker");
        existingSession("victim-session", "victim");
        drive("{\"type\":\"interrupt\",\"sessionId\":\"victim-session\"}");

        handler.handle(session).block(java.time.Duration.ofSeconds(5));

        verify(agent, never()).interrupt(anyString());
        assertTrue(outbound.stream().anyMatch(f -> f.contains("session not found")),
            "Expected 'session not found', got: " + outbound);
    }

    @Test
    void subProtocols_isTianshu() {
        assertEquals(List.of("tianshu"), handler.getSubProtocols());
    }
}
