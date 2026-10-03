package com.gantang.axiflux.spring.config;

import com.gantang.reaxon.api.tool.ToolResultStore;

import com.gantang.reaxon.api.email.EmailProvider;
import com.gantang.reaxon.api.agent.BackgroundSpawner;
import com.gantang.reaxon.api.mcp.McpClientFactory;
import com.gantang.reaxon.api.tool.Tool;
import com.gantang.reaxon.api.tool.ToolRegistry;
import com.gantang.reaxon.api.tool.Workspaces;
import com.gantang.reaxon.api.tts.TtsProvider;
import com.gantang.reaxon.api.vision.VisionClient;
import com.gantang.reaxon.impl.tool.builtin.*;
import com.gantang.axiflux.spring.config.props.ToolsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Registers the built-in tool set (http_client / calculator / date_time /
 * file_read / file_write / database_query) into the {@link ToolRegistry}.
 *
 * <p>Toggled by {@code axiflux.tools.builtins.enabled} (default {@code true}).
 * File tools honour {@code axiflux.tools.file.allowed-roots} — comma-separated
 * absolute paths. If left empty the jail falls back to the process working
 * directory (dev convenience; <b>set it explicitly in production</b> — see
 * {@link FileRootsResolver}).
 */
@Configuration
@ConditionalOnProperty(name = "axiflux.tools.builtins.enabled", havingValue = "true", matchIfMissing = true)
public class BuiltInToolsConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BuiltInToolsConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(name = "httpClientTool")
    public Tool httpClientTool(ToolsProperties tools, ObjectProvider<com.gantang.reaxon.api.config.LiveSettings> live) {
        return new HttpClientTool(8192, allowPrivateNetwork(tools), egressProxy(tools))
            .withLiveSettings(live.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(name = "calculatorTool")
    public Tool calculatorTool() {
        return new CalculatorTool();
    }

    @Bean
    @ConditionalOnMissingBean(name = "dateTimeTool")
    public Tool dateTimeTool() {
        return new DateTimeTool();
    }

    @Bean
    @ConditionalOnMissingBean(name = "fileReadTool")
    public Tool fileReadTool(ToolsProperties tools,
                             ObjectProvider<com.gantang.reaxon.api.config.LiveSettings> live,
                             ObjectProvider<Workspaces> workspaces) {
        return new FileReadTool(resolveFileRoots(tools), 32768)
            .withLiveSettings(live.getIfAvailable())
            .withWorkspaces(workspaces.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(name = "fileWriteTool")
    public Tool fileWriteTool(ToolsProperties tools,
                              ObjectProvider<com.gantang.reaxon.api.config.LiveSettings> live,
                              ObjectProvider<Workspaces> workspaces) {
        return new FileWriteTool(resolveFileRoots(tools))
            .withLiveSettings(live.getIfAvailable())
            .withWorkspaces(workspaces.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(name = "fileEditTool")
    public Tool fileEditTool(ToolsProperties tools,
                             ObjectProvider<com.gantang.reaxon.api.config.LiveSettings> live,
                             ObjectProvider<Workspaces> workspaces) {
        return new FileEditTool(resolveFileRoots(tools))
            .withLiveSettings(live.getIfAvailable())
            .withWorkspaces(workspaces.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(name = "gitTool")
    public Tool gitTool(ToolsProperties tools,
                        ObjectProvider<com.gantang.reaxon.api.config.LiveSettings> live,
                        ObjectProvider<Workspaces> workspaces) {
        // Same filesystem jail as the file tools (toolsec P2-4): read actions
        // (status/diff/log) must not reach repositories outside the allowed roots.
        return new GitTool(resolveFileRoots(tools))
            .withLiveSettings(live.getIfAvailable())
            .withWorkspaces(workspaces.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(name = "grepSearchTool")
    public Tool grepSearchTool(ToolsProperties tools,
                               ObjectProvider<com.gantang.reaxon.api.config.LiveSettings> live,
                               ObjectProvider<Workspaces> workspaces) {
        return new GrepSearchTool(resolveFileRoots(tools))
            .withLiveSettings(live.getIfAvailable())
            .withWorkspaces(workspaces.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(name = "codebaseSearchTool")
    public Tool codebaseSearchTool(ToolsProperties tools,
                                   ObjectProvider<com.gantang.reaxon.api.config.LiveSettings> live,
                                   ObjectProvider<com.gantang.reaxon.api.codeindex.CodeIndexStore> index,
                                   ObjectProvider<Workspaces> workspaces) {
        // Same tenant scoping as grep_search (toolsec P2-7): a global root list
        // spanning tenants must not let one tenant index another's code.
        return new CodebaseSearchTool(resolveFileRoots(tools), index.getIfAvailable())
            .withLiveSettings(live.getIfAvailable())
            .withWorkspaces(workspaces.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(name = "loadSkillTool")
    public Tool loadSkillTool(ObjectProvider<com.gantang.reaxon.api.skill.SkillRegistry> registry) {
        return new LoadSkillTool(registry.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(name = "databaseQueryTool")
    public Tool databaseQueryTool(ObjectProvider<DataSource> ds) {
        DataSource resolved = ds.getIfAvailable();
        if (resolved == null) {
            log.info("No DataSource on classpath — database_query tool disabled");
            return new DisabledTool("database_query",
                "Read-only SQL query (currently disabled: no DataSource configured).");
        }
        return new DatabaseQueryTool(resolved);
    }

    @Bean
    @ConditionalOnMissingBean(name = "webFetchTool")
    public Tool webFetchTool(ToolsProperties tools, ObjectProvider<com.gantang.reaxon.api.config.LiveSettings> live) {
        return new WebFetchTool(allowPrivateNetwork(tools), egressProxy(tools))
            .withLiveSettings(live.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(name = "webSearchTool")
    public Tool webSearchTool() {
        return new WebSearchTool();
    }

    @Bean
    @ConditionalOnMissingBean(name = "codeExecutorTool")
    public Tool codeExecutorTool(ToolsProperties tools,
                                 ObjectProvider<Workspaces> workspaces,
                                 ObjectProvider<com.gantang.reaxon.api.config.LiveSettings> live) {
        var ws = workspaces.getIfAvailable();
        String mode = tools == null ? "local" : String.valueOf(tools.getCodeExecutorSandbox());
        if ("docker".equalsIgnoreCase(mode == null ? "" : mode.trim())) {
            return new CodeExecutorTool(new com.gantang.reaxon.impl.tool.support.DockerCommandSandbox(
                tools.getDockerImage(),
                tools.getDockerMemory(),
                tools.getDockerCpus()), ws, resolveFileRoots(tools))
                .withLiveSettings(live.getIfAvailable());
        }
        return new CodeExecutorTool(new com.gantang.reaxon.impl.tool.support.LocalCommandSandbox(), ws,
            resolveFileRoots(tools))
            .withLiveSettings(live.getIfAvailable());
    }

    /**
     * Per-tenant filesystem workspaces (P2-3). Created only when explicitly enabled;
     * its absence keeps every file/command tool on the pre-existing global roots.
     */
    @Bean
    @ConditionalOnMissingBean(Workspaces.class)
    @ConditionalOnProperty(name = "axiflux.tools.workspaces-enabled", havingValue = "true")
    public Workspaces tenantWorkspaces(ToolsProperties tools) {
        String raw = tools.getWorkspacesRoot();
        Path base = (raw == null || raw.isBlank())
            ? Path.of(System.getProperty("user.dir", "."), "workspaces").toAbsolutePath().normalize()
            : Path.of(raw.trim()).toAbsolutePath().normalize();
        var ws = new com.gantang.reaxon.impl.tool.support.TenantWorkspaces(
            base, tools.isWorkspacesCombineGlobalRoots());
        log.info("Tenant workspaces enabled at {} (combineGlobalRoots={})",
            base, tools.isWorkspacesCombineGlobalRoots());
        return ws;
    }

    /**
     * SaaS startup guard (M2-4): in {@code deployment.mode=saas}, fail fast when
     * the {@link com.gantang.reaxon.impl.tool.support.TenantWorkspaces} bean is
     * absent. Always created; it no-ops for standalone (default) deployments.
     */
    @Bean
    @ConditionalOnMissingBean(TenantWorkspaceGuard.class)
    public TenantWorkspaceGuard tenantWorkspaceGuard(
            com.gantang.axiflux.spring.config.props.DeploymentProperties deployment,
            ObjectProvider<Workspaces> workspaces) {
        return new TenantWorkspaceGuard(deployment, workspaces);
    }

    /** Readiness backstop: reports DOWN for SaaS without an active workspace root. */
    @Bean
    @ConditionalOnMissingBean(TenantWorkspacesHealthIndicator.class)
    public TenantWorkspacesHealthIndicator tenantWorkspacesHealthIndicator(
            com.gantang.axiflux.spring.config.props.DeploymentProperties deployment,
            ObjectProvider<Workspaces> workspaces) {
        return new TenantWorkspacesHealthIndicator(deployment, workspaces);
    }

    @Bean
    @ConditionalOnMissingBean(name = "imageAnalyzeTool")
    public Tool imageAnalyzeTool(ObjectProvider<VisionClient> visionClient) {
        return new ImageAnalyzeTool(visionClient.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(name = "ttsTool")
    public Tool ttsTool(ObjectProvider<TtsProvider> ttsProvider) {
        return new TtsTool(ttsProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(name = "emailSendTool")
    public Tool emailSendTool(ObjectProvider<EmailProvider> emailProvider) {
        return new EmailSendTool(emailProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(name = "mcpClientTool")
    public Tool mcpClientTool(McpClientFactory mcpClientFactory) {
        return new McpClientTool(mcpClientFactory);
    }

    @Bean
    @ConditionalOnMissingBean(name = "spawnTaskTool")
    public Tool spawnTaskTool(ObjectProvider<BackgroundSpawner> spawnerProvider) {
        // Lazy supplier so the tool (registered at startup) never forces the
        // agent -> tool-registry -> tool -> agent dependency cycle to resolve early.
        // Spawn is fire-and-forget: the tool returns a handle immediately and the
        // child result is aggregated back asynchronously, so it needs no long timeout.
        return new SpawnTaskTool(spawnerProvider::getIfAvailable);
    }

    @Bean
    @ConditionalOnMissingBean(name = "scheduleTaskTool")
    public Tool scheduleTaskTool(ObjectProvider<com.gantang.axiflux.spring.service.ScheduledTaskService> taskServiceProvider) {
        // Lazy supplier for the same startup-cycle reason as spawnTaskTool.
        return new com.gantang.axiflux.spring.tool.ScheduleTaskTool(taskServiceProvider::getIfAvailable);
    }

    @Bean
    @ConditionalOnMissingBean(name = "todoWriteTool")
    public Tool todoWriteTool(ObjectProvider<com.gantang.reaxon.api.session.SessionManager> sessions) {
        return new com.gantang.reaxon.impl.tool.builtin.TodoWriteTool(sessions.getIfAvailable());
    }

    /**
     * Session-scoped side storage for oversized tool results (roadmap P1-3).
     *
     * <p>Redis-backed when a template is present: the {@code ref://tool-result/<id>}
     * handle lives in the transcript, so a later turn of the same session served by
     * another instance must still be able to dereference it. The backend is chosen
     * here rather than by two competing conditional beans, so the outcome does not
     * depend on configuration-class evaluation order.
     */
    @Bean
    @ConditionalOnMissingBean(com.gantang.reaxon.api.tool.ToolResultStore.class)
    public com.gantang.reaxon.api.tool.ToolResultStore toolResultStore(
            ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> redis,
            ObjectProvider<com.fasterxml.jackson.databind.ObjectMapper> mappers) {
        org.springframework.data.redis.core.StringRedisTemplate template = redis.getIfAvailable();
        if (com.gantang.axiflux.spring.storage.RedisAvailability.reachable(template)) {
            return new com.gantang.axiflux.spring.storage.RedisToolResultStore(template,
                mappers.getIfAvailable(com.fasterxml.jackson.databind.ObjectMapper::new));
        }
        return new com.gantang.reaxon.impl.tool.support.InMemoryToolResultStore();
    }

    @Bean
    @ConditionalOnMissingBean(name = "resultReadTool")
    public Tool resultReadTool(com.gantang.reaxon.api.tool.ToolResultStore store) {
        return new com.gantang.reaxon.impl.tool.builtin.ResultReadTool(store);
    }

    /** Register all discovered Tool beans into the registry after startup. */
    @Bean
    public SmartInitializingSingleton builtInToolsRegistrar(ToolRegistry registry, List<Tool> tools) {
        return () -> {
            for (Tool t : tools) {
                if (t instanceof DisabledTool) continue;
                registry.register(t);
            }
            log.info("Registered {} built-in tools", tools.stream().filter(t -> !(t instanceof DisabledTool)).count());
        };
    }

    private static boolean allowPrivateNetwork(ToolsProperties tools) {
        return tools != null && tools.isAllowPrivateNetwork();
    }

    /**
     * Shared egress forward proxy as an injectable bean (P1-5). All outbound
     * HttpClient builders (HTTP tools, embedding, skill registry / ClawHub,
     * MCP SSE) should use this so a locked-down deployment's egress policy is
     * honoured uniformly. Returns an {@link Optional} so consumers can stay
     * direct-by-default without null checks at every injection point.
     */
    @Bean
    @ConditionalOnMissingBean(name = "egressProxySelector")
    public java.util.Optional<java.net.ProxySelector> egressProxySelector(ToolsProperties tools) {
        return java.util.Optional.ofNullable(egressProxy(tools));
    }

    /**
     * Build the optional egress forward proxy ({@code host:port}) shared by the
     * HTTP tools. Fails fast on a malformed value rather than silently sending
     * traffic direct. Returns {@code null} when unconfigured.
     */
    static java.net.ProxySelector egressProxy(ToolsProperties tools) {
        if (tools == null) return null;
        return com.gantang.reaxon.impl.tool.support.EgressProxy.parse(tools.getEgressProxy());
    }

    /**
     * Resolve the filesystem jail for the file tools. Delegates to
     * {@link FileRootsResolver} so boot and hot-reload agree on the default.
     */
    private List<Path> resolveFileRoots(ToolsProperties tools) {
        String raw = tools != null ? tools.getFileAllowedRoots() : null;
        return FileRootsResolver.resolve(raw);
    }

    /** Placeholder that keeps the bean-graph valid when a dependency is missing. */
    private record DisabledTool(String toolName, String reason) implements Tool {
        @Override public String name() { return toolName; }
        @Override public String description() { return reason; }
        @Override public com.fasterxml.jackson.databind.JsonNode parameters() {
            return new com.fasterxml.jackson.databind.node.ObjectNode(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
        }
        @Override public boolean hidden() { return true; }
        @Override public com.gantang.reaxon.api.tool.ToolResult execute(
                String callId, java.util.Map<String, Object> params,
                com.gantang.reaxon.api.agent.AgentContext context) {
            return com.gantang.reaxon.api.tool.ToolResult.failure(callId, reason);
        }
    }
}
