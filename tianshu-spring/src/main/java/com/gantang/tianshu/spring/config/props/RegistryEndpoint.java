package com.gantang.tianshu.spring.config.props;

/**
 * Shared skill-registry support type. Split out of the monolithic
 * {@code TianshuProperties} (P5 config decoupling). Not itself a properties
 * bean — a nested value type owned by {@link SkillsProperties}.
 */

public class RegistryEndpoint {
    /** Unique short name used in install syntax {@code registry:<name>/slug[@version]}. */
    private String name;
    /** Base URL, e.g. {@code http://localhost:8090}. */
    private String url;
    /** Optional bearer token for registries requiring auth (search/download). */
    private String token = "";
    /** Ed25519 public key (base64 X.509) pinned for this source; empty = skip verification. */
    private String publicKey = "";
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getUrl() { return url; }
    public void setUrl(String v) { this.url = v; }
    public String getToken() { return token; }
    public void setToken(String v) { this.token = v == null ? "" : v; }
    public String getPublicKey() { return publicKey; }
    public void setPublicKey(String v) { this.publicKey = v == null ? "" : v; }
}
