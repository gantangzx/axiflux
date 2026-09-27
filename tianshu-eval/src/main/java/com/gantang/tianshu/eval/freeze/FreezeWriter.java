package com.gantang.tianshu.eval.freeze;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.gantang.tianshu.api.observability.SecretMasker;
import com.gantang.tianshu.eval.engine.RecordingLlmClient;
import com.gantang.tianshu.eval.scenario.Scenario;
import com.gantang.tianshu.eval.trace.Trace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts a failed live run into a deterministic replay scenario YAML
 * (design doc §3.4: "失败可固化").
 *
 * <p>The frozen script is exactly what the real model said during the live
 * run — every tool-loop response becomes a {@code model} step (tool calls or
 * text), every model error becomes an {@code error} step — while the scenario's
 * deterministic expectations are kept, so the frozen run replays through the
 * real agent loop in CI at zero API cost and pins the framework behavior
 * observed during the failure.
 *
 * <p>Tools the model called but the scenario never declared are back-filled
 * as stubs from the observed results. Tool-call arguments written to disk
 * pass through {@link SecretMasker} so a frozen scenario can be committed
 * without leaking credentials.
 */
public final class FreezeWriter {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private FreezeWriter() {}

    /** Render the frozen replay scenario as YAML text. */
    @SuppressWarnings("unchecked")
    public static String toReplayYaml(Scenario original, Trace trace,
                                      RecordingLlmClient recording) {
        return toReplayYaml(original, trace, recording, null);
    }

    /**
     * Render the frozen replay scenario, using {@code executedUserMessages} when
     * provided (live user-simulator branches send messages not present in the
     * declared turns; each executed message corresponds to one recording turn).
     */
    @SuppressWarnings("unchecked")
    public static String toReplayYaml(Scenario original, Trace trace,
                                      RecordingLlmClient recording,
                                      List<String> executedUserMessages) {
        String stamp = LocalDateTime.now().format(TS);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", original.id() + "-frozen-" + stamp);
        root.put("name", original.name() + "（frozen from live run " + stamp + "）");
        root.put("mode", "replay");
        if (original.systemPrompt() != null && !original.systemPrompt().isBlank()) {
            root.put("systemPrompt", original.systemPrompt());
        }
        if (original.policyChain() != null && !original.policyChain().isBlank()) {
            root.put("policyChain", original.policyChain());
        }
        root.put("tools", buildTools(original, trace));
        root.put("turns", buildTurns(original, recording, executedUserMessages));

        Map<String, Object> expect = prune(
                new ObjectMapper().convertValue(original.expect(), Map.class));
        if (expect != null && !expect.isEmpty()) {
            root.put("expect", expect);
        }
        // Grading rubric is intentionally dropped: judging a scripted model is meaningless.

        ObjectMapper yaml = JsonMapper.builder(new YAMLFactory())
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .build();
        try {
            return "# Auto-frozen from a live eval run — do not edit by hand.\n"
                    + "# Replays the real model's exact responses; deterministic expectations kept.\n"
                    + yaml.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render frozen YAML: " + e.getMessage(), e);
        }
    }

    /** Write the frozen scenario into {@code dir} as {@code <id>.yaml}; returns the file. */
    public static Path writeFrozen(Path dir, Scenario original, Trace trace,
                                   RecordingLlmClient recording) throws IOException {
        return writeFrozen(dir, original, trace, recording, null);
    }

    /** See {@link #toReplayYaml(Scenario, Trace, RecordingLlmClient, List)}. */
    public static Path writeFrozen(Path dir, Scenario original, Trace trace,
                                   RecordingLlmClient recording,
                                   List<String> executedUserMessages) throws IOException {
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now().format(TS);
        Path file = dir.resolve(original.id() + "-frozen-" + stamp + ".yaml");
        Files.writeString(file, toReplayYaml(original, trace, recording, executedUserMessages));
        return file;
    }

    private static List<Map<String, Object>> buildTools(Scenario original, Trace trace) {
        List<Map<String, Object>> tools = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Scenario.ToolStub stub : original.tools()) {
            seen.add(stub.name());
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("name", stub.name());
            if (stub.description() != null) t.put("description", stub.description());
            if (stub.returns() != null) t.put("returns", stub.returns());
            if (stub.error() != null) t.put("error", stub.error());
            if (stub.repeatReturns() != null) t.put("repeatReturns", stub.repeatReturns());
            if (stub.repeatTimes() != null) t.put("repeatTimes", stub.repeatTimes());
            if (stub.requiresApproval() != null) t.put("requiresApproval", stub.requiresApproval());
            tools.add(prune(t));
        }
        // Back-fill tools the model called but the scenario never declared,
        // using the observed results as the stub's fixed reply.
        for (Trace.ToolInvocation inv : trace.toolInvocations()) {
            if (!seen.add(inv.name())) continue;
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("name", inv.name());
            t.put("description", "Auto-frozen from live run (tool was not declared in scenario)");
            if (inv.success()) {
                t.put("returns", inv.result() != null ? inv.result() : "ok");
            } else {
                t.put("error", inv.error() != null ? inv.error() : "error");
            }
            tools.add(t);
        }
        return tools;
    }

    private static List<Map<String, Object>> buildTurns(Scenario original,
                                                        RecordingLlmClient recording,
                                                        List<String> executedUserMessages) {
        List<String> userMessages;
        if (executedUserMessages != null && !executedUserMessages.isEmpty()) {
            userMessages = executedUserMessages;
        } else {
            userMessages = original.turns().stream().map(Scenario.Turn::user).toList();
        }
        List<Map<String, Object>> turns = new ArrayList<>();
        for (int t = 0; t < userMessages.size(); t++) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("user", userMessages.get(t));

            List<Map<String, Object>> steps = new ArrayList<>();
            for (RecordingLlmClient.RecordedCall call : recording.callsForTurn(t + 1)) {
                if ("summary".equals(call.phase())) {
                    // Compaction summarizer calls don't consume replay steps
                    // (ReplayLlmClient.complete returns a fixed summary).
                    continue;
                }
                Map<String, Object> step = new LinkedHashMap<>();
                if (call.error() != null) {
                    step.put("error", call.error());
                } else if (!call.toolCalls().isEmpty()) {
                    List<Map<String, Object>> refs = new ArrayList<>();
                    for (RecordingLlmClient.RecordedToolCall tc : call.toolCalls()) {
                        Map<String, Object> ref = new LinkedHashMap<>();
                        ref.put("name", tc.name());
                        @SuppressWarnings("unchecked")
                        Map<String, Object> masked = (Map<String, Object>)
                                SecretMasker.mask(new LinkedHashMap<>(tc.args()));
                        ref.put("args", masked);
                        refs.add(ref);
                    }
                    step.put("toolCalls", refs);
                    if (call.content() != null && !call.content().isBlank()) {
                        step.put("text", call.content());
                    }
                } else {
                    step.put("text", call.content() != null ? call.content() : "");
                }
                steps.add(step);
            }
            out.put("model", steps);
            turns.add(out);
        }
        return turns;
    }

    /** Remove null values and empty containers (YAML nulls bind as null records anyway). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> prune(Map<String, Object> map) {
        if (map == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            Object v = e.getValue();
            if (v == null) continue;
            if (v instanceof Map<?, ?> m) {
                Map<String, Object> pruned = prune((Map<String, Object>) m);
                if (!pruned.isEmpty()) out.put(e.getKey(), pruned);
            } else if (v instanceof List<?> list) {
                if (!list.isEmpty()) out.put(e.getKey(), v);
            } else if (v instanceof String s && s.isBlank()) {
                continue;
            } else {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }
}
