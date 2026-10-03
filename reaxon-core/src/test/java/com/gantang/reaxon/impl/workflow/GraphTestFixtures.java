package com.gantang.reaxon.impl.workflow;

import com.gantang.reaxon.api.agent.Agent;
import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.agent.AgentResponse;
import com.gantang.reaxon.api.agent.AgentStatus;
import com.gantang.reaxon.api.agent.ApprovalManager;
import com.gantang.reaxon.api.tool.ToolResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** Shared in-memory fakes for the state-graph engine tests. */
final class GraphTestFixtures {

    private GraphTestFixtures() {}

    static AgentContext baseContext() {
        return AgentContext.builder()
            .sessionId("sess-1")
            .userId("user-1")
            .build();
    }

    /** An agent that returns scripted responses in order, echoing the query by default. */
    static final class ScriptAgent implements Agent {
        private final Deque<String> responses = new ArrayDeque<>();
        private final CopyOnWriteArrayList<String> queries = new CopyOnWriteArrayList<>();
        private boolean echo;

        ScriptAgent() {
            this.echo = true;
        }

        ScriptAgent(String... scripted) {
            this.echo = false;
            responses.addAll(List.of(scripted));
        }

        List<String> queries() {
            return List.copyOf(queries);
        }

        @Override
        public String getAgentId() {
            return "script-agent";
        }

        @Override
        public Mono<AgentResponse> process(AgentContext context) {
            queries.add(context.currentQuery());
            String content = echo ? context.currentQuery() : responses.removeFirst();
            return Mono.just(AgentResponse.builder().content(content).build());
        }

        @Override
        public Flux<com.gantang.reaxon.api.agent.AgentEvent> processStream(AgentContext context) {
            return process(context).flatMapMany(r -> Flux.just(
                com.gantang.reaxon.api.agent.AgentEvent.done(r, null)));
        }

        @Override
        public void interrupt(String sessionId) { }

        @Override
        public AgentStatus getStatus(String sessionId) {
            return new AgentStatus(sessionId, AgentStatus.State.IDLE, 0, null, 0L);
        }
    }

    /**
     * Approval manager that auto-resolves every submitted request: approve when
     * {@code approve=true}, reject otherwise. No blocking wait.
     */
    static final class AutoApprovalManager implements ApprovalManager {
        private final boolean approve;
        private final List<ApprovalRequest> submitted = new CopyOnWriteArrayList<>();

        AutoApprovalManager(boolean approve) {
            this.approve = approve;
        }

        List<ApprovalRequest> submitted() {
            return List.copyOf(submitted);
        }

        @Override
        public Mono<ToolResult> submit(ApprovalRequest request) {
            submitted.add(request);
            ToolResult result = approve
                ? ToolResult.success(request.callId(), "approved")
                : ToolResult.failure(request.callId(), "rejected");
            return Mono.just(result);
        }

        @Override
        public boolean approve(String callId, String approverId) {
            return false;
        }

        @Override
        public boolean reject(String callId, String approverId, String reason) {
            return false;
        }

        @Override
        public Optional<ApprovalRequest> get(String callId) {
            return Optional.empty();
        }

        @Override
        public List<ApprovalRequest> listPending(String userId) {
            return List.of();
        }
    }

    /** Quick successful response. */
    static AgentResponse response(String content) {
        return AgentResponse.builder().content(content).build();
    }

    /** Timestamp helper kept for parity with future expiry assertions. */
    @SuppressWarnings("unused")
    static Instant now() {
        return Instant.now();
    }
}
