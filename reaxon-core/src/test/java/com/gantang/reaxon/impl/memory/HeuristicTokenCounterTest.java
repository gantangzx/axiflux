package com.gantang.reaxon.impl.memory;

import com.gantang.reaxon.api.session.Message;
import com.gantang.reaxon.api.tool.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HeuristicTokenCounterTest {

    private final HeuristicTokenCounter counter = HeuristicTokenCounter.INSTANCE;

    @Test
    void nullAndEmptyAreZero() {
        assertEquals(0, counter.countText(null));
        assertEquals(0, counter.countText(""));
    }

    @Test
    void cjkCountsRoughlyOneTokenPerChar() {
        // 4 ideographs → 4 tokens (no latin batch rounding)
        assertEquals(4, counter.countText("你好世界"));
    }

    @Test
    void latinCountsRoughlyFourCharsPerToken() {
        // 11 latin chars → ceil(11/4) = 3
        assertEquals(3, counter.countText("hello world"));
    }

    @Test
    void neverUnderestimatesNonEmptyText() {
        assertTrue(counter.countText("a") >= 1);
        assertTrue(counter.countText("这是一段用来测试的中文内容，包含标点符号") >= 15);
    }

    @Test
    void messageCountIncludesToolCallsAndOverhead() {
        ToolCall call = new ToolCall("c1", "calculator", Map.of("expr", "1+1"));
        Message m = Message.assistant("", List.of(call));
        int bare = counter.countText("");
        int withCall = counter.countMessage(m);
        // tool name + arguments JSON + per-message overhead must be counted
        assertTrue(withCall > bare);
        assertTrue(withCall >= counter.countText("calculator") + 4);
    }
}
