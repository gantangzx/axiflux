package com.gantang.tianshu.eval.grading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.tianshu.api.llm.CompletionRequest;
import com.gantang.tianshu.api.llm.LlmClient;
import com.gantang.tianshu.api.session.Message;
import com.gantang.tianshu.eval.scenario.Scenario;
import com.gantang.tianshu.eval.trace.Trace;

import java.util.ArrayList;
import java.util.List;

/**
 * Live-mode quality grading by a cheap LLM judge.
 *
 * <p>The judge sees the full dialogue (every user message — including user-
 * simulator branch replies — interleaved with each agent reply), the agent's
 * final reply, and the tool trajectory, and scores every rubric line 1-5 with
 * a short reason, answering JSON only. Per the design doc, judge scores
 * <b>never gate hard-pass</b>: a flaky judge must not redden CI; scores are
 * reported for trend tracking.
 * A judge failure yields a single {@link GradeResult#failed} entry rather than
 * throwing.
 */
public final class LlmJudgeGrader {

    private LlmJudgeGrader() {}

    public record Outcome(List<GradeResult> results, String judgeModel, String error) {
        public Outcome {
            results = results == null ? List.of() : List.copyOf(results);
        }

        /** Mean of the non-error scores (no weighting); 0 when nothing was scored. */
        public double meanScore() {
            return results.stream().filter(r -> !r.error())
                    .mapToInt(GradeResult::score)
                    .average().orElse(0.0);
        }
    }

    public static Outcome grade(Scenario.GradingSpec spec, Scenario scenario,
                                Trace trace, LlmClient judge) {
        return grade(spec, scenario, trace, judge, null);
    }

    /**
     * @param executedUserMessages every user message actually sent (including
     *                             user-simulator branch replies); null = fall
     *                             back to the scenario's declared turns
     */
    public static Outcome grade(Scenario.GradingSpec spec, Scenario scenario,
                                Trace trace, LlmClient judge,
                                List<String> executedUserMessages) {
        String judgeModel = judge.primaryModel();
        try {
            String prompt = buildPrompt(spec, scenario, trace, executedUserMessages);
            CompletionRequest req = CompletionRequest.builder()
                    .model(judge.primaryModel())
                    .messages(List.of(
                            Message.system(JUDGE_SYSTEM),
                            Message.user(prompt)
                    ))
                    .temperature(0.1)
                    .maxTokens(2000)
                    .build();
            String raw = judge.complete(req).content();
            return new Outcome(parseScores(raw, spec), judgeModel, null);
        } catch (Exception e) {
            return new Outcome(List.of(GradeResult.failed(
                    e.getClass().getSimpleName() + ": " + e.getMessage())), judgeModel,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    static String buildPrompt(Scenario.GradingSpec spec, Scenario scenario, Trace trace) {
        return buildPrompt(spec, scenario, trace, null);
    }

    static String buildPrompt(Scenario.GradingSpec spec, Scenario scenario, Trace trace,
                              List<String> executedUserMessages) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 评分标准\n");
        List<Scenario.RubricItem> rubric = spec.rubric();
        for (int i = 0; i < rubric.size(); i++) {
            sb.append(i).append(". ").append(rubric.get(i).criterion()).append('\n');
        }

        sb.append("\n## 对话过程（按实际顺序，含 agent 的每轮回复）\n");
        List<String> userMessages = (executedUserMessages != null && !executedUserMessages.isEmpty())
                ? executedUserMessages
                : scenario.turns().stream().map(Scenario.Turn::user).toList();
        List<String> agentReplies = trace.turnResponses();
        for (int i = 0; i < userMessages.size(); i++) {
            sb.append(i + 1).append(". 用户：").append(userMessages.get(i)).append('\n');
            if (i < agentReplies.size()) {
                sb.append("   Agent：").append(abbreviate(agentReplies.get(i), 600)).append('\n');
            }
        }

        sb.append("\n## Agent 最终回复\n").append(
                trace.finalResponse() != null && !trace.finalResponse().isBlank()
                        ? trace.finalResponse() : "(空)");

        sb.append("\n\n## 工具调用轨迹\n");
        if (trace.toolInvocations().isEmpty()) {
            sb.append("(未调用任何工具)\n");
        } else {
            for (Trace.ToolInvocation inv : trace.toolInvocations()) {
                sb.append("#").append(inv.order() + 1).append(' ')
                        .append(inv.name()).append('(');
                try {
                    sb.append(new ObjectMapper().writeValueAsString(inv.args()));
                } catch (Exception e) {
                    sb.append(inv.args());
                }
                sb.append(") → ").append(inv.success() ? "成功" : "失败");
                String detail = inv.success() ? inv.result() : inv.error();
                if (detail != null && !detail.isBlank()) {
                    sb.append(": ").append(abbreviate(detail, 300));
                }
                sb.append('\n');
            }
        }

        sb.append("\n请对每条标准打分（1-5 整数，5 最好），并给简短中文理由。\n")
          .append("只输出 JSON，格式：{\"grades\":[{\"i\":0,\"score\":4,\"reason\":\"...\"}]}，")
          .append("不要输出 JSON 以外的任何内容。");
        return sb.toString();
    }

    /**
     * Extract {@code {"grades":[...]}} from the judge reply with tolerance for
     * prose/fences around it. Package-private for testing.
     */
    static List<GradeResult> parseScores(String raw, Scenario.GradingSpec spec) {
        List<GradeResult> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return List.of(GradeResult.failed("judge returned empty response"));
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return List.of(GradeResult.failed("judge response contained no JSON: "
                    + abbreviate(raw, 200)));
        }
        try {
            JsonNode root = new ObjectMapper().readTree(raw.substring(start, end + 1));
            JsonNode grades = root.get("grades");
            if (grades == null || !grades.isArray()) {
                return List.of(GradeResult.failed("judge JSON missing grades array: "
                        + abbreviate(raw, 200)));
            }
            List<Scenario.RubricItem> rubric = spec.rubric();
            for (JsonNode g : grades) {
                int i = g.path("i").asInt(-1);
                if (i < 0 || i >= rubric.size()) continue;
                int score = g.path("score").asInt(0);
                if (score < 1) continue;
                String reason = g.path("reason").asText("");
                out.add(GradeResult.of(i, rubric.get(i).criterion(), score, reason));
            }
            if (out.isEmpty()) {
                return List.of(GradeResult.failed("no valid score entries in judge JSON"));
            }
            return out;
        } catch (Exception e) {
            return List.of(GradeResult.failed("cannot parse judge JSON: " + e.getMessage()));
        }
    }

    private static String abbreviate(String s, int max) {
        s = s.strip();
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private static final String JUDGE_SYSTEM = """
            你是严格的 AI Agent 行为评分员。你根据给定的评分标准，对 agent 在用户任务中的实际表现
            （最终回复 + 工具调用轨迹）逐条打分。评分要客观、可复核：工具没调用就是没调用，回复
            没提到的事实不能脑补。只输出指定 JSON，不要输出任何其他内容。""";
}
