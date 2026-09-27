package com.gantang.tianshu.impl.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.config.LiveSettings;
import com.gantang.tianshu.api.tool.Tool;
import com.gantang.tianshu.api.tool.ToolResult;
import com.gantang.tianshu.impl.tool.support.FileSafety;
import com.gantang.tianshu.impl.tool.support.PathGuard;
import com.gantang.tianshu.api.tool.Workspaces;
import com.gantang.tianshu.impl.tool.support.UnifiedDiff;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in tool: targeted search-and-replace file editing.
 *
 * <p>The model supplies an exact {@code old} block and its {@code new} replacement
 * instead of rewriting the whole file (the {@code file_write} pattern burns tokens
 * and risks clobbering unrelated lines). The block must match exactly once unless
 * {@code replaceAll} is set; on zero or ambiguous matches the tool fails with
 * locating context rather than guessing.
 *
 * <p>Returns a unified diff so the console can render red/green lines.
 * Approval-gated (WRITE level), same as {@code file_write}.
 */
public class FileEditTool implements Tool {

    private static final JsonNode SCHEMA = SchemaSupport.parse("""
        {
          "type": "object",
          "properties": {
            "path":       { "type": "string", "description": "Target file path (absolute or relative to workspace)" },
            "old":        { "type": "string", "description": "Exact text to replace. Must match the file contents exactly (including indentation) and be unique unless replaceAll=true." },
            "new":        { "type": "string", "description": "Replacement text (use empty string to delete the block)." },
            "replaceAll": { "type": "boolean", "description": "Replace every occurrence instead of requiring a unique match (default false)." }
          },
          "required": ["path", "old", "new"]
        }
        """);

    private final List<Path> allowedRoots;
    private volatile LiveSettings live;
    private volatile Workspaces workspaces;

    public FileEditTool withLiveSettings(LiveSettings live) { this.live = live; return this; }

    /** Enable per-tenant workspace jailing (P2-3). */
    public FileEditTool withWorkspaces(Workspaces workspaces) { this.workspaces = workspaces; return this; }

    private List<Path> roots() {
        LiveSettings ls = this.live;
        return ls != null ? ls.snapshot().fileAllowedRoots() : allowedRoots;
    }

    /** Effective allowlist for this call: tenant root (optionally + shared roots). */
    private List<Path> roots(AgentContext ctx) {
        return workspaces != null ? workspaces.scope(roots(), ctx) : roots();
    }

    public FileEditTool() { this(List.of()); }

    public FileEditTool(List<Path> allowedRoots) {
        this.allowedRoots = allowedRoots != null ? List.copyOf(allowedRoots) : List.of();
    }

    @Override public String name()        { return "file_edit"; }
    @Override public String description() {
        return "Edit a file by replacing an exact text block (search-and-replace). "
            + "Provide the precise 'old' text (copy it verbatim, including indentation) and the 'new' text. "
            + "The block must appear exactly once; if it matches zero or multiple times the call fails with "
            + "line numbers / surrounding context so you can add more distinguishing context. "
            + "Prefer this over file_write for changing part of an existing file. Returns a unified diff.";
    }
    @Override public JsonNode parameters()     { return SCHEMA; }
    @Override public String  group()           { return "builtin"; }
    @Override public boolean requiresApproval(){ return true; }

    @Override
    public ToolResult execute(String callId, Map<String, Object> params, AgentContext context) {
        String pathStr = String.valueOf(params.getOrDefault("path", "")).trim();
        if (pathStr.isEmpty()) return ToolResult.failure(callId, "path is required");
        String oldText = params.get("old") == null ? null : String.valueOf(params.get("old"));
        String newText = params.get("new") == null ? "" : String.valueOf(params.get("new"));
        if (oldText == null || oldText.isEmpty()) {
            return ToolResult.failure(callId, "old is required and must be non-empty (use file_write to create files)");
        }
        boolean replaceAll = Boolean.TRUE.equals(params.get("replaceAll"));

        Path target;
        if (workspaces != null) {
            try {
                target = workspaces.resolve(context != null ? context.userId() : null, pathStr);
            } catch (Workspaces.WorkspaceException e) {
                return ToolResult.failure(callId, e.getMessage());
            }
        } else {
            try {
                target = Paths.get(pathStr).toAbsolutePath().normalize();
            } catch (InvalidPathException e) {
                return ToolResult.failure(callId, "Invalid path: " + e.getMessage());
            }
        }
        if (!PathGuard.isAllowed(target.toString(), roots(context))) {
            return ToolResult.failure(callId, "Path is outside allowed roots (symlink-safe check): " + target);
        }
        if (!Files.isRegularFile(target)) {
            return ToolResult.failure(callId, "File does not exist (file_edit only modifies existing files; use file_write to create): " + target);
        }
        String safety = FileSafety.checkEdit(target);
        if (safety != null) {
            return ToolResult.failure(callId, safety);
        }
        try {
            if (Files.size(target) > FileSafety.MAX_EDIT_BYTES) {
                return ToolResult.failure(callId, "File too large for targeted edit (" + Files.size(target)
                    + " bytes > " + FileSafety.MAX_EDIT_BYTES + "); use file_write or edit a smaller file");
            }
        } catch (IOException e) {
            return ToolResult.failure(callId, "Cannot stat file: " + e.getMessage());
        }

        String original;
        try {
            original = Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return ToolResult.failure(callId, "Read error: " + e.getMessage());
        }

        List<Integer> matchLines = lineNumbersOf(original, oldText);
        if (matchLines.isEmpty()) {
            return ToolResult.failure(callId, "No match found for the 'old' block. It must match verbatim "
                + "(check whitespace/indentation).\n" + locateHint(original));
        }
        if (matchLines.size() > 1 && !replaceAll) {
            return ToolResult.failure(callId, "The 'old' block matches " + matchLines.size()
                + " times at starting lines " + matchLines
                + ". Add more surrounding context to make it unique, or set replaceAll=true to replace all.");
        }
        if (oldText.equals(newText)) {
            return ToolResult.failure(callId, "'old' and 'new' are identical; nothing to change");
        }

        String updated = replaceAll
            ? original.replace(oldText, newText)
            : replaceFirst(original, oldText, newText);

        String diff = UnifiedDiff.of(target.getFileName().toString(), original, updated, 3);

        try {
            Files.writeString(target, updated, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return ToolResult.failure(callId, "Write error: " + e.getMessage());
        }

        Map<String, Object> meta = new HashMap<>();
        meta.put("path", target.toString());
        meta.put("replacements", matchLines.size());
        meta.put("diff", diff);

        String summary = "Edited " + target + " (" + (replaceAll ? matchLines.size() : 1)
            + " replacement(s)).\n\n" + diff;
        return ToolResult.success(callId, summary, meta);
    }

    /** Replace only the first occurrence (JDK String.replace replaces all). */
    private static String replaceFirst(String text, String find, String repl) {
        int idx = text.indexOf(find);
        if (idx < 0) return text;
        return text.substring(0, idx) + repl + text.substring(idx + find.length());
    }

    /** 1-based line numbers where {@code block} begins in {@code content}. */
    private static List<Integer> lineNumbersOf(String content, String block) {
        List<Integer> lines = new ArrayList<>();
        int from = 0;
        while (true) {
            int idx = content.indexOf(block, from);
            if (idx < 0) break;
            long line = content.substring(0, idx).chars().filter(c -> c == '\n').count() + 1;
            lines.add((int) line);
            from = idx + Math.max(1, block.length());
        }
        return lines;
    }

    /** A few numbered lines around the end of the file to help the model re-anchor. */
    private static String locateHint(String content) {
        String[] lines = content.replace("\r\n", "\n").split("\n");
        int start = Math.max(0, lines.length - 12);
        StringBuilder sb = new StringBuilder("Last lines of the file (line: content):\n");
        for (int k = start; k < lines.length; k++) {
            String l = lines[k];
            if (l.length() > 120) l = l.substring(0, 120) + "…";
            sb.append(k + 1).append(": ").append(l).append('\n');
        }
        return sb.toString();
    }
}
