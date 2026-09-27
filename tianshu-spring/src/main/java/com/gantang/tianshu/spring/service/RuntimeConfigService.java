package com.gantang.tianshu.spring.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.tianshu.api.agent.Agent;
import com.gantang.tianshu.api.agent.RuntimeTunableAgent;
import com.gantang.tianshu.api.config.LiveSettings;
import com.gantang.tianshu.api.llm.ModelRouter;
import com.gantang.tianshu.api.llm.MutableModelRouter;
import com.gantang.tianshu.spring.config.FileRootsResolver;
import com.gantang.tianshu.spring.config.props.AgentProperties;
import com.gantang.tianshu.spring.config.props.ToolsProperties;
import com.gantang.tianshu.storage.entity.RuntimeConfigEntity;
import com.gantang.tianshu.storage.repository.RuntimeConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Persists and applies hot-tunable configuration overrides.
 *
 * <p>Overrides live in the {@code runtime_config} table. At startup they are
 * read and applied over {@code application.yml} (so API-driven changes survive
 * restarts); at runtime {@link #put(String, String, String)} upserts a row and
 * mutates the shared {@link LiveSettings} hub atomically.
 *
 * <p>Two kinds of keys are supported:
 * <ul>
 *   <li><b>Live keys</b> — the {@link LiveSettings} hub fields (policy mode /
 *       allow-block lists / private-network switch / file roots / approval
 *       budgets / iteration cap / tool timeout). These take effect on the next
 *       tool call with no restart.</li>
 *   <li><b>Router key</b> — {@code llm.default-provider}, which flips the
 *       {@link MutableModelRouter} directly.</li>
 * </ul>
 * Infrastructure keys (datasource, Redis, vector store, auth, sandbox) are not
 * hot-reloadable: they bind to connection pools / security chains at startup.
 */
public class RuntimeConfigService implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(RuntimeConfigService.class);
    private static final TypeReference<Map<String, Integer>> MAP_INT = new TypeReference<>() {};

    /** Router default-provider key (applied via ModelRouter, not the LiveSettings hub). */
    public static final String KEY_DEFAULT_PROVIDER = LiveSettingsCatalog.KEY_DEFAULT_PROVIDER;
    /** Agent iteration cap (applied to LiveSettings and mirrored onto ReactiveAgent). */
    public static final String KEY_MAX_ITERATIONS = LiveSettingsCatalog.KEY_MAX_ITERATIONS;

    /** Every key this service can apply live, sourced from the shared catalogue. */
    public static final Set<String> LIVE_KEYS = LiveSettingsCatalog.hubKeys();

    private final RuntimeConfigRepository repository;
    private final ObjectProvider<ModelRouter> routerProvider;
    private final ObjectProvider<Agent> agentProvider;
    private final ObjectProvider<LiveSettings> liveProvider;
    private final ObjectProvider<ToolsProperties> toolsProvider;
    private final ObjectProvider<AgentProperties> agentPropsProvider;
    private final ObjectMapper om;

    public RuntimeConfigService(RuntimeConfigRepository repository,
                                ObjectProvider<ModelRouter> routerProvider,
                                ObjectProvider<Agent> agentProvider,
                                ObjectProvider<LiveSettings> liveProvider,
                                ObjectProvider<ToolsProperties> toolsProvider,
                                ObjectProvider<AgentProperties> agentPropsProvider,
                                ObjectMapper om) {
        this.repository = repository;
        this.routerProvider = routerProvider;
        this.agentProvider = agentProvider;
        this.liveProvider = liveProvider;
        this.toolsProvider = toolsProvider;
        this.agentPropsProvider = agentPropsProvider;
        this.om = om;
    }

    /** Apply persisted overrides once all singletons (router/agent/live) exist. */
    @Override
    public void afterSingletonsInstantiated() {
        try {
            Map<String, String> stored = snapshot();
            if (stored.isEmpty()) {
                log.info("No persisted runtime config overrides found");
                return;
            }
            int applied = 0;
            for (Map.Entry<String, String> e : stored.entrySet()) {
                if (apply(e.getKey(), e.getValue())) applied++;
            }            log.info("Applied {}/{} persisted runtime config overrides: {}",
                applied, stored.size(), stored.keySet());
        } catch (Exception e) {
            // Never let bad config prevent startup — fall back to application.yml.
            log.warn("Failed to apply persisted runtime config overrides: {}", e.toString());
        }
    }

    /** Persist an override (and apply it live). Returns true on success. */
    public boolean put(String key, String value, String updatedBy) {
        RuntimeConfigEntity e = repository.findById(key).orElseGet(() ->
            new RuntimeConfigEntity(key, value, "string", updatedBy));
        e.setConfigValue(value);
        e.setValueType("string");
        e.setUpdatedBy(updatedBy);
        e.setUpdatedAt(Instant.now());
        repository.save(e);
        boolean applied = apply(key, value);
        log.info("Runtime config override {} = {} (applied={})", key, value, applied);
        return applied;
    }

    /** Remove an override and revert that field to the application.yml default. */
    public boolean reset(String key) {
        boolean existed = repository.existsById(key);
        if (existed) repository.deleteById(key);
        // Rebuild the live hub from application.yml, then re-apply remaining overrides.
        reseed();
        log.info("Reset runtime config override {} (existed={})", key, existed);
        return existed;
    }

    /** Re-seed the live hub from application.yml and layer any persisted overrides on top. */
    private void reseed() {
        LiveSettings live = liveProvider.getIfAvailable();
        if (live == null) return;
        live.replace(com.gantang.tianshu.spring.config.LiveSettingsSeeder.from(
            toolsProvider.getIfAvailable(), agentPropsProvider.getIfAvailable()).snapshot());
        for (Map.Entry<String, String> e : snapshot().entrySet()) {
            apply(e.getKey(), e.getValue());
        }
    }

    /** Read all overrides as a flat key → value map. */
    public Map<String, String> snapshot() {
        Map<String, String> out = new LinkedHashMap<>();
        for (RuntimeConfigEntity e : repository.findAll()) {
            out.put(e.getConfigKey(), e.getConfigValue());
        }
        return out;
    }

    /** Apply a single override live. Returns false for unknown/unparseable keys. */
    private boolean apply(String key, String value) {
        try {
            if (KEY_DEFAULT_PROVIDER.equals(key)) {
                return applyProvider(value);
            }
            if (!LIVE_KEYS.contains(key)) {
                log.debug("Stored override '{}' is not runtime-applicable; kept for reference", key);
                return false;
            }
            LiveSettings live = liveProvider.getIfAvailable();
            if (live == null) {
                log.warn("LiveSettings hub unavailable; override '{}' persisted but not applied", key);
                return false;
            }
            applyToLive(live, key, value);
            // Keep the agent's own iteration field in sync for embedders without the hub.
            if (KEY_MAX_ITERATIONS.equals(key) && agentProvider.getIfAvailable()
                    instanceof RuntimeTunableAgent ta) {
                ta.withMaxIterations(Integer.parseInt(value.trim()));
            }
            return true;
        } catch (Exception e) {
            log.warn("Failed to apply override {}={}: {}", key, value, e.toString());
            return false;
        }
    }

    private boolean applyProvider(String value) {
        ModelRouter router = routerProvider.getIfAvailable();
        if (router instanceof MutableModelRouter mr && value != null && !value.isBlank()) {
            if (mr.get(value).isPresent()) {
                mr.setDefaultProvider(value);
                return true;
            }
            log.warn("Persisted provider override '{}' has no matching client; ignored", value);
        }
        return false;
    }

    private void applyToLive(LiveSettings live, String key, String raw) {
        String v = raw == null ? "" : raw.trim();
        live.update(s -> {
            LiveSettings.Builder b = LiveSettings.builder()
                .maxIterations(s.maxIterations())
                .toolTimeoutSeconds(s.toolTimeoutSeconds())
                .toolPolicyMode(s.toolPolicyMode())
                .allowedTools(s.allowedTools())
                .blockedTools(s.blockedTools())
                .autoApproveTools(s.autoApproveTools())
                .allowPrivateNetwork(s.allowPrivateNetwork())
                .fileAllowedRoots(s.fileAllowedRoots())
                .budgetEnabled(s.budgetEnabled())
                .budgetRiskCeiling(s.budgetRiskCeiling())
                .budgetDefault(s.budgetDefault())
                .toolBudgets(s.toolBudgets());
            switch (key) {
                case KEY_MAX_ITERATIONS -> b.maxIterations(Integer.parseInt(v));
                case "agent.tool-timeout-seconds" -> b.toolTimeoutSeconds(Long.parseLong(v));
                case "tools.policy-mode" -> b.toolPolicyMode(
                    v.isEmpty() ? "all" : v.toUpperCase(Locale.ROOT));
                case "tools.policy-allowed" -> b.allowedTools(splitCsv(v));
                case "tools.policy-blocked" -> b.blockedTools(splitCsv(v));
                case "tools.auto-approve" -> b.autoApproveTools(splitCsv(v));
                case "tools.allow-private-network" -> b.allowPrivateNetwork(Boolean.parseBoolean(v));
                case "tools.file-allowed-roots" -> b.fileAllowedRoots(parseRoots(v));
                case "tools.auto-approve-budget-enabled" -> b.budgetEnabled(Boolean.parseBoolean(v));
                case "tools.auto-approve-budget-default" -> b.budgetDefault(Integer.parseInt(v));
                case "tools.auto-approve-budget-risk-ceiling" ->
                    b.budgetRiskCeiling(v.isEmpty() ? "WRITE" : v.toUpperCase(Locale.ROOT));
                case "tools.auto-approve-budgets" -> b.toolBudgets(parseBudgets(v));
                default -> { /* unknown — keep current */ }
            }
            return b.build();
        });
    }

    private Set<String> splitCsv(String csv) {
        Set<String> out = new java.util.HashSet<>();
        if (csv == null || csv.isBlank()) return out;
        for (String s : csv.split("[,;]")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private List<Path> parseRoots(String csv) {
        return FileRootsResolver.resolve(csv);
    }

    private Map<String, Integer> parseBudgets(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Integer> m = om.readValue(json, MAP_INT);
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            throw new IllegalArgumentException("auto-approve-budgets must be a JSON object {tool:count}", e);
        }
    }
}
