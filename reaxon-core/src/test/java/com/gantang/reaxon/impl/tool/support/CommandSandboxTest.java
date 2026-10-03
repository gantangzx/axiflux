package com.gantang.reaxon.impl.tool.support;

import com.gantang.reaxon.api.tool.CommandSandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandSandboxTest {

    @Test
    void localSandboxUsesHostShellAndIsAvailable(@TempDir Path dir) {
        LocalCommandSandbox sb = new LocalCommandSandbox();
        assertTrue(sb.isAvailable());
        assertEquals("local", sb.name());

        CommandSandbox.ProcessBuilderSpec win = sb.build(true, "echo hi", dir);
        assertEquals(List.of("cmd", "/c", "chcp 65001 >nul && echo hi"), win.argv());
        assertEquals(dir.toAbsolutePath(), win.directory().toAbsolutePath());

        CommandSandbox.ProcessBuilderSpec nix = sb.build(false, "echo hi", dir);
        assertEquals(List.of("sh", "-c", "echo hi"), nix.argv());
    }

    @Test
    void dockerSandboxBuildsLockedDownCommand(@TempDir Path dir) {
        DockerCommandSandbox sb = new DockerCommandSandbox("alpine:3.20", "256m", 0.5);
        CommandSandbox.ProcessBuilderSpec spec = sb.build(false, "cat /etc/os-release", dir);
        List<String> argv = spec.argv();

        assertTrue(sb.name().startsWith("docker:"), sb.name());
        assertEquals("docker", argv.get(0));
        assertTrue(argv.contains("run"));
        // network fully disabled
        int ni = argv.indexOf("--network");
        assertEquals("none", argv.get(ni + 1));
        // read-only root fs
        assertTrue(argv.contains("--read-only"));
        // drop all linux capabilities
        int ci = argv.indexOf("--cap-drop");
        assertEquals("ALL", argv.get(ci + 1));
        // memory + cpu limits present
        assertTrue(argv.contains("--memory"));
        assertTrue(argv.contains("--cpus"));
        // image then sh -c command
        assertTrue(argv.contains("alpine:3.20"));
        assertEquals("cat /etc/os-release", argv.get(argv.size() - 1));
    }

    @Test
    void dockerSandboxAppliesDefaults() {
        DockerCommandSandbox sb = new DockerCommandSandbox(null, null, 0);
        assertTrue(sb.name().contains("alpine"));
    }

    @Test
    void dockerSandboxMountsTenantRootOnly(@TempDir Path dir) throws Exception {
        Path mount = dir.resolve("u1");
        Files.createDirectories(mount);
        Path sub = mount.resolve("proj/src");
        Files.createDirectories(sub);
        DockerCommandSandbox sb = new DockerCommandSandbox("alpine:3.20", "256m", 0.5);

        CommandSandbox.ProcessBuilderSpec spec = sb.build(false, "ls", sub, mount, "/work/proj/src");
        List<String> argv = spec.argv();
        int vi = argv.indexOf("-v");
        assertEquals(mount + ":/work:ro", argv.get(vi + 1), "tenant root is bind-mounted read-only");
        int wi = argv.indexOf("-w");
        assertEquals("/work/proj/src", argv.get(wi + 1));
        assertEquals(mount, spec.directory(), "process directory is the existing mount root");
    }

    @Test
    void dockerSandboxRejectsWorkdirOutsideMount(@TempDir Path dir) throws Exception {
        Path mount = dir.resolve("u1");
        Files.createDirectories(mount);
        Path outside = dir.resolve("other");
        Files.createDirectories(outside);
        DockerCommandSandbox sb = new DockerCommandSandbox("alpine:3.20", "256m", 0.5);
        assertThrows(IllegalArgumentException.class,
            () -> sb.build(false, "ls", outside, mount, "/work"));
    }

    // ===== audit toolsec P2-2: availability cache TTL =====

    @Test
    void availabilityIsCachedWithinTtl() {
        DockerCommandSandbox sb = new DockerCommandSandbox("alpine:3.20", "256m", 0.5);
        java.util.concurrent.atomic.AtomicInteger probes = new java.util.concurrent.atomic.AtomicInteger();
        sb.setDockerProbeForTest(() -> { probes.incrementAndGet(); return true; });

        assertTrue(sb.isAvailable());
        assertTrue(sb.isAvailable());
        assertEquals(1, probes.get(), "second call within the TTL must reuse the cached probe");
    }

    @Test
    void availabilityCacheExpiresAfterTtl() throws Exception {
        DockerCommandSandbox sb = new DockerCommandSandbox("alpine:3.20", "256m", 0.5);
        java.util.concurrent.atomic.AtomicInteger probes = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean daemonUp = new java.util.concurrent.atomic.AtomicBoolean(false);
        sb.setDockerProbeForTest(() -> { probes.incrementAndGet(); return daemonUp.get(); });

        assertFalse(sb.isAvailable(), "daemon down");
        daemonUp.set(true);
        // Wait out the TTL so the next check re-probes and sees the recovered daemon.
        long deadline = System.currentTimeMillis() + 31_500;
        boolean recovered = false;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            if (sb.isAvailable()) { recovered = true; break; }
        }
        assertTrue(recovered, "availability must be re-probed after the TTL expires");
        assertTrue(probes.get() >= 2, "probe must have run more than once");
    }
}
