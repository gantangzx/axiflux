package com.gantang.tianshu.impl.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.config.LiveSettings;
import com.gantang.tianshu.api.tool.Tool;
import com.gantang.tianshu.api.tool.ToolResult;
import com.gantang.tianshu.impl.tool.support.PathGuard;
import com.gantang.tianshu.api.tool.Workspaces;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Built-in tool: git integration, consolidated into one tool with an {@code action}
 * discriminator (mirrors the shape of the scheduler/spawn tools).
 *
 * <ul>
 *   <li>{@code status} — branch, ahead/behind, staged/unstaged/untracked/conflicts (read-only)</li>
 *   <li>{@code diff}   — unified diff, optionally staged and/or for a path (read-only)</li>
 *   <li>{@code log}    — recent oneline history (read-only)</li>
 *   <li>{@code add}    — stage files (mutating; approval-gated by GitActionPolicy)</li>
 *   <li>{@code commit} — stage optional files then commit with a message (mutating; gated)</li>
 * </ul>
 *
 * <p>Read actions are READ level (no approval); mutating actions are ASK-gated by
 * {@link com.gantang.tianshu.impl.tool.policy.GitActionPolicy}. This tool never pushes.
 *
 * <p>Since read actions ({@code status}/{@code diff}/{@code log}) disclose
 * repository content, {@code workingDir} is jailed the same way the file tools
 * are (toolsec P2-4): an explicit directory must sit inside the configured
 * {@code file-allowed-roots} (or the caller's tenant workspace); omitting it
 * falls back to the first allowed root. With no roots configured at all the
 * tool refuses to run rather than reading arbitrary repositories.
 */
public class GitTool implements Tool {

    private final List<Path> allowedRoots;
    private volatile LiveSettings live;
    private volatile Workspaces workspaces;

    public GitTool() { this(List.of()); }
    public GitTool(List<Path> allowedRoots) {
        this.allowedRoots = allowedRoots == null ? List.of() : List.copyOf(allowedRoots);
    }

    /** Wire live settings so file-allowed-roots changes apply without restart. */
    public GitTool withLiveSettings(LiveSettings live) { this.live = live; return this; }

    /** Enable per-tenant workspace jailing (same shape as the file tools). */
    public GitTool withWorkspaces(Workspaces workspaces) { this.workspaces = workspaces; return this; }

    private List<Path> roots() {
        LiveSettings ls = live;
        if (ls != null && ls.snapshot() != null && ls.snapshot().fileAllowedRoots() != null
                && !ls.snapshot().fileAllowedRoots().isEmpty()) {
            return ls.snapshot().fileAllowedRoots();
        }
        return allowedRoots;
    }

    /** Effective allowlist for this call: tenant root (optionally + shared roots). */
    private List<Path> roots(AgentContext ctx) {
        return workspaces != null ? workspaces.scope(roots(), ctx) : roots();
    }

    private static final JsonNode SCHEMA = SchemaSupport.parse("""
        {
          "type": "object",
          "properties": {
            "action":     { "type": "string", "enum": ["status", "diff", "log", "add", "commit"],
                            "description": "Git operation. status/diff/log are read-only; add/commit modify the repo (require approval)." },
            "workingDir": { "type": "string", "description": "Repository (or subdirectory) path. Defaults to the current working directory." },
            "path":       { "type": "string", "description": "For diff: limit to this file/directory. For add: ignored (use files)." },
            "staged":     { "type": "boolean", "description": "For diff: show staged (--cached) changes. Default false (unstaged)." },
            "context":    { "type": "integer", "description": "For diff: lines of context (default 3)." },
            "files":      { "type": "array", "items": { "type": "string" },
                            "description": "For add/commit: paths to stage. For commit, omitted files mean 'commit already-staged changes'." },
            "message":    { "type": "string", "description": "For commit: the commit message (required)." },
            "count":      { "type": "integer", "description": "For log: number of entries (default 10)." }
          },
          "required": ["action"]
        }
        """);

    @Override public String name()    { return "git"; }
    @Override public String group()   { return "builtin"; }
    @Override public JsonNode parameters() { return SCHEMA; }
    @Override public String description() {
        return "Inspect and modify a git repository. Read actions: 'status' (branch + changed files), "
            + "'diff' (unified diff of changes; use staged=true for staged, path= to limit to a file), "
            + "'log' (recent commits). Write actions (require approval): 'add' (stage files) and "
            + "'commit' (stage optional 'files' then create a commit with 'message'). Never pushes. "
            + "Set workingDir to the project path if not running inside the repo.";
    }

    @Override
    public ToolResult execute(String callId, Map<String, Object> params, AgentContext context) {
        String action = String.valueOf(params.getOrDefault("action", "status")).trim().toLowerCase();
        String rawDir = String.valueOf(params.getOrDefault("workingDir", "")).trim();
        List<Path> roots = roots(context);
        if (workspaces == null && !rawDir.isEmpty() && roots.isEmpty()) {
            // Fail closed (PathGuard denies everything on an empty allowlist, and
            // silently swapping in the process cwd would be a fail-open): an
            // explicit directory must be gated by configured roots.
            return ToolResult.failure(callId,
                "workingDir is outside the allowed file roots: " + rawDir
                + " (no file roots are configured; set tools.file-allowed-roots to permit it)");
        }
        Path workDir = resolveWorkDir(rawDir, roots);
        if (workDir == null) {
            return ToolResult.failure(callId, "workingDir does not exist or is not a directory: "
                + params.get("workingDir"));
        }
        if (!PathGuard.isAllowed(workDir.toString(), roots)) {
            return ToolResult.failure(callId, "workingDir is outside the allowed file roots: " + rawDir);
        }
        if (!isGitRepo(workDir)) {
            return ToolResult.failure(callId, "Not a git repository (no .git found at or above): " + workDir);
        }

        try {
            return switch (action) {
                case "status" -> status(callId, workDir);
                case "diff"   -> diff(callId, params, workDir);
                case "log"    -> log(callId, params, workDir);
                case "add"    -> add(callId, params, workDir);
                case "commit" -> commit(callId, params, workDir);
                default -> ToolResult.failure(callId, "Unknown action: " + action
                    + " (expected status|diff|log|add|commit)");
            };
        } catch (GitException e) {
            return ToolResult.failure(callId, e.getMessage());
        } catch (Exception e) {
            return ToolResult.failure(callId, "git " + action + " failed: " + e.getMessage());
        }
    }

    // ── actions ────────────────────────────────────────────────────────────

    private ToolResult status(String callId, Path workDir) throws GitException, InterruptedException {
        String out = runGit(workDir, "status", "--porcelain=v1", "-b", "--no-renames");
        String[] lines = out.replace("\r\n", "\n").split("\n");
        String branch = "(unknown)";
        String aheadBehind = "";
        List<String> staged = new ArrayList<>();
        List<String> unstaged = new ArrayList<>();
        List<String> untracked = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();

        for (String raw : lines) {
            if (raw.isEmpty()) continue;
            if (raw.startsWith("##")) {
                String head = raw.substring(2).trim();
                int dots = head.indexOf("...");
                String name = dots >= 0 ? head.substring(0, dots) : head;
                branch = name.isBlank() ? "(detached)" : name;
                int lb = head.indexOf('[');
                if (lb >= 0) {
                    int rb = head.indexOf(']', lb);
                    aheadBehind = rb > lb ? head.substring(lb + 1, rb) : "";
                }
                continue;
            }
            if (raw.length() < 3) continue;
            char x = raw.charAt(0);   // index status
            char y = raw.charAt(1);   // worktree status
            String file = raw.substring(3).trim();
            // Conflict states: DD, AU, UD, UA, DU, AA, UU
            if (x == 'U' || y == 'U' || (x == 'A' && y == 'A') || (x == 'D' && y == 'D')) {
                conflicts.add(file);
            } else {
                if (x != ' ' && x != '?') staged.add(x + " " + file);
                if (y != ' ' && y != '?') unstaged.add(y + " " + file);
                if (x == '?' && y == '?') untracked.add(file);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("branch: ").append(branch).append('\n');
        if (!aheadBehind.isEmpty()) sb.append("upstream: ").append(aheadBehind).append('\n');
        sb.append("conflicts: ").append(conflicts.size()).append('\n');
        appendList(sb, "staged", staged);
        appendList(sb, "unstaged", unstaged);
        appendList(sb, "untracked", untracked);
        String clean = (staged.isEmpty() && unstaged.isEmpty() && untracked.isEmpty() && conflicts.isEmpty())
            ? "\nWorking tree is clean." : "";
        return ToolResult.success(callId, sb.append(clean).toString().stripTrailing());
    }

    private ToolResult diff(String callId, Map<String, Object> params, Path workDir)
            throws GitException, InterruptedException {
        boolean staged = Boolean.TRUE.equals(params.get("staged"));
        int context = intVal(params.get("context"), 3);
        String path = strVal(params.get("path"));

        List<String> cmd = new ArrayList<>(List.of("diff", "--no-color", "-U" + Math.max(0, context)));
        if (staged) cmd.add("--cached");
        cmd.add("--");
        if (path != null && !path.isBlank()) cmd.add(path);

        String out = runGit(workDir, cmd.toArray(new String[0]));
        if (out.isBlank()) {
            return ToolResult.success(callId, staged
                ? "No staged changes."
                : "No unstaged changes" + (path != null ? " for " + path : "") + ".");
        }
        // Cap very large diffs so the model context isn't flooded.
        String capped = cap(out, 20_000);
        return ToolResult.success(callId, capped);
    }

    private ToolResult log(String callId, Map<String, Object> params, Path workDir)
            throws GitException, InterruptedException {
        int count = Math.max(1, Math.min(100, intVal(params.get("count"), 10)));
        String out = runGit(workDir, "log", "-" + count, "--oneline");
        return ToolResult.success(callId, out.isBlank() ? "No commits yet." : out.stripTrailing());
    }

    private ToolResult add(String callId, Map<String, Object> params, Path workDir)
            throws GitException, InterruptedException {
        List<String> files = stringList(params.get("files"));
        if (files.isEmpty()) {
            return ToolResult.failure(callId, "add requires 'files' (list of paths to stage). "
                + "Pass the files you changed explicitly.");
        }
        List<String> cmd = new ArrayList<>(List.of("add", "--"));
        cmd.addAll(files);
        runGit(workDir, cmd.toArray(new String[0]));
        return ToolResult.success(callId, "Staged " + files.size() + " path(s):\n- " + String.join("\n- ", files));
    }

    private ToolResult commit(String callId, Map<String, Object> params, Path workDir)
            throws GitException, InterruptedException {
        String message = strVal(params.get("message"));
        if (message == null || message.isBlank()) {
            return ToolResult.failure(callId, "commit requires a non-empty 'message'");
        }
        List<String> files = stringList(params.get("files"));
        if (!files.isEmpty()) {
            List<String> addCmd = new ArrayList<>(List.of("add", "--"));
            addCmd.addAll(files);
            runGit(workDir, addCmd.toArray(new String[0]));
        }
        try {
            runGit(workDir, "commit", "-m", message);
        } catch (GitException e) {
            // "nothing to commit" surfaces as a non-zero exit with a helpful message.
            return ToolResult.failure(callId, "commit failed: " + e.getMessage());
        }
        String hash = runGit(workDir, "rev-parse", "HEAD").strip();
        String stat = runGit(workDir, "show", "--stat", "--oneline", "--no-patch", "HEAD").strip();
        return ToolResult.success(callId,
            "Committed " + hash + "\n" + (stat.isBlank() ? message : stat));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static void appendList(StringBuilder sb, String label, List<String> items) {
        sb.append(label).append(": ").append(items.size());
        if (!items.isEmpty()) {
            sb.append('\n');
            for (String f : items) sb.append("  ").append(f).append('\n');
        } else {
            sb.append('\n');
        }
    }

    /**
     * Resolve the repository directory. A blank {@code workingDir} falls back to
     * the first configured root — never to the process working directory, which
     * could sit outside the jail entirely (toolsec P2-4).
     */
    private static Path resolveWorkDir(String raw, List<Path> roots) {
        try {
            Path p = (raw == null || raw.isBlank())
                ? (roots.isEmpty() ? null : roots.get(0).toAbsolutePath().normalize())
                : Paths.get(raw).toAbsolutePath().normalize();
            return (p != null && Files.isDirectory(p)) ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isGitRepo(Path dir) {
        Path p = dir;
        while (p != null) {
            if (Files.isDirectory(p.resolve(".git"))) return true;
            p = p.getParent();
        }
        return false;
    }

    private static String strVal(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static int intVal(Object o, int dflt) {
        if (o instanceof Number n) return n.intValue();
        if (o != null) {
            try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Exception ignored) {}
        }
        return dflt;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) out.add(String.valueOf(item).trim());
            }
        } else if (o instanceof String s && !s.isBlank()) {
            for (String part : s.split("[,\\n]")) {
                if (!part.isBlank()) out.add(part.trim());
            }
        }
        return out;
    }

    private static String cap(String text, int maxChars) {
        if (text.length() <= maxChars) return text;
        String head = text.substring(0, maxChars);
        return head + "\n…[diff truncated: " + text.length() + " total chars, showing first " + maxChars + "]";
    }

    /** Run a git command, returning stdout. Throws GitException on non-zero exit. */
    private static String runGit(Path workDir, String... argv) throws GitException, InterruptedException {
        List<String> cmd = new ArrayList<>(argv.length + 1);
        cmd.add("git");
        cmd.addAll(List.of(argv));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(false);
        try {
            Process proc = pb.start();
            String stdout = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(proc.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = proc.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                throw new GitException("git " + argv[0] + " timed out after 60s");
            }
            int exit = proc.exitValue();
            if (exit != 0) {
                String msg = stderr.isBlank() ? stdout.strip() : stderr.strip();
                throw new GitException("git " + String.join(" ", argv) + " (exit " + exit + "): "
                    + (msg.isBlank() ? "(no output)" : cap(msg, 800)));
            }
            return stdout;
        } catch (IOException e) {
            throw new GitException("cannot run git (" + e.getMessage()
                + "). Ensure git is installed and on PATH.");
        }
    }

    private static final class GitException extends Exception {
        GitException(String msg) { super(msg); }
    }
}
