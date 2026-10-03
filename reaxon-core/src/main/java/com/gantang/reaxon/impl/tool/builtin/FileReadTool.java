package com.gantang.reaxon.impl.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.config.LiveSettings;
import com.gantang.reaxon.api.tool.Tool;
import com.gantang.reaxon.api.tool.ToolResult;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.gantang.reaxon.impl.tool.support.FileSafety;
import com.gantang.reaxon.impl.tool.support.PathGuard;
import com.gantang.reaxon.api.tool.Workspaces;

/**
 * Built-in tool: read text file with size guard.
 * <p>
 * Optional path allowlist restricts the roots the tool is permitted to read from.
 * When no allowlist is configured the tool falls back to the current working directory.
 */
public class FileReadTool implements Tool {

    private static final JsonNode SCHEMA = SchemaSupport.parse("""
        {
          "type": "object",
          "properties": {
            "path":         { "type": "string", "description": "File path (absolute or relative to workspace)" },
            "charset":      { "type": "string", "description": "Text encoding (default UTF-8)" },
            "maxBytes":     { "type": "integer", "description": "Max bytes to return (default 32768)" },
            "offset":       { "type": "integer", "description": "Byte offset to start reading (default 0)" }
          },
          "required": ["path"]
        }
        """);

    private final List<Path> allowedRoots;
    private final int defaultMaxBytes;
    private volatile LiveSettings live;
    private volatile Workspaces workspaces;

    /** Wire live settings so file-allowed-roots changes apply without restart. */
    public FileReadTool withLiveSettings(LiveSettings live) { this.live = live; return this; }

    /** Enable per-tenant workspace jailing (P2-3). */
    public FileReadTool withWorkspaces(Workspaces workspaces) { this.workspaces = workspaces; return this; }

    private List<Path> roots() {
        LiveSettings ls = this.live;
        return ls != null ? ls.snapshot().fileAllowedRoots() : allowedRoots;
    }

    /** Effective allowlist for this call: tenant root (optionally + shared roots). */
    private List<Path> roots(AgentContext ctx) {
        return workspaces != null ? workspaces.scope(roots(), ctx) : roots();
    }

    public FileReadTool() { this(List.of(), 32768); }

    public FileReadTool(List<Path> allowedRoots, int defaultMaxBytes) {
        this.allowedRoots = allowedRoots != null ? List.copyOf(allowedRoots) : List.of();
        this.defaultMaxBytes = defaultMaxBytes;
    }

    @Override public String name()        { return "file_read"; }
    @Override public String description() { return "Read a text file and return its contents (bounded)."; }
    @Override public JsonNode parameters(){ return SCHEMA; }
    @Override public String group()       { return "builtin"; }

    @Override
    public ToolResult execute(String callId, Map<String, Object> params, AgentContext context) {
        String pathStr = String.valueOf(params.getOrDefault("path", "")).trim();
        if (pathStr.isEmpty()) return ToolResult.failure(callId, "path is required");

        Path target;
        if (workspaces != null) {
            // Relative paths anchor at <baseRoot>/<userId>/; everything is symlink-jailed.
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
        if (!Files.exists(target)) {
            return ToolResult.failure(callId, "File not found: " + target);
        }
        if (Files.isDirectory(target)) {
            return ToolResult.failure(callId, "Path is a directory: " + target);
        }
        String safety = FileSafety.checkRead(target);
        if (safety != null) {
            return ToolResult.failure(callId, safety);
        }

        int requested = Math.max(intOr(params, "maxBytes", defaultMaxBytes), 1);
        int maxBytes = (int) Math.min(requested, FileSafety.MAX_READ_BYTES);
        int offset = Math.max(intOr(params, "offset", 0), 0);
        Charset cs = charsetOf(String.valueOf(params.getOrDefault("charset", "UTF-8")));

        try {
            long size = Files.size(target);
            byte[] slice;
            int start;
            try (java.io.InputStream in = Files.newInputStream(target)) {
                long skipped = 0;
                while (skipped < offset) {
                    long s = in.skip(offset - skipped);
                    if (s <= 0) break;
                    skipped += s;
                }
                start = (int) skipped;
                long want = Math.min(maxBytes, Math.max(size - start, 0));
                slice = in.readNBytes((int) want);
            }
            String content = new String(slice, cs);
            boolean truncated = (long) start + slice.length < size;
            if (truncated) content += "\n... [truncated, total=" + size + " bytes]";

            Map<String, Object> meta = new HashMap<>();
            meta.put("path", target.toString());
            meta.put("size", size);
            meta.put("offset", start);
            meta.put("bytesReturned", slice.length);
            meta.put("truncated", truncated);
            return ToolResult.success(callId, content, meta);
        } catch (IOException e) {
            return ToolResult.failure(callId, "Read error: " + e.getMessage());
        }
    }

    private static Charset charsetOf(String name) {
        try { return Charset.forName(name); }
        catch (Exception e) { return StandardCharsets.UTF_8; }
    }

    private static int intOr(Map<String, Object> p, String key, int fallback) {
        Object v = p.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }
}
