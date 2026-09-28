package com.gantang.tianshu.impl.workflow;

import com.gantang.tianshu.api.workflow.Checkpoint;
import com.gantang.tianshu.api.workflow.CheckpointStore;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link CheckpointStore}: keeps paused run checkpoints in an in-process map.
 *
 * <p>Sufficient for single-node and the open-source zero-dependency experience; a
 * clustered deployment supplies a durable implementation.
 */
public final class InMemoryCheckpointStore implements CheckpointStore {

    private final ConcurrentHashMap<String, Checkpoint> checkpoints = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> save(Checkpoint checkpoint) {
        return Mono.fromRunnable(() -> checkpoints.put(checkpoint.runId(), checkpoint));
    }

    @Override
    public Mono<Checkpoint> load(String runId) {
        return Mono.fromSupplier(() -> checkpoints.get(runId))
            .flatMap(c -> Mono.justOrEmpty(Optional.ofNullable(c)));
    }

    @Override
    public Mono<Void> remove(String runId) {
        return Mono.fromRunnable(() -> checkpoints.remove(runId));
    }
}
