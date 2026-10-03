package com.gantang.reaxon.api.workflow;

import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Persistence for paused-run {@link Checkpoint}s.
 *
 * <p>The default in-core implementation keeps checkpoints in memory; a deployment can
 * supply one backed by a database or another store. All methods are non-blocking;
 * blocking backends must bridge onto {@code boundedElastic}.
 *
 * <p>A checkpoint row has a lifecycle status:
 * <ul>
 *   <li>{@link Status#PAUSED} — the run is waiting for a resume payload;</li>
 *   <li>{@link Status#RESUMING} — a resume has atomically claimed it and the run is
 *       executing (a second concurrent resume must not claim it again).</li>
 * </ul>
 * A completed run is removed; a resume that fails before the next pause is released
 * back to {@code PAUSED} so it can be retried.
 */
public interface CheckpointStore {

    /** Lifecycle status of a stored checkpoint row. */
    enum Status { PAUSED, RESUMING }

    /** Persist (or overwrite) a paused checkpoint. */
    Mono<Void> save(Checkpoint checkpoint);

    /** Load a checkpoint by run id; completes empty when absent. */
    Mono<Checkpoint> load(String runId);

    /** Remove a checkpoint once its run has resumed/completed. */
    Mono<Void> remove(String runId);

    /**
     * Enumerate currently visible checkpoints. Used by the run-listing API.
     *
     * <p>The default returns an empty list; durable implementations should return at
     * least the {@link Status#PAUSED} rows.
     *
     * @param statuses statuses to include; empty means all
     */
    default Mono<List<Checkpoint>> list(Status... statuses) {
        return Mono.just(List.of());
    }

    /**
     * Atomically move a checkpoint from {@link Status#PAUSED} to
     * {@link Status#RESUMING} and return it, claiming it for this resume. Completes
     * empty when the run is absent or already claimed (so concurrent resumes cannot
     * drive the same run twice).
     *
     * <p>The default is non-atomic: it loads and removes the checkpoint. In-memory
     * callers are single-threaded per run; durable implementations must provide a
     * real compare-and-set (optimistic lock / conditional update).
     */
    default Mono<Checkpoint> claim(String runId) {
        return load(runId).flatMap(cp -> remove(runId).thenReturn(cp));
    }

    /**
     * Release a claimed run back to {@link Status#PAUSED}, persisting the supplied
     * checkpoint. Invoked when a resume fails (or pauses again) so the run remains
     * recoverable.
     */
    default Mono<Void> release(Checkpoint checkpoint) {
        return save(checkpoint);
    }
}
