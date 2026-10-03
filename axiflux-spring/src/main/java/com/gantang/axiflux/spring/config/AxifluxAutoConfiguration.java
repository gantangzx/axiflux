package com.gantang.axiflux.spring.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gantang.reaxon.api.agent.Agent;
import com.gantang.reaxon.api.agent.AgentDirectory;
import com.gantang.reaxon.api.agent.ApprovalManager;
import com.gantang.reaxon.api.agent.ApprovalStore;
import com.gantang.reaxon.api.agent.SubAgentRunner;
import com.gantang.reaxon.api.config.LiveSettings;
import com.gantang.reaxon.api.llm.ModelRouter;
import com.gantang.reaxon.api.mcp.McpEndpoint;
import com.gantang.reaxon.api.session.SessionDeleteBroadcaster;
import com.gantang.reaxon.api.session.SessionManager;
import com.gantang.reaxon.api.tool.ToolRegistry;
import com.gantang.reaxon.api.tool.policy.ToolPolicyChain;
import com.gantang.reaxon.impl.agent.DefaultSubAgentService;
import com.gantang.reaxon.impl.agent.ReactiveAgent;
import com.gantang.reaxon.impl.approval.DefaultApprovalManager;
import com.gantang.reaxon.impl.mcp.DefaultMcpEndpoint;
import com.gantang.reaxon.impl.observability.CompositeMetricsReporter;
import com.gantang.reaxon.impl.tool.DefaultToolRegistry;
import com.gantang.reaxon.impl.tool.policy.AgentScopePolicy;
import com.gantang.reaxon.impl.tool.policy.NetworkEgressPolicy;
import com.gantang.reaxon.impl.tool.policy.RiskLevelPolicy;
import com.gantang.reaxon.impl.tool.policy.ToolCallThrottlePolicy;
import com.gantang.reaxon.impl.tool.policy.ToolListPolicy;
import com.gantang.axiflux.spring.auth.AuthTokenService;
import com.gantang.axiflux.spring.config.props.AgentProperties;
import com.gantang.axiflux.spring.config.props.AuthProperties;
import com.gantang.axiflux.spring.config.props.ToolsProperties;
import com.gantang.axiflux.spring.observability.JpaToolExecutionReporter;
import com.gantang.axiflux.spring.service.JpaAgentDirectory;
import com.gantang.axiflux.spring.service.RuntimeConfigService;
import com.gantang.reaxon.api.observability.MetricsReporter;
import com.gantang.axiflux.storage.repository.ToolExecutionRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;

/**
 * Open-source core auto-configuration.
 *
 * <p>Domain wiring lives in focused member configurations imported below:
 * <ul>
 *   <li>{@link LlmConfiguration} - LLM clients and the model router</li>
 *   <li>{@link SessionConfiguration} - session manager selection (JPA/Redis/in-memory)</li>
 *   <li>{@link MemoryConfiguration} - vector backends, context assembler, memory capture</li>
 * </ul>
 * This class keeps the cross-cutting framework pieces: ObjectMapper, tool registry
 * and policy chain, the reactive agent, approvals, the MCP endpoint, and the
 * persistence-backed agent directory / runtime config. Commercial beans (accounts,
 * organizations, billing/Stripe, quotas, SSO orchestration, onboarding and the
 * lifecycle mail jobs) are registered by the closed-source {@code axiflux-commercial}
 * module and are neither defined nor referenced here.
 */
@AutoConfiguration
@EnableAsync
@EnableScheduling
@ConfigurationPropertiesScan("com.gantang.axiflux.spring.config.props")
@ConditionalOnClass(Agent.class)
@Import({LlmConfiguration.class, SessionConfiguration.class, MemoryConfiguration.class})
public class AxifluxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper AxifluxObjectMapper() {
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        om.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return om;
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry toolRegistry() {
        return new DefaultToolRegistry();
    }

    /**
     * Process-wide holder for console-tunable settings. Seeded from application.yml
     * at boot; mutated at runtime by {@code RuntimeConfigService} when an operator
     * applies a change through /api/v1/config.
     */
    @Bean
    @ConditionalOnMissingBean(LiveSettings.class)
    public LiveSettings liveSettings(ToolsProperties toolsProps, AgentProperties agentProps) {
        return LiveSettingsSeeder.from(toolsProps, agentProps);
    }

    /**
     * Tool security policy chain: list/scope/risk/throttle/egress. Always present;
     * defaults are permissive ({@code axiflux.tools.policy-mode=all}).
     *
     * <p>The list/egress policies read the shared {@link LiveSettings} hub live, so
     * console changes take effect on the very next tool call without a restart.
     */
    @Bean
    @ConditionalOnMissingBean(ToolPolicyChain.class)
    public ToolPolicyChain toolPolicyChain(LiveSettings live, ObjectProvider<AuthTokenService> tokens) {
        AuthTokenService svc = tokens.getIfAvailable();
        boolean strictScopes = svc != null && svc.isRequireExplicitScopes();
        return new ToolPolicyChain(java.util.List.of(
            new ToolListPolicy(live),
            // Organization governance allow-list: narrows members to the org's
            // configured tools injected into turn metadata; a no-op when absent.
            new com.gantang.reaxon.impl.tool.policy.OrgToolWhitelistPolicy(),
            new AgentScopePolicy(),
            new com.gantang.reaxon.impl.tool.policy.ScopePolicy(strictScopes),
            new com.gantang.reaxon.impl.tool.policy.GitActionPolicy(),
            new RiskLevelPolicy(),
            new com.gantang.reaxon.impl.tool.policy.InjectionEscalationPolicy(),
            new ToolCallThrottlePolicy(),
            new NetworkEgressPolicy(live),
            // Commercial plan-tier gate: reads the tier injected into turn
            // metadata; invisible when that key is absent.
            new com.gantang.reaxon.impl.tool.policy.PlanTierToolPolicy()
        ));
    }

    @Bean
    @ConditionalOnMissingBean(AuthTokenService.class)
    public AuthTokenService authTokenService(AuthProperties authProps) {
        return new AuthTokenService(authProps);
    }

    @Bean
    @Primary
    public Agent reactiveAgent(
            ModelRouter modelRouter,
            ToolRegistry toolRegistry,
            com.gantang.reaxon.api.memory.ContextAssembler contextAssembler,
            com.gantang.reaxon.api.memory.LongTermMemory longTermMemory,
            SessionManager sessionManager,
            ObjectMapper objectMapper,
            ObjectProvider<ApprovalManager> approvalManagers,
            ObjectProvider<MetricsReporter> metricsReporters,
            ObjectProvider<AgentDirectory> agentDirectories,
            ToolPolicyChain toolPolicyChain,
            com.gantang.reaxon.api.memory.TokenCounter tokenCounter,
            AgentProperties agentProps,
            ToolsProperties toolsProps,
            LiveSettings live,
            ObjectProvider<com.gantang.reaxon.api.agent.AgentHook> agentHooks,
            ObjectProvider<com.gantang.reaxon.api.skill.SkillRegistry> skillRegistries,
            ObjectProvider<com.gantang.reaxon.api.tool.ToolResultStore> toolResultStores,
            ObjectProvider<StringRedisTemplate> redisTemplates
    ) {
        MetricsReporter composite = CompositeMetricsReporter.of(
            metricsReporters.orderedStream().toArray(MetricsReporter[]::new));
        AgentProperties ap = agentProps != null
            ? agentProps : new AgentProperties();
        ReactiveAgent agent = new ReactiveAgent(
            modelRouter, toolRegistry, contextAssembler,
            longTermMemory, sessionManager, objectMapper
        )
            .withApprovalManager(approvalManagers.getIfAvailable())
            .withMetricsReporter(composite)
            .withAgentDirectory(agentDirectories.getIfAvailable())
            .withSkillRegistry(skillRegistries.getIfAvailable())
            .withLiveSettings(live)
            .withTokenCounter(tokenCounter)
            .withTokenBudget(ap.getContextWindowTokens(),
                ap.getMaxOutputTokens(), ap.getContextReserveTokens())
            .withLlmTemperature(ap.getTemperature())
            .withAutoApprovalPolicy(new com.gantang.reaxon.impl.approval.BudgetAutoApprovalPolicy(live))
            .withToolPolicyChain(toolPolicyChain)
            .withHooks(java.util.List.copyOf(agentHooks.orderedStream().toList()));

        com.gantang.reaxon.api.tool.ToolResultStore resultStore = toolResultStores.getIfAvailable();
        if (resultStore != null) {
            agent.withToolResultStore(resultStore);
        }

        com.gantang.reaxon.api.tool.ToolResultCache resultCache = null;
        if (toolsProps != null && toolsProps.isResultCacheEnabled() && toolsProps.getResultCacheTtlSeconds() > 0) {
            java.util.Set<String> cacheable = java.util.Arrays.stream(
                    toolsProps.getResultCacheTools().split("[,;，]"))
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!cacheable.isEmpty()) {
                StringRedisTemplate template = redisTemplates.getIfAvailable();
                com.gantang.reaxon.api.tool.ToolResultCache cache =
                    com.gantang.axiflux.spring.storage.RedisAvailability.reachable(template)
                    ? new com.gantang.axiflux.spring.storage.RedisToolResultCache(template, objectMapper,
                        java.time.Duration.ofSeconds(toolsProps.getResultCacheTtlSeconds()), 20_000)
                    : new com.gantang.reaxon.impl.tool.support.InMemoryToolResultCache(
                        java.time.Duration.ofSeconds(toolsProps.getResultCacheTtlSeconds()),
                        Math.max(10, toolsProps.getResultCacheMaxEntries()),
                        20_000);
                agent.withToolResultCache(cache, cacheable);
                resultCache = cache;
            }
        }

        if (sessionManager instanceof SessionDeleteBroadcaster broadcaster) {
            broadcaster.addDeleteListener(agent::forgetSession);
            if (resultStore != null) {
                broadcaster.addDeleteListener(resultStore::clearSession);
            }
            if (resultCache != null) {
                final com.gantang.reaxon.api.tool.ToolResultCache c = resultCache;
                broadcaster.addDeleteListener(c::clearSession);
            }
        }
        return agent;
    }

    /** Per-session token/cost accounting hook (observability; never blocks). */
    @Bean
    public com.gantang.axiflux.spring.observability.CostAccountingHook costAccountingHook(
            SessionManager sessionManager,
            com.gantang.axiflux.spring.config.props.LlmProperties llmProps,
            ObjectProvider<com.gantang.axiflux.spring.observability.UsageRecordSink> usageRecordWriter) {
        return new com.gantang.axiflux.spring.observability.CostAccountingHook(
            sessionManager,
            () -> LlmClientFactory.priceTable(llmProps.getRouting()),
            usageRecordWriter);
    }

    @Bean
    @ConditionalOnMissingBean(SubAgentRunner.class)
    public DefaultSubAgentService subAgentService(Agent agent, SessionManager sessionManager, ObjectMapper om) {
        return new DefaultSubAgentService(agent, sessionManager, om);
    }

    /** Persistent, user-editable agent personas. */
    @Bean
    @ConditionalOnBean(com.gantang.axiflux.storage.repository.AgentDefinitionRepository.class)
    @ConditionalOnMissingBean(AgentDirectory.class)
    public JpaAgentDirectory agentDirectory(
            com.gantang.axiflux.storage.repository.AgentDefinitionRepository repo) {
        return new JpaAgentDirectory(repo);
    }

    @Bean
    @ConditionalOnBean(com.gantang.axiflux.storage.repository.RuntimeConfigRepository.class)
    @ConditionalOnMissingBean(RuntimeConfigService.class)
    public RuntimeConfigService runtimeConfigService(
            com.gantang.axiflux.storage.repository.RuntimeConfigRepository repo,
            ObjectProvider<ModelRouter> router,
            ObjectProvider<Agent> agent,
            ObjectProvider<LiveSettings> live,
            ObjectProvider<ToolsProperties> toolsProps,
            ObjectProvider<AgentProperties> agentProps,
            ObjectMapper objectMapper) {
        return new RuntimeConfigService(repo, router, agent, live, toolsProps, agentProps, objectMapper);
    }

    /** Persist tool executions to the open-source {@code tool_executions} table. */
    @Bean
    @ConditionalOnBean({ToolExecutionRepository.class, DataSource.class})
    @ConditionalOnMissingBean(JpaToolExecutionReporter.class)
    public JpaToolExecutionReporter jpaToolExecutionReporter(
            ToolExecutionRepository repo, ObjectMapper objectMapper) {
        return new JpaToolExecutionReporter(repo, objectMapper);
    }

    // ===== Approvals =====

    @Bean
    @ConditionalOnMissingBean(ApprovalStore.class)
    @ConditionalOnBean(org.springframework.data.redis.connection.RedisConnectionFactory.class)
    public ApprovalStore redisApprovalStore(StringRedisTemplate redisTemplate,
                                           org.springframework.data.redis.connection.RedisConnectionFactory connectionFactory) {
        return new com.gantang.axiflux.spring.storage.RedisApprovalStore(redisTemplate, connectionFactory);
    }

    @Bean
    @ConditionalOnMissingBean({ApprovalManager.class, ApprovalStore.class})
    public ApprovalManager inMemoryApprovalManager() {
        return new DefaultApprovalManager();
    }

    @Bean
    @ConditionalOnMissingBean(ApprovalManager.class)
    @ConditionalOnBean(ApprovalStore.class)
    public ApprovalManager approvalManager(ApprovalStore approvalStore) {
        return new DefaultApprovalManager(approvalStore);
    }

    // ===== MCP endpoint (server side) =====

    @Bean
    @ConditionalOnMissingBean(McpEndpoint.class)
    public McpEndpoint mcpEndpoint(ToolRegistry toolRegistry,
                                   ObjectProvider<ToolPolicyChain> policies) {
        return new DefaultMcpEndpoint(toolRegistry, policies.getIfAvailable());
    }
}
