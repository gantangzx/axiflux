package com.gantang.tianshu.spring.controller;

import com.gantang.tianshu.spring.auth.AuthWebFilter;
import com.gantang.tianshu.spring.auth.CallerAuthorization;
import com.gantang.tianshu.spring.web.ApiResponse;
import com.gantang.tianshu.storage.entity.ToolExecutionEntity;
import com.gantang.tianshu.storage.repository.ToolExecutionRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Audit + abuse-alert API over the {@code tool_executions} table.
 *
 * <pre>
 * GET /api/v1/audits/tool-executions?page=&size=&tool=&session=&success=
 * GET /api/v1/audits/alerts?windowMinutes=
 * </pre>
 *
 * <p><b>Authorization:</b> audit rows carry a {@code userId}, so they are tenant
 * data. A caller holding {@code audit:read} (or the dev wildcard) sees the whole
 * deployment; everyone else is narrowed to their own rows — including the
 * {@code session} filter, which would otherwise let anyone enumerate another
 * user's tool calls, error strings and session ids. A request with no injected
 * identity is refused outright rather than treated as "unrestricted".
 *
 * <p>Requires a datasource (JPA). When no repository is wired the endpoints
 * return 503 so the console degrades gracefully.
 */
@RestController
@RequestMapping("/api/v1/audits")
public class AuditController {

    private final ObjectProvider<ToolExecutionRepository> repo;

    public AuditController(ObjectProvider<ToolExecutionRepository> repo) {
        this.repo = repo;
    }

    /**
     * The {@code userId} every query is pinned to: {@code null} for an audit reader
     * (deployment-wide), the caller's own id otherwise. Only meaningful once
     * {@link #denyAnonymous} has confirmed there is an identity to pin to.
     */
    private static String tenantFilter(String authUser, String authScopes) {
        return CallerAuthorization.isAuditReader(authScopes) ? null : authUser;
    }

    /** 403 when there is no identity to scope the query to. */
    private static void denyAnonymous(String authUser, String authScopes) {
        if (CallerAuthorization.isAuditReader(authScopes)) return;
        if (authUser != null && !authUser.isBlank()) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
            "audit access requires an authenticated caller or the '"
                + CallerAuthorization.ADMIN_SCOPE_AUDIT + "' scope");
    }

    @GetMapping("/tool-executions")
    public Mono<ApiResponse<Map<String, Object>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String tool,
            @RequestParam(required = false) String session,
            @RequestParam(required = false) Boolean success,
            @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        // Blocking JPA — bridge off the Netty event loop onto boundedElastic.
        return Mono.fromCallable(() ->
            ApiResponse.ok(listBlocking(page, size, tool, session, success, authUser, authScopes)))
            .subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> listBlocking(
            int page, int size, String tool, String session, Boolean success,
            String authUser, String authScopes) {
        ToolExecutionRepository r = repo.getIfAvailable();
        if (r == null) throw unavailable();

        denyAnonymous(authUser, authScopes);
        String userFilter = tenantFilter(authUser, authScopes);

        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 0);
        Page<ToolExecutionEntity> result = r.search(
            blank(tool), blank(session), userFilter, success, PageRequest.of(safePage, safeSize));

        List<Map<String, Object>> items = new ArrayList<>();
        for (ToolExecutionEntity e : result.getContent()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", e.getId());
            row.put("toolName", e.getToolName());
            row.put("sessionId", e.getSessionId());
            row.put("userId", e.getUserId());
            row.put("callId", e.getCallId());
            row.put("success", e.getSuccess());
            row.put("durationMs", e.getDurationMs());
            row.put("error", e.getError());
            row.put("createdAt", e.getCreatedAt());
            items.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("page", safePage);
        out.put("size", safeSize);
        out.put("total", result.getTotalElements());
        out.put("totalPages", result.getTotalPages());
        out.put("scope", userFilter == null ? "deployment" : "self");
        return out;
    }

    /**
     * Lightweight abuse signal: counts + per-tool rollup over a recent window.
     * A high failure/denied rate or a single tool firing far above peers flags
     * a possible retry-loop / prompt-injection / runaway agent.
     *
     * <p>Aggregated over the caller's own rows unless they hold {@code audit:read}:
     * deployment-wide totals are themselves a disclosure of other tenants' activity.
     */
    @GetMapping("/alerts")
    public Mono<ApiResponse<Map<String, Object>>> alerts(
            @RequestParam(defaultValue = "10") int windowMinutes,
            @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        // Blocking JPA rollups — bridge off the Netty event loop.
        return Mono.fromCallable(() ->
            ApiResponse.ok(alertsBlocking(windowMinutes, authUser, authScopes)))
            .subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> alertsBlocking(
            int windowMinutes, String authUser, String authScopes) {
        ToolExecutionRepository r = repo.getIfAvailable();
        if (r == null) throw unavailable();

        denyAnonymous(authUser, authScopes);
        String userFilter = tenantFilter(authUser, authScopes);

        int mins = Math.min(Math.max(windowMinutes, 1), 1440);
        Instant since = Instant.now().minus(mins, ChronoUnit.MINUTES);

        long total = r.countSince(since, userFilter);
        long failures = r.countSinceBySuccess(since, false, userFilter);

        List<Map<String, Object>> byTool = new ArrayList<>();
        for (Object[] row : r.toolRollup(since, userFilter)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tool", row[0]);
            m.put("total", ((Number) row[1]).longValue());
            m.put("failures", row[2] == null ? 0L : ((Number) row[2]).longValue());
            byTool.add(m);
        }

        // Simple heuristic alerts.
        List<String> alerts = new ArrayList<>();
        double failRate = total > 0 ? (double) failures / total : 0.0;
        if (total >= 20 && failRate >= 0.5) {
            alerts.add(String.format("High tool failure rate: %d/%d failed (%.0f%%) in last %d min",
                    failures, total, failRate * 100, mins));
        }
        for (Map<String, Object> m : byTool) {
            long toolTotal = (long) m.get("total");
            long toolFail = (long) m.get("failures");
            if (toolTotal >= 10 && toolFail >= 5 && (double) toolFail / toolTotal >= 0.5) {
                alerts.add(String.format("Tool '%s' failing repeatedly: %d/%d failed",
                        m.get("tool"), toolFail, toolTotal));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windowMinutes", mins);
        out.put("total", total);
        out.put("failures", failures);
        out.put("byTool", byTool);
        out.put("alerts", alerts);
        out.put("level", alerts.isEmpty() ? "ok" : "warn");
        out.put("scope", userFilter == null ? "deployment" : "self");
        return out;
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "audit storage not available");
    }

    private static String blank(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
