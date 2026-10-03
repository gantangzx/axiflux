package com.gantang.reaxon.impl.tool.support;

import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.tool.Workspaces;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Per-tenant filesystem workspaces: {@code <baseRoot>/<userId>/}.
 *
 * <p>This is the filesystem-isolation primitive for multi-tenant deployments
 * (roadmap P2-3). Every file/command tool that works on agent-supplied paths
 * resolves them through {@link #resolve(String, String)} so tenant A can never
 * touch tenant B's files or the host filesystem:
 *
 * <ol>
 *   <li><b>UserId is an allowlist, never used raw.</b> Only {@code [A-Za-z0-9._-]}
 *       (1–64 chars, at least one alphanumeric; {@code .} and {@code ..} refused)
 *       can reach the path layer. A blank id maps to {@code anonymous}. This kills
 *       {@code ../} and absolute-path injection through the user-id channel.</li>
 *   <li><b>Containment after symlink resolution.</b> The check goes through
 *       {@link PathGuard}, which resolves the nearest existing parent with
 *       {@code toRealPath()} — a symlink planted inside the workspace and
 *       pointing outside is rejected, not just lexical {@code ..}.</li>
 *   <li><b>Absolute agent paths are still jailed.</b> An absolute path is
 *       accepted only when its real path is inside the caller's own root.</li>
 *   <li><b>Fail-closed scoping.</b> When a tenant workspace is active the file
 *       tools default to the tenant root <em>only</em>; the host-wide configured
 *       roots are added back only with {@code combineGlobalRoots=true}.</li>
 * </ol>
 *
 * <p>The bean is created only when {@code axiflux.tools.workspaces.enabled=true},
 * so single-tenant/dev deployments keep their existing {@code file-allowed-roots}
 * behaviour unchanged.
 */
public final class TenantWorkspaces implements Workspaces {

    /** Legal user-id shape. Deliberately narrow: no separators, no spaces. */
    private static final Pattern USER_ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern HAS_ALPHANUMERIC = Pattern.compile(".*[A-Za-z0-9].*");
    private static final String ANONYMOUS = "anonymous";

    private final Path baseRoot;
    private final boolean combineGlobalRoots;
    private final Map<String, Path> rootCache = new ConcurrentHashMap<>();

    /**
     * @param baseRoot             directory under which {@code <userId>/} roots live; created on boot
     * @param combineGlobalRoots   if true, the host-wide configured file roots are
     *                             additionally visible to every tenant (shared-read style)
     */
    public TenantWorkspaces(Path baseRoot, boolean combineGlobalRoots) {
        this.baseRoot = baseRoot.toAbsolutePath().normalize();
        this.combineGlobalRoots = combineGlobalRoots;
        try {
            Files.createDirectories(this.baseRoot);
        } catch (IOException e) {
            throw new IllegalStateException(
                "Cannot create tenant workspace base root " + this.baseRoot + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Path baseRoot() {
        return baseRoot;
    }

    @Override
    public boolean combineGlobalRoots() {
        return combineGlobalRoots;
    }

    /**
     * Validate/normalize an incoming user id into a safe single path segment.
     *
     * @throws WorkspaceException for any id that is not a plain 1–64 char segment
     *         (path separators, {@code ..}, control chars, over-long, no alphanumeric)
     */
    @Override
    public String sanitizeUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return ANONYMOUS;
        }
        String id = userId.trim();
        if (!USER_ID_PATTERN.matcher(id).matches() || !HAS_ALPHANUMERIC.matcher(id).matches()
            || ".".equals(id) || "..".equals(id)) {
            throw new Workspaces.WorkspaceException("Illegal workspace user id (allowed: 1-64 chars, "
                + "[A-Za-z0-9._-], at least one letter/digit): " + id);
        }
        return id;
    }

    /** Return (creating once) the tenant root {@code <baseRoot>/<userId>/}. */
    @Override
    public Path rootFor(String userId) {
        String id = sanitizeUserId(userId);
        return rootCache.computeIfAbsent(id, k -> {
            Path p = baseRoot.resolve(k).toAbsolutePath().normalize();
            try {
                Files.createDirectories(p);
            } catch (IOException e) {
                throw new Workspaces.WorkspaceException("Cannot create tenant workspace " + p + ": " + e.getMessage());
            }
            // Defence in depth: a tampered base layout that resolves outside is refused.
            if (!p.startsWith(baseRoot)) {
                throw new Workspaces.WorkspaceException("Tenant root escaped base: " + p);
            }
            return p;
        });
    }

    /**
     * Resolve an agent-supplied path against the tenant's root, enforcing
     * containment (lexical + symlink-safe). Relative paths resolve inside the
     * root; absolute paths are accepted only when they are already inside it.
     *
     * @param userId   caller identity (blank → {@code anonymous})
     * @param pathStr  raw tool argument; blank/null means the tenant root itself
     * @throws WorkspaceException when the resolved path leaves the tenant root
     */
    @Override
    public Path resolve(String userId, String pathStr) {
        Path userRoot = rootFor(userId);
        if (pathStr == null || pathStr.isBlank()) {
            return userRoot;
        }
        Path raw;
        try {
            raw = Paths.get(pathStr.trim());
        } catch (Exception e) {
            throw new Workspaces.WorkspaceException("Invalid path: " + pathStr);
        }
        Path target = raw.isAbsolute()
            ? raw.toAbsolutePath().normalize()
            : userRoot.resolve(raw).toAbsolutePath().normalize();
        if (!PathGuard.isAllowed(target.toString(), List.of(userRoot))) {
            throw new Workspaces.WorkspaceEscapeException(
                "Path is outside the tenant workspace (" + userRoot + "): " + target);
        }
        return target;
    }

    /** Relative, forward-slash path of {@code target} inside the tenant root (for container -w). */
    @Override
    public String relativeInContainer(String userId, Path target) {
        Path userRoot = rootFor(userId);
        Path abs = target.toAbsolutePath().normalize();
        if (!abs.startsWith(userRoot)) {
            throw new Workspaces.WorkspaceEscapeException("not under tenant root: " + target);
        }
        String rel = userRoot.relativize(abs).toString().replace('\\', '/');
        return rel.isEmpty() ? "/work" : "/work/" + rel;
    }

    /**
     * Effective allowlist for a file-tool call in tenant mode: the caller's own
     * root, optionally unioned with the host-wide configured roots (shared dirs).
     */
    @Override
    public List<Path> scope(List<Path> configuredRoots, AgentContext ctx) {
        Path userRoot = rootFor(ctx != null ? ctx.userId() : null);
        if (!combineGlobalRoots || configuredRoots == null || configuredRoots.isEmpty()) {
            return List.of(userRoot);
        }
        List<Path> all = new ArrayList<>(configuredRoots.size() + 1);
        all.add(userRoot);
        for (Path r : configuredRoots) {
            if (r != null && !all.contains(r.toAbsolutePath().normalize())) {
                all.add(r.toAbsolutePath().normalize());
            }
        }
        return List.copyOf(all);
    }
}
