package com.gantang.axiflux.spring.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Binds {@code axiflux.skills.*}. Split out of the monolithic {@code AxifluxProperties}
 * (P5 config decoupling) into an independent {@code @ConfigurationProperties} bean.
 */
@ConfigurationProperties(prefix = "axiflux.skills")


// ===== Skills =====

public class SkillsProperties {
    private boolean enabled = true;
    /** Root directory scanned recursively for SKILL.md files. */
    private String rootDir = "";
    /** Skill execution wall-clock timeout. */
    private int timeoutSeconds = 300;
    /** Watch the root directory and hot-reload skills on file changes. */
    private boolean hotReload = true;
    /** Skill registry service base URL (market proxy + registry: install source). */
    private String registryUrl = "http://localhost:8090";
    /** Bearer token for registry publish (not needed for install/search; read endpoints are public). */
    private String registryToken = "";
    /**
     * Ed25519 public key (base64 X.509) for verifying registry package signatures (D1).
     * When set, registry installs REQUIRE a valid signature (fail-closed); when empty,
     * signatures are not verified (backward compatible).
     * @deprecated use {@link #registries} with per-source publicKey; kept for the
     * single-registry fallback and backward compatibility.
     */
    private String registryPublicKey = "";
    /**
     * Federated skill registries (Sprint E). Any third-party market implementing the
     * axiflux-registry HTTP protocol can be added here; the market page fans out a
     * search across all of them and installs use {@code registry:<name>/<slug>[@v]}.
     * When empty, falls back to the single {@link #registryUrl} endpoint.
     */
    private java.util.List<RegistryEndpoint> registries = new java.util.ArrayList<>();
    /**
     * ClawHub public marketplace integration (clawhub.ai). ClawHub speaks its own
     * HTTP API rather than the axiflux-registry protocol, so it is federated via
     * an adapter source; skills install with {@code clawhub:<slug>[@version]}.
     * Enabled by default for browse/search; installs remain an explicit user action.
     */
    private ClawHub clawhub = new ClawHub();
    /**
     * Fail-closed signature requirement (P1-2). When {@code true} (default), any
     * registry/federated source that does NOT pin an Ed25519 public key is
     * rejected at install time — sha256 alone only proves the bytes were not
     * corrupted in transit, not that the source is trustworthy (the hash and the
     * package come from the same, possibly compromised, origin). Set to
     * {@code false} to restore the legacy opt-in behaviour for local development.
     */
    private boolean requireSignature = true;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public String getRootDir() { return rootDir; }
    public void setRootDir(String v) { this.rootDir = v; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int v) { this.timeoutSeconds = v; }
    public boolean isHotReload() { return hotReload; }
    public void setHotReload(boolean v) { this.hotReload = v; }
    public String getRegistryUrl() { return registryUrl; }
    public void setRegistryUrl(String v) { this.registryUrl = v; }
    public String getRegistryToken() { return registryToken; }
    public void setRegistryToken(String v) { this.registryToken = v; }
    public String getRegistryPublicKey() { return registryPublicKey; }
    public void setRegistryPublicKey(String v) { this.registryPublicKey = v; }
    public java.util.List<RegistryEndpoint> getRegistries() { return registries; }
    public void setRegistries(java.util.List<RegistryEndpoint> v) { this.registries = v; }
    public ClawHub getClawhub() { return clawhub; }
    public void setClawhub(ClawHub v) { this.clawhub = v; }
    public boolean isRequireSignature() { return requireSignature; }
    public void setRequireSignature(boolean v) { this.requireSignature = v; }
}
