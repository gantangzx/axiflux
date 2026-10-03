package com.gantang.axiflux.spring.controller;

import com.gantang.reaxon.api.agent.BackgroundSpawner;
import com.gantang.reaxon.api.agent.DelegationRequest;
import com.gantang.reaxon.api.agent.SubAgentEventStreamer;
import com.gantang.reaxon.api.agent.SubAgentRunner;
import com.gantang.reaxon.api.auth.CallerIdentity;
import com.gantang.axiflux.spring.auth.AuthWebFilter;
import com.gantang.axiflux.spring.auth.CallerAuthorization;
import com.gantang.axiflux.spring.auth.CallerGuard;
import com.gantang.axiflux.spring.web.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;

/**
 * Sub-agent spawning API.
 *
 * <pre>
 * POST /api/v1/subagents/spawn  { task, parentSessionId? }
 *   -&gt; { childSessionId, answer }           (blocking convenience endpoint)
 *
 * GET  /api/v1/subagents/events             (SSE, long-lived)
 *   -&gt; background task lifecycle events for the calling user only:
 *      spawn_started | spawn_approval_required | spawn_result |
 *      spawn_summary_start | spawn_summary | spawn_summary_done | spawn_failed
 * </pre>
 *
 * The model uses async delegation via the {@code spawn_task} built-in tool: the
 * parent turn returns at once, the child runs in the background, and its result
 * is aggregated back into the parent session. Clients subscribe to {@code /events}
 * to stream the child result and the auto-generated summary without polling.
 *
 * <p>The spawning identity always comes from the authenticated caller, never from
 * the request body — a body-supplied {@code userId} would let any caller run a
 * sub-agent under someone else's name and inherit no verified scope grant.
 */
@RestController
@RequestMapping("/api/v1/subagents")
public class SubAgentController {

    private static final Logger log = LoggerFactory.getLogger(SubAgentController.class);

    private final SubAgentRunner runner;
    private final BackgroundSpawner spawner;
    private final SubAgentEventStreamer events;
    private final CallerGuard guard;
    /** Null in single-instance deployments; cancel then stays purely local. */
    private final com.gantang.axiflux.spring.service.SubAgentCancelBridge cancelBridge;
    private final com.gantang.axiflux.spring.service.PlanGateSpi planGate;

    private static final String FEATURE_MULTI_AGENT = "multi_agent";

    public SubAgentController(SubAgentRunner runner,
                              BackgroundSpawner spawner,
                              SubAgentEventStreamer events,
                              CallerGuard guard) {
        this(runner, spawner, events, guard, null, null);
    }

    public SubAgentController(SubAgentRunner runner,
                              BackgroundSpawner spawner,
                              SubAgentEventStreamer events,
                              CallerGuard guard,
                              com.gantang.axiflux.spring.service.SubAgentCancelBridge cancelBridge) {
        this(runner, spawner, events, guard, cancelBridge, null);
    }

    public SubAgentController(SubAgentRunner runner,
                              BackgroundSpawner spawner,
                              SubAgentEventStreamer events,
                              CallerGuard guard,
                              com.gantang.axiflux.spring.service.SubAgentCancelBridge cancelBridge,
                              com.gantang.axiflux.spring.service.PlanGateSpi planGate) {
        this.runner = runner;
        this.spawner = spawner;
        this.events = events;
        this.guard = guard;
        this.cancelBridge = cancelBridge;
        this.planGate = planGate;
    }

    private Mono<Void> requireMultiAgentPlan(String authUser, String authScopes) {
        if (planGate == null || guard == null) return Mono.empty();
        return Mono.fromRunnable(() -> {
            CallerGuard.Caller caller = guard.context(authUser, authScopes, null, null);
            planGate.require(FEATURE_MULTI_AGENT, new CallerIdentity(
                caller.userId(), caller.orgId(), caller.orgRole(),
                CallerAuthorization.scopeSet(caller.scopes())));
        }).then();
    }

    public record SpawnRequest(String task, String parentSessionId) {}

    @PostMapping("/spawn")
    public Mono<ApiResponse<Map<String, Object>>> spawn(
            @RequestBody SpawnRequest req,
            @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        if (req == null || req.task() == null || req.task().isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "task is required"));
        }

        String userId = CallerAuthorization.effectiveUser(authUser, null);
        CallerIdentity caller = new CallerIdentity(userId, CallerAuthorization.scopeSet(authScopes));
        String parent = (req.parentSessionId() == null || req.parentSessionId().isBlank())
            ? "api" : req.parentSessionId();

        log.info("REST spawn sub-agent user={} parent={} taskLen={}", userId, parent, req.task().length());

        // Plan gate: multi_agent feature requires at least Team plan.
        return requireMultiAgentPlan(authUser, authScopes)
            .then(Mono.fromCallable(() -> {
                if (!guard.ownsSession(parent, authUser, authScopes)) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found");
                }
                return parent;
            })
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(p -> runner.spawn(DelegationRequest.topLevel(req.task(), caller, p)))
            .map(r -> ApiResponse.ok(Map.<String, Object>of(
                "success", true,
                "childSessionId", r.sessionId(),
                "answer", r.answer() != null ? r.answer() : ""))));
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public Mono<ApiResponse<Map<String, Object>>> cancel(
            @PathVariable String taskId,
            @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser) {
        // cancelTask is owner-scoped inside the service event channel; cancellation
        // itself only mutates the caller's own task bookkeeping.
        String userId = CallerAuthorization.effectiveUser(authUser, null);
        // Owner scope is enforced on whichever instance actually owns the task,
        // including the cross-instance path (the check travels with the request).
        boolean ok = cancelBridge != null
            ? cancelBridge.cancel(taskId, userId)
            : events.cancelTaskForUser(taskId, userId);
        if (!ok) {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                "no running task with id " + taskId));
        }
        return Mono.just(ApiResponse.ok(Map.of("success", true, "taskId", taskId, "cancelled", true)));
    }

    /**
     * Long-lived SSE stream of this caller's background sub-agent lifecycle events.
     * Comment-frame heartbeats keep reverse proxies from cutting the idle connection.
     */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> events(
            @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser) {
        String userId = CallerAuthorization.effectiveUser(authUser, null);
        Flux<ServerSentEvent<String>> data = events.eventStream(userId)
            .map(json -> ServerSentEvent.<String>builder().event("subagent").data(json).build());
        Flux<ServerSentEvent<String>> heartbeats = Flux.interval(Duration.ofSeconds(20))
            .onBackpressureDrop()
            .map(n -> ServerSentEvent.<String>builder().comment("hb").build());
        return Flux.merge(data, heartbeats);
    }
}
