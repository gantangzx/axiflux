package com.gantang.reaxon.api.agent;

import java.time.Instant;
import java.util.List;

/**
 * Declarative definition of a named agent persona.
 *
 * <p>Multiple agents share the same runtime engine ({@code ReactiveAgent}) but
 * differ in identity, default behaviour and <em>tool permissions</em>:
 * a human-readable {@link #name()}, an optional avatar {@link #emoji()},
 * a {@link #systemPrompt()} persona / instructions, an optional pinned
 * {@link #provider()} (model-router key), and an optional tool-scope:
 *
 * <ul>
 *   <li>{@link #allowedTools()} — whitelist of tool names this agent may call.
 *       Empty/null means "no extra restriction" (the deployment policy still applies).
 *       An agent can only <em>narrow</em> the deployment tool set, never widen it.</li>
 *   <li>{@link #riskCeiling()} — highest {@link com.gantang.reaxon.api.tool.policy.RiskLevel}
 *       this agent may use. Tools above the ceiling are denied. Null means no ceiling.</li>
 * </ul>
 *
 * Definitions are persisted by an {@link AgentDirectory} so user-created agents
 * and edits survive restarts.
 *
 * @param agentId      stable unique id (also stored on sessions)
 * @param name         display name shown in the UI
 * @param description  short blurb
 * @param emoji        optional avatar glyph
 * @param systemPrompt persona / system instructions (prepended to every turn)
 * @param soul         SOUL.md persona file: tone, opinions, brevity, boundaries (optional)
 * @param userProfile  USER.md file: who the user is and how to address them (optional)
 * @param operatingInstructions AGENTS.md file: operating rules / how to behave (optional)
 * @param provider     optional router provider key to pin the model; null = auto/default
 * @param builtin      true for the seeded default agent (kept from deletion)
 * @param enabled      whether the agent can be selected / run
 * @param allowedTools optional per-agent tool whitelist; empty = unrestricted
 * @param riskCeiling  optional max risk level name; null = unrestricted
 * @param createdAt    creation timestamp
 * @param updatedAt    last-update timestamp
 * @param isTemplate   true for a system-provided template (not a user agent)
 * @param templateId   id of the template this agent was created from (optional)
 */
public record AgentDefinition(
    String agentId,
    String name,
    String description,
    String emoji,
    String systemPrompt,
    String soul,
    String userProfile,
    String operatingInstructions,
    String provider,
    boolean builtin,
    boolean enabled,
    List<String> allowedTools,
    String riskCeiling,
    Instant createdAt,
    Instant updatedAt,
    boolean isTemplate,
    String templateId
) {
    public AgentDefinition withUpdates(String name, String description, String emoji,
                                       String systemPrompt, String provider, Boolean enabled,
                                       List<String> allowedTools, String riskCeiling) {
        return withUpdates(name, description, emoji, systemPrompt, null, null, null,
            provider, enabled, allowedTools, riskCeiling);
    }

    public AgentDefinition withUpdates(String name, String description, String emoji,
                                       String systemPrompt, String soul, String userProfile,
                                       String operatingInstructions, String provider, Boolean enabled,
                                       List<String> allowedTools, String riskCeiling) {
        return new AgentDefinition(
            this.agentId,
            name != null ? name : this.name,
            description != null ? description : this.description,
            emoji != null ? emoji : this.emoji,
            systemPrompt != null ? systemPrompt : this.systemPrompt,
            soul != null ? soul : this.soul,
            userProfile != null ? userProfile : this.userProfile,
            operatingInstructions != null ? operatingInstructions : this.operatingInstructions,
            provider != null ? provider : this.provider,
            this.builtin,
            enabled != null ? enabled : this.enabled,
            allowedTools != null ? List.copyOf(allowedTools) : this.allowedTools,
            riskCeiling != null ? riskCeiling : this.riskCeiling,
            this.createdAt,
            Instant.now(),
            this.isTemplate,
            this.templateId
        );
    }
}
