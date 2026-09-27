package com.gantang.tianshu.impl.tool.builtin;

import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.session.Session;
import com.gantang.tianshu.api.tool.ToolResult;
import com.gantang.tianshu.impl.session.InMemorySessionManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TodoWriteToolTest {

    private AgentContext ctx() {
        return AgentContext.builder().sessionId("s1").userId("u1").currentQuery("plan").build();
    }

    private Map<String, Object> todo(String content, String status) {
        return Map.of("content", content, "status", status);
    }

    @Test
    void writesPlanToSessionMetadataAndRendersChecklist() {
        InMemorySessionManager mgr = new InMemorySessionManager();
        Session session = mgr.getOrCreate("s1", "u1", "default", Map.of());
        TodoWriteTool tool = new TodoWriteTool(mgr);

        ToolResult r = tool.execute("c1", Map.of("todos", List.of(
            todo("读代码", "completed"),
            todo("改代码", "in_progress"),
            todo("跑测试", "pending"))), ctx());

        assertTrue(r.success());
        String body = r.content();
        assertTrue(body.contains("1/3 完成"), body);
        assertTrue(body.contains("[x] 读代码"));
        assertTrue(body.contains("[~] 改代码"));
        assertTrue(body.contains("[ ] 跑测试"));

        Object stored = session.metadata().get(TodoWriteTool.META_TODO_KEY);
        assertInstanceOf(List.class, stored);
        assertEquals(3, ((List<?>) stored).size());
    }

    @Test
    void emptyOrMissingTodosFails() {
        InMemorySessionManager mgr = new InMemorySessionManager();
        mgr.getOrCreate("s1", "u1", "default", Map.of());
        TodoWriteTool tool = new TodoWriteTool(mgr);

        assertFalse(tool.execute("c1", Map.of(), ctx()).success());
        assertFalse(tool.execute("c1", Map.of("todos", List.of()), ctx()).success());
    }

    @Test
    void invalidStatusFails() {
        InMemorySessionManager mgr = new InMemorySessionManager();
        mgr.getOrCreate("s1", "u1", "default", Map.of());
        TodoWriteTool tool = new TodoWriteTool(mgr);

        ToolResult r = tool.execute("c1", Map.of("todos",
            List.of(todo("x", "done"))), ctx());
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("status"));
    }

    @Test
    void missingContentFails() {
        InMemorySessionManager mgr = new InMemorySessionManager();
        mgr.getOrCreate("s1", "u1", "default", Map.of());
        TodoWriteTool tool = new TodoWriteTool(mgr);

        ToolResult r = tool.execute("c1", Map.of("todos",
            List.of(Map.of("status", "pending"))), ctx());
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("content"));
    }

    @Test
    void multipleInProgressWarnsButPersists() {
        InMemorySessionManager mgr = new InMemorySessionManager();
        Session session = mgr.getOrCreate("s1", "u1", "default", Map.of());
        TodoWriteTool tool = new TodoWriteTool(mgr);

        ToolResult r = tool.execute("c1", Map.of("todos", List.of(
            todo("a", "in_progress"),
            todo("b", "in_progress"))), ctx());

        assertTrue(r.success());
        assertTrue(r.content().contains("一个 in_progress"));
        assertNotNull(session.metadata().get(TodoWriteTool.META_TODO_KEY));
    }

    @Test
    void safeToolNeverNeedsApproval() {
        TodoWriteTool tool = new TodoWriteTool(null);
        assertFalse(tool.requiresApproval());
        assertEquals(com.gantang.tianshu.api.tool.policy.RiskLevel.SAFE, tool.riskLevel());
        assertEquals("todo_write", tool.name());
        assertNotNull(tool.parameters());
    }

    @Test
    void missingSessionFailsGracefully() {
        TodoWriteTool tool = new TodoWriteTool(new InMemorySessionManager());
        ToolResult r = tool.execute("c1", Map.of("todos", List.of(todo("a", "pending"))), ctx());
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("session not found"));
    }

    @Test
    void renderFormatsStatesAndActiveForm() {
        List<Map<String, Object>> todos = List.of(
            Map.of("content", "任务一", "status", "completed"),
            Map.of("content", "任务二", "status", "in_progress", "activeForm", "正在改代码"),
            Map.of("content", "任务三", "status", "pending"));
        String out = TodoWriteTool.render(todos);
        assertTrue(out.contains("[x] 任务一"));
        assertTrue(out.contains("[~] 任务二  ← 进行中：正在改代码"));
        assertTrue(out.contains("[ ] 任务三"));
        assertTrue(out.contains("1/3 完成"));
    }
}
