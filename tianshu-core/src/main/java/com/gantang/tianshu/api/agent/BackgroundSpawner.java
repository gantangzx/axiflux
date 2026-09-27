package com.gantang.tianshu.api.agent;

/**
 * Fire-and-forget sub-agent delegation.
 *
 * <p>Unlike {@link SubAgentRunner} — whose {@code spawn()} the caller blocks on
 * until the child finishes — a {@code BackgroundSpawner} submits the child run
 * and returns a {@link SpawnHandle} immediately. The child executes in its own
 * session on a background scheduler; when it finishes, its result is delivered
 * back into the parent session asynchronously (and the parent is given a chance
 * to aggregate it). This lets the parent conversation keep accepting user input
 * while the sub-agent works.
 */
public interface BackgroundSpawner {

    /** Turn-metadata key carrying the sub-agent nesting depth of the current session (root agent = 0). */
    String META_SPAWN_DEPTH = "__tianshu_spawnDepth";

    /**
     * Prefix for child session ids ({@code sub:<userId>:<uuid>}). Sessions with
     * this prefix are transient execution containers: they are kept out of
     * persistent storage and user-facing session lists, and are destroyed when
     * the child run finishes.
     */
    String SUBAGENT_SESSION_PREFIX = "sub:";

    /**
     * Maximum sub-agent nesting depth. A depth-{@code N} session may spawn a depth-{@code N+1}
     * child while {@code N+1 <= MAX_SPAWN_DEPTH}. Prevents recursive fork-bombs
     * (child agents spawning children indefinitely).
     */
    int MAX_SPAWN_DEPTH = 2;

    /**
     * Submit a task for background execution. Must return promptly; never blocks
     * on the child's completion.
     *
     * <p>The child runs strictly with the {@link DelegationRequest#caller()}'s
     * granted scopes — implementations must not widen them.
     */
    SpawnHandle spawnAsync(DelegationRequest request);

    /**
     * Best-effort cancellation of a previously spawned background task (single run
     * or an entire best-of-n critique group). Implementations without cancellation
     * support return {@code false}. A {@code true} result means cancellation was
     * signalled; child runs may still be finishing asynchronously.
     */
    default boolean cancelTask(String taskId) {
        return false;
    }

    /** Opaque handle returned at submission time. */
    record SpawnHandle(String taskId, String childSessionId, String status) {}
}
