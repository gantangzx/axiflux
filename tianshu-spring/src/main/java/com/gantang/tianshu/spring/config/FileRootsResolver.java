package com.gantang.tianshu.spring.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Single place that turns the {@code tianshu.tools.file-allowed-roots} CSV into
 * the filesystem jail used by the file tools.
 *
 * <p>{@link com.gantang.tianshu.impl.tool.support.PathGuard} is fail-closed, so an empty
 * root list denies every path. Rather than let a blank/misspelled property either
 * unlock the whole filesystem (the old fail-open behaviour) or silently break the
 * file tools, an unconfigured jail resolves to the process working directory — a
 * bounded default that keeps dev and demo usage working.
 *
 * <p><b>Security warning (toolsec P2-1):</b> the working-directory fallback means a
 * deployment that forgets to set {@code tianshu.tools.file-allowed-roots} grants
 * the file tools read access to the <em>entire</em> working directory — including
 * source code and config files. {@code application*.yml/properties}, {@code .env*}
 * and key material are shielded from writes/indexing by
 * {@link com.gantang.tianshu.impl.tool.support.FileSafety}, but that is a second layer,
 * not a jail. <b>Always set {@code file-allowed-roots} explicitly in production.</b>
 *
 * <p>Both the startup beans and the hot-reload path go through here so a live
 * config change can never produce a different jail shape than boot would.
 */
public final class FileRootsResolver {

    private static final Logger log = LoggerFactory.getLogger(FileRootsResolver.class);

    private FileRootsResolver() {}

    /** Parse a {@code ,}/{@code ;}-separated root list, defaulting to the working directory. */
    public static List<Path> resolve(String csv) {
        List<Path> out = new ArrayList<>();
        if (csv != null && !csv.isBlank()) {
            for (String s : csv.split("[,;]")) {
                String trimmed = s.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    out.add(Path.of(trimmed));
                } catch (Exception ignored) {
                    // Skip a malformed root rather than failing boot; the remaining
                    // roots (or the default below) still bound the jail.
                    log.warn("Ignoring malformed tianshu.tools.file-allowed-roots entry: {}", trimmed);
                }
            }
        }
        if (out.isEmpty()) {
            Path cwd = defaultRoot();
            log.warn("tianshu.tools.file-allowed-roots is not configured; restricting the "
                + "file tools to the working directory {}. Set it explicitly in production.", cwd);
            out.add(cwd);
        }
        return List.copyOf(out);
    }

    /** The bounded fallback jail: the process working directory. */
    public static Path defaultRoot() {
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }
}
