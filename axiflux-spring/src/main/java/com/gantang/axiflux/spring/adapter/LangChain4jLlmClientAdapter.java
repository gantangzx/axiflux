package com.gantang.axiflux.spring.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gantang.reaxon.api.llm.CompletionRequest;
import com.gantang.reaxon.api.llm.CompletionResponse;
import com.gantang.reaxon.api.llm.LlmClient;
import com.gantang.reaxon.api.llm.StreamChunk;
import com.gantang.reaxon.api.tool.ToolCall;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;

/**
 * Adapter wrapping LangChain4j ChatModel/StreamingChatModel as an LlmClient.
 * Supports OpenAI, DeepSeek (via langchain4j-open-ai) and Anthropic (langchain4j-anthropic).
 *
 * LangChain4j 1.0.0 API:
 * - ToolSpecification lives in dev.langchain4j.agent.tool
 * - ToolChoice is an enum (AUTO/REQUIRED)
 * - Parameters use JsonSchemaElement hierarchy (JsonStringSchema, JsonIntegerSchema, etc.)
 * - ToolMessage is internal API, tool results use UserMessage workaround
 */
public class LangChain4jLlmClientAdapter implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jLlmClientAdapter.class);

    /** Provider family: decides which LangChain4j builder a BYOK re-key rebuilds. */
    private enum Kind { OPENAI, OPENAI_COMPATIBLE, DEEPSEEK, ANTHROPIC }

    private final String provider;
    private final String primaryModel;
    private final ChatModel chatModel;
    private final StreamingChatModel streamingModel;
    private final ObjectMapper om;
    /** OpenAI-compatible base URL for the native SSE streamer; null for non-OpenAI clients (Anthropic). */
    private final String streamBaseUrl;
    private final String streamApiKey;
    /** Send {@code thinking:{type:enabled}} so ARK plan models emit {@code reasoning_content}. */
    private boolean thinkingEnabled = false;
    /** Context window of the served model; default 128k, set via wither at wiring time. */
    private int contextWindowTokens = 128_000;

    // === BYOK re-key support ===
    // LangChain4j 1.0.0 bakes apiKey into ChatModel/StreamingChatModel as a final
    // field (OpenAiChatModel → OpenAiClient(apiKey)), so a per-call key cannot be
    // mutated on the live model. We therefore rebuild the models per distinct key
    // and cache the derived adapters, bounded + idle-evicted so many callers do
    // not blow up the heap. Rebuilding is cheap (builder allocates an HTTP
    // client wrapper; no connection is opened until the first call).
    private final Kind kind;
    private final String baseUrl;
    /** The statically configured key; a BYOK request with the same key short-circuits to this. */
    private final String configuredApiKey;
    /** Derived adapters keyed by key-hash; null on derived instances (only the root caches). */
    private final ByokClientCache byokCache;

    private LangChain4jLlmClientAdapter(
            String provider,
            String primaryModel,
            ChatModel chatModel,
            StreamingChatModel streamingModel
    ) {
        this(provider, primaryModel, chatModel, streamingModel, null, null);
    }

    private LangChain4jLlmClientAdapter(
            String provider,
            String primaryModel,
            ChatModel chatModel,
            StreamingChatModel streamingModel,
            String streamBaseUrl,
            String streamApiKey
    ) {
        this(provider, primaryModel, chatModel, streamingModel, streamBaseUrl, streamApiKey,
            Kind.OPENAI, streamBaseUrl, streamApiKey, new ByokClientCache());
    }

    private LangChain4jLlmClientAdapter(
            String provider,
            String primaryModel,
            ChatModel chatModel,
            StreamingChatModel streamingModel,
            String streamBaseUrl,
            String streamApiKey,
            Kind kind,
            String baseUrl,
            String configuredApiKey,
            ByokClientCache byokCache
    ) {
        this.provider = provider;
        this.primaryModel = primaryModel;
        this.chatModel = chatModel;
        this.streamingModel = streamingModel;
        this.om = new ObjectMapper();
        this.streamBaseUrl = streamBaseUrl;
        this.streamApiKey = streamApiKey;
        this.kind = kind;
        this.baseUrl = baseUrl;
        this.configuredApiKey = configuredApiKey;
        this.byokCache = byokCache;
    }

    // === Factory methods ===

    public static LangChain4jLlmClientAdapter openAi(String apiKey, String baseUrl, String model) {
        return new LangChain4jLlmClientAdapter(
            "openai", model, buildOpenAiChat(apiKey, baseUrl, model), buildOpenAiStreaming(apiKey, baseUrl, model),
            baseUrl, apiKey, Kind.OPENAI, baseUrl, apiKey, new ByokClientCache());
    }

    public static LangChain4jLlmClientAdapter anthropic(String apiKey, String baseUrl, String model) {
        return new LangChain4jLlmClientAdapter(
            "anthropic", model, buildAnthropicChat(apiKey, baseUrl, model), null,
            null, null, Kind.ANTHROPIC, baseUrl, apiKey, new ByokClientCache());
    }

    /**
     * Generic OpenAI-compatible provider with a custom provider name.
     * Works with any OpenAI-compatible endpoint: Volcengine ARK, DashScope (Tongyi),
     * Zhipu, Moonshot, DeepSeek, vLLM, Ollama (/v1), LM Studio, etc.
     */
    public static LangChain4jLlmClientAdapter openAiCompatible(String provider, String apiKey, String baseUrl, String model) {
        String key = apiKey != null ? apiKey : "not-needed";
        return new LangChain4jLlmClientAdapter(
            provider, model, buildOpenAiChat(key, baseUrl, model), buildOpenAiStreaming(key, baseUrl, model),
            baseUrl, key, Kind.OPENAI_COMPATIBLE, baseUrl, key, new ByokClientCache());
    }

    public static LangChain4jLlmClientAdapter deepSeek(String apiKey, String baseUrl, String model) {
        return new LangChain4jLlmClientAdapter(
            "deepseek", model, buildOpenAiChat(apiKey, baseUrl, model), buildOpenAiStreaming(apiKey, baseUrl, model),
            baseUrl, apiKey, Kind.DEEPSEEK, baseUrl, apiKey, new ByokClientCache());
    }

    // === Model builders (shared by the static factories and BYOK re-keying) ===

    private static ChatModel buildOpenAiChat(String apiKey, String baseUrl, String model) {
        return dev.langchain4j.model.openai.OpenAiChatModel.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .modelName(model)
            .temperature(0.7)
            .build();
    }

    private static StreamingChatModel buildOpenAiStreaming(String apiKey, String baseUrl, String model) {
        return dev.langchain4j.model.openai.OpenAiStreamingChatModel.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .modelName(model)
            .temperature(0.7)
            .build();
    }

    private static ChatModel buildAnthropicChat(String apiKey, String baseUrl, String model) {
        dev.langchain4j.model.anthropic.AnthropicChatModel.AnthropicChatModelBuilder b =
            dev.langchain4j.model.anthropic.AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(model)
                .temperature(0.7)
                .cacheSystemMessages(true)
                .cacheTools(true);
        if (baseUrl != null && !baseUrl.isBlank()) {
            b.baseUrl(baseUrl);
        }
        return b.build();
    }

    /**
     * Enable the {@code thinking} request parameter so ARK plan reasoning models
     * stream {@code reasoning_content} (chain-of-thought) deltas. Fluent wiring.
     */
    public LangChain4jLlmClientAdapter withThinkingEnabled(boolean enabled) {
        this.thinkingEnabled = enabled;
        return this;
    }

    // === LlmClient interface ===

    @Override
    public String provider() { return provider; }

    @Override
    public String primaryModel() { return primaryModel; }

    @Override
    public int contextWindowTokens() { return contextWindowTokens; }

    /** Set the served model's context window; returns this for fluent wiring. Called once at startup before any request. */
    public LangChain4jLlmClientAdapter withContextWindowTokens(int tokens) {
        this.contextWindowTokens = tokens > 0 ? tokens : 128_000;
        return this;
    }

    // === BYOK per-call key injection ===

    /**
     * Derive an adapter that authenticates with {@code apiKey} instead of the
     * statically configured key. LangChain4j 1.0.0 bakes the key into the
     * ChatModel/StreamingChatModel as a final field (OpenAiChatModel wraps an
     * OpenAiClient whose apiKey is immutable), so a per-call key cannot be set
     * on the live model and there is no supported hook to override the
     * Authorization header per request. Rebuilding the models per distinct key
     * is therefore the only faithful injection point; the derived adapters are
     * cached (bounded, idle-evicted) so many callers do not accumulate models.
     *
     * <p>The native-SSE streaming path ({@link #completeWithToolsStream}) sets
     * the Authorization header from {@code streamApiKey} per request, so the
     * derived adapter simply carries the BYOK key there — same code path, no
     * per-request branching.
     *
     * <p>Blank key or the statically configured key → {@code this} (no-op), so
     * the hot path without BYOK pays nothing.
     */
    @Override
    public LlmClient withApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank() || apiKey.equals(configuredApiKey)) {
            return this;
        }
        ByokClientCache cache = this.byokCache;
        if (cache == null) {
            // Derived instances do not cache further derivations; re-key the root config.
            return derive(apiKey);
        }
        return cache.get(apiKey, this::derive);
    }

    /** Build a new adapter identical to this one but bound to {@code apiKey}. */
    private LangChain4jLlmClientAdapter derive(String apiKey) {
        ChatModel chat;
        StreamingChatModel stream;
        String streamKey;
        String streamUrl;
        switch (kind) {
            case ANTHROPIC -> {
                chat = buildAnthropicChat(apiKey, baseUrl, primaryModel);
                stream = null;
                streamKey = null;
                streamUrl = null;
            }
            default -> {
                chat = buildOpenAiChat(apiKey, baseUrl, primaryModel);
                stream = buildOpenAiStreaming(apiKey, baseUrl, primaryModel);
                streamKey = apiKey;
                streamUrl = baseUrl;
            }
        }
        LangChain4jLlmClientAdapter derived = new LangChain4jLlmClientAdapter(
            provider, primaryModel, chat, stream, streamUrl, streamKey,
            kind, baseUrl, apiKey, null);
        derived.thinkingEnabled = this.thinkingEnabled;
        derived.contextWindowTokens = this.contextWindowTokens;
        return derived;
    }

    // Package-private test hooks: prove which key a derived adapter would send,
    // without opening a connection.
    String streamApiKeyForTest() { return streamApiKey; }
    String configuredApiKeyForTest() { return configuredApiKey; }
    int byokCacheSizeForTest() { return byokCache != null ? byokCache.size() : 0; }
    ChatModel chatModelForTest() { return chatModel; }

    /**
     * Bounded cache of BYOK-derived adapters, keyed by the SHA-256 of the raw
     * key (never the key itself — a heap dump of the map must not reveal
     * credentials). Evicts entries idle for {@link #BYOK_IDLE_TTL} and caps at
     * {@link #BYOK_MAX_ENTRIES} (oldest-idle first) so per-user keys cannot
     * grow the heap without bound.
     */
    static final class ByokClientCache {
        static final int BYOK_MAX_ENTRIES = 256;
        static final Duration BYOK_IDLE_TTL = Duration.ofMinutes(30);

        private static final class Entry {
            final LangChain4jLlmClientAdapter adapter;
            volatile long lastAccessNanos;
            Entry(LangChain4jLlmClientAdapter adapter) {
                this.adapter = adapter;
                this.lastAccessNanos = System.nanoTime();
            }
        }

        private final java.util.concurrent.ConcurrentHashMap<String, Entry> map =
            new java.util.concurrent.ConcurrentHashMap<>();

        LangChain4jLlmClientAdapter get(String rawKey,
                                        java.util.function.Function<String, LangChain4jLlmClientAdapter> factory) {
            String id = sha256Hex(rawKey);
            long now = System.nanoTime();
            Entry e = map.get(id);
            if (e != null) {
                if (now - e.lastAccessNanos < BYOK_IDLE_TTL.toNanos()) {
                    e.lastAccessNanos = now;
                    return e.adapter;
                }
                map.remove(id, e); // idle-expired: rebuild below
            }
            // Bounded growth: when full, evict the idle-oldest entries first.
            if (map.size() >= BYOK_MAX_ENTRIES) {
                evictExpired(now);
                while (map.size() >= BYOK_MAX_ENTRIES) {
                    String oldest = oldestEntryKey();
                    if (oldest == null || !map.remove(oldest, map.get(oldest))) break;
                }
            }
            Entry created = new Entry(factory.apply(rawKey));
            Entry raced = map.putIfAbsent(id, created);
            if (raced != null) {
                raced.lastAccessNanos = now;
                return raced.adapter;
            }
            return created.adapter;
        }

        int size() { return map.size(); }

        private void evictExpired(long now) {
            map.entrySet().removeIf(en -> now - en.getValue().lastAccessNanos >= BYOK_IDLE_TTL.toNanos());
        }

        private String oldestEntryKey() {
            String oldestKey = null;
            long oldest = Long.MAX_VALUE;
            for (var en : map.entrySet()) {
                if (en.getValue().lastAccessNanos < oldest) {
                    oldest = en.getValue().lastAccessNanos;
                    oldestKey = en.getKey();
                }
            }
            return oldestKey;
        }

        private static String sha256Hex(String raw) {
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder(digest.length * 2);
                for (byte b : digest) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
                return sb.toString();
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 unavailable", e);
            }
        }
    }

    @Override
    public CompletionResponse complete(CompletionRequest request) {
        List<ChatMessage> lcMessages = toLangChainMessages(request.messages());

        ChatRequest lcRequest = ChatRequest.builder()
                .messages(lcMessages)
                .build();

        ChatResponse response = chatModel.chat(lcRequest);

        return toCompletionResponse(response);
    }

    @Override
    public Flux<String> completeStream(CompletionRequest request) {
        if (streamingModel == null) {
            String text = complete(request).content();
            return Flux.just(text);
        }

        return Flux.create(sink -> {
            List<ChatMessage> lcMessages = toLangChainMessages(request.messages());
            ChatRequest lcRequest = ChatRequest.builder()
                    .messages(lcMessages)
                    .build();

            streamingModel.chat(lcRequest, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partial) {
                    sink.next(partial);
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    sink.complete();
                }

                @Override
                public void onError(Throwable t) {
                    sink.error(t);
                }
            });
        });
    }

    @Override
    public CompletionResponse completeWithTools(CompletionRequest request, JsonNode tools) {
        List<ChatMessage> lcMessages = toLangChainMessages(request.messages());
        List<ToolSpecification> specs = toToolSpecifications(tools);

        // Put tool specs at the ChatRequest top level. The public chat() entry
        // point merges them with the model's provider-specific default parameters
        // (e.g. OpenAiChatRequestParameters); calling doChat() directly with a
        // DefaultChatRequestParameters makes OpenAiChatModel throw ClassCastException.
        ChatRequest.Builder b = ChatRequest.builder()
                .messages(lcMessages);
        if (specs != null && !specs.isEmpty()) {
            b.toolSpecifications(specs).toolChoice(ToolChoice.AUTO);
        }
        ChatRequest lcRequest = b.build();

        ChatResponse response = chatModel.chat(lcRequest);
        CompletionResponse cr = toCompletionResponse(response);
        try {
            var usage = response.tokenUsage();
            if (usage != null) {
                int in = usage.inputTokenCount() == null ? 0 : usage.inputTokenCount();
                int out = usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();
                if (in > 0 || out > 0) {
                    log.info("[llm:{}] blocking usage model={} in={} (cached={}) out={}",
                        provider, primaryModel, in, blockingCachedTokens(usage), out);
                    cr = CompletionResponse.builder()
                        .content(cr.content())
                        .toolCalls(cr.toolCalls())
                        .reasoning(cr.reasoning())
                        .finishReason(cr.finishReason())
                        .model(primaryModel)
                        .inputTokens(in)
                        .outputTokens(out)
                        .cachedInputTokens(blockingCachedTokens(usage))
                        .provider(provider)
                        .build();
                }
            }
        } catch (Exception ignored) { }
        return cr;
    }

    // === Native streaming with tools (OpenAI-compatible SSE) ===
    //
    // LangChain4j 1.0.0's OpenAI client does not surface `reasoning_content`
    // (AssistantMessage only carries `content`), so deep-thinking deltas are
    // invisible through it. This streamer talks the OpenAI chat-completions SSE
    // protocol directly: it streams `reasoning_content` as REASONING chunks,
    // `content` as TEXT chunks, and accumulates streamed `tool_calls` into the
    // terminal CompletionResponse on DONE. Only wired for OpenAI-compatible
    // clients (streamBaseUrl != null); Anthropic falls back to the blocking path.

    @Override
    public boolean supportsStreaming() {
        // Native OpenAI-compatible SSE (with reasoning_content) is only wired
        // for clients configured with a stream base URL; Anthropic et al.
        // downgrade to the blocking recovery path.
        return streamBaseUrl != null;
    }

    @Override
    public Flux<StreamChunk> completeWithToolsStream(CompletionRequest request, JsonNode tools) {
        if (streamBaseUrl == null) {
            // Non-OpenAI client (Anthropic): let the agent fall back to blocking.
            return Flux.error(new UnsupportedOperationException(
                "native SSE streaming not configured for provider " + provider));
        }

        final ObjectNode body;
        try {
            body = buildStreamRequest(request, tools);
        } catch (Exception e) {
            return Flux.error(new RuntimeException("failed to build stream request: " + e.getMessage(), e));
        }

        return Flux.create(sink -> {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
            try {
                HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(joinUrl(streamBaseUrl, "/chat/completions")))
                    .header("Authorization", "Bearer " + streamApiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    // ARK plan's gateway gates reasoning/thinking routing on UA:
                    // default JDK/curl-style UAs get a non-reasoning upstream while
                    // neutral SDK-style UAs keep reasoning_content streaming enabled.
                    .header("User-Agent", "langchain4j-open-ai/1.0.0")
                    .timeout(Duration.ofMinutes(10))
                    .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

                HttpResponse<java.io.InputStream> resp = client.send(
                    httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() != 200) {
                    String errBody = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
                    sink.error(new RuntimeException("LLM stream HTTP " + resp.statusCode() + ": "
                        + errBody.substring(0, Math.min(500, errBody.length()))));
                    return;
                }

                StringBuilder contentBuf = new StringBuilder();
                StringBuilder reasoningBuf = new StringBuilder();
                // tool_call index -> accumulator (ids/names/args arrive in fragments)
                Map<Integer, ToolCallAcc> toolAcc = new TreeMap<>();
                String finishReason = null;
                JsonNode usageFinal = null;

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (sink.isCancelled()) break;
                        if (!line.startsWith("data:")) continue;
                        String data = line.substring(5).trim();
                        if (data.isEmpty()) continue;
                        if ("[DONE]".equals(data)) break;

                        JsonNode evt;
                        try {
                            evt = om.readTree(data);
                        } catch (Exception parseErr) {
                            continue; // skip keep-alive/partial frames
                        }
                        // Terminal usage chunk (OpenAI/ARK/DeepSeek): choices is
                        // empty on the usage frame, so capture it before the skip.
                        JsonNode usageNode = evt.get("usage");
                        if (usageNode != null && usageNode.isObject()) {
                            usageFinal = usageNode;
                        }
                        JsonNode choice = evt.path("choices").path(0);
                        if (choice.isMissingNode()) continue;
                        if (choice.hasNonNull("finish_reason")) {
                            finishReason = choice.get("finish_reason").asText();
                        }
                        JsonNode delta = choice.path("delta");
                        if (delta.isMissingNode()) continue;

                        String reasoning = delta.path("reasoning_content").asText("");
                        if (!reasoning.isEmpty()) {
                            reasoningBuf.append(reasoning);
                            sink.next(StreamChunk.reasoning(reasoning));
                        }

                        String content = delta.path("content").asText("");
                        if (!content.isEmpty()) {
                            contentBuf.append(content);
                            sink.next(StreamChunk.text(content));
                        }

                        JsonNode toolCalls = delta.get("tool_calls");
                        if (toolCalls != null && toolCalls.isArray()) {
                            for (JsonNode tc : toolCalls) {
                                int idx = tc.path("index").asInt(0);
                                ToolCallAcc acc = toolAcc.computeIfAbsent(idx, k -> new ToolCallAcc());
                                if (tc.hasNonNull("id")) acc.id = tc.get("id").asText();
                                JsonNode fn = tc.get("function");
                                if (fn != null) {
                                    if (fn.hasNonNull("name")) {
                                        String n = fn.get("name").asText();
                                        if (!n.isBlank()) acc.name = n;
                                    }
                                    if (fn.hasNonNull("arguments")) {
                                        acc.args.append(fn.get("arguments").asText());
                                    }
                                }
                            }
                        }
                    }
                }

                CompletionResponse done = buildStreamedResponse(contentBuf, reasoningBuf, toolAcc, finishReason, usageFinal);
                sink.next(StreamChunk.done(done));
                sink.complete();
            } catch (Exception e) {
                sink.error(new RuntimeException("LLM stream failed: " + e.getMessage(), e));
            } finally {
                try { client.close(); } catch (Exception ignored) {}
            }
        });
    }

    /** Per-tool-call fragment accumulator for streamed {@code tool_calls} deltas. */
    static final class ToolCallAcc {
        String id;
        String name;
        final StringBuilder args = new StringBuilder();
    }

    // Package-private (not private) so the pure request/response mapping can be
    // unit-tested without a live provider: this class is the single egress for
    // every model call, so a silent mapping regression here breaks everything.
    CompletionResponse buildStreamedResponse(StringBuilder contentBuf,
                                                      StringBuilder reasoningBuf,
                                                      Map<Integer, ToolCallAcc> toolAcc,
                                                      String finishReason,
                                                      JsonNode usage) {
        int inTokens = usage != null ? usage.path("prompt_tokens").asInt(0) : 0;
        int outTokens = usage != null
            ? (usage.has("completion_tokens") ? usage.path("completion_tokens").asInt(0)
              : usage.path("output_tokens").asInt(0))
            : 0;
        int cached = cachedTokens(usage);
        if (usage != null && (inTokens > 0 || outTokens > 0)) {
            log.info("[llm:{}] stream usage model={} in={} (cached={}) out={}",
                provider, primaryModel, inTokens, cached, outTokens);
        }
        String reasoning = reasoningBuf.length() > 0 ? reasoningBuf.toString() : null;
        boolean toolCalls = "tool_calls".equals(finishReason)
            || (!toolAcc.isEmpty() && (finishReason == null || contentBuf.length() == 0));
        if (toolCalls && !toolAcc.isEmpty()) {
            List<ToolCall> calls = new ArrayList<>();
            int fallback = 0;
            for (Map.Entry<Integer, ToolCallAcc> e : toolAcc.entrySet()) {
                ToolCallAcc acc = e.getValue();
                String id = (acc.id != null && !acc.id.isBlank()) ? acc.id : ("call_" + System.nanoTime() + "_" + fallback);
                String name = acc.name != null ? acc.name : "unknown_tool";
                calls.add(new ToolCall(id, name, parseArguments(acc.args.toString())));
                fallback++;
            }
            return CompletionResponse.builder()
                .content("")
                .toolCalls(calls)
                .reasoning(reasoning)
                .finishReason("tool_calls")
                .model(primaryModel)
                .inputTokens(inTokens)
                .outputTokens(outTokens)
                .cachedInputTokens(cached)
                .provider(provider)
                .build();
        }
        return CompletionResponse.builder()
            .content(contentBuf.toString())
            .toolCalls(List.of())
            .reasoning(reasoning)
            .finishReason(finishReason != null ? finishReason : "stop")
            .model(primaryModel)
            .inputTokens(inTokens)
            .outputTokens(outTokens)
            .cachedInputTokens(cached)
            .provider(provider)
            .build();
    }

    /**
     * Extract cached/prefix-hit input tokens from provider usage shapes:
     * OpenAI/ARK {@code prompt_tokens_details.cached_tokens};
     * DeepSeek {@code prompt_cache_hit_tokens}. Returns 0 when absent.
     */
    int cachedTokens(JsonNode usage) {
        if (usage == null) return 0;
        int cached = usage.path("prompt_tokens_details").path("cached_tokens").asInt(0);
        if (cached > 0) return cached;
        cached = usage.path("prompt_cache_hit_tokens").asInt(0);
        if (cached > 0) return cached;
        return usage.path("cache_read_input_tokens").asInt(0); // anthropic-shaped
    }

    ObjectNode buildStreamRequest(CompletionRequest request, JsonNode tools) {
        ObjectNode body = om.createObjectNode();
        body.put("model", primaryModel);
        body.set("messages", toOpenAiMessages(request.messages()));
        body.put("stream", true);
        if (request.temperature() != null) body.put("temperature", request.temperature());
        if (request.maxTokens() != null && request.maxTokens() > 0) body.put("max_tokens", request.maxTokens());
        if (thinkingEnabled) {
            ObjectNode thinking = body.putObject("thinking");
            thinking.put("type", "enabled");
        }
        ArrayNode toolArr = toOpenAiTools(tools);
        if (toolArr != null && toolArr.size() > 0) {
            body.set("tools", toolArr);
            body.put("tool_choice", "auto");
        }
        // Ask providers (OpenAI-compatible) to emit a final usage chunk so we
        // can measure input/output/cached tokens per turn.
        ObjectNode streamOpts = body.putObject("stream_options");
        streamOpts.put("include_usage", true);
        return body;
    }

    /**
     * Convert our transcript messages to the OpenAI chat-completions JSON shape.
     *
     * <p>Tool-call/result pairing is validated here: an assistant {@code tool_calls}
     * entry whose tool result was never persisted (interrupted/dead turns, e.g.
     * a deployment command that restarted the server mid-execution) is an
     * "orphan call". OpenAI requires every tool_call to have a matching tool
     * response; lenient providers answer anyway but silently disable reasoning
     * output, which kills the thinking stream. Orphan calls and their stray
     * tool responses are dropped rather than sent malformed.
     */
    ArrayNode toOpenAiMessages(List<com.gantang.reaxon.api.session.Message> messages) {
        ArrayNode arr = om.createArrayNode();
        if (messages == null) return arr;

        // Pass 1: collect tool-call ids that have a persisted tool response.
        java.util.Set<String> respondedIds = new java.util.HashSet<>();
        for (var m : messages) {
            if (m.role() == com.gantang.reaxon.api.session.Message.Role.TOOL && m.toolCallId() != null) {
                respondedIds.add(m.toolCallId());
            }
        }
        // Ids we actually emit as assistant tool_calls (responses must reference one).
        java.util.Set<String> emittedCallIds = new java.util.HashSet<>();

        List<ObjectNode> nodes = new ArrayList<>();
        for (var m : messages) {
            String content = m.content();
            switch (m.role()) {
                case SYSTEM -> {
                    if (content != null && !content.isBlank()) {
                        ObjectNode n = om.createObjectNode();
                        n.put("role", "system");
                        n.put("content", content);
                        nodes.add(n);
                    }
                }
                case USER -> {
                    List<String> imageUrls = imageUrls(m.attachments());
                    boolean hasText = content != null && !content.isBlank();
                    if (!imageUrls.isEmpty()) {
                        // Multimodal OpenAI shape:
                        // content: [{type:"text",text:...},{type:"image_url",image_url:{url:...}}]
                        ObjectNode n = om.createObjectNode();
                        n.put("role", "user");
                        ArrayNode parts = n.putArray("content");
                        if (hasText) {
                            ObjectNode t = parts.addObject();
                            t.put("type", "text");
                            t.put("text", content);
                        }
                        for (String url : imageUrls) {
                            ObjectNode img = parts.addObject();
                            img.put("type", "image_url");
                            img.putObject("image_url").put("url", url);
                        }
                        nodes.add(n);
                    } else if (hasText) {
                        ObjectNode n = om.createObjectNode();
                        n.put("role", "user");
                        n.put("content", content);
                        nodes.add(n);
                    }
                }
                case ASSISTANT -> {
                    List<ToolCall> calls = m.toolCalls();
                    boolean hasText = content != null && !content.isBlank();
                    List<ToolCall> answered = calls == null ? List.of()
                        : calls.stream().filter(c -> c.callId() != null && respondedIds.contains(c.callId())).toList();
                    long dropped = calls == null ? 0 : calls.size() - answered.size();
                    if (dropped > 0) {
                        log.warn("dropping {} orphan tool_call(s) (no tool result in transcript) for provider {}: {}",
                            dropped, provider,
                            calls.stream().filter(c -> c.callId() == null || !respondedIds.contains(c.callId()))
                                .map(c -> c.callId() + ":" + c.toolName()).toList());
                    }
                    if (!answered.isEmpty()) {
                        ObjectNode n = om.createObjectNode();
                        n.put("role", "assistant");
                        if (hasText) n.put("content", content); else n.putNull("content");
                        ArrayNode tcArr = n.putArray("tool_calls");
                        for (ToolCall c : answered) {
                            emittedCallIds.add(c.callId());
                            ObjectNode t = tcArr.addObject();
                            t.put("id", c.callId());
                            t.put("type", "function");
                            ObjectNode fn = t.putObject("function");
                            fn.put("name", c.toolName());
                            fn.put("arguments", toJsonArguments(c.arguments()));
                        }
                        nodes.add(n);
                    } else if (hasText) {
                        ObjectNode n = om.createObjectNode();
                        n.put("role", "assistant");
                        n.put("content", content);
                        nodes.add(n);
                    }
                }
                case TOOL -> {
                    // Drop stray tool responses whose assistant tool_call was itself dropped.
                    if (m.toolCallId() == null || !emittedCallIds.contains(m.toolCallId())) {
                        if (m.toolCallId() != null && respondedIds.contains(m.toolCallId())) {
                            log.warn("dropping orphan tool response (no matching tool_call): {}", m.toolCallId());
                        }
                        break;
                    }
                    ObjectNode n = om.createObjectNode();
                    n.put("role", "tool");
                    n.put("tool_call_id", m.toolCallId());
                    n.put("content", content != null ? content : "");
                    nodes.add(n);
                }
            }
        }
        nodes.forEach(arr::add);
        return arr;
    }

    /** Convert our flat tool schemas to OpenAI's {@code {type:function, function:{...}}} shape. */
    ArrayNode toOpenAiTools(JsonNode tools) {
        if (tools == null || !tools.isArray() || tools.isEmpty()) return null;
        ArrayNode arr = om.createArrayNode();
        for (JsonNode node : tools) {
            String name = node.path("name").asText("");
            if (name.isBlank()) continue;
            ObjectNode wrapper = arr.addObject();
            wrapper.put("type", "function");
            ObjectNode fn = wrapper.putObject("function");
            fn.put("name", name);
            fn.put("description", node.path("description").asText(""));
            ObjectNode params = fn.putObject("parameters");
            params.put("type", "object");
            JsonNode props = node.get("properties");
            params.set("properties", props != null ? props.deepCopy() : om.createObjectNode());
            JsonNode req = node.get("required");
            params.set("required", req != null ? req.deepCopy() : om.createArrayNode());
        }
        return arr;
    }

    static String joinUrl(String base, String path) {
        return base.endsWith("/") ? base.substring(0, base.length() - 1) + path : base + path;
    }

    // === Tool specification conversion ===
    // Maps our JSON Schema to LangChain4j JsonSchemaElement hierarchy:
    // JsonStringSchema / JsonIntegerSchema / JsonNumberSchema / JsonBooleanSchema / JsonObjectSchema

    private List<ToolSpecification> toToolSpecifications(JsonNode tools) {
        if (tools == null || !tools.isArray() || tools.isEmpty()) {
            return List.of();
        }
        List<ToolSpecification> specs = new ArrayList<>();
        for (JsonNode node : tools) {
            JsonNode props = node.get("properties");
            JsonNode required = node.get("required");

            // Resolve tool name: explicit "name" field > first key of properties
            String name = null;
            if (node.has("name") && !node.get("name").isNull()) {
                name = node.get("name").asText();
            } else if (props != null && props.isObject() && props.fields().hasNext()) {
                name = props.fields().next().getKey();
            }
            if (name == null || name.isBlank()) continue;

            // Resolve description
            String description = null;
            if (node.has("description") && !node.get("description").isNull()) {
                description = node.get("description").asText();
            } else if (node.has("title") && !node.get("title").isNull()) {
                description = node.get("title").asText();
            }

            // Build parameters schema
            JsonObjectSchema paramSchema = buildParameterSchema(props, required);

            ToolSpecification spec = ToolSpecification.builder()
                    .name(name)
                    .description(description != null ? description : "")
                    .parameters(paramSchema)
                    .build();
            specs.add(spec);
        }
        return specs;
    }

    private JsonObjectSchema buildParameterSchema(JsonNode props, JsonNode required) {
        if (props == null || !props.isObject()) {
            return JsonObjectSchema.builder().build();
        }

        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();

        for (Iterator<Map.Entry<String, JsonNode>> it = props.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            String paramName = field.getKey();
            JsonNode paramDef = field.getValue();

            JsonSchemaElement schemaEl = toSchemaElement(paramName, paramDef);
            builder.addProperty(paramName, schemaEl);
        }

        // Set required list
        if (required != null && required.isArray()) {
            List<String> requiredList = new ArrayList<>();
            required.forEach(n -> requiredList.add(n.asText()));
            builder.required(requiredList);
        }

        return builder.build();
    }

    private JsonSchemaElement toSchemaElement(String paramName, JsonNode paramDef) {
        String type = paramDef.has("type") ? paramDef.get("type").asText() : "string";

        JsonSchemaElement base;
        switch (type) {
            case "string"   -> base = JsonStringSchema.builder()
                    .description(descriptionOf(paramDef))
                    .build();
            case "integer"  -> base = JsonIntegerSchema.builder()
                    .description(descriptionOf(paramDef))
                    .build();
            case "number"   -> base = JsonNumberSchema.builder()
                    .description(descriptionOf(paramDef))
                    .build();
            case "boolean"  -> base = JsonBooleanSchema.builder()
                    .description(descriptionOf(paramDef))
                    .build();
            case "object"    -> base = buildParameterSchema(paramDef.get("properties"), paramDef.get("required"));
            case "array"    -> {
                JsonSchemaElement items = null;
                if (paramDef.has("items") && paramDef.get("items").isObject()) {
                    items = toSchemaElement(paramName, paramDef.get("items"));
                }
                // LangChain4j JsonArraySchema doesn't expose items builder method,
                // wrap in JsonObjectSchema as workaround
                base = JsonObjectSchema.builder()
                        .description(descriptionOf(paramDef))
                        .build();
            }
            default          -> base = JsonStringSchema.builder()
                    .description(descriptionOf(paramDef))
                    .build();
        }

        // LangChain4j 1.0.0 does not support enum on JsonStringSchema directly.
        // Enum values are passed as plain string with description documenting the allowed values.
        // The LLM will understand the description text as the enum constraint.
        return base;
    }

    private String descriptionOf(JsonNode paramDef) {
        if (paramDef.has("description") && !paramDef.get("description").isNull()) {
            return paramDef.get("description").asText();
        }
        return null;
    }

    /**
     * Extract prompt-cache hits from provider-specific TokenUsage subclasses.
     * Core {@code TokenUsage} carries no cache fields; OpenAI/ARK expose
     * OpenAiTokenUsage.InputTokensDetails.cachedTokens(), Anthropic exposes
     * cacheReadInputTokens(). Everything else reports 0.
     */
    private static int blockingCachedTokens(dev.langchain4j.model.output.TokenUsage usage) {
        if (usage == null) return 0;
        try {
            if (usage instanceof dev.langchain4j.model.openai.OpenAiTokenUsage oai
                    && oai.inputTokensDetails() != null
                    && oai.inputTokensDetails().cachedTokens() != null) {
                return oai.inputTokensDetails().cachedTokens();
            }
        } catch (NoClassDefFoundError ignored) { /* open-ai module absent */ }
        try {
            if (usage instanceof dev.langchain4j.model.anthropic.AnthropicTokenUsage ant
                    && ant.cacheReadInputTokens() != null) {
                return ant.cacheReadInputTokens();
            }
        } catch (NoClassDefFoundError ignored) { /* anthropic module absent */ }
        return 0;
    }

    // === Response conversion ===

    private CompletionResponse toCompletionResponse(ChatResponse response) {
        AiMessage ai = response.aiMessage();
        if (ai != null && ai.hasToolExecutionRequests()) {
            List<ToolCall> calls = ai.toolExecutionRequests().stream()
                    .map(tc -> new ToolCall(
                            tc.id(),
                            tc.name(),
                            parseArguments(tc.arguments())
                    ))
                    .toList();
            return CompletionResponse.builder()
                    .content("")
                    .toolCalls(calls)
                    .finishReason("tool_calls")
                    .provider(provider)
                    .build();
        }

        String text = (ai != null) ? ai.text() : "";
        String finish = response.finishReason() != null
                ? response.finishReason().name().toLowerCase()
                : "stop";
        return CompletionResponse.builder()
                .content(text)
                .toolCalls(List.of())
                .finishReason(finish)
                .provider(provider)
                .build();
    }

    // === Message conversion ===
    // LangChain4j 1.0.0: tool calls/results MUST use AiMessage(toolExecutionRequests)
    // and ToolExecutionResultMessage. Emitting them as UserMessage (the old workaround)
    // makes parallel tool calls produce consecutive UserMessages, which LC4j's
    // MessageSanitizer silently drops before the request is sent — the model then
    // never sees any tool output and retries the same calls until MAX_ITERATIONS.

    List<ChatMessage> toLangChainMessages(List<com.gantang.reaxon.api.session.Message> messages) {
        if (messages == null) return List.of();
        List<ChatMessage> result = new ArrayList<>();
        // callId -> tool name, so TOOL messages can reference the request they answer.
        Map<String, String> toolNames = new HashMap<>();
        for (var msg : messages) {
            String content = msg.content();
            switch (msg.role()) {
                case SYSTEM    -> {
                    // LangChain4j rejects blank SystemMessage text; skip empties.
                    if (content != null && !content.isBlank()) result.add(SystemMessage.from(content));
                }
                case USER      -> {
                    // Multimodal: image attachments ride along as ImageContent so
                    // vision-capable models actually see them. Attachment map is
                    // type -> url (see AgentContext.Attachment); only String values
                    // whose key mentions "image" are treated as images.
                    List<String> imageUrls = imageUrls(msg.attachments());
                    boolean hasText = content != null && !content.isBlank();
                    if (!imageUrls.isEmpty()) {
                        List<Content> parts = new ArrayList<>();
                        if (hasText) parts.add(TextContent.from(content));
                        for (String url : imageUrls) parts.add(new ImageContent(url));
                        // Image-only messages are legal: UserMessage.from(List) accepts pure-image.
                        result.add(UserMessage.from(parts));
                    } else if (hasText) {
                        // Blank UserMessage text crashes LC4j ("text cannot be null or blank"); skip.
                        result.add(UserMessage.from(content));
                    }
                }
                case ASSISTANT -> {
                    List<ToolCall> calls = msg.toolCalls();
                    List<ToolExecutionRequest> requests = (calls == null) ? List.of() : calls.stream()
                        .map(tc -> {
                            toolNames.put(tc.callId(), tc.toolName());
                            return ToolExecutionRequest.builder()
                                .id(tc.callId())
                                .name(tc.toolName())
                                .arguments(toJsonArguments(tc.arguments()))
                                .build();
                        })
                        .toList();
                    boolean hasText = content != null && !content.isBlank();
                    if (!requests.isEmpty()) {
                        // Tool-call turn: requests are required; text is optional.
                        result.add(hasText
                            ? AiMessage.from(content, requests)
                            : AiMessage.from(requests));
                    } else if (hasText) {
                        result.add(AiMessage.from(content));
                    }
                    // Blank text with no tool calls: skip (AiMessage.from("") is illegal).
                }
                case TOOL      -> {
                    String body = content != null ? content : "";
                    String name = toolNames.getOrDefault(msg.toolCallId(), "unknown_tool");
                    result.add(ToolExecutionResultMessage.from(
                        msg.toolCallId(), name, body));
                }
            }
        }
        return result;
    }

    /**
     * Extract image URLs from a message attachment map (type -> url). Entries whose
     * key lower-cases to contain "image" and whose value is a non-blank String are
     * treated as images; both data: URIs and http(s) URLs pass through as-is.
     */
    static List<String> imageUrls(Map<String, Object> attachments) {
        if (attachments == null || attachments.isEmpty()) return List.of();
        List<String> urls = new ArrayList<>();
        for (Map.Entry<String, Object> e : attachments.entrySet()) {
            if (e.getKey() == null || !(e.getValue() instanceof String url) || url.isBlank()) continue;
            if (e.getKey().toLowerCase().contains("image")) urls.add(url);
        }
        return urls;
    }

    /** Serialize a tool call's argument map to the JSON string LC4j expects. */
    private String toJsonArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) return "{}";
        try {
            return om.writeValueAsString(arguments);
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(String args) {
        if (args == null || args.isBlank()) return Map.of();
        try {
            return om.readValue(args, Map.class);
        } catch (Exception e) {
            return Map.of("raw", args);
        }
    }
}
