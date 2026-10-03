package com.gantang.reaxon.impl.tool.support;

import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.tool.Workspaces;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TenantWorkspacesTest {

    @TempDir
    Path temp;

    private AgentContext ctx(String user) {
        return AgentContext.builder().sessionId("s1").userId(user).currentQuery("q").build();
    }

    @Test
    void createsRootsPerUserAndCaches() {
        var ws = new TenantWorkspaces(temp, false);
        Path a = ws.rootFor("alice");
        Path b = ws.rootFor("bob");
        assertTrue(Files.isDirectory(a));
        assertTrue(Files.isDirectory(b));
        assertEquals(a, ws.rootFor("alice"));
        assertNotEquals(a, b);
        assertTrue(a.startsWith(temp) && b.startsWith(temp));
    }

    @Test
    void sanitizesUserId() {
        var ws = new TenantWorkspaces(temp, false);
        assertEquals("anonymous", ws.sanitizeUserId(null));
        assertEquals("anonymous", ws.sanitizeUserId("   "));
        assertEquals("user-1_2.x", ws.sanitizeUserId(" user-1_2.x "));
        for (String bad : List.of("..", ".", "../x", "a/b", "a\\b", "x:y", "a b",
            "a".repeat(65), "éric")) {
            assertThrows(Workspaces.WorkspaceException.class,
                () -> ws.sanitizeUserId(bad), "should reject: " + bad);
        }
    }

    @Test
    void rejectsCrossTenantAndHostEscape() throws Exception {
        var ws = new TenantWorkspaces(temp, false);
        ws.rootFor("alice");
        ws.rootFor("bob");
        Files.writeString(temp.resolve("bob").resolve("secret.txt"), "secret");

        // lexical traversal into another tenant
        assertThrows(Workspaces.WorkspaceEscapeException.class,
            () -> ws.resolve("alice", "../bob/secret.txt"));
        // absolute path into another tenant
        assertThrows(Workspaces.WorkspaceEscapeException.class,
            () -> ws.resolve("alice", temp.resolve("bob/secret.txt").toString()));
        // absolute host path
        Path host = temp.getRoot();
        assertThrows(Workspaces.WorkspaceEscapeException.class,
            () -> ws.resolve("alice", host.toString()));

        // own relative path is fine (file need not exist yet)
        Path ok = ws.resolve("alice", "notes/a.txt");
        assertTrue(ok.startsWith(ws.rootFor("alice")));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void rejectsSymlinkEscape() throws Exception {
        var ws = new TenantWorkspaces(temp, false);
        Path outside = temp.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Path link = ws.rootFor("alice").resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (Exception e) {
            // No privilege to create symlinks on this host — cannot test this vector.
            return;
        }
        assertThrows(Workspaces.WorkspaceEscapeException.class,
            () -> ws.resolve("alice", "link/secret.txt"));
    }

    @Test
    void strictScopeIsTenantOnly() {
        var ws = new TenantWorkspaces(temp, false);
        List<Path> roots = ws.scope(List.of(temp.getParent()), ctx("alice"));
        assertEquals(1, roots.size());
        assertEquals(ws.rootFor("alice"), roots.get(0));
    }

    @Test
    void combinedScopeAddsConfiguredRoots() {
        Path shared = temp.resolve("shared");
        var ws = new TenantWorkspaces(temp, true);
        List<Path> roots = ws.scope(List.of(shared), ctx("alice"));
        assertEquals(2, roots.size());
        assertTrue(roots.contains(ws.rootFor("alice")));
        assertTrue(roots.contains(shared.toAbsolutePath().normalize()));
    }

    @Test
    void relativeInContainerMapsUnderWork() {
        var ws = new TenantWorkspaces(temp, false);
        Path sub = ws.resolve("alice", "proj/src");
        assertEquals("/work/proj/src", ws.relativeInContainer("alice", sub));
        assertEquals("/work", ws.relativeInContainer("alice", ws.rootFor("alice")));
    }
}
