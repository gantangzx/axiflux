package com.gantang.reaxon.api.workflow;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A resumable checkpoint for a paused {@link StateGraph} run.
 *
 * <p>To resume without the original caller present, the checkpoint carries the safe
 * identity fields of the base {@code AgentContext} (session/user/agent, system prompt,
 * metadata, attachments, forced model). The caller's BYOK key is deliberately
 * <b>not</b> stored — it is a secret that must never be persisted; a resumed run that
 * needs a provider key relies on the deployment's static key configuration.
 *
 * @param runId        run instance id (one graph may run multiple times per session)
 * @param graphName    graph definition name
 * @param nodeId       the node that paused (resume re-enters its outgoing routing)
 * @param state        state at the pause
 * @param reason       why the run paused (also the resume-payload variable name)
 * @param sessionId    session id
 * @param userId       user id
 * @param agentId      optional agent persona
 * @param systemPrompt base system prompt
 * @param metadata     base context metadata (safe; no secrets)
 * @param attachments  base context attachments
 * @param forcedModel  optional forced model
 * @param createdAt    when the checkpoint was written
 */
public record Checkpoint(
    String runId,
    String graphName,
    String nodeId,
    GraphState state,
    String reason,
    String sessionId,
    String userId,
    String agentId,
    String systemPrompt,
    Map<String, Object> metadata,
    List<Map<String, Object>> attachments,
    String forcedModel,
    Instant createdAt
) {

    public Checkpoint {
        metadata = metadata == null ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        attachments = attachments == null ? List.of()
            : Collections.unmodifiableList(List.copyOf(attachments));
    }

    public static Checkpoint of(String runId, String graphName, String nodeId,
                                GraphState state, String reason,
                                com.gantang.reaxon.api.agent.AgentContext base) {
        List<Map<String, Object>> atts = base.attachments().stream()
            .map(Checkpoint::attachmentToMap)
            .toList();
        return new Checkpoint(runId, graphName, nodeId, state, reason,
            base.sessionId(), base.userId(), base.agentId(), base.systemPrompt(),
            base.metadata(), atts, base.forcedModel(), Instant.now());
    }

    /** Rebuild the safe base agent context (BYOK key is always absent). */
    public com.gantang.reaxon.api.agent.AgentContext toBaseContext() {
        List<com.gantang.reaxon.api.agent.AgentContext.Attachment> atts = attachments.stream()
            .map(Checkpoint::mapToAttachment)
            .toList();
        return com.gantang.reaxon.api.agent.AgentContext.builder()
            .sessionId(sessionId)
            .userId(userId)
            .agentId(agentId)
            .systemPrompt(systemPrompt == null ? "" : systemPrompt)
            .metadata(metadata)
            .attachments(atts)
            .forcedModel(forcedModel)
            .build();
    }

    private static Map<String, Object> attachmentToMap(
        com.gantang.reaxon.api.agent.AgentContext.Attachment a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", a.type());
        m.put("url", a.url());
        m.put("name", a.name());
        m.put("metadata", a.metadata() == null ? Map.of() : a.metadata());
        return m;
    }

    @SuppressWarnings("unchecked")
    private static com.gantang.reaxon.api.agent.AgentContext.Attachment mapToAttachment(
        Map<String, Object> m) {
        Object meta = m.get("metadata");
        return new com.gantang.reaxon.api.agent.AgentContext.Attachment(
            (String) m.get("type"),
            (String) m.get("url"),
            (String) m.get("name"),
            meta instanceof Map ? (Map<String, Object>) meta : Map.of());
    }
}
