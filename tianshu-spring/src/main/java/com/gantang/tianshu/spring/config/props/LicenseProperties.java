package com.gantang.tianshu.spring.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Offline enterprise license settings ({@code tianshu.license.*}).
 *
 * <p>Defaults are intentionally permissive: no file is required and existing
 * self-hosted deployments behave exactly as before.
 */
@ConfigurationProperties(prefix = "tianshu.license")
public class LicenseProperties {

    public enum Enforcement { OFF, WARN, ENFORCE }

    private boolean enabled = false;
    private String path;
    private String publicKeyPath;
    private String deploymentId;
    private Enforcement enforcement = Enforcement.OFF;
    private int graceDays = 7;
    private String expectedIssuer = "tianshu-vendor";
    private List<String> expectedAudiences = new ArrayList<>();
    /** Allowed clock skew for nbf/exp validation. */
    private java.time.Duration clockSkew = java.time.Duration.ofMinutes(1);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getPublicKeyPath() { return publicKeyPath; }
    public void setPublicKeyPath(String publicKeyPath) { this.publicKeyPath = publicKeyPath; }
    public String getDeploymentId() { return deploymentId; }
    public void setDeploymentId(String deploymentId) { this.deploymentId = deploymentId; }
    public Enforcement getEnforcement() { return enforcement; }
    public void setEnforcement(Enforcement enforcement) { this.enforcement = enforcement; }
    public int getGraceDays() { return graceDays; }
    public void setGraceDays(int graceDays) { this.graceDays = graceDays; }
    public String getExpectedIssuer() { return expectedIssuer; }
    public void setExpectedIssuer(String expectedIssuer) { this.expectedIssuer = expectedIssuer; }
    public List<String> getExpectedAudiences() { return expectedAudiences; }
    public void setExpectedAudiences(List<String> expectedAudiences) {
        this.expectedAudiences = expectedAudiences == null ? new ArrayList<>() : expectedAudiences;
    }
    public java.time.Duration getClockSkew() { return clockSkew; }
    public void setClockSkew(java.time.Duration clockSkew) { this.clockSkew = clockSkew; }
}
