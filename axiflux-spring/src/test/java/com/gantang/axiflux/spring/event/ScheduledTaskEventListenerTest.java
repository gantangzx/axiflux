package com.gantang.axiflux.spring.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.reaxon.api.agent.Agent;
import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.agent.AgentResponse;
import com.gantang.reaxon.api.session.Session;
import com.gantang.reaxon.api.session.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScheduledTaskEventListenerTest {

    private Agent agent;
    private SessionManager sessionManager;
    private Session session;
    private ScheduledTaskEventListener listener;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        agent = mock(Agent.class);
        sessionManager = mock(SessionManager.class);
        session = mock(Session.class);

        when(sessionManager.getOrCreate(anyString(), any(), anyString(), any()))
            .thenReturn(session);

        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<Agent> agentProvider = mock();
        when(agentProvider.getIfAvailable()).thenReturn(agent);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<SessionManager> smProvider = mock();
        when(smProvider.getIfAvailable()).thenReturn(sessionManager);

        listener = new ScheduledTaskEventListener(agentProvider, smProvider, om);
    }

    private ScheduledTaskFiredEvent event(String taskId, String name, String kind,
                                          String sessionId, String userId, Object payload) {
        return new ScheduledTaskFiredEvent(this, taskId, name, userId, sessionId, kind, payload);
    }

    @Test
    void agentTurn_callsAgentWithQuery() {
        Map<String, Object> payload = Map.of(
            "query", "Check the weather",
            "metadata", Map.of("source", "test"));

        when(agent.process(any(AgentContext.class)))
            .thenReturn(Mono.just(AgentResponse.builder()
                .status(AgentResponse.Status.SUCCESS)
                .content("Sunny")
                .build()));

        listener.onScheduledTaskFired(
            event("task-1", "Weather", "agentTurn", "sess-1", "user-1", payload));

        verify(agent, timeout(2000)).process(argThat(ctx ->
            "sess-1".equals(ctx.sessionId()) &&
            "user-1".equals(ctx.userId()) &&
            Boolean.TRUE.equals(ctx.metadata().get(com.gantang.reaxon.api.auth.CallerIdentity.META_HEADLESS))));
        verify(session, timeout(2000)).addUserMessage(eq("Check the weather"), any());
    }

    @Test
    void agentTurn_isolated_runsInEphemeralRestrictedSession() {
        Map<String, Object> payload = Map.of(
            "query", "Run a check",
            "sessionTarget", "isolated",
            "originSessionId", "sess-origin");
        when(agent.process(any(AgentContext.class)))
            .thenReturn(Mono.just(AgentResponse.builder()
                .status(AgentResponse.Status.SUCCESS)
                .content("done")
                .build()));

        listener.onScheduledTaskFired(
            event("task-iso", "BgCheck", "agentTurn", "sess-origin", "user-1", payload));

        // Runs in a fresh scheduled-iso-* session, marked headless + restricted.
        verify(agent, timeout(2000)).process(argThat(ctx ->
            ctx.sessionId().startsWith("scheduled-iso-task-iso") &&
            Boolean.TRUE.equals(ctx.metadata().get(com.gantang.reaxon.api.auth.CallerIdentity.META_HEADLESS)) &&
            Boolean.TRUE.equals(ctx.metadata().get(com.gantang.axiflux.spring.tool.ScheduleTaskTool.META_RESTRICTED))));
        // Result is announced back to the origin session (getOrCreate for iso run + get(origin)).
        verify(sessionManager, timeout(2000)).get(eq("sess-origin"));
    }

    @Test
    void agentTurn_nullPayload_skipsGracefully() {
        listener.onScheduledTaskFired(
            event("task-2", "Empty", "agentTurn", "sess-2", "user-1", null));
        verify(agent, never()).process(any());
    }

    @Test
    void agentTurn_noQuery_skipsGracefully() {
        listener.onScheduledTaskFired(
            event("task-3", "NoQuery", "agentTurn", "sess-3", "user-1",
                Map.of("other", "value")));
        verify(agent, never()).process(any());
    }

    @Test
    void systemEvent_postsVisibleReminder() {
        listener.onScheduledTaskFired(
            event("task-4", "Reminder", "systemEvent", "sess-4", "user-1",
                Map.of("text", "Meeting in 5 minutes")));

        // Direct reminder: posted verbatim as a visible assistant message (no model call).
        verify(session, timeout(2000)).addAssistantMessage(contains("Meeting in 5 minutes"), any(), any());
        verify(agent, never()).process(any());
    }

    @Test
    void systemEvent_jsonStringPayload_parsedCorrectly() throws Exception {
        String json = om.writeValueAsString(Map.of("text", "JSON notification"));
        listener.onScheduledTaskFired(
            event("task-5", "JSONReminder", "systemEvent", "sess-5", "user-1", json));

        verify(session, timeout(2000)).addAssistantMessage(contains("JSON notification"), any(), any());
    }
}
