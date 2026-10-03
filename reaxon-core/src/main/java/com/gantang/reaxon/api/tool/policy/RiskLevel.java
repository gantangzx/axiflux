package com.gantang.reaxon.api.tool.policy;

/**
 * Risk classification for a tool, used by the {@link ToolPolicyChain} to decide
 * whether a call is allowed, requires human approval, or is denied.
 *
 * <p>Risk escalates in declaration order. The default ceiling maps the two
 * highest levels (WRITE / DESTRUCTIVE) to human approval.
 */
public enum RiskLevel {
    /** Pure computation, no side effects, no I/O (e.g. calculator). */
    SAFE,
    /** Read-only access to local or remote data (e.g. file_read, web_search). */
    READ,
    /** Outbound network calls whose target is attacker-influenceable (e.g. http_client). */
    NETWORK,
    /** Mutates state or sends something outward (e.g. file_write, email_send). */
    WRITE,
    /** Arbitrary code execution / sub-agent delegation / irreversible actions. */
    DESTRUCTIVE;

    public boolean atLeast(RiskLevel other) {
        return this.ordinal() >= other.ordinal();
    }
}
