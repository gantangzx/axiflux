package com.gantang.axiflux.spring.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.reaxon.api.agent.Agent;
import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.auth.CallerIdentity;
import com.gantang.reaxon.api.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Consumes {@link ScheduledTaskFiredEvent} and dispatches to the appropriate handler.
 *
 * <p>Dispatch rules:
 * <ul>
 *   <li>{@code agentTurn} — calls {@code Agent.process(subContext)} with the
 *       payload as the user query; runs async so it doesn't block the transaction thread.</li>
 *   <li>{@code systemEvent} — adds a system message to the session.</li>
 * </ul>
 *
 * <p>Registered as an explicit {@code @Bean} in {@link com.gantang.axiflux.spring.config.TaskSchedulerConfiguration}:
 * the spring module is a starter and is not covered by component scanning, so a
 * {@code @Component} annotation here would silently leave the listener (and thus
 * every scheduled task) dead.
 */
public class ScheduledTaskEventListener {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskEventListener.class);

    private final Agent agent;
    private final SessionManager sessionManager;
    private final ObjectMapper om;

    public ScheduledTaskEventListener(
            ObjectProvider<Agent> agentProvider,
            ObjectProvider<SessionManager> sessionProvider,
            ObjectMapper om) {
        this.agent = agentProvider.getIfAvailable();
        this.sessionManager = sessionProvider.getIfAvailable();
        this.om = om != null ? om : new ObjectMapper();
    }

    @Async
    @EventListener
    public void onScheduledTaskFired(ScheduledTaskFiredEvent event) {
        String kind = event.kind() != null ? event.kind() : "agentTurn";
        log.info("Dispatching scheduled task: id={} name={} kind={}",
            event.taskId(), event.taskName(), kind);

        try {
            switch (kind) {
                case "agentTurn" -> handleAgentTurn(event);
                case "systemEvent" -> handleSystemEvent(event);
                default -> log.warn("Unknown task kind '{}' for task {}", kind, event.taskId());
            }
        } catch (Exception e) {
            log.error("Failed to dispatch scheduled task {}: {}", event.taskId(), e.getMessage(), e);
        }
    }

    // ── agentTurn ──────────────────────────────────────────────────────────

    /**
     * Build a sub-session context and hand off to the Agent.
     *
     * <p>Payload shape (all fields optional except {@code query}):
     * {@code
     * {
     *   "query": "What is the weather?",           // required
     *   "systemPromptOverride": "You are helpful", // optional
     *   "metadata": { ... }                         // extra metadata merged into context
     * }}
     */
    @SuppressWarnings("unchecked")
    private void handleAgentTurn(ScheduledTaskFiredEvent event) throws Exception {
        Object rawPayload = event.payload();
        if (rawPayload == null) {
            log.warn("agentTurn task {} has no payload - skipping", event.taskId());
            return;
        }

        Map<String, Object> payload = toMap(rawPayload);
        String query = (String) payload.getOrDefault("query", payload.getOrDefault("message", ""));
        if (query == null || query.isBlank()) {
            log.warn("agentTurn task {} has no query in payload - skipping", event.taskId());
            return;
        }

        String systemPromptOverride = (String) payload.get("systemPromptOverride");
        Map<String, Object> extraMeta = payload.get("metadata") instanceof Map m ? m : Map.of();

        // sessionTarget: "current" (default) runs in the creating session;
        // "isolated" runs each fire in a fresh ephemeral session and routes the
        // result back via delivery (announce=back to the creating session,
        // webhook=HTTP POST, none=log only).
        String sessionTarget = String.valueOf(payload.getOrDefault("sessionTarget", "current")).trim().toLowerCase(java.util.Locale.ROOT);
        boolean isolated = "isolated".equals(sessionTarget);
        String originSessionId = payload.get("originSessionId") instanceof String s && !s.isBlank()
            ? s : event.sessionId();
        String runSessionId = isolated
            ? "scheduled-iso-" + event.taskId().substring(0, Math.min(8, event.taskId().length())) + "-" + UUID.randomUUID().toString().substring(0, 8)
            : (event.sessionId() != null ? event.sessionId() : "scheduled:" + event.taskId());

        Map<String, Object> metadata = new HashMap<>(extraMeta);
        metadata.put("scheduledTaskId", event.taskId());
        metadata.put("scheduledTaskName", event.taskName());
        // Every scheduler-fired turn is non-interactive: a tool that would ASK
        // for human approval must fail fast rather than hang the scheduler thread.
        metadata.put(CallerIdentity.META_HEADLESS, true);
        if (isolated) {
            metadata.put("scheduledIsolated", true);
            // Autonomous background runs cannot create new schedules or touch
            // other tasks (mirrors the Axiflux RESTRICTED cron grant).
            metadata.put(com.gantang.axiflux.spring.tool.ScheduleTaskTool.META_RESTRICTED, true);
        }

        AgentContext subContext = AgentContext.builder()
            .sessionId(runSessionId)
            .userId(event.userId() != null ? event.userId() : "scheduler")
            .systemPrompt(systemPromptOverride != null
                ? systemPromptOverride
                : "You are a scheduled assistant task.")
            .metadata(metadata)
            .build();

        if (sessionManager != null) {
            sessionManager.getOrCreate(runSessionId, event.userId(), "scheduler", Map.of())
                .addUserMessage(query, Map.of());
        }

        if (agent == null) {
            log.warn("No Agent bean available - scheduled task {} not executed", event.taskId());
            return;
        }

        String displayQuery = query.length() > 80 ? query.substring(0, 80) + "..." : query;
        log.debug("Calling Agent.process() for scheduled task {} target={} session={} query='{}'",
            event.taskId(), sessionTarget, runSessionId, displayQuery);

        // block() is safe here: this handler runs on Spring's @Async task executor
        // (see @Async on onScheduledTaskFired), never on a Netty event-loop thread.
        var resp = agent.process(subContext).block();
        if (resp == null) {
            log.warn("Scheduled task {} produced no response", event.taskId());
            return;
        }
        log.info("Scheduled task {} completed: status={} contentLen={}",
            event.taskId(), resp.status(), resp.content().length());

        if (isolated) {
            deliverIsolatedResult(event, payload, originSessionId, runSessionId, resp.content());
        }
    }

    /** Route an isolated run's result according to payload.delivery. */
    @SuppressWarnings("unchecked")
    private void deliverIsolatedResult(ScheduledTaskFiredEvent event, Map<String, Object> payload,
                                       String originSessionId, String runSessionId, String result) {
        Map<String, Object> delivery = payload.get("delivery") instanceof Map m
            ? (Map<String, Object>) m : Map.of();
        String mode = String.valueOf(delivery.getOrDefault("mode", "announce")).trim().toLowerCase(java.util.Locale.ROOT);

        switch (mode) {
            case "none" -> log.info("Scheduled task {} isolated run complete (delivery=none), resultLen={}",
                event.taskId(), result.length());
            case "webhook" -> {
                String url = String.valueOf(delivery.getOrDefault("url", ""));
                if (url.isBlank()) {
                    log.warn("Scheduled task {} delivery=webhook but no url set - result dropped", event.taskId());
                    return;
                }
                postWebhook(event, url, runSessionId, result);
            }
            default -> {
                // announce: inject the result into the creating session as an assistant message
                if (sessionManager == null || originSessionId == null) return;
                sessionManager.get(originSessionId).ifPresentOrElse(s -> {
                    Map<String, Object> meta = new HashMap<>();
                    meta.put("scheduledTaskId", event.taskId());
                    meta.put("scheduledTaskName", event.taskName());
                    meta.put("scheduledAnnounce", true);
                    String banner = "\uD83D\uDD14 \u5B9A\u65F6\u4EFB\u52A1\u300C" + event.taskName() + "\u300D\u6267\u884C\u5B8C\u6210\uFF1A\n\n";
                    s.addAssistantMessage(banner + result, null, null);
                    log.info("Scheduled task {} result announced to session {}", event.taskId(), originSessionId);
                }, () -> log.warn("Scheduled task {} origin session {} gone - result dropped",
                    event.taskId(), originSessionId));
            }
        }
    }

    /** Fire-and-forget JSON POST; failures are logged, never retried here. */
    private void postWebhook(ScheduledTaskFiredEvent event, String url,
                             String runSessionId, String result) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("taskId", event.taskId());
            body.put("taskName", event.taskName());
            body.put("sessionId", runSessionId);
            body.put("firedAt", java.time.Instant.now().toString());
            body.put("result", result);
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body)))
                .build();
            java.net.http.HttpResponse<String> resp = WEBHOOK_CLIENT.send(req,
                java.net.http.HttpResponse.BodyHandlers.ofString());
            log.info("Scheduled task {} webhook -> {} status={}", event.taskId(), url, resp.statusCode());
        } catch (Exception e) {
            log.warn("Scheduled task {} webhook delivery failed: {}", event.taskId(), e.getMessage());
        }
    }

    /** Webhook calls are server-to-server; never route them through the system proxy. */
    private static final java.net.http.HttpClient WEBHOOK_CLIENT = java.net.http.HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .proxy(new java.net.ProxySelector() {
            @Override public java.util.List<java.net.Proxy> select(java.net.URI uri) {
                return java.util.List.of(java.net.Proxy.NO_PROXY);
            }
            @Override public void connectFailed(java.net.URI uri, java.net.SocketAddress sa,
                                                java.io.IOException ioe) { /* no-op */ }
        })
        .build();

    // ── systemEvent ─────────────────────────────────────────────────────────

    /**
     * Re-publishes the payload as a system message in the session.
     *
     * <p>Payload shape:
     * {@code
     * {
     *   "text": "Your meeting starts in 5 minutes", // required
     * }}
     */
    @SuppressWarnings("unchecked")
    private void handleSystemEvent(ScheduledTaskFiredEvent event) {
        Object rawPayload = event.payload();
        if (rawPayload == null) return;

        Map<String, Object> payload = toMap(rawPayload);
        String text = (String) payload.getOrDefault("text",
            payload.getOrDefault("message", "Scheduled notification"));
        String targetSession = event.sessionId();

        String displayText = text.length() > 80 ? text.substring(0, 80) + "..." : text;
        log.info("systemEvent dispatch: text='{}' sessionId={}", displayText, targetSession);

        if (sessionManager != null && targetSession != null) {
            // Render as a visible assistant reminder (no model call). The 🔔 banner
            // distinguishes scheduled reminders from normal replies.
            String banner = "\uD83D\uDD14 \u5B9A\u65F6\u63D0\u9192\uFF1A" + text;
            sessionManager.getOrCreate(targetSession, event.userId(), "scheduler", Map.of())
                .addAssistantMessage(banner, null, null);
            log.info("systemEvent reminder posted to session {}: '{}'", targetSession, displayText);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object raw) {
        if (raw instanceof Map) return (Map<String, Object>) raw;
        if (raw instanceof String s) {
            try { return om.readValue(s, Map.class); }
            catch (Exception ignored) {}
        }
        return Map.of("raw", raw != null ? raw.toString() : "");
    }
}
