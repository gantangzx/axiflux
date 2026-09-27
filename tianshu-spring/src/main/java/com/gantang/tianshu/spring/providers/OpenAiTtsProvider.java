package com.gantang.tianshu.spring.providers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gantang.tianshu.api.tts.TtsProvider;
import com.gantang.tianshu.spring.config.props.TtsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * OpenAI-compatible TTS provider (tts-1, tts-1-hd, or compatible).
 *
 * <p>Design pattern: <b>Strategy</b> — concrete {@link TtsProvider}.
 */
public class OpenAiTtsProvider implements TtsProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiTtsProvider.class);
    private static final ObjectMapper OM = new ObjectMapper();

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final String defaultVoice;
    private final String format;
    private final HttpClient http;

    public OpenAiTtsProvider(TtsProperties config) {
        this.apiKey = config.getApiKey();
        this.baseUrl = config.getBaseUrl().replaceAll("/+$", "");
        this.model = config.getModel();
        this.defaultVoice = config.getDefaultVoice();
        this.format = config.getFormat();
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    @Override
    public AudioResult synthesize(String text, String voice) {
        try {
            ObjectNode body = OM.createObjectNode();
            body.put("model", model);
            body.put("input", text);
            body.put("voice", voice != null ? voice : defaultVoice);
            body.put("response_format", format);

            HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/audio/speech"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(OM.writeValueAsString(body)));

            if (apiKey != null && !apiKey.isBlank()) {
                rb.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<byte[]> resp = http.send(rb.build(),
                HttpResponse.BodyHandlers.ofByteArray());

            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new RuntimeException("TTS API HTTP " + resp.statusCode());
            }

            String mimeType = switch (format.toLowerCase()) {
                case "mp3" -> "audio/mpeg";
                case "wav" -> "audio/wav";
                case "opus" -> "audio/opus";
                case "aac" -> "audio/aac";
                case "flac" -> "audio/flac";
                default -> "audio/mpeg";
            };

            return new AudioResult(resp.body(), mimeType, format);
        } catch (Exception e) {
            throw new RuntimeException("TTS synthesis failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean available() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String defaultVoice() {
        return defaultVoice;
    }
}
