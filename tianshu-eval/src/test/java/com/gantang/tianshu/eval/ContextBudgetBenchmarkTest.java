package com.gantang.tianshu.eval;

import com.gantang.tianshu.eval.engine.ScenarioRunner;
import com.gantang.tianshu.eval.scenario.Scenario;
import com.gantang.tianshu.eval.scenario.ScenarioLoader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Quantifies the roadmap acceptance gate "long coding session context token peak
 * drops vs baseline" for P1-3 (tool-result pruning + side store).
 *
 * <p>Runs one identical scripted session twice, changing nothing but the
 * context-engineering wiring, and compares the prompt high-water mark:
 * <ul>
 *   <li><b>baseline</b>: prune cap raised far beyond any result, so every full
 *       30k-char tool output accumulates in the transcript — this is the
 *       pre-P1-3 behavior.</li>
 *   <li><b>treatment</b>: production defaults (12k cap) plus the side store, so
 *       oversized results are parked behind a {@code ref://tool-result/<id>}
 *       handle and stay retrievable instead of merely being dropped.</li>
 * </ul>
 *
 * <p>Replay mode only: zero API cost, deterministic, safe as a CI gate.
 *
 * <p><b>Scope.</b> This measures P1-3. P1-2 (sub-agent context isolation) is a
 * structurally different lever — a child session's transcript never enters the
 * parent context at all — and is covered by its own tests; it is deliberately
 * not folded into this number, because mixing a FakeSpawner's canned summary
 * into the comparison would inflate the result without evidence.
 */
class ContextBudgetBenchmarkTest {

    private static final String BENCH =
            "eval-bench/bench-context-budget-long-coding.yaml";

    /** Cap far above any single result: nothing is ever pruned. */
    private static final int NO_PRUNING = 100_000_000;

    /**
     * Roadmap gate. The doc carries two numbers: >=30% in the P1-2 section and
     * >=40% in the overall acceptance baseline ("P1-2 + P1-3 合计"). Gate on the
     * stricter one; measured drop from P1-3 alone is ~67%, so there is headroom.
     */
    private static final double REQUIRED_DROP = 0.40;

    @Test
    void contextEngineeringCutsThePeakPromptByAtLeastFortyPercent() {
        Scenario scenario = ScenarioLoader.fromResource(BENCH);

        ScenarioRunner.Result baseline = ScenarioRunner.builder()
                .toolResultMaxChars(NO_PRUNING)
                .build()
                .run(scenario);
        ScenarioRunner.Result treated = ScenarioRunner.builder()
                .sideStore(true)
                .build()
                .run(scenario);

        // Both arms must complete the same workload, or the comparison is meaningless.
        assertTrue(baseline.passed(), () -> "baseline run failed: " + baseline.failures());
        assertTrue(treated.passed(), () -> "treatment run failed: " + treated.failures());
        assertEquals(baseline.trace().toolInvocations().size(),
                treated.trace().toolInvocations().size(),
                "both arms must run the same number of tool calls");
        assertEquals(baseline.trace().modelCallCount(), treated.trace().modelCallCount(),
                "both arms must make the same number of model calls");

        int basePeak = baseline.trace().peakPromptChars();
        int treatedPeak = treated.trace().peakPromptChars();
        int baseTokens = baseline.trace().peakPromptTokens();
        int treatedTokens = treated.trace().peakPromptTokens();

        // Non-vacuity guard: the baseline must genuinely be carrying several full
        // 30k-char results. If pruning silently applied to the baseline too, this
        // whole benchmark would measure nothing — that trap is real, because
        // ToolResultPruner ignores a raised cap for tools that have their own profile.
        assertTrue(basePeak > 100_000,
                "baseline peak should hold multiple untruncated 30k results, was " + basePeak);

        double charDrop = 1.0 - (double) treatedPeak / basePeak;
        double tokenDrop = 1.0 - (double) treatedTokens / baseTokens;

        System.out.printf(
                "[context-budget] peak chars %d -> %d (-%.1f%%) | peak tokens %d -> %d (-%.1f%%)%n",
                basePeak, treatedPeak, charDrop * 100,
                baseTokens, treatedTokens, tokenDrop * 100);

        assertTrue(charDrop >= REQUIRED_DROP, () -> String.format(
                "peak prompt chars must drop >=%.0f%%: %d -> %d (%.1f%%)",
                REQUIRED_DROP * 100, basePeak, treatedPeak, charDrop * 100));
        assertTrue(tokenDrop >= REQUIRED_DROP, () -> String.format(
                "peak prompt tokens must drop >=%.0f%%: %d -> %d (%.1f%%)",
                REQUIRED_DROP * 100, baseTokens, treatedTokens, tokenDrop * 100));
    }

    /** The parked content must stay reachable, not silently lost. */
    @Test
    void prunedResultsAreParkedBehindARetrievableHandle() {
        Scenario scenario = ScenarioLoader.fromResource(BENCH);
        ScenarioRunner.Result treated = ScenarioRunner.builder()
                .sideStore(true)
                .build()
                .run(scenario);

        assertTrue(treated.passed(), () -> "treatment run failed: " + treated.failures());
        String firstResult = treated.trace().toolInvocations().get(0).result();
        assertTrue(firstResult.contains("truncated"),
                "oversized result should be marked truncated");
        assertTrue(firstResult.contains("ref://tool-result/"),
                () -> "truncated result must carry a retrieval handle, got: "
                        + firstResult.substring(Math.max(0, firstResult.length() - 300)));
    }

    /** The new metric itself must be wired, one sample per model call. */
    @Test
    void promptSizeIsRecordedForEveryModelCall() {
        Scenario scenario = ScenarioLoader.fromResource(BENCH);
        ScenarioRunner.Result r = ScenarioRunner.builder().sideStore(true).build().run(scenario);

        assertEquals(r.trace().modelCallCount(), r.trace().promptCharsPerCall().size(),
                "one prompt-size sample per model call");
        assertTrue(r.trace().promptCharsPerCall().stream().allMatch(n -> n > 0),
                "every recorded prompt must have non-zero size");
        assertEquals(r.trace().promptCharsPerCall().stream().mapToInt(Integer::intValue).max().orElse(0),
                r.trace().peakPromptChars(),
                "peak must equal the max recorded sample");
        // A coding session grows: the last prompt must exceed the first.
        var samples = r.trace().promptCharsPerCall();
        assertTrue(samples.get(samples.size() - 1) > samples.get(0),
                "transcript should accumulate across turns");
    }
}
