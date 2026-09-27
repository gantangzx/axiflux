package com.gantang.tianshu.impl.tool.policy;

import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.tool.Tool;
import com.gantang.tianshu.api.tool.policy.PolicyDecision;
import com.gantang.tianshu.api.tool.policy.ToolPolicy;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Rate / loop guard for tool calls, keyed per session.
 *
 * <p>Two protections:
 * <ul>
 *   <li><b>Identical-repeat detection</b> — if the same tool is invoked with the
 *       same arguments more than {@code repeatThreshold} times within
 *       {@code repeatWindowMs} in one session, the call is denied. This catches the
 *       classic agent failure mode where the model loops calling the same tool with
 *       the same arguments after an error, burning budget without making progress.</li>
 *   <li><b>Burst cap</b> — if more than {@code burstLimit} tool calls happen in one
 *       session within {@code burstWindowMs}, subsequent calls are denied to
 *       prevent resource exhaustion.</li>
 * </ul>
 *
 * State is in-memory and bounded by pruning old entries on every evaluation; empty
 * session deques are evicted so the map does not grow without bound.
 */
public final class ToolCallThrottlePolicy implements ToolPolicy {

    private record Call(long ts, String tool, String argsHash) {}

    private final long burstWindowMs;
    private final int burstLimit;
    private final long repeatWindowMs;
    private final int repeatThreshold;

    private final Map<String, Deque<Call>> sessions = new ConcurrentHashMap<>();

    public ToolCallThrottlePolicy(long burstWindowMs, int burstLimit,
                                  long repeatWindowMs, int repeatThreshold) {
        this.burstWindowMs = burstWindowMs > 0 ? burstWindowMs : 30_000;
        this.burstLimit = burstLimit > 0 ? burstLimit : 25;
        this.repeatWindowMs = repeatWindowMs > 0 ? repeatWindowMs : 60_000;
        this.repeatThreshold = repeatThreshold > 0 ? repeatThreshold : 3;
    }

    /** Sensible defaults: 25 calls / 30s burst; identical-call loop trips at the 4th within 60s. */
    public ToolCallThrottlePolicy() {
        this(30_000, 25, 60_000, 3);
    }

    @Override
    public PolicyDecision evaluate(Tool tool, Map<String, Object> params, AgentContext ctx) {
        String session = ctx != null && ctx.sessionId() != null ? ctx.sessionId() : "direct";
        String toolName = tool != null ? tool.name() : "unknown";
        String argsHash = canonical(params);
        long now = System.currentTimeMillis();
        long horizon = Math.max(burstWindowMs, repeatWindowMs);

        Deque<Call> dq = sessions.computeIfAbsent(session, k -> new ConcurrentLinkedDeque<>());
        int recentTotal;
        int recentSame;
        synchronized (dq) {
            dq.removeIf(c -> c.ts() < now - horizon);

            recentTotal = 0;
            recentSame = 0;
            for (Call c : dq) {
                if (c.ts() >= now - burstWindowMs) recentTotal++;
                if (c.ts() >= now - repeatWindowMs
                        && c.tool().equals(toolName)
                        && c.argsHash().equals(argsHash)) {
                    recentSame++;
                }
            }

            // Repeat check first (deny) — a tight identical loop is the clearest runaway signal.
            // recentSame counts prior identical calls in the window; adding this one makes
            // recentSame+1 total. We trip when the count of completed identical calls already
            // equals the threshold (i.e. the 4th identical call is denied when threshold=3).
            if (recentSame >= repeatThreshold) {
                dq.addLast(new Call(now, toolName, argsHash));
                return PolicyDecision.deny("tool '" + toolName
                        + "' has been called " + (recentSame + 1)
                        + " times with identical arguments within the rate window; this looks like a "
                        + "retry loop. Change the arguments or stop calling it.");
            }
            if (recentTotal >= burstLimit) {
                dq.addLast(new Call(now, toolName, argsHash));
                return PolicyDecision.deny("tool-call burst limit (" + burstLimit
                        + " in " + (burstWindowMs / 1000) + "s) reached for this session; "
                        + "denying to prevent resource exhaustion.");
            }

            dq.addLast(new Call(now, toolName, argsHash));
            if (dq.isEmpty()) sessions.remove(session);
        }
        return PolicyDecision.allow();
    }

    /** Produce a stable string for a parameter map regardless of key order. */
    static String canonical(Object o) {
        if (o == null) return "null";
        if (o instanceof Map<?, ?> m) {
            List<String> keys = new ArrayList<>();
            for (Object k : m.keySet()) keys.add(String.valueOf(k));
            keys.sort(String.CASE_INSENSITIVE_ORDER);
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(keys.get(i)).append('=').append(canonical(m.get(keys.get(i))));
            }
            return sb.append('}').toString();
        }
        if (o instanceof List<?> l) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(canonical(l.get(i)));
            }
            return sb.append(']').toString();
        }
        if (o instanceof Object[] a) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < a.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(canonical(a[i]));
            }
            return sb.append(']').toString();
        }
        return String.valueOf(o);
    }
}
