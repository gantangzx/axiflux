package com.gantang.tianshu.impl.session;

import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.session.Session;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A conversation should read as the question it opened with, not as a UUID.
 */
class SessionTitleHookTest {

    private static final int LIMIT = 20;

    private final SessionTitleHook hook = new SessionTitleHook(LIMIT);

    private static Session session() {
        return session(Map.of());
    }

    private static Session session(Map<String, Object> metadata) {
        return new TransientSessionManager()
            .getOrCreate("s-1", "console-user", "default-agent", metadata);
    }

    private static AgentContext context() {
        return AgentContext.builder()
            .sessionId("s-1").userId("console-user").currentQuery("hi").build();
    }

    @Test
    void firstQuestionBecomesTheSessionName() {
        Session s = session();
        s.addUserMessage("北京今天天气怎么样", Map.of());

        hook.onTurnStart(context(), s);

        assertEquals("北京今天天气怎么样", s.metadata().get("title"));
    }

    @Test
    void laterTurnsDoNotRelabelTheConversation() {
        // By the second turn the name is already decided; deriving again from the
        // newest question would rename the session on every message.
        Session s = session();
        s.addUserMessage("第一个问题", Map.of());
        s.addAssistantMessage("答", List.of());

        hook.onTurnStart(context(), s);

        assertNull(s.metadata().get("title"));
    }

    @Test
    void aNameSetEarlierIsKept() {
        Session s = session(Map.of("title", "用户手动命名"));
        s.addUserMessage("第一个问题", Map.of());

        hook.onTurnStart(context(), s);

        assertEquals("用户手动命名", s.metadata().get("title"));
    }

    @Test
    void aBlankNameDoesNotBlockNaming() {
        Session s = session(new HashMap<>(Map.of("title", "   ")));
        s.addUserMessage("第一个问题", Map.of());

        hook.onTurnStart(context(), s);

        assertEquals("第一个问题", s.metadata().get("title"));
    }

    @Test
    void subAgentChildSessionsAreNotNamed() {
        // Child runs are transient and never user-facing; naming them is noise.
        Session s = session(new HashMap<>(Map.of(
            "kind", RoutingSessionManager.KIND_SUBAGENT, "parentSessionId", "parent")));
        s.addUserMessage("子代理的委派任务", Map.of());

        hook.onTurnStart(context(), s);

        assertNull(s.metadata().get("title"));
    }

    @Test
    void blankFirstMessageKeepsTheIdFallback() {
        Session s = session();
        s.addUserMessage("   \n\t  ", Map.of());

        hook.onTurnStart(context(), s);

        assertNull(s.metadata().get("title"));
    }

    @Test
    void aSystemMessageFirstIsNotMistakenForAQuestion() {
        Session s = session();
        s.addSystemMessage("internal aggregation turn");

        hook.onTurnStart(context(), s);

        assertNull(s.metadata().get("title"));
    }

    @Test
    void longQuestionsAreTruncatedWithAnEllipsis() {
        Session s = session();
        s.addUserMessage("一二三四五六七八九十一二三四五六七八九十一二三", Map.of());

        hook.onTurnStart(context(), s);

        assertEquals("一二三四五六七八九十一二三四五六七八九十…", s.metadata().get("title"));
    }

    @Test
    void multiLineQuestionsCollapseToOneLine() {
        // Pasted text and code blocks must not put newlines in a sidebar entry.
        assertEquals("第一行 第二行 第三行",
            SessionTitleHook.deriveTitle("第一行\n\n  第二行\t第三行  ", 60));
    }

    @Test
    void truncationNeverSplitsASurrogatePair() {
        assertEquals("😀😀…", SessionTitleHook.deriveTitle("😀😀😀😀", 2));
    }

    @Test
    void aBrokenSessionCannotFailTheTurn() {
        Session s = mock(Session.class);
        when(s.metadata()).thenReturn(new HashMap<>());
        when(s.getHistory(2)).thenThrow(new IllegalStateException("store down"));

        assertDoesNotThrow(() -> hook.onTurnStart(context(), s));
    }
}
