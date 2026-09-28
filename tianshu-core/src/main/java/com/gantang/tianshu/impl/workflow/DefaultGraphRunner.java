package com.gantang.tianshu.impl.workflow;

import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.workflow.Checkpoint;
import com.gantang.tianshu.api.workflow.CheckpointStore;
import com.gantang.tianshu.api.workflow.GraphEvent;
import com.gantang.tianshu.api.workflow.GraphRunResult;
import com.gantang.tianshu.api.workflow.GraphRunner;
import com.gantang.tianshu.api.workflow.GraphState;
import com.gantang.tianshu.api.workflow.NodeKind;
import com.gantang.tianshu.api.workflow.NodeSpec;
import com.gantang.tianshu.api.workflow.StateGraph;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reactor-driven {@link GraphRunner}.
 *
 * <p>One reactive recursion ({@link #drive}) backs both the event stream and the
 * terminal result. At each node it invokes the matching {@link NodeHandler}, then
 * either follows the handler's forced target (decision/approval) or resolves the
 * node's outgoing edges — first matching conditional edge, else the unconditional
 * edge, else {@link StateGraph#END}. Suspending nodes persist a {@link Checkpoint};
 * {@link #resume} loads it, rebuilds the safe base context, merges the payload, and
 * continues from the pausing node's routing.
 */
public final class DefaultGraphRunner implements GraphRunner {

    private final Map<NodeKind, NodeHandler> handlers = new EnumMap<>(NodeKind.class);
    private final CheckpointStore checkpointStore;
    private final GraphServices services;

    public DefaultGraphRunner(GraphServices services, CheckpointStore checkpointStore) {
        this.services = services;
        this.checkpointStore = checkpointStore == null ? new InMemoryCheckpointStore() : checkpointStore;
        registerDefaults();
    }

    /**
     * Convenience assembly for hosts (such as the Spring layer) that cannot see the
     * package-private {@link GraphServices} type. Every collaborator may be null; a
     * graph only needs the services its nodes actually use.
     */
    public DefaultGraphRunner(
            com.gantang.tianshu.api.agent.Agent agent,
            com.gantang.tianshu.api.tool.ToolRegistry toolRegistry,
            com.gantang.tianshu.api.skill.SkillRegistry skillRegistry,
            com.gantang.tianshu.api.skill.SkillExecutor skillExecutor,
            com.gantang.tianshu.api.agent.ApprovalManager approvalManager,
            CheckpointStore checkpointStore) {
        this(GraphServices.builder()
            .agent(agent)
            .toolRegistry(toolRegistry)
            .skillRegistry(skillRegistry)
            .skillExecutor(skillExecutor)
            .approvalManager(approvalManager)
            .build(), checkpointStore);
    }

    private void registerDefaults() {
        handlers.put(NodeKind.AGENT, new AgentNodeHandler());
        handlers.put(NodeKind.TOOL, new ToolNodeHandler());
        handlers.put(NodeKind.SKILL, new SkillNodeHandler());
        handlers.put(NodeKind.DECISION, new DecisionNodeHandler());
        handlers.put(NodeKind.PARALLEL, new ParallelNodeHandler());
        handlers.put(NodeKind.PAUSE, new PauseNodeHandler());
        handlers.put(NodeKind.APPROVAL, new ApprovalNodeHandler());
        handlers.put(NodeKind.PASS, new PassNodeHandler());
        handlers.put(NodeKind.CUSTOM, new CustomNodeHandler());
    }

    /** Register or override the handler for a node kind. */
    public DefaultGraphRunner withHandler(NodeKind kind, NodeHandler handler) {
        handlers.put(kind, handler);
        return this;
    }

    // === start ===

    @Override
    public Flux<GraphEvent> startStream(StateGraph graph, AgentContext context, Object input) {
        return startFromStream(graph, context, GraphState.ofInput(input));
    }

    @Override
    public Flux<GraphEvent> startFromStream(StateGraph graph, AgentContext context,
                                            GraphState initialState) {
        String runId = newRunId();
        registerDefinition(graph);
        NodeRuntime runtime = newRuntime(graph, runId, context);
        return Flux.concat(
            Flux.just(GraphEvent.started()),
            driveFlux(graph, runtime, initialState, firstTarget(graph), 0));
    }

    @Override
    public Mono<GraphRunResult> start(StateGraph graph, AgentContext context, Object input) {
        return startFrom(graph, context, GraphState.ofInput(input));
    }

    @Override
    public Mono<GraphRunResult> startFrom(StateGraph graph, AgentContext context,
                                          GraphState initialState) {
        String runId = newRunId();
        registerDefinition(graph);
        NodeRuntime runtime = newRuntime(graph, runId, context);
        return drive(graph, runtime, initialState, firstTarget(graph), 0)
            .map(term -> new GraphRunResult(runId, term.status(), term.state()));
    }

    // === resume ===

    @Override
    public Flux<GraphEvent> resumeStream(String runId, Object payload) {
        return prepareResume(runId, payload)
            .flatMapMany(ctx -> {
                java.util.concurrent.atomic.AtomicBoolean repaused =
                    new java.util.concurrent.atomic.AtomicBoolean();
                Flux<GraphEvent> events = Flux.concat(
                    Flux.just(new GraphEvent(GraphEvent.Type.RESUMED, ctx.nodeId(), null)),
                    driveFlux(ctx.graph(), ctx.runtime(), ctx.state(), ctx.next(), 0))
                    .doOnNext(e -> {
                        if (e.type() == GraphEvent.Type.PAUSED) {
                            repaused.set(true);
                        }
                    });
                // Remove the claimed row only on completion; a re-pause already
                // replaced it with a fresh PAUSED row.
                Flux<GraphEvent> tail = Flux.defer(() -> repaused.get()
                    ? Flux.<GraphEvent>empty()
                    : removeCheckpoint(runId).then(Mono.<GraphEvent>empty()).flux());
                return Flux.concat(events, tail)
                    .onErrorResume(error -> failResume(runId, ctx.checkpoint(), error)
                        .then(Mono.error(error)));
            });
    }

    @Override
    public Mono<GraphRunResult> resume(String runId, Object payload) {
        return prepareResume(runId, payload)
            .flatMap(ctx -> drive(ctx.graph(), ctx.runtime(), ctx.state(), ctx.next(), 0)
                .flatMap(term -> finishResume(runId, term))
                .map(term -> new GraphRunResult(runId, term.status(), term.state()))
                .onErrorResume(error -> failResume(runId, ctx.checkpoint(), error)
                    .then(Mono.error(error))));
    }

    /** Remove the claimed row only when the resumed run actually completed. */
    private Mono<Terminal> finishResume(String runId, Terminal term) {
        if (term.status() == GraphRunResult.Status.COMPLETED) {
            return checkpointStore.remove(runId).thenReturn(term);
        }
        return Mono.just(term);
    }

    private Mono<Void> removeCheckpoint(String runId) {
        return checkpointStore.remove(runId).onErrorResume(e -> Mono.empty());
    }

    private record ResumeContext(StateGraph graph, NodeRuntime runtime,
                                 GraphState state, String nodeId, String next,
                                 Checkpoint checkpoint) {}

    /**
     * Atomically claim the paused run (PAUSED → RESUMING), then rebuild the safe base
     * context and merge the payload. A missing or already-claimed run yields the
     * same "no paused run" error so concurrent resumes drive the run at most once.
     */
    private Mono<ResumeContext> prepareResume(String runId, Object payload) {
        return checkpointStore.claim(runId)
            .switchIfEmpty(Mono.error(new IllegalStateException("no paused run: " + runId)))
            .flatMap(checkpoint -> GraphDefinitions.from(checkpoint.graphName())
                .map(Mono::just)
                .orElseGet(() -> Mono.error(new IllegalStateException(
                    "graph definition '" + checkpoint.graphName() + "' is not available for resume")))
                .flatMap(graph -> {
                    GraphState state = applyPayload(checkpoint, payload);
                    AgentContext base = checkpoint.toBaseContext();
                    NodeRuntime runtime = newRuntime(graph, runId, base);
                    String next = resolveRouting(graph, checkpoint.nodeId(), state);
                    return Mono.just(new ResumeContext(graph, runtime, state,
                        checkpoint.nodeId(), next, checkpoint));
                }));
    }

    /** Put a failed resume back so the run stays PAUSED and can be retried. */
    private Mono<Void> failResume(String runId, Checkpoint original, Throwable error) {
        return checkpointStore.release(original)
            .onErrorResume(releaseError -> {
                // Best effort; swallow so the original failure is what propagates.
                return Mono.empty();
            });
    }

    // === core recursion: terminal form ===

    private record Terminal(GraphState state, GraphRunResult.Status status) {}

    private Mono<Terminal> drive(StateGraph graph, NodeRuntime runtime,
                                 GraphState state, String nodeId, int stepsUsed) {
        if (StateGraph.END.equals(nodeId)) {
            return Mono.just(new Terminal(state, GraphRunResult.Status.COMPLETED));
        }
        NodeSpec spec = graph.nodes().get(nodeId);
        if (spec == null) {
            return Mono.just(new Terminal(state, GraphRunResult.Status.COMPLETED));
        }
        if (stepsUsed >= graph.maxSteps()) {
            return Mono.error(new GraphExecutionException(
                "step budget of " + graph.maxSteps() + " exceeded at node " + nodeId));
        }
        NodeHandler handler = handlers.get(spec.kind());
        if (handler == null) {
            return Mono.error(new GraphExecutionException(
                "no handler for node kind " + spec.kind() + " (node " + nodeId + ")"));
        }

        return handler.execute(spec, state, runtime)
            .flatMap(outcome -> afterNode(graph, runtime, spec, outcome, stepsUsed + 1));
    }

    private Mono<Terminal> afterNode(StateGraph graph, NodeRuntime runtime, NodeSpec spec,
                                     NodeOutcome outcome, int stepsUsed) {
        return switch (outcome.action()) {
            case FAIL -> Mono.error(new GraphExecutionException(
                outcome.reason() == null ? "node " + spec.id() + " failed" : outcome.reason()));
            case PAUSE -> Mono.just(new Terminal(outcome.state(), GraphRunResult.Status.PAUSED));
            case CONTINUE -> {
                String target = outcome.target() != null
                    ? outcome.target()
                    : resolveRouting(graph, spec.id(), outcome.state());
                yield drive(graph, runtime, outcome.state(), target, stepsUsed);
            }
        };
    }

    // === core recursion: event-stream form ===

    private Flux<GraphEvent> driveFlux(StateGraph graph, NodeRuntime runtime,
                                       GraphState state, String nodeId, int stepsUsed) {
        if (StateGraph.END.equals(nodeId)) {
            return Flux.just(GraphEvent.completed());
        }
        NodeSpec spec = graph.nodes().get(nodeId);
        if (spec == null) {
            return Flux.just(GraphEvent.completed());
        }
        if (stepsUsed >= graph.maxSteps()) {
            return Flux.just(GraphEvent.error(
                "step budget of " + graph.maxSteps() + " exceeded at node " + nodeId));
        }
        NodeHandler handler = handlers.get(spec.kind());
        if (handler == null) {
            return Flux.just(GraphEvent.error(
                "no handler for node kind " + spec.kind() + " (node " + nodeId + ")"));
        }

        return Flux.concat(
            Flux.just(GraphEvent.nodeStart(nodeId)),
            handler.execute(spec, state, runtime)
                .flatMapMany(outcome -> afterNodeFlux(graph, runtime, spec, outcome, stepsUsed + 1)));
    }

    private Flux<GraphEvent> afterNodeFlux(StateGraph graph, NodeRuntime runtime, NodeSpec spec,
                                           NodeOutcome outcome, int stepsUsed) {
        return switch (outcome.action()) {
            case FAIL -> Flux.just(GraphEvent.error(
                outcome.reason() == null ? "node " + spec.id() + " failed" : outcome.reason()));
            case PAUSE -> Flux.just(GraphEvent.paused(spec.id(), outcome.reason()));
            case CONTINUE -> {
                String target = outcome.target() != null
                    ? outcome.target()
                    : resolveRouting(graph, spec.id(), outcome.state());
                yield Flux.concat(
                    Flux.just(GraphEvent.nodeEnd(spec.id(), textOf(outcome, spec))),
                    driveFlux(graph, runtime, outcome.state(), target, stepsUsed));
            }
        };
    }

    // === routing ===

    private String resolveRouting(StateGraph graph, String nodeId, GraphState state) {
        List<com.gantang.tianshu.api.workflow.EdgeSpec> outgoing = graph.outgoing(nodeId);
        String fallback = null;
        for (com.gantang.tianshu.api.workflow.EdgeSpec edge : outgoing) {
            if (!edge.isConditional()) {
                if (fallback == null) {
                    fallback = edge.target();
                }
                continue;
            }
            if (GraphSupport.evaluate(edge.condition(), state)) {
                return edge.target();
            }
        }
        return fallback != null ? fallback : StateGraph.END;
    }

    private String firstTarget(StateGraph graph) {
        List<com.gantang.tianshu.api.workflow.EdgeSpec> fromStart = graph.outgoing(StateGraph.START);
        if (fromStart.isEmpty()) {
            throw new GraphExecutionException("graph '" + graph.name() + "' has no edge from start");
        }
        return fromStart.get(0).target();
    }

    // === helpers ===

    private GraphState applyPayload(Checkpoint checkpoint, Object payload) {
        GraphState state = checkpoint.state();
        if (payload == null) {
            return state;
        }
        String key = checkpoint.reason() != null && !checkpoint.reason().isBlank()
            ? checkpoint.reason() : "resume";
        return state.withVariable(key, payload);
    }

    private NodeRuntime newRuntime(StateGraph graph, String runId, AgentContext base) {
        BranchExecutor executor = (spec, st, rt) -> {
            NodeHandler h = handlers.get(spec.kind());
            if (h == null) {
                return Mono.just(NodeOutcome.fail(st,
                    "no handler for branch kind " + spec.kind()));
            }
            return h.execute(spec, st, rt);
        };
        return new NodeRuntime(graph.name(), runId, services, checkpointStore,
            base, executor, graph);
    }

    private static void registerDefinition(StateGraph graph) {
        GraphDefinitions.register(graph);
    }

    private static String textOf(NodeOutcome outcome, NodeSpec spec) {
        Object value = outcome.state().outputs().get(spec.id());
        return value == null ? null : value.toString();
    }

    private static String newRunId() {
        return "run_" + UUID.randomUUID();
    }
}
