package com.gantang.tianshu.spring.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Binds {@code tianshu.agent.*}. Split out of the monolithic {@code TianshuProperties}
 * (P5 config decoupling) into an independent {@code @ConfigurationProperties} bean.
 */
@ConfigurationProperties(prefix = "tianshu.agent")


// ===== Agent =====

public class AgentProperties {
    private int maxIterations = 25;
    private int toolTimeoutSeconds = 30;
    private int spawnTimeoutSeconds = 180;
    private String defaultSystemPrompt = "You are a helpful AI assistant.";
    /** Routed model's context window in tokens; history is trimmed to fit. Default 128k. */
    private int contextWindowTokens = 128_000;
    /** Tokens reserved for the model's completion on every turn. */
    private int maxOutputTokens = 4_096;
    /** Extra safety margin on top of all counted context costs. */
    private int contextReserveTokens = 2_048;
    /**
     * Sampling temperature for the main-conversation model calls
     * (chat tool-loop, blocking fallback, best-of-n answerers). Bounded to
     * {@code [0.0, 2.0]}; deterministic callers (extraction / summarisation /
     * judging) build their own {@code CompletionRequest} and ignore this. P2-2.
     */
    private double temperature = 0.7;
    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }
    public int getToolTimeoutSeconds() { return toolTimeoutSeconds; }
    public void setToolTimeoutSeconds(int toolTimeoutSeconds) { this.toolTimeoutSeconds = toolTimeoutSeconds; }
    public int getSpawnTimeoutSeconds() { return spawnTimeoutSeconds; }
    public void setSpawnTimeoutSeconds(int spawnTimeoutSeconds) { this.spawnTimeoutSeconds = spawnTimeoutSeconds; }
    public String getDefaultSystemPrompt() { return defaultSystemPrompt; }
    public void setDefaultSystemPrompt(String defaultSystemPrompt) { this.defaultSystemPrompt = defaultSystemPrompt; }
    public int getContextWindowTokens() { return contextWindowTokens; }
    public void setContextWindowTokens(int contextWindowTokens) { this.contextWindowTokens = contextWindowTokens; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
    public int getContextReserveTokens() { return contextReserveTokens; }
    public void setContextReserveTokens(int contextReserveTokens) { this.contextReserveTokens = contextReserveTokens; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
}
