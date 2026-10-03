package com.gantang.reaxon.api.llm;

import com.gantang.reaxon.api.tool.ToolCall;
import java.util.List;

/**
 * LLM completion response.
 *
 * @param provider          registered provider/client name (cost-table key)
 * @param cachedInputTokens input tokens served from the provider's prompt/prefix
 *                          cache (OpenAI/ARK {@code cached_tokens}, DeepSeek
 *                          {@code prompt_cache_hit_tokens}, Anthropic
 *                          {@code cache_read_input_tokens}); 0 when unsupported.
 */
public record CompletionResponse(
    String content,
    List<ToolCall> toolCalls,
    String finishReason,
    int inputTokens,
    int outputTokens,
    String model,
    String reasoning,
    int cachedInputTokens,
    String provider
) {
    /** Back-compact constructor for callers without cache/provider stats. */
    public CompletionResponse(
        String content, List<ToolCall> toolCalls, String finishReason,
        int inputTokens, int outputTokens, String model, String reasoning
    ) {
        this(content, toolCalls, finishReason, inputTokens, outputTokens, model, reasoning, 0, null);
    }

    /** Constructor for callers with cache stats but no provider. */
    public CompletionResponse(
        String content, List<ToolCall> toolCalls, String finishReason,
        int inputTokens, int outputTokens, String model, String reasoning,
        int cachedInputTokens
    ) {
        this(content, toolCalls, finishReason, inputTokens, outputTokens, model,
             reasoning, cachedInputTokens, null);
    }

    /** True if this is a text-only response (no tool calls) */
    public boolean isText() {
        return content != null && !content.isBlank() &&
               (toolCalls == null || toolCalls.isEmpty());
    }

    /** True if the LLM requested one or more tool calls */
    public boolean isToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String content = "";
        private List<ToolCall> toolCalls = List.of();
        private String finishReason = "stop";
        private int inputTokens;
        private int outputTokens;
        private String model = "";
        private String reasoning;
        private int cachedInputTokens;
        private String provider;

        public Builder content(String v)            { this.content      = v; return this; }
        public Builder toolCalls(List<ToolCall> v) { this.toolCalls   = v; return this; }
        public Builder finishReason(String v)        { this.finishReason = v; return this; }
        public Builder inputTokens(int v)           { this.inputTokens  = v; return this; }
        public Builder outputTokens(int v)          { this.outputTokens = v; return this; }
        public Builder model(String v)              { this.model        = v; return this; }
        /** Chain-of-thought / deep-thinking text from reasoning models (not persisted). */
        public Builder reasoning(String v)          { this.reasoning    = v; return this; }
        /** Prompt-cache hits counted within {@link #inputTokens}. */
        public Builder cachedInputTokens(int v)     { this.cachedInputTokens = v; return this; }
        /** Registered provider/client name; key for the configured cost table. */
        public Builder provider(String v)           { this.provider     = v; return this; }

        public CompletionResponse build() {
            return new CompletionResponse(content, toolCalls, finishReason,
                                           inputTokens, outputTokens, model, reasoning,
                                           cachedInputTokens, provider);
        }
    }
}
