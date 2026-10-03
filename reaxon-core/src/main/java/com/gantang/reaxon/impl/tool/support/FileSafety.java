package com.gantang.reaxon.impl.tool.support;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Shared content-safety rules for the file tools, layered on top of the
 * {@link PathGuard} jail:
 *
 * <ul>
 *   <li><b>VCS internals</b> — any path inside a {@code .git/} directory is
 *       rejected for every tool: reading object stores is useless noise in
 *       context, and writing there corrupts the repository.</li>
 *   <li><b>Secrets / credentials</b> — write/edit of well-known secret files
 *       (.env, private keys, keystores, …) is rejected to avoid clobbering
 *       credentials by accident. Reads stay allowed (dev config work).</li>
 *   <li><b>Binary files</b> — the text edit tool must never touch files with
 *       binary extensions; a search/replace would corrupt them.</li>
 *   <li><b>Size ceilings</b> — hard caps on single reads/writes/edits.</li>
 * </ul>
 */
public final class FileSafety {

    private FileSafety() {}

    /** Hard ceiling for a single file_read slice, regardless of requested maxBytes. */
    public static final long MAX_READ_BYTES = 1024 * 1024;       // 1 MB
    /** Hard ceiling for a single file_write payload. */
    public static final long MAX_WRITE_BYTES = 2L * 1024 * 1024; // 2 MB
    /** Hard ceiling for a file_edit target file. */
    public static final long MAX_EDIT_BYTES = 512L * 1024;       // 512 KB

    /** Directory names that are VCS internals (matched as path segments). */
    private static final Set<String> VCS_DIRS = Set.of(".git", ".svn", ".hg");

    /** Exact sensitive file names (lower-cased). */
    private static final Set<String> SENSITIVE_NAMES = Set.of(
        ".env", ".env.local", ".env.production", ".env.development",
        ".htpasswd", ".npmrc", ".pypirc", ".netrc",
        "credentials", "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519",
        "known_hosts", "authorized_keys");

    /**
     * Deployment-config file prefixes that commonly carry credentials (Spring
     * {@code application*.yml/properties}, env dotfiles, …). Matched by prefix so
     * profile variants ({@code application-prod.yml}) are covered too. Reads stay
     * allowed by {@link #checkRead(Path)} (dev config work); writes/edits are
     * refused so a tool call cannot silently rewrite security configuration, and
     * the code indexer never embeds them (toolsec P2-1).
     */
    private static final Set<String> SENSITIVE_PREFIXES = Set.of(
        "application", "bootstrap", "setenv");

    /** Deployment-config extensions guarded in combination with {@link #SENSITIVE_PREFIXES}. */
    private static final Set<String> SENSITIVE_CONFIG_EXT = Set.of(
        "yml", "yaml", "properties", "env", "bat", "cmd", "sh");

    /** Sensitive file extensions (lower-cased, no dot). */
    private static final Set<String> SENSITIVE_EXT = Set.of(
        "pem", "key", "p12", "pfx", "jks", "keystore", "kdbx", "ovpn", "asc", "gpg");

    /** Extensions treated as binary — never edit via the text search/replace tool. */
    private static final Set<String> BINARY_EXT = Set.of(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "icns", "tiff",
        "pdf", "zip", "jar", "war", "ear", "class", "dll", "exe", "so", "dylib",
        "bin", "dat", "mp3", "mp4", "mov", "avi", "wav", "flac", "ogg", "webm",
        "ttf", "otf", "woff", "woff2", "eot", "gz", "tar", "7z", "rar", "xz",
        "db", "sqlite", "sqlite3", "pyc", "o", "a", "lib", "wasm");

    /** Reject reasons shared by read/write/edit. {@code null} means allowed. */
    public static String checkRead(Path path) {
        return vcsBlockReason(path);
    }

    public static String checkWrite(Path path) {
        String vcs = vcsBlockReason(path);
        if (vcs != null) return vcs;
        return sensitiveBlockReason(path);
    }

    public static String checkEdit(Path path) {
        String write = checkWrite(path);
        if (write != null) return write;
        if (isBinaryName(path.getFileName().toString())) {
            return "Refusing to edit a binary file by text search/replace: "
                + path.getFileName() + " (binary extension — content would be corrupted)";
        }
        return null;
    }

    public static boolean isBinaryName(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        return BINARY_EXT.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * True for well-known secret/credential file names ({@code .env*}, private keys,
     * keystores, …). Used by the code indexer to avoid embedding credentials.
     * Mirrors the write/edit guard in {@link #sensitiveBlockReason(Path)}.
     */
    public static boolean isSensitiveName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals(".env") || lower.startsWith(".env.")) return true;
        if (SENSITIVE_NAMES.contains(lower)) return true;
        if (isSensitiveConfigName(lower)) return true;
        int dot = lower.lastIndexOf('.');
        return dot >= 0 && dot < lower.length() - 1
            && SENSITIVE_EXT.contains(lower.substring(dot + 1));
    }

    /**
     * {@code application*.yml/properties/env}, {@code bootstrap*.yml}, {@code setenv.*}
     * — Spring deployment config, the file most likely to hold plaintext credentials
     * inside a repo working directory (toolsec P2-1).
     */
    private static boolean isSensitiveConfigName(String lower) {
        int dot = lower.lastIndexOf('.');
        if (dot < 0 || dot == lower.length() - 1) return false;
        String base = lower.substring(0, dot);
        String ext = lower.substring(dot + 1);
        if (!SENSITIVE_CONFIG_EXT.contains(ext)) return false;
        for (String prefix : SENSITIVE_PREFIXES) {
            if (base.equals(prefix) || base.startsWith(prefix + "-") || base.startsWith(prefix + "_")) {
                return true;
            }
        }
        return false;
    }

    private static String vcsBlockReason(Path path) {
        for (Path seg : path) {
            if (VCS_DIRS.contains(seg.toString())) {
                return "Refusing to touch VCS internals (." + seg.toString().substring(1)
                    + " directory): " + path + " — use the git tool for repository operations";
            }
        }
        return null;
    }

    private static String sensitiveBlockReason(Path path) {
        String name = path.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        // .env.* variants (e.g. .env.local, .env.production)
        if (lower.equals(".env") || lower.startsWith(".env.")) {
            return sensitiveReason(name);
        }
        if (SENSITIVE_NAMES.contains(lower)) {
            return sensitiveReason(name);
        }
        if (isSensitiveConfigName(lower)) {
            return sensitiveReason(name);
        }
        int dot = lower.lastIndexOf('.');
        if (dot >= 0 && dot < lower.length() - 1
                && SENSITIVE_EXT.contains(lower.substring(dot + 1))) {
            return sensitiveReason(name);
        }
        return null;
    }

    private static String sensitiveReason(String name) {
        return "Refusing to write/overwrite a likely secrets/credentials file: "
            + name + " (protecting .env, private keys, keystores; edit it manually if intended)";
    }
}
