package com.gantang.reaxon.impl.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.config.LiveSettings;
import com.gantang.reaxon.api.tool.Tool;
import com.gantang.reaxon.api.tool.ToolResult;
import com.gantang.reaxon.impl.tool.support.FileSafety;
import com.gantang.reaxon.impl.tool.support.PathGuard;
import com.gantang.reaxon.api.tool.Workspaces;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * {@code grep_search}: ripgrep-style cross-file content search over the file jail.
 *
 * <p>Pure-Java (no {@code rg} binary dependency): walks the tree, skips VCS/build/
 * dependency directories and binary files, and matches each text line against a
 * regex. Read-only, never gated.
 */
public final class GrepSearchTool implements Tool {

    private final List<Path> allowedRoots;
    private volatile LiveSettings live;
    private volatile Workspaces workspaces;

    public GrepSearchTool() { this(List.of()); }
    public GrepSearchTool(List<Path> allowedRoots) {
        this.allowedRoots = allowedRoots == null ? List.of() : allowedRoots;
    }

    /** Wire live settings so file-allowed-roots changes apply without restart. */
    public GrepSearchTool withLiveSettings(LiveSettings live) { this.live = live; return this; }

    /** Enable per-tenant workspace jailing (P2-3). */
    public GrepSearchTool withWorkspaces(Workspaces workspaces) { this.workspaces = workspaces; return this; }

    private List<Path> roots() {
        LiveSettings ls = live;
        if (ls != null && ls.snapshot() != null && ls.snapshot().fileAllowedRoots() != null
                && !ls.snapshot().fileAllowedRoots().isEmpty()) {
            return ls.snapshot().fileAllowedRoots();
        }
        return allowedRoots;
    }

    /** Directories never descended into (VCS metadata, build output, deps). */
    private static final Set<String> SKIP_DIRS = Set.of(
        ".git", ".svn", ".hg", "target", "build", "dist", "out", "bin", "obj",
        "node_modules", ".idea", ".gradle", ".mvn", "__pycache__", ".venv", "venv",
        ".next", ".nuxt", "coverage", ".cache");

    /** Extensions treated as binary are shared in {@link FileSafety#isBinaryName}. */

    private static final int MAX_LINE_LEN = 300;
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024; // don't scan huge files
    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int HARD_MAX_RESULTS = 500;
    private static final int MAX_FILES_SCANNED = 20_000;

    /**
     * Audit toolsec P2-3 (ReDoS): caller-supplied patterns run on a bounded
     * worker with a hard wall-clock budget. A catastrophic-backtracking pattern
     * (e.g. {@code (a+)+$}) would otherwise pin the calling thread — and with
     * it the agent loop / HTTP worker — for minutes on a single large file.
     * The budget covers the whole scan; on expiry partial results are returned
     * with a {@code [match timed out]} marker.
     */
    private static final long MATCH_TIMEOUT_MS = 10_000L;

    /** Test hook: shrink the wall-clock budget without touching production wiring. */
    static volatile long matchTimeoutMs = MATCH_TIMEOUT_MS;

    /**
     * Per-task daemon worker (audit toolsec P2-3). Regex matching is not
     * interruptible mid-{@code find()}, so a shared/single-thread executor is
     * unacceptable: one catastrophic pattern would wedge that worker thread and
     * every later search would queue behind it and falsely time out. We therefore
     * run each scan on its OWN daemon thread; on timeout that thread keeps chewing
     * in the background until the match unwinds (bounded to CPU, never correctness)
     * and dies with the JVM, while the next search starts on a fresh thread.
     */
    private static Thread startScanWorker(Runnable work) {
        Thread t = new Thread(work, "grep-search-matcher");
        t.setDaemon(true);
        t.start();
        return t;
    }

    @Override public String name() { return "grep_search"; }
    @Override public String group() { return "builtin"; }

    @Override public String description() {
        return "Search file contents across the codebase with a regular expression "
            + "(ripgrep/grep style). Returns matching file paths with line numbers and "
            + "the matching line. Use this to locate code, usages, or text BEFORE reading "
            + "whole files. Skips .git/build/node_modules and binary files automatically. "
            + "Read-only. The pattern runs under a "
            + (MATCH_TIMEOUT_MS / 1000) + "s wall-clock budget — pathological "
            + "(catastrophic-backtracking) patterns time out instead of hanging.";
    }

    private static final JsonNode SCHEMA = SchemaSupport.parse("""
        {
          "type": "object",
          "properties": {
            "pattern":    { "type": "string",  "description": "Regular expression to search for (Java regex). Required." },
            "path":       { "type": "string",  "description": "Directory or single file to search within. Defaults to the workspace root." },
            "glob":       { "type": "string",  "description": "Optional file-name filter, e.g. '*.java' or '*.ts,*.tsx'. Comma-separated." },
            "exclude":    { "type": "string",  "description": "Optional file/dir name filter to skip, e.g. '*Test.java' or 'generated'. Comma-separated." },
            "ignoreCase": { "type": "boolean", "description": "Case-insensitive match (default false)." },
            "maxResults": { "type": "integer", "description": "Max matching lines to return (default 100, hard cap 500)." }
          },
          "required": ["pattern"]
        }
        """);

    @Override public JsonNode parameters() { return SCHEMA; }

    @Override
    public ToolResult execute(String callId, Map<String, Object> params, AgentContext context) {
        String regex = str(params, "pattern");
        if (regex == null || regex.isBlank()) {
            return ToolResult.failure(callId, "grep_search requires a non-empty 'pattern' (regular expression).");
        }
        if (regex.length() > 500) {
            // Audit toolsec P2-3: absurdly long patterns are a complexity smell
            // with no legitimate use in code search.
            return ToolResult.failure(callId,
                "Pattern too long (" + regex.length() + " chars, max 500) — simplify the regular expression.");
        }
        final Pattern pattern;
        try {
            int flags = bool(params, "ignoreCase") ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
            pattern = Pattern.compile(regex, flags);
        } catch (PatternSyntaxException e) {
            return ToolResult.failure(callId, "Invalid regular expression: " + e.getMessage());
        }

        Path root;
        List<Path> roots;
        if (workspaces != null) {
            try {
                root = workspaces.resolve(context != null ? context.userId() : null, str(params, "path"));
            } catch (Workspaces.WorkspaceException e) {
                return ToolResult.failure(callId, e.getMessage());
            }
            roots = workspaces.scope(roots(), context);
        } else {
            root = resolveRoot(str(params, "path"));
            roots = roots();
        }
        if (root == null) {
            return ToolResult.failure(callId, "No search root: 'path' not given and no allowed roots configured.");
        }
        if (!Files.exists(root)) {
            return ToolResult.failure(callId, "Search path does not exist: " + root);
        }
        if (!PathGuard.isAllowed(root.toString(), roots)) {
            return ToolResult.failure(callId, "Search path is outside allowed roots: " + root);
        }

        List<Glob> includeGlobs = Glob.parseMany(str(params, "glob"));
        List<Glob> excludeGlobs = Glob.parseMany(str(params, "exclude"));
        int maxResults = intVal(params, "maxResults", DEFAULT_MAX_RESULTS);
        if (maxResults <= 0) maxResults = DEFAULT_MAX_RESULTS;
        if (maxResults > HARD_MAX_RESULTS) maxResults = HARD_MAX_RESULTS;

        List<Path> targets = new ArrayList<>();
        if (Files.isRegularFile(root)) {
            targets.add(root);
        } else {
            try {
                collect(root, targets, includeGlobs, excludeGlobs, roots);
            } catch (IOException e) {
                return ToolResult.failure(callId, "Failed to walk " + root + ": " + e.getMessage());
            }
        }

        return scanWithTimeout(callId, regex, root, targets, pattern, maxResults);
    }

    /**
     * Runs the per-file scan loop on {@link #MATCH_EXECUTOR} under a wall-clock
     * budget (audit toolsec P2-3). On timeout, whatever matches were already
     * collected are returned with a timeout marker; a pattern that blows the
     * budget can therefore never wedge the caller.
     */
    private ToolResult scanWithTimeout(String callId, String regex, Path root,
                                       List<Path> targets, Pattern pattern, int maxResults) {
        ScanOutcome outcome = new ScanOutcome();
        Thread worker = startScanWorker(
            () -> scanAll(root, targets, pattern, maxResults, outcome));
        try {
            worker.join(matchTimeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            worker.interrupt();
            return ToolResult.failure(callId, "grep_search was interrupted.");
        }
        if (worker.isAlive()) {
            // Budget expired but the worker is still chewing (pathological
            // pattern). interrupt() is best-effort (regex find() ignores it),
            // so mark the timeout and let the daemon thread unwind on its own;
            // the caller is unblocked immediately.
            worker.interrupt();
            outcome.timedOut = true;
        }
        return outcome.toResult(callId, regex, root);
    }

    /** Mutable bucket the worker fills as it scans; read by the caller after join/timeout. */
    private static final class ScanOutcome {
        final StringBuilder out = new StringBuilder();
        int totalMatches;
        int filesWithMatches;
        int filesScanned;
        boolean truncated;
        volatile boolean timedOut;

        ToolResult toResult(String callId, String regex, Path root) {
            if (totalMatches == 0) {
                if (timedOut) {
                    // Nothing came back before the budget expired — almost
                    // certainly a pathological pattern (ReDoS), so say so.
                    return ToolResult.failure(callId,
                        "Search timed out after " + (matchTimeoutMs / 1000) + "s with no matches. "
                        + "The pattern is likely too expensive (catastrophic backtracking); "
                        + "simplify it: /" + regex + "/");
                }
                return ToolResult.success(callId,
                    "No matches for /" + regex + "/ under " + root
                    + " (scanned " + filesScanned + " files).");
            }
            StringBuilder header = new StringBuilder();
            header.append(totalMatches).append(" match").append(totalMatches == 1 ? "" : "es")
                  .append(" in ").append(filesWithMatches).append(" file")
                  .append(filesWithMatches == 1 ? "" : "s")
                  .append(" (scanned ").append(filesScanned).append(" files) for /")
                  .append(regex).append("/")
                  .append(timedOut ? " [match timed out — partial results]" : (truncated ? " [truncated]" : ""))
                  .append("\n");
            return ToolResult.success(callId, header.append(out).toString());
        }
    }

    /** The scan loop itself; extracted so it can run on the bounded worker. */
    private void scanAll(Path root, List<Path> targets, Pattern pattern, int maxResults,
                         ScanOutcome o) {
        String currentFile = null;
        for (Path f : targets) {
            if (o.filesScanned >= MAX_FILES_SCANNED) { o.truncated = true; break; }
            o.filesScanned++;
            List<Integer> hits = new ArrayList<>(); // line numbers
            List<String> lines;
            try {
                lines = scanFile(f, pattern, maxResults - o.totalMatches, hits);
            } catch (IOException e) {
                continue; // binary or unreadable -> skip
            }
            if (hits.isEmpty()) continue;
            o.filesWithMatches++;
            for (int i = 0; i < hits.size(); i++) {
                if (o.totalMatches >= maxResults) { o.truncated = true; break; }
                int lineNo = hits.get(i);
                String text = lines.get(i);
                if (currentFile == null || !currentFile.equals(f.toString())) {
                    currentFile = f.toString();
                    o.out.append("\n").append(display(root, f)).append("\n");
                }
                o.out.append(String.format("%5d: %s%n", lineNo, text));
                o.totalMatches++;
            }
            if (o.truncated) break;
        }
    }

    /* ------------------------------------------------------------ */

    private Path resolveRoot(String given) {
        if (given != null && !given.isBlank()) {
            Path p = Paths.get(given).toAbsolutePath().normalize();
            try { if (Files.isSymbolicLink(p)) p = p.toRealPath(); } catch (IOException ignored) {}
            return p;
        }
        List<Path> roots = roots();
        return roots.isEmpty() ? null : roots.get(0).toAbsolutePath().normalize();
    }

    private void collect(Path root, List<Path> out, List<Glob> includeGlobs, List<Glob> excludeGlobs, List<Path> roots) throws IOException {
        Files.walkFileTree(root, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName().toString();
                if (!dir.equals(root) && (SKIP_DIRS.contains(name) || isExcludedDir(excludeGlobs, name))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                if (attrs.size() > MAX_FILE_BYTES) return FileVisitResult.CONTINUE;
                if (FileSafety.isBinaryName(name)) return FileVisitResult.CONTINUE;
                if (!Glob.matchesAny(includeGlobs, name, true)) return FileVisitResult.CONTINUE;
                if (!Glob.matchesAny(excludeGlobs, name, false)) return FileVisitResult.CONTINUE;
                if (!PathGuard.isAllowed(file.toString(), roots)) return FileVisitResult.CONTINUE;
                out.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean isExcludedDir(List<Glob> excludeGlobs, String dirName) {
        if (excludeGlobs.isEmpty()) return false;
        return excludeGlobs.stream().anyMatch(g -> g.matches(dirName));
    }

    /** Reads a file as UTF-8 and returns matching lines (capped), recording line numbers. */
    private List<String> scanFile(Path file, Pattern pattern, int cap, List<Integer> hitLines) throws IOException {
        List<String> matched = new ArrayList<>();
        int lineNo = 0;
        try (Stream<String> stream = Files.lines(file, StandardCharsets.UTF_8)) {
            Iterator<String> it = stream.iterator();
            while (it.hasNext()) {
                lineNo++;
                String raw = it.next();
                String line = raw.length() > MAX_LINE_LEN ? raw.substring(0, MAX_LINE_LEN) + "…" : raw;
                if (pattern.matcher(raw).find()) {
                    matched.add(line.stripTrailing());
                    hitLines.add(lineNo);
                    if (hitLines.size() >= cap) break;
                }
            }
        }
        return matched;
    }

    private static String display(Path root, Path file) {
        try {
            if (Files.isDirectory(root)) return root.relativize(file).toString().replace('\\', '/');
        } catch (IllegalArgumentException ignored) {}
        return file.getFileName().toString();
    }

    private static String str(Map<String, Object> p, String k) {
        Object v = p.get(k);
        return v == null ? null : String.valueOf(v);
    }
    private static boolean bool(Map<String, Object> p, String k) {
        Object v = p.get(k);
        return v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
    }
    private static int intVal(Map<String, Object> p, String k, int dflt) {
        Object v = p.get(k);
        if (v == null) return dflt;
        try { return Integer.parseInt(String.valueOf(v)); } catch (NumberFormatException e) { return dflt; }
    }

    /** Minimal glob supporting '*' and '?' over a file name. */
    private record Glob(Pattern pattern) {
        static List<Glob> parseMany(String csv) {
            if (csv == null || csv.isBlank()) return List.of();
            List<Glob> out = new ArrayList<>();
            for (String part : csv.split(",")) {
                String g = part.trim();
                if (!g.isEmpty()) out.add(new Glob(compile(g)));
            }
            return out;
        }
        static Pattern compile(String glob) {
            StringBuilder sb = new StringBuilder("(?i)");
            for (char c : glob.toCharArray()) {
                switch (c) {
                    case '*' -> sb.append(".*");
                    case '?' -> sb.append('.');
                    case '.', '\\', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|' -> sb.append('\\').append(c);
                    default -> sb.append(c);
                }
            }
            return Pattern.compile(sb.toString());
        }
        boolean matches(String name) { return pattern.matcher(name).matches(); }
        /** includeWhen=true: empty list means pass; includeWhen=false: empty list means pass. */
        static boolean matchesAny(List<Glob> globs, String name, boolean include) {
            if (globs.isEmpty()) return true;
            boolean any = globs.stream().anyMatch(g -> g.matches(name));
            return include ? any : !any;
        }
    }
}
