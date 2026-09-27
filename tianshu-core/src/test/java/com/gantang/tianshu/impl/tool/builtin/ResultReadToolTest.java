package com.gantang.tianshu.impl.tool.builtin;

import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.tool.ToolResult;
import com.gantang.tianshu.impl.tool.support.InMemoryToolResultStore;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResultReadToolTest {

    private static final int LINES = 250;

    private final InMemoryToolResultStore store = new InMemoryToolResultStore();
    private final ResultReadTool tool = new ResultReadTool(store);
    private final AgentContext s1 = AgentContext.builder().sessionId("s1").userId("u1").build();

    private String parked(String session, String origin) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= LINES; i++) {
            sb.append("L").append(String.format("%03d", i)).append(" payload data\n");
        }
        return store.store(session, sb.toString(), origin);
    }

    @Test
    void rangeMode_pagesWithLineNumbersAndContinueHint() {
        String handle = parked("s1", "file_read");

        ToolResult r = tool.execute("c1", Map.of("ref", handle, "offset", 2, "limit", 3), s1);
        assertTrue(r.success(), r.errorMessage());
        assertTrue(r.content().contains("lines 2-4 of 250"));
        assertTrue(r.content().contains("L002"));
        assertTrue(r.content().contains("L004"));
        assertFalse(r.content().contains("L005"));
        assertTrue(r.content().contains("continue with offset=5"),
            "model must be told where the next page starts");
        assertEquals("file_read", r.metadata().get("originTool"));
        assertEquals(2, r.metadata().get("returnedFrom"));
        assertEquals(4, r.metadata().get("returnedTo"));
    }

    @Test
    void keywordMode_returnsCaseInsensitiveMatchWindows() {
        String handle = parked("s1", "grep_search");

        ToolResult r = tool.execute("c1",
            Map.of("ref", handle, "keyword", "l042"), s1);
        assertTrue(r.success(), r.errorMessage());
        assertTrue(r.content().contains("keyword \"l042\""));
        assertTrue(r.content().contains("L042"), "matching line returned");
        assertTrue(r.content().contains("L041"), "context line before match returned");
    }

    @Test
    void missingRef_fails() {
        ToolResult r = tool.execute("c1", Map.of(), s1);
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("ref is required"));
    }

    @Test
    void unknownHandle_failsWithRecoveryHint() {
        ToolResult r = tool.execute("c1",
            Map.of("ref", "ref://tool-result/tr_does_not_exist"), s1);
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("not found or expired"));
        assertTrue(r.errorMessage().contains("re-run the original tool"));
    }

    @Test
    void foreignSessionRecord_isInvisible() {
        String handle = parked("s2", "file_read");
        AgentContext other = AgentContext.builder().sessionId("s1").userId("u1").build();
        ToolResult r = tool.execute("c1", Map.of("ref", handle), other);
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("not found or expired"));
    }

    @Test
    void offsetPastEnd_failsClearly() {
        String handle = parked("s1", "file_read");
        ToolResult r = tool.execute("c1",
            Map.of("ref", handle, "offset", 10_000, "limit", 10), s1);
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("past the end"));
    }

    @Test
    void noKeywordMatch_reportsEmpty() {
        String handle = parked("s1", "file_read");
        ToolResult r = tool.execute("c1",
            Map.of("ref", handle, "keyword", "definitely-not-here-zzz"), s1);
        assertTrue(r.success());
        assertTrue(r.content().contains("(no matches)"));
    }
}
