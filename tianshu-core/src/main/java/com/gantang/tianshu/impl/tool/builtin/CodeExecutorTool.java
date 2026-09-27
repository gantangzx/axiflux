package com.gantang.tianshu.impl.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.tool.Tool;
import com.gantang.tianshu.api.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import com.gantang.tianshu.api.tool.policy.RiskLevel;
import com.gantang.tianshu.api.tool.CommandSandbox;
import com.gantang.tianshu.api.config.LiveSettings;
import com.gantang.tianshu.api.tool.Workspaces;
import com.gantang.tianshu.impl.tool.support.LocalCommandSandbox;
import com.gantang.tianshu.impl.tool.support.PathGuard;

/**
 * Execute a shell command or script and return stdout/stderr.
 *
 * Safety:
 * - Requires explicit approval (requiresApproval = true)
 * - Timeout enforced (default 30s, max 120s)
 * - Working directory defaults to system temp
 * - Output truncated to maxBytes (default 16384)
 */
public class CodeExecutorTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CodeExecutorTool.class);

    private static final JsonNode SCHEMA = SchemaSupport.parse("""
        {
          "type": "object",
          "properties": {
            "command": { "type": "string", "description": "The COMPLETE shell command line to execute: on Windows it is passed verbatim to 'cmd.exe /c', on POSIX to 'sh -c'. Shell metacharacters (&, |, >, ;, $(), backticks) ARE interpreted by the shell — this is the tool's purpose, not a bug. The human approver must review the ENTIRE line as one unit: a benign-looking prefix does not make a chained second command safe (e.g. 'dir & del x' runs both). Never approve a command you have not read to the last character." },
            "workingDir": { "type": "string", "description": "Working directory (default: system temp). Must resolve inside the configured file allowed-roots (or the caller's workspace root in multi-tenant mode); anything else is rejected before a process is spawned." },
            "timeoutSeconds": { "type": "integer", "description": "Timeout in seconds (default 30, max 120)" },
            "maxOutputBytes": { "type": "integer", "description": "Max stdout+stderr bytes (default 16384)" }
          },
          "required": ["command"]
        }
        """);

    @Override public String name()        { return "code_executor"; }
    @Override public String description() {
        return "Execute a COMPLETE shell command line (cmd.exe /c on Windows, sh -c on POSIX) "
            + "and return stdout/stderr. DESTRUCTIVE risk, requires explicit human approval. "
            + "Shell metacharacters (&, |, >, ;) are interpreted — approvers must read the whole "
            + "command line, including anything chained after separators. workingDir is jailed to "
            + "the file allowed-roots.";
    }
    @Override public JsonNode parameters(){ return SCHEMA; }
    @Override public String group()       { return "builtin"; }
    @Override public RiskLevel riskLevel() { return RiskLevel.DESTRUCTIVE; }
    @Override public boolean requiresApproval() { return true; }

    private final CommandSandbox sandbox;
    /** Optional per-tenant workspace jail; non-null forces cwd inside the caller's root. */
    private final Workspaces workspaces;
    /** Allowlist roots for the non-workspace (single-tenant) workingDir override. */
    private final java.util.List<Path> allowedRoots;
    private volatile LiveSettings live;

    public CodeExecutorTool() {
        this(new LocalCommandSandbox(), null);
    }

    public CodeExecutorTool(CommandSandbox sandbox) {
        this(sandbox, null);
    }

    public CodeExecutorTool(CommandSandbox sandbox, Workspaces workspaces) {
        this(sandbox, workspaces, null);
    }

    public CodeExecutorTool(CommandSandbox sandbox, Workspaces workspaces,
                            java.util.List<Path> allowedRoots) {
        this.sandbox = (sandbox != null) ? sandbox : new LocalCommandSandbox();
        this.workspaces = workspaces;
        this.allowedRoots = allowedRoots == null ? java.util.List.of() : java.util.List.copyOf(allowedRoots);
    }

    /** Wire live settings so fileAllowedRoots hot-reloads without restart. */
    public CodeExecutorTool withLiveSettings(LiveSettings live) { this.live = live; return this; }

    private java.util.List<Path> effectiveRoots() {
        LiveSettings ls = this.live;
        if (ls != null && ls.snapshot() != null && ls.snapshot().fileAllowedRoots() != null
            && !ls.snapshot().fileAllowedRoots().isEmpty()) {
            return ls.snapshot().fileAllowedRoots();
        }
        return allowedRoots;
    }

    @Override
    public ToolResult execute(String callId, Map<String, Object> params, AgentContext context) {
        String command = String.valueOf(params.getOrDefault("command", ""));
        if (command.isBlank()) return ToolResult.failure(callId, "command is required");

        int timeoutSec = clamp(intOr(params, "timeoutSeconds", 30), 1, 120);
        int maxBytes = intOr(params, "maxOutputBytes", 16384);

        Path workDir;
        Path mountRoot = null;
        String containerWorkDir = null;
        String wd = params.get("workingDir") != null ? String.valueOf(params.get("workingDir")) : "";
        if (workspaces != null) {
            // Multi-tenant mode: every command is jailed to <baseRoot>/<userId>/,
            // regardless of what workingDir the model asks for. Reject before any
            // process is spawned so a bad path never reaches the shell.
            String userId = context != null ? context.userId() : null;
            try {
                workDir = wd.isBlank()
                    ? workspaces.rootFor(userId)
                    : workspaces.resolve(userId, wd);
                Files.createDirectories(workDir);
                mountRoot = workspaces.rootFor(userId);
                containerWorkDir = workspaces.relativeInContainer(userId, workDir);
            } catch (Workspaces.WorkspaceException e) {
                return ToolResult.failure(callId, e.getMessage());
            } catch (java.io.IOException e) {
                return ToolResult.failure(callId, "Cannot create workspace directory: " + e.getMessage());
            }
        } else if (!wd.isBlank()) {
            // Non-workspace (single-tenant) mode: the model may pass any absolute
            // workingDir (/etc, C:\Windows\System32, /root/.ssh). It is NOT enough to
            // check existence — gate it through the same fileAllowedRoots allowlist the
            // file tools use. With no roots configured the override is refused and we
            // fall back to the system temp dir below (PathGuard denies on empty roots).
            if (!PathGuard.isAllowed(wd, effectiveRoots())) {
                return ToolResult.failure(callId,
                    "workingDir is outside the allowed file roots: " + wd
                    + " (configure tools.file-allowed-roots to permit it, or omit workingDir to use the default temp dir)");
            }
            workDir = Path.of(wd);
            if (!Files.isDirectory(workDir)) {
                return ToolResult.failure(callId, "workingDir does not exist or is not a directory: " + wd);
            }
        } else {
            workDir = Path.of(System.getProperty("java.io.tmpdir"));
        }

        try {
            if (!sandbox.isAvailable()) {
                return ToolResult.failure(callId,
                    "Selected command sandbox is not available: " + sandbox.name()
                        + " (refusing to fall back to host execution)");
            }
            CommandSandbox.ProcessBuilderSpec spec = mountRoot != null
                ? sandbox.build(isWindows(), command, workDir, mountRoot, containerWorkDir)
                : sandbox.build(isWindows(), command, workDir);

            // chcp 65001 helps cmd built-ins emit UTF-8, but native Windows tools
            // (netstat/tasklist/ipconfig) ignore the console codepage when stdout is
            // piped and emit the OEM codepage (e.g. GBK/CP936). We therefore read raw
            // bytes and decode with a UTF-8-strict -> platform-charset fallback.
            ProcessBuilder pb = new ProcessBuilder(spec.argv());
            pb.directory(spec.directory().toFile());
            pb.redirectErrorStream(true);
            scrubEnvironment(pb);

            long start = System.currentTimeMillis();
            Process proc = pb.start();

            String outStr = readProcessOutput(proc.getInputStream(), maxBytes);

            boolean finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - start;

            if (!finished) {
                proc.destroyForcibly();
                return ToolResult.failure(callId,
                    "Command timed out after " + timeoutSec + "s: " + command,
                    Map.of("timeout", true, "elapsedMs", elapsed));
            }

            int exitCode = proc.exitValue();
            if (outStr.length() >= maxBytes) {
                outStr += "\n... [output truncated at " + maxBytes + " bytes]";
            }

            String content = "$ " + command + "\n" +
                "(exit=" + exitCode + ", " + elapsed + "ms, sandbox=" + sandbox.name()
                + (mountRoot != null ? ", workspace=" + mountRoot : "") + ")\n\n" + outStr;

            return exitCode == 0
                ? ToolResult.success(callId, content, Map.of(
                    "exitCode", exitCode, "elapsedMs", elapsed))
                : ToolResult.failure(callId, content, Map.of(
                    "exitCode", exitCode, "elapsedMs", elapsed));
        } catch (Exception e) {
            log.warn("code_executor failed: {}", e.getMessage());
            return ToolResult.failure(callId, "Execution error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * Environment allowlist: keep ONLY the system variables a shell/tool needs to
     * run, and drop everything else. This replaces the previous keyword blacklist,
     * which leaked any credential whose name didn't match a known pattern (e.g.
     * DATABASE_URL, KUBECONFIG, REDIS_URL, PGPASS, custom secret names). A whitelist
     * fails closed: a new credential variable is dropped by default, not inherited.
     *
     * Matching is case-insensitive because Windows env keys are case-insensitive
     * (Path vs PATH) while the map itself preserves the original casing.
     */
    private static final java.util.Set<String> ENV_ALLOWLIST = java.util.Set.of(
        // process/shell resolution
        "path", "pathext", "comspec", "systemroot", "systemdrive", "windir",
        // temp/scratch
        "temp", "tmp", "tmpdir",
        // user/home/profile (some tools resolve ~/.config, user home)
        "home", "userprofile", "homedrive", "homepath", "username", "user", "logonserver",
        // locale/codepage so native tools pick a sane output encoding
        "lang", "lc_all", "lc_ctype", "tz",
        // runtimes commonly needed on PATH-adjacent lookups
        "java_home"
    );

    static void scrubEnvironment(ProcessBuilder pb) {
        pb.environment().keySet().removeIf(k -> !ENV_ALLOWLIST.contains(k.toLowerCase(Locale.ROOT)));
    }

    /**
     * Read process stdout/stderr as raw bytes, then decode with UTF-8 first.
     * If the bytes are not valid UTF-8 (common for native Windows tools that
     * emit GBK/CP936 when piped), fall back to the platform default charset.
     * Strips NUL bytes which are illegal in PostgreSQL text columns.
     */
    private static String readProcessOutput(InputStream in, int maxBytes) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            int allowed = Math.min(n, maxBytes - total);
            if (allowed > 0) {
                baos.write(buf, 0, allowed);
                total += allowed;
            }
            if (total >= maxBytes) {
                // drain the rest so the process does not block on a full pipe
                while (in.read(buf) != -1) { /* discard */ }
                break;
            }
        }
        byte[] raw = baos.toByteArray();

        // Try strict UTF-8 first.
        try {
            CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
            String s = dec.decode(java.nio.ByteBuffer.wrap(raw)).toString();
            return sanitize(s);
        } catch (CharacterCodingException notUtf8) {
            // Fall back to platform default (GBK/MS936 on Chinese Windows).
            Charset fallback = Charset.defaultCharset();
            log.debug("process output is not valid UTF-8, decoding with {}", fallback.name());
            return sanitize(new String(raw, fallback));
        }
    }

    /** Strip characters that are illegal in PostgreSQL text (NUL) and other ASCII control chars except tab/newline/CR. */
    private static String sanitize(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u0000') continue;
            if (c < ' ' && c != '\t' && c != '\n' && c != '\r') continue;
            sb.append(c);
        }
        return sb.toString();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
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
