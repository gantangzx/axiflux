package com.gantang.axiflux.spring.controller;

import com.gantang.axiflux.spring.event.ScheduledTaskFiredEvent;
import com.gantang.axiflux.spring.auth.AuthWebFilter;
import com.gantang.axiflux.spring.auth.CallerAuthorization;
import com.gantang.axiflux.spring.auth.CallerGuard;
import com.gantang.axiflux.spring.service.PlanGateSpi;
import com.gantang.axiflux.spring.service.ScheduledTaskService;
import com.gantang.axiflux.spring.web.ApiResponse;
import com.gantang.axiflux.storage.entity.ScheduledTaskEntity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * REST endpoints for managing persisted scheduled tasks.
 *
 * <p>All tasks are stored in PostgreSQL ({@code scheduled_tasks} table) and
 * registered with the in-process {@code TaskScheduler}.  This gives us:
 * <ul>
 *   <li>Durability — tasks survive application restarts</li>
 *   <li>Run stats — {@code runCount / errorCount / lastRun / nextRun} persisted</li>
 *   <li>Event dispatch — each fire publishes {@link ScheduledTaskFiredEvent}</li>
 * </ul>
 *
 * <p>Payload dispatch kinds (set in the request body as {@code payload.kind}):
 * <table>
 *   <tr><th>kind</th><th>Meaning</th></tr>
 *   <tr><td>{@code agentTurn}</td><td>Send to Agent.process() as the current query</td></tr>
 *   <tr><td>{@code systemEvent}</td><td>Re-published as a Spring system event</td></tr>
 * </table>
 */
@RestController
@RequestMapping("/api/v1/scheduler")
public class SchedulerController {

    private final ScheduledTaskService service;
    private final CallerGuard guard;
    private final ObjectProvider<PlanGateSpi> planGate;

    public SchedulerController(ScheduledTaskService service) {
        this(service, null, null);
    }

    /**
     * P0-4: optional {@link CallerGuard} + {@link PlanGate} for plan-tier
     * gating on task creation. Both may be absent (self-hosted / no org
     * backend) — the gate is then skipped entirely and behaviour matches
     * pre-P0-4.
     */
    public SchedulerController(ScheduledTaskService service, CallerGuard guard,
                               ObjectProvider<PlanGateSpi> planGate) {
        this.service = Objects.requireNonNull(service);
        this.guard = guard;
        this.planGate = planGate;
    }

    // ── Task CRUD ─────────────────────────────────────────────────────────

    @GetMapping("/tasks")
    public Mono<ApiResponse<List<Map<String, Object>>>> list(@RequestParam(required = false) String userId,
            @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        return Mono.fromCallable(() -> service.listForCaller(
                CallerAuthorization.effectiveUser(authUser, userId),
                CallerAuthorization.isAdmin(authScopes)))
            .subscribeOn(Schedulers.boundedElastic())
            .map(list -> list.stream().map(this::toMap).toList())
            .map(ApiResponse::ok);
    }

    @GetMapping("/tasks/{id}")
    public Mono<ApiResponse<Map<String, Object>>> get(@PathVariable String id,
            @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        return Mono.fromCallable(() -> service.getForCaller(id,
                CallerAuthorization.effectiveUser(authUser, null),
                CallerAuthorization.isAdmin(authScopes)))
            .subscribeOn(Schedulers.boundedElastic())
            .map(e -> e != null ? ApiResponse.ok(toMap(e)) : null)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("task not found: " + id)));
    }

    @PostMapping("/cron")
    public Mono<ApiResponse<Map<String, Object>>> createCron(@RequestBody TaskRequest req,
                @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
                @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        return Mono.fromCallable(() -> {
            requirePlan(PlanGateSpi.FEATURE_SCHEDULER, authUser, authScopes, req.userId(), req.sessionId());
            ScheduledTaskEntity e = service.createCron(
                req.name(), req.cron(),
                payloadMap(req.payload(), "agentTurn"),
                CallerAuthorization.effectiveUser(authUser, req.userId(), authScopes),
                req.sessionId());
            return ApiResponse.ok(toMap(e));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/delay")
    public Mono<ApiResponse<Map<String, Object>>> createDelay(@RequestBody TaskRequest req,
                @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
                @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        return Mono.fromCallable(() -> {
            requirePlan(PlanGateSpi.FEATURE_SCHEDULER, authUser, authScopes, req.userId(), req.sessionId());
            ScheduledTaskEntity e = service.createDelay(
                req.name(), req.delayMs() != null ? req.delayMs() : 0L,
                payloadMap(req.payload(), "agentTurn"),
                CallerAuthorization.effectiveUser(authUser, req.userId(), authScopes),
                req.sessionId());
            return ApiResponse.ok(toMap(e));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/periodic")
    public Mono<ApiResponse<Map<String, Object>>> createPeriodic(@RequestBody TaskRequest req,
                @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
                @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        return Mono.fromCallable(() -> {
            ScheduledTaskEntity e = service.createPeriodic(
                req.name(), req.intervalMs() != null ? req.intervalMs() : 60_000L,
                payloadMap(req.payload(), "agentTurn"),
                CallerAuthorization.effectiveUser(authUser, req.userId(), authScopes),
                req.sessionId());
            return ApiResponse.ok(toMap(e));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/tasks/{id}/trigger")
    public Mono<ApiResponse<Map<String, Object>>> trigger(@PathVariable String id,
            @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        return Mono.fromRunnable(() -> service.triggerNow(id,
                CallerAuthorization.effectiveUser(authUser, null),
                CallerAuthorization.isAdmin(authScopes)))
            .subscribeOn(Schedulers.boundedElastic())
            .thenReturn(ApiResponse.ok(Map.<String, Object>of("id", id, "triggered", true)));
    }

    @PostMapping("/tasks/{id}/enable")
    public Mono<ApiResponse<Map<String, Object>>> enable(@PathVariable String id,
            @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        return Mono.fromCallable(() -> ApiResponse.ok(toMap(service.setEnabled(id, true,
                CallerAuthorization.effectiveUser(authUser, null),
                CallerAuthorization.isAdmin(authScopes)))))
            .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/tasks/{id}/disable")
    public Mono<ApiResponse<Map<String, Object>>> disable(@PathVariable String id,
            @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        return Mono.fromCallable(() -> ApiResponse.ok(toMap(service.setEnabled(id, false,
                CallerAuthorization.effectiveUser(authUser, null),
                CallerAuthorization.isAdmin(authScopes)))))
            .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/tasks/{id}")
    public Mono<ApiResponse<Map<String, Object>>> cancel(@PathVariable String id,
            @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        return Mono.fromCallable(() -> service.cancelTask(id,
                CallerAuthorization.effectiveUser(authUser, null),
                CallerAuthorization.isAdmin(authScopes)))
            .subscribeOn(Schedulers.boundedElastic())
            .map(cancelled -> ApiResponse.ok(
                Map.<String, Object>of("id", id, "cancelled", cancelled)));
    }

    // ── Plan gating (P0-4) ───────────────────────────────────────────────

    /**
     * Enforce the plan gate for {@code feature} when the wiring exists.
     * No-ops when no {@link PlanGate} bean is present (self-hosted) or no
     * {@link CallerGuard} is wired — org-less callers pass the gate itself.
     */
    private void requirePlan(String feature, String authUser, String authScopes,
                             String requestedUser, String requestedSession) {
        PlanGateSpi gate = planGate != null ? planGate.getIfAvailable() : null;
        if (gate == null || guard == null) return;
        CallerGuard.Caller caller = guard.context(authUser, authScopes, requestedUser, requestedSession);
        gate.require(feature, toIdentity(caller));
    }

    private static com.gantang.reaxon.api.auth.CallerIdentity toIdentity(CallerGuard.Caller caller) {
        return new com.gantang.reaxon.api.auth.CallerIdentity(
            caller.userId(), caller.orgId(), caller.orgRole(),
            CallerAuthorization.scopeSet(caller.scopes()));
    }

    // ── Serialisation helpers ─────────────────────────────────────────────

    private Map<String, Object> toMap(ScheduledTaskEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",         e.getId());
        m.put("name",       e.getName());
        m.put("type",       e.getType());
        m.put("schedule",   e.getSchedule());
        m.put("userId",     e.getUserId());
        m.put("sessionId",  e.getSessionId());
        m.put("enabled",    e.getEnabled());
        m.put("nextRun",    e.getNextRun());
        m.put("lastRun",    e.getLastRun());
        m.put("runCount",   e.getRunCount());
        m.put("errorCount", e.getErrorCount());
        m.put("createdAt",  e.getCreatedAt());
        m.put("payload",    e.getPayload());
        return m;
    }

    /** Merge flat payload fields with the optional inline payload map. */
    private Map<String, Object> payloadMap(Map<String, Object> inline, String kind) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (inline != null) p.putAll(inline);
        p.putIfAbsent("kind", kind);
        return p;
    }

    // ── Request records ───────────────────────────────────────────────────

    /**
     * Unified request body.  Not all fields are used for every endpoint:
     * <ul>
     *   <li>CRON: {@code name}, {@code cron}, {@code payload} (opt)</li>
     *   <li>DELAY: {@code name}, {@code delayMs}, {@code payload} (opt)</li>
     *   <li>PERIODIC: {@code name}, {@code intervalMs}, {@code payload} (opt)</li>
     * </ul>
     * {@code userId} and {@code sessionId} are optional fallbacks for "anonymous".
     */
    public record TaskRequest(
        String name,
        String cron,
        Long   delayMs,
        Long   intervalMs,
        Map<String, Object> payload,
        String userId,
        String sessionId
    ) {}
}
