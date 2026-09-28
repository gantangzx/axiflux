package com.gantang.tianshu.spring.service;

import com.gantang.tianshu.api.workflow.Checkpoint;
import com.gantang.tianshu.api.workflow.CheckpointStore;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Arrays;
import java.util.List;

/**
 * Durable, JPA-backed {@link CheckpointStore}.
 *
 * <p>Each {@link Checkpoint} (including its {@code GraphState}) is serialized to JSON
 * and stored in {@code graph_checkpoint}; the JPA calls are blocking, so every
 * operation bridges onto {@code boundedElastic}. Resuming uses the gateway's
 * compare-and-set {@code claim} (PAUSED → RESUMING) to guarantee a run is driven at
 * most once even under concurrent resumes.
 */
public class JpaCheckpointStore implements CheckpointStore {

    private final JpaCheckpointGateway gateway;

    public JpaCheckpointStore(JpaCheckpointGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public Mono<Void> save(Checkpoint checkpoint) {
        return Mono.<Void>fromRunnable(() -> gateway.upsert(checkpoint))
            .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Checkpoint> load(String runId) {
        return Mono.fromCallable(() -> gateway.find(runId).orElse(null))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(Mono::justOrEmpty);
    }

    @Override
    public Mono<Void> remove(String runId) {
        return Mono.<Void>fromRunnable(() -> gateway.delete(runId))
            .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<List<Checkpoint>> list(Status... statuses) {
        return Mono.fromCallable(() -> gateway
                .list(statuses == null ? List.of() : Arrays.asList(statuses)))
            .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Checkpoint> claim(String runId) {
        return Mono.fromCallable(() -> gateway.claim(runId).orElse(null))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(Mono::justOrEmpty);
    }

    @Override
    public Mono<Void> release(Checkpoint checkpoint) {
        return Mono.<Void>fromRunnable(() -> gateway.release(checkpoint))
            .subscribeOn(Schedulers.boundedElastic());
    }
}
