package com.gantang.tianshu.impl.workflow;

import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.agent.ApprovalManager;
import com.gantang.tianshu.api.tool.ToolResult;
import com.gantang.tianshu.api.workflow.ApprovalSpec;
import com.gantang.tianshu.api.workflow.GraphState;
import com.gantang.tianshu.api.workflow.NodeSpec;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * {@link com.gantang.tianshu.api.workflow.NodeKind#APPROVAL}: submits a human-approval
 * request and routes to the configured approve / reject branch. A timeout is treated
 * as a rejection (fail-closed). The decision outcome is recorded as the node output.
 */
final class ApprovalNodeHandler implements NodeHandler {

    @Override
    public Mono<NodeOutcome> execute(NodeSpec spec, GraphState state, NodeRuntime runtime) {
        ApprovalManager manager = runtime.services().approvalManager();
        if (manager == null) {
            return Mono.just(NodeOutcome.fail(state,
                "no approval manager configured for node " + spec.id()));
        }

        ApprovalSpec cfg = spec.approval();
        AgentContext base = runtime.baseContext();
        String callId = "appr_" + UUID.randomUUID();
        String description = GraphSupport.render(cfg.description(), state);

        ApprovalManager.ApprovalRequest request = new ApprovalManager.ApprovalRequest(
            callId,
            base.sessionId(),
            base.userId(),
            cfg.subject(),
            description,
            description,
            Instant.now(),
            cfg.timeout() == null ? null : Instant.now().plus(cfg.timeout()));

        Duration timeout = cfg.timeout() == null ? Duration.ofMinutes(5) : cfg.timeout();

        return manager.submit(request)
            .timeout(timeout)
            .map(result -> route(spec, state, result))
            .onErrorResume(e -> Mono.just(timeoutOrFail(spec, state, e)));
    }

    private NodeOutcome route(NodeSpec spec, GraphState state, ToolResult result) {
        ApprovalSpec cfg = spec.approval();
        GraphState next = state.withNodeOutput(spec.id(), result.displayContent());
        if (result.success()) {
            return NodeOutcome.next(next, cfg.onApprove());
        }
        return NodeOutcome.next(next, cfg.onReject());
    }

    private NodeOutcome timeoutOrFail(NodeSpec spec, GraphState state, Throwable error) {
        // Fail-closed: a wait timeout follows the rejection branch.
        if (error instanceof java.util.concurrent.TimeoutException
            || error.getClass().getSimpleName().contains("Timeout")) {
            GraphState next = state.withNodeOutput(spec.id(), "approval timed out");
            return NodeOutcome.next(next, spec.approval().onReject());
        }
        return NodeOutcome.fail(state,
            "approval node " + spec.id() + " failed: " + error.getMessage());
    }
}
