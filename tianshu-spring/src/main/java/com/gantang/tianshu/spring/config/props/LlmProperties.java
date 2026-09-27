package com.gantang.tianshu.spring.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Binds {@code tianshu.llm.*}. Split out of the monolithic {@code TianshuProperties}
 * (P5 config decoupling) into an independent {@code @ConfigurationProperties} bean.
 */
@ConfigurationProperties(prefix = "tianshu.llm")


// ===== LLM =====

public class LlmProperties {
    private RoutingProperties routing = new RoutingProperties();
    private OpenAiProperties openai;
    private AnthropicProperties anthropic;
    private DeepSeekProperties deepseek;
    /**
     * Extra OpenAI-compatible providers declared purely from config.
     * Each entry becomes a registered LlmClient under its {@code name},
     * so adding a new model/vendor needs no code change.
     * Example (YAML):
     * <pre>
     * tianshu.llm.extra-providers:
     *   - name: qwen
     *     base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
     *     api-key: ${DASHSCOPE_API_KEY}
     *     model: qwen-plus
     *   - name: local-vllm
     *     base-url: http://localhost:8000/v1
     *     model: meta-llama/Llama-3-8b
     * </pre>
     */
    private java.util.List<ExtraProvider> extraProviders = new java.util.ArrayList<>();

    /**
     * Master switch for deep-thinking (chain-of-thought) streaming on
     * OpenAI-compatible providers that support it (e.g. Volcengine ARK plan
     * {@code thinking:{type:enabled}} → {@code reasoning_content} deltas).
     * Only sent to ARK endpoints; other vendors never receive the parameter.
     */
    private boolean thinkingEnabled = true;

    public boolean isThinkingEnabled() { return thinkingEnabled; }
    public void setThinkingEnabled(boolean thinkingEnabled) { this.thinkingEnabled = thinkingEnabled; }

    /** A single config-declared OpenAI-compatible model/provider. */
    public static class ExtraProvider {
        /** Unique provider/route name used by the model router, e.g. {@code qwen}. */
        private String name;
        /** OpenAI-compatible base URL (ending in /v1 or vendor equivalent). */
        private String baseUrl;
        /** API key (may be null for local servers that don't require one). */
        private String apiKey;
        /** Model name/id to send to the backend. */
        private String model;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }

    public static class RoutingProperties {
        private String strategy = "cost_optimized";
        private String defaultProvider = "openai";
        private String defaultModel = "gpt-4o-mini";
        /**
         * Per-1k-token prices keyed by provider name, used by the
         * {@code cost_optimized} strategy. Deliberately configuration-only —
         * a price table baked into the code goes stale silently and starts
         * routing traffic by last year's numbers.
         *
         * <p>A provider absent from this map is treated as "cost unknown", not
         * "free", and is therefore not eligible for cost-based selection. If the
         * map is empty the strategy degrades to capability routing rather than
         * picking arbitrarily.
         * <pre>
         * tianshu.llm.routing:
         *   strategy: cost_optimized
         *   costs:
         *     deepseek: { input-per-1k: 0.27, output-per-1k: 1.10 }
         *     openai:   { input-per-1k: 2.50, output-per-1k: 10.00 }
         * </pre>
         */
        private java.util.Map<String, CostProperties> costs = new java.util.LinkedHashMap<>();
        /**
         * Model tokens classified as premium for the commercial
         * {@code advanced_models} gate. A caller on a non-premium plan who
         * <em>explicitly</em> forces one of these via {@code forcedModel} gets a
         * 402; automatic routing is never gated. Entries are matched
         * case-insensitively as exact ids or substrings.
         * <pre>
         * tianshu.llm.routing:
         *   premium-models: [o3, gpt-5, claude-opus, gemini-2.5-pro]
         * </pre>
         */
        private java.util.List<String> premiumModels = new java.util.ArrayList<>();
        public String getStrategy() { return strategy; }
        public void setStrategy(String strategy) { this.strategy = strategy; }
        public String getDefaultProvider() { return defaultProvider; }
        public void setDefaultProvider(String defaultProvider) { this.defaultProvider = defaultProvider; }
        public String getDefaultModel() { return defaultModel; }
        public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }
        public java.util.Map<String, CostProperties> getCosts() { return costs; }
        public void setCosts(java.util.Map<String, CostProperties> costs) {
            this.costs = costs == null ? new java.util.LinkedHashMap<>() : costs;
        }
        public java.util.List<String> getPremiumModels() { return premiumModels; }
        public void setPremiumModels(java.util.List<String> premiumModels) {
            this.premiumModels = premiumModels == null ? new java.util.ArrayList<>() : premiumModels;
        }
    }

    /**
     * Unit price of one provider, in whatever currency the deployment uses
     * consistently (only relative magnitude matters for routing).
     */
    public static class CostProperties {
        /** Price per 1000 prompt tokens. Null means "not declared". */
        private Double inputPer1k;
        /** Price per 1000 completion tokens. Null means "not declared". */
        private Double outputPer1k;
        /** Optional discounted price per 1000 prompt-cache-hit tokens; null = billed at inputPer1k. */
        private Double cachedInputPer1k;
        public Double getInputPer1k() { return inputPer1k; }
        public void setInputPer1k(Double inputPer1k) { this.inputPer1k = inputPer1k; }
        public Double getOutputPer1k() { return outputPer1k; }
        public void setOutputPer1k(Double outputPer1k) { this.outputPer1k = outputPer1k; }
        public Double getCachedInputPer1k() { return cachedInputPer1k; }
        public void setCachedInputPer1k(Double cachedInputPer1k) { this.cachedInputPer1k = cachedInputPer1k; }
        /** True when both halves of the price are present and usable. */
        public boolean isComplete() { return inputPer1k != null && outputPer1k != null; }
    }

    public static class OpenAiProperties {
        private String apiKey;
        private String baseUrl = "https://api.openai.com/v1";
        private String model = "gpt-4o";
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }

    public static class AnthropicProperties {
        private String apiKey;
        private String baseUrl;
        private String model = "claude-sonnet-4-20250514";
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }

    public static class DeepSeekProperties {
        private String apiKey;
        private String model = "deepseek-chat";
        private String baseUrl = "https://api.deepseek.com";
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }

    public RoutingProperties getRouting() { return routing; }
    public void setRouting(RoutingProperties routing) { this.routing = routing; }
    public OpenAiProperties getOpenai() { return openai; }
    public void setOpenai(OpenAiProperties openai) { this.openai = openai; }
    public AnthropicProperties getAnthropic() { return anthropic; }
    public void setAnthropic(AnthropicProperties anthropic) { this.anthropic = anthropic; }
    public DeepSeekProperties getDeepseek() { return deepseek; }
    public void setDeepseek(DeepSeekProperties deepseek) { this.deepseek = deepseek; }
    public java.util.List<ExtraProvider> getExtraProviders() { return extraProviders; }
    public void setExtraProviders(java.util.List<ExtraProvider> extraProviders) { this.extraProviders = extraProviders; }
}
