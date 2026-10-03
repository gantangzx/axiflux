package com.gantang.axiflux.spring.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Binds {@code axiflux.vision.*}. Split out of the monolithic {@code AxifluxProperties}
 * (P5 config decoupling) into an independent {@code @ConfigurationProperties} bean.
 */
@ConfigurationProperties(prefix = "axiflux.vision")


// ===== Vision =====

public class VisionProperties {
    private String apiKey;
    private String baseUrl = "https://api.openai.com/v1";
    private String model = "gpt-4o";
    private int maxTokens = 1024;
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
}
