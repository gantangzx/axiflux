package com.gantang.tianshu.spring.config;

import com.gantang.tianshu.api.llm.LlmClient;
import com.gantang.tianshu.api.llm.ModelRouter;
import com.gantang.tianshu.impl.llm.router.DefaultModelRouter;
import com.gantang.tianshu.spring.adapter.LangChain4jLlmClientAdapter;
import com.gantang.tianshu.spring.config.props.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;

/**
 * LLM client beans and the model router.
 *
 * <p>Each provider bean activates only when its key is available (property or
 * env var, with the ARK plan key as a cross-provider fallback); the router
 * collects every registered client and applies the configured strategy
 * (capability / cost-optimized). Construction heuristics (key chain, context
 * windows, price table) live in {@link LlmClientFactory}.
 */
@Configuration(proxyBeanMethods = false)
public class LlmConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LlmConfiguration.class);

    /** ARK plan endpoints accept the {@code thinking} parameter and stream {@code reasoning_content}. */
    private static boolean isArk(String baseUrl) {
        return baseUrl != null && baseUrl.contains("volces.com");
    }

    @Bean
    @ConditionalOnExpression("'${tianshu.llm.openai.api-key:}${OPENAI_API_KEY:}${ARK_API_KEY:}' != ''")
    @ConditionalOnMissingBean(name = "openAiLlmClient")
    public LlmClient openAiLlmClient(LlmProperties llm) {
        var p = llm.getOpenai();
        return LangChain4jLlmClientAdapter.openAi(
            LlmClientFactory.resolveApiKey("OPENAI_API_KEY", p.getApiKey()),
            p.getBaseUrl(), p.getModel())
            .withThinkingEnabled(llm.isThinkingEnabled() && isArk(p.getBaseUrl()))
            .withContextWindowTokens(LlmClientFactory.contextWindowFor(p.getModel()));
    }

    @Bean
    @ConditionalOnExpression("'${tianshu.llm.anthropic.api-key:}${ANTHROPIC_API_KEY:}${ARK_API_KEY:}' != ''")
    @ConditionalOnMissingBean(name = "anthropicLlmClient")
    public LlmClient anthropicLlmClient(LlmProperties llm) {
        var p = llm.getAnthropic();
        return LangChain4jLlmClientAdapter.anthropic(
            LlmClientFactory.resolveApiKey("ANTHROPIC_API_KEY", p.getApiKey()),
            p.getBaseUrl(), p.getModel())
            .withContextWindowTokens(LlmClientFactory.contextWindowFor(p.getModel()));
    }

    @Bean
    @ConditionalOnExpression("'${tianshu.llm.deepseek.api-key:}${DEEPSEEK_API_KEY:}' != ''")
    @ConditionalOnMissingBean(name = "deepSeekLlmClient")
    public LlmClient deepSeekLlmClient(LlmProperties llm) {
        var p = llm.getDeepseek();
        return LangChain4jLlmClientAdapter.deepSeek(
            LlmClientFactory.resolveApiKey("DEEPSEEK_API_KEY", p.getApiKey()),
            p.getBaseUrl(), p.getModel())
            .withContextWindowTokens(LlmClientFactory.contextWindowFor(p.getModel()));
    }

    @Bean
    @Primary
    public ModelRouter modelRouter(List<LlmClient> clients, LlmProperties llm) {
        var routing = llm.getRouting();
        DefaultModelRouter router = LlmClientFactory.newRouter(routing);
        router.setDefaultProvider(routing.getDefaultProvider());
        // Premium-model classification for the advanced_models commercial gate.
        router.setPremiumModels(routing.getPremiumModels());
        clients.forEach(router::register);
        // Config-declared extra OpenAI-compatible providers (no code change needed).
        if (llm.getExtraProviders() != null) {
            for (var ep : llm.getExtraProviders()) {
                if (ep.getName() == null || ep.getName().isBlank()
                        || ep.getBaseUrl() == null || ep.getBaseUrl().isBlank()
                        || ep.getModel() == null || ep.getModel().isBlank()) {
                    continue;
                }
                if (router.get(ep.getName()).isPresent()) {
                    continue; // bean-defined provider takes precedence
                }
                LlmClient c = LangChain4jLlmClientAdapter.openAiCompatible(
                    ep.getName(), ep.getApiKey(), ep.getBaseUrl(), ep.getModel())
                    .withThinkingEnabled(llm.isThinkingEnabled() && isArk(ep.getBaseUrl()));
                router.register(c.withContextWindowTokens(
                    LlmClientFactory.contextWindowFor(ep.getModel())));
            }
        }
        log.info("ModelRouter: strategy={} default={} pricedProviders={}",
            router.getStrategy().name(),
            routing.getDefaultProvider(),
            LlmClientFactory.priceTable(routing).keySet());
        return router;
    }
}
