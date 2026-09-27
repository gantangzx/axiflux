package com.gantang.tianshu.api.llm;

import com.fasterxml.jackson.databind.JsonNode;
import reactor.core.publisher.Flux;

/**
 * LLM client interface — adapter per provider.
 *
 * Each provider (OpenAI, Anthropic, DeepSeek, etc.) has one implementation.
 * Isolated so the core engine has no direct dependency on any LLM SDK.
 */
public interface LlmClient {

    /** Provider name: "openai" | "anthropic" | "deepseek" | "qianfan" */
    String provider();

    /** Primary model served by this client */
    String primaryModel();

    /** Synchronous completion */
    CompletionResponse complete(CompletionRequest request);

    /** Streaming completion — emits one token per emission */
    Flux<String> completeStream(CompletionRequest request);

    /**
     * Completion with tool definitions.
     * LLM decides whether to return text or tool_calls based on the prompt and available tools.
     */
    CompletionResponse completeWithTools(CompletionRequest request, JsonNode tools);

    /**
     * Streaming completion with tool definitions.
     *
     * <p>Emits {@link StreamChunk}s: REASONING deltas (chain-of-thought from
     * reasoning models, UI-only), TEXT deltas (the visible answer), then a
     * terminal DONE carrying the assembled response (text and/or tool calls).
     *
     * <p>Default implementation adapts the blocking {@link #completeWithTools}
     * call: the whole answer arrives as a single TEXT chunk (no reasoning,
     * no token streaming). Providers with a native streaming API override this
     * to stream reasoning and text deltas live.
     */
    default reactor.core.publisher.Flux<StreamChunk> completeWithToolsStream(
            CompletionRequest request, com.fasterxml.jackson.databind.JsonNode tools) {
        return reactor.core.publisher.Flux.defer(() -> {
            CompletionResponse resp = completeWithTools(request, tools);
            java.util.List<StreamChunk> chunks = new java.util.ArrayList<>();
            if (resp.reasoning() != null && !resp.reasoning().isBlank()) {
                chunks.add(StreamChunk.reasoning(resp.reasoning()));
            }
            if (resp.isText() && resp.content() != null && !resp.content().isBlank()) {
                chunks.add(StreamChunk.text(resp.content()));
            }
            chunks.add(StreamChunk.done(resp));
            return reactor.core.publisher.Flux.fromIterable(chunks);
        });
    }

    /**
     * Whether this client serves {@link #completeWithToolsStream} with native
     * incremental streaming (live REASONING/TEXT deltas). When {@code false},
     * the default stream method adapts the blocking call — the whole answer
     * arrives in one chunk and reasoning deltas are unavailable. The routing
     * chain uses this to prefer stream-capable providers on the streaming
     * path instead of silently downgrading a turn to the blocking fallback.
     */
    default boolean supportsStreaming() {
        return false;
    }

    /**
     * Generate a text embedding vector.
     * @return vector of dimension 1536 (OpenAI ada) or 1024 (bge-m3)
     */
    default float[] embed(String text) {
        throw new UnsupportedOperationException(
            "Embedding not supported by " + provider());
    }

    /** Available models for this provider */
    default java.util.List<String> listModels() {
        return java.util.List.of(primaryModel());
    }

    /**
     * Context window size (in tokens) of the primary model served by this client.
     * Used by the agent loop to size the per-turn token budget so history is
     * trimmed to fit the routed model rather than a global default.
     *
     * @return window in tokens; defaults to 128k when a client does not override it
     */
    default int contextWindowTokens() {
        return 128_000;
    }

    /**
     * Fluent wither to set the served model's context window. No-op by default
     * (returns {@code this}); adapters that expose a configurable window override
     * it to mutate their field. Safe to call once at wiring time.
     */
    default LlmClient withContextWindowTokens(int tokens) {
        return this;
    }

    /**
     * BYOK: derive a client that calls the same provider/model with a different
     * (caller-supplied) API key. The router applies this when the turn carries a
     * BYOK key (see {@link ByokKeyResolver}); the returned client must use
     * {@code apiKey} for authentication instead of the statically configured key.
     *
     * <p>Default: this client does not support per-call key override and the
     * statically configured key is used (returns {@code this}) — preserving the
     * pre-BYOK behaviour for adapters that have not implemented key injection.
     * Implementations should treat {@code apiKey} as a turn-scoped secret (never
     * persist or log it) and may cache derived clients (bounded, with eviction)
     * to avoid rebuilding the underlying SDK model per call.
     */
    default LlmClient withApiKey(String apiKey) {
        return this;
    }
}
