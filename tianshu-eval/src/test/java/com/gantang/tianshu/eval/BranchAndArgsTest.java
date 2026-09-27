package com.gantang.tianshu.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.gantang.tianshu.api.llm.CompletionRequest;
import com.gantang.tianshu.api.llm.CompletionResponse;
import com.gantang.tianshu.api.llm.LlmClient;
import com.gantang.tianshu.eval.engine.ScenarioRunner;
import com.gantang.tianshu.eval.freeze.FreezeWriter;
import com.gantang.tianshu.eval.scenario.Scenario;
import com.gantang.tianshu.eval.scenario.ScenarioLoader;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M4 mechanics: live user-simulator branches (clarifying-question follow-ups)
 * and tool-argument assertions. A scripted client stands in for the real model.
 */
class BranchAndArgsTest {

    /**
     * Scenario: the agent must ask WHEN before scheduling; the branch fires on
     * "时间/几点", supplies "明天下午3点，叫上王总", and the agent then calls the
     * scheduling tool with the extracted arguments.
     */
    private static final String BRANCH_YAML = """
            id: branch-clarify-then-schedule
            name: 信息不全先反问，补齐后再调工具
            mode: live
            systemPrompt: 你是日程助手。信息不全时必须先向用户确认，不要编造时间或参会人。
            tools:
              - name: schedule_meeting
                description: 在日历上创建会议
                parameters:
                  type: object
                  properties:
                    title: {type: string, description: 会议标题}
                    time: {type: string, description: 会议时间}
                    attendees: {type: string, description: 参会人}
                  required: [title, time]
                returns: "已创建日程：项目评审会，明天下午3点，参会人：王总。"
            turns:
              - user: "帮我安排一个项目评审会"
                branches:
                  - ifContains: ["时间", "几点"]
                    say: "明天下午 3 点，叫上王总。"
                model:
                  - text: "好的，请问会议安排在什么时间？还有谁参加？"
                  - toolCalls:
                      - name: schedule_meeting
                        args: {title: "项目评审会", time: "明天下午3点", attendees: "王总"}
                  - text: "已安排：明天下午 3 点的项目评审会，已邀请王总。"
            expect:
              finalContains: ["3 点"]
              toolCalled:
                - name: schedule_meeting
                  times: 1
                  fromTurn: 2
                  argsContains:
                    title: "评审"
                    time: "3点"
                    attendees: "王"
              toolNotCalled: ["email_send"]
              eventsNone: ["ERROR"]
              status: SUCCESS
            """;

    private static LlmClient scriptedLiveModel(Scenario scenario) {
        List<Scenario.ModelStep> script = scenario.turns().stream()
                .flatMap(t -> t.model().stream())
                .toList();
        return new com.gantang.tianshu.eval.engine.ReplayLlmClient(script);
    }

    @Test
    void branch_firesOnClarifyingQuestion_andSuppliesMissingInfo() {
        Scenario scenario = ScenarioLoader.fromYaml(BRANCH_YAML);
        ScenarioRunner.Result result = ScenarioRunner.builder()
                .liveClient(scriptedLiveModel(scenario))
                .build()
                .run(scenario);

        assertTrue(result.passed(), () -> String.join("\n", result.failures()));

        // Two user messages were actually sent: the original + the branch reply.
        assertEquals(2, result.userMessages().size());
        assertEquals("帮我安排一个项目评审会", result.userMessages().get(0));
        assertTrue(result.userMessages().get(1).contains("3 点"));

        // Two recording turns; the tool call happens after the branch reply.
        assertNotNull(result.recording());
        assertEquals(1, result.recording().callsForTurn(1).size(),
                "turn 1: clarifying question only");
        assertEquals(2, result.recording().callsForTurn(2).size(),
                "turn 2: tool call + final text");
        assertEquals(2, result.trace().turnResponses().size(),
                "one assistant reply recorded per user turn");
        assertTrue(result.trace().turnResponses().get(0).contains("时间"),
                "turn 1 reply is the clarifying question");
        assertEquals(1, result.recording().callsForTurn(2).get(0).toolCalls().size());
        assertEquals("schedule_meeting",
                result.recording().callsForTurn(2).get(0).toolCalls().get(0).name());
    }

    @Test
    void argsContains_isWhitespaceInsensitive() {
        // Model emits spaced "明天下午 3 点"; assertion needle "3点" still matches.
        String yaml = BRANCH_YAML
                .replace("time: \"明天下午3点\"", "time: \"明天下午 3 点\"")
                .replace("attendees: \"王总\"", "attendees: \"王 总\"");
        Scenario scenario = ScenarioLoader.fromYaml(yaml);
        ScenarioRunner.Result result = ScenarioRunner.builder()
                .liveClient(scriptedLiveModel(scenario))
                .build()
                .run(scenario);
        assertTrue(result.passed(), () -> String.join("\n", result.failures()));
    }

    @Test
    void argsMismatch_isReportedNotThrown() {
        // Tool called, but with a different title than the assertion expects.
        String yaml = BRANCH_YAML.replace("项目评审会", "茶话会");
        Scenario scenario = ScenarioLoader.fromYaml(yaml);
        ScenarioRunner.Result result = ScenarioRunner.builder()
                .liveClient(scriptedLiveModel(scenario))
                .build()
                .run(scenario);
        assertFalse(result.passed());
        assertTrue(result.failures().stream()
                        .anyMatch(f -> f.contains("unexpected arguments") && f.contains("schedule_meeting")),
                "expected an args mismatch failure, got: " + result.failures());
    }

    @Test
    void noMatchingBranch_runsSingleTurn() {
        // Agent answers everything up front (no 时间/几点 in reply) → branch never fires.
        String yaml = BRANCH_YAML
                .replace("好的，请问会议安排在什么时间？还有谁参加？",
                         "好的，请补充会议的具体安排信息。");
        Scenario scenario = ScenarioLoader.fromYaml(yaml);
        ScenarioRunner.Result result = ScenarioRunner.builder()
                .liveClient(scriptedLiveModel(scenario))
                .build()
                .run(scenario);
        assertEquals(1, result.userMessages().size(), "no branch → no follow-up message");
    }

    @Test
    void toolCalledBeforeInfoTurn_failsFromTurnAssertion() {
        // Model calls the scheduling tool immediately on turn 1 (hallucinating
        // details) instead of asking — fromTurn must flag it even though the
        // tool name/args would otherwise match.
        String yaml = """
                id: early-tool-call
                name: 首轮信息不全就调工具必须判失败
                mode: live
                systemPrompt: 你是日程助手，信息不全先反问。
                tools:
                  - name: schedule_meeting
                    description: 创建会议
                    parameters:
                      type: object
                      properties:
                        title: {type: string}
                        time: {type: string}
                    returns: "已创建"
                turns:
                  - user: "帮我安排一个项目评审会"
                    branches:
                      - ifContains: ["几点", "时间"]
                        say: "明天下午 3 点。"
                    model:
                      - toolCalls:
                          - name: schedule_meeting
                            args: {title: "项目评审会", time: "明天下午3点"}
                      - text: "已安排明天下午 3 点的项目评审会。"
                expect:
                  toolCalled:
                    - name: schedule_meeting
                      times: 1
                      fromTurn: 2
                """;
        Scenario scenario = ScenarioLoader.fromYaml(yaml);
        ScenarioRunner.Result result = ScenarioRunner.builder()
                .liveClient(scriptedLiveModel(scenario))
                .build()
                .run(scenario);
        assertFalse(result.passed());
        assertTrue(result.failures().stream()
                        .anyMatch(f -> f.contains("must not be called before turn 2")),
                "expected a fromTurn failure, got: " + result.failures());
    }

    @Test
    void frozenBranchRun_carriesSimulatedUserMessages() {
        Scenario scenario = ScenarioLoader.fromYaml(BRANCH_YAML);
        ScenarioRunner.Result result = ScenarioRunner.builder()
                .liveClient(scriptedLiveModel(scenario))
                .build()
                .run(scenario);
        assertTrue(result.passed());

        String frozenYaml = FreezeWriter.toReplayYaml(
                scenario, result.trace(), result.recording(), result.userMessages());
        Scenario frozen = ScenarioLoader.fromYaml(frozenYaml);
        assertFalse(frozen.live());
        assertEquals(2, frozen.turns().size(), "frozen scenario has one turn per executed user message");
        assertTrue(frozen.turns().get(1).user().contains("3 点"),
                "the simulated follow-up becomes a declared replay turn");
        // Replays deterministically through the replay runner.
        ScenarioRunner.Result replayed = new ScenarioRunner().run(frozen);
        assertTrue(replayed.passed(), () -> String.join("\n", replayed.failures()));
    }
}
