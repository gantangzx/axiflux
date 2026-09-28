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
import com.gantang.tianshu.spring.service.JpaCheckpointGateway;
import com.gantang.tianshu.spring.service.JpaCheckpointStore;
import com.gantang.tianshu.storage.repository.GraphCheckpointRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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

    /** Transactional gateway; only created when the durable repository exists. */
    @Bean
    @ConditionalOnBean(GraphCheckpointRepository.class)
    @ConditionalOnMissingBean(JpaCheckpointGateway.class)
    public JpaCheckpointGateway jpaCheckpointGateway(
            GraphCheckpointRepository repository,
            ObjectProvider<com.fasterxml.jackson.databind.ObjectMapper> mapper) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = mapper.getIfAvailable(
            com.fasterxml.jackson.databind.ObjectMapper::new);
        return new JpaCheckpointGateway(repository, objectMapper);
    }

    /**
     * Durable store when JPA and the graph_checkpoint repository are active, so
     * paused runs survive restarts and resume is an atomic compare-and-set.
     */
    @Bean
    @ConditionalOnBean(GraphCheckpointRepository.class)
    @ConditionalOnMissingBean(CheckpointStore.class)
    public CheckpointStore jpaCheckpointStore(JpaCheckpointGateway gateway) {
        log.info("Workflow checkpoint store: durable JPA (graph_checkpoint)");
        return new JpaCheckpointStore(gateway);
    }

    /** Zero-dependency fallback: keep paused runs in an in-process map. */
    @Bean
    @ConditionalOnMissingBean({CheckpointStore.class, GraphCheckpointRepository.class})
    public CheckpointStore checkpointStore() {
        log.info("Workflow checkpoint store: in-memory");
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
