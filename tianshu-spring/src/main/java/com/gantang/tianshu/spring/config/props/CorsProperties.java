package com.gantang.tianshu.spring.config.props;

import java.util.ArrayList;
import java.util.List;

/**
 * CORS for the API. Disabled by default: the console is served same-origin,
 * so no CORS headers are needed and none are emitted. Enable explicitly with
 * an origin allowlist when a separate frontend host must call the API.
 *
 * <p>{@code allowed-origins: "*"} together with {@code allow-credentials: true}
 * is rejected at startup — the browser forbids it and silently failing open
 * would be worse than a boot error.
 */
public class CorsProperties {
    private boolean enabled = false;
    private List<String> allowedOrigins = new ArrayList<>();
    /** Origin patterns (e.g. https://*.example.com); required for wildcard + credentials. */
    private List<String> allowedOriginPatterns = new ArrayList<>();
    private List<String> allowedMethods = new ArrayList<>(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    private List<String> allowedHeaders = new ArrayList<>(List.of("Authorization", "Content-Type", "X-Tianshu-User"));
    private List<String> exposedHeaders = new ArrayList<>(List.of("X-Trace-Id"));
    private boolean allowCredentials = false;
    private long maxAgeSeconds = 1800L;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public List<String> getAllowedOrigins() { return allowedOrigins; }
    public void setAllowedOrigins(List<String> v) { this.allowedOrigins = v == null ? new ArrayList<>() : v; }
    public List<String> getAllowedOriginPatterns() { return allowedOriginPatterns; }
    public void setAllowedOriginPatterns(List<String> v) { this.allowedOriginPatterns = v == null ? new ArrayList<>() : v; }
    public List<String> getAllowedMethods() { return allowedMethods; }
    public void setAllowedMethods(List<String> v) { this.allowedMethods = v == null ? new ArrayList<>() : v; }
    public List<String> getAllowedHeaders() { return allowedHeaders; }
    public void setAllowedHeaders(List<String> v) { this.allowedHeaders = v == null ? new ArrayList<>() : v; }
    public List<String> getExposedHeaders() { return exposedHeaders; }
    public void setExposedHeaders(List<String> v) { this.exposedHeaders = v == null ? new ArrayList<>() : v; }
    public boolean isAllowCredentials() { return allowCredentials; }
    public void setAllowCredentials(boolean v) { this.allowCredentials = v; }
    public long getMaxAgeSeconds() { return maxAgeSeconds; }
    public void setMaxAgeSeconds(long v) { this.maxAgeSeconds = v; }
}
