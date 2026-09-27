package com.gantang.tianshu.eval.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.gantang.tianshu.api.llm.CompletionRequest;
import com.gantang.tianshu.api.llm.CompletionResponse;
import com.gantang.tianshu.api.llm.LlmClient;
import com.gantang.tianshu.api.tool.ToolCall;
import com.gantang.tianshu.eval.scenario.Scenario;
import reactor.core.publisher.Flux;

import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scripted {@link LlmClient} for replay scenarios: each {@code completeWithTools}
 * call returns the next {@link Scenario.ModelStep} in the script, in order.
 *
 * <p>Zero API cost, fully deterministic. The agent's real tool-call loop,
 * policy chain, compaction and hooks all run against this stub.
 *
 * <p>Error steps ({@link Scenario.ModelStep#error()}) simulate provider failures:
 * {@code overflow} triggers the compaction-retry path, {@code unavailable}/
 * {@code auth}/{@code hang} trigger provider fallback ({@code hang} blocks the
 * worker thread until the agent's watchdog abandons the call). Plain
 * {@code complete()} calls — used only by the CompactionService summarizer —
 * always return a fixed summary and do <b>not</b> consume scripted steps, so
 * proactive/reactive compaction never desynchronises the script.
 */
public class ReplayLlmClient implements LlmClient {

    /** Fixed summary returned to the compaction summarizer (content is not asserted). */
    private static final String SCRIPTED_SUMMARY = "历史摘要：先前对话已压缩，涵盖各轮讨论与结论。";

    private final List<Scenario.ModelStep> script;
    private final AtomicInteger cursor = new AtomicInteger(0);
    private final int contextWindow;

    public ReplayLlmClient(List<Scenario.ModelStep> script) {
        this(script, 128_000);
    }

    public ReplayLlmClient(List<Scenario.ModelStep> script, int contextWindowTokens) {
        this.script = List.copyOf(script);
        this.contextWindow = contextWindowTokens;
    }

    @Override
    public String provider() {
        return "replay";
    }

    @Override
    public String primaryModel() {
        return "replay-model";
    }

    @Override
    public int contextWindowTokens() {
        return contextWindow;
    }

    @Override
    public CompletionResponse complete(CompletionRequest request) {
        // Summarizer path only — stay script-neutral so compaction never consumes
        // a tool-loop step (and never throws an error step at the summarizer).
        return CompletionResponse.builder()
                .content(SCRIPTED_SUMMARY)
                .toolCalls(List.of())
                .finishReason("stop")
                .model(primaryModel())
                .build();
    }

    @Override
    public Flux<String> completeStream(CompletionRequest request) {
        Scenario.ModelStep step = nextStep();
        maybeThrow(step);
        String text = step.text() != null ? step.text() : "";
        return Flux.fromArray(text.split("(?<= )"));
    }

    @Override
    public CompletionResponse completeWithTools(CompletionRequest request, JsonNode tools) {
        Scenario.ModelStep step = nextStep();
        maybeThrow(step);
        if (!step.toolCalls().isEmpty()) {
            List<ToolCall> calls = new ArrayList<>();
            int n = 0;
            for (Scenario.ToolCallRef ref : step.toolCalls()) {
                calls.add(new ToolCall("call_" + cursor.get() + "_" + (n++),
                        ref.name(), ref.args()));
            }
            return CompletionResponse.builder()
                    .content(step.text() != null ? step.text() : "")
                    .toolCalls(calls)
                    .finishReason("tool_calls")
                    .model(primaryModel())
                    .build();
        }
        return CompletionResponse.builder()
                .content(step.text() != null ? step.text() : "")
                .toolCalls(List.of())
                .finishReason("stop")
                .model(primaryModel())
                .build();
    }

    private Scenario.ModelStep nextStep() {
        int i = cursor.getAndIncrement();
        if (i >= script.size()) {
            throw new IllegalStateException(
                    "Replay script exhausted at model call #" + (i + 1)
                            + " — the scenario declared only " + script.size()
                            + " model step(s). Add more `model` entries for this turn.");
        }
        return script.get(i);
    }

    private void maybeThrow(Scenario.ModelStep step) {
        String err = step.error();
        if (err == null || err.isBlank()) {
            return;
        }
        switch (err.trim().toLowerCase()) {
            case "overflow" -> throw new RuntimeException(
                    "400: This model's maximum context length is 8000 tokens. "
                    + "Your messages resulted in 12000 tokens. Please reduce the length.");
            case "hang" -> {
                // Simulate a wedged provider that never answers: block until the
                // agent's watchdog (llmCallTimeout, 2s in the hang scenario)
                // abandons the call and falls through the provider chain. The wait
                // is capped at 10s — comfortably above the scenario watchdog but
                // bounded, so the abandoned boundedElastic daemon thread lingers
                // seconds rather than a full minute before dying.
                try {
                    new java.util.concurrent.CountDownLatch(1)
                        .await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                throw new RuntimeException("scripted hang interrupted");
            }
            case "unavailable" -> throw new RuntimeException(
                    new ConnectException("Connection refused"));
            case "auth" -> throw new RuntimeException(
                    "401: Authentication failed: invalid or expired api key");
            case "overload" -> throw new RuntimeException(
                    "429: Rate limit exceeded, please retry later");
            default -> throw new RuntimeException(err);
        }
    }

    /** Number of model responses actually consumed (for trace/assertions). */
    public int consumed() {
        return cursor.get();
    }
}
