package com.gantang.tianshu.impl.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.HashMap;
import java.util.Map;

/**
 * Multicast lifecycle-event bus for background sub-agent tasks.
 *
 * <p>One {@code directBestEffort} sink; every task transition (spawn_started /
 * spawn_branch_* / spawn_result / spawn_summary_* / spawn_failed / spawn_cancelled)
 * is serialised to JSON and published with the owning user id. Subscribers receive
 * a filtered stream — events carry the child agent's full text answer, so an
 * unfiltered stream would hand every subscriber the contents of every other user's
 * sub-agent runs.
 *
 * <p>Extracted from {@code DefaultSubAgentService} (audit-2026-09-14 P1-1) with no
 * behavioural change.
 */
final class SubAgentEventBus {

    private static final Logger log = LoggerFactory.getLogger(SubAgentEventBus.class);

    private final ObjectMapper om;
    private final Sinks.Many<TaskEvent> eventSink = Sinks.many().multicast().directBestEffort();

    SubAgentEventBus(ObjectMapper om) {
        this.om = om;
    }

    /**
     * Background sub-agent lifecycle events as JSON strings, restricted to tasks
     * owned by {@code userId}.
     */
    Flux<String> eventStream(String userId) {
        String owner = userId == null ? "" : userId;
        return eventSink.asFlux()
            .filter(ev -> owner.equals(ev.userId()))
            .map(TaskEvent::json);
    }

    /** Publish one lifecycle event for {@code ti}; failures are logged, never thrown. */
    void emit(SubAgentTaskInfo ti, String type, Map<String, ?> fields) {
        try {
            Map<String, Object> payload = new HashMap<>(fields);
            payload.put("type", type);
            eventSink.tryEmitNext(new TaskEvent(ti.caller.userId(), om.writeValueAsString(payload)));
        } catch (Exception e) {
            log.warn("failed to emit event {}: {}", type, e.toString());
        }
    }

    /** One lifecycle event plus the user id that owns the task it belongs to. */
    private record TaskEvent(String userId, String json) {}
}
