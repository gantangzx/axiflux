package com.gantang.tianshu.spring.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Binds {@code tianshu.tts.*}. Split out of the monolithic {@code TianshuProperties}
 * (P5 config decoupling) into an independent {@code @ConfigurationProperties} bean.
 */
@ConfigurationProperties(prefix = "tianshu.tts")


// ===== TTS =====

public class TtsProperties {
    private String apiKey;
    private String baseUrl = "https://api.openai.com/v1";
    private String model = "tts-1";
    private String defaultVoice = "alloy";
    private String format = "mp3";
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getDefaultVoice() { return defaultVoice; }
    public void setDefaultVoice(String defaultVoice) { this.defaultVoice = defaultVoice; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
}
