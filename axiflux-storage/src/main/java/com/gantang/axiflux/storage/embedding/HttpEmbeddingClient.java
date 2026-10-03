package com.gantang.axiflux.storage.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HTTP-based embedding client for OpenAI-compatible /v1/embeddings endpoints,
 * with explicit support for the Volcano ARK <b>multimodal</b> embedding endpoint
 * ({@code /api/v3/embeddings/multimodal}, e.g. {@code doubao-embedding-vision-*}).
 *
 * Supports:
 * - OpenAI text-embedding-3-small / text-embedding-ada-002
 * - Any OpenAI-compatible proxy (e.g., local TEI, Azure OpenAI)
 * - ARK multimodal embeddings (auto-detected from the endpoint path):
 *   request {@code input} is a content-item array {@code [{"type":"text","text":...}]}
 *   and the vector comes back at {@code data.embedding} instead of {@code data[0].embedding}.
 *
 * Falls back to {@code null} on failure so memory operations degrade to keyword
 * search rather than crashing or polluting the vector store.
 */
public class HttpEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(HttpEmbeddingClient.class);
    private static final ObjectMapper OM = new ObjectMapper();

    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final int dimension;
    private final boolean multimodal;
    private final HttpClient http;

    /** Max retries for transient 429/5xx responses (P2-9). */
    private static final int MAX_RETRIES = 2;

    public HttpEmbeddingClient(String endpoint, String apiKey, String model, int dimension) {
        this(endpoint, apiKey, model, dimension, null);
    }

    /**
     * @param proxy optional egress {@link java.net.ProxySelector} (P1-5); when
     *              non-null the embedding HTTP calls transit the configured
     *              forward proxy instead of connecting directly. Null = direct.
     */
    public HttpEmbeddingClient(String endpoint, String apiKey, String model, int dimension,
                               java.net.ProxySelector proxy) {
        this.endpoint = endpoint != null && !endpoint.isBlank()
            ? endpoint : "https://api.openai.com/v1/embeddings";
        this.apiKey = apiKey != null ? apiKey : "";
        this.model = model != null && !model.isBlank() ? model : "text-embedding-3-small";
        this.dimension = dimension;
        // ARK multimodal embeddings use a distinct path and wire format.
        this.multimodal = this.endpoint.endsWith("/embeddings/multimodal");
        this.http = com.gantang.reaxon.impl.tool.support.EgressProxy.applyTo(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)), proxy)
            .build();
    }

    /**
     * Generate an embedding vector for the given text, or {@code null} when the
     * embedding endpoint is unreachable / misconfigured / returns an error.
     *
     * <p>Callers MUST handle {@code null}: store the item without a vector (it
     * remains retrievable by keyword) and degrade search to keyword matching —
     * never persist or query with a zero vector, whose cosine distance is
     * undefined and would silently scramble vector-search ranking.
     */
    /** Sentinel to distinguish "retryable failure" from "permanent failure". */
    private static final float[] RETRY = new float[0];

    public float[] embedOrNull(String text) {
        if (text == null || text.isBlank()) return null;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                // Exponential back-off between retries: 500ms, 1000ms.
                try { Thread.sleep(500L * (1L << (attempt - 1))); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
            }
            float[] result = doEmbed(text, attempt);
            if (result != null && result != RETRY) return result;
            if (result == null) return null; // permanent failure, no retry
            // result == RETRY → loop continues
        }
        // All retries exhausted.
        return null;
    }

    /**
     * Single embedding attempt. Returns the vector on success, {@code null} on
     * failure. Retries only on transient HTTP statuses (429, 5xx).
     */
    private float[] doEmbed(String text, int attempt) {
        try {
            // ARK multimodal: input is an array of content items; OpenAI: plain string.
            String body = multimodal
                ? OM.writeValueAsString(java.util.Map.of("model", model,
                    "input", java.util.List.of(java.util.Map.of("type", "text", "text", text))))
                : OM.writeValueAsString(java.util.Map.of("model", model, "input", text));

            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body));

            if (!apiKey.isBlank()) {
                rb.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                JsonNode node = OM.readTree(resp.body());
                // OpenAI: data[0].embedding; ARK multimodal: data.embedding (single object).
                JsonNode embNode = node.at("/data/0/embedding");
                if (!embNode.isArray() || embNode.size() == 0) embNode = node.at("/data/embedding");
                if (embNode.isArray() && embNode.size() > 0) {
                    float[] vec = new float[embNode.size()];
                    for (int i = 0; i < vec.length; i++) {
                        vec[i] = (float) embNode.get(i).asDouble();
                    }
                    if (isZeroVector(vec)) {
                        log.warn("Embedding endpoint {} returned an all-zero vector for model {} "
                            + "(check model name / API key); vector memory degraded", endpoint, model);
                        return null;
                    }
                    if (vec.length != dimension) {
                        // A mismatched dimension would fail at INSERT (or silently corrupt a
                        // recreated table); degrade to keyword search with an actionable message.
                        log.error("Embedding dimension mismatch: model {} returned {} dims but the "
                            + "vector column is configured for {} dims. Rebuild the memory_items "
                            + "table (DROP TABLE memory_items) after setting axiflux.vector.dimension "
                            + "to {} and restarting. Vector memory degraded to keyword search.",
                            model, vec.length, dimension, vec.length);
                        return null;
                    }
                    return vec;
                }
            }

            // Non-2xx: retry on 429/5xx, otherwise log and degrade.
            if (shouldRetry(resp.statusCode()) && attempt < MAX_RETRIES) {
                log.warn("Embedding API returned {} (attempt {}/{}), retrying: {}",
                    resp.statusCode(), attempt + 1, MAX_RETRIES + 1, endpoint);
                return RETRY;
            }
            log.warn("Embedding API returned status {} from {} (vector memory degraded; "
                    + "items still stored for keyword search): {}",
                resp.statusCode(), endpoint,
                resp.body() != null && resp.body().length() > 200 ? resp.body().substring(0, 200) : resp.body());
        } catch (Exception e) {
            log.warn("Embedding generation failed (endpoint={}, model={}): {} — vector memory degraded",
                endpoint, model, e.getMessage());
        }
        return null;
    }

    private static boolean shouldRetry(int status) {
        return status == 429 || (status >= 500 && status < 600);
    }

    /**
     * Generate an embedding; returns a zero vector on failure for backward
     * compatibility. New code should prefer {@link #embedOrNull} and handle
     * {@code null} explicitly to avoid polluting vector search.
     */
    public float[] embed(String text) {
        float[] v = embedOrNull(text);
        return v != null ? v : zeroVector();
    }

    /** True when a vector is null or all zeros (cosine distance undefined). */
    public static boolean isZeroVector(float[] v) {
        if (v == null) return true;
        for (float x : v) {
            if (x != 0f) return false;
        }
        return true;
    }

    public int dimension() { return dimension; }

    private float[] zeroVector() {
        return new float[dimension];
    }
}
