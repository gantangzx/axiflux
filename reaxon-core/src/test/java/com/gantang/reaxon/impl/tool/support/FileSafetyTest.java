package com.gantang.reaxon.impl.tool.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileSafetyTest {

    @Test
    void vcsInternals_blocked_for_all_operations(@TempDir Path root) {
        Path gitConfig = root.resolve(".git").resolve("config");
        assertNotNull(FileSafety.checkRead(gitConfig), ".git read must be blocked");
        assertNotNull(FileSafety.checkWrite(gitConfig), ".git write must be blocked");
        assertNotNull(FileSafety.checkEdit(gitConfig), ".git edit must be blocked");
        // .gitignore / .github are NOT the .git directory
        assertNull(FileSafety.checkRead(root.resolve(".gitignore")));
        assertNull(FileSafety.checkWrite(root.resolve(".github").resolve("workflows").resolve("ci.yml")));
    }

    @Test
    void secretFiles_blocked_for_write_but_readable(@TempDir Path root) {
        for (String name : new String[]{".env", ".env.local", ".env.production", "id_rsa", "server.pem", "cert.key", "store.jks"}) {
            Path f = root.resolve(name);
            assertNotNull(FileSafety.checkWrite(f), name + " write must be blocked");
            assertNotNull(FileSafety.checkEdit(f), name + " edit must be blocked");
            assertNull(FileSafety.checkRead(f), name + " read should remain allowed");
        }
    }

    @Test
    void normalFiles_unaffected(@TempDir Path root) {
        // Note: "application.properties" used to sit here; since toolsec P2-1
        // deployment configs are sensitive (see deploymentConfigs_flaggedSensitive).
        for (String name : new String[]{"Main.java", "config.yml", "README.md", "notes.txt"}) {
            Path f = root.resolve(name);
            assertNull(FileSafety.checkRead(f), name);
            assertNull(FileSafety.checkWrite(f), name);
            assertNull(FileSafety.checkEdit(f), name);
        }
    }

    @Test
    void binaryFiles_blocked_for_edit_only(@TempDir Path root) {
        Path png = root.resolve("diagram.png");
        assertNotNull(FileSafety.checkEdit(png), "editing a .png must be rejected");
        assertNull(FileSafety.checkWrite(png), "writing a binary is the model's own choice (no edit guard)");
        assertTrue(FileSafety.isBinaryName("photo.JPG"));
        assertTrue(FileSafety.isBinaryName("archive.tar.gz"));
        assertTrue(FileSafety.isBinaryName("x.class"));
        assertFalse(FileSafety.isBinaryName("Main.java"));
        assertFalse(FileSafety.isBinaryName("Dockerfile"));
    }

    @Test
    void sizeCaps_are_positive_and_ordered() {
        assertTrue(FileSafety.MAX_READ_BYTES > 0);
        assertTrue(FileSafety.MAX_WRITE_BYTES >= FileSafety.MAX_READ_BYTES);
        assertTrue(FileSafety.MAX_EDIT_BYTES > 0 && FileSafety.MAX_EDIT_BYTES <= FileSafety.MAX_WRITE_BYTES);
    }

    /** toolsec P2-1: Spring/env deployment configs are treated as sensitive —
     *  never embedded by the code indexer, never overwritten by the file tools. */
    @Test
    void deploymentConfigs_flaggedSensitive(@TempDir Path root) {
        for (String name : new String[]{"application.yml", "application-local.yml",
                "application_prod.properties", "bootstrap.yml", "setenv.bat", "setenv.sh"}) {
            assertTrue(FileSafety.isSensitiveName(name), name + " must be flagged sensitive");
            assertNotNull(FileSafety.checkWrite(root.resolve(name)), name + " write must be blocked");
        }
        // Lookalikes that are NOT deployment configs stay untouched.
        for (String name : new String[]{"Application.java", "application-notes.md",
                "applications.ymlx", "config.yml", "app.properties"}) {
            assertFalse(FileSafety.isSensitiveName(name), name + " must NOT be flagged sensitive");
        }
    }
}
