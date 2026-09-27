package com.gantang.tianshu.spring.service;

import com.gantang.tianshu.spring.config.props.SkillsProperties;
import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.skill.Skill;
import com.gantang.tianshu.api.skill.SkillEventListener;
import com.gantang.tianshu.api.skill.SkillExecutor;
import com.gantang.tianshu.api.skill.SkillInterceptor;
import com.gantang.tianshu.api.skill.SkillResult;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-2: signature verification must be fail-CLOSED by default. A federated
 * source that does not pin an Ed25519 public key (empty {@link
 * SkillSource#publicKey()}) must be REFUSED when
 * {@code tianshu.skills.require-signature=true} (the default), because a bare
 * sha256 only proves the zip survived transit — it cannot prove the source is
 * trustworthy. The flag can be explicitly turned off for local development.
 */
class SkillInstallServiceSignatureTest {

    /** Minimal unsigned source (no pinned public key). */
    private SkillSource unsignedSource() {
        return new SkillSource() {
            @Override public String name() { return "unsigned-hub"; }
            @Override public String base() { return "http://localhost:1"; }
            @Override public String scheme() { return "registry"; }
            @Override public String publicKey() { return ""; }
            @Override public String installSpec(String slug) { return "registry:unsigned-hub/" + slug; }
            @Override public CatalogPage search(String q, int page, int size) { return new CatalogPage(List.of(), 0, 0, 0); }
            @Override public ResolvedMeta resolve(String slug, String v) { return null; }
            @Override public byte[] download(String url) { return new byte[0]; }
            @Override public void notifyInstall(String slug) { }
        };
    }

    /** No-op executor: verifySignature never touches it, but the ctor requires non-null. */
    private SkillExecutor noopExecutor() {
        return new SkillExecutor() {
            @Override public Mono<SkillResult> execute(Skill skill, String input, AgentContext context) { return Mono.empty(); }
            @Override public Mono<SkillResult> executeMatching(String query, AgentContext context) { return Mono.empty(); }
            @Override public boolean matchesTrigger(Skill skill, String query) { return false; }
            @Override public void loadFromDirectory(String rootDir) { }
            @Override public void addListener(SkillEventListener listener) { }
            @Override public void addInterceptor(SkillInterceptor interceptor) { }
        };
    }

    private SkillInstallService service(SkillsProperties props) {
        // verifySignature only touches skillsProps + the source; ledger unused here.
        return new SkillInstallService(props, noopExecutor(), null);
    }

    @Test
    void unsignedSourceRejectedByDefault() {
        SkillsProperties props = new SkillsProperties(); // requireSignature defaults to true
        assertTrue(props.isRequireSignature());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service(props).verifySignature(unsignedSource(), "demo", "abc123", null));
        assertTrue(ex.getMessage().contains("require-signature"),
            "error should name the toggle that can relax the gate");
    }

    @Test
    void unsignedSourceRejectedWhenExplicitlyRequired() {
        SkillsProperties props = new SkillsProperties();
        props.setRequireSignature(true);
        assertThrows(IllegalArgumentException.class,
            () -> service(props).verifySignature(unsignedSource(), "demo", "abc123", null));
    }

    @Test
    void unsignedSourceAllowedWhenRequirementDisabled() {
        SkillsProperties props = new SkillsProperties();
        props.setRequireSignature(false); // local-dev escape hatch
        assertDoesNotThrow(
            () -> service(props).verifySignature(unsignedSource(), "demo", "abc123", null));
    }

    @Test
    void pinnedKeyWithoutSignatureStillFailsClosed() {
        SkillsProperties props = new SkillsProperties();
        props.setRequireSignature(false); // even in relaxed mode, a pinned key demands a signature
        SkillSource pinned = new SkillSource() {
            @Override public String name() { return "signed-hub"; }
            @Override public String base() { return "http://localhost:1"; }
            @Override public String scheme() { return "registry"; }
            @Override public String publicKey() { return "dGVzdA=="; } // any non-blank base64
            @Override public String installSpec(String slug) { return "registry:signed-hub/" + slug; }
            @Override public CatalogPage search(String q, int page, int size) { return new CatalogPage(List.of(), 0, 0, 0); }
            @Override public ResolvedMeta resolve(String slug, String v) { return null; }
            @Override public byte[] download(String url) { return new byte[0]; }
            @Override public void notifyInstall(String slug) { }
        };
        assertThrows(IllegalArgumentException.class,
            () -> service(props).verifySignature(pinned, "demo", "abc123", null));
    }
}
