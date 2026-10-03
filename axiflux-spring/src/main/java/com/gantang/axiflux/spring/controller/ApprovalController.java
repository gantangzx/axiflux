package com.gantang.axiflux.spring.controller;

import com.gantang.reaxon.api.agent.ApprovalManager;
import com.gantang.axiflux.spring.auth.AuthWebFilter;
import com.gantang.axiflux.spring.auth.CallerAuthorization;
import com.gantang.axiflux.spring.web.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/**
 * REST endpoints for human-in-the-loop tool approval.
 *
 * <pre>
 * GET    /api/v1/approvals              — list this caller's pending approvals
 * GET    /api/v1/approvals/{callId}     — get one pending approval
 * POST   /api/v1/approvals/{callId}/approve  — approve
 * POST   /api/v1/approvals/{callId}/reject   — reject
 * </pre>
 *
 * <p>Every endpoint is scoped to the authenticated caller. This is the most
 * privileged surface in the API: a pending approval is, by construction, a tool
 * call the risk policy refused to run unattended (file writes, shell execution,
 * sub-agent delegation). Letting one user resolve another user's approval would
 * turn the human-in-the-loop gate into a way to execute DESTRUCTIVE tool calls
 * inside someone else's session. The approver id is likewise taken from the
 * verified identity, never from a request parameter, so the audit trail records
 * who actually decided.
 *
 * <p>{@link ApprovalManager}'s synchronous surface (list/get/approve/reject/
 * grantSession) bridges the reactive {@code ApprovalStore} with {@code block()},
 * so it must never run on a WebFlux event-loop/parallel thread. Every handler
 * wraps its work in {@code Mono.fromCallable(...).subscribeOn(boundedElastic)}
 * — the same pattern the other controllers use for blocking service calls.
 */
@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalController {

    private static final Logger auditLog = LoggerFactory.getLogger(ApprovalController.class);

    private final ApprovalManager approvalManager;

    public ApprovalController(ApprovalManager approvalManager) {
        this.approvalManager = approvalManager;
    }

    @GetMapping
    public Mono<ApiResponse<List<ApprovalManager.ApprovalRequest>>> list(
            @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes,
            @RequestParam(required = false) String userId) {
        // A wildcard caller may inspect the whole queue (operator console); everyone
        // else only ever sees their own, regardless of the requested userId.
        return Mono.fromCallable(() -> {
            if (CallerAuthorization.isWildcard(authScopes)) {
                return approvalManager.listPending(userId);
            }
            return approvalManager.listPending(CallerAuthorization.effectiveUser(authUser, userId));
        }).subscribeOn(Schedulers.boundedElastic()).map(ApiResponse::ok);
    }

    @GetMapping("/{callId}")
    public Mono<ApiResponse<ApprovalManager.ApprovalRequest>> get(
            @PathVariable String callId,
            @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        return authorized(callId, authUser, authScopes)
            .map(ApiResponse::ok)
            .switchIfEmpty(Mono.error(notFound(callId)));
    }

    @PostMapping("/{callId}/approve")
    public Mono<ApiResponse<Map<String, Object>>> approve(
            @PathVariable String callId,
            @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes,
            @RequestParam(required = false, defaultValue = "once") String scope) {
        return authorized(callId, authUser, authScopes)
            .flatMap(req -> Mono.fromCallable(() -> {
                String approverId = CallerAuthorization.effectiveUser(authUser, req.userId());
                boolean ok = approvalManager.approve(callId, approverId);
                String scopeUsed = "once";
                // "session" scope: remember the grant so future calls to the same
                // tool in the same session skip the prompt ("approve for this session").
                if (ok && "session".equalsIgnoreCase(scope)) {
                    approvalManager.grantSession(req.sessionId(), req.toolName(), approverId);
                    scopeUsed = "session";
                }
                // Audit authz P2-5: record the caller's verified scope snapshot so
                // the trail distinguishes a real scoped grant from the dev
                // wildcard identity. Production deployments should set
                // axiflux.auth.require-explicit-scopes=true (ScopePolicy strict
                // mode) so no-privilege callers never default to "*".
                auditLog.info("approval decision action=approve callId={} tool={} approver={} "
                        + "grantedScope={} callerScopes={} devIdentity={} ok={}",
                    callId, req.toolName(), approverId, scopeUsed,
                    CallerAuthorization.scopeSnapshot(authScopes),
                    CallerAuthorization.isWildcard(authScopes), ok);
                Map<String, Object> respBody = new java.util.HashMap<>();
                respBody.put("success", ok);
                respBody.put("callId", callId);
                respBody.put("scope", scopeUsed);
                respBody.put("action", "approved");
                return ApiResponse.ok(respBody);
            }).subscribeOn(Schedulers.boundedElastic()))
            .switchIfEmpty(Mono.error(notFound(callId)));
    }

    @PostMapping("/{callId}/reject")
    public Mono<ApiResponse<Map<String, Object>>> reject(
            @PathVariable String callId,
            @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes,
            @RequestBody(required = false) Map<String, String> body) {
        return authorized(callId, authUser, authScopes)
            .flatMap(req -> Mono.fromCallable(() -> {
                String approverId = CallerAuthorization.effectiveUser(authUser, req.userId());
                String reason = body != null ? body.getOrDefault("reason", "") : "";
                boolean ok = approvalManager.reject(callId, approverId, reason);
                // Audit authz P2-5: caller scope snapshot (see approve).
                auditLog.info("approval decision action=reject callId={} tool={} approver={} "
                        + "callerScopes={} devIdentity={} ok={}",
                    callId, req.toolName(), approverId,
                    CallerAuthorization.scopeSnapshot(authScopes),
                    CallerAuthorization.isWildcard(authScopes), ok);
                Map<String, Object> respBody = new java.util.HashMap<>();
                respBody.put("success", ok);
                respBody.put("callId", callId);
                respBody.put("action", "rejected");
                return ApiResponse.ok(respBody);
            }).subscribeOn(Schedulers.boundedElastic()))
            .switchIfEmpty(Mono.error(notFound(callId)));
    }

    /**
     * The pending approval, but only if this caller owns it (or holds a wildcard).
     * Runs on boundedElastic because the manager's sync get() blocks on the store.
     */
    private Mono<ApprovalManager.ApprovalRequest> authorized(
            String callId, String authUser, String authScopes) {
        return Mono.fromCallable(() -> approvalManager.get(callId)
                .filter(r -> CallerAuthorization.canAccess(authUser, r.userId(), authScopes)))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(Mono::justOrEmpty);
    }

    /**
     * 404 for both "no such approval" and "not yours" — telling an unauthorized
     * caller that a given callId exists is already a leak about another session.
     */
    private static org.springframework.web.server.ResponseStatusException notFound(String callId) {
        return new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.NOT_FOUND, "approval not found: " + callId);
    }
}
