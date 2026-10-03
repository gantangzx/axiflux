package com.gantang.axiflux.spring.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.reaxon.api.agent.Agent;
import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.agent.AgentDefinition;
import com.gantang.reaxon.api.agent.AgentDirectory;
import com.gantang.reaxon.api.agent.AgentResponse;
import com.gantang.reaxon.api.agent.AgentEvent;
import com.gantang.reaxon.api.auth.CallerIdentity;
import com.gantang.reaxon.api.llm.ModelRouter;
import com.gantang.reaxon.api.session.SessionManager;
import com.gantang.axiflux.spring.config.props.WebProperties;
import com.gantang.axiflux.spring.auth.CallerAuthorization;
import com.gantang.axiflux.spring.auth.CallerGuard;
import com.gantang.axiflux.spring.web.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent HTTP API.
 *
 * POST /api/v1/chat              — synchronous chat
 * POST /api/v1/chat/stream       — streaming chat (SSE)
 * POST /api/v1/sessions          — create session
 * GET  /api/v1/sessions/{id}     — get session
 */
@RestController
@RequestMapping("/api/v1")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final Agent agent;
    private final SessionManager sessionManager;
    private final ModelRouter modelRouter;
    private final ObjectMapper om;
    private final WebProperties webProps;
    private final ObjectProvider<AgentDirectory> agentDirectories;
    private final CallerGuard guard;
    private final ObjectProvider<com.gantang.axiflux.spring.service.QuotaServiceSpi> quotas;
    private final ObjectProvider<com.gantang.axiflux.spring.service.PlanGateSpi> planGates;
    private final ObjectProvider<com.gantang.axiflux.spring.service.OrgDirectorySpi> orgs;

    /** Backward-compatible wiring without the plan-tier gate (no tier injection). */
    public AgentController(Agent agent, SessionManager sessionManager, ModelRouter modelRouter,
                           ObjectMapper om, WebProperties webProps,
                           ObjectProvider<AgentDirectory> agentDirectories,
                           CallerGuard guard,
                           ObjectProvider<com.gantang.axiflux.spring.service.QuotaServiceSpi> quotas) {
        this(agent, sessionManager, modelRouter, om, webProps, agentDirectories,
            guard, quotas, null, null);
    }

    public AgentController(Agent agent, SessionManager sessionManager, ModelRouter modelRouter,
                           ObjectMapper om, WebProperties webProps,
                           ObjectProvider<AgentDirectory> agentDirectories,
                           CallerGuard guard,
                           ObjectProvider<com.gantang.axiflux.spring.service.QuotaServiceSpi> quotas,
                           ObjectProvider<com.gantang.axiflux.spring.service.PlanGateSpi> planGates) {
        this(agent, sessionManager, modelRouter, om, webProps, agentDirectories,
            guard, quotas, planGates, null);
    }

    /** Full wiring with plan gate and organization governance allow-list (P3-7). */
    public AgentController(Agent agent, SessionManager sessionManager, ModelRouter modelRouter,
                           ObjectMapper om, WebProperties webProps,
                           ObjectProvider<AgentDirectory> agentDirectories,
                           CallerGuard guard,
                           ObjectProvider<com.gantang.axiflux.spring.service.QuotaServiceSpi> quotas,
                           ObjectProvider<com.gantang.axiflux.spring.service.PlanGateSpi> planGates,
                           ObjectProvider<com.gantang.axiflux.spring.service.OrgDirectorySpi> orgs) {
        this.agent = agent;
        this.sessionManager = sessionManager;
        this.modelRouter = modelRouter;
        this.om = om;
        this.webProps = webProps;
        this.agentDirectories = agentDirectories;
        this.guard = guard;
        this.quotas = quotas;
        this.planGates = planGates;
        this.orgs = orgs;
    }

    /** List available models/providers for the client model selector. */
    @GetMapping("/models")
    public Mono<ApiResponse<List<Map<String, String>>>> models() {
        // Pure in-memory traversal; fromCallable keeps the assembly non-blocking
        // and routes any failure through the Reactor error channel.
        return Mono.fromCallable(() -> {
            List<Map<String, String>> out = new java.util.ArrayList<>();
            for (String name : modelRouter.listProviders()) {
                modelRouter.get(name).ifPresent(c -> out.add(Map.of(
                    "provider", c.provider(),
                    "model", c.primaryModel() != null ? c.primaryModel() : "")));
            }
            return ApiResponse.ok(out);
        });
    }

    /** List configured agents — persisted personas plus their available models. */
    @GetMapping("/agents")
    public Mono<ApiResponse<List<Map<String, Object>>>> agents() {
        // AgentDirectory is blocking JPA; bridge off the Netty event loop.
        return Mono.fromCallable(() -> {
                List<Map<String, String>> models = availableModels();
                AgentDirectory dir = agentDirectories.getIfAvailable();
                if (dir == null) {
                    return ApiResponse.ok(List.of(Map.<String, Object>of("agentId", agent.getAgentId(), "name", "默认助手",
                        "emoji", "🤖", "builtin", true, "enabled", true, "models", models)));
                }
                return ApiResponse.ok(dir.list().stream().map(d -> toMap(d, models)).toList());
            })
            .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Agent personas are a global shared resource (systemPrompt / allowedTools /
     * riskCeiling) with no per-user owner. Mutating one is an indirect privilege
     * escalation that affects every tenant, so CRUD is gated behind the
     * {@code agent:admin} scope. GET stays open (read-only listing).
     */
    private static void requireAgentAdmin(String scopes) {
        if (!CallerAuthorization.isAgentAdmin(scopes)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "caller lacks scope '" + CallerAuthorization.ADMIN_SCOPE_AGENT + "'");
        }
    }

    /** Create a new agent persona. */
    @PostMapping("/agents")
    public Mono<ApiResponse<Map<String, Object>>> createAgent(@RequestBody Map<String, Object> body,
            @RequestHeader(value = com.gantang.axiflux.spring.auth.AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        requireAgentAdmin(authScopes);
        return Mono.fromCallable(() -> {
                AgentDirectory dir = agentDirectories.getIfAvailable();
                if (dir == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "agent persistence is not available");
                String name = str(body.get("name"));
                if (name == null || name.isBlank())
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
                AgentDefinition saved = dir.save(new AgentDefinition(
                    null, name.trim(), str(body.get("description")),
                    blankToNull(str(body.get("emoji"))),
                    blankToNull(str(body.get("systemPrompt"))),
                    blankToNull(str(body.get("soul"))),
                    blankToNull(str(body.get("userProfile"))),
                    blankToNull(str(body.get("operatingInstructions"))),
                    blankToNull(str(body.get("provider"))),
                    false, bool(body.get("enabled"), true),
                    toolList(body.get("allowedTools")) != null ? toolList(body.get("allowedTools")) : List.of(),
                    riskCeiling(body.get("riskCeiling")),
                    java.time.Instant.now(), java.time.Instant.now(),
                    false, null));
                return ApiResponse.ok(toMap(saved, availableModels()));
            })
            .subscribeOn(Schedulers.boundedElastic());
    }

    /** Update an existing agent persona. */
    @PutMapping("/agents/{agentId}")
    public Mono<ApiResponse<Map<String, Object>>> updateAgent(@PathVariable String agentId, @RequestBody Map<String, Object> body,
            @RequestHeader(value = com.gantang.axiflux.spring.auth.AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        requireAgentAdmin(authScopes);
        return Mono.fromCallable(() -> {
                AgentDirectory dir = agentDirectories.getIfAvailable();
                if (dir == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "agent persistence is not available");
                AgentDefinition existing = dir.get(agentId).orElse(null);
                if (existing == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "agent not found: " + agentId);
                AgentDefinition updated = existing.withUpdates(
                    str(body.get("name")),
                    body.containsKey("description") ? str(body.get("description")) : existing.description(),
                    body.containsKey("emoji") ? str(body.get("emoji")) : existing.emoji(),
                    body.containsKey("systemPrompt") ? str(body.get("systemPrompt")) : existing.systemPrompt(),
                    body.containsKey("soul") ? str(body.get("soul")) : existing.soul(),
                    body.containsKey("userProfile") ? str(body.get("userProfile")) : existing.userProfile(),
                    body.containsKey("operatingInstructions") ? str(body.get("operatingInstructions")) : existing.operatingInstructions(),
                    body.containsKey("provider") ? str(body.get("provider")) : existing.provider(),
                    body.containsKey("enabled") ? bool(body.get("enabled"), existing.enabled()) : existing.enabled(),
                    body.containsKey("allowedTools")
                        ? (toolList(body.get("allowedTools")) != null ? toolList(body.get("allowedTools")) : List.of())
                        : existing.allowedTools(),
                    body.containsKey("riskCeiling") ? riskCeiling(body.get("riskCeiling")) : existing.riskCeiling());
                dir.save(updated);
                return ApiResponse.ok(toMap(updated, availableModels()));
            })
            .subscribeOn(Schedulers.boundedElastic());
    }

    /** Delete a user-created agent (built-in agents are protected). */
    @DeleteMapping("/agents/{agentId}")
    public Mono<ApiResponse<Map<String, Object>>> deleteAgent(@PathVariable String agentId,
            @RequestHeader(value = com.gantang.axiflux.spring.auth.AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        requireAgentAdmin(authScopes);
        return Mono.fromCallable(() -> {
                AgentDirectory dir = agentDirectories.getIfAvailable();
                if (dir == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "agent persistence is not available");
                boolean removed = dir.delete(agentId);
                if (!removed) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "cannot delete agent (not found or built-in): " + agentId);
                return ApiResponse.ok(Map.<String, Object>of("success", true, "agentId", agentId));
            })
            .subscribeOn(Schedulers.boundedElastic());
    }

    // ─── Identity-file view (SOUL/USER/AGENTS/IDENTITY) ─────────────────────

    /** Identity files for an agent as an editable file-style view. */
    @GetMapping("/agents/{agentId}/files")
    public Mono<ApiResponse<List<Map<String, Object>>>> agentFiles(@PathVariable String agentId) {
        return Mono.fromCallable(() -> {
                AgentDirectory dir = agentDirectories.getIfAvailable();
                if (dir == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "agent persistence is not available");
                AgentDefinition d = dir.get(agentId).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "agent not found: " + agentId));
                List<Map<String, Object>> files = new java.util.ArrayList<>();
                files.add(fileView("IDENTITY.md", identityMarkdown(d), d.updatedAt(), true));
                files.add(fileView("SOUL.md", d.soul(), d.updatedAt(), false));
                files.add(fileView("USER.md", d.userProfile(), d.updatedAt(), false));
                files.add(fileView("AGENTS.md", d.operatingInstructions(), d.updatedAt(), false));
                return ApiResponse.ok(files);
            })
            .subscribeOn(Schedulers.boundedElastic());
    }

    /** Update a single editable identity file (SOUL/USER/AGENTS) for an agent. */
    @PutMapping("/agents/{agentId}/files/{fileName}")
    public Mono<ApiResponse<Map<String, Object>>> updateAgentFile(@PathVariable String agentId,
            @PathVariable String fileName, @RequestBody Map<String, Object> body,
            @RequestHeader(value = com.gantang.axiflux.spring.auth.AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        requireAgentAdmin(authScopes);
        return Mono.fromCallable(() -> {
                AgentDirectory dir = agentDirectories.getIfAvailable();
                if (dir == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "agent persistence is not available");
                AgentDefinition existing = dir.get(agentId).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "agent not found: " + agentId));
                String content = str(body.get("content"));
                AgentDefinition updated = switch (fileName) {
                    case "SOUL.md" -> existing.withUpdates(null, existing.description(), existing.emoji(),
                        existing.systemPrompt(), content, existing.userProfile(), existing.operatingInstructions(),
                        existing.provider(), existing.enabled(), existing.allowedTools(), existing.riskCeiling());
                    case "USER.md" -> existing.withUpdates(null, existing.description(), existing.emoji(),
                        existing.systemPrompt(), existing.soul(), content, existing.operatingInstructions(),
                        existing.provider(), existing.enabled(), existing.allowedTools(), existing.riskCeiling());
                    case "AGENTS.md" -> existing.withUpdates(null, existing.description(), existing.emoji(),
                        existing.systemPrompt(), existing.soul(), existing.userProfile(), content,
                        existing.provider(), existing.enabled(), existing.allowedTools(), existing.riskCeiling());
                    default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "file not editable: " + fileName + " (only SOUL.md, USER.md, AGENTS.md)");
                };
                dir.save(updated);
                return ApiResponse.ok(fileView(fileName, content, updated.updatedAt(), false));
            })
            .subscribeOn(Schedulers.boundedElastic());
    }

    private static Map<String, Object> fileView(String name, String content, java.time.Instant updatedAt, boolean readOnly) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("content", content != null ? content : "");
        m.put("readOnly", readOnly);
        m.put("updatedAt", updatedAt != null ? updatedAt.toString() : null);
        m.put("size", content != null ? content.length() : 0);
        return m;
    }

    /** Composed IDENTITY.md from the structured name/emoji/description fields. */
    private static String identityMarkdown(AgentDefinition d) {
        StringBuilder sb = new StringBuilder();
        sb.append("# IDENTITY\n\n");
        if (d.name() != null) sb.append("- Name: ").append(d.name()).append('\n');
        if (d.emoji() != null) sb.append("- Emoji: ").append(d.emoji()).append('\n');
        if (d.description() != null) sb.append("- Description: ").append(d.description()).append('\n');
        return sb.toString();
    }

    private List<Map<String, String>> availableModels() {
        List<Map<String, String>> models = new java.util.ArrayList<>();
        for (String pn : modelRouter.listProviders()) {
            modelRouter.get(pn).ifPresent(c -> models.add(Map.of(
                "provider", c.provider(),
                "model", c.primaryModel() != null ? c.primaryModel() : "")));
        }
        return models;
    }

    private static Map<String, Object> toMap(AgentDefinition d, List<Map<String, String>> models) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agentId", d.agentId());
        m.put("name", d.name());
        m.put("description", d.description());
        m.put("emoji", d.emoji());
        m.put("systemPrompt", d.systemPrompt());
        m.put("soul", d.soul());
        m.put("userProfile", d.userProfile());
        m.put("operatingInstructions", d.operatingInstructions());
        m.put("provider", d.provider());
        m.put("builtin", d.builtin());
        m.put("enabled", d.enabled());
        m.put("allowedTools", d.allowedTools() != null ? d.allowedTools() : List.of());
        m.put("riskCeiling", d.riskCeiling());
        m.put("models", models);
        return m;
    }

    /** Accepts a JSON array or comma/semicolon-separated string of tool names. */
    private static List<String> toolList(Object o) {
        if (o == null) return null;
        if (o instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull)
                .map(String::valueOf).map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
        String s = String.valueOf(o);
        if (s.isBlank()) return List.of();
        return java.util.Arrays.stream(s.split("[,;]")).map(String::trim)
            .filter(x -> !x.isEmpty()).toList();
    }

    /** Normalizes a risk ceiling to a valid {@code RiskLevel} name, or null when unrestricted/invalid. */
    private static String riskCeiling(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return null;
        try {
            return com.gantang.reaxon.api.tool.policy.RiskLevel.valueOf(s.toUpperCase(java.util.Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static String blankToNull(String s) { return (s == null || s.isBlank()) ? null : s.trim(); }
    private static boolean bool(Object o, boolean dflt) {
        if (o == null) return dflt;
        if (o instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(o));
    }

    /** Synchronous chat */
    @PostMapping("/chat")
    public Mono<ApiResponse<AgentResponse>> chat(@RequestBody ChatRequest request,
            @org.springframework.web.bind.annotation.RequestHeader(value = com.gantang.axiflux.spring.auth.AuthWebFilter.H_USER, required = false) String authUser,
            @org.springframework.web.bind.annotation.RequestHeader(value = com.gantang.axiflux.spring.auth.AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        // Pure in-memory validation — stay synchronous so malformed requests fail fast.
        validate(request);
        // authorize/buildContext hit blocking stores (CallerGuard JDBC); resolve on boundedElastic.
        return Mono.fromCallable(() -> {
                authorizeSession(request.sessionId(), authUser, authScopes);
                return buildContext(request, authUser, authScopes);
            })
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(ctx -> {
                log.info("chat sync session={} user={} model={} contentLen={}",
                    ctx.sessionId(), ctx.userId(),
                    request.forcedModel() != null ? request.forcedModel() : "auto",
                    request.content() != null ? request.content().length() : 0);
                return agent.process(ctx)
                    .doOnError(e -> log.error("chat failed session={}: {}", ctx.sessionId(), e.toString(), e))
                    .map(resp -> {
                        // LLM auth/config and provider-unavailable failures are deployment
                        // problems, not generic 500s — answer 503 with an actionable hint.
                        if (resp.status() == AgentResponse.Status.ERROR && resp.metadata() != null) {
                            Object kind = resp.metadata().get("errorKind");
                            if ("AUTH_CONFIG".equals(kind) || "UNAVAILABLE".equals(kind)) {
                                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                                    resp.content() != null && !resp.content().isBlank()
                                        ? resp.content() : "LLM provider unavailable or misconfigured");
                            }
                        }
                        return ApiResponse.ok(resp);
                    });
            });
    }

    /** Streaming chat (Server-Sent Events) */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody ChatRequest request,
            @org.springframework.web.bind.annotation.RequestHeader(value = com.gantang.axiflux.spring.auth.AuthWebFilter.H_USER, required = false) String authUser,
            @org.springframework.web.bind.annotation.RequestHeader(value = com.gantang.axiflux.spring.auth.AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        validate(request);
        return Mono.fromCallable(() -> {
                authorizeSession(request.sessionId(), authUser, authScopes);
                return buildContext(request, authUser, authScopes);
            })
            .subscribeOn(Schedulers.boundedElastic())
            .flatMapMany(ctx -> {
        Flux<ServerSentEvent<String>> events = agent.processStream(ctx)
            .onErrorResume(e -> {
                // Any upstream error (LLM auth failure, routing error, etc.) becomes a
                // structured SSE 'error' frame instead of a dropped connection / bare 500.
                log.error("chat stream failed session={}: {}", ctx.sessionId(), e.toString(), e);
                String msg = e.getMessage() != null ? e.getMessage() : "internal server error";
                return Flux.just(AgentEvent.error(msg));
            })
            .map(this::toSse);

        // Keep-alive: emit SSE comment frames (":hb\n\n") on a fixed cadence so reverse
        // proxies / load balancers with an idle timeout don't cut the connection while a
        // long tool run (e.g. a sub-agent) produces no events. Comment frames are ignored
        // by EventSource and by our client parser (no data: line). takeUntil cancels the
        // interval as soon as the run finishes, so heartbeats stop after done/error.
        int hbSeconds = webProps != null
            ? webProps.getSseHeartbeatSeconds() : 20;
        Flux<ServerSentEvent<String>> stream = events;
        if (hbSeconds > 0) {
            Flux<ServerSentEvent<String>> heartbeats = Flux.interval(Duration.ofSeconds(hbSeconds))
                .onBackpressureDrop()
                .map(n -> ServerSentEvent.<String>builder().comment("hb").build());
            stream = Flux.merge(events, heartbeats);
        }

        return stream
            .takeUntil(e -> "done".equals(e.event()) || "error".equals(e.event()))
            .concatWith(Flux.just(ServerSentEvent.<String>builder().event("close").data("").build()));
            });
    }

    /** Map an agent event to its named SSE frame. */
    private ServerSentEvent<String> toSse(AgentEvent event) {
        try {
            String json = om.writeValueAsString(event);
            String eventName = switch (event.type()) {
                case THINKING_TOKEN    -> "thinking_token";
                case TEXT_TOKEN         -> "text";
                case TOOL_CALL         -> "tool_call";
                case TOOL_RESULT       -> "tool_result";
                case APPROVAL_REQUIRED -> "approval";
                case DONE              -> "done";
                case ERROR             -> "error";
            };
            return ServerSentEvent.<String>builder()
                .event(eventName)
                .data(json)
                .build();
        } catch (Exception e) {
            return ServerSentEvent.<String>builder()
                .event("error")
                .data("{\"error\":\"stream serialization failed\"}")
                .build();
        }
    }

    /** Interrupt running agent */
    @PostMapping("/sessions/{sessionId}/interrupt")
    public Mono<ApiResponse<Map<String, String>>> interrupt(@PathVariable String sessionId,
            @RequestHeader(value = com.gantang.axiflux.spring.auth.AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = com.gantang.axiflux.spring.auth.AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        return Mono.fromCallable(() -> {
                if (!guard.ownsSession(sessionId, authUser, authScopes)
                        || sessionManager.get(sessionId).isEmpty()) {
                    throw new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.NOT_FOUND, "session not found");
                }
                log.info("interrupt requested session={}", sessionId);
                agent.interrupt(sessionId);
                return ApiResponse.ok(Map.of("sessionId", sessionId, "status", "interrupted"));
            })
            .subscribeOn(Schedulers.boundedElastic());
    }

    /** Reject blank queries up front with a 400 instead of letting the LLM client throw 500. */
    private void validate(ChatRequest request) {
        boolean hasText = request != null && request.content() != null && !request.content().isBlank();
        boolean hasAttachments = request != null && request.attachments() != null && !request.attachments().isEmpty();
        if (!hasText && !hasAttachments) {
            throw new org.springframework.web.server.ResponseStatusException(
                HttpStatus.BAD_REQUEST, "消息内容不能为空");
        }
    }

    private void authorizeSession(String sessionId, String authUser, String authScopes) {
        guard.requireSessionOwner(sessionId, authUser, authScopes);
    }

    /** P1: refuse the turn when the caller's org has exhausted a monthly cap. */
    private void requireQuota(CallerGuard.Caller caller) {
        if (quotas == null) return;
        com.gantang.axiflux.spring.service.QuotaServiceSpi quota = quotas.getIfAvailable();
        if (quota == null) return;
        quota.requireWithinQuota(toIdentity(caller));
    }

    private static CallerIdentity toIdentity(CallerGuard.Caller c) {
        return new CallerIdentity(c.userId(), c.orgId(), c.orgRole(),
            com.gantang.axiflux.spring.auth.CallerGuard.scopeList(c.scopes()));
    }

    /**
     * Enrich the turn's OBO metadata with the caller's resolved plan tier so the
     * plan-tier tool policy can gate paid tools. The key is added <em>only</em>
     * for an org caller with billing live; otherwise it stays absent and the
     * core policy treats the gate as invisible.
     */
    private Map<String, Object> withPlanTier(CallerGuard.Caller caller) {
        Map<String, Object> meta = new HashMap<>(caller.metadata());
        com.gantang.axiflux.spring.service.PlanGateSpi gate =
            planGates != null ? planGates.getIfAvailable() : null;
        if (gate != null && caller.hasOrg()) {
            String tier = gate.planOf(toIdentity(caller));
            if (tier != null && !tier.isBlank()) {
                meta.put(CallerIdentity.META_PLAN_TIER, tier);
            }
        }
        // Organization governance allow-list (P3-7): inject the resolved list so
        // the core policy can narrow every member. Resolve failures are ignored —
        // governance must not break a turn due to a lookup hiccup.
        if (caller.hasOrg() && orgs != null) {
            com.gantang.axiflux.spring.service.OrgDirectorySpi orgService = orgs.getIfAvailable();
            if (orgService != null) {
                try {
                    String allowed = orgService.allowedTools(caller.orgId());
                    if (allowed != null && !allowed.isBlank()) {
                        meta.put(CallerIdentity.META_ORG_ALLOWED_TOOLS, allowed);
                    }
                } catch (Exception ignored) {
                    // fall through without the governance key
                }
            }
        }
        return meta;
    }

    // ===== Request DTOs =====

    public record ChatRequest(
        String sessionId,
        String userId,
        String content,
        String systemPrompt,
        String forcedModel,
        List<Map<String, Object>> attachments,
        // Optional BYOK provider key for this turn (in-memory only; never persisted,
        // never logged). Null/absent → the statically configured provider key is used.
        String byokApiKey
    ) {}

    private AgentContext buildContext(ChatRequest req, String authUser, String authScopes) {
        // CallerGuard resolves the identity, verifies session ownership (404 when the
        // id belongs to someone else) and threads the caller's scopes into the
        // metadata for OBO tool checks. Doing all three in one call is what keeps a
        // new endpoint from silently shipping without one of them.
        CallerGuard.Caller caller = guard.context(
            authUser, authScopes, req.userId(), req.sessionId());
        requireQuota(caller);
        return AgentContext.builder()
            .sessionId(caller.sessionId())
            .userId(caller.userId())
            .metadata(withPlanTier(caller))
            .currentQuery(req.content() != null ? req.content() : "")
            .systemPrompt(req.systemPrompt() != null ? req.systemPrompt() : "")
            .forcedModel(req.forcedModel())
            .byokApiKey(req.byokApiKey())
            .attachments(req.attachments() != null
                ? req.attachments().stream().map(m -> {
                    String type = (String) m.getOrDefault("type", "file");
                    String url = (String) m.getOrDefault("url", "");
                    String name = (String) m.getOrDefault("name", "");
                    return new AgentContext.Attachment(type, url, name, Map.of());
                }).toList()
                : List.of())
            .build();
    }
}
