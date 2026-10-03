package com.gantang.reaxon.impl.approval;

import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.agent.AutoApprovalPolicy;
import com.gantang.reaxon.api.config.LiveSettings;
import com.gantang.reaxon.api.tool.Tool;
import com.gantang.reaxon.api.tool.policy.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Budget-threshold auto-approval: each gated tool may auto-approve up to a
 * fixed number of calls per session; once the budget is spent, subsequent calls
 * fall through to interactive human approval.
 *
 * <p>Use case: a deployment operator wants the first few routine actions in a
 * session (e.g. a couple of {@code file_write} calls) to proceed without a
 * human click, while an unbounded stream of writes — a runaway loop or a
 * prompt-injected agent — pauses for review.
 *
 * <p>Safety constraints:
 * <ul>
 *   <li>Only tools at or below a configurable {@link RiskLevel} ceiling are
 *       eligible; by default {@link RiskLevel#WRITE} — {@code DESTRUCTIVE}
 *       tools (code_executor, spawn) are never auto-approved.</li>
 *   <li>DENY gates still apply: this policy is only consulted after the
 *       security chain returns ASK.</li>
 *   <li>Counters are per (session, tool); a budget of 0 disables auto-approval
 *       for that tool.</li>
 *   <li>In-memory state is bounded: when the session count exceeds
 *       {@link #MAX_TRACKED_SESSIONS} the least-recently-used sessions are
 *       evicted (they simply start a fresh budget afterwards).</li>
 * </ul>
 */
public final class BudgetAutoApprovalPolicy implements AutoApprovalPolicy {

    private static final Logger log = LoggerFactory.getLogger(BudgetAutoApprovalPolicy.class);
    private static final int MAX_TRACKED_SESSIONS = 5000;

    /** Live source (console-tunable); when set, fixed* fields are ignored. */
    private final LiveSettings live;
    private final RiskLevel fixedRiskCeiling;
    private final int fixedDefaultBudget;
    private final Map<String, Integer> fixedToolBudgets;

    /** sessionId -> toolName -> consumed budget */
    private final Map<String, Map<String, AtomicInteger>> consumed = new ConcurrentHashMap<>();
    /** sessionId -> lastAccess epoch millis, for LRU eviction */
    private final Map<String, Long> lastAccess = new ConcurrentHashMap<>();

    public BudgetAutoApprovalPolicy(RiskLevel riskCeiling, int defaultBudget, Map<String, Integer> toolBudgets) {
        this.live = null;
        this.fixedRiskCeiling = riskCeiling == null ? RiskLevel.WRITE : riskCeiling;
        this.fixedDefaultBudget = Math.max(0, defaultBudget);
        this.fixedToolBudgets = toolBudgets == null ? Map.of() : Map.copyOf(toolBudgets);
    }

    /** Live mode: ceiling/budgets/enabled are read from {@link LiveSettings} per call. */
    public BudgetAutoApprovalPolicy(LiveSettings live) {
        this.live = live;
        this.fixedRiskCeiling = RiskLevel.WRITE;
        this.fixedDefaultBudget = 3;
        this.fixedToolBudgets = Map.of();
    }

    private boolean enabled() {
        return live == null || live.snapshot().budgetEnabled();
    }

    private RiskLevel ceiling() {
        if (live == null) return fixedRiskCeiling;
        try {
            RiskLevel r = RiskLevel.valueOf(
                live.snapshot().budgetRiskCeiling().trim().toUpperCase(Locale.ROOT));
            // DESTRUCTIVE can never be an auto-approval ceiling.
            return r == RiskLevel.DESTRUCTIVE ? RiskLevel.WRITE : r;
        } catch (Exception e) {
            return RiskLevel.WRITE;
        }
    }

    private int defaultBudget() {
        return live == null ? fixedDefaultBudget : Math.max(0, live.snapshot().budgetDefault());
    }

    private int budgetFor(String tool) {
        if (live == null) return fixedToolBudgets.getOrDefault(tool, fixedDefaultBudget);
        LiveSettings.Snapshot s = live.snapshot();
        Integer v = s.toolBudgets().get(tool);
        return v != null ? Math.max(0, v) : Math.max(0, s.budgetDefault());
    }

    @Override
    public boolean isAutoApproved(AgentContext ctx, Tool tool) {
        if (ctx == null || tool == null) return false;
        if (!enabled()) return false;
        RiskLevel risk = tool.riskLevel();
        if (risk == null || risk.atLeast(RiskLevel.DESTRUCTIVE)) {
            return false; // irreversible actions always need a human
        }
        RiskLevel ceiling = ceiling();
        if (risk.ordinal() > ceiling.ordinal()) {
            return false; // riskier than the configured ceiling
        }
        int budget = budgetFor(tool.name());
        if (budget <= 0) return false;

        touch(ctx.sessionId());
        Map<String, AtomicInteger> perTool = consumed.computeIfAbsent(ctx.sessionId(), k -> new ConcurrentHashMap<>());
        int n = perTool.computeIfAbsent(tool.name(), k -> new AtomicInteger()).incrementAndGet();
        if (n <= budget) {
            log.info("Auto-approved tool {} in session {} via budget ({}/{})",
                tool.name(), ctx.sessionId(), n, budget);
            return true;
        }
        log.info("Budget exhausted for tool {} in session {} ({}/{}); requiring human approval",
            tool.name(), ctx.sessionId(), n, budget);
        return false;
    }

    /** Clear all consumed budget for a session (e.g. when the session is deleted). */
    public void resetSession(String sessionId) {
        if (sessionId == null) return;
        consumed.remove(sessionId);
        lastAccess.remove(sessionId);
    }

    private void touch(String sessionId) {
        long now = System.currentTimeMillis();
        lastAccess.put(sessionId, now);
        if (lastAccess.size() > MAX_TRACKED_SESSIONS) {
            evictOldest();
        }
    }

    /** Remove the oldest ~25% of tracked sessions so a fresh budget applies afterwards. */
    private void evictOldest() {
        int target = lastAccess.size() - (MAX_TRACKED_SESSIONS * 3 / 4);
        if (target <= 0) return;
        lastAccess.entrySet().stream()
            .sorted(Map.Entry.comparingByValue())
            .limit(target)
            .forEach(e -> {
                consumed.remove(e.getKey());
                lastAccess.remove(e.getKey());
            });
    }
}
