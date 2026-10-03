package com.gantang.reaxon.impl.tool.support;

import com.gantang.reaxon.api.tool.CommandSandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Container sandbox: run each command in a fresh, locked-down Docker container.
 *
 * <p>Hardening applied on every run:
 * <ul>
 *   <li>{@code --network=none} — no outbound network (kills SSRF/exfiltration)</li>
 *   <li>{@code --read-only} root filesystem + {@code --tmpfs /tmp} — ephemeral scratch</li>
 *   <li>{@code --cap-drop=ALL} + {@code no-new-privileges} — no Linux capabilities</li>
 *   <li>{@code --memory}/{@code --cpus} caps and {@code --rm} auto-cleanup</li>
 *   <li>runs {@code sh -c} inside a Linux image regardless of host OS</li>
 * </ul>
 *
 * <p>Requires a working Docker daemon (Docker Desktop / Engine). When docker is
 * unavailable the sandbox reports {@link #isAvailable()} false so the caller can
 * fail closed rather than silently falling back to host execution.
 */
public final class DockerCommandSandbox implements CommandSandbox {

    private static final Logger log = LoggerFactory.getLogger(DockerCommandSandbox.class);

    private final String image;
    private final String memory;
    private final double cpus;

    /**
     * Audit toolsec P2-2: the availability probe is cached, but with a TTL — a
     * Docker daemon that is stopped or restarted at runtime changes the answer,
     * and a permanently cached value would either keep dispatching into a dead
     * daemon or keep refusing after it recovered. Default 30s.
     */
    private static final long AVAILABILITY_TTL_MS = 30_000L;

    private volatile long availabilityCheckedAtMs = -1L;
    private volatile Boolean availabilityCache;
    /** Test hook: replace the daemon probe. */
    private volatile java.util.function.BooleanSupplier dockerProbe = null;

    public DockerCommandSandbox(String image, String memory, double cpus) {
        this.image = (image == null || image.isBlank()) ? "alpine:3.20" : image.trim();
        this.memory = (memory == null || memory.isBlank()) ? "512m" : memory.trim();
        this.cpus = cpus <= 0 ? 1.0 : cpus;
    }

    @Override
    public String name() {
        return "docker:" + image;
    }

    @Override
    public ProcessBuilderSpec build(boolean hostWindows, String command, Path workDir) {
        Path abs = workDir.toAbsolutePath();
        return spec(command, abs, abs, "/work");
    }

    @Override
    public ProcessBuilderSpec build(boolean hostWindows, String command, Path workDir,
                                    Path mountRoot, String containerWorkDir) {
        if (mountRoot == null) {
            return build(hostWindows, command, workDir);
        }
        Path mount = mountRoot.toAbsolutePath().normalize();
        Path absWork = workDir.toAbsolutePath().normalize();
        // Never bind-mount anything above the tenant root.
        if (!absWork.startsWith(mount)) {
            throw new IllegalArgumentException(
                "container workDir " + absWork + " is not under mount root " + mount);
        }
        String cw = (containerWorkDir == null || containerWorkDir.isBlank()) ? "/work" : containerWorkDir;
        return spec(command, absWork, mount, cw);
    }

    /**
     * @param command       shell command run inside the container
     * @param hostWorkDir   host working directory (must exist; ProcessBuilder.directory)
     * @param hostMount     host directory bind-mounted at {@code /work}
     * @param containerCwd  in-container working directory under {@code /work}
     */
    private ProcessBuilderSpec spec(String command, Path hostWorkDir, Path hostMount, String containerCwd) {
        List<String> argv = new ArrayList<>(List.of(
            "docker", "run", "--rm",
            "--network", "none",
            "--read-only",
            "--cap-drop", "ALL",
            "--security-opt", "no-new-privileges",
            "--memory", memory,
            "--cpus", String.valueOf(cpus),
            "--tmpfs", "/tmp:rw,size=64m,exec",
            // Mount the tenant workspace READ-ONLY. Without :ro the container (which
            // drops all caps but still runs as the image's default user) can write into
            // the host workspace — planting files or following a symlink out of the
            // mount. Writable scratch is /tmp (tmpfs above); results return via stdout,
            // and intentional file writes go through the file_write tool's approval path.
            "-v", hostMount + ":/work:ro",
            "-w", containerCwd,
            image,
            "sh", "-c", command
        ));
        return new ProcessBuilderSpec(argv, hostMount);
    }

    @Override
    public boolean isAvailable() {
        long now = System.currentTimeMillis();
        Boolean cached = availabilityCache;
        if (cached != null && now - availabilityCheckedAtMs < AVAILABILITY_TTL_MS) {
            return cached;
        }
        boolean ok = probeDocker();
        availabilityCheckedAtMs = now;
        availabilityCache = ok;
        return ok;
    }

    /** Package-private test hook: substitute the daemon probe (avoids spawning processes). */
    void setDockerProbeForTest(java.util.function.BooleanSupplier probe) {
        this.dockerProbe = probe;
        this.availabilityCache = null;
        this.availabilityCheckedAtMs = -1L;
    }

    private boolean probeDocker() {
        java.util.function.BooleanSupplier probe = dockerProbe;
        if (probe != null) {
            return probe.getAsBoolean();
        }
        boolean ok = false;
        try {
            Process p = new ProcessBuilder("docker", "--version")
                .redirectErrorStream(true).start();
            byte[] out = p.getInputStream().readAllBytes();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            ok = finished && p.exitValue() == 0;
            if (ok) {
                log.info("Docker sandbox available: {}", new String(out).trim());
            } else {
                log.warn("Docker sandbox selected but 'docker --version' did not succeed");
            }
        } catch (Exception e) {
            log.warn("Docker sandbox unavailable: {}", e.getMessage());
            ok = false;
        }
        return ok;
    }

    /** Unused here but kept symmetric with the local sandbox (directory must exist). */
    static boolean dirOk(Path p) {
        return p != null && Files.isDirectory(p);
    }
}
