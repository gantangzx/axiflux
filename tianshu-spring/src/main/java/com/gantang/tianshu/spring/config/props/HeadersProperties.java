package com.gantang.tianshu.spring.config.props;

/**
 * Security response headers for the console and API.
 *
 * <p>Defaults are the hardened posture: strict CSP, no framing, no referrer
 * leakage, nosniff. {@code style-src} must keep {@code 'unsafe-inline'}
 * {@code script-src} stays strict {@code 'self'} (the built index.html has
 * zero inline scripts), which is where the actual XSS risk lives.
 */
public class HeadersProperties {
    private boolean enabled = true;
    /**
     * Full CSP value; blank disables the CSP header only.
     *
     * <p>{@code worker-src 'self'} keeps web workers / service workers inside
     * the origin (they fall back to {@code script-src} but the explicit
     * directive survives a future script-src relaxation); {@code frame-src
     * 'none'} forbids this page embedding any sub-frame at all (audit infra
     * P2-1).
     */
    private String contentSecurityPolicy =
        "default-src 'self'; "
        + "script-src 'self'; "
        + "style-src 'self' 'unsafe-inline'; "
        + "img-src 'self' data:; "
        + "font-src 'self'; "
        + "connect-src 'self'; "
        + "media-src 'self' data:; "
        + "object-src 'none'; "
        + "worker-src 'self'; "
        + "frame-src 'none'; "
        + "base-uri 'self'; "
        + "form-action 'self'; "
        + "frame-ancestors 'none'";
    /** Report-only mode: emit Content-Security-Policy-Report-Only instead (for rollout). */
    private boolean cspReportOnly = false;
    /**
     * HSTS max-age seconds; <=0 disables. Only written on HTTPS requests —
     * behind a TLS-terminating proxy this requires the proxy to forward
     * {@code X-Forwarded-Proto} and the app to honour it
     * ({@code server.forward-headers-strategy}); otherwise browsers never see
     * HSTS (audit infra P2-2). No {@code preload} on purpose.
     */
    private long hstsMaxAgeSeconds = 31536000L;
    private boolean hstsIncludeSubdomains = true;
    private String referrerPolicy = "no-referrer";
    /** X-Frame-Options; blank disables (CSP frame-ancestors already covers modern browsers). */
    private String frameOptions = "DENY";
    /** Permissions-Policy; blank disables. */
    private String permissionsPolicy = "geolocation=(), microphone=(), camera=()";
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public String getContentSecurityPolicy() { return contentSecurityPolicy; }
    public void setContentSecurityPolicy(String v) { this.contentSecurityPolicy = v == null ? "" : v.trim(); }
    public boolean isCspReportOnly() { return cspReportOnly; }
    public void setCspReportOnly(boolean v) { this.cspReportOnly = v; }
    public long getHstsMaxAgeSeconds() { return hstsMaxAgeSeconds; }
    public void setHstsMaxAgeSeconds(long v) { this.hstsMaxAgeSeconds = v; }
    public boolean isHstsIncludeSubdomains() { return hstsIncludeSubdomains; }
    public void setHstsIncludeSubdomains(boolean v) { this.hstsIncludeSubdomains = v; }
    public String getReferrerPolicy() { return referrerPolicy; }
    public void setReferrerPolicy(String v) { this.referrerPolicy = v == null ? "" : v.trim(); }
    public String getFrameOptions() { return frameOptions; }
    public void setFrameOptions(String v) { this.frameOptions = v == null ? "" : v.trim(); }
    public String getPermissionsPolicy() { return permissionsPolicy; }
    public void setPermissionsPolicy(String v) { this.permissionsPolicy = v == null ? "" : v.trim(); }
}
