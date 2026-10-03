package com.gantang.axiflux.spring.config;

import com.gantang.reaxon.api.email.EmailProvider;
import com.gantang.reaxon.api.mcp.McpClientFactory;
import com.gantang.reaxon.api.memory.SummaryGenerator;
import com.gantang.reaxon.api.tts.TtsProvider;
import com.gantang.reaxon.api.vision.VisionClient;
import com.gantang.reaxon.impl.mcp.DefaultMcpClientFactory;
import com.gantang.axiflux.spring.providers.OpenAiTtsProvider;
import com.gantang.axiflux.spring.providers.OpenAiVisionClient;
import com.gantang.axiflux.spring.providers.LlmSummaryGenerator;
import com.gantang.axiflux.spring.providers.SmtpEmailProvider;
import com.gantang.axiflux.spring.config.props.EmailProperties;
import com.gantang.axiflux.spring.config.props.LlmProperties;
import com.gantang.axiflux.spring.config.props.ToolsProperties;
import com.gantang.axiflux.spring.config.props.TtsProperties;
import com.gantang.axiflux.spring.config.props.VisionProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for external service providers (Vision, TTS, Email, MCP).
 *
 * <p>Each provider is conditional on the relevant API key or host being configured,
 * so absent services gracefully degrade (their tool returns a clear "not configured" error).
 *
 * <p>Design pattern: <b>Strategy</b> — each provider bean is a concrete strategy;
 * the tool that consumes it only knows the interface.
 */
@Configuration
public class ToolProvidersConfiguration {

    // ===== Vision =====

    @Bean
    @ConditionalOnProperty(name = "axiflux.vision.api-key")
    @ConditionalOnMissingBean(VisionClient.class)
    public VisionClient openAiVisionClient(VisionProperties visionProps) {
        return new OpenAiVisionClient(visionProps);
    }

    // ===== TTS =====

    @Bean
    @ConditionalOnProperty(name = "axiflux.tts.api-key")
    @ConditionalOnMissingBean(TtsProvider.class)
    public TtsProvider openAiTtsProvider(TtsProperties ttsProps) {
        return new OpenAiTtsProvider(ttsProps);
    }

    // ===== Email =====

    @Bean
    @ConditionalOnProperty(name = "axiflux.email.host")
    @ConditionalOnMissingBean(EmailProvider.class)
    public EmailProvider smtpEmailProvider(EmailProperties emailProps) {
        return new SmtpEmailProvider(emailProps);
    }

    // ===== MCP =====

    @Bean
    @ConditionalOnMissingBean(McpClientFactory.class)
    public McpClientFactory mcpClientFactory(ToolsProperties tools,
            org.springframework.beans.factory.ObjectProvider<java.util.Optional<java.net.ProxySelector>> egressProxy) {
        // Reuse the same SSRF toggle as the HTTP tools: SSE endpoints on private/
        // loopback addresses are blocked unless the operator opted in. Local MCP
        // servers (127.0.0.1) therefore require tools.allow-private-network=true.
        // SSE traffic also transits the shared egress proxy when one is set (P1-5).
        java.net.ProxySelector proxy = egressProxy.getIfAvailable(java.util.Optional::empty).orElse(null);
        return new DefaultMcpClientFactory(tools != null && tools.isAllowPrivateNetwork(), proxy);
    }

    // ===== Summary Generator =====

    @Bean
    @ConditionalOnMissingBean(SummaryGenerator.class)
    @ConditionalOnProperty(name = "axiflux.llm.openai.api-key")
    public SummaryGenerator llmSummaryGenerator(LlmProperties llm) {
        String baseUrl = llm.getOpenai() != null && llm.getOpenai().getBaseUrl() != null
            ? llm.getOpenai().getBaseUrl()
            : "https://api.openai.com/v1/chat/completions";
        String apiKey = llm.getOpenai() != null ? llm.getOpenai().getApiKey() : "";
        String model = llm.getOpenai() != null && llm.getOpenai().getModel() != null
            ? llm.getOpenai().getModel() : "gpt-4o-mini";
        return new LlmSummaryGenerator(baseUrl, apiKey, model);
    }

    @Bean
    @ConditionalOnMissingBean(SummaryGenerator.class)
    public SummaryGenerator truncatingSummaryGenerator() {
        return SummaryGenerator.truncating();
    }
}
