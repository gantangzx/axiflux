package com.gantang.tianshu.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.gantang.tianshu.api.llm.CompletionRequest;
import com.gantang.tianshu.api.llm.CompletionResponse;
import com.gantang.tianshu.api.llm.LlmClient;
import com.gantang.tianshu.api.tool.ToolCall;
import com.gantang.tianshu.eval.engine.RecordingLlmClient;
import com.gantang.tianshu.eval.engine.ReplayLlmClient;
import com.gantang.tianshu.eval.engine.ScenarioRunner;
import com.gantang.tianshu.eval.freeze.FreezeWriter;
import com.gantang.tianshu.eval.grading.GradeResult;
import com.gantang.tianshu.eval.scenario.Scenario;
import com.gantang.tianshu.eval.scenario.ScenarioLoader;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live-mode mechanics: a scripted client stands in for the real model, so the
 * recording → judging → freeze → replay loop is tested without API cost.
 */
class LiveModeTest {

    private static final String LIVE_YAML = """
            id: live-freeze-test
            name: live 录制与 freeze 回放
            mode: live
            systemPrompt: 你是助手，外部事实以工具返回为准。
            tools:
              - name: web_search
                description: 搜索最新规定
                returns: "2026 年新规：报销截止日为每月 8 号。"
            turns:
              - user: "这个月报销截止到几号？"
                model:
                  - toolCalls:
                      - name: web_search
                        args: {q: "报销截止日 2026"}
                  - text: "根据最新规定，报销截止到每月 8 号。"
            expect:
              finalContains: ["8 号"]
              toolCalled:
                - name: web_search
                  times: 1
            grading:
              rubric:
                - criterion: "是否调用了 web_search 核实"
                - criterion: "最终回答是否给出每月 8 号"
            """;

    /** The "real model" for the live run: a replay client fed the YAML's model steps. */
    private static LlmClient scriptedLiveModel(Scenario scenario) {
        List<Scenario.ModelStep> script = scenario.turns().stream()
                .flatMap(t -> t.model().stream())
                .toList();
        return new ReplayLlmClient(script);
    }

    private static LlmClient stubJudge(String jsonReply) {
        return new LlmClient() {
            @Override public String provider() { return "stub-judge"; }
            @Override public String primaryModel() { return "judge-x"; }
            @Override public CompletionResponse complete(CompletionRequest request) {
                return CompletionResponse.builder()
                        .content(jsonReply).finishReason("stop").model("judge-x").build();
            }
            @Override public Flux<String> completeStream(CompletionRequest request) {
                return Flux.just(jsonReply);
            }
            @Override public CompletionResponse completeWithTools(CompletionRequest request, JsonNode tools) {
                return complete(request);
            }
        };
    }

    @Test
    void liveRun_recordsCalls_runsAssertions_andGrades() {
        Scenario scenario = ScenarioLoader.fromYaml(LIVE_YAML);
        LlmClient liveModel = scriptedLiveModel(scenario);
        LlmClient judge = stubJudge("""
                {"grades":[
                  {"i":0,"score":5,"reason":"调用了 web_search 核实"},
                  {"i":1,"score":4,"reason":"明确回答每月 8 号"}]}
                """);

        ScenarioRunner.Result result = ScenarioRunner.builder()
                .liveClient(liveModel)
                .judgeClient(judge)
                .build()
                .run(scenario);

        assertTrue(result.passed(), () -> String.join("\n", result.failures()));
        assertNotNull(result.recording(), "live run must expose the recording");

        List<RecordingLlmClient.RecordedCall> turnCalls = result.recording().callsForTurn(1);
        assertEquals(2, turnCalls.size(), "two tool-loop model calls in the turn");
        assertEquals(1, turnCalls.get(0).toolCalls().size());
        assertEquals("web_search", turnCalls.get(0).toolCalls().get(0).name());
        assertEquals("报销截止日 2026", turnCalls.get(0).toolCalls().get(0).args().get("q"));
        assertTrue(turnCalls.get(1).toolCalls().isEmpty());
        assertTrue(turnCalls.get(1).content().contains("8 号"));
        assertTrue(turnCalls.stream().allMatch(c -> c.phase().equals("tool-loop")));

        List<GradeResult> grades = result.grades();
        assertEquals(2, grades.stream().filter(g -> !g.error()).count());
        assertEquals(4.5, result.meanJudgeScore(), 0.001);
    }

    @Test
    void judgeFailure_isReportedNotFatal() {
        Scenario scenario = ScenarioLoader.fromYaml(LIVE_YAML);
        LlmClient liveModel = scriptedLiveModel(scenario);
        LlmClient badJudge = new LlmClient() {
            @Override public String provider() { return "broken-judge"; }
            @Override public String primaryModel() { return "judge-broken"; }
            @Override public CompletionResponse complete(CompletionRequest request) {
                throw new RuntimeException("401: auth failed");
            }
            @Override public Flux<String> completeStream(CompletionRequest request) {
                return Flux.error(new RuntimeException("401"));
            }
            @Override public CompletionResponse completeWithTools(CompletionRequest request, JsonNode tools) {
                return complete(request);
            }
        };

        ScenarioRunner.Result result = ScenarioRunner.builder()
                .liveClient(liveModel).judgeClient(badJudge).build()
                .run(scenario);

        assertTrue(result.passed(), "deterministic assertions still pass when judge is down");
        assertTrue(result.grades().stream().anyMatch(GradeResult::error));
        assertEquals(0.0, result.meanJudgeScore(), 0.001);
    }

    @Test
    void frozenLiveRun_roundTripsIntoPassingReplayScenario() {
        Scenario live = ScenarioLoader.fromYaml(LIVE_YAML);
        ScenarioRunner.Result liveResult = ScenarioRunner.builder()
                .liveClient(scriptedLiveModel(live))
                .judgeClient(stubJudge("{\"grades\":[]}"))
                .build()
                .run(live);
        assertTrue(liveResult.passed());

        String frozenYaml = FreezeWriter.toReplayYaml(live, liveResult.trace(), liveResult.recording());

        // The frozen scenario is replay mode, carries no rubric, and loads cleanly.
        Scenario frozen = ScenarioLoader.fromYaml(frozenYaml);
        assertFalse(frozen.live());
        assertNull(frozen.grading());
        assertTrue(frozen.id().startsWith("live-freeze-test-frozen-"));

        // Its scripted model steps reproduce the real model's exact responses.
        List<Scenario.ModelStep> steps = frozen.turns().get(0).model();
        assertEquals(2, steps.size());
        assertEquals(1, steps.get(0).toolCalls().size());
        assertEquals("web_search", steps.get(0).toolCalls().get(0).name());
        assertTrue(steps.get(1).text().contains("8 号"));

        // And it passes through the replay runner with the same expectations.
        ScenarioRunner.Result replayed = new ScenarioRunner().run(frozen);
        assertTrue(replayed.passed(), () -> String.join("\n", replayed.failures()));
        assertNull(replayed.recording());
    }

    @Test
    void liveScenarioWithoutClient_failsFast() {
        Scenario live = ScenarioLoader.fromYaml(LIVE_YAML);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new ScenarioRunner().run(live));
        assertTrue(ex.getMessage().contains("mode: live"));
    }

    @Test
    void recordingCapturesErrors() {
        LlmClient exploding = new LlmClient() {
            @Override public String provider() { return "boom"; }
            @Override public String primaryModel() { return "boom-model"; }
            @Override public CompletionResponse complete(CompletionRequest request) {
                throw new RuntimeException("HTTP 500: kaputt");
            }
            @Override public Flux<String> completeStream(CompletionRequest request) {
                return Flux.error(new RuntimeException("boom"));
            }
            @Override public CompletionResponse completeWithTools(CompletionRequest request, JsonNode tools) {
                throw new RuntimeException("HTTP 500: kaputt");
            }
        };
        RecordingLlmClient recording = new RecordingLlmClient(exploding);
        recording.markTurn();
        assertThrows(RuntimeException.class,
                () -> recording.completeWithTools(CompletionRequest.builder().build(), null));
        assertEquals(1, recording.calls().size());
        assertNotNull(recording.calls().get(0).error());
        assertTrue(recording.calls().get(0).error().contains("500"));
    }
}
