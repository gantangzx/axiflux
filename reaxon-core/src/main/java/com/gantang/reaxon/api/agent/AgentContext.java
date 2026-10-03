package com.gantang.reaxon.api.agent;

import java.util.List;
import java.util.Map;

/**
 * Immutable execution context for a single agent turn.
 * Built via the nested Builder.
 *
 * <p>BYOK: {@code byokApiKey} carries the caller's own provider key for this
 * turn (plaintext, in-memory only). It is intentionally a dedicated field —
 * NOT part of {@link #metadata()} — because metadata flows into session
 * persistence and audit; the key must never be persisted or logged.
 * {@link #toString()} masks it.
 */
public record AgentContext(
    String sessionId,
    String userId,
    String agentId,        // optional persona to run; null = the agent's default
    String currentQuery,
    String systemPrompt,
    Map<String, Object> metadata,
    List<Attachment> attachments,
    String forcedModel,     // optional, overrides router decision
    String byokApiKey       // optional BYOK provider key for this turn; null = static key
) {

    public record Attachment(
        String type,    // "image" | "file" | "audio"
        String url,
        String name,
        Map<String, Object> metadata
    ) {}

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    /** True when this turn carries an explicit BYOK provider key. */
    public boolean hasByokKey() {
        return byokApiKey != null && !byokApiKey.isBlank();
    }

    /**
     * Masked rendering: the BYOK key is a secret and must never appear in logs.
     * Records print every component by default, so this override is load-bearing.
     */
    @Override
    public String toString() {
        return "AgentContext[sessionId=" + sessionId
            + ", userId=" + userId
            + ", agentId=" + agentId
            + ", currentQuery=" + currentQuery
            + ", systemPrompt=" + (systemPrompt != null ? systemPrompt.length() : 0) + " chars"
            + ", metadata=" + metadata
            + ", attachments=" + attachments
            + ", forcedModel=" + forcedModel
            + ", byokApiKey=" + (hasByokKey() ? "***" : null)
            + "]";
    }

    public static class Builder {
        private String sessionId;
        private String userId;
        private String agentId;
        private String currentQuery = "";
        private String systemPrompt = "";
        private Map<String, Object> metadata = Map.of();
        private List<Attachment> attachments = List.of();
        private String forcedModel;
        private String byokApiKey;

        Builder() {}

        Builder(AgentContext ctx) {
            this.sessionId     = ctx.sessionId;
            this.userId        = ctx.userId;
            this.agentId       = ctx.agentId;
            this.currentQuery  = ctx.currentQuery;
            this.systemPrompt  = ctx.systemPrompt;
            this.metadata      = ctx.metadata;
            this.attachments   = ctx.attachments;
            this.forcedModel   = ctx.forcedModel;
            this.byokApiKey    = ctx.byokApiKey;
        }

        public Builder sessionId(String v)    { this.sessionId    = v; return this; }
        public Builder userId(String v)       { this.userId       = v; return this; }
        public Builder agentId(String v)      { this.agentId      = v; return this; }
        public Builder currentQuery(String v){ this.currentQuery  = v; return this; }
        public Builder systemPrompt(String v) { this.systemPrompt  = v; return this; }
        public Builder metadata(Map<String, Object> v) { this.metadata = v; return this; }
        public Builder attachments(List<Attachment> v) { this.attachments = v; return this; }
        public Builder forcedModel(String v)  { this.forcedModel   = v; return this; }
        public Builder byokApiKey(String v)   { this.byokApiKey    = v; return this; }

        public AgentContext build() {
            if (sessionId == null) throw new IllegalStateException("sessionId is required");
            if (userId == null)   throw new IllegalStateException("userId is required");
            return new AgentContext(
                sessionId, userId, agentId, currentQuery, systemPrompt,
                metadata, attachments, forcedModel, byokApiKey
            );
        }
    }
}
