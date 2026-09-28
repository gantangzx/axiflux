package com.gantang.tianshu.impl.workflow;

import com.gantang.tianshu.api.workflow.Checkpoint;
import com.gantang.tianshu.api.workflow.CheckpointStore;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
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
        return Mono.fromSupplier(() -> Optional.ofNullable(checkpoints.get(runId)))
            .flatMap(Mono::justOrEmpty);
    }

    @Override
    public Mono<Void> remove(String runId) {
        return Mono.fromRunnable(() -> checkpoints.remove(runId));
    }

    @Override
    public Mono<List<Checkpoint>> list(Status... statuses) {
        return Mono.fromCallable(() -> {
            boolean all = statuses == null || statuses.length == 0;
            // The in-memory store only ever holds PAUSED rows (a claimed row is
            // removed), so RESUMING never matches here.
            boolean includePaused = all || Arrays.asList(statuses).contains(Status.PAUSED);
            return includePaused ? List.copyOf(checkpoints.values()) : List.<Checkpoint>of();
        });
    }

    @Override
    public Mono<Checkpoint> claim(String runId) {
        return Mono.fromSupplier(() -> {
            @SuppressWarnings("unchecked")
            Checkpoint[] holder = new Checkpoint[1];
            checkpoints.computeIfPresent(runId, (id, cp) -> {
                holder[0] = cp;
                return null;
            });
            return Optional.ofNullable(holder[0]);
        }).flatMap(Mono::justOrEmpty);
    }

    @Override
    public Mono<Void> release(Checkpoint checkpoint) {
        return save(checkpoint);
    }
}
