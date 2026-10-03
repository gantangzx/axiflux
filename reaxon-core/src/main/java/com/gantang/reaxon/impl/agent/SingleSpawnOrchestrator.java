package com.gantang.reaxon.impl.agent;

import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.agent.AgentEvent;
import com.gantang.reaxon.api.agent.AgentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Orchestrates a single background sub-agent run (DelegationRequest.Mode#SINGLE):
 * one child session executes the task; when it finishes its result is handed to the
 * aggregation phase via {@code onComplete}.
 *
 * <p>Stateless apart from the shared {@link SubAgentChildPipeline}; all per-run
 * bookkeeping lives in the caller-supplied {@link SubAgentTaskInfo}.
 *
 * <p>Extracted from {@code DefaultSubAgentService} (audit-2026-09-14 P1-1) with no
 * behavioural change.
 */
final class SingleSpawnOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SingleSpawnOrchestrator.class);

    private final SubAgentChildPipeline pipe;
    private final SubAgentEventBus events;
    /** Invoked when a run produces a usable result (drives the aggregation phase). */
    private final Consumer<SubAgentTaskInfo> onComplete;

    SingleSpawnOrchestrator(SubAgentChildPipeline pipe, SubAgentEventBus events,
                            Consumer<SubAgentTaskInfo> onComplete) {
        this.pipe = pipe;
        this.events = events;
        this.onComplete = onComplete;
    }

    Mono<Void> run(SubAgentTaskInfo ti, String childUserId, int childDepth, String childSessionId) {
        return pipe.prepareChild(childSessionId, childUserId, childDepth, ti.parentSessionId, ti.taskId)
            .thenMany(Flux.defer(() -> pipe.agent.processStream(AgentContext.builder()
                .sessionId(childSessionId).userId(childUserId).currentQuery(ti.task)
                .metadata(SubAgentChildPipeline.childMeta(ti.caller, childDepth))
                .build())))
            .doOnNext(ev -> pipe.forwardChildEvent(ti, ev, 0))
            .filter(ev -> ev.type() == AgentEvent.Type.DONE)
            .next()
            .doOnNext(ev -> onDone(ti, pipe.parseDone(ev)))
            .doOnError(e -> {
                if (ti.cancelled.get()) return;
                ti.status = "failed";
                log.warn("async sub-agent {} failed: {}", childSessionId, e.toString());
                events.emit(ti, "spawn_failed", Map.of(
                    "taskId", ti.taskId, "parentSessionId", ti.parentSessionId,
                    "error", e.getMessage() != null ? e.getMessage() : "sub-agent error"));
            })
            // P2-12: doOnError only observes; the error would otherwise reach the
            // no-error-handler subscribe() at the pipeline root and log Reactor's
            // errorCallbackNotImplemented noise. Swallow it explicitly here — the
            // failure is already recorded + emitted above.
            .onErrorResume(e -> Mono.empty())
            .then();
    }

    private void onDone(SubAgentTaskInfo ti, AgentResponse resp) {
        String fail = SubAgentChildPipeline.terminalFailure(resp);
        if (fail != null) {
            // A DONE event may still carry terminal ERROR/INTERRUPTED status (or an
            // empty answer): treat the run as failed rather than aggregating junk.
            ti.status = "failed";
            ti.accumulate(TurnUsage.fromMetadata(resp != null ? resp.metadata() : null));
            log.warn("async sub-agent {} finished without a usable answer: {}", ti.taskId, fail);
            events.emit(ti, "spawn_failed", Map.of(
                "taskId", ti.taskId, "parentSessionId", ti.parentSessionId,
                "mode", ti.mode.name().toLowerCase(), "error", fail));
            return;
        }
        String answer = SubAgentChildPipeline.presentableAnswer(resp);
        ti.status = "done";
        ti.result = answer;
        ti.accumulate(TurnUsage.fromMetadata(resp != null ? resp.metadata() : null));
        log.info("async sub-agent done task={} parent={} answerLen={} tokens in={} out={}",
            ti.taskId, ti.parentSessionId, answer.length(), ti.inputTokens, ti.outputTokens);

        // The result is injected into the parent transcript only after the idle
        // gate in the aggregation phase — see DefaultSubAgentService for the ordering rationale.
        events.emit(ti, "spawn_result", ResultFields.of(ti, answer));
        onComplete.accept(ti);
    }
}
