package com.gantang.tianshu.impl.tool.builtin;

import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CodeExecutorToolTest {

    private final CodeExecutorTool tool = new CodeExecutorTool();

    private AgentContext ctx() {
        return AgentContext.builder()
            .sessionId("test").userId("test").currentQuery("").build();
    }

    @Test
    void scrubEnvironmentStripsSecretsButKeepsSystemVars() {
        ProcessBuilder pb = new ProcessBuilder();
        pb.environment().put("ARK_API_KEY", "sk-secret");
        pb.environment().put("PG_PASSWORD", "hunter2");
        pb.environment().put("MY_SESSION_TOKEN", "tok");
        pb.environment().put("CUSTOMER_JWT", "jwtval");

        CodeExecutorTool.scrubEnvironment(pb);

        var env = pb.environment();
        assertFalse(env.containsValue("sk-secret"), "API key value must be removed");
        assertFalse(env.containsValue("hunter2"), "password value must be removed");
        assertFalse(env.containsValue("tok"), "token value must be removed");
        assertFalse(env.containsValue("jwtval"), "credential value must be removed");
        // System variables needed for shells to function are retained.
        assertTrue(env.containsKey("Path") || env.containsKey("PATH")
            || env.containsKey("SystemRoot"), "core system env vars must be retained");
    }

    @Test
    void executesSimpleCommand() {
        String cmd = System.getProperty("os.name", "").toLowerCase().contains("win")
            ? "echo hello" : "echo hello";
        ToolResult result = tool.execute("c1", Map.of("command", cmd), ctx());
        assertTrue(result.success(), "Expected success but got: " + result.errorMessage());
        assertTrue(result.content().contains("hello"));
        assertTrue(result.content().contains("exit=0"));
    }

    @Test
    void missingCommand_returnsFailure() {
        ToolResult result = tool.execute("c2", Map.of(), ctx());
        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("command is required"));
    }

    @Test
    void failedCommand_returnsFailureWithExitCode() {
        String cmd = System.getProperty("os.name", "").toLowerCase().contains("win")
            ? "cmd /c exit 1" : "false";
        ToolResult result = tool.execute("c3", Map.of("command", cmd), ctx());
        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("exit="));
    }

    @Test
    void requiresApproval() {
        assertTrue(tool.requiresApproval());
    }

    // ===== audit toolsec P2-5: shell-command transparency + workingDir jail =====

    @Test
    void descriptionDisclosesFullShellSemanticsForApproverReview() {
        // The approval gate is the only review step for a DESTRUCTIVE tool, so
        // the description must tell the approver exactly what they are signing:
        // a complete shell line where metacharacters chain commands.
        String desc = tool.description();
        assertTrue(desc.contains("cmd.exe /c") && desc.contains("sh -c"),
            "description must name the shell wrappers: " + desc);
        assertTrue(desc.toLowerCase().contains("metacharacter"),
            "description must warn about shell metacharacters: " + desc);
        String cmdParam = tool.parameters().get("properties").get("command").get("description").asText();
        assertTrue(cmdParam.contains("cmd.exe /c") && cmdParam.contains("ENTIRE"),
            "command param must instruct whole-line review: " + cmdParam);
    }

    @Test
    void workingDirOutsideAllowedRootsIsRejectedBeforeSpawn(@org.junit.jupiter.api.io.TempDir java.nio.file.Path jail)
            throws java.io.IOException {
        java.nio.file.Path allowed = jail.resolve("allowed");
        java.nio.file.Files.createDirectories(allowed);
        java.nio.file.Path outside = jail.resolve("outside");
        java.nio.file.Files.createDirectories(outside);

        CodeExecutorTool jailed = new CodeExecutorTool(
            new com.gantang.tianshu.impl.tool.support.LocalCommandSandbox(), null,
            java.util.List.of(allowed));

        ToolResult r = jailed.execute("wd1", Map.of(
            "command", "echo hi", "workingDir", outside.toString()), ctx());
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("outside the allowed file roots"), r.errorMessage());

        // ...and inside the jail is accepted.
        ToolResult ok = jailed.execute("wd2", Map.of(
            "command", "echo hi", "workingDir", allowed.toString()), ctx());
        assertTrue(ok.success(), "inside-jail workingDir should run: " + ok.errorMessage());
    }

    @Test
    void toolMetadata() {
        assertEquals("code_executor", tool.name());
        assertNotNull(tool.parameters());
        assertTrue(tool.parameters().has("properties"));
    }
}
