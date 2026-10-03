package com.gantang.reaxon.api.tool.policy;

import java.util.Map;

/**
 * Human-facing description of one {@link ToolPolicy} rule, used by the
 * console Security page to render the <em>actually wired</em> chain instead
 * of a hard-coded picture.
 *
 * <p>{@code kind} classifies the rule so the UI can show whether it is a
 * commercial variable or an invariant security baseline:
 * <ul>
 *   <li>{@code deployment} — operator/instance switch (allow lists, private
 *       network toggle); not sold by plan.</li>
 *   <li>{@code governance} — persona / organization scoping.</li>
 *   <li>{@code commercial} — capability entitlement that <b>does</b> track the
 *       plan tier (scope grants, plan-tier matrix).</li>
 *   <li>{@code security} — invariant guard (SSRF, risk approval, throttle);
 *       enforced identically for every plan and never downgraded for pay.</li>
 * </ul>
 *
 * @param order      zero-based execution index within the live chain
 * @param className  concrete policy simple class name
 * @param key        stable identifier (e.g. {@code ToolList})
 * @param label      short Chinese label
 * @param detail     one-line Chinese explanation
 * @param kind       one of deployment / governance / commercial / security
 * @param planAware  whether the effective result changes with the billing plan
 */
public record PolicyMetadata(
        int order,
        String className,
        String key,
        String label,
        String detail,
        String kind,
        boolean planAware
) {

    public Map<String, Object> toMap() {
        return Map.of(
                "order", order,
                "className", className,
                "key", key,
                "label", label,
                "detail", detail,
                "kind", kind,
                "planAware", planAware
        );
    }
}
