package com.gantang.tianshu.spring.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Binds {@code tianshu.vector.*}. Split out of the monolithic {@code TianshuProperties}
 * (P5 config decoupling) into an independent {@code @ConfigurationProperties} bean.
 */
@ConfigurationProperties(prefix = "tianshu.vector")


// ===== Vector Store =====

public class VectorProperties {
    /** "pgvector" | "qdrant" | "none" — unset/"none" disables long-term memory (no-op). */
    private String provider = "none";
    private String url = "http://localhost:6333";
    private String apiKey;
    private String collectionName = "tianshu_memory";
    private int dimension = 1536;
    private String metric = "cosine";
    /** Embedding endpoint (OpenAI-compatible /v1/embeddings; ARK text: /api/v3/embeddings; ARK multimodal e.g. doubao-embedding-vision-*: /api/v3/embeddings/multimodal — wire format auto-detected from path). */
    private String embedUrl = "https://api.openai.com/v1/embeddings";
    /** Embedding API key (ARK deployments can reuse the ARK API key). */
    private String embedApiKey;
    /** Embedding model name (OpenAI text-embedding-3-small; ARK doubao-embedding endpoint/model id). */
    private String embedModel = "text-embedding-3-small";
    public String getProvider() { return provider; }
    public void setProvider(String v) { this.provider = v; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getCollectionName() { return collectionName; }
    public void setCollectionName(String collectionName) { this.collectionName = collectionName; }
    public int getDimension() { return dimension; }
    public void setDimension(int dimension) { this.dimension = dimension; }
    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }
    public String getEmbedUrl() { return embedUrl; }
    public void setEmbedUrl(String embedUrl) { this.embedUrl = embedUrl; }
    public String getEmbedApiKey() { return embedApiKey; }
    public void setEmbedApiKey(String embedApiKey) { this.embedApiKey = embedApiKey; }
    public String getEmbedModel() { return embedModel; }
    public void setEmbedModel(String embedModel) { this.embedModel = embedModel; }
}
