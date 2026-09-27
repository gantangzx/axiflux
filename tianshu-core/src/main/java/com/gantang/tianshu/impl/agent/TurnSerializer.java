package com.gantang.tianshu.impl.agent;

import com.gantang.tianshu.api.agent.AgentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-session turn serialization.
 *
 * <p>Turns for the <b>same</b> session execute one at a time, FIFO; turns for
 * different sessions run concurrently. This mirrors the upstream agent-loop
 * guarantee: a session has at most one run in flight, so history writes and
 * model views never interleave.
 *
 * <p>Mechanics: each session owns a unicast queue of pending turns. A single
 * worker drains it via {@code concatMap}, so a turn's work {@code Flux} is not
 * subscribed until the previous turn for that session terminated. Each turn has
 * its own output sink that the caller subscribes to; cancelling that
 * subscription disposes the in-flight work and releases the queue slot, so an
 * abandoned turn never blocks the session forever.
 */
public final class TurnSerializer {

    private static final Logger log = LoggerFactory.getLogger(TurnSerializer.class);

    private record Turn(
            Flux<AgentEvent> work,
            Sinks.Many<AgentEvent> out,
            Sinks.Empty<Void> gate,
            AtomicReference<Disposable> workSubscription,
            AtomicBoolean cancelled) {
    }

    private record SessionQueue(Sinks.Many<Turn> turns, AtomicInteger inFlight) {
    }

    private final ConcurrentHashMap<String, SessionQueue> queues = new ConcurrentHashMap<>();

    /**
     * Idle timeout for a session's worker. After this long with no queued or
     * running turn, the worker self-destructs and its entry is removed from
     * {@link #queues}, so the map does not grow monotonically for the process
     * lifetime (P2-2). Turn subscriptions themselves are unaffected: they are
     * driven by their own caller's subscription, not this timer.
     */
    private volatile Duration idleTimeout = Duration.ofMinutes(10);

    /** Override the idle timeout (mainly for tests). Values <= 0 are ignored. */
    public void setIdleTimeout(Duration timeout) {
        if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
            this.idleTimeout = timeout;
        }
    }

    /**
     * Queue {@code work} as the next turn for {@code sessionId} and return the
     * stream of that turn's events. The work is subscribed only after every
     * earlier turn for the session has terminated.
     */
    public Flux<AgentEvent> submit(String sessionId, Flux<AgentEvent> work) {
        SessionQueue queue = queues.computeIfAbsent(sessionId, this::startWorker);

        Sinks.Many<AgentEvent> out = Sinks.many().unicast().onBackpressureBuffer();
        Sinks.Empty<Void> gate = Sinks.empty();
        Turn turn = new Turn(work, out, gate, new AtomicReference<>(), new AtomicBoolean(false));

        queue.inFlight().incrementAndGet();
        Sinks.EmitResult r = queue.turns().tryEmitNext(turn);
        if (r.isFailure()) {
            queue.inFlight().decrementAndGet();
            return Flux.error(new IllegalStateException(
                "Failed to enqueue turn for session " + sessionId + ": " + r));
        }

        return out.asFlux()
            .doOnCancel(() -> {
                // Mark the turn cancelled first: if it is still queued, the worker
                // skips subscribing its work entirely (no runaway tool side effects).
                turn.cancelled().set(true);
                Disposable d = turn.workSubscription().get();
                if (d != null && !d.isDisposed()) {
                    d.dispose();
                }
                // Release the queue slot even when the caller abandoned the turn.
                gate.tryEmitEmpty();
            });
    }

    /**
     * Number of turns for {@code sessionId} that have been submitted but not yet
     * fully terminated (running + queued). ReactiveAgent reads this at turn
     * teardown to decide whether an INTERRUPTED flag must survive for the next
     * queued turn (user pressed stop with work still queued) or be cleared
     * (queue drained; a leftover flag would poison the next fresh message).
     */
    public int pendingTurns(String sessionId) {
        SessionQueue q = queues.get(sessionId);
        return q == null ? 0 : q.inFlight().get();
    }

    /** Test/ops visibility: whether a session currently has a live worker entry. */
    boolean hasQueue(String sessionId) {
        return queues.containsKey(sessionId);
    }

    private SessionQueue startWorker(String sessionId) {
        Sinks.Many<Turn> turns = Sinks.many().unicast().onBackpressureBuffer();
        SessionQueue queue = new SessionQueue(turns, new AtomicInteger(0));
        Disposable worker = turns.asFlux()
            // Turn bodies (session intake = blocking JDBC under JPA stores, plus
            // the agent loop) must never subscribe on a Netty event loop.
            .publishOn(Schedulers.boundedElastic())
            .concatMap(turn -> {
                // A turn cancelled while queued must never run: its work Flux may
                // carry real side effects (emails, file writes, spawned agents).
                // Just release the slot and move on.
                if (turn.cancelled().get()) {
                    turn.gate().tryEmitEmpty();
                    return turn.gate().asMono().doFinally(sig -> queue.inFlight().decrementAndGet());
                }
                Disposable sub = turn.work().subscribe(
                    event -> turn.out().tryEmitNext(event),
                    err -> {
                        turn.out().tryEmitError(err);
                        turn.gate().tryEmitEmpty();
                    },
                    () -> {
                        turn.out().tryEmitComplete();
                        turn.gate().tryEmitEmpty();
                    });
                turn.workSubscription().set(sub);
                // A cancel that landed between subscribe() and set() above must still
                // dispose the work; otherwise the cancelled turn keeps running and
                // interleaves writes with the next turn the gate just released.
                if (turn.cancelled().get()) {
                    sub.dispose();
                }
                // The gate completes when the turn's work terminates OR the
                // caller cancelled; concatMap then subscribes the next turn.
                return turn.gate().asMono().doFinally(sig -> queue.inFlight().decrementAndGet());
            })
            .subscribe(
                null,
                err -> log.error("[turn-serializer:{}] worker died; session turns will fail", sessionId, err));

        // Idle self-destruction: when the session has been quiet for idleTimeout,
        // tear the worker down and drop the queues entry so the map does not grow
        // unboundedly with session count (P2-2). Only remove when truly idle
        // (inFlight == 0) to avoid racing a turn that just arrived.
        scheduleIdleReaper(sessionId, queue, worker);
        return queue;
    }

    /**
     * Schedule a one-shot idle check. If the queue is still empty when it fires,
     * complete the turn sink (ending the worker's concatMap), dispose the worker
     * subscription, and remove the map entry. Any turn submitted after removal
     * simply triggers {@link #startWorker} afresh via computeIfAbsent.
     */
    private void scheduleIdleReaper(String sessionId, SessionQueue queue, Disposable worker) {
        Mono.delay(idleTimeout, Schedulers.boundedElastic())
            .subscribe(tick -> {
                if (queue.inFlight().get() == 0 && queues.remove(sessionId, queue)) {
                    queue.turns().tryEmitComplete();
                    worker.dispose();
                    log.debug("[turn-serializer:{}] idle worker reaped", sessionId);
                } else if (queues.get(sessionId) == queue) {
                    // Still active (or was re-created); only reschedule if this entry
                    // is still the live one — avoids leaking a chain of reapers.
                    scheduleIdleReaper(sessionId, queue, worker);
                }
            });
    }
}
