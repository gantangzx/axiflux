package com.gantang.axiflux.spring.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gantang.reaxon.api.memory.SummaryGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * LLM-based {@link SummaryGenerator} that calls an OpenAI-compatible
 * chat completions endpoint to generate concise summaries.
 *
 * <p>Falls back to truncating summarizer on any API error so memory
 * compression never blocks the system.
 *
 * <p>Design pattern: <b>Strategy</b> — concrete SummaryGenerator backed by LLM.
 */
public class LlmSummaryGenerator implements SummaryGenerator {

    private static final Logger log = LoggerFactory.getLogger(LlmSummaryGenerator.class);
    private static final ObjectMapper OM = new ObjectMapper();
    private static final SummaryGenerator FALLBACK = SummaryGenerator.truncating();

    private final String apiUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient http;

    public LlmSummaryGenerator(String apiUrl, String apiKey, String model) {
        this.apiUrl = apiUrl != null && !apiUrl.isBlank()
            ? apiUrl : "https://api.openai.com/v1/chat/completions";
        this.apiKey = apiKey != null ? apiKey : "";
        this.model = model != null && !model.isBlank() ? model : "gpt-4o-mini";
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    @Override
    public String summarize(String content) {
        if (content == null || content.isBlank()) return "";
        if (content.length() <= 100) return content;

        try {
            ObjectNode body = OM.createObjectNode();
            body.put("model", model);
            body.put("temperature", 0.3);
            body.put("max_tokens", 150);

            var messages = body.putArray("messages");
            var sysMsg = messages.addObject();
            sysMsg.put("role", "system");
            sysMsg.put("content",
                "You are a summarization engine. Summarize the given text in 2-3 sentences. " +
                "Preserve key facts, names, and decisions. Output only the summary, no preamble.");
            var userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", content.length() > 4000 ? content.substring(0, 4000) : content);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(OM.writeValueAsString(body)))
                .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Summary API returned {}: {}", response.statusCode(), response.body());
                return FALLBACK.summarize(content);
            }

            JsonNode root = OM.readTree(response.body());
            String summary = root.path("choices").path(0).path("message").path("content").asText("");
            if (summary.isBlank()) {
                return FALLBACK.summarize(content);
            }
            return summary.trim();
        } catch (Exception e) {
            log.debug("LLM summary failed, falling back to truncation: {}", e.getMessage());
            return FALLBACK.summarize(content);
        }
    }
}
