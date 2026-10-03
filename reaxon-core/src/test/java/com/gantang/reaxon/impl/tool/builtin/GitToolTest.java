package com.gantang.reaxon.impl.tool.builtin;

import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.tool.ToolResult;
import com.gantang.reaxon.impl.tool.policy.GitActionPolicy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests for the {@code git} tool. Requires a {@code git} binary on
 * PATH; the whole class is skipped (not failed) when git is unavailable.
 */
class GitToolTest {

    private final AgentContext ctx = AgentContext.builder()
        .sessionId("t").userId("u").currentQuery("").build();
    private final GitTool tool = new GitTool();
    private final GitActionPolicy policy = new GitActionPolicy();

    /** toolsec P2-4: an explicit workingDir outside the configured roots is refused. */
    @Test
    void workingDir_outsideAllowedRoots_isRejected(@TempDir Path root, @TempDir Path repo) throws Exception {
        git(repo, "init", "-q");
        GitTool jailed = new GitTool(java.util.List.of(root));
        ToolResult r = jailed.execute("p1", Map.of(
            "action", "status", "workingDir", repo.toString()), ctx);
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("outside the allowed file roots"), r.errorMessage());
    }

    /** toolsec P2-4: explicit workingDir with NO roots configured fails closed (no cwd swap-in). */
    @Test
    void workingDir_withoutAnyRoots_failsClosed(@TempDir Path repo) throws Exception {
        git(repo, "init", "-q");
        ToolResult r = tool.execute("p2", Map.of(
            "action", "status", "workingDir", repo.toString()), ctx);
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("outside the allowed file roots"), r.errorMessage());
    }

    /** toolsec P2-4: inside the roots still works; blank workingDir falls back to the first root. */
    @Test
    void workingDir_insideAllowedRoots_isAccepted(@TempDir Path root) throws Exception {
        Path repo = Files.createDirectory(root.resolve("repo"));
        git(repo, "init", "-q");
        GitTool jailed = new GitTool(java.util.List.of(root));
        ToolResult inside = jailed.execute("p3", Map.of(
            "action", "status", "workingDir", repo.toString()), ctx);
        assertTrue(inside.success(), inside.errorMessage());
        ToolResult fallback = jailed.execute("p4", Map.of(
            "action", "status", "workingDir", ""), ctx);
        // root itself is not a repo, so the fallback resolves to root and reports that —
        // proving it did not escape to the process working directory.
        assertFalse(fallback.success());
        assertTrue(fallback.errorMessage().contains("Not a git repository"), fallback.errorMessage());
    }

    @BeforeAll
    static void requireGit() throws Exception {
        boolean present;
        try {
            Process p = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            present = p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            present = false;
        }
        assumeTrue(present, "git not on PATH; skipping GitToolTest");
    }

    private static void git(Path repo, String... argv) throws Exception {
        var cmd = new java.util.ArrayList<String>();
        cmd.add("git");
        cmd.add("-c"); cmd.add("user.email=test@example.com");
        cmd.add("-c"); cmd.add("user.name=Test");
        cmd.addAll(List.of(argv));
        Process p = new ProcessBuilder(cmd).directory(repo.toFile())
            .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "git " + String.join(" ", argv) + " hung");
        assertEquals(0, p.exitValue(), "git " + String.join(" ", argv) + " -> " + out);
    }

    @Test
    void status_diff_log_commit_flow(@TempDir Path repo) throws Exception {
        git(repo, "init", "-q");
        GitTool tool = new GitTool(List.of(repo)); // jail to the test repo (P2-4)

        // Not a repo case: a sibling empty dir is not under .git
        Path outside = Files.createTempDirectory("notgit");
        GitTool wide = new GitTool(List.of(outside)); // jail containing the non-repo dir
        ToolResult noRepo = wide.execute("g0", Map.of("action", "status",
            "workingDir", outside.toString()), ctx);
        assertFalse(noRepo.success());
        assertTrue(noRepo.errorMessage().contains("Not a git repository"));

        // First file -> add + commit via the tool
        Files.writeString(repo.resolve("a.txt"), "one\n");
        ToolResult add = tool.execute("g1", Map.of(
            "action", "add", "workingDir", repo.toString(),
            "files", List.of("a.txt")), ctx);
        assertTrue(add.success(), add.errorMessage());

        ToolResult commit = tool.execute("g2", Map.of(
            "action", "commit", "workingDir", repo.toString(),
            "message", "feat: add a"), ctx);
        assertTrue(commit.success(), commit.errorMessage());
        assertTrue(commit.content().contains("Committed"), commit.content());

        ToolResult log = tool.execute("g3", Map.of(
            "action", "log", "workingDir", repo.toString(), "count", 5), ctx);
        assertTrue(log.success(), log.errorMessage());
        assertTrue(log.content().contains("feat: add a"), log.content());

        // Modify tracked file -> unstaged diff and status show it
        Files.writeString(repo.resolve("a.txt"), "one\ntwo\n");
        ToolResult status = tool.execute("g4", Map.of(
            "action", "status", "workingDir", repo.toString()), ctx);
        assertTrue(status.success(), status.errorMessage());
        assertTrue(status.content().contains("unstaged: 1"), status.content());

        ToolResult diff = tool.execute("g5", Map.of(
            "action", "diff", "workingDir", repo.toString(), "path", "a.txt"), ctx);
        assertTrue(diff.success(), diff.errorMessage());
        assertTrue(diff.content().contains("+two"), diff.content());
        assertTrue(diff.content().contains("-one") || diff.content().contains(" one"),
            "diff should show context/removal:\n" + diff.content());
    }

    @Test
    void commit_requires_message(@TempDir Path repo) throws Exception {
        git(repo, "init", "-q");
        GitTool tool = new GitTool(List.of(repo)); // jail to the test repo (P2-4)
        ToolResult r = tool.execute("c1", Map.of(
            "action", "commit", "workingDir", repo.toString()), ctx);
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("message"));
    }

    @Test
    void read_actions_allowed_mutating_ask() {
        // read actions -> policy allows (no ASK)
        assertTrue(policy.evaluate(tool, Map.of("action", "status"), ctx).isAllow());
        assertTrue(policy.evaluate(tool, Map.of("action", "diff"), ctx).isAllow());
        assertTrue(policy.evaluate(tool, Map.of("action", "log"), ctx).isAllow());
        // mutating actions -> ASK
        var ask = policy.evaluate(tool, Map.of("action", "commit"), ctx);
        assertTrue(ask.isAsk(), "commit should ASK, got: " + ask);
        assertTrue(policy.evaluate(tool, Map.of("action", "add"), ctx).isAsk());
        // non-git tool -> null (falls through)
        assertNull(policy.evaluate(new com.gantang.reaxon.api.tool.Tool() {
            public String name() { return "other"; }
            public String description() { return ""; }
            public com.fasterxml.jackson.databind.JsonNode parameters() { return null; }
            public com.gantang.reaxon.api.tool.ToolResult execute(String c, Map<String, Object> p, AgentContext x) { return null; }
        }, Map.of("action", "commit"), ctx));
    }
}
