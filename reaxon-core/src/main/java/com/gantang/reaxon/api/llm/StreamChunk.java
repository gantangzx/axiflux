package com.gantang.reaxon.api.llm;

/**
 * One chunk of a streaming tool-capable completion ({@link LlmClient#completeWithToolsStream}).
 *
 * <p>Three kinds, in emission order:
 * <ul>
 *   <li>{@link Kind#REASONING} — a delta of the model's chain-of-thought
 *       ("deep thinking") text. Never persisted to the transcript; UI-only.</li>
 *   <li>{@link Kind#TEXT} — a delta of the visible answer text.</li>
 *   <li>{@link Kind#DONE} — terminal chunk carrying the assembled
 *       {@link CompletionResponse} (final text and/or tool calls plus usage).</li>
 * </ul>
 *
 * @param kind     chunk kind
 * @param delta    incremental text for REASONING/TEXT; null for DONE
 * @param response assembled final response for DONE; null otherwise
 */
public record StreamChunk(Kind kind, String delta, CompletionResponse response) {

    public enum Kind { REASONING, TEXT, DONE }

    public static StreamChunk reasoning(String delta) {
        return new StreamChunk(Kind.REASONING, delta, null);
    }

    public static StreamChunk text(String delta) {
        return new StreamChunk(Kind.TEXT, delta, null);
    }

    public static StreamChunk done(CompletionResponse response) {
        return new StreamChunk(Kind.DONE, null, response);
    }
}
