package com.gantang.reaxon.impl.tool.policy;

import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.tool.Tool;
import com.gantang.reaxon.api.tool.ToolResult;
import com.gantang.reaxon.api.tool.policy.PolicyDecision;
import com.gantang.reaxon.api.tool.policy.RiskLevel;
import com.gantang.reaxon.api.tool.policy.ToolPolicyChain;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the tool security policy chain:
 * whitelist/blacklist, risk-level approval, SSRF guard, precedence.
 */
class ToolPolicyChainTest {

    private final AgentContext ctx = AgentContext.builder()
            .sessionId("s1").userId("u1").currentQuery("q").build();
    private final ObjectMapper om = new ObjectMapper();

    private Tool tool(String name, RiskLevel level, boolean approval) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name + " test tool"; }
            @Override public com.fasterxml.jackson.databind.JsonNode parameters() { return om.createObjectNode(); }
            @Override public RiskLevel riskLevel() { return level; }
            @Override public boolean requiresApproval() { return approval; }
            @Override public ToolResult execute(String callId, Map<String, Object> params, AgentContext context) {
                return ToolResult.success(callId, "ok");
            }
        };
    }

    private ToolPolicyChain chain(boolean allowPrivate) {
        return new ToolPolicyChain(List.of(
            new ToolListPolicy(ToolListPolicy.Mode.ALL, Set.of(), Set.of()),
            new RiskLevelPolicy(),
            new NetworkEgressPolicy(allowPrivate)
        ));
    }

    @Test
    void safeToolIsAllowedWithoutApproval() {
        Tool calc = tool("calculator", RiskLevel.SAFE, false);
        PolicyDecision d = chain(false).evaluate(calc, Map.of("expression", "1+1"), ctx);
        assertTrue(d.isAllow(), "SAFE tool should be allowed: " + d.reason());
    }

    @Test
    void writeLevelToolAsksForApproval() {
        Tool fw = tool("file_write", RiskLevel.WRITE, true);
        PolicyDecision d = chain(false).evaluate(fw, Map.of("path", "/tmp/a.txt"), ctx);
        assertTrue(d.isAsk(), "WRITE tool should require approval");
    }

    @Test
    void destructiveToolAsksForApproval() {
        Tool cx = tool("code_executor", RiskLevel.DESTRUCTIVE, true);
        PolicyDecision d = chain(false).evaluate(cx, Map.of("command", "ls"), ctx);
        assertTrue(d.isAsk(), "DESTRUCTIVE tool should require approval");
    }

    @Test
    void approvalFlagForcesAskEvenWhenLevelIsLow() {
        Tool weird = tool("weird", RiskLevel.SAFE, true);
        PolicyDecision d = chain(false).evaluate(weird, Map.of(), ctx);
        assertTrue(d.isAsk());
    }

    // ── whitelist / blacklist ──────────────────────────────────────────────

    @Test
    void whitelistDeniesUnlistedTool() {
        ToolPolicyChain c = new ToolPolicyChain(List.of(
            new ToolListPolicy(ToolListPolicy.Mode.WHITELIST, Set.of("calculator", "date_time"), Set.of()),
            new RiskLevelPolicy()));
        assertTrue(c.evaluate(tool("http_client", RiskLevel.NETWORK, false), Map.of(), ctx).isDeny());
        assertTrue(c.evaluate(tool("calculator", RiskLevel.SAFE, false), Map.of(), ctx).isAllow());
    }

    @Test
    void blacklistBlocksNamedTool() {
        ToolPolicyChain c = new ToolPolicyChain(List.of(
            new ToolListPolicy(ToolListPolicy.Mode.BLACKLIST, Set.of(), Set.of("code_executor")),
            new RiskLevelPolicy()));
        assertTrue(c.evaluate(tool("code_executor", RiskLevel.DESTRUCTIVE, true), Map.of(), ctx).isDeny());
        assertTrue(c.evaluate(tool("calculator", RiskLevel.SAFE, false), Map.of(), ctx).isAllow());
    }

    // ── SSRF ───────────────────────────────────────────────────────────────

    @Test
    void blocksLoopbackAndPrivateAddresses() {
        NetworkEgressPolicy p = new NetworkEgressPolicy(false);
        Tool http = tool("http_client", RiskLevel.NETWORK, false);
        for (String url : List.of(
                "http://127.0.0.1:8080/admin",
                "http://localhost:9090/metrics",
                "http://169.254.169.254/latest/meta-data/",
                "http://10.0.0.5/internal",
                "http://192.168.1.1/",
                "http://172.16.0.1/",
                "http://[::1]:8080/",
                "file:///etc/passwd")) {
            PolicyDecision d = p.evaluate(http, Map.of("url", url), ctx);
            assertTrue(d.isDeny(), "should block " + url + " but got " + d.decision());
        }
    }

    @Test
    void allowsPublicHttps() {
        NetworkEgressPolicy p = new NetworkEgressPolicy(false);
        Tool fetch = tool("web_fetch", RiskLevel.NETWORK, false);
        PolicyDecision d = p.evaluate(fetch, Map.of("url", "https://api.example.com/data"), ctx);
        assertTrue(d.isAllow(), "public URL should pass: " + d.reason());
    }

    @Test
    void privateNetworkAllowedWhenConfigured() {
        NetworkEgressPolicy p = new NetworkEgressPolicy(true);
        Tool http = tool("http_client", RiskLevel.NETWORK, false);
        PolicyDecision d = p.evaluate(http, Map.of("url", "http://127.0.0.1:8080/health"), ctx);
        assertTrue(d.isAllow(), "dev mode should allow loopback: " + d.reason());
    }

    @Test
    void nonNetworkToolIsNotSubjectToEgressPolicy() {
        NetworkEgressPolicy p = new NetworkEgressPolicy(false);
        Tool calc = tool("calculator", RiskLevel.SAFE, false);
        // even with a private URL in params, calculator isn't a URL tool
        PolicyDecision d = p.evaluate(calc, Map.of("expression", "http://127.0.0.1"), ctx);
        assertTrue(d.isAllow());
    }

    // ── chain semantics ────────────────────────────────────────────────────

    @Test
    void denyWinsOverAsk() {
        ToolPolicyChain c = new ToolPolicyChain(List.of(
            (t, p, x) -> PolicyDecision.ask("ask"),
            (t, p, x) -> PolicyDecision.deny("deny")));
        PolicyDecision d = c.evaluate(tool("t", RiskLevel.SAFE, false), Map.of(), ctx);
        assertTrue(d.isDeny());
        assertEquals("deny", d.reason());
    }

    @Test
    void emptyChainAllows() {
        PolicyDecision d = ToolPolicyChain.open().evaluate(
            tool("anything", RiskLevel.DESTRUCTIVE, true), Map.of(), ctx);
        assertTrue(d.isAllow());
    }

    @Test
    void brokenPolicyFailsClosed() {
        ToolPolicyChain c = new ToolPolicyChain(List.of(
            (ToolPolicyChainTest::boom)));
        PolicyDecision d = c.evaluate(tool("t", RiskLevel.SAFE, false), Map.of(), ctx);
        assertTrue(d.isDeny(), "a failing guard must deny, not allow");
    }

    private static PolicyDecision boom(Tool t, Map<String, Object> p, AgentContext c) {
        throw new IllegalStateException("guard exploded");
    }

    // ── per-agent scope ──────────────────────────────────────────────────

    private AgentContext ctxWithMeta(Map<String, Object> extra) {
        Map<String, Object> meta = new java.util.HashMap<>();
        if (extra != null) meta.putAll(extra);
        return AgentContext.builder().sessionId("s1").userId("u1")
            .currentQuery("q").metadata(meta).build();
    }

    @Test
    void agentWhitelistDeniesToolNotInScope() {
        AgentScopePolicy p = new AgentScopePolicy();
        Tool http = tool("http_client", RiskLevel.NETWORK, false);
        AgentContext c = ctxWithMeta(Map.of(AgentScopePolicy.META_ALLOWED_TOOLS, List.of("calculator", "date_time")));
        assertTrue(p.evaluate(http, Map.of(), c).isDeny(), "tool outside agent whitelist must be denied");
    }

    @Test
    void agentWhitelistAllowsListedTool() {
        AgentScopePolicy p = new AgentScopePolicy();
        Tool calc = tool("calculator", RiskLevel.SAFE, false);
        AgentContext c = ctxWithMeta(Map.of(AgentScopePolicy.META_ALLOWED_TOOLS, List.of("calculator")));
        assertTrue(p.evaluate(calc, Map.of(), c).isAllow());
    }

    @Test
    void riskCeilingBlocksHigherLevelTools() {
        AgentScopePolicy p = new AgentScopePolicy();
        AgentContext c = ctxWithMeta(Map.of(AgentScopePolicy.META_RISK_CEILING, "READ"));
        // NETWORK/WRITE/DESTRUCTIVE all exceed a READ ceiling
        assertTrue(p.evaluate(tool("http_client", RiskLevel.NETWORK, false), Map.of(), c).isDeny());
        assertTrue(p.evaluate(tool("file_write", RiskLevel.WRITE, false), Map.of(), c).isDeny());
        assertTrue(p.evaluate(tool("code_executor", RiskLevel.DESTRUCTIVE, false), Map.of(), c).isDeny());
        // SAFE and READ are within the ceiling
        assertTrue(p.evaluate(tool("calculator", RiskLevel.SAFE, false), Map.of(), c).isAllow());
        assertTrue(p.evaluate(tool("file_read", RiskLevel.READ, false), Map.of(), c).isAllow());
    }

    @Test
    void topCeilingAllowsEverything() {
        AgentScopePolicy p = new AgentScopePolicy();
        AgentContext c = ctxWithMeta(Map.of(AgentScopePolicy.META_RISK_CEILING, "DESTRUCTIVE"));
        assertTrue(p.evaluate(tool("code_executor", RiskLevel.DESTRUCTIVE, false), Map.of(), c).isAllow());
    }

    @Test
    void noAgentMetadataMeansNoExtraRestriction() {
        AgentScopePolicy p = new AgentScopePolicy();
        assertTrue(p.evaluate(tool("anything", RiskLevel.DESTRUCTIVE, false), Map.of(), ctx).isAllow());
    }

    @Test
    void agentScopeNarrowsButNeverWidensDeploymentPolicy() {
        // Deployment blacklists code_executor; a READ-ceiling agent also can't reach NETWORK.
        ToolPolicyChain c = new ToolPolicyChain(List.of(
            new ToolListPolicy(ToolListPolicy.Mode.BLACKLIST, Set.of(), Set.of("code_executor")),
            new AgentScopePolicy(),
            new RiskLevelPolicy()));
        AgentContext readOnly = ctxWithMeta(Map.of(AgentScopePolicy.META_RISK_CEILING, "READ"));
        // blacklisted -> deny even though agent would otherwise allow
        assertTrue(c.evaluate(tool("code_executor", RiskLevel.DESTRUCTIVE, true), Map.of(), readOnly).isDeny());
        // within agent scope but blacklisted tool name via whitelist
        AgentContext calcOnly = ctxWithMeta(Map.of(AgentScopePolicy.META_ALLOWED_TOOLS, List.of("calculator")));
        assertTrue(c.evaluate(tool("http_client", RiskLevel.NETWORK, false), Map.of(), calcOnly).isDeny());
    }
}
