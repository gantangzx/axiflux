package com.gantang.tianshu.spring.config.props;

/**
 * Shared skill-registry support type. Split out of the monolithic
 * {@code TianshuProperties} (P5 config decoupling). Not itself a properties
 * bean — a nested value type owned by {@link SkillsProperties}.
 */

public class ClawHub {
    /** Federate clawhub.ai into the market page (browse/search); installs stay explicit. */
    private boolean enabled = true;
    /** Base URL (override for self-hosted/compatible registries or via CLAWHUB_REGISTRY). */
    private String url = "https://clawhub.ai";
    /** Optional bearer token (anonymous read works; token raises rate limits). */
    private String token = "";
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public String getUrl() { return url; }
    public void setUrl(String v) { this.url = v; }
    public String getToken() { return token; }
    public void setToken(String v) { this.token = v == null ? "" : v; }
}
