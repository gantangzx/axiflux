package com.gantang.axiflux.spring.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gantang.reaxon.api.agent.Agent;
import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.agent.AgentEvent;
import com.gantang.reaxon.api.session.SessionManager;
import com.gantang.axiflux.spring.config.props.AgentProperties;
import com.gantang.axiflux.spring.auth.CallerAuthorization;
import com.gantang.axiflux.spring.auth.CallerGuard;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket handler for real-time agent communication.
 *
 * Protocol (JSON text frames):
 *
 * Client → Server:
 *   Chat:    { "type": "chat", "sessionId": "...", "userId": "...", "content": "...", "systemPrompt": "..." }
 *   Interrupt: { "type": "interrupt", "sessionId": "..." }
 *   Ping:    { "type": "ping" }
 *
 * Server → Client:
 *   Agent events as JSON: { "type": "text_token|tool_call|tool_result|done|error", ... }
 *   Pong:    { "type": "pong" }
 */
public class AgentWebSocketHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentWebSocketHandler.class);

    private Agent agent;
    private SessionManager sessionManager;
    private ObjectMapper objectMapper;
    private AgentProperties agentProps;
    private CallerGuard guard;
    private com.gantang.axiflux.spring.service.QuotaServiceSpi quotas;

    // wsSessionId → event sink for outbound messages
    private final Map<String, Sinks.Many<String>> sessionSinks = new ConcurrentHashMap<>();

    // Setter injection (allows manual bean construction)
    public void setAgent(Agent agent) { this.agent = agent; }
    public void setSessionManager(SessionManager sm) { this.sessionManager = sm; }
    public void setObjectMapper(ObjectMapper om) { this.objectMapper = om; }
    public void setAgentProperties(AgentProperties props) { this.agentProps = props; }
    public void setCallerGuard(CallerGuard guard) { this.guard = guard; }
    public void setQuotaService(com.gantang.axiflux.spring.service.QuotaServiceSpi quotas) {
        this.quotas = quotas;
    }

    @Override
    public @NonNull List<String> getSubProtocols() {
        return List.of("axiflux");
    }

    @Override
    public @NonNull Mono<Void> handle(WebSocketSession wsSession) {
        String wsId = wsSession.getId();
        log.info("WebSocket connected: {}", wsId);

        // Authenticated identity (injected by AuthWebFilter on the upgrade request).
        // When present it overrides any userId/scopes supplied in frames, so a
        // verified token cannot be used to drive someone else's session.
        // Null-guarded: some test doubles (and non-handshake invocations) have no HandshakeInfo.
        var handshakeInfo = wsSession.getHandshakeInfo();
        var upgradeHeaders = handshakeInfo != null ? handshakeInfo.getHeaders() : new org.springframework.http.HttpHeaders();
        String authUser = upgradeHeaders.getFirst(com.gantang.axiflux.spring.auth.AuthWebFilter.H_USER);
        String authScopes = upgradeHeaders.getFirst(com.gantang.axiflux.spring.auth.AuthWebFilter.H_SCOPES);
        final String authedUser = (authUser != null && !authUser.isBlank()) ? authUser : null;
        final String authedScopes = (authScopes != null && !authScopes.isBlank()) ? authScopes : null;

        Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();
        sessionSinks.put(wsId, sink);

        // Outbound: sink → ws
        Disposable outSub = sink.asFlux()
            .filter(text -> !text.isEmpty())
            .map(wsSession::textMessage)
            .flatMap(msg -> wsSession.send(Mono.just(msg)))
            .doOnError(e -> log.error("WS send error: {}", wsId, e))
            .doFinally(sig -> {
                log.info("WS outbound closed: {}", wsId);
                sessionSinks.remove(wsId);
            })
            .subscribe();

        // Inbound: ws → handler
        Mono<Void> inbound = wsSession.receive()
            .map(WebSocketMessage::getPayloadAsText)
            .flatMap(payload -> dispatch(wsId, payload, authedUser, authedScopes))
            .then()
            .doFinally(sig -> {
                log.info("WebSocket disconnected: {}", wsId);
                sessionSinks.remove(wsId);
                outSub.dispose();
            });

        return inbound;
    }

    private Mono<Void> dispatch(String wsId, String payload, String authUser, String authScopes) {
        JsonNode node;
        try {
            node = objectMapper.readTree(payload);
        } catch (JsonProcessingException e) {
            log.warn("Malformed WS frame on {}: {}", wsId, e.getOriginalMessage());
            return sendError(wsId, "Invalid JSON");
        }
        String type = node.has("type") ? node.get("type").asText() : "chat";
        // Handler faults are reported separately from parse faults: folding both into
        // "Invalid JSON" made every server-side bug look like a client-side bad frame.
        try {
            return switch (type) {
                case "ping" -> sendPong(wsId);
                case "interrupt" -> handleInterrupt(wsId, node, authUser, authScopes);
                case "chat" -> handleChat(wsId, node, authUser, authScopes);
                default -> {
                    log.warn("Unknown WS message type: {}", type);
                    yield Mono.empty();
                }
            };
        } catch (Exception e) {
            log.error("Failed to handle WS frame type '{}' on {}", type, wsId, e);
            return sendError(wsId, "internal error");
        }
    }

    private Mono<Void> handleChat(String wsId, JsonNode node, String authUser, String authScopes) {
        String reqSessionId = node.has("sessionId") && !node.get("sessionId").isNull()
            ? node.get("sessionId").asText() : wsId;
        // In-band error rather than a status code: this transport has no response code.
        if (!guard.ownsSession(reqSessionId, authUser, authScopes)) {
            return sendError(wsId, "session not found");
        }
        String frameUser = node.has("userId") && !node.get("userId").isNull()
            ? node.get("userId").asText() : null;
        // Verified identity from the upgrade wins over the frame-supplied userId;
        // a frame userId is honoured only for a wildcard (dev) caller.
        String userId = CallerAuthorization.effectiveUser(authUser, frameUser, authScopes);
        String content = node.has("content") ? node.get("content").asText() : "";
        String systemPrompt = node.has("systemPrompt") && !node.get("systemPrompt").isNull()
            ? node.get("systemPrompt").asText()
            : agentProps.getDefaultSystemPrompt();
        String forcedModel = node.has("forcedModel") && !node.get("forcedModel").isNull()
            ? node.get("forcedModel").asText() : null;
        // Optional BYOK provider key for this turn: carried in-memory only, never
        // logged or persisted; absent → the statically configured provider key.
        String byokApiKey = node.has("byokApiKey") && !node.get("byokApiKey").isNull()
            ? node.get("byokApiKey").asText() : null;
        if (byokApiKey != null && byokApiKey.isBlank()) byokApiKey = null;

        if (content.isBlank()) {
            return sendError(wsId, "content is required");
        }

        // Resolve the caller's org and enforce the monthly quota in one place.
        // guard.context verifies session ownership (it ownsSession's first); a
        // 404 means the addressed session belongs to someone else.
        CallerGuard.Caller resolved;
        try {
            resolved = guard.context(authUser, authScopes, frameUser, reqSessionId);
            if (quotas != null) {
                quotas.requireWithinQuota(toIdentity(resolved));
            }
        } catch (com.gantang.axiflux.spring.service.QuotaExceededException e) {
            return sendError(wsId, e.getMessage());
        } catch (org.springframework.web.server.ResponseStatusException e) {
            return sendError(wsId, "session not found");
        }

        AgentContext ctx = AgentContext.builder()
            .sessionId(reqSessionId)
            .userId(userId)
            .metadata(resolved.metadata())
            .currentQuery(content)
            .systemPrompt(systemPrompt)
            .forcedModel(forcedModel)
            .byokApiKey(byokApiKey)
            .build();

        return agent.processStream(ctx)
            .doOnNext(event -> emitEvent(wsId, event))
            .doOnError(e -> {
                log.error("Agent stream error for session {}", reqSessionId, e);
                emitJson(wsId, Map.of("type", "error", "content", "agent processing failed"));
            })
            .onErrorResume(e -> Mono.empty())
            .then();
    }

    private Mono<Void> handleInterrupt(String wsId, JsonNode node, String authUser, String authScopes) {
        String sessionId = node.has("sessionId") ? node.get("sessionId").asText() : null;
        if (sessionId == null) return Mono.empty();
        // An unknown id is refused too: there is nothing to interrupt, and a distinct
        // reply would tell a prober which ids exist.
        if (!guard.ownsSession(sessionId, authUser, authScopes)
                || sessionManager.get(sessionId).isEmpty()) {
            return sendError(wsId, "session not found");
        }
        agent.interrupt(sessionId);
        log.info("Interrupt sent for session {}", sessionId);
        return Mono.empty();
    }

    private Mono<Void> sendPong(String wsId) {
        return Mono.fromRunnable(() -> emitJson(wsId, Map.of("type", "pong")));
    }

    private Mono<Void> sendError(String wsId, String message) {
        return Mono.fromRunnable(() -> emitJson(wsId, Map.of("type", "error", "content", message)));
    }

    private static com.gantang.reaxon.api.auth.CallerIdentity toIdentity(CallerGuard.Caller c) {
        return new com.gantang.reaxon.api.auth.CallerIdentity(
            c.userId(), c.orgId(), c.orgRole(), CallerGuard.scopeList(c.scopes()));
    }

    // ===== Outbound helpers =====

    private void emitEvent(String wsId, AgentEvent event) {
        try {
            ObjectNode json = objectMapper.createObjectNode();
            json.put("type", event.type().name().toLowerCase());

            if (event.content() != null) json.put("content", event.content());
            if (event.callId() != null) json.put("callId", event.callId());
            if (event.toolName() != null) json.put("toolName", event.toolName());
            if (event.arguments() != null && !event.arguments().isEmpty()) {
                json.set("arguments", objectMapper.valueToTree(event.arguments()));
            }
            if (event.rawPayload() != null) json.put("rawPayload", event.rawPayload());

            emitRaw(wsId, objectMapper.writeValueAsString(json));
        } catch (Exception e) {
            log.error("Failed to serialize event", e);
        }
    }

    private void emitJson(String wsId, Map<String, String> data) {
        try {
            emitRaw(wsId, objectMapper.writeValueAsString(data));
        } catch (Exception e) {
            log.error("Failed to serialize JSON", e);
        }
    }

    private void emitRaw(String wsId, String json) {
        Sinks.Many<String> sink = sessionSinks.get(wsId);
        if (sink != null) {
            Sinks.EmitResult result = sink.tryEmitNext(json);
            if (result.isFailure()) {
                log.warn("Failed to emit to WS {}: {}", wsId, result);
            }
        }
    }
}
