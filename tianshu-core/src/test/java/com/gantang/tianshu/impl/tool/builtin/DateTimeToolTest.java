package com.gantang.tianshu.impl.tool.builtin;

import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DateTimeToolTest {

    private final DateTimeTool tool = new DateTimeTool();
    private final AgentContext ctx = AgentContext.builder()
        .sessionId("t").userId("u").currentQuery("").build();

    @Test
    void now_default_zone() {
        ToolResult r = tool.execute("d1", Map.of("operation", "now"), ctx);
        assertTrue(r.success(), r.errorMessage());
        assertNotNull(r.content());
        assertTrue(r.content().length() > 10);
    }

    @Test
    void now_with_format() {
        ToolResult r = tool.execute("d2",
            Map.of("operation", "now", "zone", "UTC", "format", "yyyy-MM-dd"), ctx);
        assertTrue(r.success());
        assertTrue(r.content().matches("\\d{4}-\\d{2}-\\d{2}"));
    }

    @Test
    void diff() {
        ToolResult r = tool.execute("d3",
            Map.of("operation", "diff",
                   "from", "2026-01-01T00:00:00Z",
                   "to",   "2026-01-02T01:30:00Z"), ctx);
        assertTrue(r.success());
        assertTrue(r.content().contains("1d"));
        assertTrue(r.content().contains("1h"));
    }

    @Test
    void add_hours() {
        ToolResult r = tool.execute("d4",
            Map.of("operation", "add",
                   "from", "2026-01-01T00:00:00Z",
                   "amount", 5,
                   "unit", "hours"), ctx);
        assertTrue(r.success());
        assertEquals("2026-01-01T05:00:00Z", r.content());
    }

    @Test
    void unknown_operation_fails() {
        ToolResult r = tool.execute("d5", Map.of("operation", "foo"), ctx);
        assertFalse(r.success());
    }
}
