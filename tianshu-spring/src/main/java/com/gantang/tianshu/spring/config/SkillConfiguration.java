package com.gantang.tianshu.spring.config;

import com.gantang.tianshu.api.agent.Agent;
import com.gantang.tianshu.api.skill.*;
import com.gantang.tianshu.api.tool.ToolRegistry;
import com.gantang.tianshu.impl.skill.DefaultSkillRegistry;
import com.gantang.tianshu.impl.skill.MarkdownSkillLoader;
import com.gantang.tianshu.impl.skill.SkillLoaderFactory;
import com.gantang.tianshu.impl.skill.WorkflowSkillExecutor;
import com.gantang.tianshu.impl.skill.interceptor.LoggingInterceptor;
import com.gantang.tianshu.impl.skill.interceptor.RequiredToolsInterceptor;
import com.gantang.tianshu.impl.skill.interceptor.TimeoutInterceptor;
import com.gantang.tianshu.impl.skill.strategy.LlmGuidedWorkflowStrategy;
import com.gantang.tianshu.impl.skill.strategy.ParallelWorkflowStrategy;
import com.gantang.tianshu.impl.skill.strategy.SequentialWorkflowStrategy;
import com.gantang.tianshu.spring.service.SkillDirectoryWatcher;
import com.gantang.tianshu.spring.config.props.SkillsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.List;

/**
 * Auto-configuration for the Skill subsystem.
 *
 * <p>Assembles the Strategy/Registry/Factory/Interceptor/Observer chain and
 * exposes a single {@link SkillExecutor} bean.  Wires the default set of
 * strategies and interceptors; users can supply their own by declaring
 * additional beans of the corresponding types (they'll be picked up via
 * {@link ObjectProvider}).
 */
@AutoConfiguration
@AutoConfigureAfter(TianshuAutoConfiguration.class)
@ConditionalOnProperty(name = "tianshu.skills.enabled", havingValue = "true", matchIfMissing = true)
public class SkillConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SkillConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public SkillRegistry skillRegistry() {
        return new DefaultSkillRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillLoaderFactory skillLoaderFactory(ObjectProvider<SkillLoader> extra) {
        SkillLoaderFactory factory = new SkillLoaderFactory(List.of(new MarkdownSkillLoader()));
        extra.orderedStream().forEach(factory::addLoader);
        return factory;
    }

    @Bean
    @ConditionalOnBean(ToolRegistry.class)
    @ConditionalOnMissingBean
    public SkillExecutor skillExecutor(SkillRegistry registry,
                                       SkillLoaderFactory loaderFactory,
                                       ToolRegistry toolRegistry,
                                       ObjectProvider<WorkflowStrategy> userStrategies,
                                       ObjectProvider<SkillInterceptor> userInterceptors,
                                       ObjectProvider<SkillEventListener> listeners,
                                       ObjectProvider<Agent> agents,
                                       SkillsProperties skillsProps) {
        // Default strategies plus any user-supplied ones.
        List<WorkflowStrategy> defaults = List.of(
            new SequentialWorkflowStrategy(toolRegistry),
            new ParallelWorkflowStrategy(toolRegistry),
            new LlmGuidedWorkflowStrategy(toolRegistry)
        );
        WorkflowSkillExecutor executor = new WorkflowSkillExecutor(registry, loaderFactory, defaults);
        userStrategies.orderedStream().forEach(executor::registerStrategy);

        // Inject Agent so LlmGuidedWorkflowStrategy can re-enter the tool loop.
        agents.getIfAvailable(() -> null);
        agents.orderedStream().findFirst().ifPresent(executor::setAgent);

        // Default interceptor chain.
        executor.addInterceptor(new LoggingInterceptor());
        executor.addInterceptor(new TimeoutInterceptor(
            Duration.ofSeconds(skillsProps.getTimeoutSeconds())));
        executor.addInterceptor(new RequiredToolsInterceptor(toolRegistry));

        userInterceptors.orderedStream().forEach(executor::addInterceptor);
        listeners.orderedStream().forEach(executor::addListener);

        String root = skillsProps.getRootDir();
        if (root != null && !root.isBlank()) {
            executor.loadFromDirectory(root);
            log.info("Skills loaded from {} (count={})", root, executor.registry().size());
        }
        return executor;
    }

    /**
     * Filesystem watcher that hot-reloads skills on SKILL.md create/modify/delete.
     * SmartLifecycle — starts after context refresh, stops on shutdown.
     */
    @Bean
    @ConditionalOnBean(SkillExecutor.class)
    @ConditionalOnProperty(name = "tianshu.skills.hot-reload", havingValue = "true", matchIfMissing = true)
    public SkillDirectoryWatcher skillDirectoryWatcher(SkillExecutor executor, SkillsProperties skillsProps) {
        return new SkillDirectoryWatcher(skillsProps.getRootDir(), executor::reloadSkills);
    }

    @Bean
    @ConditionalOnBean(SkillExecutor.class)
    @ConditionalOnMissingBean
    public com.gantang.tianshu.spring.service.SkillInstallService skillInstallService(
            SkillsProperties skillsProps, SkillExecutor executor,
            org.springframework.beans.factory.ObjectProvider<com.gantang.tianshu.spring.service.SkillLedgerService> ledger,
            org.springframework.beans.factory.ObjectProvider<java.util.Optional<java.net.ProxySelector>> egressProxy) {
        java.net.ProxySelector proxy = egressProxy.getIfAvailable(java.util.Optional::empty).orElse(null);
        return new com.gantang.tianshu.spring.service.SkillInstallService(skillsProps, executor, ledger, proxy);
    }

    /** Skill install ledger (Sprint A): present only when JPA/repositories are active. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(com.gantang.tianshu.storage.repository.SkillRepository.class)
    public com.gantang.tianshu.spring.service.SkillLedgerService skillLedgerService(
            com.gantang.tianshu.storage.repository.SkillRepository repo,
            ObjectProvider<SkillRegistry> registry,
            ObjectProvider<SkillExecutor> executor,
            SkillsProperties skillsProps) {
        return new com.gantang.tianshu.spring.service.SkillLedgerService(repo, registry, executor, skillsProps);
    }

    /** Market proxy (Sprint C): registry search merged with local install state. */
    @Bean
    @ConditionalOnMissingBean
    public com.gantang.tianshu.spring.service.SkillMarketService skillMarketService(
            SkillsProperties skillsProps,
            ObjectProvider<com.gantang.tianshu.spring.service.SkillLedgerService> ledger) {
        return new com.gantang.tianshu.spring.service.SkillMarketService(skillsProps, ledger);
    }

    /**
     * Wire the disabled-names gate, reload (so disabled skills are never registered)
     * and align the ledger with the filesystem — once all beans are ready.
     */
    @Bean
    @ConditionalOnBean(com.gantang.tianshu.spring.service.SkillLedgerService.class)
    public org.springframework.beans.factory.SmartInitializingSingleton skillLedgerReconcileOnStartup(
            com.gantang.tianshu.spring.service.SkillLedgerService ledger) {
        return () -> {
            try {
                ledger.applyDisabledFilter();
                com.gantang.tianshu.spring.service.SkillLedgerService.ReconcileResult r = ledger.reloadAndReconcile();
                log.info("Skill ledger startup reconcile: {} rows, {} disabled",
                    r.total(), ledger.disabledNames().size());
            } catch (Exception e) {
                log.warn("Skill ledger startup reconcile failed: {}", e.toString());
            }
        };
    }
}
