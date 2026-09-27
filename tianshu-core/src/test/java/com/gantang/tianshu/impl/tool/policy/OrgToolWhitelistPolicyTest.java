package com.gantang.tianshu.impl.tool.policy;

import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.auth.CallerIdentity;
import com.gantang.tianshu.api.tool.Tool;
import com.gantang.tianshu.api.tool.policy.PolicyDecision;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrgToolWhitelistPolicy}: absent list is invisible, a
 * listed tool is allowed, and an unlisted tool is denied.
 */
class OrgToolWhitelistPolicyTest {

    private final OrgToolWhitelistPolicy policy = new OrgToolWhitelistPolicy();

    private Tool tool(String name) {
        Tool t = mock(Tool.class);
        when(t.name()).thenReturn(name);
        return t;
    }

    private AgentContext contextWith(Object allowed) {
        AgentContext ctx = mock(AgentContext.class);
        when(ctx.metadata()).thenReturn(allowed == null
            ? Map.of() : Map.of(CallerIdentity.META_ORG_ALLOWED_TOOLS, allowed));
        return ctx;
    }

    @Test
    void absentKeyAllows() {
        PolicyDecision d = policy.evaluate(tool("http_fetch"), Map.of(), contextWith(null));
        assertTrue(d.isAllow());
    }

    @Test
    void emptyListAllows() {
        PolicyDecision d = policy.evaluate(tool("http_fetch"), Map.of(), contextWith("  "));
        assertTrue(d.isAllow());
    }

    @Test
    void listedToolAllowedCaseInsensitive() {
        PolicyDecision d = policy.evaluate(tool("HTTP_Fetch"), Map.of(),
            contextWith("http_fetch, code_executor"));
        assertTrue(d.isAllow());
    }

    @Test
    void unlistedToolDenied() {
        PolicyDecision d = policy.evaluate(tool("email_send"), Map.of(),
            contextWith("http_fetch,code_executor"));
        assertFalse(d.isAllow());
        assertTrue(d.isDeny());
    }
}
