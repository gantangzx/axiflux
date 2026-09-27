package com.gantang.tianshu.registry.web;

import com.gantang.tianshu.registry.config.RegistryProperties;
import com.gantang.tianshu.registry.entity.SkillCatalog;
import com.gantang.tianshu.registry.entity.SkillVersion;
import com.gantang.tianshu.registry.service.CatalogService;
import com.gantang.tianshu.registry.service.PublishService;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill registry HTTP API (Sprint B MVP).
 *
 * <p>Read endpoints are public; {@code POST /publish} requires a Bearer token
 * ({@code tianshu.registry.publish-token}). All JPA/filesystem work runs on
 * boundedElastic to keep the event loop non-blocking.
 */
@RestController
public class RegistryController {

    private final PublishService publishService;
    private final CatalogService catalogService;
    private final RegistryProperties props;
    private final com.gantang.tianshu.registry.service.SigningService signing;
    /**
     * Audit infra P2-5: rate limit on the public install-count endpoint (see
     * {@link InstallRateLimiter} for the threat model).
     */
    private final InstallRateLimiter installRateLimiter;

    public RegistryController(PublishService publishService,
                              CatalogService catalogService,
                              RegistryProperties props,
                              com.gantang.tianshu.registry.service.SigningService signing) {
        this(publishService, catalogService, props, signing, new InstallRateLimiter());
    }

    /** Visible for testing: inject a custom limiter. */
    RegistryController(PublishService publishService,
                       CatalogService catalogService,
                       RegistryProperties props,
                       com.gantang.tianshu.registry.service.SigningService signing,
                       InstallRateLimiter installRateLimiter) {
        this.publishService = publishService;
        this.catalogService = catalogService;
        this.props = props;
        this.signing = signing;
        this.installRateLimiter = installRateLimiter != null
            ? installRateLimiter : new InstallRateLimiter();
    }

    // -- publish (authenticated) -------------------------------------------------

    @PostMapping(value = "/api/registry/publish", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, Object>> publish(@RequestPart("file") FilePart file,
                                             @RequestHeader HttpHeaders headers) {
        requireToken(headers);
        String filename = file.filename();
        if (filename == null || !filename.toLowerCase().endsWith(".zip")) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "只接受 .zip 文件"));
        }
        return DataBufferUtils.join(file.content())
            .map(this::toBytes)
            .flatMap(bytes -> Mono.fromCallable(() -> publishService.publish(bytes))
                .subscribeOn(Schedulers.boundedElastic()))
            .map(p -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("slug", p.slug());
                m.put("name", p.name());
                m.put("version", p.version());
                m.put("sha256", p.sha256());
                m.put("sizeBytes", p.sizeBytes());
                m.put("alreadyExisted", p.alreadyExisted());
                m.put("signed", p.signed());
                m.put("downloadUrl", "/api/registry/skills/" + p.slug()
                    + "/versions/" + p.version() + "/download");
                return m;
            });
    }

    // -- read (public) -----------------------------------------------------------

    @GetMapping("/api/registry/skills")
    public Mono<Map<String, Object>> list(String q, Integer page, Integer size) {
        return Mono.fromCallable(() -> catalogService.search(q,
                page == null ? 0 : page, size == null ? 20 : size))
            .subscribeOn(Schedulers.boundedElastic())
            .map(p -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("items", p.getContent().stream().map(RegistryController::catalogSummary).toList());
                m.put("page", p.getNumber());
                m.put("size", p.getSize());
                m.put("total", p.getTotalElements());
                return m;
            });
    }

    @GetMapping("/api/registry/skills/{slug}")
    public Mono<Map<String, Object>> detail(@PathVariable String slug) {
        return Mono.fromCallable(() -> {
                SkillCatalog c = catalogService.requireCatalog(slug);
                List<SkillVersion> versions = catalogService.versions(slug);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("catalog", catalogSummary(c));
                m.put("versions", versions.stream().map(RegistryController::versionSummary).toList());
                return m;
            })
            .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/api/registry/skills/{slug}/latest")
    public Mono<Map<String, Object>> latest(@PathVariable String slug) {
        return Mono.fromCallable(() -> {
                SkillCatalog c = catalogService.requireCatalog(slug);
                SkillVersion v = catalogService.latestVersion(slug);
                return java.util.Map.entry(c, v);
            })
            .subscribeOn(Schedulers.boundedElastic())
            .map(e -> {
                SkillCatalog c = e.getKey();
                SkillVersion v = e.getValue();
                Map<String, Object> m = versionSummary(v);
                m.put("status", c.getStatus());
                m.put("deprecated", "deprecated".equals(c.getStatus()));
                m.put("downloadUrl", "/api/registry/skills/" + slug
                    + "/versions/" + v.getVersion() + "/download");
                return m;
            });
    }

    /** Public key pinning endpoint (D1): Ed25519 X.509 base64. */
    @GetMapping("/api/registry/public-key")
    public Mono<Map<String, Object>> publicKey() {
        return Mono.fromCallable(() -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("algorithm", "Ed25519");
            m.put("publicKey", signing.isEnabled() ? signing.getPublicKeyBase64() : null);
            m.put("signingEnabled", signing.isEnabled());
            return m;
        });
    }

    /** Lifecycle management (authenticated): PATCH {"status":"published|deprecated"}. */
    @PatchMapping("/api/registry/skills/{slug}/status")
    public Mono<Map<String, Object>> setStatus(@PathVariable String slug,
                                               @RequestBody Map<String, String> body,
                                               @RequestHeader HttpHeaders headers) {
        requireToken(headers);
        String status = body == null ? null : body.get("status");
        return Mono.fromCallable(() -> catalogService.setStatus(slug, status))
            .subscribeOn(Schedulers.boundedElastic())
            .map(c -> catalogSummary(c));
    }

    /**
     * Install callback (public, fire-and-forget from clients): bumps total installs.
     *
     * <p>Audit infra P2-5: the endpoint is deliberately unauthenticated, so it
     * is rate-limited per client IP ({@link InstallRateLimiter}) — over-budget
     * callers get 429. The counter remains an approximate popularity signal,
     * not a proof of install.
     */
    @PostMapping("/api/registry/skills/{slug}/install")
    public Mono<Map<String, Object>> recordInstall(
            @PathVariable String slug,
            org.springframework.http.server.reactive.ServerHttpRequest request) {
        return Mono.fromCallable(() -> {
                if (!installRateLimiter.tryRecord(clientKey(request))) {
                    throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "install event rate limit exceeded; retry later");
                }
                installRateLimiter.evictExpired();
                return catalogService.recordInstall(slug);
            })
            .subscribeOn(Schedulers.boundedElastic())
            .map(c -> Map.of("slug", c.getSlug(), "totalInstalls", c.getTotalInstalls()));
    }

    /** Client key for the install rate limiter: immediate peer address. */
    private static String clientKey(org.springframework.http.server.reactive.ServerHttpRequest request) {
        if (request == null || request.getRemoteAddress() == null
                || request.getRemoteAddress().getAddress() == null) {
            return "unknown";
        }
        // Deliberately the direct peer, not X-Forwarded-For: the header is
        // trivially spoofable and would let an attacker mint a fresh budget per
        // request. Deployments behind a proxy should rate-limit at the proxy.
        return request.getRemoteAddress().getAddress().getHostAddress();
    }

    // -- download (public, counts) ----------------------------------------------

    @GetMapping("/api/registry/skills/{slug}/versions/{version}/download")
    public Mono<ResponseEntity<byte[]>> download(@PathVariable String slug,
                                                 @PathVariable String version) {
        return Mono.fromCallable(() -> catalogService.download(slug, version))
            .subscribeOn(Schedulers.boundedElastic())
            .map(d -> {
                ResponseEntity.BodyBuilder b = ResponseEntity.ok()
                    .contentType(MediaType.valueOf("application/zip"))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + d.filename() + "\"")
                    .header("X-Content-Sha256", d.sha256());
                if (d.signature() != null) b.header("X-Signature", d.signature());
                return b.body(d.bytes());
            });
    }

    @GetMapping("/api/registry/skills/{slug}/latest/download")
    public Mono<ResponseEntity<byte[]>> downloadLatest(@PathVariable String slug) {
        return Mono.fromCallable(() -> catalogService.latestVersion(slug))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(v -> download(slug, v.getVersion()));
    }

    // -- helpers -----------------------------------------------------------------

    /**
     * Publish/admin token gate. Only {@code Authorization: Bearer} is accepted
     * (P1-3): the multipart {@code token} form field was removed because form
     * bodies are routinely logged by proxies/access logs, whereas the
     * {@code Authorization} header is not — consistent with the main app
     * stripping {@code ?token=} in {@code BearerTokenHoistFilter}.
     */
    private void requireToken(HttpHeaders headers) {
        requireToken(headers, props.getPublishToken());
    }

    /** Visible for testing. */
    static void requireToken(HttpHeaders headers, String expectedToken) {
        String provided = null;
        String auth = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.startsWith("Bearer ")) {
            provided = auth.substring(7).trim();
        }
        if (provided == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "需要发布 token（仅接受 Authorization: Bearer 头）");
        }
        if (!MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expectedToken.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "发布 token 无效");
        }
    }

    private byte[] toBytes(DataBuffer buf) {
        try {
            byte[] bytes = new byte[buf.readableByteCount()];
            buf.read(bytes);
            return bytes;
        } finally {
            DataBufferUtils.release(buf);
        }
    }

    private static Map<String, Object> catalogSummary(SkillCatalog c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slug", c.getSlug());
        m.put("name", c.getName());
        m.put("author", c.getAuthor());
        m.put("description", c.getDescription());
        m.put("tags", c.getTags());
        m.put("latestVersion", c.getLatestVersion());
        m.put("totalDownloads", c.getTotalDownloads());
        m.put("totalInstalls", c.getTotalInstalls());
        m.put("status", c.getStatus());
        m.put("deprecated", "deprecated".equals(c.getStatus()));
        m.put("createdAt", c.getCreatedAt());
        m.put("updatedAt", c.getUpdatedAt());
        return m;
    }

    private static Map<String, Object> versionSummary(SkillVersion v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slug", v.getSlug());
        m.put("version", v.getVersion());
        m.put("sha256", v.getSha256());
        m.put("sizeBytes", v.getSizeBytes());
        m.put("triggers", v.getTriggers());
        m.put("requiredTools", v.getRequiredTools());
        m.put("downloads", v.getDownloads());
        m.put("signature", v.getSignature());
        m.put("signed", v.getSignature() != null);
        m.put("publishedAt", v.getPublishedAt());
        return m;
    }
}
