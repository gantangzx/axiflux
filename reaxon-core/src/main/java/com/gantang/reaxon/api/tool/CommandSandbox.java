package com.gantang.reaxon.api.tool;

import java.nio.file.Path;
import java.util.List;

/**
 * Isolation strategy for {@code code_executor} child processes.
 *
 * <p>The tool itself handles timeouts, output bounding and secret scrubbing;
 * the sandbox decides <em>where</em> the command runs — directly on the host
 * ({@link LocalCommandSandbox}) or inside a locked-down container
 * ({@code DockerCommandSandbox} in the spring module).
 *
 * <p>Defence in depth: even the local sandbox runs approval-gated
 * (DESTRUCTIVE risk) with a scrubbed environment and bounded timeout; the
 * docker sandbox adds no network, read-only root, dropped capabilities and
 * CPU/memory caps for hostile/untrusted commands.
 */
public interface CommandSandbox {

    /** Short identifier surfaced in tool metadata / logs (e.g. "local", "docker"). */
    String name();

    /**
     * Build a {@link ProcessBuilder} for the shell command.
     *
     * @param hostWindows true on a Windows host (local sandbox picks cmd vs sh)
     * @param command     the raw command string the agent asked to run
     * @param workDir     working directory (already validated)
     */
    ProcessBuilderSpec build(boolean hostWindows, String command, Path workDir);

    /**
     * Build a command whose host-side files are confined to {@code mountRoot},
     * mounted at {@code /work} in the container, with the process started in
     * {@code containerWorkDir} (an absolute container path under {@code /work}).
     *
     * <p>Tenant workspaces use this so the container bind mount can never expose
     * an arbitrary host directory: the mount source is always the caller's own
     * root and every path the command sees is inside it. Local sandboxes ignore
     * the container arguments (they run on the host, where containment is
     * enforced by path validation before this method is called).
     *
     * @param mountRoot         host directory to bind-mount at /work (null = legacy behaviour)
     * @param containerWorkDir  absolute in-container working directory under /work
     */
    default ProcessBuilderSpec build(boolean hostWindows, String command, Path workDir,
                                     Path mountRoot, String containerWorkDir) {
        return build(hostWindows, command, workDir);
    }

    /** Whether this sandbox can run on the current host (e.g. docker CLI present). */
    default boolean isAvailable() { return true; }

    /** Result of preparing a command: the argv plus the directory to run in. */
    record ProcessBuilderSpec(List<String> argv, Path directory) {}
}
