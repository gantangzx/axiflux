package com.gantang.reaxon.eval;

import com.gantang.reaxon.eval.engine.ScenarioRunner;
import com.gantang.reaxon.eval.scenario.Scenario;
import com.gantang.reaxon.eval.scenario.ScenarioLoader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs every YAML scenario on the classpath eval/ classpath through the
 * replay harness and asserts each one's declared expectations pass.
 */
class ScenarioRunnerTest {

    private static final List<String> FALLBACK_SCENARIOS = List.of(
            "eval/01-text-only.yaml",
            "eval/02-single-tool-loop.yaml",
            "eval/03-multi-turn-multi-tool.yaml",
            "eval/04-policy-denies-ssrf.yaml",
            "eval/05-tool-error-recovery.yaml",
            "eval/06-compaction-recovery.yaml",
            "eval/07-provider-fallback.yaml",
            "eval/08-approval-required-no-manager.yaml",
            "eval/09-tool-result-pruned.yaml",
            "eval/10-approval-granted.yaml",
            "eval/11-approval-rejected.yaml",
            "eval/12-spawn-subagent.yaml",
            "eval/13-llm-watchdog-fallback.yaml",
            "eval/20-memory-preference-supersede.yaml",
            "eval/21-memory-multi-user-isolation.yaml",
            "eval/22-memory-explicit-ttl.yaml",
            "eval/23-memory-distractors.yaml",
            "eval/24-memory-cross-session-multiturn.yaml"
    );

    /**
     * CI gate: auto-discover every replay scenario under the eval resources
     * directory, so adding a YAML file automatically extends the PR gate with
     * no test edit. Falls back to the baked-in classpath list when the source
     * tree isn't the working directory (IDE launch configs).
     */
    private static List<Scenario> discoverScenarios() throws Exception {
        java.nio.file.Path dir = java.nio.file.Path.of("src/test/resources/eval");
        if (java.nio.file.Files.isDirectory(dir)) {
            try (var files = java.nio.file.Files.list(dir)) {
                List<java.nio.file.Path> yamls = files
                        .filter(java.nio.file.Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".yaml"))
                        .sorted()
                        .toList();
                List<Scenario> scenarios = new java.util.ArrayList<>();
                for (java.nio.file.Path p : yamls) {
                    scenarios.add(ScenarioLoader.fromPath(p));
                }
                return scenarios;
            }
        }
        List<Scenario> scenarios = new java.util.ArrayList<>();
        for (String resource : FALLBACK_SCENARIOS) {
            scenarios.add(ScenarioLoader.fromResource(resource));
        }
        return scenarios;
    }

    @Test
    void allBundledScenariosPass() throws Exception {
        List<Scenario> scenarios = discoverScenarios();
        ScenarioRunner runner = new ScenarioRunner();
        for (Scenario scenario : scenarios) {
            ScenarioRunner.Result result = runner.run(scenario);
            assertTrue(result.passed(),
                    "Scenario " + scenario.id() + " failed:\n  - "
                            + String.join("\n  - ", result.failures()));
        }
    }

    @Test
    void textOnlyScenario_drivesAgentAndMatchesText() {
        Scenario s = ScenarioLoader.fromResource("eval/01-text-only.yaml");
        ScenarioRunner.Result r = new ScenarioRunner().run(s);
        assertTrue(r.passed(), () -> String.join("\n", r.failures()));
        assertEquals("SUCCESS", r.trace().status());
        assertTrue(r.trace().eventTypes().contains("TEXT_TOKEN"));
        assertTrue(r.trace().eventTypes().contains("DONE"));
        assertTrue(r.trace().toolInvocations().isEmpty());
        assertTrue(r.trace().modelCallCount() >= 1);
    }

    @Test
    void toolLoopScenario_executesFakeTool() {
        Scenario s = ScenarioLoader.fromResource("eval/02-single-tool-loop.yaml");
        ScenarioRunner.Result r = new ScenarioRunner().run(s);
        assertTrue(r.passed(), () -> String.join("\n", r.failures()));
        assertEquals(1, r.trace().toolInvocations().size());
        assertEquals("echo", r.trace().toolInvocations().get(0).name());
        assertTrue(r.trace().toolInvocations().get(0).success());
    }

    @Test
    void failingAssertion_isReportedNotThrown() {
        // Same shape as 01 but expecting text the scripted model never says.
        String yaml = """
                id: negative-check
                name: 断言失败应被收集
                systemPrompt: x
                tools: []
                turns:
                  - user: "hi"
                    model:
                      - text: "goodbye"
                expect:
                  finalContains:
                    - "hello"
                """;
        Scenario s = ScenarioLoader.fromYaml(yaml);
        ScenarioRunner.Result r = new ScenarioRunner().run(s);
        assertFalse(r.passed());
        assertTrue(r.failures().stream().anyMatch(f -> f.contains("finalResponse should contain")));
    }
}
