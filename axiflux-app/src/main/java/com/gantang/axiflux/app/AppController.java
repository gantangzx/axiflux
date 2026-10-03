package com.gantang.axiflux.app;

import com.gantang.reaxon.api.agent.Agent;
import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.agent.AgentEvent;
import com.gantang.reaxon.api.session.SessionManager;
import com.gantang.axiflux.spring.auth.AuthWebFilter;
import com.gantang.axiflux.spring.auth.CallerGuard;
import com.gantang.axiflux.spring.web.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Complete example controller demonstrating:
 * - Session management (create/list/close)
 * - Streaming chat with tool calls
 * - Multi-turn conversation
 *
 * <p><b>Authorization:</b> this module is what the Dockerfile ships, so these are
 * live endpoints, not demo code. Every {@code userId} and {@code sessionId} in a
 * request body is untrusted input: it is resolved through {@link CallerGuard},
 * which pins the identity to the verified {@code X-axiflux-User} header (a
 * body-supplied user is honoured only for a wildcard dev caller) and rejects a
 * session owned by another user with 404. Session listing is likewise scoped to
 * the caller instead of to a query parameter.
 */
@RestController
@RequestMapping("/api/app")
public class AppController {

    /** Largest page a client may ask for, so a big {@code size} cannot exhaust the heap. */
    private static final int MAX_PAGE_SIZE = 200;

    private final Agent agent;
    private final SessionManager sessions;
    private final CallerGuard guard;

    public AppController(Agent agent, SessionManager sessions, CallerGuard guard) {
        this.agent = agent;
        this.sessions = sessions;
        this.guard = guard;
    }

    /**
     * Create a new session, owned by the authenticated caller.
     *
     * POST /api/app/sessions
     */
    @PostMapping("/sessions")
    public Mono<ApiResponse<Map<String, String>>> createSession(
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        String requestedUser = body != null ? body.get("userId") : null;
        // No session addressed yet: this only resolves the owner.
        String userId = guard.context(authUser, authScopes, requestedUser, null).userId();
        String sessionId = "app-" + System.currentTimeMillis();

        return Mono.fromCallable(() -> sessions.getOrCreate(sessionId, userId, "default", Map.of()))
            .map(session -> ApiResponse.ok(Map.of(
                "sessionId", session.sessionId(),
                "userId", session.userId(),
                "state", session.state().name()
            )));
    }

    /**
     * List the caller's own active sessions, one page at a time.
     *
     * GET /api/app/sessions?page=0&size=50
     */
    @GetMapping("/sessions")
    public Mono<ApiResponse<List<Map<String, String>>>> listSessions(
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        // The userId parameter is only honoured for a wildcard (console) caller;
        // otherwise it is replaced by the verified identity.
        String owner = guard.context(authUser, authScopes, userId, null).userId();
        return sessions.listActiveByUser(owner, Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE))
            .map(s -> Map.of(
                "sessionId", s.sessionId(),
                "userId", s.userId(),
                "state", s.state().name()
            ))
            .collectList()
            .map(ApiResponse::ok);
    }

    /**
     * Close a session the caller owns.
     *
     * DELETE /api/app/sessions/{sessionId}
     */
    @DeleteMapping("/sessions/{sessionId}")
    public Mono<ApiResponse<Map<String, Object>>> closeSession(@PathVariable String sessionId,
            @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        // Throws 404 when the session exists but belongs to someone else.
        guard.requireSessionOwner(sessionId, authUser, authScopes);
        return Mono.fromCallable(() -> sessions.get(sessionId)
                .map(session -> {
                    session.close();
                    return sessions.save(session);
                }))
            .flatMap(maybeSave -> maybeSave
                .map(mono -> mono.thenReturn(ApiResponse.ok(Map.<String, Object>of(
                    "sessionId", sessionId, "state", "CLOSED"))))
                .orElseGet(() -> Mono.error(new ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "session not found"))));
    }

    /**
     * Streaming chat endpoint with SSE.
     *
     * POST /api/app/chat/stream
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentEvent> chatStream(@RequestBody ChatRequest request,
            @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        AgentContext ctx = context(request.sessionId(), request.userId(), request.message(),
            "You are a helpful AI assistant. You have access to tools: calculator, http_client, web_search.",
            authUser, authScopes);

        return agent.processStream(ctx)
            .timeout(Duration.ofMinutes(5))
            .onErrorResume(e -> Flux.just(new AgentEvent(
                AgentEvent.Type.ERROR,
                e.getClass().getSimpleName() + ": " + e.getMessage(),
                null, null, null, null
            )));
    }

    /**
     * Synchronous chat (waits for DONE event).
     *
     * POST /api/app/chat
     */
    @PostMapping("/chat")
    public Mono<ApiResponse<Map<String, Object>>> chat(@RequestBody ChatRequest request,
            @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        StringBuilder result = new StringBuilder();

        AgentContext ctx = context(request.sessionId(), request.userId(), request.message(),
            "You are a helpful AI assistant.", authUser, authScopes);

        return agent.processStream(ctx)
            .doOnNext(event -> {
                if (event.type() == AgentEvent.Type.TEXT_TOKEN) {
                    result.append(event.content());
                }
            })
            .filter(event -> event.type() == AgentEvent.Type.DONE)
            .next()
            .map(done -> ApiResponse.ok(Map.<String, Object>of(
                "status", "success",
                "response", result.toString(),
                "sessionId", ctx.sessionId()
            )))
            .timeout(Duration.ofMinutes(2));
    }

    /**
     * Multi-turn conversation example.
     *
     * POST /api/app/conversation
     */
    @PostMapping("/conversation")
    public Flux<AgentEvent> conversation(@RequestBody ConversationRequest request,
            @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        // Authorize once, up front: the whole turn sequence runs as one caller in
        // one session, so a per-message re-resolve would only repeat the same work.
        CallerGuard.Caller caller = guard.context(
            authUser, authScopes, request.userId(), request.sessionId());

        return Flux.fromIterable(request.messages())
            .concatMap(message -> {
                AgentContext ctx = AgentContext.builder()
                    .sessionId(caller.sessionId())
                    .userId(caller.userId())
                    .metadata(caller.metadata())
                    .currentQuery(message)
                    .build();

                return agent.processStream(ctx)
                    .filter(e -> e.type() == AgentEvent.Type.TEXT_TOKEN
                              || e.type() == AgentEvent.Type.DONE
                              || e.type() == AgentEvent.Type.ERROR)
                    .takeUntil(e -> e.type() == AgentEvent.Type.DONE);
            });
    }

    /** Resolve caller + session and build the agent context for a single turn. */
    private AgentContext context(String sessionId, String requestedUser, String message,
                                 String systemPrompt, String authUser, String authScopes) {
        CallerGuard.Caller caller = guard.context(authUser, authScopes, requestedUser, sessionId);
        return AgentContext.builder()
            .sessionId(caller.sessionId())
            .userId(caller.userId())
            .metadata(caller.metadata())
            .currentQuery(message != null ? message : "")
            .systemPrompt(systemPrompt)
            .build();
    }

    // ─── DTOs ────────────────────────────────────────────────────────

    public record ChatRequest(
        String sessionId,
        String userId,
        String message
    ) {}

    public record ConversationRequest(
        String sessionId,
        String userId,
        List<String> messages
    ) {}
}
