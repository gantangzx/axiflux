package com.gantang.tianshu.api.agent;

import reactor.core.publisher.Flux;

/**
 * Per-user sub-agent lifecycle event stream and owner-scoped cancellation.
 *
 * <p>The two operations complement {@link SubAgentRunner} and
 * {@link BackgroundSpawner} for the controllers and bridges that observe or
 * stop background tasks. Keeping them on a separate interface lets the Spring
 * layer depend only on the contract, not on the concrete
 * {@code DefaultSubAgentService}.
 *
 * <p>Stream semantics: each subscriber sees the lifecycle events for tasks
 * owned by the supplied {@code userId} only — an unfiltered stream would leak
 * every other user's child-agent answers.
 */
public interface SubAgentEventStreamer {

    /**
     * Background sub-agent lifecycle events as JSON strings, restricted to tasks
     * owned by {@code userId}.
     *
     * <p>Events include {@code spawn_started}, {@code spawn_result},
     * {@code spawn_summary_start}/{@code spawn_summary}/{@code spawn_summary_done},
     * {@code spawn_failed}, {@code spawn_cancelled}.
     */
    Flux<String> eventStream(String userId);

    /**
     * Owner-scoped cancellation. Returns {@code false} (so the caller answers
     * 404 rather than leaking cross-tenant existence) when the task is unknown,
     * already finished, or owned by a different user. Implementations without
     * per-user scoping return {@code false} for any input.
     */
    default boolean cancelTaskForUser(String taskId, String userId) {
        return false;
    }
}