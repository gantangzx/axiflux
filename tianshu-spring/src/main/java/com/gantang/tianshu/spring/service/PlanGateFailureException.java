package com.gantang.tianshu.spring.service;

/**
 * Domain failure for the open-core {@link PlanGateSpi}: carries the intended
 * HTTP status (402 Payment Required) plus the plan the caller would need. The
 * web layer maps it without the SPI depending on web types.
 */
public class PlanGateFailureException extends HttpDomainException {
    private final String requiredPlan;
    private final String currentPlan;

    protected PlanGateFailureException(int status, String message,
                                       String requiredPlan, String currentPlan) {
        super(status, message);
        this.requiredPlan = requiredPlan;
        this.currentPlan = currentPlan;
    }

    public String requiredPlan() { return requiredPlan; }
    public String currentPlan() { return currentPlan; }

    /** 402: the caller's plan does not cover the feature. */
    public static PlanGateFailureException requiresPlan(String feature, String required, String current) {
        return new PlanGateFailureException(402,
            "feature '" + feature + "' requires the '" + required + "' plan"
                + " (current plan: '" + current + "')",
            required, current);
    }
}
