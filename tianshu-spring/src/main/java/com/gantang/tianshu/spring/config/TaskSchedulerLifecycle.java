package com.gantang.tianshu.spring.config;

import com.gantang.tianshu.api.scheduler.TaskScheduler;
import com.gantang.tianshu.spring.service.ScheduledTaskService;
import com.gantang.tianshu.storage.entity.ScheduledTaskEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Wires the in-process {@link TaskScheduler} into the Spring application context.
 *
 * <h2>Task restore on startup</h2>
 * On {@code ApplicationReadyEvent} this component re-registers all persisted
 * CRON / PERIODIC tasks from the database so they survive application restarts.
 * DELAY tasks are not re-registered (they fire once and are done).
 *
 * <h2>Circular dependency resolution</h2>
 * {@code TaskSchedulerLifecycle} is created after {@code TaskSchedulerConfiguration}
 * (its dependency), so the {@code TaskScheduler} is available in its constructor.
 * It injects itself into {@code ScheduledTaskService} via
 * {@link ScheduledTaskService#setScheduler(TaskScheduler)} before firing
 * {@code ApplicationReadyEvent} so that task creation works immediately.
 */
@Component
public class TaskSchedulerLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TaskSchedulerLifecycle.class);

    private final TaskScheduler scheduler;
    private final ScheduledTaskService taskService;
    private final ApplicationEventPublisher events;
    private volatile boolean ready = false;

    public TaskSchedulerLifecycle(TaskScheduler scheduler,
                                  ScheduledTaskService taskService,
                                  ApplicationEventPublisher events) {
        this.scheduler   = scheduler;
        this.taskService = taskService;
        this.events      = events;
        // Inject scheduler into ScheduledTaskService so it can register new tasks
        // before ApplicationReadyEvent fires (e.g. if tasks are created during startup).
        taskService.setScheduler(scheduler);
    }

    /**
     * Thin internal event used to bridge the scheduler daemon thread into
     * Spring's transaction boundary when needed.
     *
     * <p>Currently unused because the direct call chain
     * {@code fireTask → onTaskFired} already runs inside a {@code @Transactional}
     * context.  Retained for future use (e.g. ShedLock integration where lock
     * acquisition happens in a different thread).
     */
    public record TaskFiredEvent(String taskId) {}

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        ready = false;
        try {
            List<ScheduledTaskEntity> tasks = taskService.listAll();
            int count = 0;
            for (ScheduledTaskEntity e : tasks) {
                if (!Boolean.TRUE.equals(e.getEnabled())) continue;
                String type = e.getType();
                if ("DELAY".equals(type)) continue; // one-shot, no need to re-register

                try {
                    taskService.registerTask(e);
                    count++;
                } catch (Exception ex) {
                    log.warn("Failed to restore task {}: {}", e.getId(), ex.getMessage());
                }
            }
            log.info("TaskSchedulerLifecycle: restored {} CRON/PERIODIC tasks from DB", count);
        } finally {
            ready = true;
        }
    }

    private static long parseIntervalMs(String schedule) {
        if (schedule == null) return 0L;
        String n = schedule.replaceAll("[^0-9]", "");
        return n.isEmpty() ? 0L : Long.parseLong(n);
    }
}
