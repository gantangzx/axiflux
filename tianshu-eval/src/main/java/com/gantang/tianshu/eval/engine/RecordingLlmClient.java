package com.gantang.tianshu.eval.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.gantang.tianshu.api.llm.CompletionRequest;
import com.gantang.tianshu.api.llm.CompletionResponse;
import com.gantang.tianshu.api.llm.LlmClient;
import com.gantang.tianshu.api.tool.ToolCall;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Recording decorator around a real {@link LlmClient} for live eval mode.
 *
 * <p>Every model call (tool-loop, summarizer, stream) is captured as a
 * {@link RecordedCall}: response text, tool-call requests, token usage,
 * latency, and any thrown error. The recording drives two things:
 * <ul>
 *   <li>{@code EvalReport} — token/latency aggregation for quality trends;</li>
 *   <li>{@code FreezeWriter} — a failed live run is frozen into a replay YAML
 *       whose scripted model steps are exactly what the real model said.</li>
 * </ul>
 *
 * <p>Turn boundaries are marked by the runner via {@link #markTurn()} before
 * each user turn, so recorded calls can be grouped per turn when freezing.
 *
 * <p>This class stores arguments as-received (assertions/judging need the real
 * values); anything written to disk goes through {@code SecretMasker} at the
 * freeze/report boundary.
 */
public class RecordingLlmClient implements LlmClient {

    public record RecordedToolCall(String name, Map<String, Object> args) {}

    public record RecordedCall(
            int index,
            int turn,           // 1-based; 0 = before the first turn boundary (e.g. summarizer)
            String phase,       // "tool-loop" | "summary" | "stream"
            String content,
            List<RecordedToolCall> toolCalls,
            String finishReason,
            int inputTokens,
            int outputTokens,
            long latencyMs,
            String error        // non-null when the delegate threw
    ) {
        public RecordedCall {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }
    }

    private final LlmClient delegate;
    private final List<RecordedCall> calls = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger turn = new AtomicInteger(0);
    private final AtomicInteger seq = new AtomicInteger(0);

    public RecordingLlmClient(LlmClient delegate) {
        this.delegate = delegate;
    }

    /** Called by the runner before each user turn. */
    public void markTurn() {
        turn.incrementAndGet();
    }

    public List<RecordedCall> calls() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    /** Tool-loop / summary calls recorded for the given 1-based turn. */
    public List<RecordedCall> callsForTurn(int turnNo) {
        return calls().stream()
                .filter(c -> c.turn() == turnNo)
                .toList();
    }

    public int totalInputTokens() {
        return calls().stream().mapToInt(RecordedCall::inputTokens).sum();
    }

    public int totalOutputTokens() {
        return calls().stream().mapToInt(RecordedCall::outputTokens).sum();
    }

    @Override
    public String provider() {
        return delegate.provider();
    }

    @Override
    public String primaryModel() {
        return delegate.primaryModel();
    }

    @Override
    public int contextWindowTokens() {
        return delegate.contextWindowTokens();
    }

    @Override
    public CompletionResponse complete(CompletionRequest request) {
        long start = System.nanoTime();
        try {
            CompletionResponse resp = delegate.complete(request);
            record("summary", start, resp, null);
            return resp;
        } catch (RuntimeException e) {
            record("summary", start, null, e);
            throw e;
        }
    }

    @Override
    public Flux<String> completeStream(CompletionRequest request) {
        long start = System.nanoTime();
        StringBuilder assembled = new StringBuilder();
        return delegate.completeStream(request)
                .doOnNext(assembled::append)
                .doOnComplete(() -> {
                    CompletionResponse synthetic = CompletionResponse.builder()
                            .content(assembled.toString())
                            .toolCalls(List.of())
                            .finishReason("stop")
                            .model(delegate.primaryModel())
                            .build();
                    record("stream", start, synthetic, null);
                })
                .doOnError(e -> record("stream", start, null, e));
    }

    @Override
    public CompletionResponse completeWithTools(CompletionRequest request, JsonNode tools) {
        long start = System.nanoTime();
        try {
            CompletionResponse resp = delegate.completeWithTools(request, tools);
            record("tool-loop", start, resp, null);
            return resp;
        } catch (RuntimeException e) {
            record("tool-loop", start, null, e);
            throw e;
        }
    }

    private void record(String phase, long startNanos, CompletionResponse resp, Throwable error) {
        List<RecordedToolCall> toolCalls = new ArrayList<>();
        String content = "";
        String finishReason = null;
        int inTokens = 0, outTokens = 0;
        if (resp != null) {
            content = resp.content() != null ? resp.content() : "";
            finishReason = resp.finishReason();
            inTokens = resp.inputTokens();
            outTokens = resp.outputTokens();
            if (resp.toolCalls() != null) {
                for (ToolCall tc : resp.toolCalls()) {
                    toolCalls.add(new RecordedToolCall(tc.toolName(),
                            tc.arguments() != null ? Map.copyOf(tc.arguments()) : Map.of()));
                }
            }
        }
        calls.add(new RecordedCall(
                seq.getAndIncrement(),
                turn.get(),
                phase,
                content,
                toolCalls,
                finishReason,
                inTokens,
                outTokens,
                (System.nanoTime() - startNanos) / 1_000_000,
                error != null ? error.getClass().getSimpleName() + ": " + error.getMessage() : null
        ));
    }
}
