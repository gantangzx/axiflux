package com.gantang.tianshu.spring.config;

import com.gantang.tianshu.api.agent.Agent;
import com.gantang.tianshu.api.agent.ApprovalManager;
import com.gantang.tianshu.api.skill.SkillExecutor;
import com.gantang.tianshu.api.skill.SkillRegistry;
import com.gantang.tianshu.api.tool.ToolRegistry;
import com.gantang.tianshu.api.workflow.CheckpointStore;
import com.gantang.tianshu.api.workflow.GraphRunner;
import com.gantang.tianshu.impl.workflow.DefaultGraphRunner;
import com.gantang.tianshu.impl.workflow.InMemoryCheckpointStore;
import com.gantang.tianshu.spring.config.props.WorkflowProperties;
import com.gantang.tianshu.spring.service.GraphCatalog;
import com.gantang.tianshu.spring.service.GraphDiscoveryLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the state-graph workflow engine.
 *
 * <p>Assembles a {@link GraphRunner} from the existing {@link Agent},
 * {@link ToolRegistry}, {@link SkillRegistry}/{@link SkillExecutor} and
 * {@link ApprovalManager} beans (all optional), wires the {@link CheckpointStore},
 * and discovers graph definitions at startup into a {@link GraphCatalog}.
 *
 * <p>Turned on by default; disable with {@code tianshu.workflow.enabled=false}.
 */
@AutoConfiguration
@AutoConfigureAfter({TianshuAutoConfiguration.class, SkillConfiguration.class})
@ConditionalOnClass({GraphRunner.class, Agent.class})
@ConditionalOnProperty(name = "tianshu.workflow.enabled", havingValue = "true", matchIfMissing = true)
public class WorkflowConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WorkflowConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(CheckpointStore.class)
    public CheckpointStore checkpointStore() {
        return new InMemoryCheckpointStore();
    }

    @Bean
    @ConditionalOnMissingBean(GraphCatalog.class)
    public GraphCatalog graphCatalog() {
        return new GraphCatalog();
    }

    @Bean
    @ConditionalOnMissingBean(GraphRunner.class)
    public GraphRunner graphRunner(
            ObjectProvider<Agent> agent,
            ObjectProvider<ToolRegistry> toolRegistry,
            ObjectProvider<SkillRegistry> skillRegistry,
            ObjectProvider<SkillExecutor> skillExecutor,
            ObjectProvider<ApprovalManager> approvalManager,
            CheckpointStore checkpointStore) {
        return new DefaultGraphRunner(
            agent.getIfAvailable(),
            toolRegistry.getIfAvailable(),
            skillRegistry.getIfAvailable(),
            skillExecutor.getIfAvailable(),
            approvalManager.getIfAvailable(),
            checkpointStore);
    }

    /** Discover and register graph definitions once the catalog bean exists. */
    @Bean
    @ConditionalOnMissingBean(GraphDiscoveryLoader.class)
    public GraphDiscoveryLoader graphDiscoveryLoader(
            WorkflowProperties props, GraphCatalog catalog) {
        GraphDiscoveryLoader discovery = new GraphDiscoveryLoader(props);
        discovery.loadInto(catalog);
        log.info("Workflow graphs registered (count={})", catalog.size());
        return discovery;
    }
}
