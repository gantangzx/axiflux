package com.gantang.reaxon.api.tool;

import com.gantang.reaxon.api.agent.AgentContext;

import java.nio.file.Path;
import java.util.List;

/**
 * Per-tenant filesystem workspace contract: resolves agent-supplied paths
 * inside a caller's own root {@code <baseRoot>/<userId>/} so one tenant can
 * never reach another tenant's files or the host filesystem.
 *
 * <p>This is the filesystem-isolation primitive for multi-tenant deployments.
 * File, git, search and command tools depend on this contract rather than a
 * concrete implementation, so the open-core layer stays free of impl types.
 *
 * <p>Implementations must be fail-closed: an invalid user id or a resolved
 * path that leaves the tenant root is rejected with a {@link WorkspaceException}.
 */
public interface Workspaces {

    /** Directory under which the per-user roots live. */
    Path baseRoot();

    /** Whether the host-wide configured file roots are also visible to tenants. */
    boolean combineGlobalRoots();

    /**
     * Normalize an incoming user id into a safe single path segment. A blank
     * id maps to {@code anonymous}.
     *
     * @throws WorkspaceException for any id that is not a plain, short segment
     *         (separators, {@code ..}, control chars, over-long, no alphanumeric)
     */
    String sanitizeUserId(String userId);

    /** Return (creating once) the tenant root {@code <baseRoot>/<userId>/}. */
    Path rootFor(String userId);

    /**
     * Resolve an agent-supplied path against the tenant's root, enforcing
     * lexical and symlink-safe containment. A blank path means the tenant root.
     *
     * @throws WorkspaceException when the resolved path leaves the tenant root
     */
    Path resolve(String userId, String pathStr);

    /**
     * Relative, forward-slash path of {@code target} inside the tenant root,
     * suitable for a container working directory (e.g. {@code /work/...}).
     *
     * @throws WorkspaceException when {@code target} is not under the tenant root
     */
    String relativeInContainer(String userId, Path target);

    /**
     * Effective allowlist for a file-tool call in tenant mode: the caller's own
     * root, optionally unioned with the host-wide configured roots.
     */
    List<Path> scope(List<Path> configuredRoots, AgentContext ctx);

    /** Invalid workspace request (bad user id, malformed path). */
    class WorkspaceException extends IllegalArgumentException {
        public WorkspaceException(String msg) {
            super(msg);
        }
    }

    /** Containment breach: a resolved path leaves the tenant root. */
    class WorkspaceEscapeException extends WorkspaceException {
        public WorkspaceEscapeException(String msg) {
            super(msg);
        }
    }
}
