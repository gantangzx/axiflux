package com.gantang.axiflux.spring.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Federated adapter for the public ClawHub marketplace (clawhub.ai).
 *
 * <p>ClawHub does not speak the axiflux-registry protocol; it exposes its own
 * public HTTP API:
 * <ul>
 *   <li>{@code GET /api/v1/search?q=&limit=} — relevance/vector search</li>
 *   <li>{@code GET /api/v1/skills?limit=&sort=} — browsable listing</li>
 *   <li>{@code GET /api/v1/skills/{slug}} — detail (latest version, owner)</li>
 *   <li>{@code GET /api/v1/skills/{slug}/versions/{version}} — version metadata</li>
 *   <li>{@code GET /api/v1/download?slug=&version=} — skill ZIP</li>
 * </ul>
 *
 * <p>Trust model: ClawHub has no Ed25519 package signature (its trust signal is
 * the server-side security scan / moderation surfaced on the site), so
 * {@link #publicKey()} is empty and packages are installed unsigned. Download
 * bytes come straight from ClawHub over TLS; {@code .skill-origin.json} records
 * {@code clawhub:<slug>} so updates route back here.
 */
public class ClawHubSource implements SkillSource {

    private static final Logger log = LoggerFactory.getLogger(ClawHubSource.class);
    public static final String NAME = "clawhub";
    private static final String UA = "axiflux-agent/skill-federation";

    private final String name;
    private final String base; // no trailing slash
    private final String token; // optional bearer, may be empty
    private final ObjectMapper om = new ObjectMapper();
    private final HttpClient http;

    public ClawHubSource(String url, String token) {
        this(NAME, url, token);
    }

    public ClawHubSource(String name, String url, String token) {
        this(name, url, token, null);
    }

    /**
     * @param proxy optional egress {@link java.net.ProxySelector} (P1-5); non-null
     *              routes search/resolve/download calls through the forward proxy.
     */
    public ClawHubSource(String name, String url, String token, java.net.ProxySelector proxy) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("clawhub url is required");
        }
        this.name = name == null || name.isBlank() ? NAME : name.strip();
        String u = url.strip();
        this.base = u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
        this.token = token == null ? "" : token.strip();
        this.http = com.gantang.reaxon.impl.tool.support.EgressProxy.applyTo(
                HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(8)),
                proxy)
            .build();
    }

    @Override public String name() { return name; }
    @Override public String base() { return base; }
    @Override public String scheme() { return "clawhub"; }
    /** ClawHub uses server-side security scans, not package signatures → unsigned source. */
    @Override public String publicKey() { return ""; }
    @Override public String installSpec(String slug) { return "clawhub:" + slug; }

    @Override
    public CatalogPage search(String q, int page, int size) throws IOException, InterruptedException {
        int limit = Math.clamp(size, 1, 50);
        List<CatalogEntry> items = new ArrayList<>();
        if (q == null || q.isBlank()) {
            // Browse: recommended first page.
            JsonNode root = getJson(base + "/api/v1/skills?limit=" + limit + "&sort=recommended", 15);
            root.path("items").forEach(n -> items.add(mapListEntry(n)));
        } else {
            String url = base + "/api/v1/search?limit=" + limit
                + "&q=" + URLEncoder.encode(q.strip(), StandardCharsets.UTF_8);
            JsonNode root = getJson(url, 15);
            // Search results are score-ranked but can repeat a slug (different owners);
            // install specs are slug-based, so keep the highest-ranked entry per slug.
            java.util.Set<String> seen = new java.util.HashSet<>();
            root.path("results").forEach(n -> {
                CatalogEntry e = mapSearchEntry(n);
                if (e.slug() != null && seen.add(e.slug())) items.add(e);
            });
        }
        return new CatalogPage(items, page, size, items.size());
    }

    /** Map a /api/v1/skills list item (has stats + topics, no owner). */
    private CatalogEntry mapListEntry(JsonNode n) {
        String slug = text(n, "slug");
        String latest = text(n.path("latestVersion"), "version");
        if (latest == null) latest = n.path("tags").path("latest").asText(null);
        JsonNode stats = n.path("stats");
        List<String> tags = new ArrayList<>();
        n.path("topics").forEach(t -> {
            String v = t.asText(null);
            if (v != null && !v.isBlank()) tags.add(v);
        });
        return new CatalogEntry(
            slug,
            text(n, "displayName") != null ? text(n, "displayName") : slug,
            firstNonBlank(text(n, "summary"), text(n, "description")),
            null,
            tags,
            latest,
            stats.path("downloads").asLong(0),
            stats.path("installs").asLong(0),
            false);
    }

    /** Map a /api/v1/search result (minimal: owner + version, no stats). */
    private CatalogEntry mapSearchEntry(JsonNode n) {
        String slug = text(n, "slug");
        return new CatalogEntry(
            slug,
            text(n, "displayName") != null ? text(n, "displayName") : slug,
            text(n, "summary"),
            firstNonBlank(text(n, "ownerHandle"), n.path("owner").path("handle").asText(null)),
            List.of(),
            text(n, "version"),
            0,
            0,
            false);
    }

    @Override
    public ResolvedMeta resolve(String slug, String pinnedVersion) throws IOException, InterruptedException {
        String version = pinnedVersion;
        if (version != null) {
            // Confirm the pinned version exists (404 → IllegalArgumentException).
            getJson(base + "/api/v1/skills/" + enc(slug) + "/versions/" + enc(version), 15);
        } else {
            JsonNode detail = getJson(base + "/api/v1/skills/" + enc(slug), 15);
            version = text(detail.path("latestVersion"), "version");
            if (version == null) {
                throw new IllegalArgumentException("ClawHub 响应缺少 latestVersion: " + slug);
            }
        }
        String downloadUrl = base + "/api/v1/download?slug=" + enc(slug) + "&version=" + enc(version);
        // No zip hash / signature from ClawHub (sha256 null → skip hash check; unsigned source).
        return new ResolvedMeta(slug, version, null, null, false, downloadUrl);
    }

    @Override
    public byte[] download(String url) throws IOException, InterruptedException {
        HttpRequest req = auth(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120)))
            .GET().build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("ClawHub 下载失败 HTTP " + resp.statusCode() + ": " + url);
        }
        return resp.body();
    }

    /** ClawHub counts downloads server-side; no separate install callback needed. */
    @Override public void notifyInstall(String slug) { /* no-op */ }

    private JsonNode getJson(String url, int timeoutSec) throws IOException, InterruptedException {
        HttpRequest req = auth(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeoutSec)))
            .header("Accept", "application/json")
            .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() == 404) {
            throw new IllegalArgumentException("ClawHub 中不存在: " + url);
        }
        if (resp.statusCode() == 429) {
            throw new IOException("ClawHub 请求过于频繁（429），请稍后再试");
        }
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("ClawHub HTTP " + resp.statusCode() + " from " + url);
        }
        return om.readTree(resp.body());
    }

    private HttpRequest.Builder auth(HttpRequest.Builder b) {
        b.header("User-Agent", UA);
        if (!token.isEmpty()) b.header("Authorization", "Bearer " + token);
        return b;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String text(JsonNode n, String field) {
        String v = n.path(field).asText(null);
        return (v == null || v.isBlank()) ? null : v;
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }
}
