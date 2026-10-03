package com.gantang.axiflux.spring.service;

import com.gantang.reaxon.api.memory.LongTermMemory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;

/**
 * Nightly long-term memory maintenance: for every user that has memories, run
 * {@link LongTermMemory#compress(String)} to generate summaries for
 * high-importance items (keeps injected context concise as the store grows).
 *
 * <p>ShedLock-guarded so only one instance in a cluster runs per night. Runs on
 * the scheduler thread pool; all work is bounded and best-effort — a failure for
 * one user is logged and does not stop the rest.
 */
public class MemoryMaintenanceJob {

    private static final Logger log = LoggerFactory.getLogger(MemoryMaintenanceJob.class);

    private final LongTermMemory memory;

    public MemoryMaintenanceJob(LongTermMemory memory) {
        this.memory = memory;
    }

    @Scheduled(cron = "${axiflux.memory.maintenance-cron:0 30 3 * * *}")
    @SchedulerLock(name = "axiflux-memory-maintenance",
        lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void runMaintenance() {
        if (!memory.enabled()) {
            return; // no vector backend wired
        }
        List<String> users;
        try {
            users = memory.listUserIds().block();
        } catch (Exception e) {
            log.warn("Memory maintenance: cannot list users ({}); skipping run", e.toString());
            return;
        }
        if (users == null || users.isEmpty()) {
            log.info("Memory maintenance: no users with memories, nothing to do");
            return;
        }
        log.info("Memory maintenance starting for {} user(s)", users.size());
        int totalSummarized = 0;
        for (String user : users) {
            try {
                Integer n = memory.compress(user).block();
                if (n != null && n > 0) {
                    totalSummarized += n;
                    log.info("Memory maintenance: summarized {} item(s) for user {}", n, user);
                }
            } catch (Exception e) {
                log.warn("Memory maintenance: compress failed for user {} ({}); continuing", user, e.toString());
            }
        }
        log.info("Memory maintenance done: {} item(s) summarized across {} user(s)",
            totalSummarized, users.size());
    }
}
