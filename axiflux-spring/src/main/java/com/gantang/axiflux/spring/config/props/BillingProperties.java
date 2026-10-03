package com.gantang.axiflux.spring.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Binds {@code axiflux.billing.*} — subscription gating + Stripe webhook
 * (BYOK commercialization, P0-4).
 *
 * <pre>
 * axiflux.billing:
 *   enabled: false                    # master switch; off = no gating, webhook 503
 *   webhook-secret: whsec_...         # Stripe signing secret for the webhook endpoint
 *   price-plan:                       # Stripe price id -> plan tier
 *     price_1Pro: pro
 *     price_1Team: team
 *   feature-plan:                     # feature -> minimum required plan tier
 *     scheduler: pro                  # (empty map = PlanGate's built-in defaults)
 *     skill.install: pro
 *     skill.updateAll: team
 * </pre>
 *
 * <p>All fields default to "off / empty", which preserves pre-P0-4 behaviour:
 * no request is gated and the webhook endpoint reports itself unconfigured.
 */
@ConfigurationProperties(prefix = "axiflux.billing")
public class BillingProperties {

    /**
     * Master switch for the billing surface. When {@code false} (default) the
     * plan gate lets every caller through and the Stripe webhook endpoint
     * answers 503 (not configured).
     */
    private boolean enabled = false;

    /**
     * Stripe webhook signing secret ({@code whsec_...}). Required for the
     * webhook endpoint; without it the endpoint answers 503.
     */
    private String webhookSecret;

    /**
     * Stripe price id → plan tier mapping ({@code price_...} →
     * {@code free|pro|team}). Used by the webhook to derive the org's plan
     * from the subscribed price. An unmapped price falls back to
     * {@code subscription.metadata.plan_tier}, then leaves the plan unchanged.
     */
    private Map<String, String> pricePlan = new LinkedHashMap<>();

    /**
     * Feature → minimum required plan tier. Empty (default) means
     * {@code PlanGate}'s built-in matrix applies; entries here override it
     * per feature.
     */
    private Map<String, String> featurePlan = defaultFeaturePlan();

    /**
     * Built-in feature → minimum-plan matrix, shared by the runtime gate and the
     * console lock tags. Kept consistent with core {@code PlanTierToolPolicy}
     * defaults; operators may still replace the whole map in YAML.
     */
    static Map<String, String> defaultFeaturePlan() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("advanced_models", "pro");
        m.put("long_term_memory", "pro");
        m.put("multi_agent", "pro");
        m.put("code_executor", "pro");
        m.put("spawn_task", "pro");
        m.put("email_send", "pro");
        m.put("tts", "pro");
        return m;
    }

    /**
     * Length of the free trial granted to a newly self-registered user's
     * organization, in days. {@code 0} disables trials (new orgs start on the
     * plain free tier). Only consulted during self-serve onboarding.
     */
    private int trialDays = 14;

    /**
     * Stripe secret server-side key ({@code sk_live_...} / {@code sk_test_...}).
     * Sent as {@code Authorization: Bearer} on Stripe REST calls. When blank,
     * the console stays in mock-preview mode even when {@link #enabled} is on.
     */
    private String secretKey;

    /**
     * Stripe publishable key ({@code pk_live_...} / {@code pk_test_...}). Surfaced
     * to the SPA if it embeds Stripe.js; not required for server-side Checkout.
     */
    private String publishableKey;

    /** Stripe API base URL; overridable for stubbing in tests. */
    private String apiBase = "https://api.stripe.com";

    /**
     * Public origin of this deployment ({@code https://app.example.com}), used
     * to build absolute Stripe redirect URLs. When blank, relative configured
     * paths are returned and the deployment must supply its own origin.
     */
    private String publicUrl;

    /**
     * Plan tier → Stripe recurring <em>price id</em> used when creating a
     * Checkout session (forward lookup). Distinct from {@link #pricePlan}, which
     * is the reverse map (price id → tier) the webhook uses.
     */
    private Map<String, String> tierPrice = new LinkedHashMap<>();

    /**
     * Plan tier → monthly Stripe recurring price id. Used for monthly Checkout
     * sessions; distinct from the optional yearly prices in {@link #tierYearlyPrice}.
     * When this map is empty the legacy {@link #tierPrice} map is consulted.
     */
    private Map<String, String> tierMonthlyPrice = new LinkedHashMap<>();

    /**
     * Plan tier → yearly Stripe recurring price id (typically carrying the annual
     * discount). Absent tiers fall back to monthly, so a deployment can enable
     * yearly billing only for the plans it has configured.
     */
    private Map<String, String> tierYearlyPrice = new LinkedHashMap<>();

    /**
     * Whether the Stripe Checkout page exposes a promotion-code field. When
     * {@code true} the session is created with {@code allow_promotion_codes=true};
     * codes themselves are created in the Stripe Dashboard.
     */
    private boolean allowPromotionCodes = true;

    /**
     * Where Stripe sends the browser after a successful Checkout. A relative
     * path is resolved against the request origin; {@code {CHECKOUT_SESSION_ID}}
     * is left for Stripe to interpolate. Points at a public static bridge page
     * (billing-return.html), since the browser lands here without a JWT.
     */
    private String successUrl = "/billing-return.html?session_id={CHECKOUT_SESSION_ID}";

    /** Where Stripe sends the browser when the user cancels out of Checkout. */
    private String cancelUrl = "/billing-cancel.html";

    /**
     * Per-plan periodic usage quotas (P1). Limits the resources an organization
     * may consume in a calendar month (UTC, aligned with the {@code period} key
     * on {@code usage_record}); sending is refused once a cap is reached.
     */
    private Quota quota = new Quota();

    /**
     * Quota configuration. Two independently-enforced dimensions: monthly
     * tokens (input + output) and monthly agent turns. A cap of {@code 0} means
     * that dimension is unlimited. Per-tier maps may be overridden wholesale in
     * YAML; a tier absent from a map is treated as unlimited.
     */
    public static class Quota {
        /**
         * Master switch for quota enforcement. When {@code true} quotas apply
         * while billing is enabled; {@code false} turns the QuotaService into a
         * no-op (the default limits are not enforced).
         */
        private boolean enabled = true;

        /** Plan tier → monthly token cap (input + output tokens). 0 = unlimited. */
        private Map<String, Long> monthlyTokens = defaultMonthlyTokens();

        /** Plan tier → monthly agent-turn cap. 0 = unlimited. */
        private Map<String, Integer> monthlyTurns = defaultMonthlyTurns();

        private static Map<String, Long> defaultMonthlyTokens() {
            Map<String, Long> m = new LinkedHashMap<>();
            m.put("free", 200_000L);
            m.put("pro", 5_000_000L);
            m.put("team", 25_000_000L);
            return m;
        }

        private static Map<String, Integer> defaultMonthlyTurns() {
            Map<String, Integer> m = new LinkedHashMap<>();
            m.put("free", 200);
            m.put("pro", 0);
            m.put("team", 0);
            return m;
        }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Map<String, Long> getMonthlyTokens() { return monthlyTokens; }
        public void setMonthlyTokens(Map<String, Long> monthlyTokens) {
            this.monthlyTokens = monthlyTokens == null ? new LinkedHashMap<>() : monthlyTokens;
        }
        public Map<String, Integer> getMonthlyTurns() { return monthlyTurns; }
        public void setMonthlyTurns(Map<String, Integer> monthlyTurns) {
            this.monthlyTurns = monthlyTurns == null ? new LinkedHashMap<>() : monthlyTurns;
        }
    }

    public boolean isLive() {
        return enabled && secretKey != null && !secretKey.isBlank();
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
    public Map<String, String> getPricePlan() { return pricePlan; }
    public void setPricePlan(Map<String, String> pricePlan) {
        this.pricePlan = pricePlan == null ? new LinkedHashMap<>() : pricePlan;
    }
    public Map<String, String> getFeaturePlan() { return featurePlan; }
    public void setFeaturePlan(Map<String, String> featurePlan) {
        this.featurePlan = featurePlan == null ? new LinkedHashMap<>() : featurePlan;
    }
    public int getTrialDays() { return trialDays; }
    public void setTrialDays(int trialDays) { this.trialDays = trialDays; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public String getPublishableKey() { return publishableKey; }
    public void setPublishableKey(String publishableKey) { this.publishableKey = publishableKey; }
    public String getApiBase() { return apiBase; }
    public void setApiBase(String apiBase) { this.apiBase = apiBase; }
    public String getPublicUrl() { return publicUrl; }
    public void setPublicUrl(String publicUrl) { this.publicUrl = publicUrl; }
    public Map<String, String> getTierPrice() { return tierPrice; }
    public void setTierPrice(Map<String, String> tierPrice) {
        this.tierPrice = tierPrice == null ? new LinkedHashMap<>() : tierPrice;
    }
    public Map<String, String> getTierMonthlyPrice() { return tierMonthlyPrice; }
    public void setTierMonthlyPrice(Map<String, String> tierMonthlyPrice) {
        this.tierMonthlyPrice = tierMonthlyPrice == null ? new LinkedHashMap<>() : tierMonthlyPrice;
    }
    public Map<String, String> getTierYearlyPrice() { return tierYearlyPrice; }
    public void setTierYearlyPrice(Map<String, String> tierYearlyPrice) {
        this.tierYearlyPrice = tierYearlyPrice == null ? new LinkedHashMap<>() : tierYearlyPrice;
    }
    public boolean isAllowPromotionCodes() { return allowPromotionCodes; }
    public void setAllowPromotionCodes(boolean allowPromotionCodes) {
        this.allowPromotionCodes = allowPromotionCodes;
    }

    /**
     * Resolve the configured Stripe price id for a plan and interval.
     * Prefers the interval-specific map, then the legacy {@link #tierPrice}
     * map (for monthly). Returns null when nothing is configured.
     */
    public String resolvePriceId(String planTier, String interval) {
        boolean yearly = "year".equalsIgnoreCase(interval) || "yearly".equalsIgnoreCase(interval);
        Map<String, String> specific = yearly ? tierYearlyPrice : tierMonthlyPrice;
        String price = specific.get(planTier);
        if (price != null && !price.isBlank()) return price;
        if (yearly) {
            // No yearly price configured: fall back to monthly rather than fail.
            price = tierMonthlyPrice.get(planTier);
        }
        if (price == null || price.isBlank()) price = tierPrice.get(planTier);
        return (price != null && !price.isBlank()) ? price : null;
    }

    public String getSuccessUrl() { return successUrl; }
    public void setSuccessUrl(String successUrl) { this.successUrl = successUrl; }
    public String getCancelUrl() { return cancelUrl; }
    public void setCancelUrl(String cancelUrl) { this.cancelUrl = cancelUrl; }
    public Quota getQuota() { return quota; }
    public void setQuota(Quota quota) { this.quota = quota == null ? new Quota() : quota; }
}
