package com.gantang.tianshu.storage.vector;

/**
 * Neutral vector-store configuration shared by the storage backends
 * ({@code PGVectorLongTermMemory}, {@code QdrantVectorMemory}, code index).
 *
 * <p>Lives in the storage module so no backend needs to depend on the spring
 * wiring layer just to read its knobs. The spring
 * {@code com.gantang.tianshu.spring.config.props.VectorProperties} binder maps onto
 * this at the assembly edge; field semantics (and defaults) are identical.
 *
 * @param provider       "pgvector" | "qdrant" | "none"
 * @param url            qdrant endpoint (pgvector uses the JDBC DataSource instead)
 * @param apiKey         qdrant API key (nullable)
 * @param collectionName qdrant collection (pgvector manages its own tables)
 * @param dimension      embedding dimension (default 1536)
 * @param metric         distance metric name (default "cosine")
 * @param embedUrl       embedding endpoint (OpenAI-compatible /v1/embeddings)
 * @param embedApiKey    embedding API key (nullable)
 * @param embedModel     embedding model id (default text-embedding-3-small)
 */
public record VectorStoreConfig(
    String provider,
    String url,
    String apiKey,
    String collectionName,
    int dimension,
    String metric,
    String embedUrl,
    String embedApiKey,
    String embedModel
) {
    public VectorStoreConfig {
        if (provider == null) provider = "none";
        if (url == null || url.isBlank()) url = "http://localhost:6333";
        if (collectionName == null || collectionName.isBlank()) collectionName = "tianshu_memory";
        if (dimension <= 0) dimension = 1536;
        if (metric == null || metric.isBlank()) metric = "cosine";
        if (embedUrl == null || embedUrl.isBlank()) embedUrl = "https://api.openai.com/v1/embeddings";
        if (embedApiKey == null) embedApiKey = "";
        if (embedModel == null || embedModel.isBlank()) embedModel = "text-embedding-3-small";
    }

    /** Embedding/dimension subset shared by every backend. */
    public VectorStoreConfig(String url, String apiKey, String collectionName,
                             int dimension, String metric,
                             String embedUrl, String embedApiKey, String embedModel) {
        this("none", url, apiKey, collectionName, dimension, metric, embedUrl, embedApiKey, embedModel);
    }
}
