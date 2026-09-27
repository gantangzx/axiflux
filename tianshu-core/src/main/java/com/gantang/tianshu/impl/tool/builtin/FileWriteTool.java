package com.gantang.tianshu.impl.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.config.LiveSettings;
import com.gantang.tianshu.api.tool.Tool;
import com.gantang.tianshu.api.tool.ToolResult;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.gantang.tianshu.impl.tool.support.FileSafety;
import com.gantang.tianshu.impl.tool.support.PathGuard;
import com.gantang.tianshu.api.tool.Workspaces;

/**
 * Built-in tool: write text file (requires human approval).
 * <p>
 * By design {@link #requiresApproval()} is {@code true}. The Agent runtime must
 * gate this tool with a user-facing approval step before {@link #execute}.
 */
public class FileWriteTool implements Tool {

    private static final JsonNode SCHEMA = SchemaSupport.parse("""
        {
          "type": "object",
          "properties": {
            "path":    { "type": "string", "description": "Target file path" },
            "content": { "type": "string", "description": "Content to write" },
            "mode":    { "type": "string", "enum": ["overwrite", "append", "create_new"], "description": "Write mode (default overwrite)" },
            "charset": { "type": "string", "description": "Text encoding (default UTF-8)" }
          },
          "required": ["path", "content"]
        }
        """);

    private final List<Path> allowedRoots;
    private volatile LiveSettings live;
    private volatile Workspaces workspaces;

    /** Wire live settings so file-allowed-roots changes apply without restart. */
    public FileWriteTool withLiveSettings(LiveSettings live) { this.live = live; return this; }

    /** Enable per-tenant workspace jailing (P2-3). */
    public FileWriteTool withWorkspaces(Workspaces workspaces) { this.workspaces = workspaces; return this; }

    private List<Path> roots() {
        LiveSettings ls = this.live;
        return ls != null ? ls.snapshot().fileAllowedRoots() : allowedRoots;
    }

    /** Effective allowlist for this call: tenant root (optionally + shared roots). */
    private List<Path> roots(AgentContext ctx) {
        return workspaces != null ? workspaces.scope(roots(), ctx) : roots();
    }

    public FileWriteTool() { this(List.of()); }

    public FileWriteTool(List<Path> allowedRoots) {
        this.allowedRoots = allowedRoots != null ? List.copyOf(allowedRoots) : List.of();
    }

    @Override public String name()             { return "file_write"; }
    @Override public String description()      { return "Write text content to a file (requires user approval)."; }
    @Override public JsonNode parameters()     { return SCHEMA; }
    @Override public String  group()           { return "builtin"; }
    @Override public boolean requiresApproval(){ return true; }

    @Override
    public ToolResult execute(String callId, Map<String, Object> params, AgentContext context) {
        String pathStr = String.valueOf(params.getOrDefault("path", "")).trim();
        if (pathStr.isEmpty()) return ToolResult.failure(callId, "path is required");

        Object contentObj = params.get("content");
        if (contentObj == null) return ToolResult.failure(callId, "content is required");
        String content = String.valueOf(contentObj);

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
        String safety = FileSafety.checkWrite(target);
        if (safety != null) {
            return ToolResult.failure(callId, safety);
        }
        if (content.length() > FileSafety.MAX_WRITE_BYTES) {
            return ToolResult.failure(callId, "Content too large: " + content.length()
                + " chars > " + FileSafety.MAX_WRITE_BYTES + " byte cap for a single file_write");
        }

        String mode = String.valueOf(params.getOrDefault("mode", "overwrite"));
        Charset cs = charsetOf(String.valueOf(params.getOrDefault("charset", "UTF-8")));

        try {
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            OpenOption[] opts = switch (mode) {
                case "append"     -> new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND};
                case "create_new" -> new OpenOption[]{StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE};
                case "overwrite"  -> new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE};
                default -> {
                    yield null;
                }
            };
            if (opts == null) return ToolResult.failure(callId, "Unknown mode: " + mode);
            Files.writeString(target, content, cs, opts);

            Map<String, Object> meta = new HashMap<>();
            meta.put("path", target.toString());
            meta.put("mode", mode);
            meta.put("bytesWritten", content.getBytes(cs).length);
            return ToolResult.success(callId, "Wrote " + meta.get("bytesWritten") + " bytes to " + target, meta);
        } catch (FileAlreadyExistsException e) {
            return ToolResult.failure(callId, "File already exists (mode=create_new): " + target);
        } catch (IOException e) {
            return ToolResult.failure(callId, "Write error: " + e.getMessage());
        }
    }

    private static Charset charsetOf(String name) {
        try { return Charset.forName(name); }
        catch (Exception e) { return StandardCharsets.UTF_8; }
    }
}
