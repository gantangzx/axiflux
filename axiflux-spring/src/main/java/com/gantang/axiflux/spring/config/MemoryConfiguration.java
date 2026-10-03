package com.gantang.axiflux.spring.config;

import com.gantang.reaxon.api.memory.ContextAssembler;
import com.gantang.reaxon.api.memory.LongTermMemory;
import com.gantang.reaxon.api.memory.MemoryConsolidator;
import com.gantang.reaxon.api.memory.MemoryExtractor;
import com.gantang.reaxon.api.memory.SummaryGenerator;
import com.gantang.reaxon.api.memory.TokenCounter;
import com.gantang.reaxon.api.llm.ModelRouter;
import com.gantang.reaxon.impl.memory.DefaultContextAssembler;
import com.gantang.reaxon.impl.memory.HeuristicTokenCounter;
import com.gantang.reaxon.impl.memory.LlmMemoryConsolidator;
import com.gantang.reaxon.impl.memory.LlmMemoryExtractor;
import com.gantang.reaxon.impl.memory.MemoryCaptureHook;
import com.gantang.reaxon.impl.memory.MemoryWritingService;
import com.gantang.reaxon.impl.memory.NoOpLongTermMemory;
import com.gantang.axiflux.spring.service.MemoryMaintenanceJob;
import com.gantang.axiflux.storage.pgvector.PGVectorLongTermMemory;
import com.gantang.axiflux.storage.qdrant.QdrantVectorMemory;
import com.gantang.axiflux.storage.vector.VectorStoreConfig;
import com.gantang.axiflux.spring.config.props.AgentProperties;
import com.gantang.axiflux.spring.config.props.MemoryProperties;
import com.gantang.axiflux.spring.config.props.VectorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * Long-term memory wiring: vector backend selection (pgvector / qdrant /
 * no-op), the context assembler and token counter, and the auto-capture
 * pipeline (extract → dedup → consolidate → store) plus its nightly
 * maintenance job.
 */
@Configuration(proxyBeanMethods = false)
public class MemoryConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MemoryConfiguration.class);

    // ===== Long-Term Memory =====

    @Bean
    @Lazy
    @ConditionalOnProperty(name = "axiflux.vector.provider", havingValue = "qdrant")
    public LongTermMemory qdrantLongTermMemory(VectorProperties vp,
            org.springframework.beans.factory.ObjectProvider<java.util.Optional<java.net.ProxySelector>> egressProxy) {
        // Qdrant backend supports store/search but compress()/searchScored()/updateMemory()
        // are not implemented yet: memory-maintenance jobs no-op and scored recall degrades
        // to insertion order. Surface that loudly instead of silently looking healthy.
        log.warn("Vector backend 'qdrant' is active: store/search work, but memory "
            + "compression/maintenance and scored/update operations are not implemented. "
            + "Use 'pgvector' for the full LongTermMemory feature set.");
        java.net.ProxySelector proxy = egressProxy.getIfAvailable(java.util.Optional::empty).orElse(null);
        return new QdrantVectorMemory(new VectorStoreConfig(
            vp.getUrl(), vp.getApiKey(), vp.getCollectionName(),
            vp.getDimension(), vp.getMetric(),
            vp.getEmbedUrl(), vp.getEmbedApiKey(), vp.getEmbedModel()),
            null, com.gantang.reaxon.api.memory.SummaryGenerator.truncating(), proxy);
    }

    @Bean
    @Lazy
    @ConditionalOnProperty(name = "axiflux.vector.provider", havingValue = "pgvector")
    public LongTermMemory pgVectorLongTermMemory(DataSource ds, VectorProperties vp,
                                                  SummaryGenerator summaryGenerator,
            org.springframework.beans.factory.ObjectProvider<java.util.Optional<java.net.ProxySelector>> egressProxy) {
        String embedUrl = vp.getEmbedUrl() != null && !vp.getEmbedUrl().isBlank()
            ? vp.getEmbedUrl()
            : "https://api.openai.com/v1/embeddings";
        String embedKey = vp.getEmbedApiKey() != null && !vp.getEmbedApiKey().isBlank()
            ? vp.getEmbedApiKey()
            : firstNonBlankEnv("EMBED_API_KEY", "ARK_API_KEY", "OPENAI_API_KEY");
        String embedModel = vp.getEmbedModel() != null && !vp.getEmbedModel().isBlank()
            ? vp.getEmbedModel() : "text-embedding-3-small";
        java.net.ProxySelector proxy = egressProxy.getIfAvailable(java.util.Optional::empty).orElse(null);
        return new PGVectorLongTermMemory(ds,
            new PGVectorLongTermMemory.Config(vp.getDimension(), embedUrl, embedKey, embedModel),
            summaryGenerator, proxy);
    }

    // ===== Semantic code index (codebase_search tool) =====

    @Bean
    @Lazy
    @ConditionalOnProperty(name = "axiflux.vector.provider", havingValue = "pgvector")
    public com.gantang.reaxon.api.codeindex.CodeIndexStore codeIndexStore(DataSource ds, VectorProperties vp,
            org.springframework.beans.factory.ObjectProvider<java.util.Optional<java.net.ProxySelector>> egressProxy) {
        String embedUrl = vp.getEmbedUrl() != null && !vp.getEmbedUrl().isBlank()
            ? vp.getEmbedUrl()
            : "https://api.openai.com/v1/embeddings";
        String embedKey = vp.getEmbedApiKey() != null && !vp.getEmbedApiKey().isBlank()
            ? vp.getEmbedApiKey()
            : firstNonBlankEnv("EMBED_API_KEY", "ARK_API_KEY", "OPENAI_API_KEY");
        String embedModel = vp.getEmbedModel() != null && !vp.getEmbedModel().isBlank()
            ? vp.getEmbedModel() : "text-embedding-3-small";
        java.net.ProxySelector proxy = egressProxy.getIfAvailable(java.util.Optional::empty).orElse(null);
        var store = new com.gantang.axiflux.storage.pgvector.PGVectorCodeIndexStore(ds,
            new PGVectorLongTermMemory.Config(vp.getDimension(), embedUrl, embedKey, embedModel), proxy);
        if (store.available()) {
            log.info("Semantic code index enabled (pgvector, dimension={})", vp.getDimension());
        } else {
            log.warn("Semantic code index requested but code_chunks table unavailable (pgvector extension?)");
        }
        return store;
    }

    /**
     * Fallback no-op memory when no vector provider is configured
     * ({@code axiflux.vector.provider=none}). Keeps the context bootable
     * without any external vector store.
     */
    @Bean
    @Lazy
    @ConditionalOnMissingBean(LongTermMemory.class)
    public LongTermMemory noOpLongTermMemory(Environment env) {
        String provider = env.getProperty("axiflux.vector.provider");
        if (provider != null && !provider.isBlank()
                && !Set.of("none", "qdrant", "pgvector").contains(provider.trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException(
                "Invalid axiflux.vector.provider='" + provider + "'; valid values: none|qdrant|pgvector");
        }
        if (provider == null || provider.isBlank()) {
            log.info("axiflux.vector.provider not set; long-term memory disabled (no-op). "
                + "Set axiflux.vector.provider=pgvector|qdrant to enable it.");
        }
        return new NoOpLongTermMemory();
    }

    /** First non-blank environment variable among the given names, or "" (embedding key chain). */
    private static String firstNonBlankEnv(String... names) {
        for (String n : names) {
            String v = System.getenv(n);
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    // ===== Context Assembler =====

    /**
     * Token counter for context-budget planning. Hosts can supply an exact
     * model-specific tokenizer bean; the default is a conservative heuristic.
     */
    @Bean
    @ConditionalOnMissingBean
    public TokenCounter tokenCounter() {
        return HeuristicTokenCounter.INSTANCE;
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    public ContextAssembler contextAssembler(LongTermMemory longTermMemory,
                                             TokenCounter tokenCounter,
                                             AgentProperties agent) {
        AgentProperties ap = agent != null
            ? agent : new AgentProperties();
        return new DefaultContextAssembler(longTermMemory,
                ContextAssembler.DEFAULT_SHORT_TERM_LIMIT,
                ContextAssembler.DEFAULT_LONG_TERM_LIMIT,
                tokenCounter)
            .withTokenBudget(ap.getContextWindowTokens(),
                ap.getMaxOutputTokens(), ap.getContextReserveTokens());
    }

    // ===== Auto long-term memory capture (extract -> dedup -> store) =====

    @Bean
    @Lazy
    @ConditionalOnMissingBean(MemoryExtractor.class)
    public MemoryExtractor memoryExtractor(ModelRouter modelRouter, MemoryProperties memory) {
        var mp = memory != null ? memory : new MemoryProperties();
        return new LlmMemoryExtractor(
            modelRouter, mp.getMaxInputChars(), mp.getMinImportance(), Duration.ofSeconds(40));
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean(MemoryConsolidator.class)
    public MemoryConsolidator memoryConsolidator(ModelRouter modelRouter) {
        return new LlmMemoryConsolidator(modelRouter);
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean(MemoryWritingService.class)
    public MemoryWritingService memoryWritingService(
            LongTermMemory longTermMemory, MemoryExtractor extractor,
            MemoryConsolidator consolidator, MemoryProperties memory) {
        double dedup = memory != null ? memory.getDedupSimilarity() : 0.62;
        return new MemoryWritingService(longTermMemory, extractor, consolidator, dedup);
    }

    /** Nightly memory summarization; disable with {@code axiflux.memory.maintenance-enabled=false}. */
    @Bean
    @ConditionalOnProperty(name = "axiflux.memory.maintenance-enabled", havingValue = "true", matchIfMissing = true)
    public MemoryMaintenanceJob memoryMaintenanceJob(LongTermMemory longTermMemory) {
        return new MemoryMaintenanceJob(longTermMemory);
    }

    /**
     * Hook that persists durable memories after successful turns. Active by
     * default; disable with {@code axiflux.memory.auto-capture=false}. Requires
     * a real vector provider (pgvector/qdrant) — with {@code provider=none} the
     * NoOp memory stores nothing, so turn extraction off there too.
     */
    @Bean
    @Lazy
    @ConditionalOnProperty(name = "axiflux.memory.auto-capture", havingValue = "true", matchIfMissing = true)
    public MemoryCaptureHook memoryCaptureHook(MemoryWritingService writingService) {
        return new MemoryCaptureHook(writingService);
    }
}
