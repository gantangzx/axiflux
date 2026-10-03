package com.gantang.reaxon.api.tool.policy;

/**
 * Outcome of a policy evaluation.
 *
 * <ul>
 *   <li>{@link Decision#ALLOW} — execute immediately</li>
 *   <li>{@link Decision#ASK}   — suspend and wait for human approval</li>
 *   <li>{@link Decision#DENY}  — reject the call outright</li>
 * </ul>
 *
 * <p>An {@code escalated} ASK (<em>prompt-injection guard</em>) cannot be
 * bypassed by auto-approval (deployment trust, budget policy, session grant):
 * when recent context contains untrusted external content, derived
 * state-changing actions always require a human.
 */
public record PolicyDecision(Decision decision, String reason, boolean escalated) {

    public enum Decision { ALLOW, ASK, DENY }

    /** Backwards-compatible constructor: non-escalated decision. */
    public PolicyDecision(Decision decision, String reason) {
        this(decision, reason, false);
    }

    public static PolicyDecision allow() {
        return new PolicyDecision(Decision.ALLOW, "");
    }

    public static PolicyDecision ask(String reason) {
        return new PolicyDecision(Decision.ASK, reason == null ? "requires approval" : reason, false);
    }

    /** Human approval that auto-approval shortcuts must NOT bypass. */
    public static PolicyDecision askEscalated(String reason) {
        return new PolicyDecision(Decision.ASK, reason == null ? "requires approval" : reason, true);
    }

    public static PolicyDecision deny(String reason) {
        return new PolicyDecision(Decision.DENY, reason == null ? "denied by policy" : reason, false);
    }

    public boolean isAllow() { return decision == Decision.ALLOW; }
    public boolean isAsk()   { return decision == Decision.ASK; }
    public boolean isDeny()  { return decision == Decision.DENY; }

    /** Most restrictive decision wins: DENY &gt; ASK &gt; ALLOW; escalated flag survives merges. */
    public PolicyDecision mostRestrictive(PolicyDecision other) {
        if (other == null) return this;
        int cmp = other.decision.ordinal() - this.decision.ordinal();
        if (cmp > 0) return other;
        if (cmp < 0) return this;
        if (other.escalated && !this.escalated) return other;
        return this;
    }
}
