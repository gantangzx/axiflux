package com.gantang.tianshu.impl.workflow;

import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.workflow.CheckpointStore;
import com.gantang.tianshu.api.workflow.StateGraph;

/**
 * Per-run runtime handle handed to every {@link NodeHandler}: identity, collaborators,
 * the checkpoint store (suspending nodes), the {@link BranchExecutor} (parallel node),
 * and the graph being executed.
 */
final class NodeRuntime {

    private final String graphName;
    private final String runId;
    private final GraphServices services;
    private final CheckpointStore checkpointStore;
    private final AgentContext baseContext;
    private final BranchExecutor branchExecutor;
    private final StateGraph currentGraph;

    NodeRuntime(String graphName, String runId, GraphServices services,
                CheckpointStore checkpointStore, AgentContext baseContext,
                BranchExecutor branchExecutor, StateGraph currentGraph) {
        this.graphName = graphName;
        this.runId = runId;
        this.services = services;
        this.checkpointStore = checkpointStore;
        this.baseContext = baseContext;
        this.branchExecutor = branchExecutor;
        this.currentGraph = currentGraph;
    }

    String graphName() {
        return graphName;
    }

    String runId() {
        return runId;
    }

    GraphServices services() {
        return services;
    }

    CheckpointStore checkpointStore() {
        return checkpointStore;
    }

    AgentContext baseContext() {
        return baseContext;
    }

    BranchExecutor branchExecutor() {
        return branchExecutor;
    }

    StateGraph currentGraph() {
        return currentGraph;
    }
}
