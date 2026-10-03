package com.gantang.reaxon.impl.workflow;

import com.gantang.reaxon.api.workflow.Checkpoint;
import com.gantang.reaxon.api.workflow.CheckpointStore;
import com.gantang.reaxon.api.workflow.GraphState;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link InMemoryCheckpointStore}: saving/loading, listing paused
 * rows, and the atomic claim latch that lets a run be claimed only once.
 */
class InMemoryCheckpointStoreTest {

    private static Checkpoint checkpoint(String runId, String user) {
        return new Checkpoint(runId, "g", "review", GraphState.ofInput(null),
            "reply", "s1", user, null, "", java.util.Map.of(),
            java.util.List.of(), null, java.time.Instant.now());
    }

    @Test
    void saveLoadAndRemove() {
        CheckpointStore store = new InMemoryCheckpointStore();
        Checkpoint cp = checkpoint("run_1", "alice");
        StepVerifier.create(store.save(cp).then(store.load("run_1")))
            .assertNext(loaded -> assertEquals("run_1", loaded.runId()))
            .verifyComplete();

        StepVerifier.create(store.remove("run_1").then(store.load("run_1")))
            .verifyComplete();
    }

    @Test
    void claimIsSingleUse() {
        CheckpointStore store = new InMemoryCheckpointStore();
        store.save(checkpoint("run_2", "alice")).block();

        StepVerifier.create(store.claim("run_2"))
            .assertNext(cp -> assertEquals("run_2", cp.runId()))
            .verifyComplete();

        // Second claim finds nothing: concurrent resumes cannot drive it twice.
        StepVerifier.create(store.claim("run_2")).verifyComplete();
    }

    @Test
    void listReturnsPausedRowsAndReleaseRestores() {
        CheckpointStore store = new InMemoryCheckpointStore();
        Checkpoint cp = checkpoint("run_3", "alice");
        store.save(cp).block();

        StepVerifier.create(store.list(CheckpointStore.Status.PAUSED))
            .assertNext(list -> assertEquals(1, list.size()))
            .verifyComplete();

        store.claim("run_3").block();
        StepVerifier.create(store.list(CheckpointStore.Status.PAUSED))
            .assertNext(List::isEmpty)
            .verifyComplete();

        store.release(cp).block();
        StepVerifier.create(store.list(CheckpointStore.Status.PAUSED))
            .assertNext(list -> assertEquals(1, list.size()))
            .verifyComplete();
    }
}
