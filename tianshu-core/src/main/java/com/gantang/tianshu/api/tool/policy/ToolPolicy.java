package com.gantang.tianshu.api.tool.policy;

import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.tool.Tool;

import java.util.Map;

/**
 * A single security rule evaluated before a tool executes.
 *
 * <p>Policies are pure, Spring-free (core has no Spring dependency): all
 * configuration is passed via constructors. Implementations must be
 * thread-safe and side-effect free.
 */
@FunctionalInterface
public interface ToolPolicy {

    /**
     * @param tool   the tool about to be called
     * @param params parsed tool arguments
     * @param ctx    the owning agent context (session/user/metadata)
     * @return ALLOW, ASK or DENY with a human-readable reason
     */
    PolicyDecision evaluate(Tool tool, Map<String, Object> params, AgentContext ctx);
}
