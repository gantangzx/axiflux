package com.gantang.tianshu.eval.cli;

import com.gantang.tianshu.api.llm.LlmClient;
import com.gantang.tianshu.eval.engine.OpenAiCompatLlmClient;
import com.gantang.tianshu.eval.engine.ScenarioRunner;
import com.gantang.tianshu.eval.freeze.FreezeWriter;
import com.gantang.tianshu.eval.report.EvalReport;
import com.gantang.tianshu.eval.scenario.Scenario;
import com.gantang.tianshu.eval.scenario.ScenarioLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Command-line entry point for the eval harness.
 *
 * <pre>
 * # Replay all scenarios in a directory (CI, zero cost):
 *   java -cp tianshu-eval.jar com.gantang.tianshu.eval.cli.EvalMain --scenarios eval
 *
 * # Live run against an OpenAI-compatible endpoint (ARK/DeepSeek/vLLM...),
 * # with quality judging and failure freeze:
 *   ARK_API_KEY=ark-... java -cp ... com.gantang.tianshu.eval.cli.EvalMain \
 *       --mode live --scenarios eval-live \
 *       --base-url https://ark.cn-beijing.volces.com/api/plan/v3 \
 *       --model ark-code-latest --judge-model ark-code-latest \
 *       --report target/eval --freeze-dir eval
 * </pre>
 *
 * Exit code: 0 all passed, 1 any deterministic failure (judge scores never
 * gate), 2 usage/configuration error.
 */
public final class EvalMain {

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (Exception e) {
            System.err.println("eval failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.exit(2);
        }
    }

    static int run(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);
        if (opts.containsKey("help")) {
            printHelp();
            return 0;
        }

        String mode = opts.getOrDefault("mode", "replay").toLowerCase();
        Path scenariosPath = Path.of(opts.getOrDefault("scenarios", "eval"));
        if (!Files.exists(scenariosPath)) {
            System.err.println("scenarios path not found: " + scenariosPath.toAbsolutePath());
            return 2;
        }

        List<Path> files = collectYamls(scenariosPath);
        if (files.isEmpty()) {
            System.err.println("no .yaml/.yml scenarios under " + scenariosPath);
            return 2;
        }

        ScenarioRunner runner;
        LlmClient judgeClient = null;
        if ("live".equals(mode)) {
            String baseUrl = firstNonBlank(opts.get("base-url"), env("EVAL_BASE_URL"), env("OPENAI_BASE_URL"));
            String model = firstNonBlank(opts.get("model"), env("EVAL_MODEL"));
            String keyEnv = opts.getOrDefault("api-key-env", "ARK_API_KEY");
            String apiKey = env(keyEnv);
            if (baseUrl == null || model == null) {
                System.err.println("live mode requires --base-url and --model (or EVAL_BASE_URL/EVAL_MODEL env)");
                return 2;
            }
            if (apiKey == null) {
                System.err.println("live mode requires API key in env $" + keyEnv
                        + " (use --api-key-env to change the variable name)");
                return 2;
            }
            String provider = opts.getOrDefault("provider", "openai-compat");
            int timeout = Integer.parseInt(opts.getOrDefault("timeout", "120"));
            LlmClient liveClient = new OpenAiCompatLlmClient(
                    provider, apiKey, baseUrl, model, 128_000, timeout);

            String judgeModel = firstNonBlank(opts.get("judge-model"), env("EVAL_JUDGE_MODEL"), model);
            judgeClient = judgeModel.equals(model) ? liveClient
                    : new OpenAiCompatLlmClient(provider, apiKey, baseUrl, judgeModel, 128_000, timeout);
            runner = ScenarioRunner.builder().liveClient(liveClient).judgeClient(judgeClient).build();
            System.out.println("Live mode: " + baseUrl + " model=" + model
                    + " judge=" + judgeModel + " (" + files.size() + " scenario file(s) found)");
        } else {
            runner = new ScenarioRunner();
            System.out.println("Replay mode: " + files.size() + " scenario file(s) found");
        }

        Path reportDir = opts.containsKey("report") ? Path.of(opts.get("report")) : null;
        Path freezeDir = opts.containsKey("freeze-dir") ? Path.of(opts.get("freeze-dir")) : null;

        List<EvalReport.ScenarioOutcome> outcomes = new ArrayList<>();
        int skipped = 0;
        for (Path file : files) {
            Scenario scenario;
            try {
                scenario = ScenarioLoader.fromPath(file);
            } catch (Exception e) {
                System.err.println("! skip " + file + ": " + e.getMessage());
                skipped++;
                continue;
            }
            if ("live".equals(mode) && !scenario.live()) {
                System.out.println("- skip replay scenario in live mode: " + scenario.id());
                skipped++;
                continue;
            }
            if ("replay".equals(mode) && scenario.live()) {
                System.out.println("- skip live scenario in replay mode: " + scenario.id());
                skipped++;
                continue;
            }

            System.out.print("• " + scenario.id() + " ... ");
            long start = System.currentTimeMillis();
            ScenarioRunner.Result result;
            try {
                result = runner.run(scenario);
            } catch (Exception e) {
                System.out.println("ERROR (runner)");
                result = null;
                long duration = System.currentTimeMillis() - start;
                outcomes.add(new EvalReport.ScenarioOutcome(
                        scenario.id(), scenario.name(),
                        scenario.live() ? "live" : "replay",
                        false, List.of("runner raised: " + e.getClass().getSimpleName()
                                + ": " + e.getMessage()),
                        List.of(), null, 0, 0, duration, null));
                continue;
            }
            long duration = System.currentTimeMillis() - start;

            String frozenTo = null;
            if (!result.passed() && scenario.live() && freezeDir != null && result.recording() != null) {
                try {
                    Path frozen = FreezeWriter.writeFrozen(
                            freezeDir, scenario, result.trace(), result.recording(),
                            result.userMessages());
                    frozenTo = frozen.toString();
                    System.out.print("frozen → " + frozen + " ");
                } catch (Exception e) {
                    System.out.print("(freeze failed: " + e.getMessage() + ") ");
                }
            }

            System.out.println(result.passed() ? "PASS"
                    : "FAIL (" + result.failures().size() + " assertion(s))"
                    + (result.meanJudgeScore() > 0
                        ? String.format(" [judge %.2f/5]", result.meanJudgeScore()) : ""));
            outcomes.add(EvalReport.ScenarioOutcome.of(scenario, result, duration, frozenTo));
        }

        EvalReport report = EvalReport.of(mode, outcomes);
        String markdown = report.toMarkdown();
        System.out.println();
        System.out.println(markdown);

        if (reportDir != null) {
            Files.createDirectories(reportDir);
            Files.writeString(reportDir.resolve("report.md"), markdown);
            Files.writeString(reportDir.resolve("report.json"), report.toJson());
            System.out.println("Report written to " + reportDir.toAbsolutePath()
                    + " (report.md, report.json)");
        }

        if (skipped > 0) {
            System.out.println("(" + skipped + " scenario file(s) skipped)");
        }
        return report.failedCount() > 0 ? 1 : 0;
    }

    private static List<Path> collectYamls(Path path) throws Exception {
        List<Path> files = new ArrayList<>();
        if (Files.isRegularFile(path)) {
            files.add(path);
        } else {
            try (var stream = Files.list(path)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> {
                            String n = p.getFileName().toString().toLowerCase();
                            return n.endsWith(".yaml") || n.endsWith(".yml");
                        })
                        .forEach(files::add);
            }
            files.sort(Comparator.comparing(p -> p.getFileName().toString()));
        }
        return files;
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new java.util.HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--help".equals(a) || "-h".equals(a)) {
                opts.put("help", "true");
            } else if (a.startsWith("--")) {
                String key = a.substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    opts.put(key, args[++i]);
                } else {
                    opts.put(key, "true");
                }
            }
        }
        return opts;
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? null : v;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static void printHelp() {
        System.out.println("""
                tianshu-eval — agent scenario evaluation harness

                Usage:
                  EvalMain [options]

                Options:
                  --scenarios <dir|file>  Scenario YAML location (default: eval)
                  --mode <replay|live>    replay = scripted model, zero cost (default);
                                          live = real model via OpenAI-compatible API
                  --base-url <url>        Chat completions base URL (live; or $EVAL_BASE_URL)
                  --model <name>          Live model name (or $EVAL_MODEL)
                  --judge-model <name>    Judge model; defaults to --model (or $EVAL_JUDGE_MODEL)
                  --api-key-env <name>    Env var holding the API key (default: ARK_API_KEY)
                  --provider <name>       Provider label (default: openai-compat)
                  --timeout <seconds>     Per-request timeout (default: 120)
                  --report <dir>          Write report.md + report.json
                  --freeze-dir <dir>      Freeze failed live runs into replay YAMLs
                  -h, --help              Show this help

                Exit codes: 0 all passed | 1 deterministic failures | 2 config/usage error""");
    }
}
