package com.gantang.reaxon.impl.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.codeindex.CodeIndexStore;
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
import java.security.MessageDigest;
import java.util.*;

/**
 * {@code codebase_search}: semantic code search over an embedded source index.
 *
 * <p>Complementary to {@link GrepSearchTool} (regex/exact match): this tool
 * embeds a natural-language query and ranks code chunks by meaning, so it finds
 * implementations by intent ("where do we handle auth failures?") even when no
 * shared identifier exists.
 *
 * <ul>
 *   <li>{@code action=index}  — walk {@code path}, chunk source files, embed and
 *       upsert changed files (incremental by content hash; {@code force=true}
 *       rebuilds everything). Skips VCS/build/dependency dirs, binaries and
 *       secret files.</li>
 *   <li>{@code action=search} — embed {@code query} and return the top-K chunks
 *       with file:line and a snippet. {@code path} scopes to one indexed root;
 *       omitted searches across all indexed roots.</li>
 *   <li>{@code action=status} — report indexed file/chunk counts.</li>
 * </ul>
 *
 * <p>Requires a vector backend ({@code axiflux.vector.provider=pgvector}); when
 * no {@link CodeIndexStore} is wired the tool reports the gap instead of failing
 * the turn. Read-only with respect to the user's files (indexing only reads
 * source and writes to the vector store), so it is READ level and never gated.
 *
 * <p>In multi-tenant deployments the index/search paths are scoped to the
 * caller's tenant workspace the same way {@link GrepSearchTool} is (toolsec
 * P2-7): without that, a global {@code file-allowed-roots} spanning several
 * tenants would let one tenant index and search another tenant's code.
 */
public final class CodebaseSearchTool implements Tool {

    private final List<Path> allowedRoots;
    private final CodeIndexStore store;
    private volatile LiveSettings live;
    private volatile Workspaces workspaces;

    public CodebaseSearchTool() { this(List.of(), null); }
    public CodebaseSearchTool(List<Path> allowedRoots, CodeIndexStore store) {
        this.allowedRoots = allowedRoots == null ? List.of() : allowedRoots;
        this.store = store;
    }

    public CodebaseSearchTool withLiveSettings(LiveSettings live) { this.live = live; return this; }

    /** Enable per-tenant workspace jailing (same shape as {@link GrepSearchTool#withWorkspaces}). */
    public CodebaseSearchTool withWorkspaces(Workspaces workspaces) { this.workspaces = workspaces; return this; }

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

    private static final Set<String> SKIP_DIRS = Set.of(
        ".git", ".svn", ".hg", "target", "build", "dist", "out", "bin", "obj",
        "node_modules", ".idea", ".gradle", ".mvn", "__pycache__", ".venv", "venv",
        ".next", ".nuxt", "coverage", ".cache");

    /** Source/config/text extensions worth indexing (everything else is ignored unless extensionless & known). */
    private static final Set<String> INDEX_EXT = Set.of(
        "java", "kt", "kts", "groovy", "gradle", "scala", "clj",
        "py", "rb", "php", "swift", "m", "mm", "go", "rs",
        "c", "h", "cpp", "cc", "cxx", "hpp", "hh", "cs",
        "js", "jsx", "mjs", "cjs", "ts", "tsx", "vue", "svelte",
        "sql", "sh", "bash", "zsh", "bat", "cmd", "ps1", "dockerfile",
        "yaml", "yml", "json", "json5", "toml", "ini", "conf", "cfg", "properties", "env-example",
        "xml", "html", "htm", "css", "scss", "less",
        "md", "mdx", "txt", "rst", "adoc", "csv", "proto", "graphql", "gql");

    /** Extensionless files that are still source. */
    private static final Set<String> INDEX_NAMES = Set.of(
        "Dockerfile", "Jenkinsfile", "Makefile", "CMakeLists.txt", "Rakefile",
        "Gemfile", "Procfile", "LICENSE", "README", "CHANGELOG", ".gitignore",
        ".gitattributes", ".editorconfig", ".bashrc", ".zshrc");

    private static final long MAX_FILE_BYTES = 512L * 1024; // don't embed huge/minified files
    private static final int CHUNK_TARGET_CHARS = 3200;   // ~ keep each embedding focused
    private static final int CHUNK_MAX_LINES = 80;
    private static final int CHUNK_OVERLAP_LINES = 8;
    private static final int MAX_FILES = 20_000;
    private static final int SNIPPET_CHARS = 480;

    @Override public String name() { return "codebase_search"; }
    @Override public String group() { return "builtin"; }
    @Override public String description() {
        return "Semantic code search over an embedded index of the codebase. Finds code by MEANING "
            + "(e.g. 'where do we handle authentication failures', 'retry logic for HTTP calls') "
            + "unlike grep_search which matches literal text/regex. Workflow: call action='index' on "
            + "the project root first (or after big changes; it incrementally re-embeds only changed "
            + "files), then action='search' with a natural-language query. Returns file:line locations "
            + "with snippets. Read-only; skips .git/build/node_modules, binaries and secret files.";
    }

    private static final JsonNode SCHEMA = SchemaSupport.parse("""
        {
          "type": "object",
          "properties": {
            "action": { "type": "string", "enum": ["index", "search", "status"],
                        "description": "index=build/refresh the embedding index for a project root; search=semantic query; status=show index size." },
            "path":   { "type": "string",
                        "description": "Project root directory for index/status, or scope for search. Required for index; optional for search (omit to search all indexed roots)." },
            "query":  { "type": "string", "description": "Natural-language description of what to find (search only)." },
            "topK":   { "type": "integer", "description": "Number of hits for search (default 8, max 25)." },
            "force":  { "type": "boolean", "description": "index: re-embed every file even if unchanged (default false)." }
          },
          "required": ["action"]
        }
        """);

    @Override public JsonNode parameters() { return SCHEMA; }

    @Override
    public ToolResult execute(String callId, Map<String, Object> params, AgentContext context) {
        String action = str(params, "action", "search").toLowerCase(Locale.ROOT).trim();
        if (store == null || !store.available()) {
            return ToolResult.failure(callId, "Code index is unavailable: no vector backend configured. "
                + "Set axiflux.vector.provider=pgvector (with embed-url/embed-api-key) and restart.");
        }
        return switch (action) {
            case "index"  -> doIndex(callId, params, context);
            case "status" -> doStatus(callId, params, context);
            case "search" -> doSearch(callId, params, context);
            default -> ToolResult.failure(callId, "Unknown action '" + action + "'. Use index | search | status.");
        };
    }

    /* ------------------------------------------------------------ index */

    private ToolResult doIndex(String callId, Map<String, Object> params, AgentContext context) {
        List<Path> roots = roots(context);
        String given = str(params, "path", null);
        Path root = resolveRoot(given, roots);
        if (root == null) {
            return ToolResult.failure(callId, "index requires a 'path' (project root directory).");
        }
        if (!Files.isDirectory(root)) {
            return ToolResult.failure(callId, "Index path is not a directory: " + root);
        }
        if (!PathGuard.isAllowed(root.toString(), roots)) {
            return ToolResult.failure(callId, "Index path is outside allowed roots: " + root);
        }
        boolean force = bool(params, "force", false);
        String rootKey = root.toString();

        Map<String, String> existing = force ? Map.of() : store.fileHashes(rootKey);

        List<Path> files = new ArrayList<>();
        try {
            collectIndexable(root, files, roots);
        } catch (IOException e) {
            return ToolResult.failure(callId, "Failed to walk " + root + ": " + e.getMessage());
        }

        int indexed = 0, unchanged = 0, failed = 0, chunksTotal = 0;
        List<String> seen = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (Path f : files) {
            if (seen.size() >= MAX_FILES) break;
            String rel = root.relativize(f).toString().replace('\\', '/');
            seen.add(rel);
            String lang = langOf(rel);
            try {
                byte[] raw = Files.readAllBytes(f);
                String hash = sha256(raw);
                if (!force && hash.equals(existing.get(rel))) { unchanged++; continue; }
                String text = new String(raw, StandardCharsets.UTF_8);
                List<CodeIndexStore.Chunk> chunks = chunk(text);
                if (chunks.isEmpty()) continue;
                store.upsertFile(rootKey, rel, lang, hash, chunks);
                indexed++;
                chunksTotal += chunks.size();
            } catch (Exception e) {
                failed++;
                if (failures.size() < 5) failures.add(rel + ": " + e.getMessage());
            }
        }

        // Remove chunks for files that disappeared.
        List<String> deleted = new ArrayList<>();
        for (String oldRel : existing.keySet()) {
            if (!seen.contains(oldRel)) deleted.add(oldRel);
        }
        if (!deleted.isEmpty()) store.deleteFiles(rootKey, deleted);

        StringBuilder sb = new StringBuilder();
        sb.append("Indexed ").append(root).append("\n")
          .append("  files embedded (new/changed): ").append(indexed).append("\n")
          .append("  files unchanged: ").append(unchanged).append("\n")
          .append("  chunks embedded: ").append(chunksTotal).append("\n")
          .append("  files removed from index: ").append(deleted.size()).append("\n");
        if (failed > 0) {
            sb.append("  files failed: ").append(failed).append("\n");
            failures.forEach(m -> sb.append("    - ").append(m).append("\n"));
        }
        sb.append("Use action='search' to query the index.");
        return ToolResult.success(callId, sb.toString());
    }

    private void collectIndexable(Path root, List<Path> out, List<Path> roots) throws IOException {
        Files.walkFileTree(root, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName().toString();
                if (!dir.equals(root) && SKIP_DIRS.contains(name)) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                if (attrs.size() > MAX_FILE_BYTES) return FileVisitResult.CONTINUE;
                if (FileSafety.isBinaryName(name) || FileSafety.isSensitiveName(name)) return FileVisitResult.CONTINUE;
                if (!isIndexableName(name)) return FileVisitResult.CONTINUE;
                if (!PathGuard.isAllowed(file.toString(), roots)) return FileVisitResult.CONTINUE;
                out.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean isIndexableName(String name) {
        if (INDEX_NAMES.contains(name)) return true;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        return INDEX_EXT.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /* ------------------------------------------------------------ search */

    private ToolResult doSearch(String callId, Map<String, Object> params, AgentContext context) {
        String query = str(params, "query", null);
        if (query == null || query.isBlank()) {
            return ToolResult.failure(callId, "search requires a 'query' (natural-language description).");
        }
        String given = str(params, "path", null);
        String rootKey = null;
        if (given != null && !given.isBlank()) {
            Path root = resolveRoot(given, roots(context));
            if (root == null || !Files.isDirectory(root)) {
                return ToolResult.failure(callId, "Search path is not a directory: " + given);
            }
            rootKey = root.toString();
        }
        int topK = intVal(params, "topK", 8);
        if (topK <= 0) topK = 8;
        if (topK > 25) topK = 25;

        List<CodeIndexStore.CodeHit> hits = store.search(rootKey, query, topK);
        if (hits.isEmpty()) {
            return ToolResult.success(callId, "No semantic matches"
                + (rootKey != null ? " under " + rootKey : "")
                + ". If you haven't indexed yet (or the embedding endpoint is down), run action='index' first.");
        }
        StringBuilder sb = new StringBuilder();
        sb.append(hits.size()).append(" semantic match").append(hits.size() == 1 ? "" : "es")
          .append(" for: ").append(query).append("\n");
        String lastPath = null;
        for (CodeIndexStore.CodeHit h : hits) {
            if (!h.relPath().equals(lastPath)) {
                sb.append("\n").append(h.relPath()).append("\n");
                lastPath = h.relPath();
            }
            String snip = h.snippet() == null ? "" : h.snippet().strip();
            snip = snippetWindow(snip);
            sb.append(String.format("  L%d-%d (%.2f): %s%n",
                h.startLine(), h.endLine(), h.score(), firstLine(snip)));
        }
        sb.append("\nUse file_read with the path and offset to open the full context.");
        return ToolResult.success(callId, sb.toString());
    }

    private static String snippetWindow(String s) {
        if (s.length() <= SNIPPET_CHARS) return s;
        return s.substring(0, SNIPPET_CHARS) + "…";
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        String first = nl < 0 ? s : s.substring(0, nl);
        return first.length() > 160 ? first.substring(0, 160) + "…" : first;
    }

    /* ------------------------------------------------------------ status */

    private ToolResult doStatus(String callId, Map<String, Object> params, AgentContext context) {
        String given = str(params, "path", null);
        String rootKey = null;
        if (given != null && !given.isBlank()) {
            Path root = resolveRoot(given, roots(context));
            rootKey = root == null ? given : root.toString();
        }
        CodeIndexStore.IndexStats st = store.stats(rootKey);
        return ToolResult.success(callId, "Code index"
            + (rootKey != null ? " for " + rootKey : " (all roots)") + ":\n"
            + "  indexed files: " + st.files() + "\n"
            + "  indexed chunks: " + st.chunks() + "\n"
            + (st.files() == 0 ? "Run action='index' with a project path to build it.\n" : ""));
    }

    /* ------------------------------------------------------------ helpers */

    /**
     * Resolve an index/search root against the (possibly tenant-scoped) allowlist.
     * A blank path falls back to the first effective root — the tenant root in
     * multi-tenant mode, never an unscoped global root (toolsec P2-7).
     */
    private Path resolveRoot(String given, List<Path> roots) {
        if (given != null && !given.isBlank()) {
            Path p = Paths.get(given).toAbsolutePath().normalize();
            try { if (Files.isSymbolicLink(p)) p = p.toRealPath(); } catch (IOException ignored) {}
            return p;
        }
        return roots.isEmpty() ? null : roots.get(0).toAbsolutePath().normalize();
    }

    /** Split source into line-ranged, overlapping windows bounded by chars or lines. */
    static List<CodeIndexStore.Chunk> chunk(String text) {
        String[] raw = text.split("\n", -1);
        // A trailing newline produces a spurious empty segment; drop it so line ranges stay real.
        int n = raw.length;
        while (n > 0 && raw[n - 1].isEmpty()) n--;
        String[] lines = raw;
        List<CodeIndexStore.Chunk> chunks = new ArrayList<>();
        int i = 0;
        int idx = 0;
        while (i < n) {
            StringBuilder buf = new StringBuilder();
            int start = i;
            int count = 0;
            while (i < n && count < CHUNK_MAX_LINES && buf.length() < CHUNK_TARGET_CHARS) {
                buf.append(lines[i]).append('\n');
                i++;
                count++;
            }
            String content = buf.toString().strip();
            if (!content.isBlank()) {
                chunks.add(new CodeIndexStore.Chunk(idx, start + 1, i, content));
                idx++;
            }
            if (i >= n) break;
            // Step back by overlap to preserve cross-boundary context.
            int back = Math.min(CHUNK_OVERLAP_LINES, count);
            i = Math.max(i - back, start + 1);
        }
        return chunks;
    }

    private static String langOf(String relPath) {
        int dot = relPath.lastIndexOf('.');
        if (dot < 0) {
            String base = relPath.contains("/") ? relPath.substring(relPath.lastIndexOf('/') + 1) : relPath;
            if (base.equalsIgnoreCase("Dockerfile")) return "dockerfile";
            if (base.equals("Makefile")) return "make";
            return "text";
        }
        return relPath.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(Arrays.hashCode(data));
        }
    }

    private static String str(Map<String, Object> p, String k, String def) {
        Object v = p.get(k);
        if (v == null) return def;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }
    private static int intVal(Map<String, Object> p, String k, int def) {
        Object v = p.get(k);
        if (v == null) return def;
        try { return (int) Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return def; }
    }
    private static boolean bool(Map<String, Object> p, String k, boolean def) {
        Object v = p.get(k);
        if (v == null) return def;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        return s.isBlank() ? def : (s.equals("true") || s.equals("1") || s.equals("yes"));
    }
}
