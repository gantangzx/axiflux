package com.gantang.tianshu.impl.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.agent.BackgroundSpawner;
import com.gantang.tianshu.api.agent.DelegationRequest;
import com.gantang.tianshu.api.auth.CallerIdentity;
import com.gantang.tianshu.api.tool.Tool;
import com.gantang.tianshu.api.tool.ToolResult;
import com.gantang.tianshu.api.tool.policy.RiskLevel;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Built-in tool: spawn an isolated sub-agent to handle a self-contained task.
 *
 * <p>This is the agentic "delegation" primitive, mirroring Tianshu's sub-agent
 * spawn: the model hands a well-scoped sub-task to a fresh agent session with its
 * own short-term memory. Delegation is <em>asynchronous</em> — the tool submits
 * the child run and returns a handle at once, so the parent turn finishes quickly
 * and the parent conversation keeps accepting user input while the child works.
 * When the child completes, its result is injected back into the parent session
 * and the parent is (re)invoked to aggregate it; that aggregated summary is
 * streamed to the client over the background-event channel.
 *
 * <p>The tool resolves {@link BackgroundSpawner} lazily through a {@link Supplier}
 * so the tool (registered into the ToolRegistry at startup) does not create a hard
 * circular dependency with the Agent that owns the registry.
 */
public class SpawnTaskTool implements Tool {

    private final Supplier<BackgroundSpawner> spawnerSupplier;

    public SpawnTaskTool(Supplier<BackgroundSpawner> spawnerSupplier) {
        this.spawnerSupplier = spawnerSupplier;
    }

    /**
     * Scope-attenuation mode for spawned sub-agents (audit toolsec P2-8).
     *
     * <p><b>Risk statement.</b> By default a child agent inherits the parent's
     * scopes <em>unchanged</em> — including {@code *} — and may itself spawn
     * grand-children up to {@code MAX_SPAWN_DEPTH} with the same grant. A
     * prompt-injected parent can therefore launder its full privileges into an
     * unmonitored side session. The existing gates are: DESTRUCTIVE risk level
     * (human approval per spawn), the {@code agent:spawn} scope requirement,
     * and the recursion-depth cap. Deployments that need privilege decay along
     * the delegation chain should run with
     * {@code tianshu.auth.require-explicit-scopes=true} (no implicit wildcard)
     * and grant parents the narrowest scope set possible; a configurable
     * per-depth attenuation list is a documented follow-up.
     */
    enum ScopeAttenuation {
        /** Child scopes = parent scopes (default; preserves console behaviour). */
        INHERIT,
        /** Child scopes = parent scopes minus the wildcard ("*" is dropped). */
        DROP_WILDCARD
    }

    /**
     * Active attenuation mode. Read once from the
     * {@code TIANSHU_SPAWN_SCOPE_ATTENUATION} environment variable /
     * {@code tianshu.spawn.scope-attenuation} system property
     * ({@code drop-wildcard} selects {@link ScopeAttenuation#DROP_WILDCARD}).
     * Kept as a process-level knob rather than a per-call parameter: privilege
     * policy must not be model-controllable.
     */
    static volatile ScopeAttenuation scopeAttenuation = loadAttenuation();

    private static ScopeAttenuation loadAttenuation() {
        String v = System.getProperty("tianshu.spawn.scope-attenuation");
        if (v == null || v.isBlank()) {
            v = System.getenv("TIANSHU_SPAWN_SCOPE_ATTENUATION");
        }
        return (v != null && v.trim().equalsIgnoreCase("drop-wildcard"))
            ? ScopeAttenuation.DROP_WILDCARD
            : ScopeAttenuation.INHERIT;
    }

    /**
     * Apply the configured attenuation to the parent's scope set. The result is
     * always a subset of the parent's scopes — a child can never hold a
     * privilege the parent did not have.
     */
    static Set<String> attenuateScopes(Set<String> parentScopes) {
        if (parentScopes == null || parentScopes.isEmpty()) return Set.of();
        Set<String> out = new LinkedHashSet<>(parentScopes);
        if (scopeAttenuation == ScopeAttenuation.DROP_WILDCARD) {
            out.remove(CallerIdentity.WILDCARD);
        }
        return java.util.Collections.unmodifiableSet(out);
    }

    private static final JsonNode SCHEMA = SchemaSupport.parse("""
        {
          "type": "object",
          "properties": {
            "task": {
              "type": "string",
              "description": "A complete, self-contained instruction for the sub-agent. Include all needed context, the objective, and the expected output. The sub-agent cannot see this conversation. It runs in the background; you do not block on it."
            },
            "mode": {
              "type": "string",
              "enum": ["single", "parallel_critique"],
              "description": "Orchestration mode. 'single' (default) runs one sub-agent. 'parallel_critique' runs N independent sub-agents in parallel on the same task, then a critic agent scores their answers and returns the best one or a synthesis — use ONLY for high-value, hard-to-verify work (architecture/design decisions, complex debugging, competing approaches), because it costs about N+1x tokens."
            },
            "n": {
              "type": "integer",
              "minimum": 2,
              "maximum": 5,
              "description": "Number of parallel answerer branches when mode=parallel_critique. Default 3. Ignored in single mode."
            }
          },
          "required": ["task"]
        }
        """);

    /**
     * Delegation spawns a fresh agent that acts on the caller's behalf: the
     * {@link RiskLevel} taxonomy classifies sub-agent delegation as DESTRUCTIVE,
     * which forces a human-approval ASK in {@code RiskLevelPolicy} (the budget
     * auto-approval policy never covers DESTRUCTIVE) and additionally requires
     * the {@code agent:spawn} scope via {@code ScopePolicy}. We deliberately do
     * <em>not</em> set {@code requiresApproval()}: the risk level already drives
     * the same ASK, avoiding two overlapping gates on one tool.
     */
    @Override public RiskLevel riskLevel() { return RiskLevel.DESTRUCTIVE; }

    @Override public String name()        { return "spawn_task"; }
    @Override public String description() {
        return "Spawn an isolated sub-agent to perform a self-contained task "
             + "(research, analysis, or a multi-step chore) in a fresh, background "
             + "session. Returns immediately with a task handle; the sub-agent runs "
             + "asynchronously and its result is later aggregated back into this "
             + "conversation automatically. Use for independent work that should not "
             + "clutter the current conversation or block further user input. The "
             + "sub-agent cannot see this conversation, so give it full context. "
             + "SECURITY: the sub-agent inherits your exact tool scopes (including "
             + "wildcard, if you hold it) and can itself spawn further sub-agents up "
             + "to the depth cap — never delegate tasks involving untrusted content "
             + "when you hold broad scopes.";
    }
    @Override public JsonNode parameters() { return SCHEMA; }
    @Override public String group()        { return "builtin"; }

    @Override
    public ToolResult execute(String callId, Map<String, Object> params, AgentContext context) {
        String task = Optional.ofNullable(params.get("task"))
            .map(String::valueOf).map(String::trim).orElse("");
        if (task.isEmpty()) {
            return ToolResult.failure(callId, "task is required");
        }

        BackgroundSpawner spawner = spawnerSupplier.get();
        if (spawner == null) {
            return ToolResult.failure(callId, "Background sub-agent spawning is not available in this runtime");
        }

        String userId = context != null && context.userId() != null ? context.userId() : "anonymous";
        String parentSessionId = context != null ? context.sessionId() : "unknown";

        // Recursion guard: a session at depth d may spawn a child at d+1, up to
        // MAX_SPAWN_DEPTH. Prevents sub-agents indefinitely spawning more sub-agents.
        int parentDepth = 0;
        if (context != null && context.metadata() != null) {
            Object raw = context.metadata().get(BackgroundSpawner.META_SPAWN_DEPTH);
            if (raw instanceof Number n) {
                parentDepth = n.intValue();
            } else if (raw != null) {
                try { parentDepth = Integer.parseInt(String.valueOf(raw).trim()); }
                catch (NumberFormatException ignored) { parentDepth = 0; }
            }
        }
        int childDepth = parentDepth + 1;
        if (childDepth > BackgroundSpawner.MAX_SPAWN_DEPTH) {
            return ToolResult.failure(callId,
                "Sub-agent nesting limit (depth " + BackgroundSpawner.MAX_SPAWN_DEPTH
                + ") reached. This sub-agent cannot spawn another nested sub-agent; "
                + "perform the task directly or break it into smaller steps at this level.");
        }

        String mode = Optional.ofNullable(params.get("mode"))
            .map(String::valueOf).map(String::trim).orElse("single");
        int n = DelegationRequest.DEFAULT_CRITIQUE_N;
        Object rawN = params.get("n");
        if (rawN instanceof Number num) {
            n = num.intValue();
        } else if (rawN != null && !String.valueOf(rawN).isBlank()) {
            try { n = Integer.parseInt(String.valueOf(rawN).trim()); }
            catch (NumberFormatException ignored) { n = DelegationRequest.DEFAULT_CRITIQUE_N; }
        }
        boolean critique = "parallel_critique".equalsIgnoreCase(mode);
        if (n < DelegationRequest.MIN_CRITIQUE_N) n = DelegationRequest.MIN_CRITIQUE_N;
        if (n > DelegationRequest.MAX_CRITIQUE_N) n = DelegationRequest.MAX_CRITIQUE_N;

        try {
            DelegationRequest req = critique
                ? new DelegationRequest(task, inheritedCaller(userId, context),
                    parentSessionId, childDepth, DelegationRequest.Mode.CRITIQUE, n)
                : new DelegationRequest(task, inheritedCaller(userId, context),
                    parentSessionId, childDepth);
            BackgroundSpawner.SpawnHandle handle = spawner.spawnAsync(req);
            String note = critique
                ? "Background best-of-" + n + " critique started (taskId=" + handle.taskId()
                    + "): " + n + " answerer sub-agents run in parallel, then a critic selects the best "
                    + "answer or synthesizes one. It costs about " + (n + 1) + "x tokens of a single run. "
                    + "Runs asynchronously; the final answer is aggregated back into this conversation "
                    + "when done. Tell the user briefly that the task is running and they can keep chatting."
                : "Background sub-agent started (taskId=" + handle.taskId()
                    + ", childSession=" + handle.childSessionId() + "). It runs asynchronously; "
                    + "its result will be aggregated back into this conversation automatically when "
                    + "done. Do NOT wait or poll — tell the user briefly that the task is running in "
                    + "the background and you will summarize the result as soon as it completes, and "
                    + "that they can keep chatting in the meantime.";
            return ToolResult.success(callId, note, Map.of(
                "taskId", handle.taskId(),
                "childSessionId", handle.childSessionId(),
                "status", handle.status() != null ? handle.status() : "running",
                "mode", critique ? "parallel_critique" : "single",
                "n", critique ? n : 1,
                "async", true
            ));
        } catch (Exception e) {
            return ToolResult.failure(callId, "Failed to spawn background sub-agent: " + e.getMessage());
        }
    }

    /**
     * The identity the child inherits: exactly the scopes injected into the parent
     * turn by the transport layer, never more.
     *
     * <p>When the parent turn carries no scope grant at all we propagate an empty
     * set rather than a wildcard. That keeps the strict/lenient decision in one
     * place ({@code ScopePolicy}) instead of quietly minting full access here —
     * spawning is exactly the path a prompt-injected agent would use to escape a
     * narrow grant.
     */
    private static CallerIdentity inheritedCaller(String userId, AgentContext context) {
        if (context == null || context.metadata() == null) {
            return new CallerIdentity(userId, Set.of());
        }
        Object raw = context.metadata().get(CallerIdentity.META_SCOPES);
        if (!(raw instanceof Collection<?> c)) {
            return new CallerIdentity(userId, Set.of());
        }
        Set<String> scopes = new LinkedHashSet<>();
        for (Object o : c) {
            if (o != null) {
                String s = String.valueOf(o).trim();
                if (!s.isEmpty()) scopes.add(s);
            }
        }
        // Audit toolsec P2-8: apply the configured attenuation — the child never
        // holds anything beyond a subset of the parent's scopes.
        Set<String> attenuated = attenuateScopes(scopes);
        if (attenuated.size() != scopes.size()) {
            org.slf4j.LoggerFactory.getLogger(SpawnTaskTool.class).info(
                "spawn_task: scope attenuation dropped {} entr{} for child of user={} (mode={})",
                scopes.size() - attenuated.size(),
                scopes.size() - attenuated.size() == 1 ? "y" : "ies",
                userId, scopeAttenuation);
        }
        return new CallerIdentity(userId, attenuated);
    }
}
