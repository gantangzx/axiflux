package com.gantang.tianshu.eval.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.tianshu.eval.engine.ScenarioRunner;
import com.gantang.tianshu.eval.grading.GradeResult;
import com.gantang.tianshu.eval.scenario.Scenario;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Aggregated eval result across a batch of scenarios: pass rate, token usage,
 * latency distribution and judge scores, rendered as Markdown (human) or JSON
 * (machine / trend tracking).
 */
public record EvalReport(
        String generatedAt,
        String mode,
        List<ScenarioOutcome> outcomes
) {

    public EvalReport {
        outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
    }

    public record ScenarioOutcome(
            String id,
            String name,
            String mode,
            boolean passed,
            List<String> failures,
            List<GradeResult> grades,
            Double judgeScore,   // weighted mean 1-5, null when no grades
            int inputTokens,
            int outputTokens,
            long durationMs,
            String frozenTo      // frozen replay file path when a live failure was frozen
    ) {
        public ScenarioOutcome {
            failures = failures == null ? List.of() : List.copyOf(failures);
            grades = grades == null ? List.of() : List.copyOf(grades);
        }

        /** Build an outcome from a runner result; token/latency come from the recording/wall clock. */
        public static ScenarioOutcome of(Scenario scenario, ScenarioRunner.Result result,
                                         long durationMs, String frozenTo) {
            int inTokens = 0, outTokens = 0;
            if (result.recording() != null) {
                inTokens = result.recording().totalInputTokens();
                outTokens = result.recording().totalOutputTokens();
            }
            return new ScenarioOutcome(
                    scenario.id(),
                    scenario.name(),
                    scenario.live() ? "live" : "replay",
                    result.passed(),
                    result.failures(),
                    result.grades(),
                    weightedScore(scenario, result),
                    inTokens, outTokens, durationMs, frozenTo);
        }

        private static Double weightedScore(Scenario scenario, ScenarioRunner.Result result) {
            if (result.grades().stream().noneMatch(g -> !g.error())) {
                return null;
            }
            double weightSum = 0, scoreSum = 0;
            for (GradeResult g : result.grades()) {
                if (g.error() || g.index() < 0) continue;
                int weight = 1;
                if (scenario.grading() != null && g.index() < scenario.grading().rubric().size()) {
                    Integer w = scenario.grading().rubric().get(g.index()).weight();
                    if (w != null && w > 0) weight = w;
                }
                weightSum += weight;
                scoreSum += g.score() * weight;
            }
            return weightSum > 0 ? scoreSum / weightSum : null;
        }
    }

    public static EvalReport of(String mode, List<ScenarioOutcome> outcomes) {
        return new EvalReport(OffsetDateTime.now().toString(), mode, outcomes);
    }

    public int totalCount() { return outcomes.size(); }
    public int passedCount() { return (int) outcomes.stream().filter(ScenarioOutcome::passed).count(); }
    public int failedCount() { return totalCount() - passedCount(); }
    public double passRate() { return outcomes.isEmpty() ? 0 : (double) passedCount() / totalCount(); }
    public int totalInputTokens() { return outcomes.stream().mapToInt(ScenarioOutcome::inputTokens).sum(); }
    public int totalOutputTokens() { return outcomes.stream().mapToInt(ScenarioOutcome::outputTokens).sum(); }
    public long totalDurationMs() { return outcomes.stream().mapToLong(ScenarioOutcome::durationMs).sum(); }

    /** p95 scenario latency (nearest-rank on the sorted durations). */
    public long p95DurationMs() {
        if (outcomes.isEmpty()) return 0;
        List<Long> sorted = outcomes.stream().map(ScenarioOutcome::durationMs).sorted().toList();
        int idx = (int) Math.ceil(0.95 * sorted.size()) - 1;
        return sorted.get(Math.max(0, idx));
    }

    /** Mean judge score across outcomes that have one, or 0 when none were graded. */
    public double meanJudgeScore() {
        return outcomes.stream()
                .map(ScenarioOutcome::judgeScore)
                .filter(s -> s != null)
                .mapToDouble(Double::doubleValue)
                .average().orElse(0.0);
    }

    public String toJson() {
        try {
            return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render eval report JSON: " + e.getMessage(), e);
        }
    }

    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Eval Report\n\n");
        sb.append("- Generated: ").append(generatedAt).append('\n');
        sb.append("- Mode: ").append(mode).append('\n');
        sb.append("- Scenarios: ").append(totalCount()).append(" total, ")
          .append(passedCount()).append(" passed, ").append(failedCount())
          .append(" failed (").append(String.format("%.1f%%", passRate() * 100)).append(")\n");
        if (totalInputTokens() + totalOutputTokens() > 0) {
            sb.append("- Tokens: ").append(totalInputTokens()).append(" in / ")
              .append(totalOutputTokens()).append(" out\n");
            sb.append("- Latency: total ").append(seconds(totalDurationMs()))
              .append("s, p95 ").append(seconds(p95DurationMs())).append("s\n");
        }
        if (meanJudgeScore() > 0) {
            sb.append("- Judge mean score: ").append(String.format("%.2f", meanJudgeScore()))
              .append("/5 (informational, not gating)\n");
        }

        sb.append("\n## Results\n\n");
        sb.append("| Scenario | Mode | Result | Judge | Tokens in/out | Duration |\n");
        sb.append("|---|---|---|---|---|---|\n");
        for (ScenarioOutcome o : outcomes) {
            sb.append("| ").append(o.id()).append(" | ").append(o.mode())
              .append(" | ").append(o.passed() ? "✅ PASS" : "❌ FAIL")
              .append(" | ").append(o.judgeScore() != null
                      ? String.format("%.2f/5", o.judgeScore()) : "—")
              .append(" | ").append(o.inputTokens()).append('/').append(o.outputTokens())
              .append(" | ").append(seconds(o.durationMs())).append("s |\n");
        }

        List<ScenarioOutcome> failed = outcomes.stream().filter(o -> !o.passed()).toList();
        if (!failed.isEmpty()) {
            sb.append("\n## Failures\n");
            for (ScenarioOutcome o : failed) {
                sb.append("\n### ").append(o.id()).append(" — ").append(o.name()).append('\n');
                for (String f : o.failures()) {
                    sb.append("- ").append(f).append('\n');
                }
                if (o.frozenTo() != null) {
                    sb.append("- 🔒 Frozen to replay: `").append(o.frozenTo()).append("`\n");
                }
            }
        }

        List<ScenarioOutcome> graded = outcomes.stream()
                .filter(o -> !o.grades().isEmpty()).toList();
        if (!graded.isEmpty()) {
            sb.append("\n## Judge details\n");
            for (ScenarioOutcome o : graded) {
                sb.append("\n### ").append(o.id()).append('\n');
                for (GradeResult g : o.grades()) {
                    if (g.error()) {
                        sb.append("- ⚠️ ").append(g.reason()).append('\n');
                    } else {
                        sb.append("- [").append(g.score()).append("/5] ").append(g.criterion())
                          .append(" — ").append(g.reason()).append('\n');
                    }
                }
            }
        }

        return sb.toString();
    }

    private static String seconds(long ms) {
        return String.format("%.1f", ms / 1000.0);
    }
}
