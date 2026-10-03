package com.gantang.reaxon.impl.agent;

import com.gantang.reaxon.api.agent.DelegationRequest;
import com.gantang.reaxon.api.auth.CallerIdentity;
import reactor.core.Disposable;
import reactor.core.Disposables;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Internal bookkeeping for one background sub-agent task (single run or critique group).
 *
 * <p>Package-private, mutable, and shared across the ledger, the two orchestrators
 * and the aggregation pipeline. Concurrency contract:
 * <ul>
 *   <li>{@link #aggregated}/{@link #cancelled}/{@link #finished} are CAS-guarded
 *       lifecycle flags;</li>
 *   <li>{@link #status}/{@link #result} and the critic/token fields are
 *       {@code volatile} — written from parallel branches, read for status display
 *       and event payloads (benign last-write-wins races, never used for control flow);</li>
 *   <li>{@link #childSessions} is a synchronized list, always copied before iterating;</li>
 *   <li>{@link #accumulate(TurnUsage)} is synchronized because parallel critique
 *       branches add token usage concurrently.</li>
 * </ul>
 *
 * <p>Extracted from {@code DefaultSubAgentService} (audit-2026-09-14 P1-1) with no
 * behavioural change; only the class gained a name and a home of its own.
 */
final class SubAgentTaskInfo {

    final String taskId;
    final String parentSessionId;
    final CallerIdentity caller;
    final String task;
    final DelegationRequest.Mode mode;
    final int n;

    final AtomicBoolean aggregated = new AtomicBoolean(false);
    /** Terminal marker for the aggregation phase (set when the wait/turn settles or is skipped). */
    volatile boolean aggregationDone = false;
    final AtomicBoolean cancelled = new AtomicBoolean(false);
    final AtomicBoolean finished = new AtomicBoolean(false);
    /** Wall-clock millis set in doFinally; drives the ledger TTL eviction. */
    volatile long finishedAtMillis;

    final List<String> childSessions = Collections.synchronizedList(new ArrayList<>());
    volatile Disposable disposable;
    /** Aggregation-phase subscriptions (idle wait + internal summary turn), disposed by cancelTask. */
    final Disposable.Composite extraDisposables = Disposables.composite();

    volatile String status = "running";
    volatile String result = "";
    volatile String criticSessionId;
    volatile boolean criticDegraded = false;
    volatile int chosenBranch = 0;
    volatile List<Map<String, Object>> branchSummaries = List.of();
    volatile Map<String, Object> criticInfo = Map.of();
    volatile int inputTokens = 0;
    volatile int cachedInputTokens = 0;
    volatile int outputTokens = 0;
    volatile int modelCalls = 0;

    SubAgentTaskInfo(String taskId, String parentSessionId, CallerIdentity caller,
                     String task, DelegationRequest.Mode mode, int n) {
        this.taskId = taskId;
        this.parentSessionId = parentSessionId;
        this.caller = caller;
        this.task = task;
        this.mode = mode;
        this.n = n;
    }

    void addChild(String sessionId) {
        childSessions.add(sessionId);
    }

    /** Track an aggregation-phase disposable; disposes it immediately if the task is already cancelled. */
    void track(Disposable d) {
        if (d != null && !extraDisposables.add(d)) {
            d.dispose();
        }
    }

    List<String> childSessionIds() {
        synchronized (childSessions) {
            return new ArrayList<>(childSessions);
        }
    }

    String firstChildSessionId() {
        synchronized (childSessions) {
            return childSessions.isEmpty() ? "" : childSessions.get(0);
        }
    }

    /** Accumulate one model-run usage; called from parallel branches, hence synchronized. */
    synchronized void accumulate(TurnUsage u) {
        if (u == null) return;
        inputTokens += u.inputTokens();
        cachedInputTokens += u.cachedInputTokens();
        outputTokens += u.outputTokens();
        modelCalls += u.modelCallsTotal();
    }
}
