package com.gantang.tianshu.api.workflow;

import reactor.core.publisher.Mono;

/**
 * Persistence for paused-run {@link Checkpoint}s.
 *
 * <p>The default in-core implementation keeps checkpoints in memory; a deployment can
 * supply one backed by {@code session} metadata or another store. All methods are
 * non-blocking; blocking backends must bridge onto {@code boundedElastic}.
 */
public interface CheckpointStore {

    /** Persist (or overwrite) a checkpoint. */
    Mono<Void> save(Checkpoint checkpoint);

    /** Load a checkpoint by run id; completes empty when absent. */
    Mono<Checkpoint> load(String runId);

    /** Remove a checkpoint once its run has resumed/completed. */
    Mono<Void> remove(String runId);
}
