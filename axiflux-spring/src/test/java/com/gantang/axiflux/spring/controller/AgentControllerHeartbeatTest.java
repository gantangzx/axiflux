package com.gantang.axiflux.spring.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.reaxon.api.agent.Agent;
import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.agent.AgentEvent;
import com.gantang.reaxon.api.agent.AgentResponse;
import com.gantang.reaxon.api.agent.AgentStatus;
import com.gantang.reaxon.api.session.SessionManager;
import com.gantang.axiflux.spring.auth.CallerGuard;
import com.gantang.axiflux.spring.config.props.WebProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that the SSE stream emits keep-alive comment frames while a long-running
 * agent run produces no events (e.g. a sub-agent tool call), so reverse proxies don't
 * cut the idle connection.
 */
class AgentControllerHeartbeatTest {

    /** Fake agent: emits one text token then keeps the stream open (never completes),
     *  simulating a long idle tool run. */
    private static Agent idleAgent() {
        return new Agent() {
            @Override public String getAgentId() { return "test"; }
            @Override public Mono<AgentResponse> process(AgentContext context) { return Mono.never(); }
            @Override public Flux<AgentEvent> processStream(AgentContext context) {
                return Flux.concat(
                    Flux.just(AgentEvent.textToken("hi")),
                    Flux.<AgentEvent>never());
            }
            @Override public void interrupt(String sessionId) { }
            @Override public AgentStatus getStatus(String sessionId) { return null; }
        };
    }

    private AgentController controllerWithHeartbeat(int seconds) {
        WebProperties props = new WebProperties();
        props.setSseHeartbeatSeconds(seconds);
        // A real SessionManager stub is required: the controller checks session
        // ownership before streaming, and a null one would NPE rather than allow.
        SessionManager sessionManager = mock(SessionManager.class);
        when(sessionManager.get(anyString())).thenReturn(Optional.empty());
        return new AgentController(idleAgent(), sessionManager, null, new ObjectMapper(), props, null,
            new CallerGuard(sessionManager), null);
    }

    private AgentController.ChatRequest request() {
        return new AgentController.ChatRequest("s1", "u1", "hello", null, null, null, null);
    }

    @Test
    void heartbeatFramesAreSseComments() {
        AgentController controller = controllerWithHeartbeat(1);

        long commentCount = controller.chatStream(request(), null, null)
            .filter(sse -> sse.comment() != null)
            .take(Duration.ofMillis(2500))
            .count()
            .block(Duration.ofSeconds(5));

        // At least one heartbeat within the 2.5s window (cadence is 1s).
        assertNotNull(commentCount);
        assertTrue(commentCount >= 1, "expected keep-alive comment frames, got " + commentCount);
    }

    @Test
    void blankContentRejectedAsBadRequest() {
        AgentController controller = controllerWithHeartbeat(20);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> controller.chatStream(
                new AgentController.ChatRequest("s1", "u1", "   ", null, null, null, null), null, null));
    }

    @Test
    void heartbeatDisabledEmitsNoComments() {
        AgentController controller = controllerWithHeartbeat(0);

        long commentCount = controller.chatStream(request(), null, null)
            .filter(sse -> sse.comment() != null)
            .take(Duration.ofMillis(1200))
            .count()
            .block(Duration.ofSeconds(4));

        assertNotNull(commentCount);
        assertEquals(0, commentCount, "no heartbeat frames expected when disabled");
    }
}
