package com.gantang.axiflux.storage.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * JPA entity for the {@code agent_definition} table — a named, editable agent
 * persona managed through the console and applied per turn by the reactive engine.
 */
@Entity
@Table(name = "agent_definition")
public class AgentDefinitionEntity {

    @Id
    @Column(name = "agent_id", length = 64)
    private String agentId;

    @Column(name = "name", length = 128, nullable = false)
    private String name;

    @Column(name = "description", length = 512)
    private String description;

    @Column(name = "emoji", length = 16)
    private String emoji;

    @Column(name = "system_prompt", columnDefinition = "text")
    private String systemPrompt;

    /** SOUL.md identity file: persona, tone, opinions, boundaries. */
    @Column(name = "soul", columnDefinition = "text")
    private String soul;

    /** USER.md identity file: who the user is and how to address them. */
    @Column(name = "user_profile", columnDefinition = "text")
    private String userProfile;

    /** AGENTS.md identity file: operating rules / how to behave. */
    @Column(name = "operating_instructions", columnDefinition = "text")
    private String operatingInstructions;

    @Column(name = "provider", length = 64)
    private String provider;

    /** Comma-separated tool whitelist for this persona; null/empty = unrestricted. */
    @Column(name = "allowed_tools", columnDefinition = "text")
    private String allowedTools;

    /** Max risk level name (SAFE/READ/NETWORK/WRITE/DESTRUCTIVE); null = unrestricted. */
    @Column(name = "risk_ceiling", length = 16)
    private String riskCeiling;

    @Column(name = "is_builtin", nullable = false)
    private boolean builtin;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** True for a system-provided agent template (not a user-created agent). */
    @Column(name = "is_template", nullable = false)
    private boolean isTemplate = false;

    /** Id of the template this agent was created from (null for non-derived agents). */
    @Column(name = "template_id", length = 64)
    private String templateId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public AgentDefinitionEntity() {}

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getEmoji() { return emoji; }
    public void setEmoji(String emoji) { this.emoji = emoji; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public String getSoul() { return soul; }
    public void setSoul(String soul) { this.soul = soul; }
    public String getUserProfile() { return userProfile; }
    public void setUserProfile(String userProfile) { this.userProfile = userProfile; }
    public String getOperatingInstructions() { return operatingInstructions; }
    public void setOperatingInstructions(String operatingInstructions) { this.operatingInstructions = operatingInstructions; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getAllowedTools() { return allowedTools; }
    public void setAllowedTools(String allowedTools) { this.allowedTools = allowedTools; }
    public String getRiskCeiling() { return riskCeiling; }
    public void setRiskCeiling(String riskCeiling) { this.riskCeiling = riskCeiling; }
    public boolean isBuiltin() { return builtin; }
    public void setBuiltin(boolean builtin) { this.builtin = builtin; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public boolean isTemplate() { return isTemplate; }
    public void setIsTemplate(boolean isTemplate) { this.isTemplate = isTemplate; }
    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }
}
