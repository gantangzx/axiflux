package com.gantang.axiflux.spring.service;

import com.gantang.reaxon.api.auth.CallerIdentity;

/**
 * Open-core SPI for the plan-tier feature gate.
 *
 * <p>This is the <em>contract</em> shipped in the open-source framework; the
 * implementation that resolves organizations, plan tiers and the billing switch
 * lives in the closed-source {@code axiflux-commercial} module. Open-source
 * transports obtain an instance through an {@code ObjectProvider}, so when the
 * commercial jar is absent no bean exists and every paid-feature check is simply
 * skipped (single-user self-hosted behaviour) rather than failing.
 *
 * <p>Feature keys and plan names are defined here so open-source callers never
 * reference a closed-source type.
 */
public interface PlanGateSpi {

    /** Scheduled-task creation (POST /api/v1/scheduler/cron|delay). */
    String FEATURE_SCHEDULER = "scheduler";
    /** Skill installation (POST /api/v1/skills/install, incl. /{name}/update). */
    String FEATURE_SKILL_INSTALL = "skill.install";
    /** Batch skill update (POST /api/v1/skills/update-all). */
    String FEATURE_SKILL_UPDATE_ALL = "skill.updateAll";

    String PLAN_FREE = "free";
    String PLAN_PRO = "pro";
    String PLAN_TEAM = "team";

    /**
     * Require the caller's organization plan to cover {@code feature}.
     *
     * @throws PlanGateException 402 with the required plan named when the
     *         caller's org plan is insufficient
     */
    void require(String feature, CallerIdentity caller);

    /** The org's plan tier ("free" on unknown/missing). */
    String planOf(CallerIdentity caller);

    /** The minimum plan a feature needs; an unlisted feature is "free". */
    String requiredPlan(String feature);
}
