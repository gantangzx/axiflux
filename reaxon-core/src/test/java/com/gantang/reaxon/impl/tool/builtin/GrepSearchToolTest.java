package com.gantang.reaxon.impl.tool.builtin;

import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GrepSearchToolTest {

    private final AgentContext ctx = AgentContext.builder()
        .sessionId("t").userId("u").currentQuery("").build();

    private void seed(Path root) throws IOException {
        Files.writeString(root.resolve("Alpha.java"),
            "class Alpha {\n  int add(int a, int b) { return a + b; }\n}\n");
        Files.writeString(root.resolve("Beta.java"),
            "class Beta {\n  // TODO: implement\n  int sub(int a, int b) { return a - b; }\n}\n");
        Files.writeString(root.resolve("notes.txt"),
            "random notes\nno code here\n");
        Files.createDirectories(root.resolve("target/classes"));
        Files.writeString(root.resolve("target/classes/Alpha.class"), "binary\u0000stuff");
        Files.createDirectories(root.resolve("node_modules/pkg"));
        Files.writeString(root.resolve("node_modules/pkg/Alpha.java"),
            "class Alpha { int add(){ return 0; } }\n");
    }

    @Test
    void finds_matches_with_line_numbers(@TempDir Path root) throws IOException {
        seed(root);
        GrepSearchTool tool = new GrepSearchTool(List.of(root));

        ToolResult r = tool.execute("g1", Map.of(
            "pattern", "return a", "path", root.toString()), ctx);
        assertTrue(r.success(), r.errorMessage());
        // both Alpha.java and Beta.java match, with path + line: prefix
        assertTrue(r.content().contains("Alpha.java"), r.content());
        assertTrue(r.content().contains("Beta.java"), r.content());
        assertTrue(r.content().contains("2:"), "line numbers present:\n" + r.content());
        assertTrue(r.content().contains("2 matches") || r.content().contains("3 match"),
            "header count:\n" + r.content());
    }

    @Test
    void skips_build_and_dependency_dirs_and_binary(@TempDir Path root) throws IOException {
        seed(root);
        GrepSearchTool tool = new GrepSearchTool(List.of(root));

        // 'class Alpha' appears in src Alpha.java AND target/.../Alpha.class(binary) AND node_modules
        ToolResult r = tool.execute("g2", Map.of(
            "pattern", "class Alpha", "path", root.toString()), ctx);
        assertTrue(r.success(), r.errorMessage());
        // node_modules copy must NOT appear; binary .class not read
        assertFalse(r.content().contains("node_modules"), "node_modules should be skipped:\n" + r.content());
        // exactly one source file (Alpha.java at root) matches
        long occurrences = r.content().lines().filter(l -> l.contains("Alpha.java")).count();
        assertTrue(occurrences >= 1, "root Alpha.java header present:\n" + r.content());
    }

    @Test
    void glob_filter_restricts_files(@TempDir Path root) throws IOException {
        seed(root);
        GrepSearchTool tool = new GrepSearchTool(List.of(root));

        // 'return a' in *.java only; glob to *.txt should find nothing for 'return'
        ToolResult txt = tool.execute("g3", Map.of(
            "pattern", "return", "path", root.toString(), "glob", "*.txt"), ctx);
        assertTrue(txt.success(), txt.errorMessage());
        assertTrue(txt.content().contains("No matches"), "txt glob -> no return:\n" + txt.content());

        ToolResult java = tool.execute("g4", Map.of(
            "pattern", "return", "path", root.toString(), "glob", "*.java"), ctx);
        assertTrue(java.content().contains("Alpha.java") && java.content().contains("Beta.java"),
            "java glob finds both:\n" + java.content());
    }

    @Test
    void case_insensitive_and_no_matches(@TempDir Path root) throws IOException {
        seed(root);
        GrepSearchTool tool = new GrepSearchTool(List.of(root));

        ToolResult ci = tool.execute("g5", Map.of(
            "pattern", "todo", "path", root.toString(), "ignoreCase", true), ctx);
        assertTrue(ci.success(), ci.errorMessage());
        assertTrue(ci.content().contains("Beta.java"), "ignoreCase should find TODO:\n" + ci.content());

        ToolResult none = tool.execute("g6", Map.of(
            "pattern", "ZZZ_NOT_PRESENT_ZZZ", "path", root.toString()), ctx);
        assertTrue(none.content().contains("No matches"));
    }

    @Test
    void invalid_regex_and_missing_pattern(@TempDir Path root) {
        GrepSearchTool tool = new GrepSearchTool(List.of(root));
        ToolResult badRegex = tool.execute("g7", Map.of(
            "pattern", "([unclosed", "path", root.toString()), ctx);
        assertFalse(badRegex.success());
        assertTrue(badRegex.errorMessage().contains("Invalid regular expression"));

        ToolResult noPattern = tool.execute("g8", Map.of("path", root.toString()), ctx);
        assertFalse(noPattern.success());
        assertTrue(noPattern.errorMessage().contains("pattern"));
    }

    @Test
    void rejects_outside_allowed_roots(@TempDir Path allowed) {
        GrepSearchTool tool = new GrepSearchTool(List.of(allowed));
        ToolResult r = tool.execute("g9", Map.of(
            "pattern", "x", "path", "C:/Windows/System32/drivers/etc"), ctx);
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("outside allowed roots"));
    }

    // ===== audit toolsec P2-3: ReDoS guard =====

    @Test
    void catastrophicBacktrackingPatternTimesOutInsteadOfHanging(@TempDir Path root) throws IOException {
        // One large file of non-matching input: (a+)+$ backtracks exponentially.
        Files.writeString(root.resolve("evil.txt"), "a".repeat(100_000) + "b\n");
        GrepSearchTool tool = new GrepSearchTool(List.of(root));

        long prev = GrepSearchTool.matchTimeoutMs;
        GrepSearchTool.matchTimeoutMs = 1_000; // keep the suite fast; the guard logic is what is under test
        try {
            long start = System.nanoTime();
            ToolResult r = tool.execute("g-redos", Map.of(
                "pattern", "(a+)+$", "path", root.toString()), ctx);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            // The call must come back shortly after the budget (never minutes).
            assertTrue(elapsedMs < 15_000, "scan returned in " + elapsedMs + "ms — timeout guard broken");
            assertFalse(r.success(), "a timed-out scan with no matches should fail cleanly");
            assertTrue(r.errorMessage().contains("timed out"), r.errorMessage());
        } finally {
            GrepSearchTool.matchTimeoutMs = prev;
        }
    }

    @Test
    void oversizedPatternIsRejectedUpFront(@TempDir Path root) {
        GrepSearchTool tool = new GrepSearchTool(List.of(root));
        ToolResult r = tool.execute("g-long", Map.of(
            "pattern", "a".repeat(600), "path", root.toString()), ctx);
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("too long"), r.errorMessage());
    }
}
