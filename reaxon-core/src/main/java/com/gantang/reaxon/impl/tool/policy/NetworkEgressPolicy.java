package com.gantang.reaxon.impl.tool.policy;

import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.config.LiveSettings;
import com.gantang.reaxon.api.tool.Tool;
import com.gantang.reaxon.api.tool.policy.PolicyDecision;
import com.gantang.reaxon.api.tool.policy.ToolPolicy;
import com.gantang.reaxon.impl.tool.support.EgressGuard;

import java.util.Map;
import java.util.Set;

/**
 * SSRF guard for tools that fetch attacker-influenceable URLs.
 *
 * <p>Checks the tool arguments before execution. The HTTP tools additionally
 * re-validate every redirect hop via {@link EgressGuard}, so a public URL that
 * 302-redirects to an internal address cannot bypass this check.
 *
 * <p>DNS-rebinding (a public name that resolves to an internal IP) is NOT
 * resolved here — that requires egress-layer enforcement (proxy / firewall)
 * and is documented as a deployment control.
 */
public final class NetworkEgressPolicy implements ToolPolicy {

    /** Tools whose arguments include a URL the model/user controls. */
    private static final Set<String> URL_TOOLS = Set.of("http_client", "web_fetch");

    private final LiveSettings live;
    private final boolean fixedAllowPrivateNetwork;

    public NetworkEgressPolicy(boolean allowPrivateNetwork) {
        this.live = null;
        this.fixedAllowPrivateNetwork = allowPrivateNetwork;
    }

    /** Live mode: the allow-private flag is read from {@link LiveSettings} per call. */
    public NetworkEgressPolicy(LiveSettings live) {
        this.live = live;
        this.fixedAllowPrivateNetwork = false;
    }

    private boolean allowPrivateNetwork() {
        return live == null ? fixedAllowPrivateNetwork : live.snapshot().allowPrivateNetwork();
    }

    @Override
    public PolicyDecision evaluate(Tool tool, Map<String, Object> params, AgentContext ctx) {
        if (!URL_TOOLS.contains(tool.name()) || params == null) {
            return PolicyDecision.allow();
        }
        for (Object value : params.values()) {
            if (!(value instanceof String s)) continue;
            String trimmed = s.trim();
            if (!EgressGuard.looksLikeUrl(trimmed)) continue;
            String deny = EgressGuard.denyReason(trimmed, allowPrivateNetwork());
            if (deny != null) {
                return PolicyDecision.deny(deny);
            }
        }
        return PolicyDecision.allow();
    }
}
