package com.gantang.tianshu.spring.service;

/**
 * Snapshot of an organization's quota standing for one period, exposed by the
 * open-core {@link QuotaServiceSpi}. A plain DTO so the open-source transports
 * never reference the closed-source service type.
 *
 * @param orgId       organization id
 * @param period      accounting period (UTC {@code yyyy-MM})
 * @param plan        plan tier the limits derive from
 * @param usedTokens  tokens consumed this period
 * @param tokenLimit  monthly token cap ({@code 0} = unlimited)
 * @param usedTurns   turns consumed this period
 * @param turnLimit   monthly turn cap ({@code 0} = unlimited)
 * @param enforced    whether quota is actively enforced
 */
public record QuotaStatus(
    String orgId, String period, String plan,
    long usedTokens, long tokenLimit,
    long usedTurns, long turnLimit,
    boolean enforced) {

    /** Remaining tokens this period (never negative; {@code -1} when unlimited). */
    public long remainingTokens() {
        return tokenLimit == 0 ? -1L : Math.max(0L, tokenLimit - usedTokens);
    }

    /** Remaining turns this period (never negative; {@code -1} when unlimited). */
    public long remainingTurns() {
        return turnLimit == 0 ? -1L : Math.max(0L, turnLimit - usedTurns);
    }
}
