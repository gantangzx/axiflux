package com.gantang.tianshu.spring.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Binds {@code tianshu.auth.*}. Split out of the monolithic {@code TianshuProperties}
 * (P5 config decoupling) into an independent {@code @ConfigurationProperties} bean.
 */
@ConfigurationProperties(prefix = "tianshu.auth")

/**
 * On-behalf-of auth (short-lived scoped tokens). Disabled by default:
 * callers run as full-access single user. Enable for multi-user/deployed use.
 */
public class AuthProperties {
    /** Master toggle. When false, every call runs with the wildcard scope. */
    private boolean enabled = false;
    /** HMAC secret for signing tokens. MUST be overridden in production. */
    private String secret = "dev-insecure-secret-change-me";
    /** Default token lifetime (seconds) when the issuer omits ttl. */
    private int defaultTtlSeconds = 3600;
    /**
     * When true (recommended with {@code enabled=true}), requests without
     * an explicit scope set are treated as no-privilege by ScopePolicy
     * instead of being granted the wildcard scope.
     *
     * <p><b>Production recommendation (audit authz P2-5):</b> set this to
     * {@code true} in any deployed environment. With it off, a caller whose
     * token carries no scopes is treated as full-access, and only the audit
     * scope snapshot ({@code callerScopes} on approval decisions) can
     * distinguish that dev identity from a real grant after the fact.
     */
    private boolean requireExplicitScopes = false;
    /**
     * Whether the self-serve {@code POST /api/v1/auth/token} endpoint is
     * exposed. {@code null} (default) derives it: enabled only when auth
     * itself is disabled (dev convenience). Set explicitly to keep the
     * endpoint on behind an external IdP, or to force it off in dev.
     *
     * <p><b>Development-only.</b> When enabled, the endpoint mints JWTs for
     * whoever reaches it, so it must only ever run on a developer machine or
     * behind a network-level restriction — never as a production token edge
     * (audit authz P2-1).
     */
    private Boolean tokenEndpointEnabled = null;

    /**
     * Static admin credential for the local token edge (audit authz P2-1).
     *
     * <p>When the token endpoint is enabled, every
     * {@code POST /api/v1/auth/token} must present this value in the
     * {@code X-Admin-Token} header; a missing/wrong header is rejected with
     * 401. When the endpoint is enabled but this secret is blank, issuance is
     * refused with 503 — the edge fails closed rather than minting tokens
     * with no credential at all. Configure via the environment
     * ({@code TIANSHU_AUTH_TOKENADMINSECRET}); never commit a real value.
     */
    private String tokenAdminSecret = "";
    private String userIdClaim = "sub";
    private String usernameClaim = "preferred_username";
    private String emailClaim = "email";
    private String displayNameClaim = "name";
    private boolean jitProvisioning = true;

    /**
     * First-run administrator bootstrap. When enabled, a local account with
     * the configured username/password is created on startup only if no
     * account with that username already exists, so an operator can log in
     * immediately after a fresh deploy. Defaults to username
     * {@code tianshu} / password {@code admin}; override both via the
     * environment in any real deployment ({@code TIANSHU_AUTH_BOOTSTRAPADMIN_PASSWORD}).
     */
    private BootstrapAdmin bootstrapAdmin = new BootstrapAdmin();

    public static class BootstrapAdmin {
        private boolean enabled = true;
        private String username = "tianshu";
        private String password = "admin";
        /** Comma-separated platform roles granted to the bootstrapped account. */
        private String roles = "users:admin,config:admin,license:admin";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public String getUsername() { return username; }
        public void setUsername(String v) { this.username = v; }
        public String getPassword() { return password; }
        public void setPassword(String v) { this.password = v; }
        public String getRoles() { return roles; }
        public void setRoles(String v) { this.roles = v; }
    }

    public BootstrapAdmin getBootstrapAdmin() { return bootstrapAdmin; }
    public void setBootstrapAdmin(BootstrapAdmin v) { this.bootstrapAdmin = v; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public String getSecret() { return secret; }
    public void setSecret(String v) { this.secret = v; }
    public int getDefaultTtlSeconds() { return defaultTtlSeconds; }
    public void setDefaultTtlSeconds(int v) { this.defaultTtlSeconds = v; }
    public boolean isRequireExplicitScopes() { return requireExplicitScopes; }
    public void setRequireExplicitScopes(boolean v) { this.requireExplicitScopes = v; }
    public Boolean getTokenEndpointEnabled() { return tokenEndpointEnabled; }
    public void setTokenEndpointEnabled(Boolean v) { this.tokenEndpointEnabled = v; }
    public String getTokenAdminSecret() { return tokenAdminSecret; }
    public void setTokenAdminSecret(String v) { this.tokenAdminSecret = v; }
    public String getUserIdClaim() { return userIdClaim; }
    public void setUserIdClaim(String v) { this.userIdClaim = v; }
    public String getUsernameClaim() { return usernameClaim; }
    public void setUsernameClaim(String v) { this.usernameClaim = v; }
    public String getEmailClaim() { return emailClaim; }
    public void setEmailClaim(String v) { this.emailClaim = v; }
    public String getDisplayNameClaim() { return displayNameClaim; }
    public void setDisplayNameClaim(String v) { this.displayNameClaim = v; }
    public boolean isJitProvisioning() { return jitProvisioning; }
    public void setJitProvisioning(boolean v) { this.jitProvisioning = v; }
}
