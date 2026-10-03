package com.gantang.reaxon.impl.tool.builtin;

import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileToolsTest {

    private final AgentContext ctx = AgentContext.builder()
        .sessionId("t").userId("u").currentQuery("").build();

    @Test
    void write_then_read_roundtrip(@TempDir Path tempDir) throws IOException {
        FileWriteTool writer = new FileWriteTool(List.of(tempDir));
        FileReadTool  reader = new FileReadTool(List.of(tempDir), 4096);

        Path target = tempDir.resolve("hello.txt");
        ToolResult w = writer.execute("w1",
            Map.of("path", target.toString(),
                   "content", "hello, world 你好",
                   "mode", "overwrite"), ctx);
        assertTrue(w.success(), w.errorMessage());
        assertTrue(Files.exists(target));

        ToolResult r = reader.execute("r1", Map.of("path", target.toString()), ctx);
        assertTrue(r.success(), r.errorMessage());
        assertEquals("hello, world 你好", r.content());
    }

    @Test
    void write_requires_approval_flag() {
        assertTrue(new FileWriteTool().requiresApproval());
    }

    @Test
    void read_rejects_outside_allowed_root(@TempDir Path allowed) {
        FileReadTool reader = new FileReadTool(List.of(allowed), 1024);
        ToolResult r = reader.execute("r2", Map.of("path", "C:/Windows/System32/drivers/etc/hosts"), ctx);
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("outside allowed roots"));
    }

    @Test
    void read_blocksSymlinkEscape(@TempDir Path allowed, @TempDir Path outside) throws IOException {
        Path secret = outside.resolve("secret.txt");
        Files.writeString(secret, "TOP-SECRET");
        Path link = allowed.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            // Creating symlinks requires privileges on some hosts (Windows without dev mode).
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                "symlink creation not permitted on this host: " + e.getMessage());
        }

        // normalize()+startsWith would pass: the link itself sits inside the root.
        // toRealPath() resolution must reveal it points outside and reject it.
        FileReadTool reader = new FileReadTool(List.of(allowed), 4096);
        ToolResult r = reader.execute("r3", Map.of("path", link.toString()), ctx);
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("outside allowed roots"), r.errorMessage());

        FileWriteTool writer = new FileWriteTool(List.of(allowed));
        ToolResult w = writer.execute("w3",
            Map.of("path", link.toString(), "content", "pwned", "mode", "overwrite"), ctx);
        assertFalse(w.success());
    }

    @Test
    void writeBlocksSecretsAndVcsInternals(@TempDir Path allowed) throws IOException {
        FileWriteTool writer = new FileWriteTool(List.of(allowed));

        ToolResult env = writer.execute("w5", Map.of(
            "path", allowed.resolve(".env").toString(),
            "content", "SECRET=xyz", "mode", "overwrite"), ctx);
        assertFalse(env.success());
        assertTrue(env.errorMessage().contains("secrets"), env.errorMessage());

        Files.createDirectories(allowed.resolve(".git/refs"));
        ToolResult git = writer.execute("w6", Map.of(
            "path", allowed.resolve(".git/config").toString(),
            "content", "[core]", "mode", "overwrite"), ctx);
        assertFalse(git.success());
        assertTrue(git.errorMessage().contains("VCS internals"), git.errorMessage());
    }

    @Test
    void readOfEnvAllowedButGitInternalsBlocked(@TempDir Path allowed) throws IOException {
        Files.writeString(allowed.resolve(".env"), "SECRET=xyz\n");
        FileReadTool reader = new FileReadTool(List.of(allowed), 4096);

        ToolResult env = reader.execute("r5",
            Map.of("path", allowed.resolve(".env").toString()), ctx);
        assertTrue(env.success(), "reads of .env stay allowed: " + env.errorMessage());
        assertTrue(env.content().contains("SECRET=xyz"));

        Files.createDirectories(allowed.resolve(".git"));
        Files.writeString(allowed.resolve(".git/config"), "[core]\n");
        ToolResult git = reader.execute("r6",
            Map.of("path", allowed.resolve(".git/config").toString()), ctx);
        assertFalse(git.success());
        assertTrue(git.errorMessage().contains("VCS internals"), git.errorMessage());
    }

    @Test
    void editRefusesBinaryFiles(@TempDir Path allowed) throws IOException {
        Path png = allowed.resolve("img.png");
        Files.write(png, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A});
        ToolResult r = new FileEditTool(List.of(allowed)).execute("e9", Map.of(
            "path", png.toString(), "old", "x", "new", "y"), ctx);
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("binary"), r.errorMessage());
    }

    @Test
    void readClampsHugeMaxBytes_toHardCeiling(@TempDir Path allowed) throws IOException {
        Path big = allowed.resolve("big.txt");
        byte[] data = new byte[2 * 1024 * 1024];
        java.util.Arrays.fill(data, (byte) 'a');
        Files.write(big, data);

        ToolResult r = new FileReadTool(List.of(allowed), 4096).execute("r7", Map.of(
            "path", big.toString(), "maxBytes", 99_000_000, "offset", 0), ctx);
        assertTrue(r.success(), r.errorMessage());
        long returned = ((Number) r.metadata().get("bytesReturned")).longValue();
        assertTrue(returned <= com.gantang.reaxon.impl.tool.support.FileSafety.MAX_READ_BYTES,
            "must clamp to hard ceiling, got " + returned);
        assertEquals(Boolean.TRUE, r.metadata().get("truncated"));
    }

    @Test
    void deniesEverythingWhenNoRootConfigured(@TempDir Path anywhere) throws IOException {
        // An empty allowlist means the jail was never configured. That must deny,
        // not grant the whole filesystem — the wiring supplies a bounded default.
        Path file = anywhere.resolve("plain.txt");
        Files.writeString(file, "data");

        ToolResult r = new FileReadTool(List.of(), 1024)
            .execute("r4", Map.of("path", file.toString()), ctx);
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("outside allowed roots"), r.errorMessage());

        ToolResult w = new FileWriteTool(List.of()).execute("w4",
            Map.of("path", anywhere.resolve("new.txt").toString(),
                   "content", "x", "mode", "overwrite"), ctx);
        assertFalse(w.success());
    }
}
