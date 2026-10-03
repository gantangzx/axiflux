package com.gantang.reaxon.eval.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gantang.reaxon.api.llm.CompletionRequest;
import com.gantang.reaxon.api.llm.CompletionResponse;
import com.gantang.reaxon.api.llm.LlmClient;
import com.gantang.reaxon.api.session.Message;
import com.gantang.reaxon.api.tool.ToolCall;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minimal OpenAI-compatible chat client for live eval mode, built on the JDK
 * {@link HttpClient} — zero Spring, zero LangChain4j, so the eval module stays
 * core-only.
 *
 * <p>Talks to any {@code /chat/completions} endpoint: Volcengine ARK
 * ({@code /api/plan/v3} or {@code /api/v3}), DeepSeek, vLLM, Ollama
 * ({@code /v1}), LM Studio, etc. Tool calling uses the standard OpenAI
 * function-calling schema; the agent's internal tool-definition shape
 * ({@code {name, description, properties, required}}) is converted here.
 *
 * <p>Error messages deliberately carry HTTP status codes and provider bodies
 * so {@code LlmErrorClassifier} can classify auth (401/403), overflow
 * (context-length) and transient (timeout/5xx) failures for the fallback chain.
 */
public class OpenAiCompatLlmClient implements LlmClient {

    private final String provider;
    private final String apiKey;
    private final String chatUrl;
    private final String model;
    private final int contextWindow;
    private final ObjectMapper om = new ObjectMapper();
    private final HttpClient http;
    private final Duration requestTimeout;

    public OpenAiCompatLlmClient(String provider, String apiKey, String baseUrl, String model) {
        this(provider, apiKey, baseUrl, model, 128_000, 120);
    }

    public OpenAiCompatLlmClient(String provider, String apiKey, String baseUrl,
                                 String model, int contextWindowTokens, int timeoutSeconds) {
        this.provider = provider == null || provider.isBlank() ? "openai-compat" : provider;
        this.apiKey = apiKey;
        this.model = model;
        this.contextWindow = contextWindowTokens > 0 ? contextWindowTokens : 128_000;
        this.requestTimeout = Duration.ofSeconds(timeoutSeconds > 0 ? timeoutSeconds : 120);
        String root = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.chatUrl = root + "/chat/completions";
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String provider() {
        return provider;
    }

    @Override
    public String primaryModel() {
        return model;
    }

    @Override
    public int contextWindowTokens() {
        return contextWindow;
    }

    @Override
    public CompletionResponse complete(CompletionRequest request) {
        return chat(request, null);
    }

    @Override
    public CompletionResponse completeWithTools(CompletionRequest request, JsonNode tools) {
        return chat(request, tools);
    }

    @Override
    public Flux<String> completeStream(CompletionRequest request) {
        // The agent's tool loop uses completeWithTools(); this path is only a
        // fallback for stream-only callers — split the full reply on spaces.
        String text = complete(request).content();
        return Flux.fromArray(text.split("(?<= )"));
    }

    private CompletionResponse chat(CompletionRequest request, JsonNode tools) {
        ObjectNode body = om.createObjectNode();
        body.put("model", model);
        body.set("messages", toOpenAiMessages(om, request.messages()));
        body.put("temperature", request.temperature() != null ? request.temperature() : 0.7);
        if (request.maxTokens() != null) {
            body.put("max_tokens", request.maxTokens());
        }
        if (tools != null && tools.isArray() && !tools.isEmpty()) {
            body.set("tools", toOpenAiTools(om, tools));
            body.put("tool_choice", "auto");
        }

        HttpResponse<String> resp;
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(chatUrl))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body)));
            if (apiKey != null && !apiKey.isBlank()) {
                rb.header("Authorization", "Bearer " + apiKey);
            }
            resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException("LLM request to " + chatUrl + " failed: "
                    + e.getClass().getSimpleName() + " " + e.getMessage(), e);
        }

        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            String snippet = resp.body() == null ? "" :
                    resp.body().substring(0, Math.min(500, resp.body().length()));
            throw new RuntimeException("HTTP " + resp.statusCode() + " from " + chatUrl + ": " + snippet);
        }

        try {
            return parseResponse(resp.body());
        } catch (Exception e) {
            throw new RuntimeException("Cannot parse LLM response from " + chatUrl + ": " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private CompletionResponse parseResponse(String json) throws Exception {
        JsonNode root = om.readTree(json);
        JsonNode choice = root.path("choices").path(0);
        JsonNode msg = choice.path("message");

        String content = msg.path("content").isTextual() ? msg.get("content").asText() : "";
        String finishReason = choice.path("finish_reason").asText("stop");

        List<ToolCall> toolCalls = new ArrayList<>();
        JsonNode rawCalls = msg.get("tool_calls");
        if (rawCalls != null && rawCalls.isArray()) {
            for (JsonNode tc : rawCalls) {
                String id = tc.path("id").asText("call_" + toolCalls.size());
                JsonNode fn = tc.path("function");
                String name = fn.path("name").asText("");
                String argsJson = fn.path("arguments").asText("{}");
                Map<String, Object> args;
                try {
                    args = om.readValue(argsJson, Map.class);
                } catch (Exception parseErr) {
                    args = Map.of("raw", argsJson);
                }
                toolCalls.add(new ToolCall(id, name, args));
            }
        }

        int inTokens = root.path("usage").path("prompt_tokens").asInt(0);
        int outTokens = root.path("usage").path("completion_tokens").asInt(0);

        return CompletionResponse.builder()
                .content(content)
                .toolCalls(toolCalls)
                .finishReason(toolCalls.isEmpty() ? finishReason : "tool_calls")
                .inputTokens(inTokens)
                .outputTokens(outTokens)
                .model(model)
                .build();
    }

    /**
     * Convert framework {@link Message}s to OpenAI chat-message JSON.
     * Package-private for unit testing.
     */
    public static ArrayNode toOpenAiMessages(ObjectMapper om, List<Message> messages) {
        ArrayNode out = om.createArrayNode();
        for (Message m : messages) {
            if (m.role() == null) continue;
            String content = m.content();
            switch (m.role()) {
                case SYSTEM, USER -> {
                    if (content != null && !content.isBlank()) {
                        out.addObject()
                                .put("role", m.role().name().toLowerCase())
                                .put("content", content);
                    }
                }
                case ASSISTANT -> {
                    List<ToolCall> calls = m.toolCalls();
                    boolean hasCalls = calls != null && !calls.isEmpty();
                    boolean hasText = content != null && !content.isBlank();
                    if (hasCalls) {
                        ObjectNode node = out.addObject().put("role", "assistant");
                        if (hasText) {
                            node.put("content", content);
                        } else {
                            node.putNull("content");
                        }
                        ArrayNode tcArr = node.putArray("tool_calls");
                        for (ToolCall tc : calls) {
                            ObjectNode tcNode = tcArr.addObject()
                                    .put("id", tc.callId())
                                    .put("type", "function");
                            String argsJson;
                            try {
                                argsJson = om.writeValueAsString(
                                        tc.arguments() != null ? tc.arguments() : Map.of());
                            } catch (Exception e) {
                                argsJson = "{}";
                            }
                            tcNode.putObject("function")
                                    .put("name", tc.toolName())
                                    .put("arguments", argsJson);
                        }
                    } else if (hasText) {
                        out.addObject().put("role", "assistant").put("content", content);
                    }
                }
                case TOOL -> {
                    if (m.toolCallId() != null) {
                        out.addObject()
                                .put("role", "tool")
                                .put("tool_call_id", m.toolCallId())
                                .put("content", content != null ? content : "");
                    }
                }
            }
        }
        return out;
    }

    /**
     * Convert the agent's tool-definition shape ({@code {name, description,
     * properties, required}}) to the OpenAI function-calling schema.
     * Package-private for unit testing.
     */
    public static ArrayNode toOpenAiTools(ObjectMapper om, JsonNode agentTools) {
        ArrayNode out = om.createArrayNode();
        for (JsonNode tool : agentTools) {
            String name = tool.path("name").asText("");
            if (name.isBlank()) continue;
            ObjectNode fn = om.createObjectNode();
            fn.put("name", name);
            fn.put("description", tool.path("description").asText(""));
            ObjectNode parameters = fn.putObject("parameters");
            parameters.put("type", "object");
            parameters.set("properties", tool.has("properties") && tool.get("properties").isObject()
                    ? tool.get("properties").deepCopy()
                    : om.createObjectNode());
            if (tool.has("required") && tool.get("required").isArray() && !tool.get("required").isEmpty()) {
                parameters.set("required", tool.get("required").deepCopy());
            }
            out.addObject().put("type", "function").set("function", fn);
        }
        return out;
    }
}
