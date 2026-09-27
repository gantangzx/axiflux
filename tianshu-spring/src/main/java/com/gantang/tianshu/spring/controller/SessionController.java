package com.gantang.tianshu.spring.controller;

import com.gantang.tianshu.api.session.Message;
import com.gantang.tianshu.api.session.Session;
import com.gantang.tianshu.api.session.SessionManager;
import com.gantang.tianshu.spring.auth.CallerAuthorization;
import com.gantang.tianshu.spring.auth.CallerGuard;
import com.gantang.tianshu.spring.web.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Session management REST API.
 *
 * <pre>
 * GET    /api/v1/sessions?userId=xxx&state=ACTIVE&page=0&size=100
 * GET    /api/v1/sessions/{id}
 * DELETE /api/v1/sessions/{id}
 * POST   /api/v1/sessions/{id}/close
 * GET    /api/v1/sessions/{id}/messages?lastN=20
 * PATCH  /api/v1/sessions/{id}          body {"title":"..."}  rename (stored in metadata)
 * </pre>
 *
 * <p>When auth is enabled the {@code X-Tianshu-User} header (injected by the
 * auth filter from the verified token) is authoritative: it overrides any
 * {@code userId} parameter and every per-session access is checked against the
 * session owner. Cross-user access answers 404 (no existence disclosure).
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    /** Largest page a client may ask for, so a big {@code size} cannot exhaust the heap. */
    private static final int MAX_PAGE_SIZE = 500;

    private final SessionManager sessions;
    private final CallerGuard guard;

    public SessionController(SessionManager sessions, CallerGuard guard) {
        this.sessions = sessions;
        this.guard = guard;
    }

    @GetMapping
    public Mono<ApiResponse<List<Map<String, Object>>>> list(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String state,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestHeader(value = com.gantang.tianshu.spring.auth.AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = com.gantang.tianshu.spring.auth.AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        String effective = CallerAuthorization.effectiveUser(authUser, userId, authScopes);
        int p = Math.max(0, page);
        int s = Math.clamp(size, 1, MAX_PAGE_SIZE);
        // Paged explicitly: a client that gets back a full page knows to ask for
        // the next one, which the unbounded form could not express.
        return ("ACTIVE".equalsIgnoreCase(state)
            ? sessions.listActiveByUser(effective, p, s)
            : sessions.listByUser(effective, p, s))
            .map(this::toSummary)
            .collectList()
            .map(ApiResponse::ok);
    }

    @PostMapping
    public Mono<ApiResponse<Map<String, Object>>> create(@RequestBody CreateRequest req,
            @RequestHeader(value = com.gantang.tianshu.spring.auth.AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = com.gantang.tianshu.spring.auth.AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        String sid = req.sessionId() != null && !req.sessionId().isBlank()
            ? req.sessionId()
            : java.util.UUID.randomUUID().toString();
        // Re-creating somebody else's session id must not hand it over; the guard
        // answers 404 for an existing session the caller does not own.
        // Blocking guard + getOrCreate (JDBC) run on boundedElastic, not Netty.
        return Mono.fromCallable(() -> {
                CallerGuard.Caller caller = guard.context(authUser, authScopes, req.userId(), req.sessionId());
                Session s = sessions.getOrCreate(sid,
                    caller.userId(),
                    req.agentId() != null ? req.agentId() : "default",
                    req.metadata() != null ? req.metadata() : Map.of());
                return ApiResponse.ok(Map.<String, Object>of(
                    "sessionId", s.sessionId(),
                    "userId", s.userId(),
                    "agentId", s.agentId(),
                    "state", s.state().name(),
                    "created", true
                ));
            })
            .subscribeOn(Schedulers.boundedElastic());
    }

    public record CreateRequest(
        String sessionId,
        String userId,
        String agentId,
        Map<String, Object> metadata
    ) {}

    /**
     * Load a session and enforce ownership; empty when missing OR not owned (404, no enumeration).
     * Evaluated on boundedElastic: the ownership check and session hydration are blocking.
     */
    private Mono<Session> ownedSession(String sessionId, String authUser, String authScopes) {
        return Mono.fromCallable(() -> {
                if (!guard.ownsSession(sessionId, authUser, authScopes)) {
                    return null;
                }
                return sessions.get(sessionId).orElse(null);
            })
            .subscribeOn(Schedulers.boundedElastic());
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found");
    }

    @GetMapping("/{sessionId}")
    public Mono<ApiResponse<Map<String, Object>>> get(@PathVariable String sessionId,
            @RequestHeader(value = com.gantang.tianshu.spring.auth.AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = com.gantang.tianshu.spring.auth.AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        return ownedSession(sessionId, authUser, authScopes)
            .map(s -> ApiResponse.ok(toDetail(s)))
            .switchIfEmpty(Mono.error(notFound()));
    }

    @DeleteMapping("/{sessionId}")
    public Mono<ApiResponse<Map<String, Object>>> delete(@PathVariable String sessionId,
            @RequestHeader(value = com.gantang.tianshu.spring.auth.AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = com.gantang.tianshu.spring.auth.AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        return ownedSession(sessionId, authUser, authScopes)
            .switchIfEmpty(Mono.error(notFound()))
            .flatMap(s -> sessions.delete(sessionId))
            .thenReturn(ApiResponse.ok(Map.<String, Object>of("sessionId", sessionId, "deleted", true)));
    }

    @PostMapping("/{sessionId}/close")
    public Mono<ApiResponse<Map<String, Object>>> close(@PathVariable String sessionId,
            @RequestHeader(value = com.gantang.tianshu.spring.auth.AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = com.gantang.tianshu.spring.auth.AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        return ownedSession(sessionId, authUser, authScopes)
            .switchIfEmpty(Mono.error(notFound()))
            // Session::close fires the blocking state hook; ownedSession emits on boundedElastic.
            .doOnNext(Session::close)
            .flatMap(s -> sessions.save(s).thenReturn(s))
            .map(s -> ApiResponse.ok(Map.<String, Object>of(
                "sessionId", s.sessionId(), "state", "CLOSED")));
    }

    @PatchMapping("/{sessionId}")
    public Mono<ApiResponse<Map<String, Object>>> rename(@PathVariable String sessionId,
            @RequestBody RenameRequest req,
            @RequestHeader(value = com.gantang.tianshu.spring.auth.AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = com.gantang.tianshu.spring.auth.AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        String title = req.title() == null ? "" : req.title().trim();
        // Metadata mutation triggers a blocking store hook (JPA UPDATE / Redis HSET);
        // run the whole load+mutate on boundedElastic, then reconcile via save().
        return ownedSession(sessionId, authUser, authScopes)
            .switchIfEmpty(Mono.error(notFound()))
            .flatMap(s -> Mono.fromRunnable(() -> {
                    if (title.isEmpty()) {
                        // Empty title clears the override; the UI then falls back to the short id.
                        s.updateMetadata("title", null);
                    } else {
                        s.updateMetadata("title", title);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then(sessions.save(s))
                .thenReturn(ApiResponse.ok(Map.<String, Object>of("sessionId", s.sessionId(), "title", title))));
    }

    public record RenameRequest(String title) {}

    @GetMapping("/{sessionId}/messages")
    public Mono<ApiResponse<Map<String, Object>>> messages(@PathVariable String sessionId,
                                                    @RequestParam(defaultValue = "50") int lastN,
                                                    @RequestParam(defaultValue = "0") int skip,
            @RequestHeader(value = com.gantang.tianshu.spring.auth.AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = com.gantang.tianshu.spring.auth.AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        int n = Math.clamp(lastN, 1, 500);
        int sk = Math.max(0, skip);
        return ownedSession(sessionId, authUser, authScopes)
            .switchIfEmpty(Mono.error(notFound()))
            .map(s -> {
                // Paged "load earlier": skip the newest `sk` messages, then take
                // the next `n` going backwards over the full stored history.
                List<Message> all = s.messages().getAll();
                int total = all.size();
                int end = Math.max(0, total - sk);
                int start = Math.max(0, end - n);
                List<Map<String, Object>> items = all.subList(start, end).stream()
                    .map(this::toDto).toList();
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("messages", items);
                out.put("total", total);
                out.put("hasEarlier", start > 0);
                return ApiResponse.ok(out);
            });
    }

    // ===== helpers =====

    private Map<String, Object> toSummary(Session s) {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("sessionId", s.sessionId());
        m.put("userId", s.userId());
        m.put("agentId", s.agentId());
        m.put("state", s.state().name());
        m.put("messageCount", s.messages().size());
        m.put("title", titleOf(s));
        Object usage = s.metadata() != null ? s.metadata().get("usage") : null;
        if (usage instanceof java.util.Map<?, ?> um && !um.isEmpty()) {
            m.put("usage", usage);
        }
        return m;
    }

    /** User-assigned display name from metadata, or empty string when unset. */
    private static String titleOf(Session s) {
        Object t = s.metadata() != null ? s.metadata().get("title") : null;
        return t != null ? t.toString() : "";
    }

    private Map<String, Object> toDetail(Session s) {
        return Map.of(
            "sessionId", s.sessionId(),
            "userId", s.userId(),
            "agentId", s.agentId(),
            "state", s.state().name(),
            "metadata", s.metadata(),
            "messageCount", s.messages().size()
        );
    }

    private Map<String, Object> toDto(Message m) {
        Map<String, Object> dto = new java.util.HashMap<>();
        dto.put("id", m.id());
        dto.put("role", m.role().name());
        dto.put("content", m.content() != null ? m.content() : "");
        dto.put("toolCallId", m.toolCallId() != null ? m.toolCallId() : "");
        dto.put("toolCalls", m.toolCalls() != null ? m.toolCalls() : List.of());
        dto.put("attachments", m.attachments() != null ? m.attachments() : Map.of());
        dto.put("timestamp", m.timestamp().toString());
        dto.put("reasoning", m.reasoning() != null ? m.reasoning() : "");
        return dto;
    }
}
