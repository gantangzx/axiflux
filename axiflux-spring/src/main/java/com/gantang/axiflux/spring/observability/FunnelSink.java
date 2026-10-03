package com.gantang.axiflux.spring.observability;

import java.util.Map;

/**
 * Narrow output port for conversion-funnel events.
 *
 * <p>Separating this from the general {@link
 * com.gantang.reaxon.api.observability.MetricsReporter} collection is what
 * keeps the wiring acyclic: a {@code FunnelTracker} observes tool-execution
 * metric events (it is a {@code MetricsReporter}) while emitting its own funnel
 * counters through this sink. Implemented by the metric backend
 * (e.g. Micrometer), which depends on neither, so the graph stays one-way:
 *
 * <pre>
 *   tool event ──▶ FunnelTracker ──▶ FunnelSink (metric backend)
 *             └─▶ JpaToolExecutionReporter (persistence)
 * </pre>
 */
@FunctionalInterface
public interface FunnelSink {

    /** Sink that discards every event, used when no metric backend is present. */
    FunnelSink NOOP = (name, tags) -> { };

    void incrementCounter(String name, Map<String, String> tags);
}
