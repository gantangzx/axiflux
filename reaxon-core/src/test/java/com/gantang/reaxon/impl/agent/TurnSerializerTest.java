package com.gantang.reaxon.impl.agent;

import com.gantang.reaxon.api.agent.AgentEvent;
import com.gantang.reaxon.api.agent.AgentResponse;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TurnSerializerTest {

    private final TurnSerializer serializer = new TurnSerializer();

    private static Flux<AgentEvent> done() {
        return Flux.just(AgentEvent.done(AgentResponse.builder()
            .content("ok").status(AgentResponse.Status.SUCCESS).build(), "{}"));
    }

    @Test
    void sameSession_turnsRunOneAtATime() throws Exception {
        AtomicInteger firstStarted = new AtomicInteger();
        AtomicInteger secondStarted = new AtomicInteger();

        Flux<AgentEvent> slow = Flux.defer(() -> {
            firstStarted.incrementAndGet();
            return Mono.delay(Duration.ofMillis(300)).then(Mono.from(done())).flux();
        });
        Flux<AgentEvent> fast = Flux.defer(() -> {
            secondStarted.incrementAndGet();
            return done();
        });

        Disposable d1 = serializer.submit("s1", slow).subscribe();
        Disposable d2 = serializer.submit("s1", fast).subscribe();

        Thread.sleep(150);
        assertEquals(1, firstStarted.get(), "first turn should be running");
        assertEquals(0, secondStarted.get(),
            "second turn for the same session must not start while the first is running");

        Thread.sleep(400);
        assertEquals(1, secondStarted.get(), "second turn runs after the first completes");
        d1.dispose();
        d2.dispose();
    }

    @Test
    void differentSessions_runConcurrently() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();

        java.util.function.Function<String, Flux<AgentEvent>> slowTurn = sid -> Flux.defer(() -> {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            return Mono.delay(Duration.ofMillis(250))
                .doOnNext(n -> active.decrementAndGet())
                .then(Mono.from(done())).flux();
        });

        Disposable d1 = serializer.submit("a", slowTurn.apply("a")).subscribe();
        Disposable d2 = serializer.submit("b", slowTurn.apply("b")).subscribe();
        Thread.sleep(150);
        assertEquals(2, maxActive.get(), "turns for different sessions must overlap");
        Thread.sleep(400);
        d1.dispose();
        d2.dispose();
    }

    @Test
    void cancelledTurn_releasesQueueSlot() throws Exception {
        AtomicBoolean thirdRan = new AtomicBoolean();
        Flux<AgentEvent> wedged = Flux.never();  // stuck until cancelled
        Flux<AgentEvent> after = Flux.defer(() -> {
            thirdRan.set(true);
            return done();
        });

        Disposable d1 = serializer.submit("s2", wedged).subscribe();
        Disposable d3 = serializer.submit("s2", after).subscribe();
        Thread.sleep(100);
        assertFalse(thirdRan.get(), "queued turn waits behind the wedged one");

        d1.dispose();  // abandon the wedged turn
        Thread.sleep(150);
        assertTrue(thirdRan.get(), "cancelling the running turn must release the queue");
        d3.dispose();
    }

    @Test
    void cancelledWhileQueued_neverRuns() throws Exception {
        AtomicBoolean secondRan = new AtomicBoolean();
        AtomicBoolean thirdRan = new AtomicBoolean();
        Flux<AgentEvent> wedged = Flux.never();  // turn A: stuck until cancelled
        Flux<AgentEvent> queued = Flux.defer(() -> {
            secondRan.set(true);
            return done();
        });
        Flux<AgentEvent> after = Flux.defer(() -> {
            thirdRan.set(true);
            return done();
        });

        Disposable d1 = serializer.submit("s4", wedged).subscribe();
        Disposable d2 = serializer.submit("s4", queued).subscribe();
        Disposable d3 = serializer.submit("s4", after).subscribe();
        Thread.sleep(100);
        assertFalse(secondRan.get(), "queued turn waits behind the wedged one");

        d2.dispose();  // abandon turn B while it is still queued
        Thread.sleep(100);
        assertFalse(secondRan.get(), "cancelled-while-queued turn must never be subscribed");

        d1.dispose();  // release the wedged turn → worker advances
        Thread.sleep(200);
        assertFalse(secondRan.get(), "cancelled turn must not run when the worker reaches it");
        assertTrue(thirdRan.get(), "the turn after the cancelled one must run normally (FIFO preserved)");
        d3.dispose();
    }

    @Test
    void events_reachOnlyTheirOwnCaller() {
        Flux<AgentEvent> t1 = Flux.just(AgentEvent.textToken("turn-1"));
        Flux<AgentEvent> t2 = Flux.just(AgentEvent.textToken("turn-2"));

        StringBuilder b1 = new StringBuilder();
        StringBuilder b2 = new StringBuilder();
        serializer.submit("s3", t1).doOnNext(e -> b1.append(e.content())).blockLast();
        serializer.submit("s3", t2).doOnNext(e -> b2.append(e.content())).blockLast();

        assertEquals("turn-1", b1.toString());
        assertEquals("turn-2", b2.toString());
    }

    // ==== P2-2: idle worker self-destructs and the queues map shrinks ====

    @Test
    void idleWorker_isReaped_andQueueEntryRemoved() throws Exception {
        TurnSerializer ts = new TurnSerializer();
        ts.setIdleTimeout(Duration.ofMillis(150));

        // Run one quick turn to completion so the queue goes idle.
        ts.submit("reap-me", Flux.just(AgentEvent.textToken("x"))).blockLast();
        assertTrue(ts.hasQueue("reap-me"), "queue entry exists while active");

        // After the idle timeout the worker self-destructs and removes its entry.
        long deadline = System.currentTimeMillis() + 5_000;
        while (ts.hasQueue("reap-me") && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertFalse(ts.hasQueue("reap-me"),
            "idle session worker must be reaped and removed from the queues map (P2-2)");

        // A fresh turn after the reap still works: computeIfAbsent starts a new worker.
        StringBuilder seen = new StringBuilder();
        ts.submit("reap-me", Flux.just(AgentEvent.textToken("back")))
            .doOnNext(e -> seen.append(e.content())).blockLast();
        assertEquals("back", seen.toString(), "session still functions after its worker was reaped");
    }
}
