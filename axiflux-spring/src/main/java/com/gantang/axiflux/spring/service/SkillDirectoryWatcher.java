package com.gantang.axiflux.spring.service;

import com.gantang.reaxon.api.skill.SkillReloadResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * Watches the skill root directory and hot-reloads the skill registry whenever
 * SKILL.md files (or skill directories) are created, modified or deleted.
 *
 * <p>Implementation notes:
 * <ul>
 *   <li>{@link WatchService} registered recursively (root + every subdirectory);
 *       newly created directories are registered on the fly.</li>
 *   <li>File editors fire bursts of events (atomic save = create+modify, or
 *       delete+create); events are coalesced with a 500 ms debounce so one save
 *       triggers exactly one rescan.</li>
 *   <li>Reload is a full directory rescan + registry reconcile inside
 *       {@link com.gantang.reaxon.api.skill.SkillExecutor#reloadSkills()}; the watcher
 *       itself never touches the registry, so start/stop is trivially safe.</li>
 *   <li>Daemon threads only; a missing/unwatchable root logs and disables
 *       watching rather than failing application startup.</li>
 * </ul>
 */
public final class SkillDirectoryWatcher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SkillDirectoryWatcher.class);
    private static final long DEBOUNCE_MS = 500;

    private final Path root;
    private final Supplier<SkillReloadResult> reloadAction;

    private WatchService watchService;
    private Thread watchThread;
    private ScheduledExecutorService debouncer;
    private ScheduledFuture<?> pendingReload;
    private final Map<WatchKey, Path> keys = new ConcurrentHashMap<>();
    private volatile boolean running;

    public SkillDirectoryWatcher(String rootDir, Supplier<SkillReloadResult> reloadAction) {
        this.root = (rootDir == null || rootDir.isBlank()) ? null : Path.of(rootDir);
        this.reloadAction = reloadAction;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        if (root == null) {
            log.info("Skill hot-reload disabled: no skills root directory configured");
            return;
        }
        try {
            Files.createDirectories(root);
            watchService = root.getFileSystem().newWatchService();
            registerTree(root);
        } catch (IOException e) {
            log.warn("Skill hot-reload disabled: cannot watch {}: {}", root, e.getMessage());
            closeQuietly();
            return;
        }
        debouncer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "skill-reload-debounce");
            t.setDaemon(true);
            return t;
        });
        running = true;
        watchThread = new Thread(this::watchLoop, "skill-dir-watcher");
        watchThread.setDaemon(true);
        watchThread.start();
        log.info("Skill hot-reload watcher started on {}", root.toAbsolutePath());
    }

    @Override
    public synchronized void stop() {
        if (!running) return;
        running = false;
        if (pendingReload != null) pendingReload.cancel(false);
        if (debouncer != null) debouncer.shutdownNow();
        closeQuietly();
        if (watchThread != null) watchThread.interrupt();
        log.info("Skill hot-reload watcher stopped");
    }

    @Override
    public boolean isRunning() { return running; }

    @Override
    public int getPhase() { return Integer.MAX_VALUE; }

    // ------------------------------------------------------------------ //

    private void registerTree(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            walk.filter(Files::isDirectory).forEach(this::registerOne);
        }
    }

    private void registerOne(Path dir) {
        try {
            WatchKey k = dir.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
            keys.put(k, dir);
        } catch (IOException e) {
            log.debug("Could not register watch for {}: {}", dir, e.getMessage());
        }
    }

    private void watchLoop() {
        while (running) {
            WatchKey key;
            try {
                key = watchService.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            } catch (ClosedWatchServiceException e) {
                return;
            }
            if (key == null) continue;
            Path dir = keys.get(key);
            if (dir != null) {
                for (WatchEvent<?> ev : key.pollEvents()) {
                    if (ev.kind() == OVERFLOW) continue;
                    Object ctx = ev.context();
                    if (ctx instanceof Path p) {
                        Path changed = dir.resolve(p);
                        // A new directory (e.g. new-skill/) needs its own watch so
                        // SKILL.md landing inside it is observed.
                        if (ev.kind() == ENTRY_CREATE && Files.isDirectory(changed)) {
                            try {
                                registerTree(changed);
                            } catch (IOException ex) {
                                log.debug("Could not watch new skill directory {}: {}", changed, ex.getMessage());
                            }
                        }
                    }
                }
            }
            key.reset();
            scheduleReload();
        }
    }

    /** Coalesce bursty editor events into one rescan DEBOUNCE_MS after the last. */
    private synchronized void scheduleReload() {
        if (!running) return;
        if (pendingReload != null) pendingReload.cancel(false);
        pendingReload = debouncer.schedule(this::doReload, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private void doReload() {
        try {
            SkillReloadResult r = reloadAction.get();
            if (r != null && !r.isEmpty()) {
                log.info("Skill hot-reload: +{} ~{} -{} (total {})",
                    r.added(), r.updated().size(), r.removed(), r.total());
            }
        } catch (Exception e) {
            log.warn("Skill hot-reload failed: {}", e.getMessage());
        }
    }

    private void closeQuietly() {
        keys.clear();
        if (watchService != null) {
            try { watchService.close(); } catch (IOException ignored) { }
            watchService = null;
        }
    }
}
