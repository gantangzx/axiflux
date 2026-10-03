package com.gantang.reaxon.impl.llm.router;

/**
 * Raised when a caller on a plan that does not cover {@code advanced_models}
 * explicitly requests a premium model.
 *
 * <p>This is distinct from a routing error: the model <em>is</em> available and
 * the request is well-formed, but the commercial plan does not include it. The
 * web layer maps it to HTTP 402; the agent converts it to an upgrade prompt.
 * Automatic (unforced) routing is never gated — a free user simply gets routed to
 * an in-plan model, so the free experience keeps working.
 */
public class AdvancedModelGateException extends RuntimeException {

    private final String model;
    private final String planTier;

    public AdvancedModelGateException(String model, String planTier) {
        super("高级模型 '" + model + "' 需要 Pro 及以上套餐（当前："
            + (planTier == null ? "free" : planTier) + "）。升级后即可使用。");
        this.model = model;
        this.planTier = planTier;
    }

    public String model() {
        return model;
    }

    public String planTier() {
        return planTier;
    }
}
