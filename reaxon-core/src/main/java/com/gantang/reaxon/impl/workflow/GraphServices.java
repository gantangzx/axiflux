package com.gantang.reaxon.impl.workflow;

import com.gantang.reaxon.api.agent.Agent;
import com.gantang.reaxon.api.agent.ApprovalManager;
import com.gantang.reaxon.api.skill.SkillExecutor;
import com.gantang.reaxon.api.skill.SkillRegistry;
import com.gantang.reaxon.api.tool.ToolRegistry;

/**
 * The collaborators a graph execution may call into. Every field is optional
 * (nullable): a graph only needs the services its nodes actually use, and a missing
 * service surfaces as a node failure rather than a startup error.
 */
record GraphServices(
    Agent agent,
    ToolRegistry toolRegistry,
    SkillRegistry skillRegistry,
    SkillExecutor skillExecutor,
    ApprovalManager approvalManager
) {

    static Builder builder() {
        return new Builder();
    }

    static final class Builder {
        private Agent agent;
        private ToolRegistry toolRegistry;
        private SkillRegistry skillRegistry;
        private SkillExecutor skillExecutor;
        private ApprovalManager approvalManager;

        Builder agent(Agent v) { this.agent = v; return this; }
        Builder toolRegistry(ToolRegistry v) { this.toolRegistry = v; return this; }
        Builder skillRegistry(SkillRegistry v) { this.skillRegistry = v; return this; }
        Builder skillExecutor(SkillExecutor v) { this.skillExecutor = v; return this; }
        Builder approvalManager(ApprovalManager v) { this.approvalManager = v; return this; }

        GraphServices build() {
            return new GraphServices(agent, toolRegistry, skillRegistry, skillExecutor, approvalManager);
        }
    }
}
