package com.gantang.tianshu.impl.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.tianshu.api.agent.AutoApprovalPolicy;
import com.gantang.tianshu.api.llm.ModelRouter;
import com.gantang.tianshu.api.observability.MetricsReporter;
import com.gantang.tianshu.impl.memory.DefaultContextAssembler;
import com.gantang.tianshu.impl.session.InMemorySessionManager;
import com.gantang.tianshu.impl.tool.DefaultToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Structural guard for {@link ReactiveAgent}'s configuration wiring.
 *
 * <p><b>The failure mode this exists for.</b> {@code ReactiveAgent} is configured
 * after construction through {@code withXxx()} witheres, and nine of those must
 * <em>also</em> forward the value into its {@link ToolExecutor}
 * ({@code toolExecutor.setXxx(...)}). Nothing in the type system enforces the
 * second write: forget it and the agent looks correctly configured while the
 * tool layer silently runs on defaults. That is not hypothetical — the
 * observability work (P2-2) shipped {@code withMetricsReporter} assigning only
 * the field, and every tool-level metric was lost with no test turning red.
 *
 * <p>Rather than duplicating the invariant in prose, this test discovers it:
 * every package-private {@code setXxx} on {@code ToolExecutor} must have a
 * matching wither on {@code ReactiveAgent} whose invocation is observable in
 * the executor's own state. Adding a new executor setter without wiring the
 * wither fails here immediately.
 *
 * <p>Not covered on purpose: agent-only witheres (hooks, token budget, max
 * iterations, LLM timeout, agent directory, skill registry). Hooks in
 * particular are intentionally <em>not</em> forwarded — the executor receives a
 * constructor-time callback that reads the live {@code hooks} field on each
 * invocation, so late registration already works.
 */
class ReactiveAgentConfigPropagationTest {

    private static final ObjectMapper OM = new ObjectMapper();

    /** Executor setter -> the wither expected to forward it. */
    private static final Map<String, String> WITHER_OF = Map.of(
        "setMetricsReporter", "withMetricsReporter",
        "setApprovalManager", "withApprovalManager",
        "setApprovalTimeout", "withApprovalTimeout",
        "setPolicyChain", "withToolPolicyChain",
        "setLiveSettings", "withLiveSettings",
        "setAutoApproveTools", "withAutoApproveTools",
        "setAutoApprovalPolicy", "withAutoApprovalPolicy",
        "setToolResultMaxChars", "withToolResultMaxChars",
        "setToolResultStore", "withToolResultStore",
        "setToolResultCache", "withToolResultCache");

    private static ReactiveAgent newAgent() {
        ModelRouter router = ctx -> null;
        return new ReactiveAgent(
            router,
            new DefaultToolRegistry(),
            new DefaultContextAssembler(null, 50, 5),
            null,
            new InMemorySessionManager(),
            OM);
    }

    private static ToolExecutor executorOf(ReactiveAgent agent) throws Exception {
        Field f = ReactiveAgent.class.getDeclaredField("toolExecutor");
        f.setAccessible(true);
        return (ToolExecutor) f.get(agent);
    }

    private static Object fieldOf(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    // ==================== the discovery half ====================

    @Test
    @DisplayName("every ToolExecutor setter is claimed by exactly one known wither")
    void executorSettersAreAllAccountedFor() {
        Set<String> actual = new TreeSet<>();
        for (Method m : ToolExecutor.class.getDeclaredMethods()) {
            if (m.getName().startsWith("set") && m.getReturnType() == void.class
                    && !m.isSynthetic()) {
                actual.add(m.getName());
            }
        }

        assertEquals(new TreeSet<>(WITHER_OF.keySet()), actual,
            """
            ToolExecutor's setter surface changed. A new setter here means \
            ReactiveAgent must forward it from a wither, otherwise the tool layer \
            silently runs on defaults. Wire the wither, then add the mapping to \
            WITHER_OF.""");

        // The other half of the contract: the withers must actually exist.
        List<String> missing = new ArrayList<>();
        for (String wither : WITHER_OF.values()) {
            boolean found = false;
            for (Method m : ReactiveAgent.class.getDeclaredMethods()) {
                if (m.getName().equals(wither)) {
                    found = true;
                    break;
                }
            }
            if (!found) missing.add(wither);
        }
        assertTrue(missing.isEmpty(), "missing witheres on ReactiveAgent: " + missing);
    }

    // ==================== the behavioural half ====================

    @Test
    void metricsReporterReachesTheToolLayer() throws Exception {
        // The exact regression from P2-2: field set, executor left on NOOP.
        MetricsReporter sentinel = new MetricsReporter() {};
        ReactiveAgent agent = newAgent();

        assertNotSame(sentinel, fieldOf(executorOf(agent), "metricsReporter"), "precondition");
        agent.withMetricsReporter(sentinel);

        assertSame(sentinel, fieldOf(executorOf(agent), "metricsReporter"),
            "withMetricsReporter must forward: tool-level metrics live in ToolExecutor");
    }

    @Test
    void policyChainReachesTheToolLayer() throws Exception {
        // Security-relevant: the chain is what denies/asks on tool calls.
        var chain = new com.gantang.tianshu.api.tool.policy.ToolPolicyChain(List.of());
        ReactiveAgent agent = newAgent();
        agent.withToolPolicyChain(chain);

        assertSame(chain, fieldOf(executorOf(agent), "toolPolicyChain"),
            "withToolPolicyChain must forward, or every tool call bypasses policy");
    }

    @Test
    void autoApprovalKnobsReachTheToolLayer() throws Exception {
        ReactiveAgent agent = newAgent();
        agent.withAutoApproveTools(Set.of("calculator"));
        agent.withAutoApprovalPolicy(AutoApprovalPolicy.NEVER);

        ToolExecutor ex = executorOf(agent);
        assertEquals(Set.of("calculator"), fieldOf(ex, "autoApproveTools"));
        assertSame(AutoApprovalPolicy.NEVER, fieldOf(ex, "autoApprovalPolicy"),
            "approval policy must forward: this is an authorization decision");
    }

    @Test
    void approvalManagerReachesTheToolLayer() throws Exception {
        var store = new com.gantang.tianshu.impl.approval.InMemoryApprovalStore();
        var mgr = new com.gantang.tianshu.impl.approval.DefaultApprovalManager(store);
        ReactiveAgent agent = newAgent();
        agent.withApprovalManager(mgr);

        assertSame(mgr, fieldOf(executorOf(agent), "approvalManager"));
    }

    @Test
    void approvalTimeoutReachesTheToolLayer() throws Exception {
        // P1-9: the failsafe ceiling for a stalled approval handshake.
        ReactiveAgent agent = newAgent();
        agent.withApprovalTimeout(java.time.Duration.ofMinutes(2));

        assertEquals(java.time.Duration.ofMinutes(2),
            fieldOf(executorOf(agent), "approvalTimeout"),
            "withApprovalTimeout must forward, or a stalled approval parks the turn on defaults");
    }

    @Test
    void toolResultSideStoreAndCacheReachTheToolLayer() throws Exception {
        var store = new com.gantang.tianshu.impl.tool.support.InMemoryToolResultStore();
        var cache = new com.gantang.tianshu.impl.tool.support.InMemoryToolResultCache(
            java.time.Duration.ofMinutes(10), 100, 20_000);
        ReactiveAgent agent = newAgent();
        agent.withToolResultStore(store);
        agent.withToolResultCache(cache, Set.of("calculator"));

        ToolExecutor ex = executorOf(agent);
        assertSame(store, fieldOf(ex, "toolResultStore"),
            "handles are minted in ToolExecutor; an unforwarded store means ref:// never resolves");
        assertSame(cache, fieldOf(ex, "toolResultCache"));
        assertEquals(Set.of("calculator"), fieldOf(ex, "cacheableTools"));
    }

    @Test
    void toolResultMaxCharsReachesThePruner() throws Exception {
        ReactiveAgent agent = newAgent();
        agent.withToolResultMaxChars(1234);

        Object pruner = fieldOf(executorOf(agent), "toolResultPruner");
        assertNotNull(pruner, "pruner must exist");
        assertEquals(1234, fieldOf(pruner, "maxChars"),
            "withToolResultMaxChars must reach the pruner, not just the agent");
    }

    @Test
    void liveSettingsReachTheToolLayer() throws Exception {
        // Hot-reloaded switches (kill switch, budget ceiling) are read tool-side.
        ReactiveAgent agent = newAgent();
        var live = new com.gantang.tianshu.api.config.LiveSettings(
            com.gantang.tianshu.api.config.LiveSettings.builder().build());
        agent.withLiveSettings(live);

        assertSame(live, fieldOf(executorOf(agent), "liveSettings"));
    }

    @Test
    @DisplayName("witheres stay chainable so wiring order cannot silently drop a call")
    void withersReturnTheSameInstance() {
        ReactiveAgent agent = newAgent();
        assertSame(agent, agent.withMetricsReporter(MetricsReporter.NOOP));
        assertSame(agent, agent.withMaxIterations(7));
        assertSame(agent, agent.withToolResultMaxChars(9999));
    }
}
