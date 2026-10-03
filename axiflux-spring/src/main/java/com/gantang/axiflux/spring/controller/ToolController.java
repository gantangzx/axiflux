package com.gantang.axiflux.spring.controller;

import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.observability.MetricsReporter;
import com.gantang.reaxon.api.observability.ToolExecutionRecord;
import com.gantang.reaxon.api.tool.Tool;
import com.gantang.reaxon.api.tool.ToolRegistry;
import com.gantang.reaxon.api.tool.ToolResult;
import com.gantang.axiflux.spring.auth.CallerGuard;
import com.gantang.axiflux.spring.web.ApiResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tool registry REST API — introspection + direct invocation.
 *
 * <pre>
 * GET  /api/v1/tools                        — list all tools
 * GET  /api/v1/tools/groups                 — list groups
 * GET  /api/v1/tools/{name}                 — get one tool
 * POST /api/v1/tools/{name}/invoke          — direct invocation (bypasses LLM)
 * </pre>
 *
 * Note: dynamic registration is intentionally not exposed via HTTP; register
 * tools through {@code ToolRegistry} bean wiring or a plugin loader.
 */
@RestController
@RequestMapping("/api/v1/tools")
public class ToolController {

    private final ToolRegistry registry;
    private final MetricsReporter metrics;
    private final com.gantang.reaxon.api.tool.policy.ToolPolicyChain policyChain;
    private final CallerGuard guard;
    private final com.gantang.axiflux.spring.service.PlanGateSpi planGate;

    public ToolController(ToolRegistry registry, ObjectProvider<MetricsReporter> metrics,
                          ObjectProvider<com.gantang.reaxon.api.tool.policy.ToolPolicyChain> policy,
                          CallerGuard guard,
                          ObjectProvider<com.gantang.axiflux.spring.service.PlanGateSpi> planGates) {
        this.registry = registry;
        // Fan each event out to every reporter (micrometer, JPA audit, funnel):
        // getIfAvailable() would fail once more than one MetricsReporter exists.
        this.metrics = com.gantang.reaxon.impl.observability.CompositeMetricsReporter.of(
            metrics.orderedStream().toArray(MetricsReporter[]::new));
        this.policyChain = policy.getIfAvailable();
        this.guard = guard;
        this.planGate = planGates.getIfAvailable();
    }

    @GetMapping
    public Mono<ApiResponse<List<Map<String, Object>>>> list() {
        return Mono.just(ApiResponse.ok(registry.getAll().stream()
            .filter(t -> !t.hidden())
            .map(this::toSummary)
            .toList()));
    }

    @GetMapping("/groups")
    public Mono<ApiResponse<List<String>>> groups() {
        return Mono.just(ApiResponse.ok(registry.getGroups()));
    }

    /**
     * Introspect the security chain that is actually wired at runtime, in
     * execution order. Each entry says whether it tracks the billing plan
     * (commercial entitlement) or is an invariant security baseline. Literal
     * path wins over {@code /{name}} routing.
     */
    @GetMapping("/policies")
    public Mono<ApiResponse<List<Map<String, Object>>>> policies() {
        List<com.gantang.reaxon.api.tool.policy.PolicyMetadata> chain =
                com.gantang.reaxon.api.tool.policy.PolicyCatalog.describeChain(
                        policyChain == null ? List.of() : policyChain.policies());
        return Mono.just(ApiResponse.ok(chain.stream()
                .map(com.gantang.reaxon.api.tool.policy.PolicyMetadata::toMap)
                .toList()));
    }

    @GetMapping("/{name}")
    public Mono<ApiResponse<Map<String, Object>>> get(@PathVariable String name) {
        return Mono.justOrEmpty(registry.get(name))
            .map(t -> ApiResponse.ok(toDetail(t)))
            .switchIfEmpty(Mono.error(new ResponseStatusException(
                HttpStatus.NOT_FOUND, "tool not found: " + name)));
    }

    @PostMapping("/{name}/invoke")
    public Mono<ApiResponse<Map<String, Object>>> invoke(@PathVariable String name,
                                                            @RequestBody InvokeRequest req,
            @org.springframework.web.bind.annotation.RequestHeader(value =
                com.gantang.axiflux.spring.auth.AuthWebFilter.H_USER, required = false) String authUser,
            @org.springframework.web.bind.annotation.RequestHeader(value =
                com.gantang.axiflux.spring.auth.AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        return Mono.fromCallable(() -> {
            Tool tool = registry.get(name).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "tool not found: " + name));
            String callId0 = req.callId() != null ? req.callId() : ("call_" + UUID.randomUUID());
            // The guard pins the identity to the verified header, refuses a
            // sessionId owned by someone else, and threads the caller's scopes
            // in so ScopePolicy gates sensitive tools on direct invocation too.
            CallerGuard.Caller caller = guard.context(
                authUser, authScopes, req.userId(), req.sessionId());
            AgentContext ctx0 = AgentContext.builder()
                .sessionId(caller.sessionId())
                .userId(caller.userId())
                .metadata(withPlanTier(caller))
                .currentQuery("")
                .build();
            if (policyChain != null) {
                com.gantang.reaxon.api.tool.policy.PolicyDecision decision = policyChain.evaluate(
                    tool, req.arguments() != null ? req.arguments() : Map.of(), ctx0);
                if (decision.isDeny()) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Security policy denied this tool call: " + decision.reason());
                }
                if (decision.isAsk()) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Tool requires human approval; direct invocation not permitted");
                }
            } else {
                // No policy chain wired (toolsec P2-6): fail closed. Without the
                // chain there is nothing to deny/escalate a sensitive call, so
                // every WRITE/DESTRUCTIVE tool is refused — not just the ones
                // whose requiresApproval() flag happens to be set.
                com.gantang.reaxon.api.tool.policy.RiskLevel risk = tool.riskLevel();
                if (risk != null && risk.atLeast(com.gantang.reaxon.api.tool.policy.RiskLevel.WRITE)) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Tool risk level " + risk + " requires a policy chain; "
                        + "direct invocation not permitted without one");
                }
                if (tool.requiresApproval()) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Tool requires approval; direct invocation not permitted");
                }
            }
            String callId = callId0;
            AgentContext ctx = ctx0;
            long start = System.nanoTime();
            ToolResult result;
            try {
                result = tool.execute(callId,
                    req.arguments() != null ? req.arguments() : Map.of(), ctx);
            } catch (Exception e) {
                Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
                metrics.recordToolExecution(ToolExecutionRecord.of(
                    ctx.sessionId(), ctx.userId(), name, callId,
                    req.arguments(), false, elapsed, e.getMessage()));
                throw e;
            }
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
            metrics.recordToolExecution(ToolExecutionRecord.of(
                ctx.sessionId(), ctx.userId(), name, callId,
                req.arguments(), result.success(), elapsed, result.errorMessage()));
            return ApiResponse.ok(Map.<String, Object>of(
                "callId", callId,
                "success", result.success(),
                "content", result.displayContent(),
                "metadata", result.metadata()
            ));
        })
        // The callable performs blocking JDBC (CallerGuard context) and runs
        // arbitrary-duration blocking tools (shell/HTTP/file); never on Netty.
        .subscribeOn(Schedulers.boundedElastic());
    }

    // ===== helpers =====

    /**
     * Enrich the direct-invocation OBO metadata with the caller's resolved plan
     * tier, mirroring the chat path in AgentController. Without this key the
     * core ScopePolicy cannot see the paid tier, so Team/Pro members would be
     * wrongly denied baseline tools (web_fetch etc.) on direct invocation. The
     * key stays absent for no-org callers / when billing is off.
     */
    private Map<String, Object> withPlanTier(CallerGuard.Caller caller) {
        Map<String, Object> meta = new java.util.HashMap<>(caller.metadata());
        if (planGate != null && caller.hasOrg()) {
            com.gantang.reaxon.api.auth.CallerIdentity identity =
                new com.gantang.reaxon.api.auth.CallerIdentity(
                    caller.userId(), caller.orgId(), caller.orgRole(),
                    CallerGuard.scopeList(caller.scopes()));
            String tier = planGate.planOf(identity);
            if (tier != null && !tier.isBlank()) {
                meta.put(com.gantang.reaxon.api.auth.CallerIdentity.META_PLAN_TIER, tier);
            }
        }
        return meta;
    }

    private Map<String, Object> toSummary(Tool t) {
        return Map.of(
            "name", t.name(),
            "description", t.description(),
            "group", t.group(),
            "riskLevel", t.riskLevel(),
            "requiresApproval", t.requiresApproval()
        );
    }

    private Map<String, Object> toDetail(Tool t) {
        return Map.of(
            "name", t.name(),
            "description", t.description(),
            "group", t.group(),
            "riskLevel", t.riskLevel(),
            "requiresApproval", t.requiresApproval(),
            "hidden", t.hidden(),
            "parameters", t.parameters()
        );
    }

    // ===== DTOs =====

    public record InvokeRequest(
        String callId,
        String sessionId,
        String userId,
        Map<String, Object> arguments
    ) {}
}
