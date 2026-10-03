package com.gantang.reaxon.impl.tool.builtin;

import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.tool.ToolResult;
import com.gantang.reaxon.impl.tool.support.LocalCommandSandbox;
import com.gantang.reaxon.impl.tool.support.TenantWorkspaces;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2-3 multi-tenant workspace isolation at the tool layer: every file/command
 * path is jailed to {@code <base>/<userId>/}, both for lexical traversal and
 * absolute paths aimed at another tenant or the host.
 */
class TenantWorkspaceToolsTest {

    @TempDir
    Path temp;

    private AgentContext ctx(String user) {
        return AgentContext.builder().sessionId("s1").userId(user).currentQuery("q").build();
    }

    @Test
    void fileWriteIsJailedToTenantRoot() throws Exception {
        var ws = new TenantWorkspaces(temp, false);
        var tool = new FileWriteTool().withWorkspaces(ws);

        ToolResult ok = tool.execute("c1",
            Map.of("path", "notes/a.txt", "content", "hello"), ctx("alice"));
        assertTrue(ok.success(), ok::errorMessage);
        assertTrue(Files.exists(temp.resolve("alice/notes/a.txt")));

        // lexical traversal into a sibling tenant root
        ToolResult cross = tool.execute("c2",
            Map.of("path", "../bob/secret.txt", "content", "x"), ctx("alice"));
        assertFalse(cross.success());
        assertTrue(cross.errorMessage().contains("outside the tenant workspace"));

        // absolute path into another tenant's root
        Path bob = temp.resolve("bob/secret.txt");
        ToolResult abs = tool.execute("c3",
            Map.of("path", bob.toString(), "content", "x"), ctx("alice"));
        assertFalse(abs.success());
        assertFalse(Files.exists(bob));
    }

    @Test
    void tenantsCannotReadEachOthersFiles() throws Exception {
        var ws = new TenantWorkspaces(temp, false);
        var write = new FileWriteTool().withWorkspaces(ws);
        var read = new FileReadTool().withWorkspaces(ws);

        write.execute("c1", Map.of("path", "diary.txt", "content", "alice-only"), ctx("alice"));

        ToolResult denied = read.execute("c2",
            Map.of("path", "../alice/diary.txt"), ctx("bob"));
        assertFalse(denied.success());
        assertTrue(denied.errorMessage().contains("outside the tenant workspace"));

        ToolResult own = read.execute("c3", Map.of("path", "diary.txt"), ctx("alice"));
        assertTrue(own.success(), own::errorMessage);
        assertTrue(own.content().contains("alice-only"));
    }

    @Test
    void codeExecutorRejectsWorkdirOutsideWorkspace() {
        var ws = new TenantWorkspaces(temp, false);
        var tool = new CodeExecutorTool(new LocalCommandSandbox(), ws);

        ToolResult denied = tool.execute("c1",
            Map.of("command", "echo hi", "workingDir", temp.getRoot().toString()),
            ctx("alice"));
        assertFalse(denied.success());
        assertTrue(denied.errorMessage().contains("outside the tenant workspace"));
    }

    @Test
    void codeExecutorRunsInsideTenantWorkspace() {
        var ws = new TenantWorkspaces(temp, false);
        var tool = new CodeExecutorTool(new LocalCommandSandbox(), ws);

        ToolResult result = tool.execute("c2", Map.of("command", "echo hi"), ctx("alice"));
        assertTrue(result.success(), result::errorMessage);
        assertTrue(result.content().contains("hello") || result.content().contains("hi"));
        // The output header surfaces the jailed workspace for observability.
        assertTrue(result.content().contains("workspace="), result.content());
        assertTrue(result.content().contains("alice"));
    }
}
