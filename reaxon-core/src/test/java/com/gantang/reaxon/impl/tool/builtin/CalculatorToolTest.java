package com.gantang.reaxon.impl.tool.builtin;

import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CalculatorToolTest {

    private final CalculatorTool tool = new CalculatorTool();
    private final AgentContext ctx = AgentContext.builder()
        .sessionId("t").userId("u").currentQuery("").build();

    @Test
    void simple_arithmetic() {
        ToolResult r = tool.execute("c1", Map.of("expression", "1 + 2 * (3 + 4)"), ctx);
        assertTrue(r.success(), r.errorMessage());
        assertEquals("15", r.content());
    }

    @Test
    void math_functions() {
        ToolResult r = tool.execute("c2", Map.of("expression", "sqrt(16) + pow(2, 8)"), ctx);
        assertTrue(r.success(), r.errorMessage());
        // 4 + 256
        assertTrue(r.content().startsWith("260"), "expected 260, got " + r.content());
    }

    @Test
    void rejects_unsafe_expression() {
        ToolResult r = tool.execute("c3", Map.of("expression", "System.exit(1)"), ctx);
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("Unsafe"));
    }

    @Test
    void rejects_empty() {
        ToolResult r = tool.execute("c4", Map.of("expression", ""), ctx);
        assertFalse(r.success());
    }
}
