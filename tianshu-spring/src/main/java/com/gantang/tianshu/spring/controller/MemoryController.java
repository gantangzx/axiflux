package com.gantang.tianshu.spring.controller;

import com.gantang.tianshu.api.auth.CallerIdentity;
import com.gantang.tianshu.api.memory.LongTermMemory;
import com.gantang.tianshu.api.memory.MemoryItem;
import com.gantang.tianshu.spring.auth.AuthWebFilter;
import com.gantang.tianshu.spring.auth.CallerAuthorization;
import com.gantang.tianshu.spring.auth.CallerGuard;
import com.gantang.tianshu.spring.service.PlanGateSpi;
import com.gantang.tianshu.spring.web.ApiResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Long-term memory REST API.
 *
 * <pre>
 * POST   /api/v1/memory/search    — semantic search
 * POST   /api/v1/memory/store     — store a memory
 * GET    /api/v1/memory/{userId}  — list all
 * DELETE /api/v1/memory/{id}      — delete
 * POST   /api/v1/memory/{userId}/compress — compact memories
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/memory")
public class MemoryController {

    private static final String FEATURE = "long_term_memory";

    private final ObjectProvider<LongTermMemory> memory;
    private final CallerGuard guard;
    private final ObjectProvider<PlanGateSpi> planGates;

    public MemoryController(ObjectProvider<LongTermMemory> memory) {
        this(memory, null, null);
    }

    public MemoryController(ObjectProvider<LongTermMemory> memory,
                            CallerGuard guard,
                            ObjectProvider<PlanGateSpi> planGates) {
        this.memory = memory;
        this.guard = guard;
        this.planGates = planGates;
    }

    /**
     * Require the caller's plan to cover long-term memory. Runs on the
     * subscribing thread; a 402 is raised as a reactive signal. No-op when no
     * gate/guard is wired (self-hosted) or the caller has no organization.
     */
    private Mono<Void> requireMemoryPlan(String authUser, String authScopes) {
        return Mono.fromRunnable(() -> {
            PlanGateSpi gate = planGates != null ? planGates.getIfAvailable() : null;
            if (gate == null || guard == null) return;
            CallerGuard.Caller caller = guard.context(authUser, authScopes, null, null);
            gate.require(FEATURE, new CallerIdentity(
                caller.userId(), caller.orgId(), caller.orgRole(),
                CallerAuthorization.scopeSet(caller.scopes())));
        }).then();
    }

    private LongTermMemory ltmOrUnavailable() {
        LongTermMemory ltm = memory.getIfAvailable();
        if (ltm == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Long-term memory is not configured. Set tianshu.vector.provider.");
        }
        return ltm;
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "not found");
    }

    @PostMapping("/search")
    public Mono<ApiResponse<Map<String, Object>>> search(@RequestBody SearchRequest req,
                @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
                @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        LongTermMemory ltm = ltmOrUnavailable();
        int topK = req.topK() > 0 ? req.topK() : 5;
        String userId = CallerAuthorization.effectiveUser(authUser, req.userId(), authScopes);
        return requireMemoryPlan(authUser, authScopes)
            .then(ltm.search(userId, req.query(), topK)
                .map(list -> ApiResponse.ok(Map.of("results", list))));
    }

    @PostMapping("/store")
    public Mono<ApiResponse<Map<String, Object>>> store(@RequestBody StoreRequest req,
                @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
                @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        LongTermMemory ltm = ltmOrUnavailable();
        String userId = CallerAuthorization.effectiveUser(authUser, req.userId(), authScopes);
        // Validated synchronously, before the plan gate and before any Mono is created,
        // so malformed payloads always surface as an immediate 400 (P2-11).
        validate(req);
        return requireMemoryPlan(authUser, authScopes)
            .then(Mono.defer(() -> Mono.fromCallable(() -> doStore(req, userId))
                .flatMap(item -> ltm.store(userId, item)
                    .thenReturn(ApiResponse.ok(Map.of("id", item.id(), "stored", true))))));
    }

    /** Fail-fast input validation; must stay synchronous so bad requests get 400, not 500 (P2-11). */
    private void validate(StoreRequest req) {
        if (req.content() == null || req.content().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "content must not be blank");
        }
        if (req.importance() < 0 || req.importance() > 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "importance must be between 1 and 10 (0 = default), got " + req.importance());
        }
        if (req.validUntil() != null && !req.validUntil().isBlank()) {
            try {
                Instant.parse(req.validUntil().trim());
            } catch (java.time.format.DateTimeParseException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "validUntil must be ISO-8601, e.g. 2026-10-01T00:00:00Z");
            }
        }
    }

    private MemoryItem doStore(StoreRequest req, String userId) {
        int importance = req.importance() > 0 ? req.importance() : 5;
        Instant now = Instant.now();
        Instant validUntil = null;
        if (req.validUntil() != null && !req.validUntil().isBlank()) {
            validUntil = Instant.parse(req.validUntil().trim());
        }
        MemoryItem item = new MemoryItem(
            req.id() != null ? req.id() : ("mem_" + UUID.randomUUID()),
            userId,
            req.content(),
            req.summary(),
            req.tags() != null ? req.tags() : List.of(),
            importance,
            now,
            now,
            validUntil
        );
        return item;
    }

    @GetMapping("/{userId}")
    public Mono<ApiResponse<Map<String, Object>>> listByUser(@PathVariable String userId,
            @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        LongTermMemory ltm = ltmOrUnavailable();
        if (!CallerAuthorization.canAccess(authUser, userId, authScopes)) {
            return Mono.error(notFound());
        }
        String effectiveUser = CallerAuthorization.effectiveUser(authUser, userId, authScopes);
        return requireMemoryPlan(authUser, authScopes)
            .then(ltm.getAll(effectiveUser)
                .map(list -> ApiResponse.ok(Map.of("userId", userId, "items", list))));
    }

    @DeleteMapping("/{id}")
    public Mono<ApiResponse<Map<String, Object>>> delete(@PathVariable String id,
            @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        LongTermMemory ltm = ltmOrUnavailable();
        if (CallerAuthorization.isWildcard(authScopes)) {
            return ltm.delete(id)
                .map(ok -> ApiResponse.ok(Map.of("id", id, "deleted", ok)));
        }
        String userId = CallerAuthorization.effectiveUser(authUser, null);
        Mono<Void> plan = requireMemoryPlan(authUser, authScopes);
        return plan.then(ltm.getAll(userId)
            .flatMap(items -> items.stream().anyMatch(item -> id.equals(item.id()))
                ? ltm.delete(id).map(ok -> ApiResponse.ok(Map.of("id", id, "deleted", ok)))
                : Mono.error(notFound())));
    }

    @PostMapping("/{userId}/compress")
    public Mono<ApiResponse<Map<String, Object>>> compress(@PathVariable String userId,
            @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        LongTermMemory ltm = ltmOrUnavailable();
        if (!CallerAuthorization.canAccess(authUser, userId, authScopes)) {
            return Mono.error(notFound());
        }
        String effectiveUser = CallerAuthorization.effectiveUser(authUser, userId, authScopes);
        return requireMemoryPlan(authUser, authScopes)
            .then(ltm.compress(effectiveUser)
                .map(compressed -> ApiResponse.ok(Map.of("userId", userId, "compressed", compressed))));
    }

    // ===== DTOs =====

    public record SearchRequest(String userId, String query, int topK) {}

    public record StoreRequest(
        String id,
        String userId,
        String content,
        String summary,
        List<String> tags,
        int importance,
        String validUntil
    ) {}
}
