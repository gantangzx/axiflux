package com.gantang.reaxon.eval.scenario;

import java.util.List;
import java.util.Map;

/**
 * Declarative eval scenario, normally loaded from YAML (see {@link ScenarioLoader}).
 *
 * <p>A scenario wires: fake tools ({@link ToolStub}), a scripted model dialogue
 * ({@link Turn} / {@link ModelStep}), and expected observable outcomes
 * ({@link Expect}). The runner ({@code com.gantang.reaxon.eval.engine.ScenarioRunner})
 * executes it against a real {@code ReactiveAgent} with zero LLM API cost.
 */
public record Scenario(
        String id,
        String name,
        String systemPrompt,
        String policyChain,          // null/none = no chain; "default" = production 6-layer chain
        Integer contextWindowTokens, // replay model context window (small values force compaction)
        Integer seedHistoryTurns,    // pre-seed N long user/assistant turns before running
        String fallbackModelResponse, // replay backup-provider response (non-null = exercise fallback chain)
        List<ToolStub> tools,
        List<Turn> turns,
        Expect expect,
        String mode,                 // null/"replay" = scripted model; "live" = real LlmClient
        GradingSpec grading,         // live mode only: LLM-judge rubric (null = no grading)
        String approval,             // null/"none" = no ApprovalManager (ASK auto-rejects);
                                     // "approve"/"reject" = wire a manager and auto-decide
        Boolean spawnTool,           // register the built-in spawn_task tool backed by a
                                     // scripted BackgroundSpawner (sub-agent fan-out regression)
        Integer llmCallTimeoutSeconds, // replay watchdog cap on one model call (short values
                                     // exercise the LLM-timeout → provider-fallback path)
        List<MemorySeed> memories    // P1-4: seed the deterministic eval memory store before turns
) {
    public Scenario {
        tools = tools == null ? List.of() : List.copyOf(tools);
        turns = turns == null ? List.of() : List.copyOf(turns);
        memories = memories == null ? List.of() : List.copyOf(memories);
    }

    /**
     * A preloaded long-term memory for a scenario (P1-4 LongMemEval-style set).
     * {@code user} defaults to the scenario's eval-user; use another id for
     * multi-user isolation checks. {@code expired}=true seeds an already-invalid
     * fact (valid_until in the past); {@code validUntilHours} sets an explicit TTL
     * (negative = already expired).
     */
    public record MemorySeed(
            String user,
            String content,
            String summary,
            List<String> tags,
            Integer importance,
            Boolean expired,
            Integer validUntilHours
    ) {
        public MemorySeed {
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }

    /** True when this scenario drives a real model through a RecordingLlmClient. */
    public boolean live() {
        return "live".equalsIgnoreCase(mode);
    }

    /**
     * A fake tool. {@code returns} gives a fixed reply; {@code error} makes it
     * fail. For long outputs, {@code repeatReturns} repeated {@code repeatTimes}
     * times exercises the ToolResultPruner.
     */
    public record ToolStub(
            String name,
            String description,
            String returns,
            String error,
            String repeatReturns,
            Integer repeatTimes,
            Boolean requiresApproval,
            // JSON Schema for the tool's parameters (type/properties/required).
            // Live scenarios need this so the real model supplies real arguments —
            // without it the fake advertises no parameters and gets empty args.
            Map<String, Object> parameters
    ) {
        public ToolStub {
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        }
    }

    /** One user turn; {@code model} scripts every LLM call within this turn. */
    public record Turn(String user, List<ModelStep> model, List<Branch> branches) {
        public Turn {
            model = model == null ? List.of() : List.copyOf(model);
            branches = branches == null ? List.of() : List.copyOf(branches);
        }
    }

    /**
     * User-simulator branch (live mode only, KC-Bench style): after the agent
     * replies, the first branch whose conditions match the reply fires — the
     * runner sends {@code say} as the next user message and evaluates again.
     * {@code ifContains} needles are OR-ed (alternative phrasings of the same
     * condition; use multiple branches for AND); {@code ifNotContains} are all
     * vetoes. Each branch fires at most once per turn; evaluation stops when no
     * branch matches (or the safety cap is reached).
     */
    public record Branch(List<String> ifContains, List<String> ifNotContains, String say) {
        public Branch {
            ifContains = ifContains == null ? List.of() : List.copyOf(ifContains);
            ifNotContains = ifNotContains == null ? List.of() : List.copyOf(ifNotContains);
        }
    }

    /**
     * One scripted LLM response.
     * <ul>
     *   <li>{@code error} non-blank → the call fails: {@code overflow} (context
     *       length, recovered by compaction), {@code unavailable} (connection
     *       refused, recovered by provider fallback), {@code hang} (the call
     *       wedges forever; abandoned after {@code llmCallTimeoutSeconds} and
     *       recovered by provider fallback), {@code auth},
     *       {@code overload}, or any raw message;</li>
     *   <li>{@code toolCalls} non-empty → the model requests these tool calls;</li>
     *   <li>otherwise {@code text} is the response content.</li>
     * </ul>
     */
    public record ModelStep(String text, List<ToolCallRef> toolCalls, String error) {
        public ModelStep {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }
    }

    public record ToolCallRef(String name, Map<String, Object> args) {
        public ToolCallRef {
            args = args == null ? Map.of() : Map.copyOf(args);
        }
    }

    /** Expected outcomes; every populated list/field is asserted. */
    public record Expect(
            List<String> finalContains,
            List<String> finalNotContains,
            List<ToolCallExpect> toolCalled,
            List<String> toolNotCalled,
            List<String> toolOrder,
            List<ToolResultExpect> toolResult,
            List<String> eventsContains,
            List<String> eventsNone,
            String status,
            List<RecallExpect> recall,
            List<String> storedContains,
            List<String> storedNotContains
    ) {
        public Expect {
            finalContains = finalContains == null ? List.of() : List.copyOf(finalContains);
            finalNotContains = finalNotContains == null ? List.of() : List.copyOf(finalNotContains);
            toolCalled = toolCalled == null ? List.of() : List.copyOf(toolCalled);
            toolNotCalled = toolNotCalled == null ? List.of() : List.copyOf(toolNotCalled);
            toolOrder = toolOrder == null ? List.of() : List.copyOf(toolOrder);
            toolResult = toolResult == null ? List.of() : List.copyOf(toolResult);
            eventsContains = eventsContains == null ? List.of() : List.copyOf(eventsContains);
            eventsNone = eventsNone == null ? List.of() : List.copyOf(eventsNone);
            recall = recall == null ? List.of() : List.copyOf(recall);
            storedContains = storedContains == null ? List.of() : List.copyOf(storedContains);
            storedNotContains = storedNotContains == null ? List.of() : List.copyOf(storedNotContains);
        }
    }

    /**
     * P1-4: assert what the memory gate retrieves for {@code query} after all
     * turns run (active facts only — expired/superseded must never come back).
     * {@code user} defaults to the scenario's eval-user.
     */
    public record RecallExpect(
            String query,
            String user,
            List<String> contains,
            List<String> notContains
    ) {
        public RecallExpect {
            contains = contains == null ? List.of() : List.copyOf(contains);
            notContains = notContains == null ? List.of() : List.copyOf(notContains);
        }
    }

    /**
     * Expect a tool to be called exactly {@code times} times (times null = ≥1).
     * {@code argsContains} optionally asserts that at least one matching call
     * carried each parameter whose string value contains the given substring
     * (whitespace-normalized for natural-language values like "明天下午 3 点").
     * {@code fromTurn} (1-based) asserts the tool is not called before that
     * user turn — e.g. {@code fromTurn: 2} pins "do not act before the user
     * supplied missing information" in user-simulator scenarios.
     */
    public record ToolCallExpect(String name, Integer times, Map<String, String> argsContains,
                                 Integer fromTurn) {
        public ToolCallExpect {
            argsContains = argsContains == null ? Map.of() : Map.copyOf(argsContains);
        }
    }

    /** Expect at least one result for {@code name} matching content/error conditions. */
    public record ToolResultExpect(String name, String resultContains, Boolean isError) {}

    /**
     * LLM-judge rubric for live scenarios (see docs/eval-harness-design.md §3.4).
     * Judge scores never gate CI hard-pass; they are reported for quality trends.
     */
    public record GradingSpec(List<RubricItem> rubric, Double passScore) {
        public GradingSpec {
            rubric = rubric == null ? List.of() : List.copyOf(rubric);
        }
    }

    /** One rubric line: {@code criterion} is shown to the judge; {@code weight} defaults to 1. */
    public record RubricItem(String criterion, Integer weight) {}
}
