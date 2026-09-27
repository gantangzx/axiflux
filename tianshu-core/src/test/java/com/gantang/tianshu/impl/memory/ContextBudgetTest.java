package com.gantang.tianshu.impl.memory;

import com.gantang.tianshu.api.memory.ContextAssembler.Budget;
import com.gantang.tianshu.api.memory.TokenCounter;
import com.gantang.tianshu.api.session.Message;
import com.gantang.tianshu.api.tool.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContextBudgetTest {

    private final TokenCounter counter = HeuristicTokenCounter.INSTANCE;

    private static Budget budget(int window, int output, int tools, int reserve) {
        return new Budget(window, output, tools, reserve);
    }

    @Test
    void largeBudgetKeepsEverythingInOrder() {
        List<Message> prefix = List.of(Message.system("you are a helper"));
        List<Message> history = List.of(
            Message.user("q1"), Message.assistant("a1", List.of()),
            Message.user("q2"), Message.assistant("a2", List.of()));

        List<Message> out = ContextBudget.fit(prefix, history, null,
            budget(128_000, 4_096, 0, 2_048), counter);

        assertEquals(5, out.size());
        assertEquals("you are a helper", out.get(0).content());
        assertEquals("q1", out.get(1).content());
        assertEquals("a2", out.get(4).content());
    }

    @Test
    void tightBudgetKeepsNewestDropsOldest() {
        List<Message> history = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            history.add(Message.user("message number " + i + " with enough padding to cost several tokens each"));
        }

        List<Message> out = ContextBudget.fit(List.of(), history, null,
            budget(400, 40, 0, 40), counter);

        // Newest message survives, oldest does not.
        assertTrue(out.contains(history.get(history.size() - 1)));
        assertFalse(out.contains(history.get(0)));
        // Remaining messages stay in chronological order (subset of history).
        for (int i = 1; i < out.size(); i++) {
            int prev = history.indexOf(out.get(i - 1));
            int cur = history.indexOf(out.get(i));
            assertTrue(cur > prev, "messages must stay chronological");
        }
    }

    @Test
    void orphanToolResultsAtFrontAreDropped() {
        ToolCall call = new ToolCall("c1", "calculator", Map.of("expr", "1+1"));
        List<Message> history = List.of(
            Message.user("please compute one plus one"),
            Message.assistant("", List.of(call)),
            Message.tool("c1", "the result is two"),
            Message.user("thanks a lot for the help"),
            Message.assistant("you are most welcome", List.of()));

        // Tight window: only the last couple of messages fit.
        List<Message> out = ContextBudget.fit(List.of(), history, null,
            budget(160, 0, 0, 0), counter);

        assertFalse(out.isEmpty());
        // The retained tail must never start with an orphan TOOL result.
        assertNotEquals(Message.Role.TOOL, out.get(0).role(),
            "leading orphan TOOL result must be trimmed");

        // Every retained TOOL message has a preceding ASSISTANT carrying its callId.
        for (int i = 0; i < out.size(); i++) {
            Message m = out.get(i);
            if (m.role() == Message.Role.TOOL) {
                String callId = m.toolCallId();
                boolean hasOwner = false;
                for (int j = 0; j < i; j++) {
                    Message earlier = out.get(j);
                    if (earlier.role() == Message.Role.ASSISTANT && earlier.toolCalls() != null
                        && earlier.toolCalls().stream().anyMatch(c -> c.callId().equals(callId))) {
                        hasOwner = true;
                    }
                }
                assertTrue(hasOwner, "TOOL result " + callId + " retained without its assistant tool_call");
            }
        }
    }

    @Test
    void prefixAndSuffixAreAlwaysRetained() {
        List<Message> prefix = List.of(Message.system("system prompt that must stay"));
        List<Message> history = List.of(
            Message.user("old question"),
            Message.assistant("old answer", List.of()));
        Message suffix = Message.user("brand new question");

        List<Message> out = ContextBudget.fit(prefix, history, suffix,
            budget(80, 10, 0, 10), counter);

        assertEquals("system prompt that must stay", out.get(0).content());
        assertEquals("brand new question", out.get(out.size() - 1).content());
    }

    @Test
    void exhaustedFixedCostsStillKeepsEmergencyTail() {
        // "Fixed" costs (system + output reserve) exceed the whole window.
        List<Message> prefix = List.of(Message.system("x".repeat(500)));
        List<Message> history = List.of(
            Message.user("latest question"),
            Message.assistant("latest answer", List.of()));

        List<Message> out = ContextBudget.fit(prefix, history, null,
            budget(100, 200, 0, 0), counter);

        // Prefix retained and at least the newest answer survives via emergency tail.
        assertFalse(out.isEmpty());
        assertEquals(Message.Role.SYSTEM, out.get(0).role());
        assertTrue(out.contains(history.get(history.size() - 1)));
    }
}
