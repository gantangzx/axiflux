package com.gantang.tianshu.spring.service;

import com.gantang.tianshu.api.agent.AgentDefinition;
import com.gantang.tianshu.api.agent.AgentDirectory;
import com.gantang.tianshu.storage.entity.AgentDefinitionEntity;
import com.gantang.tianshu.storage.repository.AgentDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA-backed {@link AgentDirectory}. Persists named agent personas so user-created
 * agents and edits survive restarts. Seeds a built-in {@code default} agent when
 * the table is empty so the console always has at least one runnable persona.
 */
public class JpaAgentDirectory implements AgentDirectory, SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(JpaAgentDirectory.class);
    public static final String DEFAULT_AGENT_ID = "default";

    private final AgentDefinitionRepository repo;

    public JpaAgentDirectory(AgentDefinitionRepository repo) {
        this.repo = repo;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Instant now = Instant.now();
        for (BuiltinAgents.Seed seed : BuiltinAgents.all()) {
            if (repo.existsById(seed.agentId())) {
                reconcileBuiltinTools(seed);
                continue; // never clobber user edits to a built-in persona
            }
            AgentDefinitionEntity d = new AgentDefinitionEntity();
            d.setAgentId(seed.agentId());
            d.setName(seed.name());
            d.setDescription(seed.description());
            d.setEmoji(seed.emoji());
            d.setSystemPrompt(seed.systemPrompt());
            d.setProvider(null);
            d.setBuiltin(true);
            d.setEnabled(true);
            d.setAllowedTools(seed.allowedTools() == null || seed.allowedTools().isEmpty()
                ? null : String.join(",", seed.allowedTools()));
            d.setRiskCeiling(seed.riskCeiling());
            d.setCreatedAt(now);
            d.setUpdatedAt(now);
            repo.save(d);
            log.info("Seeded built-in agent definition: {}", seed.agentId());
        }
    }

    /**
     * Keep a built-in agent's tool whitelist in step with the shipped preset across
     * upgrades (so a newly added built-in tool lights up without a manual edit).
     * Persona fields (name/emoji/systemPrompt/enabled/provider) are left untouched.
     */
    private void reconcileBuiltinTools(BuiltinAgents.Seed seed) {
        try {
            AgentDefinitionEntity e = repo.findById(seed.agentId()).orElse(null);
            if (e == null || !e.isBuiltin()) return;
            String desired = seed.allowedTools() == null || seed.allowedTools().isEmpty()
                ? null : String.join(",", seed.allowedTools());
            String current = e.getAllowedTools();
            boolean same = (desired == null && (current == null || current.isBlank()))
                || (desired != null && desired.equals(current));
            if (!same) {
                e.setAllowedTools(desired);
                e.setUpdatedAt(Instant.now());
                repo.save(e);
                log.info("Refreshed built-in agent tool whitelist: {}", seed.agentId());
            }
        } catch (Exception ex) {
            log.warn("Failed to reconcile built-in tools for {}: {}", seed.agentId(), ex.getMessage());
        }
    }

    @Override
    public List<AgentDefinition> list() {
        // Templates are surfaced separately (GET /api/v1/templates); keep the
        // regular agent listing free of the system-seeded template personas.
        return repo.findAllByOrderByCreatedAtAsc().stream()
            .filter(e -> !e.isTemplate())
            .map(JpaAgentDirectory::toDomain)
            .toList();
    }

    @Override
    public List<AgentDefinition> listTemplates() {
        return repo.findAllByOrderByCreatedAtAsc().stream()
            .filter(AgentDefinitionEntity::isTemplate)
            .map(JpaAgentDirectory::toDomain)
            .toList();
    }

    @Override
    public Optional<AgentDefinition> get(String agentId) {
        if (agentId == null) return Optional.empty();
        return repo.findById(agentId).map(JpaAgentDirectory::toDomain);
    }

    @Override
    public synchronized AgentDefinition save(AgentDefinition def) {
        String id = def.agentId() != null && !def.agentId().isBlank()
            ? def.agentId()
            : "agent-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        AgentDefinitionEntity e = repo.findById(id).orElseGet(AgentDefinitionEntity::new);
        boolean isNew = e.getAgentId() == null;
        if (isNew) {
            e.setAgentId(id);
            e.setCreatedAt(Instant.now());
            e.setBuiltin(false);
        }
        // Built-in identity is immutable; only persona fields may change.
        e.setName(blankTo(def.name(), isNew ? "未命名 Agent" : e.getName()));
        e.setDescription(def.description());
        e.setEmoji(blankTo(def.emoji(), e.getEmoji()));
        e.setSystemPrompt(blankToNull(def.systemPrompt()));
        e.setSoul(blankToNull(def.soul()));
        e.setUserProfile(blankToNull(def.userProfile()));
        e.setOperatingInstructions(blankToNull(def.operatingInstructions()));
        e.setProvider(blankToNull(def.provider()));
        e.setAllowedTools(joinTools(def.allowedTools()));
        e.setRiskCeiling(blankToNull(def.riskCeiling()));
        e.setIsTemplate(def.isTemplate());
        e.setTemplateId(blankToNull(def.templateId()));
        e.setEnabled(def.enabled());
        e.setUpdatedAt(Instant.now());

        AgentDefinitionEntity saved = repo.save(e);
        log.info("{} agent definition: {}", isNew ? "Created" : "Updated", id);
        return toDomain(saved);
    }

    @Override
    public synchronized boolean delete(String agentId) {
        if (agentId == null || DEFAULT_AGENT_ID.equals(agentId)) return false;
        return repo.findById(agentId).map(e -> {
            if (e.isBuiltin()) return false;
            repo.delete(e);
            log.info("Deleted agent definition: {}", agentId);
            return true;
        }).orElse(false);
    }

    private static AgentDefinition toDomain(AgentDefinitionEntity e) {
        return new AgentDefinition(
            e.getAgentId(), e.getName(), e.getDescription(), e.getEmoji(),
            e.getSystemPrompt(), e.getSoul(), e.getUserProfile(), e.getOperatingInstructions(),
            e.getProvider(), e.isBuiltin(), e.isEnabled(),
            splitTools(e.getAllowedTools()), blankToNull(e.getRiskCeiling()),
            e.getCreatedAt(), e.getUpdatedAt(), e.isTemplate(), e.getTemplateId());
    }

    private static List<String> splitTools(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return java.util.Arrays.stream(csv.split("[,;]"))
            .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String joinTools(List<String> tools) {
        if (tools == null || tools.isEmpty()) return null;
        return String.join(",", tools);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String blankTo(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s.trim();
    }
}
