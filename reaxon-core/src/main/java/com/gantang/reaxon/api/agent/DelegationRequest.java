package com.gantang.reaxon.api.agent;

import com.gantang.reaxon.api.auth.CallerIdentity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One sub-agent delegation, carrying everything a child run needs to execute
 * <em>on behalf of the original caller</em> and nothing more.
 *
 * <p>The security-relevant part is {@link #caller()}. A child agent must inherit
 * the parent's {@link CallerIdentity} verbatim: inheriting <em>less</em> silently
 * breaks delegation under {@code axiflux.auth.require-explicit-scopes=true}
 * (every scope-gated tool in the child is denied), while inheriting <em>more</em>
 * — including the "no scopes injected" case, which lenient mode reads as full
 * access — is a privilege escalation: a prompt-injected parent could spawn a
 * child that reaches tools the human caller was never granted.
 *
 * <p>Use {@link #childMetadata()} to build the child {@code AgentContext}
 * metadata so identity propagation is expressed once rather than duplicated at
 * every spawn site.
 *
 * @param task            self-contained instruction for the child agent
 * @param caller          identity + granted scopes the child inherits
 * @param parentSessionId session that requested the delegation
 * @param childDepth      nesting depth of the child, bounded by
 *                        {@link BackgroundSpawner#MAX_SPAWN_DEPTH}
 * @param mode            orchestration mode: a single background run or a
 *                        best-of-n parallel_critique group
 * @param n               number of parallel answerer branches in
 *                        {@link Mode#CRITIQUE} mode (ignored otherwise)
 */
public record DelegationRequest(
        String task,
        CallerIdentity caller,
        String parentSessionId,
        int childDepth,
        Mode mode,
        int n) {

    /** Orchestration modes supported by the background spawner. */
    public enum Mode {
        /** One background child run. */
        SINGLE,
        /** {@code n} parallel answerers followed by one critic that scores and selects. */
        CRITIQUE
    }

    /** Default parallelism for best-of-n critique (paper baseline: 3 answerers). */
    public static final int DEFAULT_CRITIQUE_N = 3;
    /** Hard bounds on critique fan-out (fork-bomb / cost guard). */
    public static final int MIN_CRITIQUE_N = 2;
    public static final int MAX_CRITIQUE_N = 5;

    public DelegationRequest {
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("task is required");
        }
        if (caller == null) {
            throw new IllegalArgumentException("caller identity is required for delegation");
        }
        if (childDepth < 1) {
            throw new IllegalArgumentException("childDepth must be >= 1, got " + childDepth);
        }
        if (mode == null) {
            mode = Mode.SINGLE;
        }
        if (mode == Mode.CRITIQUE) {
            if (n < MIN_CRITIQUE_N || n > MAX_CRITIQUE_N) {
                throw new IllegalArgumentException(
                    "critique n must be in [" + MIN_CRITIQUE_N + "," + MAX_CRITIQUE_N + "], got " + n);
            }
        } else {
            n = 1;
        }
        task = task.trim();
        parentSessionId = (parentSessionId == null || parentSessionId.isBlank()) ? "unknown" : parentSessionId;
    }

    /** Single-run delegation (backwards-compatible canonical shape). */
    public DelegationRequest(String task, CallerIdentity caller, String parentSessionId, int childDepth) {
        this(task, caller, parentSessionId, childDepth, Mode.SINGLE, 1);
    }

    /** A top-level delegation (depth 1) requested directly by a caller. */
    public static DelegationRequest topLevel(String task, CallerIdentity caller, String parentSessionId) {
        return new DelegationRequest(task, caller, parentSessionId, 1);
    }

    /** A top-level best-of-n critique delegation. */
    public static DelegationRequest critique(String task, CallerIdentity caller,
                                             String parentSessionId, int n) {
        return new DelegationRequest(task, caller, parentSessionId, 1, Mode.CRITIQUE,
            n <= 0 ? DEFAULT_CRITIQUE_N : n);
    }

    /** Effective user id of the child run. */
    public String userId() {
        return caller.userId();
    }

    /**
     * Metadata for the child {@code AgentContext}: inherited scopes plus the
     * nesting depth that {@code SpawnTaskTool} reads back to enforce
     * {@link BackgroundSpawner#MAX_SPAWN_DEPTH}.
     *
     * <p>Scopes are propagated faithfully, empty set included, so
     * {@code ScopePolicy}'s strict/lenient flag — not this method — decides what
     * an absent grant means.
     */
    public Map<String, Object> childMetadata() {
        Map<String, Object> meta = new LinkedHashMap<>(4);
        meta.put(BackgroundSpawner.META_SPAWN_DEPTH, childDepth);
        meta.put(CallerIdentity.META_SCOPES, caller.scopes());
        return Map.copyOf(meta);
    }
}
