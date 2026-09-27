package com.gantang.tianshu.api.agent;

import com.gantang.tianshu.api.tool.Tool;

/**
 * Decides whether an ASK-gated tool call may skip the interactive human
 * approval and proceed automatically ("auto-approve").
 *
 * <p>This only ever <em>narrows</em> the approval gate: it is consulted after
 * the security policy chain returns ASK and after DENY checks (whitelist,
 * SSRF, agent tool permissions, caller scopes) have already passed. A
 * {@code false} result simply falls through to the normal human-approval flow.
 *
 * <p>Design pattern: <b>Strategy</b> — deployment operators plug in their own
 * auto-approval rules (budget windows, time-of-day, per-user quotas).
 */
public interface AutoApprovalPolicy {

    /** Never auto-approves — every ASK goes to a human. */
    AutoApprovalPolicy NEVER = (ctx, tool) -> false;

    /**
     * @param ctx  the agent turn context (session/user identity)
     * @param tool the tool whose call is gated
     * @return {@code true} to auto-approve this call, {@code false} to ask a human
     */
    boolean isAutoApproved(AgentContext ctx, Tool tool);
}
