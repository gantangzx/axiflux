package com.gantang.tianshu.spring.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code tianshu.sso.*}: global switches for the organization-bound OIDC
 * single-sign-on flow (P3-7). Per-organization bindings (issuer, client id /
 * secret) live on the {@code organization} row; these are only the deployment
 * edge settings.
 */
@ConfigurationProperties(prefix = "tianshu.sso")
public class SsoProperties {

    /** Master toggle for the OIDC start/callback endpoints. */
    private boolean enabled = false;

    /**
     * External base URL of this deployment used to build the redirect URI
     * (e.g. {@code https://agent.example.com}). When blank, the request's
     * scheme/host are used.
     */
    private String baseUrl;

    /** Lifetime of the signed, stateless authorization state in seconds. */
    private int stateTtlSeconds = 600;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public int getStateTtlSeconds() { return stateTtlSeconds; }
    public void setStateTtlSeconds(int stateTtlSeconds) { this.stateTtlSeconds = stateTtlSeconds; }
}
