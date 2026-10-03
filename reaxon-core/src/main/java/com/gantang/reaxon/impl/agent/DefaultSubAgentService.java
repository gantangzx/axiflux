package com.gantang.reaxon.impl.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.reaxon.api.agent.Agent;
import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.agent.AgentResponse;
import com.gantang.reaxon.api.agent.BackgroundSpawner;
import com.gantang.reaxon.api.agent.DelegationRequest;
import com.gantang.reaxon.api.agent.SubAgentEventStreamer;
import com.gantang.reaxon.api.agent.SubAgentRunner;
import com.gantang.reaxon.api.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sub-agent orchestration.
 *
 * <p>Two entry modes:
 * <ul>
 *   <li><b>Blocking</b> ({@link SubAgentRunner#spawn}) — runs a child session and
 *       returns its final answer; used by the REST {@code /subagents/spawn}
 *       endpoint and kept for synchronous callers.</li>
 *   <li><b>Background</b> ({@link #spawnAsync}) — submits child work and returns
 *       a handle immediately. Two orchestration shapes:
 *       <ul>
 *         <li>{@link DelegationRequest.Mode#SINGLE}: one child run; when it
 *             finishes its result is injected into the parent session and an
 *             internal aggregation turn produces a consolidated summary.</li>
 *         <li>{@link DelegationRequest.Mode#CRITIQUE}: N independent answerer
 *             sessions run the same task in parallel (different approaches via
 *             independent sampling), then one critic session scores them and
 *             returns the best answer or a synthesis (best-of-n, following
 *             "More Agents Is All You Need"). Branch failures are tolerated;
 *             the critic failing falls back to the longest branch answer.</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <p>Per-phase collaborators (extracted in audit-2026-09-14 P1-1):
 * {@link SubAgentTaskLedger} tracks task lifecycle;
 * {@link SubAgentEventBus} filters + serialises per-user event streams;
 * {@link SubAgentChildPipeline} owns the child session lifecycle;
 * {@link SingleSpawnOrchestrator} drives a single background run;
 * {@link CritiqueOrchestrator} drives a best-of-n group;
 * {@link AggregationRunner} waits for the parent to idle then runs the
 * consolidated-summary turn. This class stays a thin orchestrator that
 * allocates capacity, registers the task, wires the pipeline and exposes
 * cancellation.
 */
public class DefaultSubAgentService implements SubAgentRunner, BackgroundSpawner, SubAgentEventStreamer {

    private static final Logger log = LoggerFactory.getLogger(DefaultSubAgentService.class);

    /** Hard cap on concurrently running child sessions (fork-bomb guard); mirrors the ledger. */
    static final int MAX_CONCURRENT_BACKGROUND_SPAWNS = SubAgentTaskLedger.MAX_CONCURRENT_BACKGROUND_SPAWNS;

    /**
     * Sentinel for "no usable text answer". Matches the literal ReactiveAgent
     * historically emitted; kept here for the blocking {@link #spawn} path's answer
     * normalisation (the async path uses SubAgentChildPipeline's copy).
     */
    private static final String NO_RESULT_SENTINEL = "(no response)";

    private final Agent agent;
    private final SessionManager sessionManager;
    private final ObjectMapper om;

    /**
     * Dedicated scheduler for async background pipelines. The global
     * {@code boundedElastic} is shared process-wide (turn serialization,
     * compaction, recovery, and many tests that call {@code Thread.sleep} or
     * {@code .block()}); under heavy test load its threads can be starved long
     * enough for event-driven sub-agent tests to time out. A private scheduler
     * isolates background spawns from that contention.
     */
    private final reactor.core.scheduler.Scheduler asyncScheduler;

    // ── Collaborators extracted in audit-2026-09-14 P1-1 ─────────────────────
    private final SubAgentTaskLedger ledger = new SubAgentTaskLedger();
    private final SubAgentEventBus eventBus;
    private final SubAgentChildPipeline pipe;
    private final SingleSpawnOrchestrator singleOrchestrator;
    private final CritiqueOrchestrator critiqueOrchestrator;
    private final AggregationRunner aggregationRunner;

    public DefaultSubAgentService(Agent agent, SessionManager sessionManager, ObjectMapper om) {
        this(agent, sessionManager, om,
             reactor.core.scheduler.Schedulers.newBoundedElastic(
                 Schedulers.DEFAULT_BOUNDED_ELASTIC_SIZE,
                 Schedulers.DEFAULT_BOUNDED_ELASTIC_QUEUESIZE,
                 "subagent-spawn-", 60, true));
    }

    DefaultSubAgentService(Agent agent, SessionManager sessionManager, ObjectMapper om,
                           reactor.core.scheduler.Scheduler asyncScheduler) {
        this.agent = agent;
        this.sessionManager = sessionManager;
        this.om = om;
        this.asyncScheduler = asyncScheduler;
        this.eventBus = new SubAgentEventBus(om);
        this.pipe = new SubAgentChildPipeline(agent, sessionManager, om, eventBus);
        this.aggregationRunner = new AggregationRunner(agent, sessionManager, eventBus);
        this.singleOrchestrator = new SingleSpawnOrchestrator(pipe, eventBus, aggregationRunner::schedule);
        this.critiqueOrchestrator = new CritiqueOrchestrator(pipe, eventBus, aggregationRunner::schedule);
    }

    /**
     * Background sub-agent lifecycle events as JSON strings, restricted to tasks
     * owned by {@code userId}.
     *
     * <p>Deliberately the only accessor: events carry the child agent's full text
     * answer, so an unfiltered stream would hand every subscriber the contents of
     * every other user's sub-agent runs.
     */
    public Flux<String> eventStream(String userId) {
        return eventBus.eventStream(userId);
    }

    /**
     * Cancel a background task (single run or critique group). All in-flight child
     * sessions are interrupted and their pipelines disposed; transient sessions
     * are cleaned up in the group's doFinally. Best-effort, idempotent.
     *
     * @return true if a live task was found and a cancellation was signalled
     */
    public boolean cancelTask(String taskId) {
        SubAgentTaskInfo ti = ledger.get(taskId);
        if (ti == null) {
            return false;
        }
        // The main pipeline finishing is not the end of the task: the
        // aggregation phase (idle wait + internal summary turn) is still live
        // work that cancelTask must be able to stop (P1-4).
        boolean aggregationActive = ti.aggregated.get() && !ti.aggregationDone;
        if (ti.finished.get() && !aggregationActive) {
            return false;
        }
        if (!ti.cancelled.compareAndSet(false, true)) {
            return true; // already cancelling
        }
        ti.status = "cancelled";
        log.info("cancelling sub-agent task={} mode={} childSessions={}",
            taskId, ti.mode, ti.childSessionIds());
        try {
            Disposable d = ti.disposable;
            if (d != null && !d.isDisposed()) d.dispose();
            // Aggregation-phase subscriptions (idle wait + internal summary turn)
            // live outside the main pipeline; dispose them so a cancel also stops
            // the interval polling and any in-flight aggregation turn immediately.
            ti.extraDisposables.dispose();
        } catch (Exception e) {
            log.warn("dispose failed for task {}: {}", taskId, e.toString());
        }
        for (String sid : ti.childSessionIds()) {
            try { agent.interrupt(sid); } catch (Exception ignored) { }
        }
        eventBus.emit(ti, "spawn_cancelled", Map.of(
            "taskId", taskId,
            "parentSessionId", ti.parentSessionId,
            "mode", ti.mode.name().toLowerCase()));
        return true;
    }

    /**
     * Owner-scoped cancellation: returns false (so the caller gets 404 rather
     * than a cross-tenant existence signal) when the task is unknown, already
     * finished, or owned by another user.
     */
    public boolean cancelTaskForUser(String taskId, String userId) {
        SubAgentTaskInfo ti = ledger.get(taskId);
        // P2-14: caller.userId() can be null (e.g. unauthenticated/internal
        // callers); guard before equals() to avoid an NPE.
        if (ti == null || ti.caller == null || ti.caller.userId() == null
            || !ti.caller.userId().equals(userId)) {
            return false;
        }
        return cancelTask(taskId);
    }

    /**
     * Destroy a transient sub-agent session after its run. Routing (prefix
     * {@code sub:}) takes this to the in-memory session store, so this only
     * evicts memory — no JPA/Redis rows are touched.
     */
    private void cleanupChildSession(String childSessionId) {
        pipe.cleanupChildSession(childSessionId);
    }

    // ==================== Blocking mode (REST) ====================

    @Override
    public Mono<SpawnResult> spawn(DelegationRequest request) {
        String childUserId = request.userId();
        String childSessionId = BackgroundSpawner.SUBAGENT_SESSION_PREFIX + childUserId + ":" + UUID.randomUUID();
        String parent = request.parentSessionId();

        // P2-13: apply the same fork-bomb capacity gate as spawnAsync so a burst
        // of blocking spawns cannot create unbounded child sessions, and run the
        // whole pipeline on boundedElastic (getOrCreate is blocking JDBC under
        // JPA stores; the caller thread may be a Netty event loop).
        if (!ledger.tryReserve(1)) {
            return Mono.just(new SpawnResult(childSessionId,
                "Too many concurrent background sub-agents ("
                    + MAX_CONCURRENT_BACKGROUND_SPAWNS + " child-session slots in use); "
                    + "wait for running tasks to finish."));
        }

        return Mono.fromRunnable(() ->
                sessionManager.getOrCreate(childSessionId, childUserId, agent.getAgentId(),
                    Map.of("kind", "subagent", "parentSessionId", parent,
                        BackgroundSpawner.META_SPAWN_DEPTH, request.childDepth())))
            .then(Mono.defer(() -> {
                AgentContext ctx = AgentContext.builder()
                    .sessionId(childSessionId)
                    .userId(childUserId)
                    .currentQuery(request.task())
                    .metadata(request.childMetadata())
                    .build();
                log.info("spawn sub-agent session={} parent={} depth={} taskLen={}",
                    childSessionId, parent, request.childDepth(), request.task().length());
                return agent.process(ctx);
            }))
            .map(resp -> toResult(childSessionId, resp))
            .onErrorResume(e -> {
                log.warn("sub-agent {} failed: {}", childSessionId, e.toString());
                return Mono.just(new SpawnResult(childSessionId,
                    "Sub-agent error: " + e.getMessage()));
            })
            // P2-13: keep blocking session/JPA work off the caller's event loop.
            .subscribeOn(Schedulers.boundedElastic())
            // The child session is a transient execution container: destroy it
            // once the run settles (success or failure) so no sub-agent history
            // is persisted or visible in session lists. Also release the capacity
            // slot reserved above (P2-13).
            .doFinally(s -> {
                ledger.release(1);
                cleanupChildSession(childSessionId);
            });
    }

    // ==================== Background mode (async) ====================

    @Override
    public SpawnHandle spawnAsync(DelegationRequest request) {
        String childUserId = request.userId();
        String taskId = "task-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String parent = request.parentSessionId();
        int childDepth = request.childDepth();
        String task = request.task();

        // Capacity gate, counting EVERY child session the group will create
        // (a critique group reserves its N answerers + 1 critic up front).
        int reserve = request.mode() == DelegationRequest.Mode.CRITIQUE ? request.n() + 1 : 1;
        if (!ledger.tryReserve(reserve)) {
            throw new IllegalStateException("Too many concurrent background sub-agents ("
                + MAX_CONCURRENT_BACKGROUND_SPAWNS + " child-session slots in use); a best-of-"
                + request.n() + " critique needs " + reserve + " slots. Wait for running tasks "
                + "to finish or reduce n.");
        }

        SubAgentTaskInfo ti = new SubAgentTaskInfo(taskId, parent, request.caller(), task, request.mode(), request.n());
        ledger.put(ti);

        // Create child sessions synchronously BEFORE subscribe so the returned
        // handle and start event can name real session ids (no async race).
        List<String> branchSessions = new ArrayList<>();
        int precreate = request.mode() == DelegationRequest.Mode.CRITIQUE ? request.n() : 1;
        for (int i = 0; i < precreate; i++) {
            String sid = SubAgentChildPipeline.newChildSessionId(childUserId);
            branchSessions.add(sid);
            ti.addChild(sid);
        }

        log.info("async spawn task={} parent={} depth={} mode={} n={} taskLen={}",
            taskId, parent, childDepth, request.mode(), request.n(), task.length());

        Map<String, Object> startFields = new HashMap<>();
        startFields.put("taskId", taskId);
        startFields.put("parentSessionId", parent);
        startFields.put("mode", request.mode().name().toLowerCase());
        if (request.mode() == DelegationRequest.Mode.CRITIQUE) {
            startFields.put("n", request.n());
            startFields.put("branches", branchSessions);
        } else {
            startFields.put("childSessionId", branchSessions.get(0));
        }
        Mono<?> pipeline = request.mode() == DelegationRequest.Mode.CRITIQUE
            ? critiqueOrchestrator.runGroup(ti, childUserId, childDepth, branchSessions)
            : singleOrchestrator.run(ti, childUserId, childDepth, branchSessions.get(0));

        // Subscribe and backfill the disposable BEFORE broadcasting spawn_started:
        // the event exposes taskId, so a subscriber cancelling immediately must
        // always observe a non-null disposable (P1-3 cancel race).
        Disposable d = pipeline
            .doFinally(s -> {
                ledger.release(reserve);
                ti.finished.set(true);
                ti.finishedAtMillis = System.currentTimeMillis();
                for (String sid : ti.childSessionIds()) {
                    cleanupChildSession(sid);
                }
                ledger.purgeFinishedTasks();
            })
            .subscribeOn(asyncScheduler)
            .subscribe();
        ti.disposable = d;
        // Belt-and-braces: a cancel squeezed in before the volatile write still
        // disposes the freshly created subscription instead of being lost.
        if (ti.cancelled.get() && !d.isDisposed()) {
            d.dispose();
        }

        eventBus.emit(ti, "spawn_started", startFields);

        return new SpawnHandle(taskId, ti.firstChildSessionId(), "running");
    }


    /** Test/ops visibility: whether a task record is currently retained (delegates to the ledger). */
    boolean hasTask(String taskId) {
        return ledger.hasTask(taskId);
    }

    /** Test seam: evict finished records older than a caller-supplied retention (delegates to the ledger). */
    void purgeFinishedTasksOlderThan(Duration retention) {
        ledger.purgeFinishedTasksOlderThan(retention);
    }


    private SpawnResult toResult(String sessionId, AgentResponse resp) {
        String answer = resp != null && resp.content() != null ? resp.content() : "";
        boolean maxIter = resp != null && resp.status() == AgentResponse.Status.MAX_ITERATIONS;
        if (answer.isBlank() || NO_RESULT_SENTINEL.equals(answer.trim())) {
            answer = "子代理在最大迭代轮次内未能产出文本结论（所有轮次可能都消耗在工具调用上，或工具结果未被模型接收）。";
        } else if (maxIter) {
            answer = answer + "\n[子代理在达到最大迭代次数时停止]";
        }
        return new SpawnResult(sessionId, answer);
    }

}