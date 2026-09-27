package com.gantang.tianshu.impl.memory;

import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.memory.ContextAssembler.Budget;
import com.gantang.tianshu.api.session.Message;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Budgeted assembly path: system prompt always retained, oldest history
 * trimmed to fit the token window, current message appended last.
 */
class DefaultContextAssemblerBudgetTest {

    private final DefaultContextAssembler assembler = new DefaultContextAssembler(null, 50, 5);
    private final HeuristicTokenCounter counter = HeuristicTokenCounter.INSTANCE;

    private AgentContext ctx() {
        return AgentContext.builder()
            .sessionId("s1").userId("u1")
            .currentQuery("latest question")
            .systemPrompt("you are a careful engineering assistant")
            .build();
    }

    @Test
    void trimsHistoryButKeepsSystemPromptAndNewestMessages() {
        List<Message> history = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            history.add(Message.user("history message number " + i + " with padding text to cost tokens"));
        }

        List<Message> out = assembler.assembleBudgeted(
                ctx(), history, null, new Budget(300, 40, 0, 40), counter)
            .block();

        assertNotNull(out);
        assertFalse(out.isEmpty());
        assertEquals(Message.Role.SYSTEM, out.get(0).role(), "system prompt must lead");
        assertTrue(out.contains(history.get(history.size() - 1)), "newest history must survive");
        assertFalse(out.contains(history.get(0)), "oldest history must be trimmed");
    }

    @Test
    void currentMessageIsAppendedAsSuffix() {
        List<Message> history = List.of(
            Message.user("previous question"),
            Message.assistant("previous answer", List.of()));
        Message current = Message.user("brand new question");

        List<Message> out = assembler.assembleBudgeted(
                ctx(), history, current, new Budget(128_000, 4_096, 0, 2_048), counter)
            .block();

        assertNotNull(out);
        assertSame(current, out.get(out.size() - 1));
        assertEquals(Message.Role.SYSTEM, out.get(0).role());
    }

    @Test
    void largeBudgetKeepsEntireHistory() {
        List<Message> history = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            history.add(Message.user("short msg " + i));
            history.add(Message.assistant("reply " + i, List.of()));
        }

        List<Message> out = assembler.assembleBudgeted(
                ctx(), history, null, new Budget(128_000, 4_096, 0, 2_048), counter)
            .block();

        assertNotNull(out);
        // system prompt + all 40 history messages
        assertEquals(41, out.size());
    }
}
