package com.gantang.tianshu.impl.tool.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Symlink-safe path allowlist enforcement for the file tools.
 *
 * <p>{@code Path.normalize()} only collapses lexical {@code ..} segments — it
 * does NOT resolve symbolic links. A symlink placed inside an allowed root that
 * points outside (e.g. {@code /work/link -> /etc/secrets}) would pass a
 * {@code startsWith} check on the normalized path. We therefore resolve real
 * paths via {@code toRealPath()} for whatever part of the path exists, then
 * compare the real paths.
 *
 * <p>The guard is <b>fail-closed</b>: an empty or null allowlist denies every
 * path. Callers are responsible for supplying at least one root (see the file
 * tool beans, which fall back to the process working directory) — an empty list
 * means "the jail was never configured", which must not silently grant the
 * agent read/write access to the whole filesystem.
 */
public final class PathGuard {

    private PathGuard() {}

    /**
     * @param pathStr       raw path argument from the tool call
     * @param allowedRoots  configured allowlist roots; empty/null denies everything
     * @return true when the resolved real path stays within an allowed root
     */
    public static boolean isAllowed(String pathStr, List<Path> allowedRoots) {
        if (allowedRoots == null || allowedRoots.isEmpty()) {
            return false; // no allowlist configured -> fail closed
        }
        final Path target;
        try {
            target = Paths.get(pathStr).toAbsolutePath().normalize();
        } catch (Exception e) {
            return false; // invalid path -> fail closed
        }
        Path realTarget = resolveReal(target);
        for (Path root : allowedRoots) {
            Path realRoot = resolveReal(root.toAbsolutePath().normalize());
            if (realTarget.startsWith(realRoot)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolve symlinks for whatever part of the path exists on disk.
     * For not-yet-existing paths (file_write create_new) the nearest existing
     * parent is resolved and the remaining (non-existent) segments are reappended.
     */
    private static Path resolveReal(Path p) {
        try {
            if (Files.exists(p)) {
                return p.toRealPath();
            }
            Path parent = p.getParent();
            while (parent != null && !Files.exists(parent)) {
                parent = parent.getParent();
            }
            if (parent == null) {
                return p; // nothing exists yet; a symlink cannot have been planted either
            }
            Path realParent = parent.toRealPath();
            Path relative = parent.relativize(p);
            return relative.toString().isEmpty() ? realParent : realParent.resolve(relative);
        } catch (IOException e) {
            // toRealPath fails only for non-existent/inaccessible paths; an existing
            // symlink (the actual threat) always resolves, so falling back to the
            // normalized form here cannot be bypassed by a planted link.
            return p;
        }
    }
}
