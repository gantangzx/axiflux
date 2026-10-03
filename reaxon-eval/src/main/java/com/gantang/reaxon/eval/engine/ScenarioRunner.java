package com.gantang.reaxon.eval.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.agent.AgentEvent;
import com.gantang.reaxon.api.config.LiveSettings;
import com.gantang.reaxon.api.llm.LlmClient;
import com.gantang.reaxon.api.llm.ModelRouter;
import com.gantang.reaxon.api.tool.policy.ToolPolicyChain;
import com.gantang.reaxon.eval.assertion.TraceAssertions;
import com.gantang.reaxon.impl.tool.policy.AgentScopePolicy;
import com.gantang.reaxon.impl.tool.policy.NetworkEgressPolicy;
import com.gantang.reaxon.impl.tool.policy.RiskLevelPolicy;
import com.gantang.reaxon.impl.tool.policy.ScopePolicy;
import com.gantang.reaxon.impl.tool.policy.ToolCallThrottlePolicy;
import com.gantang.reaxon.impl.tool.policy.ToolListPolicy;
import com.gantang.reaxon.eval.grading.GradeResult;
import com.gantang.reaxon.eval.grading.LlmJudgeGrader;
import com.gantang.reaxon.eval.scenario.Scenario;
import com.gantang.reaxon.eval.trace.Trace;
import com.gantang.reaxon.eval.trace.TraceRecorderHook;
import com.gantang.reaxon.impl.agent.ReactiveAgent;
import com.gantang.reaxon.impl.approval.DefaultApprovalManager;
import com.gantang.reaxon.impl.approval.InMemoryApprovalStore;
import com.gantang.reaxon.impl.memory.DefaultContextAssembler;
import com.gantang.reaxon.impl.tool.DefaultToolRegistry;
import com.gantang.reaxon.impl.tool.builtin.SpawnTaskTool;

import java.util.ArrayList;
import java.util.List;

/**
 * Executes a {@link Scenario} against a fully wired {@link ReactiveAgent}.
 *
 * <p>Two modes (see docs/eval-harness-design.md §3.4):
 * <ul>
 *   <li><b>replay</b> (default, zero API cost): a {@link ReplayLlmClient}
 *       returns scripted model steps from the YAML. CI regression net for
 *       framework behavior (tool loop, policy chain, compaction, hooks).</li>
 *   <li><b>live</b> (scenario {@code mode: live} + a real {@link LlmClient}
 *       via {@link Builder#liveClient}): the real model drives the run through
 *       a {@link RecordingLlmClient}; tools stay scripted fakes (deterministic
 *       environment, KC-Bench style). Deterministic assertions still run; an
 *       optional LLM judge ({@link Builder#judgeClient} + scenario rubric)
 *       scores quality without gating hard-pass.</li>
 * </ul>
 */
public class ScenarioRunner {

    private final LlmClient liveClient;
    private final LlmClient judgeClient;
    private final Integer toolResultMaxChars;
    private final boolean sideStore;

    /** Replay-only runner (back-compat with M1/M2 tests). */
    public ScenarioRunner() {
        this(null, null, null, false);
    }

    private ScenarioRunner(LlmClient liveClient, LlmClient judgeClient,
                           Integer toolResultMaxChars, boolean sideStore) {
        this.liveClient = liveClient;
        this.judgeClient = judgeClient;
        this.toolResultMaxChars = toolResultMaxChars;
        this.sideStore = sideStore;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private LlmClient liveClient;
        private LlmClient judgeClient;
        private Integer toolResultMaxChars;
        private boolean sideStore;

        /** Real model for live scenarios; typically wrapped in a RecordingLlmClient. */
        public Builder liveClient(LlmClient client) {
            this.liveClient = client;
            return this;
        }

        /** Cheap model used by the LLM judge for live-scenario rubric grading. */
        public Builder judgeClient(LlmClient client) {
            this.judgeClient = client;
            return this;
        }

        /**
         * Override the tool-result prune cap. A very large value disables pruning,
         * which is how the context-budget benchmark models the "before P1-3" baseline;
         * leave unset to use the production defaults.
         */
        public Builder toolResultMaxChars(int maxChars) {
            this.toolResultMaxChars = maxChars;
            return this;
        }

        /**
         * Wire the P1-3 side store, so oversized results are parked out of context
         * behind a {@code ref://tool-result/<id>} handle instead of being dropped.
         * Off by default to keep the 90+ bundled scenarios on their existing wiring.
         */
        public Builder sideStore(boolean enabled) {
            this.sideStore = enabled;
            return this;
        }

        public ScenarioRunner build() {
            return new ScenarioRunner(liveClient, judgeClient, toolResultMaxChars, sideStore);
        }
    }

    /**
     * Run result.
     *
     * @param trace        observable trajectory (assertions/judging read only this)
     * @param failures     deterministic assertion failures; empty = hard pass
     * @param recording    recorded model calls for live mode (null in replay mode)
     * @param grades       LLM-judge scores for live mode with a rubric (empty otherwise)
     * @param userMessages every user message actually sent, in order — including
     *                     user-simulator branch replies in live multi-turn scenarios
     */
    public record Result(
            Trace trace,
            List<String> failures,
            RecordingLlmClient recording,
            List<GradeResult> grades,
            List<String> userMessages
    ) {
        public Result {
            failures = failures == null ? List.of() : List.copyOf(failures);
            grades = grades == null ? List.of() : List.copyOf(grades);
            userMessages = userMessages == null ? List.of() : List.copyOf(userMessages);
        }

        public boolean passed() {
            return failures.isEmpty();
        }

        /** Mean judge score (1-5), or 0 when there are no grades. */
        public double meanJudgeScore() {
            return grades.stream().filter(g -> !g.error())
                    .mapToInt(GradeResult::score)
                    .average().orElse(0.0);
        }
    }

    public Result run(Scenario scenario) {
        ObjectMapper om = new ObjectMapper();

        DefaultToolRegistry registry = new DefaultToolRegistry();
        SideEffectStore effects = new SideEffectStore();
        for (Scenario.ToolStub stub : scenario.tools()) {
            registry.register(new FakeTool(stub, effects));
        }
        // Optional built-in sub-agent delegation tool, backed by a scripted
        // spawner (fan-out regression: the model can hand off a background task
        // and relay the handle without waiting on a real child run).
        if (Boolean.TRUE.equals(scenario.spawnTool())) {
            FakeBackgroundSpawner spawner = new FakeBackgroundSpawner(effects);
            registry.register(new SpawnTaskTool(() -> spawner));
        }

        com.gantang.reaxon.impl.session.InMemorySessionManager sessions =
            new com.gantang.reaxon.impl.session.InMemorySessionManager();
        // Optional deterministic memory backend (P1-4 LongMemEval-style scenarios).
        EvalMemoryStore memoryStore = scenario.memories().isEmpty()
            ? null : new EvalMemoryStore().seed(scenario.memories());
        DefaultContextAssembler assembler = new DefaultContextAssembler(memoryStore, 50, 5);

        boolean live = scenario.live() && liveClient != null;
        final RecordingLlmClient recording;
        final ModelRouter router;
        if (live) {
            recording = new RecordingLlmClient(liveClient);
            router = ctx -> recording;
        } else {
            recording = null;
            if (scenario.live()) {
                throw new IllegalStateException(
                        "Scenario '" + scenario.id() + "' is mode: live but no live LlmClient "
                                + "was supplied — build the runner with ScenarioRunner.builder()"
                                + ".liveClient(...), or run it in replay mode.");
            }
            List<Scenario.ModelStep> script = new ArrayList<>();
            for (Scenario.Turn turn : scenario.turns()) {
                script.addAll(turn.model());
            }
            int window = scenario.contextWindowTokens() != null
                    ? scenario.contextWindowTokens() : 128_000;
            ReplayLlmClient replay = new ReplayLlmClient(script, window);
            // Optional backup provider: exercises the model-router fallback chain
            // (UNAVAILABLE/AUTH errors → next provider). Always answers with a
            // fixed final text and never throws.
            LlmClient backup = scenario.fallbackModelResponse() != null
                    ? new ConstantLlmClient(scenario.fallbackModelResponse()) : null;
            router = new ModelRouter() {
                @Override public LlmClient route(com.gantang.reaxon.api.llm.RoutingContext ctx) { return replay; }
                @Override public List<LlmClient> routeChain(com.gantang.reaxon.api.llm.RoutingContext ctx) {
                    return backup != null ? List.of(replay, backup) : List.of(replay);
                }
            };
        }

        TraceRecorderHook hook = new TraceRecorderHook();
        ReactiveAgent agent = new ReactiveAgent(router, registry, assembler, memoryStore, sessions, om)
                .withHooks(List.of(hook));
        if ("default".equalsIgnoreCase(scenario.policyChain())) {
            agent.withToolPolicyChain(defaultPolicyChain());
        }
        // Context-engineering knobs (benchmark only; unset = production defaults).
        if (toolResultMaxChars != null) {
            agent.withToolResultMaxChars(toolResultMaxChars);
        }
        if (sideStore) {
            agent.withToolResultStore(new com.gantang.reaxon.impl.tool.support.InMemoryToolResultStore());
        }
        if (scenario.llmCallTimeoutSeconds() != null && scenario.llmCallTimeoutSeconds() > 0) {
            agent.withLlmCallTimeout(java.time.Duration.ofSeconds(scenario.llmCallTimeoutSeconds()));
        }
        // Optional approval channel for human-in-the-loop scenarios: a real
        // DefaultApprovalManager whose decision is scripted by the scenario
        // ("approve"/"reject"); null/"none" leaves the manager unset, which is
        // the fail-closed default (ASK → rejection, covered by scenario 08).
        DefaultApprovalManager approvalManager = null;
        if (scenario.approval() != null && !scenario.approval().isBlank()
                && !"none".equalsIgnoreCase(scenario.approval())) {
            approvalManager = new DefaultApprovalManager(new InMemoryApprovalStore(),
                    java.time.Duration.ofSeconds(60), java.time.Duration.ofHours(1));
            agent.withApprovalManager(approvalManager);
        }

        List<String> eventTypes = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> userMessages = new ArrayList<>();
        String sessionId = "eval-" + scenario.id();

        // Optional long-history seed (compaction scenarios need old turns to summarize).
        if (!live && scenario.seedHistoryTurns() != null && scenario.seedHistoryTurns() > 0) {
            com.gantang.reaxon.api.session.Session seeded =
                    sessions.getOrCreate(sessionId, "eval-user", "default", java.util.Map.of());
            for (int i = 0; i < scenario.seedHistoryTurns(); i++) {
                seeded.addUserMessage(("历史问题内容编号" + i + "，包含关键事实与背景。").repeat(40),
                        java.util.Map.of());
                seeded.addAssistantMessage(("历史回答内容编号" + i + "，给出明确结论。").repeat(40),
                        java.util.List.of());
            }
        }

        for (Scenario.Turn turn : scenario.turns()) {
            sendUserMessage(agent, scenario, recording, live, sessionId, turn.user(),
                    eventTypes, errors, userMessages, approvalManager);

            // User-simulator branches (live only): after the agent replies, fire
            // the first matching branch and evaluate again, until nothing matches.
            if (live && turn.branches() != null && !turn.branches().isEmpty()) {
                java.util.Set<Integer> fired = new java.util.HashSet<>();
                int depth = 0;
                while (depth++ < MAX_BRANCH_DEPTH) {
                    String reply = hook.lastResponse() != null ? hook.lastResponse() : "";
                    Scenario.Branch match = null;
                    List<Scenario.Branch> branches = turn.branches();
                    for (int bi = 0; bi < branches.size(); bi++) {
                        if (fired.contains(bi)) continue;
                        if (branchMatches(branches.get(bi), reply)) {
                            match = branches.get(bi);
                            fired.add(bi);
                            break;
                        }
                    }
                    if (match == null || match.say() == null || match.say().isBlank()) break;
                    sendUserMessage(agent, scenario, recording, live, sessionId, match.say(),
                            eventTypes, errors, userMessages, approvalManager);
                }
            }
        }

        Trace trace = new Trace(
                scenario.id(),
                hook.lastStatus() != null ? hook.lastStatus()
                        : (errors.isEmpty() ? "UNKNOWN" : "ERROR"),
                hook.lastResponse() != null ? hook.lastResponse() : "",
                eventTypes,
                hook.toolInvocations(),
                errors,
                hook.modelCallCount(),
                hook.turnResponses(),
                hook.promptCharsPerCall(),
                hook.peakPromptChars(),
                hook.peakPromptTokens()
        );

        List<String> failures = new ArrayList<>(TraceAssertions.evaluate(trace, scenario.expect()));
        if (memoryStore != null) {
            failures.addAll(evaluateMemoryExpectations(scenario, memoryStore));
        }

        List<GradeResult> grades = List.of();
        if (live && scenario.grading() != null && !scenario.grading().rubric().isEmpty()) {
            if (judgeClient != null) {
                LlmJudgeGrader.Outcome outcome =
                        LlmJudgeGrader.grade(scenario.grading(), scenario, trace, judgeClient, userMessages);
                grades = outcome.results();
                if (outcome.error() != null) {
                    // Judge failure is reported, never fatal (design doc §6).
                    grades = new ArrayList<>(grades);
                    grades.add(GradeResult.failed("judge unavailable: " + outcome.error()));
                }
            } else {
                grades = List.of(GradeResult.failed(
                        "scenario declares a grading rubric but no judge client was supplied"));
            }
        }

        return new Result(trace, failures, recording, grades, userMessages);
    }

    // ─── P1-4 memory expectations ──────────────────────────────────────────────

    /**
     * LongMemEval/LoCoMo-style retrieval assertions, evaluated after every turn
     * against the deterministic memory store. Recall sees active facts only;
     * storedContains/NotContains sees the audit view (including expired rows).
     */
    private static List<String> evaluateMemoryExpectations(Scenario scenario, EvalMemoryStore store) {
        List<String> failures = new ArrayList<>();
        Scenario.Expect expect = scenario.expect();
        for (Scenario.RecallExpect re : expect.recall()) {
            String user = re.user() != null && !re.user().isBlank()
                ? re.user() : EvalMemoryStore.DEFAULT_USER;
            List<com.gantang.reaxon.api.memory.ScoredMemory> raw;
            try {
                raw = store.searchScored(user, re.query(), 5).block();
            } catch (Exception e) {
                failures.add("[memory recall '" + re.query() + "'] store failed: " + e.getMessage());
                continue;
            }
            // Mirror the production context gate (DefaultContextAssembler, 0.30):
            // only hits the agent would actually inject are allowed to match.
            final List<com.gantang.reaxon.api.memory.ScoredMemory> hits = raw.stream()
                .filter(h -> !h.hasScore()
                    || h.score() >= com.gantang.reaxon.impl.memory.DefaultContextAssembler.DEFAULT_MEMORY_SCORE_THRESHOLD)
                .toList();
            String joined = hits.stream().map(com.gantang.reaxon.api.memory.ScoredMemory::item)
                .map(com.gantang.reaxon.api.memory.MemoryItem::content)
                .collect(java.util.stream.Collectors.joining("\n"));
            for (String needle : re.contains()) {
                if (!joined.contains(needle)) {
                    failures.add("[memory recall '" + re.query() + "'] expected active hit containing '"
                        + needle + "' but got:\n" + joined);
                }
            }
            for (String needle : re.notContains()) {
                if (joined.contains(needle)) {
                    failures.add("[memory recall '" + re.query() + "'] expired/superseded/foreign fact '"
                        + needle + "' must not be retrieved; got:\n" + joined);
                }
            }
        }
        String stored = store.getAll(EvalMemoryStore.DEFAULT_USER).block().stream()
            .map(com.gantang.reaxon.api.memory.MemoryItem::content)
            .collect(java.util.stream.Collectors.joining("\n"));
        for (String needle : expect.storedContains()) {
            if (!stored.contains(needle)) {
                failures.add("[memory store] expected retained row (audit view) containing '" + needle + "'");
            }
        }
        for (String needle : expect.storedNotContains()) {
            if (stored.contains(needle)) {
                failures.add("[memory store] did not expect any row containing '" + needle + "'");
            }
        }
        return failures;
    }

    /** Max simulated follow-up messages per declared turn (loop safety). */
    private static final int MAX_BRANCH_DEPTH = 5;

    private void sendUserMessage(ReactiveAgent agent, Scenario scenario,
                                 RecordingLlmClient recording, boolean live,
                                 String sessionId, String userMessage,
                                 List<String> eventTypes, List<String> errors,
                                 List<String> userMessages,
                                 DefaultApprovalManager approvalManager) {
        if (live) {
            recording.markTurn();
        }
        userMessages.add(userMessage);
        AgentContext ctx = AgentContext.builder()
                .sessionId(sessionId)
                .userId("eval-user")
                .currentQuery(userMessage)
                .systemPrompt(scenario.systemPrompt() != null ? scenario.systemPrompt() : "")
                // Eval scenarios exercise tool-execution defenses (SSRF, injection,
                // memory, ...), so they must run as an authorized caller. Since the
                // toolsec ScopePolicy fix, a context with no scope metadata is
                // fail-closed (scope-gated tools denied before reaching the guard
                // under test). Grant the wildcard so the scenario reaches the actual
                // defense layer it asserts on.
                .metadata(java.util.Map.of(ScopePolicy.META_SCOPES, java.util.List.of("*")))
                .build();
        try {
            agent.processStream(ctx)
                    .doOnNext(ev -> {
                        eventTypes.add(ev.type().name());
                        if (approvalManager != null
                                && ev.type() == AgentEvent.Type.APPROVAL_REQUIRED) {
                            autoDecide(approvalManager, scenario.approval(), ev.callId());
                        }
                    })
                    .blockLast();
        } catch (Exception e) {
            errors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Simulate the human decision for approval-gated replay scenarios. Runs off
     * the turn thread ({@code approve}/{@code reject} block on the reactive
     * store) and retries briefly: the APPROVAL_REQUIRED event is emitted just
     * before the request is registered with the manager, so deciding immediately
     * can race the waiter registration.
     */
    private static void autoDecide(DefaultApprovalManager manager, String mode, String callId) {
        boolean approve = !"reject".equalsIgnoreCase(mode);
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                boolean done = approve
                        ? manager.approve(callId, "eval-approver")
                        : manager.reject(callId, "eval-approver", "rejected by eval scenario");
                if (done) return;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    /**
     * Branch condition: fires when the reply contains <b>any</b> {@code ifContains}
     * needle (alternative phrasings are OR-ed — use multiple branches for AND)
     * and none of the {@code ifNotContains} needles. An empty {@code ifContains}
     * list is a catch-all (subject to ifNotContains). All matching is
     * whitespace-insensitive for Chinese typesetting drift.
     */
    private static boolean branchMatches(Scenario.Branch branch, String reply) {
        String normalized = reply == null ? "" : reply.replaceAll("\\s+", "");
        for (String needle : branch.ifNotContains()) {
            if (normalized.contains(needle.replaceAll("\\s+", ""))) return false;
        }
        if (branch.ifContains().isEmpty()) return true;
        for (String needle : branch.ifContains()) {
            if (normalized.contains(needle.replaceAll("\\s+", ""))) return true;
        }
        return false;
    }

    /** Backup provider for fallback scenarios: always returns the same text. */
    private static final class ConstantLlmClient implements LlmClient {
        private final String text;

        ConstantLlmClient(String text) {
            this.text = text;
        }

        @Override public String provider() { return "constant-backup"; }
        @Override public String primaryModel() { return "backup-model"; }

        @Override
        public com.gantang.reaxon.api.llm.CompletionResponse complete(
                com.gantang.reaxon.api.llm.CompletionRequest request) {
            return com.gantang.reaxon.api.llm.CompletionResponse.builder().content(text)
                    .finishReason("stop").model(primaryModel()).build();
        }

        @Override
        public reactor.core.publisher.Flux<String> completeStream(
                com.gantang.reaxon.api.llm.CompletionRequest request) {
            return reactor.core.publisher.Flux.fromArray(text.split("(?<= )"));
        }

        @Override
        public com.gantang.reaxon.api.llm.CompletionResponse completeWithTools(
                com.gantang.reaxon.api.llm.CompletionRequest request, com.fasterxml.jackson.databind.JsonNode tools) {
            return com.gantang.reaxon.api.llm.CompletionResponse.builder().content(text)
                    .toolCalls(java.util.List.of()).finishReason("stop").model(primaryModel()).build();
        }
    }

    /**
     * The same six-layer fail-closed chain the Spring auto-configuration wires
     * in production (ToolList → AgentScope → Scope → RiskLevel → Throttle →
     * NetworkEgress), with permissive-default LiveSettings so scenarios only
     * exercise the policies their assertions target.
     */
    public static ToolPolicyChain defaultPolicyChain() {
        LiveSettings live = new LiveSettings(LiveSettings.builder().build());
        return new ToolPolicyChain(List.of(
                new ToolListPolicy(live),
                new AgentScopePolicy(),
                new ScopePolicy(false),
                new RiskLevelPolicy(),
                new ToolCallThrottlePolicy(),
                new NetworkEgressPolicy(live)
        ));
    }
}
