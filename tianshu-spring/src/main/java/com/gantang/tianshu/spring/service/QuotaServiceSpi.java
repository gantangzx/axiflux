package com.gantang.tianshu.spring.service;

import com.gantang.tianshu.api.auth.CallerIdentity;

/**
 * Open-core SPI for monthly quota enforcement.
 *
 * <p>Contract shipped in the open-source framework; the implementation that
 * aggregates {@code usage_record}, atomically reserves turns and reads the
 * billing switch lives in the closed-source {@code tianshu-commercial} module.
 * Open-source transports obtain it through an {@code ObjectProvider}; absent the
 * commercial jar no bean exists and no quota is enforced (self-hosted).
 */
public interface QuotaServiceSpi {

    /**
     * Refuse the turn when the caller's organization has exhausted a monthly
     * cap. No-op when quota/billing is off or the caller has no organization.
     *
     * @throws QuotaExceededException 429 naming the breached dimension
     */
    void requireWithinQuota(CallerIdentity caller);

    /** Current quota standing of an org for the period (for UI display). */
    QuotaStatus statusOf(String orgId);
}
