package com.gantang.tianshu.registry.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Registry service configuration: blob storage, publish token, upload limits. */
@ConfigurationProperties(prefix = "tianshu.registry")
public class RegistryProperties {

    private Path blobDir = Paths.get("./registry-blobs");
    private String publishToken = "change-me";
    private long maxZipBytes = 50L * 1024 * 1024;
    /** Sign published versions with a persistent Ed25519 key pair (D1). */
    private boolean signingEnabled = true;
    /** Private key file (PKCS#8 base64); a {@code .pub} sibling holds the X.509 public key. */
    private Path signingKeyFile = Paths.get("./registry-keys/ed25519.key");

    /** Built-in default that must never be used to serve traffic. */
    static final String DEFAULT_PUBLISH_TOKEN = "change-me";

    @PostConstruct
    void requireExplicitPublishToken() {
        // Fail-fast (P1-1): refuse to boot with the well-known built-in default or an
        // empty token, otherwise anyone can publish arbitrary skill zips. The env var
        // REGISTRY_PUBLISH_TOKEN remains the override path (see application.yml).
        if (publishToken == null || publishToken.isBlank()
                || DEFAULT_PUBLISH_TOKEN.equals(publishToken.trim())) {
            throw new IllegalStateException(
                "tianshu.registry.publish-token is not set or still the built-in default '"
                    + DEFAULT_PUBLISH_TOKEN + "'. Set REGISTRY_PUBLISH_TOKEN to a strong "
                    + "unique value before starting the registry (refusing to start).");
        }
    }

    public Path getBlobDir() { return blobDir; }
    public void setBlobDir(Path blobDir) { this.blobDir = blobDir; }
    public String getPublishToken() { return publishToken; }
    public void setPublishToken(String publishToken) { this.publishToken = publishToken; }
    public long getMaxZipBytes() { return maxZipBytes; }
    public void setMaxZipBytes(long maxZipBytes) { this.maxZipBytes = maxZipBytes; }
    public boolean isSigningEnabled() { return signingEnabled; }
    public void setSigningEnabled(boolean signingEnabled) { this.signingEnabled = signingEnabled; }
    public Path getSigningKeyFile() { return signingKeyFile; }
    public void setSigningKeyFile(Path signingKeyFile) { this.signingKeyFile = signingKeyFile; }
}
