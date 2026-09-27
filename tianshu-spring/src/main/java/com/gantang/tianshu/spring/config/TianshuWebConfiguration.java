package com.gantang.tianshu.spring.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.tianshu.api.agent.Agent;
import com.gantang.tianshu.api.llm.ModelRouter;
import com.gantang.tianshu.api.mcp.McpEndpoint;
import com.gantang.tianshu.api.memory.LongTermMemory;
import com.gantang.tianshu.api.session.SessionManager;
import com.gantang.tianshu.api.skill.SkillExecutor;
import com.gantang.tianshu.api.skill.SkillRegistry;
import com.gantang.tianshu.api.tool.ToolRegistry;
import com.gantang.tianshu.spring.auth.CallerGuard;
import com.gantang.tianshu.spring.controller.AgentController;
import com.gantang.tianshu.spring.controller.ApprovalController;
import com.gantang.tianshu.spring.controller.AuditController;
import com.gantang.tianshu.spring.controller.ConfigController;
import com.gantang.tianshu.spring.controller.MemoryController;
import com.gantang.tianshu.spring.controller.McpController;
import com.gantang.tianshu.spring.controller.SchedulerController;
import com.gantang.tianshu.spring.controller.SessionController;
import com.gantang.tianshu.spring.controller.SkillController;
import com.gantang.tianshu.spring.controller.SubAgentController;
import com.gantang.tianshu.spring.controller.ToolController;
import com.gantang.tianshu.spring.mcp.McpStreamableController;
import com.gantang.tianshu.spring.config.props.AgentProperties;
import com.gantang.tianshu.spring.config.props.AuthProperties;
import com.gantang.tianshu.spring.config.props.EmailProperties;
import com.gantang.tianshu.spring.config.props.LlmProperties;
import com.gantang.tianshu.spring.config.props.MemoryProperties;
import com.gantang.tianshu.spring.config.props.SchedulerProperties;
import com.gantang.tianshu.spring.config.props.SessionProperties;
import com.gantang.tianshu.spring.config.props.SkillsProperties;
import com.gantang.tianshu.spring.config.props.ToolsProperties;
import com.gantang.tianshu.spring.config.props.TtsProperties;
import com.gantang.tianshu.spring.config.props.VectorProperties;
import com.gantang.tianshu.spring.config.props.VisionProperties;
import com.gantang.tianshu.spring.config.props.WebProperties;
import com.gantang.tianshu.spring.config.props.WebsocketProperties;
import com.gantang.tianshu.spring.web.GlobalExceptionHandler;
import com.gantang.tianshu.spring.web.RequestLoggingFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * REST controller wiring for the <strong>open-source single-user console</strong>.
 *
 * <p>Only the framework's own controllers are registered here. The commercial
 * endpoints (organization, billing/Stripe, SSO, local accounts, admin users,
 * API keys, compliance export, admin billing/funnel, agent-template ops) are
 * wired by the closed-source {@code tianshu-commercial} module instead, so the
 * open-source jar neither contains nor references them.
 *
 * <p>Each controller registers only when its required beans exist
 * ({@link ConditionalOnBean}), keeping smoke tests and embedded deployments
 * boot-friendly; the commercial SPI beans ({@code PlanGateSpi}, etc.) are simply
 * absent in an open-source build and every paid check becomes a no-op.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "tianshu.web.enabled", havingValue = "true", matchIfMissing = true)
@org.springframework.boot.autoconfigure.AutoConfigureAfter({TaskSchedulerConfiguration.class, SkillConfiguration.class})
public class TianshuWebConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RequestLoggingFilter requestLoggingFilter() {
        return new RequestLoggingFilter();
    }

    /** Uniform JSON error body + status mapping for every REST controller. */
    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler(
            ObjectProvider<com.gantang.tianshu.spring.observability.FunnelTrackerSpi> funnelTrackers) {
        return new GlobalExceptionHandler(funnelTrackers.getIfAvailable());
    }

    /**
     * Shared authorization guard. Registered unconditionally (the session
     * manager and org directory are looked up lazily) so any controller —
     * including ones in downstream embedded apps — can depend on it.
     */
    @Bean
    @ConditionalOnMissingBean
    public CallerGuard callerGuard(ObjectProvider<SessionManager> sessions,
                                   ObjectProvider<com.gantang.tianshu.spring.service.OrgDirectorySpi> orgDirectory) {
        return new CallerGuard(sessions, orgDirectory);
    }

    @Bean
    @ConditionalOnBean({Agent.class, SessionManager.class})
    @ConditionalOnMissingBean
    public AgentController agentController(Agent agent, SessionManager sm, ModelRouter mr, ObjectMapper om,
                                          WebProperties webProps,
                                          ObjectProvider<com.gantang.tianshu.api.agent.AgentDirectory> dirs,
                                          CallerGuard guard,
                                          ObjectProvider<com.gantang.tianshu.spring.service.QuotaServiceSpi> quotas,
                                          ObjectProvider<com.gantang.tianshu.spring.service.PlanGateSpi> planGates,
                                          ObjectProvider<com.gantang.tianshu.spring.service.OrgDirectorySpi> orgs) {
        return new AgentController(agent, sm, mr, om, webProps, dirs, guard, quotas, planGates, orgs);
    }

    @Bean
    @ConditionalOnBean(SessionManager.class)
    @ConditionalOnMissingBean
    public SessionController sessionController(SessionManager sm, CallerGuard guard) {
        return new SessionController(sm, guard);
    }

    @Bean
    @ConditionalOnBean(ToolRegistry.class)
    @ConditionalOnMissingBean
    public ToolController toolController(ToolRegistry registry,
                                         ObjectProvider<com.gantang.tianshu.api.observability.MetricsReporter> metrics,
                                         ObjectProvider<com.gantang.tianshu.api.tool.policy.ToolPolicyChain> policy,
                                         CallerGuard guard,
                                         ObjectProvider<com.gantang.tianshu.spring.service.PlanGateSpi> planGates) {
        return new ToolController(registry, metrics, policy, guard, planGates);
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryController memoryController(ObjectProvider<LongTermMemory> memory,
                                             CallerGuard guard,
                                             ObjectProvider<com.gantang.tianshu.spring.service.PlanGateSpi> planGates) {
        return new MemoryController(memory, guard, planGates);
    }

    @Bean
    @ConditionalOnBean({SkillRegistry.class, SkillExecutor.class, com.gantang.tianshu.spring.service.SkillInstallService.class})
    @ConditionalOnMissingBean
    public SkillController skillController(SkillRegistry registry, SkillExecutor executor,
                                           CallerGuard guard,
                                           com.gantang.tianshu.spring.service.SkillInstallService installer,
                                           ObjectProvider<com.gantang.tianshu.spring.service.SkillLedgerService> ledger,
                                           ObjectProvider<com.gantang.tianshu.spring.service.SkillMarketService> market,
                                           ObjectProvider<com.gantang.tianshu.spring.service.PlanGateSpi> planGate) {
        return new SkillController(registry, executor, guard, installer, ledger, market, planGate);
    }

    @Bean
    @ConditionalOnBean(com.gantang.tianshu.spring.service.ScheduledTaskService.class)
    @ConditionalOnMissingBean
    public SchedulerController schedulerController(com.gantang.tianshu.spring.service.ScheduledTaskService service,
                                                    CallerGuard guard,
                                                    ObjectProvider<com.gantang.tianshu.spring.service.PlanGateSpi> planGate) {
        return new SchedulerController(service, guard, planGate);
    }

    @Bean
    @ConditionalOnBean(McpEndpoint.class)
    @ConditionalOnMissingBean
    public McpController mcpController(McpEndpoint endpoint, CallerGuard callerGuard) {
        return new McpController(endpoint, callerGuard);
    }

    @Bean
    @ConditionalOnBean(McpEndpoint.class)
    @ConditionalOnMissingBean
    public McpStreamableController mcpStreamableController(ObjectMapper om, McpEndpoint endpoint,
                                                            CallerGuard callerGuard) {
        return new McpStreamableController(om, endpoint, callerGuard);
    }

    @Bean
    @ConditionalOnBean(com.gantang.tianshu.api.agent.ApprovalManager.class)
    @ConditionalOnMissingBean
    public ApprovalController approvalController(com.gantang.tianshu.api.agent.ApprovalManager mgr) {
        return new ApprovalController(mgr);
    }

    @Bean
    @ConditionalOnBean(com.gantang.tianshu.api.agent.SubAgentRunner.class)
    @ConditionalOnMissingBean
    public SubAgentController subAgentController(
            com.gantang.tianshu.api.agent.SubAgentRunner runner,
            com.gantang.tianshu.api.agent.BackgroundSpawner spawner,
            com.gantang.tianshu.api.agent.SubAgentEventStreamer events,
            CallerGuard guard,
            ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> redisTemplates,
            ObjectProvider<org.springframework.data.redis.connection.RedisConnectionFactory> connectionFactories,
            ObjectProvider<ObjectMapper> mappers,
            ObjectProvider<com.gantang.tianshu.spring.service.PlanGateSpi> planGates) {
        // A spawned task lives in the memory of the instance that started it, so a
        // cancel routed elsewhere would 404 while the task keeps burning tokens.
        // The bridge is built here (next to its only consumer) so it can share the
        // very same service instance without a bean-ordering dance.
        org.springframework.data.redis.core.StringRedisTemplate template = redisTemplates.getIfAvailable();
        org.springframework.data.redis.connection.RedisConnectionFactory cf = connectionFactories.getIfAvailable();
        com.gantang.tianshu.spring.service.SubAgentCancelBridge bridge =
            (cf != null && com.gantang.tianshu.spring.storage.RedisAvailability.reachable(template))
                ? new com.gantang.tianshu.spring.service.SubAgentCancelBridge(template, cf, events,
                    mappers.getIfAvailable(ObjectMapper::new))
                : null;
        return new SubAgentController(runner, spawner, events, guard, bridge, planGates.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public ConfigController configController(
            LlmProperties llm, SchedulerProperties scheduler,
            VectorProperties vector, AgentProperties agentProps,
            ToolsProperties tools, SkillsProperties skills,
            WebProperties web, SessionProperties session,
            VisionProperties vision, TtsProperties tts,
            EmailProperties email, AuthProperties auth,
            MemoryProperties memory, WebsocketProperties websocket,
            ObjectMapper om,
            ObjectProvider<ModelRouter> router,
            ObjectProvider<Agent> agent,
            ObjectProvider<ToolRegistry> toolsReg,
            ObjectProvider<com.gantang.tianshu.spring.service.RuntimeConfigService> configService,
            ObjectProvider<com.gantang.tianshu.api.config.LiveSettings> live) {
        return new ConfigController(llm, scheduler, vector, agentProps, tools, skills,
            web, session, vision, tts, email, auth, memory, websocket,
            om, router, agent, toolsReg, configService, live);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditController auditController(
            ObjectProvider<com.gantang.tianshu.storage.repository.ToolExecutionRepository> repo) {
        return new AuditController(repo);
    }
}
