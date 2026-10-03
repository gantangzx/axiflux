package com.gantang.axiflux.registry.service;

import com.gantang.axiflux.registry.config.RegistryProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Ed25519 content signing (roadmap D1).
 *
 * <p>Signs the sha256 hex of each published zip with a persistent Ed25519 key
 * pair; the base64 signature is stored per version and served alongside the
 * download. The key pair lives in two local files ({@code <keyFile>} PKCS#8
 * private, {@code <keyFile>.pub} X.509 public, both base64); a fresh pair is
 * generated on first start. The public key is exposed at
 * {@code GET /api/registry/public-key} for clients to pin.
 *
 * <p>Trust model: private registry, TOFU acceptable for MVP — production
 * deployments should distribute the public key out-of-band and guard the
 * signing key file like a credential.
 */
@Service
public class SigningService {

    private static final Logger log = LoggerFactory.getLogger(SigningService.class);

    private final RegistryProperties props;
    private boolean enabled;
    private PrivateKey privateKey;
    private String publicKeyBase64;

    public SigningService(RegistryProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        enabled = props.isSigningEnabled();
        if (!enabled) {
            log.info("Skill signing disabled (axiflux.registry.signing.enabled=false)");
            return;
        }
        Path privFile = props.getSigningKeyFile().toAbsolutePath().normalize();
        Path pubFile = privFile.resolveSibling(privFile.getFileName() + ".pub");
        try {
            if (Files.isRegularFile(privFile) && Files.isRegularFile(pubFile)) {
                privateKey = KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new PKCS8EncodedKeySpec(
                        Base64.getDecoder().decode(Files.readString(privFile, StandardCharsets.UTF_8).strip())));
                publicKeyBase64 = Files.readString(pubFile, StandardCharsets.UTF_8).strip();
                log.info("Loaded Ed25519 signing key from {}", privFile);
            } else {
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
                kpg.initialize(255, new SecureRandom());
                KeyPair kp = kpg.generateKeyPair();
                privateKey = kp.getPrivate();
                publicKeyBase64 = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
                Path parent = privFile.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.writeString(privFile,
                    Base64.getEncoder().encodeToString(privateKey.getEncoded()) + "\n", StandardCharsets.UTF_8);
                Files.writeString(pubFile, publicKeyBase64 + "\n", StandardCharsets.UTF_8);
                try {
                    Files.setAttribute(privFile, "dos:readonly", true);
                } catch (Exception ignored) {
                    // POSIX hosts: tighten via native chmod out-of-band.
                }
                log.warn("Generated NEW Ed25519 signing key at {} (+{}.pub) — pin this public key on clients out-of-band:\n  {}",
                    privFile, privFile.getFileName(), publicKeyBase64);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialise Ed25519 signing key: " + e.getMessage(), e);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Base64 (X.509 SubjectPublicKeyInfo) public key for client pinning. */
    public String getPublicKeyBase64() {
        return publicKeyBase64;
    }

    /** Sign the sha256 hex string; returns base64 signature, or null when signing is disabled. */
    public String signSha256(String sha256Hex) {
        if (!enabled) return null;
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(privateKey);
            sig.update(sha256Hex.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sig.sign());
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 signing failed: " + e.getMessage(), e);
        }
    }

    /** Verify a base64 Ed25519 signature over sha256 hex with a base64 X.509 public key. */
    public static boolean verify(String sha256Hex, String signatureBase64, String publicKeyBase64) {
        try {
            PublicKey pub = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(
                    Base64.getDecoder().decode(publicKeyBase64.strip())));
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(pub);
            sig.update(sha256Hex.getBytes(StandardCharsets.UTF_8));
            return sig.verify(Base64.getDecoder().decode(signatureBase64.strip()));
        } catch (Exception e) {
            return false;
        }
    }
}
