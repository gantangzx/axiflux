package com.gantang.tianshu.spring.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gantang.tianshu.api.vision.VisionClient;
import com.gantang.tianshu.spring.config.props.VisionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * OpenAI-compatible vision client (GPT-4o, GPT-4V, or compatible proxies).
 *
 * <p>Sends a chat completion request with an image_url content part.
 *
 * <p>Design pattern: <b>Strategy</b> — concrete implementation of {@link VisionClient}.
 */
public class OpenAiVisionClient implements VisionClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiVisionClient.class);
    private static final ObjectMapper OM = new ObjectMapper();

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int maxTokens;
    private final HttpClient http;

    public OpenAiVisionClient(VisionProperties config) {
        this.apiKey = config.getApiKey();
        this.baseUrl = config.getBaseUrl().replaceAll("/+$", "");
        this.model = config.getModel();
        this.maxTokens = config.getMaxTokens();
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    @Override
    public String analyze(String imageUrl, String prompt) {
        try {
            ObjectNode body = OM.createObjectNode();
            body.put("model", model);
            body.put("max_tokens", maxTokens);

            ArrayNode messages = body.putArray("messages");
            ObjectNode msg = messages.addObject();
            msg.put("role", "user");

            ArrayNode content = msg.putArray("content");
            ObjectNode textPart = content.addObject();
            textPart.put("type", "text");
            textPart.put("text", prompt);

            ObjectNode imagePart = content.addObject();
            imagePart.put("type", "image_url");
            ObjectNode imageUrlObj = imagePart.putObject("image_url");
            imageUrlObj.put("url", imageUrl);

            HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(OM.writeValueAsString(body)));

            if (apiKey != null && !apiKey.isBlank()) {
                rb.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> resp = http.send(rb.build(),
                HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                log.warn("Vision API returned {}: {}", resp.statusCode(), resp.body());
                throw new RuntimeException("Vision API HTTP " + resp.statusCode());
            }

            JsonNode root = OM.readTree(resp.body());
            return root.at("/choices/0/message/content").asText("(no response)");
        } catch (Exception e) {
            throw new RuntimeException("Vision analysis failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean available() {
        return apiKey != null && !apiKey.isBlank();
    }
}
