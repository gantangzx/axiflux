package com.gantang.reaxon.impl.tool.builtin;

import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileEditToolTest {

    private final AgentContext ctx = AgentContext.builder()
        .sessionId("t").userId("u").currentQuery("").build();

    @Test
    void replaces_unique_block_and_returns_diff(@TempDir Path dir) throws Exception {
        FileEditTool tool = new FileEditTool(List.of(dir));
        Path f = dir.resolve("A.java");
        Files.writeString(f, "line one\nline two\nline three\n");

        ToolResult r = tool.execute("e1", Map.of(
            "path", f.toString(),
            "old", "line two",
            "new", "line TWO"), ctx);

        assertTrue(r.success(), r.errorMessage());
        assertEquals("line one\nline TWO\nline three\n", Files.readString(f));
        assertTrue(r.content().contains("--- a/A.java"), "diff header missing:\n" + r.content());
        assertTrue(r.content().contains("-line two"));
        assertTrue(r.content().contains("+line TWO"));
        assertTrue(r.content().contains("@@"));
    }

    @Test
    void zero_matches_fails_with_context_hint(@TempDir Path dir) throws Exception {
        FileEditTool tool = new FileEditTool(List.of(dir));
        Path f = dir.resolve("A.txt");
        Files.writeString(f, "alpha\nbeta\ngamma\n");

        ToolResult r = tool.execute("e2", Map.of(
            "path", f.toString(),
            "old", "not-present",
            "new", "x"), ctx);

        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("No match found"));
        assertTrue(r.errorMessage().contains("gamma"), "should show tail context:\n" + r.errorMessage());
    }

    @Test
    void multiple_matches_fail_unless_replaceAll(@TempDir Path dir) throws Exception {
        FileEditTool tool = new FileEditTool(List.of(dir));
        Path f = dir.resolve("dup.txt");
        Files.writeString(f, "x\nx\nx\n");

        ToolResult ambiguous = tool.execute("e3", Map.of(
            "path", f.toString(), "old", "x", "new", "y"), ctx);
        assertFalse(ambiguous.success());
        assertTrue(ambiguous.errorMessage().contains("3 times"));

        ToolResult all = tool.execute("e4", Map.of(
            "path", f.toString(), "old", "x", "new", "y", "replaceAll", true), ctx);
        assertTrue(all.success(), all.errorMessage());
        assertEquals("y\ny\ny\n", Files.readString(f));
    }

    @Test
    void identical_old_and_new_rejected(@TempDir Path dir) throws Exception {
        FileEditTool tool = new FileEditTool(List.of(dir));
        Path f = dir.resolve("s.txt");
        Files.writeString(f, "same\n");

        ToolResult r = tool.execute("e5", Map.of(
            "path", f.toString(), "old", "same", "new", "same"), ctx);
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("identical"));
    }

    @Test
    void edit_rejects_outside_allowed_root(@TempDir Path allowed) {
        FileEditTool tool = new FileEditTool(List.of(allowed));
        ToolResult r = tool.execute("e6", Map.of(
            "path", "C:/Windows/System32/drivers/etc/hosts",
            "old", "a", "new", "b"), ctx);
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("outside allowed roots"));
    }

    @Test
    void edit_requires_approval_flag() {
        assertTrue(new FileEditTool().requiresApproval());
    }

    @Test
    void deletion_of_block_supported(@TempDir Path dir) throws Exception {
        FileEditTool tool = new FileEditTool(List.of(dir));
        Path f = dir.resolve("d.txt");
        Files.writeString(f, "keep\nremove\nkeep2\n");

        ToolResult r = tool.execute("e7", Map.of(
            "path", f.toString(),
            "old", "remove\n",
            "new", ""), ctx);
        assertTrue(r.success(), r.errorMessage());
        assertEquals("keep\nkeep2\n", Files.readString(f));
    }
}
