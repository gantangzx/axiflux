package com.gantang.tianshu.spring.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.tianshu.api.scheduler.CronExpression;
import com.gantang.tianshu.api.scheduler.CronExpressionParser;
import com.gantang.tianshu.api.scheduler.TaskScheduler;
import com.gantang.tianshu.api.session.SessionManager;
import com.gantang.tianshu.spring.config.TaskSchedulerLifecycle.TaskFiredEvent;
import com.gantang.tianshu.spring.event.ScheduledTaskFiredEvent;
import com.gantang.tianshu.storage.entity.ScheduledTaskEntity;
import com.gantang.tianshu.storage.repository.ScheduledTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.context.event.EventListener;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service layer bridging the in-process {@link TaskScheduler} with the
 * persisted {@link ScheduledTaskEntity} in PostgreSQL.
 *
 * <p>All mutation operations are transactional.  When a task fires,
 * {@link #onTaskFired(String)} publishes a {@link ScheduledTaskFiredEvent}
 * after the DB stats-update commits.
 *
 * <p>Dispatch mapping (configurable per task via {@code payload.kind}):
 * <table>
 *   <tr><th>kind field</th><th>Handler behaviour</th></tr>
 *   <tr><td>{@code agentTurn}</td><td>{@code Agent.process(subContext)} with payload as query</td></tr>
 *   <tr><td>{@code systemEvent}</td><td>Re-published as Spring {@code SystemEvent}</td></tr>
 * </table>
 */
@Service
public class ScheduledTaskService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskService.class);

    private final ScheduledTaskRepository repo;
    private final ApplicationEventPublisher events;
    private final ObjectMapper om;
    private final TransactionTemplate tx;
    private final CronExpressionParser cronParser;
    private final ObjectProvider<SessionManager> sessions;
    private final Map<String, String> runtimeTaskIds = new ConcurrentHashMap<>();
    private volatile TaskScheduler scheduler;  // resolved lazily via ObjectProvider

    public ScheduledTaskService(ScheduledTaskRepository repo,
                                ApplicationEventPublisher events,
                                ObjectMapper om,
                                ObjectProvider<TaskScheduler> schedulerProvider,
                                CronExpressionParser cronParser,
                                TransactionTemplate transactionTemplate,
                                ObjectProvider<SessionManager> sessionProvider) {
        this.repo   = Objects.requireNonNull(repo);
        this.events = Objects.requireNonNull(events);
        this.om     = om != null ? om : new ObjectMapper();
        this.tx     = Objects.requireNonNull(transactionTemplate);
        this.cronParser = Objects.requireNonNull(cronParser);
        this.sessions = Objects.requireNonNull(sessionProvider);
        // Resolve scheduler lazily — TaskSchedulerConfiguration creates TaskScheduler,
        // TaskSchedulerLifecycle depends on this service, so we can't constructor-inject it.
        this.scheduler = schedulerProvider.getIfAvailable();
    }

    /** Setter injection to resolve the scheduler after all beans are initialised. */
    public void setScheduler(TaskScheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler);
    }

    // ── Mutation API ────────────────────────────────────────────────────────

    /** Creates and persists a cron task, then registers it with the scheduler. */
    @Transactional
    public ScheduledTaskEntity createCron(String name, String cronExpr,
                                          Map<String, Object> payload,
                                          String userId, String sessionId) {
        return createCron(name, cronExpr, null, payload, userId, sessionId);
    }

    /** Creates a cron task whose expression is evaluated in the given timezone. */
    @Transactional
    public ScheduledTaskEntity createCron(String name, String cronExpr, String timezone,
                                          Map<String, Object> payload,
                                          String userId, String sessionId) {
        return createTask(name, "CRON", cronExpr, timezone, payload, userId, sessionId);
    }

    /** Creates a one-shot task that fires at the given absolute instant. */
    @Transactional
    public ScheduledTaskEntity createAt(String name, Instant at,
                                        Map<String, Object> payload,
                                        String userId, String sessionId) {
        if (at == null || !at.isAfter(Instant.now())) {
            throw new IllegalArgumentException("'at' must be a future ISO-8601 timestamp");
        }
        return createTask(name, "AT", at.toString(), null, payload, userId, sessionId);
    }

    @Transactional
    public ScheduledTaskEntity createDelay(String name, long delayMs,
                                           Map<String, Object> payload,
                                           String userId, String sessionId) {
        return createTask(name, "DELAY", "delayMs=" + delayMs, null, payload, userId, sessionId);
    }

    @Transactional
    public ScheduledTaskEntity createPeriodic(String name, long intervalMs,
                                              Map<String, Object> payload,
                                              String userId, String sessionId) {
        return createTask(name, "PERIODIC", "intervalMs=" + intervalMs, null, payload, userId, sessionId);
    }

    @Transactional
    public void cancelTask(String id) {
        repo.findById(id).ifPresent(e -> {
            cancelRuntimeTask(e.getId());
            repo.delete(e);
        });
    }

    @Transactional
    public boolean cancelTask(String id, String userId, boolean admin) {
        return findForCaller(id, userId, admin).map(e -> {
            cancelRuntimeTask(e.getId());
            repo.delete(e);
            return true;
        }).orElse(false);
    }

    /** Toggle enabled state; cancelled tasks are removed from the scheduler. */
    @Transactional
    public ScheduledTaskEntity setEnabled(String id, boolean enabled) {
        ScheduledTaskEntity e = repo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("task not found: " + id));
        e.setEnabled(enabled);
        return repo.save(e);
    }

    @Transactional
    public ScheduledTaskEntity setEnabled(String id, boolean enabled, String userId, boolean admin) {
        ScheduledTaskEntity e = findForCaller(id, userId, admin)
            .orElseThrow(() -> new IllegalArgumentException("task not found"));
        boolean wasEnabled = Boolean.TRUE.equals(e.getEnabled());
        e.setEnabled(enabled);
        if (!enabled) {
            if (wasEnabled) {
                cancelRuntimeTask(e.getId());
            }
        }
        ScheduledTaskEntity saved = repo.save(e);
        if (enabled && !wasEnabled) registerAfterCommit(saved.getId(), saved.getType(), saved.getSchedule());
        return saved;
    }

    /** Trigger a task immediately, bypassing its schedule. */
    @Transactional
    public void triggerNow(String id) {
        ScheduledTaskEntity e = repo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("task not found: " + id));
        scheduler.triggerNow(runtimeTaskIds.getOrDefault(e.getId(), e.getId()));
    }

    @Transactional
    public void triggerNow(String id, String userId, boolean admin) {
        ScheduledTaskEntity e = findForCaller(id, userId, admin)
            .orElseThrow(() -> new IllegalArgumentException("task not found"));
        scheduler.triggerNow(runtimeTaskIds.getOrDefault(e.getId(), e.getId()));
    }

    // ── Query API ───────────────────────────────────────────────────────────

    public List<ScheduledTaskEntity> listByUser(String userId) {
        return repo.findByUserId(userId);
    }

    public List<ScheduledTaskEntity> listByUserEnabled(String userId) {
        return repo.findByUserIdAndEnabledTrue(userId);
    }

    public List<ScheduledTaskEntity> listAll() {
        return repo.findAll();
    }

    public List<ScheduledTaskEntity> listForCaller(String userId, boolean admin) {
        return admin ? repo.findAll() : repo.findByUserId(userId);
    }

    public ScheduledTaskEntity getById(String id) {
        return repo.findById(id).orElse(null);
    }

    public ScheduledTaskEntity getForCaller(String id, String userId, boolean admin) {
        return findForCaller(id, userId, admin).orElse(null);
    }

    // ── Event bridge: scheduler daemon thread → Spring transaction ─────────

    /**
     * Consumes {@link TaskFiredEvent} published by the scheduler daemon thread.
     *
     * <p>This is a plain {@link EventListener}, deliberately NOT
     * {@code @TransactionalEventListener(AFTER_COMMIT)}: the event originates on
     * the scheduler's daemon thread, outside any transaction, so an AFTER_COMMIT
     * listener would never fire. The DB update in {@link #onTaskFired} manages
     * its own transaction explicitly with {@link #tx} (TransactionTemplate) —
     * do not add {@code @Transactional} there: self-invocation from this method
     * bypasses the Spring proxy, so the annotation would be inert while looking
     * authoritative.
     */
    @EventListener
    public void onSchedulerTaskFired(TaskFiredEvent e) {
        log.debug("TaskFiredEvent received for scheduler task: {}", e.taskId());
        onTaskFired(e.taskId());
    }

    // ── Event handler ───────────────────────────────────────────────────────

    /**
     * Updates run-count / next-run in DB and publishes {@link ScheduledTaskFiredEvent}.
     * Runs in an explicit programmatic transaction: invoked from the event
     * listener on the scheduler daemon thread (no surrounding transaction, and
     * self-invocation would skip a declarative {@code @Transactional} proxy).
     */
    public void onTaskFired(String schedulerTaskId) {
        tx.executeWithoutResult(status -> repo.findById(schedulerTaskId).ifPresent(e -> {
            e.setLastRun(Instant.now());
            e.setRunCount(e.getRunCount() + 1);
            if ("CRON".equals(e.getType())) {
                try {
                    CronExpression expr = cronParser.parse(e.getSchedule());
                    ZoneId zone = resolveZone(e.getTimezone());
                    java.time.LocalDateTime next = expr.next(java.time.LocalDateTime.now(zone));
                    if (next != null) {
                        e.setNextRun(next.atZone(zone).toInstant());
                    }
                } catch (Exception ex) {
                    log.warn("Failed to compute next run for cron task {}: {}", e.getId(), ex.getMessage());
                }
            } else if ("AT".equals(e.getType()) || "DELAY".equals(e.getType())) {
                // one-shot: no future run
                e.setNextRun(null);
            }
            repo.save(e);

            Object payload = parsePayload(e.getPayload());
            String kind = payload instanceof Map m ? (String) m.getOrDefault("kind", "agentTurn") : "agentTurn";
            String sessionId = e.getSessionId() != null ? e.getSessionId() : "scheduled:" + e.getId();

            log.info("Task fired: id={} name={} kind={}", e.getId(), e.getName(), kind);

            publishAfterCommit(new ScheduledTaskFiredEvent(
                this, e.getId(), e.getName(), e.getUserId(), sessionId, kind, payload));
        }));
    }

    // ── Update ─────────────────────────────────────────────────────────────

    /** Fields a caller may patch on an existing task; null fields are left unchanged. */
    public record TaskUpdate(
        String name,
        String query,
        String timezone,
        String type,        // cron | periodic | delay | at (with the matching time field)
        String cron,
        Long intervalMs,
        Long delayMs,
        Instant at,
        Boolean enabled
    ) {}

    /** Patch an existing task. Schedule/enabled changes re-register the runtime task post-commit. */
    @Transactional
    public ScheduledTaskEntity updateTask(String id, String userId, boolean admin, TaskUpdate patch) {
        ScheduledTaskEntity e = findForCaller(id, userId, admin)
            .orElseThrow(() -> new IllegalArgumentException("task not found or not owned by you: " + id));

        boolean scheduleChanged = false;
        if (patch.type() != null && !patch.type().isBlank()) {
            String type = patch.type().trim().toUpperCase(java.util.Locale.ROOT);
            String schedule;
            String tz = patch.timezone() != null ? patch.timezone() : e.getTimezone();
            schedule = switch (type) {
                case "CRON" -> {
                    String expr = patch.cron() != null ? patch.cron() : e.getSchedule();
                    cronParser.parse(expr); // validate
                    yield expr;
                }
                case "PERIODIC" -> {
                    Long newMs = patch.intervalMs();
                    long ms = newMs != null ? newMs
                        : ("PERIODIC".equals(e.getType()) ? parseIntervalMs(e.getSchedule()) : -1L);
                    if (ms <= 0) throw new IllegalArgumentException("intervalMs required when switching to type=periodic");
                    yield "intervalMs=" + ms;
                }
                case "DELAY" -> {
                    Long newMs = patch.delayMs();
                    long ms = newMs != null ? newMs
                        : ("DELAY".equals(e.getType()) ? parseDelayMs(e.getSchedule()) : -1L);
                    if (ms < 0) throw new IllegalArgumentException("delayMs required when switching to type=delay");
                    yield "delayMs=" + ms;
                }
                case "AT" -> {
                    Instant at = patch.at() != null ? patch.at()
                        : ("AT".equals(e.getType()) ? Instant.parse(e.getSchedule()) : null);
                    if (at == null || !at.isAfter(Instant.now()))
                        throw new IllegalArgumentException("'at' (future ISO-8601 timestamp) required when switching to type=at");
                    yield at.toString();
                }
                default -> throw new IllegalArgumentException("type must be one of: cron, periodic, delay, at");
            };
            e.setType(type);
            e.setSchedule(schedule);
            e.setTimezone("CRON".equals(type) ? tz : null);
            e.setNextRun(computeNextRun(type, schedule, e.getTimezone()));
            scheduleChanged = true;
        } else if (patch.timezone() != null) {
            resolveZone(patch.timezone()); // validate
            e.setTimezone(patch.timezone());
            if ("CRON".equals(e.getType())) {
                e.setNextRun(computeNextRun("CRON", e.getSchedule(), patch.timezone()));
            }
            scheduleChanged = true;
        }

        if (patch.name() != null && !patch.name().isBlank()) e.setName(patch.name().trim());
        if (patch.query() != null && !patch.query().isBlank()) {
            Object raw = parsePayload(e.getPayload());
            if (raw instanceof Map m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = new java.util.HashMap<>((Map<String, Object>) m);
                payload.put("query", patch.query());
                e.setPayload(payload);
            }
        }

        boolean enabledChanged = false;
        if (patch.enabled() != null) {
            e.setEnabled(patch.enabled());
            enabledChanged = true;
        }

        ScheduledTaskEntity saved = repo.save(e);

        // Runtime registration follows the persisted state after commit.
        boolean wantRegistered = Boolean.TRUE.equals(saved.getEnabled());
        if (scheduleChanged || (enabledChanged && wantRegistered)) {
            cancelRuntimeTask(e.getId());
            if (wantRegistered) registerAfterCommit(e.getId(), e.getType(), e.getSchedule());
        } else if (enabledChanged && !wantRegistered) {
            cancelRuntimeTask(e.getId());
        }
        return saved;
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private ScheduledTaskEntity createTask(String name, String type, String schedule, String timezone,
                                           Map<String, Object> payload,
                                           String userId, String sessionId) {
                        validateSchedule(type, schedule);
        if (sessionId != null) {
            SessionManager manager = sessions.getIfAvailable();
            if (manager == null || manager.get(sessionId).filter(s -> userId.equals(s.userId())).isEmpty()) {
                throw new IllegalArgumentException("session not found");
            }
        }
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        ScheduledTaskEntity e = new ScheduledTaskEntity();
        e.setId(id);
        e.setName(name);
        e.setType(type);
        e.setSchedule(schedule);
        e.setTimezone(timezone);
        e.setPayload(payload);
        e.setUserId(userId);
        e.setSessionId(sessionId);
        e.setEnabled(true);

        e.setNextRun(computeNextRun(type, schedule, timezone));

        e = repo.save(e);

        registerAfterCommit(e.getId(), type, schedule);

        return e;
    }

    /** Compute the next fire instant for a fresh task; null for one-shot tasks already due. */
    private Instant computeNextRun(String type, String schedule, String timezone) {
        ZoneId zone = resolveZone(timezone);
        return switch (type) {
            case "CRON" -> {
                try {
                    CronExpression expr = cronParser.parse(schedule);
                    java.time.LocalDateTime next = expr.next(java.time.LocalDateTime.now(zone));
                    yield next != null ? next.atZone(zone).toInstant() : null;
                } catch (Exception ex) {
                    yield null;
                }
            }
            case "AT" -> {
                try { yield Instant.parse(schedule); } catch (Exception ex) { yield Instant.now(); }
            }
            default -> Instant.now(); // PERIODIC / DELAY: runtime computes the actual instant
        };
    }

    private static ZoneId resolveZone(String timezone) {
        if (timezone == null || timezone.isBlank()) return ZoneId.systemDefault();
        try { return ZoneId.of(timezone.trim()); }
        catch (Exception e) { throw new IllegalArgumentException("invalid timezone: " + timezone); }
    }

    private Object parsePayload(Object raw) {
        if (raw == null) return Map.of();
        if (raw instanceof Map || raw instanceof List) return raw;
        try { return om.readValue(om.writeValueAsString(raw), new TypeReference<>() {}); }
        catch (Exception ex) { return Map.of("raw", raw.toString()); }
    }

    private java.util.Optional<ScheduledTaskEntity> findForCaller(String id, String userId, boolean admin) {
        return admin ? repo.findById(id) : repo.findByIdAndUserId(id, userId);
    }

    private void validateSchedule(String type, String schedule) {
        switch (type) {
            case "CRON" -> cronParser.parse(schedule);
            case "PERIODIC" -> {
                if (parseIntervalMs(schedule) <= 0) throw new IllegalArgumentException("intervalMs must be > 0");
            }
            case "DELAY" -> {
                if (parseDelayMs(schedule) < 0) throw new IllegalArgumentException("delayMs must be >= 0");
            }
            case "AT" -> {
                try {
                    if (!Instant.parse(schedule).isAfter(Instant.now())) {
                        throw new IllegalArgumentException("'at' timestamp must be in the future: " + schedule);
                    }
                } catch (java.time.format.DateTimeParseException ex) {
                    throw new IllegalArgumentException("'at' must be an ISO-8601 timestamp, e.g. 2026-09-07T09:00:00+08:00");
                }
            }
            default -> throw new IllegalArgumentException("unsupported task type");
        }
    }

    private void registerAfterCommit(String id, String type, String schedule) {
        Runnable registration = () -> {
            ScheduledTaskEntity task = repo.findById(id).orElse(null);
            if (task != null) registerTask(task);
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { registration.run(); }
                });
        } else {
            registration.run();
        }
    }

    public synchronized void registerTask(ScheduledTaskEntity task) {
        String id = task.getId();
        if (runtimeTaskIds.containsKey(id)) return;
        Runnable fireTask = () -> events.publishEvent(new TaskFiredEvent(id));
        String runtimeId = switch (task.getType()) {
            case "CRON" -> scheduler.scheduleCron(id, task.getSchedule(), resolveZone(task.getTimezone()), fireTask);
            case "PERIODIC" -> scheduler.schedulePeriodic(id, parseIntervalMs(task.getSchedule()), fireTask);
            case "AT" -> {
                long delay = Math.max(0L, Duration.between(Instant.now(), Instant.parse(task.getSchedule())).toMillis());
                yield scheduler.scheduleDelay(id, delay, fireTask);
            }
            default -> scheduler.scheduleDelay(id, parseDelayMs(task.getSchedule()), fireTask);
        };
        runtimeTaskIds.put(id, runtimeId);
    }

    private synchronized void cancelRuntimeTask(String id) {
        String runtimeId = runtimeTaskIds.remove(id);
        if (runtimeId != null) scheduler.cancel(runtimeId);
    }

    private void publishAfterCommit(ScheduledTaskFiredEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { events.publishEvent(event); }
                });
        } else {
            events.publishEvent(event);
        }
    }

    private static long parseDelayMs(String schedule) {
        if (schedule == null) return 0L;
        String n = schedule.replaceAll("[^0-9]", "");
        return n.isEmpty() ? 0L : Long.parseLong(n);
    }

    private static long parseIntervalMs(String schedule) {
        return parseDelayMs(schedule);
    }
}
