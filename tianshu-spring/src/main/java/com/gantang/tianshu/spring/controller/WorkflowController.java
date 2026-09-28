package com.gantang.tianshu.spring.controller;

import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.workflow.Checkpoint;
import com.gantang.tianshu.api.workflow.GraphEvent;
import com.gantang.tianshu.api.workflow.GraphRunResult;
import com.gantang.tianshu.api.workflow.GraphRunner;
import com.gantang.tianshu.api.workflow.GraphState;
import com.gantang.tianshu.api.workflow.NodeSpec;
import com.gantang.tianshu.api.workflow.StateGraph;
import com.gantang.tianshu.spring.auth.AuthWebFilter;
import com.gantang.tianshu.spring.auth.CallerGuard;
import com.gantang.tianshu.spring.service.GraphCatalog;
import com.gantang.tianshu.spring.web.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * State-graph workflow HTTP API.
 *
 * <pre>
 * GET  /api/v1/workflows                       — list registered graphs
 * GET  /api/v1/workflows/{name}                — describe one graph
 * POST /api/v1/workflows/{name}/runs           — start a run (terminal result)
 * POST /api/v1/workflows/{name}/runs/stream    — start a run (SSE event stream)
 * GET  /api/v1/workflows/runs                   — list paused runs (own / all for wildcard)
 * GET  /api/v1/workflows/runs/{runId}          — inspect a paused run checkpoint
 * POST /api/v1/workflows/runs/{runId}/resume   — resume a paused run (terminal)
 * POST /api/v1/workflows/runs/{runId}/resume/stream — resume (SSE)
 * </pre>
 *
 * <p>Every run is executed under the authenticated caller's identity and session via
 * {@link CallerGuard}, exactly like a chat turn. The {@code input} (or
 * {@code variables}) in the request seed the graph's initial {@link GraphState}.
 */
@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowController {

    private static final Logger log = LoggerFactory.getLogger(WorkflowController.class);

    private final GraphRunner runner;
    private final GraphCatalog catalog;
    private final CallerGuard guard;
    private final com.gantang.tianshu.api.workflow.CheckpointStore checkpointStore;

    public WorkflowController(GraphRunner runner, GraphCatalog catalog, CallerGuard guard,
                              com.gantang.tianshu.api.workflow.CheckpointStore checkpointStore) {
        this.runner = runner;
        this.catalog = catalog;
        this.guard = guard;
        this.checkpointStore = checkpointStore;
    }

    // ===== Catalog =====

    @GetMapping
    public Mono<ApiResponse<List<Map<String, Object>>>> list() {
        return Mono.fromCallable(() ->
            ApiResponse.ok(catalog.all().stream().map(WorkflowController::summary).toList()));
    }

    @GetMapping("/{name}")
    public Mono<ApiResponse<Map<String, Object>>> describe(@PathVariable String name) {
        return Mono.fromCallable(() ->
            ApiResponse.ok(catalog.find(name)
                .map(WorkflowController::detail)
                .orElseThrow(() -> notFound(name))));
    }

    // ===== Run (terminal result) =====

    @PostMapping("/{name}/runs")
    public Mono<ApiResponse<GraphRunResult>> run(
            @PathVariable String name,
            @RequestBody(required = false) RunRequest request,
            @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        StateGraph graph = requireGraph(name);
        return Mono.fromCallable(() -> buildContext(graph, request, authUser, authScopes))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(ctx -> start(graph, request, ctx))
            .map(ApiResponse::ok);
    }

    // ===== Run (SSE) =====

    @PostMapping(value = "/{name}/runs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<GraphEvent>> runStream(
            @PathVariable String name,
            @RequestBody(required = false) RunRequest request,
            @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        StateGraph graph = requireGraph(name);
        return Mono.fromCallable(() -> buildContext(graph, request, authUser, authScopes))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMapMany(ctx -> startStream(graph, request, ctx))
            .map(WorkflowController::toSse);
    }

    // ===== List runs =====

    /**
     * List paused runs visible to the caller: the caller's own runs by default, or
     * every run for a wildcard caller. Only paused (awaiting resume) runs are shown;
     * completed runs are not retained.
     */
    @GetMapping("/runs")
    public Mono<ApiResponse<List<Map<String, Object>>>> listRuns(
            @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        boolean wildcard = com.gantang.tianshu.spring.auth.CallerAuthorization
            .isWildcard(authScopes);
        return checkpointStore
            .list(com.gantang.tianshu.api.workflow.CheckpointStore.Status.PAUSED)
            .map(checkpoints -> ApiResponse.ok(checkpoints.stream()
                .filter(cp -> wildcard || com.gantang.tianshu.spring.auth.CallerAuthorization
                    .canAccess(authUser, cp.userId(), authScopes))
                .map(WorkflowController::runSummary)
                .toList()));
    }

    // ===== Inspect paused run =====

    @GetMapping("/runs/{runId}")
    public Mono<ApiResponse<Checkpoint>> getRun(
            @PathVariable String runId,
            @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        return requireOwnedCheckpoint(runId, authUser, authScopes)
            .map(ApiResponse::ok)
            .switchIfEmpty(Mono.error(runNotFound(runId)));
    }

    // ===== Resume (terminal) =====

    @PostMapping("/runs/{runId}/resume")
    public Mono<ApiResponse<GraphRunResult>> resume(
            @PathVariable String runId,
            @RequestBody(required = false) ResumeRequest request,
            @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        return requireOwnedCheckpoint(runId, authUser, authScopes)
            .flatMap(cp -> runner.resume(runId, request == null ? null : request.payload()))
            .switchIfEmpty(Mono.error(runNotFound(runId)))
            .map(ApiResponse::ok);
    }

    // ===== Resume (SSE) =====

    @PostMapping(value = "/runs/{runId}/resume/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<GraphEvent>> resumeStream(
            @PathVariable String runId,
            @RequestBody(required = false) ResumeRequest request,
            @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        return requireOwnedCheckpoint(runId, authUser, authScopes)
            .flatMapMany(cp -> runner.resumeStream(runId, request == null ? null : request.payload()))
            .switchIfEmpty(Flux.error(runNotFound(runId)))
            .map(WorkflowController::toSse);
    }

    // ===== Internals =====

    private Mono<GraphRunResult> start(StateGraph graph, RunRequest request, AgentContext ctx) {
        Map<String, Object> variables = request == null ? null : request.variables();
        if (variables != null && !variables.isEmpty()) {
            GraphState initial = GraphState.ofInput(request.input()).withVariables(variables);
            return runner.startFrom(graph, ctx, initial);
        }
        return runner.start(graph, ctx, request == null ? null : request.input());
    }

    private Flux<GraphEvent> startStream(StateGraph graph, RunRequest request, AgentContext ctx) {
        Map<String, Object> variables = request == null ? null : request.variables();
        if (variables != null && !variables.isEmpty()) {
            GraphState initial = GraphState.ofInput(request.input()).withVariables(variables);
            return runner.startFromStream(graph, ctx, initial);
        }
        return runner.startStream(graph, ctx, request == null ? null : request.input());
    }

    private StateGraph requireGraph(String name) {
        return catalog.find(name).orElseThrow(() -> notFound(name));
    }

    /**
     * Build the per-run base context through {@link CallerGuard} so session ownership
     * and the caller's OBO metadata are applied. Graph runs normally need an existing
     * session (the agent nodes read/write its history); when none is supplied a
     * per-user default session is used.
     */
    private AgentContext buildContext(StateGraph graph, RunRequest request,
                                      String authUser, String authScopes) {
        String requestedSession = request == null ? null : request.sessionId();
        CallerGuard.Caller caller = guard.context(
            authUser, authScopes, request == null ? null : request.userId(), requestedSession);
        return AgentContext.builder()
            .sessionId(caller.sessionId())
            .userId(caller.userId())
            .metadata(caller.metadata())
            .currentQuery("")
            .systemPrompt("")
            .build();
    }

    /**
     * Load the checkpoint and confirm the caller owns it (or holds a wildcard), on
     * boundedElastic because the store may be blocking. Mirrors ApprovalController.
     */
    private Mono<Checkpoint> requireOwnedCheckpoint(
            String runId, String authUser, String authScopes) {
        return checkpointStore.load(runId)
            .filter(cp -> com.gantang.tianshu.spring.auth.CallerAuthorization
                .canAccess(authUser, cp.userId(), authScopes));
    }

    private static ServerSentEvent<GraphEvent> toSse(GraphEvent event) {
        String eventName = switch (event.type()) {
            case STARTED -> "started";
            case NODE_START -> "node_start";
            case NODE_END -> "node_end";
            case PAUSED -> "paused";
            case RESUMED -> "resumed";
            case APPROVAL_REQUESTED -> "approval_requested";
            case APPROVAL_RESOLVED -> "approval_resolved";
            case COMPLETED -> "completed";
            case ERROR -> "error";
        };
        return ServerSentEvent.<GraphEvent>builder()
            .event(eventName)
            .data(event)
            .build();
    }

    private static Map<String, Object> summary(StateGraph graph) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", graph.name());
        m.put("nodeCount", graph.nodes().size());
        m.put("edgeCount", graph.edges().size());
        m.put("maxSteps", graph.maxSteps());
        return m;
    }

    private static Map<String, Object> detail(StateGraph graph) {
        Map<String, Object> m = summary(graph);
        m.put("nodes", graph.nodes().values().stream()
            .map(n -> {
                Map<String, Object> nm = new LinkedHashMap<>();
                nm.put("id", n.id());
                nm.put("type", String.valueOf(n.kind()));
                nm.put("label", n.label() == null || n.label().isBlank() ? n.id() : n.label());
                return nm;
            })
            .toList());
        m.put("edges", graph.edges().stream()
            .map(e -> {
                Map<String, Object> em = new LinkedHashMap<>();
                em.put("source", e.source());
                em.put("target", e.target());
                em.put("condition", e.condition());
                em.put("conditional", e.isConditional());
                return em;
            })
            .toList());
        return m;
    }

    private static Map<String, Object> runSummary(Checkpoint cp) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", cp.runId());
        m.put("graphName", cp.graphName());
        m.put("nodeId", cp.nodeId());
        m.put("userId", cp.userId());
        m.put("sessionId", cp.sessionId());
        m.put("reason", cp.reason());
        m.put("createdAt", cp.createdAt());
        return m;
    }

    private static ResponseStatusException notFound(String name) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "workflow not found: " + name);
    }

    private static ResponseStatusException runNotFound(String runId) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "run not found: " + runId);
    }

    // ===== DTOs =====

    /**
     * @param input     single seed value placed in {@link GraphState#INPUT}
     * @param variables extra initial variables merged into the first state
     * @param sessionId session to run in; null = the caller's default session
     * @param userId    optional caller override (honoured only for wildcard callers)
     */
    public record RunRequest(
        Object input,
        Map<String, Object> variables,
        String sessionId,
        String userId
    ) {}

    /** @param payload merged into the state under the pausing node's key. */
    public record ResumeRequest(Object payload) {}
}
