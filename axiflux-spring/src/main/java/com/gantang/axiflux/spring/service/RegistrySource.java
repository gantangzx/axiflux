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
 * One federated skill registry endpoint (Sprint E). Speaks the axiflux-registry
 * HTTP protocol: catalog search ({@code GET /api/registry/skills}), version
 * resolution ({@code /skills/{slug}/latest} and {@code /skills/{slug}}), package
 * download ({@code /versions/{v}/download}), install counter
 * ({@code POST /skills/{slug}/install}) and Ed25519 signature metadata.
 *
 * <p>Any third-party market implementing this protocol can be federated by
 * adding an entry to {@code axiflux.skills.registries} — no code changes on the
 * client side. Each endpoint pins its own Ed25519 public key, so trust is scoped
 * per source.
 */
public class RegistrySource implements SkillSource {

    private static final Logger log = LoggerFactory.getLogger(RegistrySource.class);

    private final String name;
    private final String base; // no trailing slash
    private final String token; // optional bearer, may be empty
    private final String publicKey; // optional Ed25519 X.509 base64, may be empty
    private final ObjectMapper om = new ObjectMapper();
    private final HttpClient http;

    public RegistrySource(String name, String url, String token, String publicKey) {
        this(name, url, token, publicKey, null);
    }

    /**
     * @param proxy optional egress {@link java.net.ProxySelector} (P1-5); non-null
     *              routes catalog/resolve/download calls through the forward proxy.
     */
    public RegistrySource(String name, String url, String token, String publicKey,
                          java.net.ProxySelector proxy) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("registry name is required");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("registry url is required: " + name);
        }
        String n = name.strip();
        if (!n.matches("[a-z0-9][a-z0-9._-]*")) {
            throw new IllegalArgumentException("非法注册表名称: " + name + "（仅允许小写字母/数字/._-）");
        }
        this.name = n;
        String u = url.strip();
        this.base = u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
        this.token = token == null ? "" : token.strip();
        this.publicKey = publicKey == null ? "" : publicKey.strip();
        this.http = com.gantang.reaxon.impl.tool.support.EgressProxy.applyTo(
                HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(5)),
                proxy)
            .build();
    }

    public String name() { return name; }
    public String base() { return base; }
    @Override public String scheme() { return "registry"; }
    /** Pinned Ed25519 public key (X.509 base64); empty means signatures are not verified. */
    @Override public String publicKey() { return publicKey; }
    /** Default source keeps the legacy {@code registry:slug} shape; others are namespaced. */
    @Override public String installSpec(String slug) {
        return "registry:" + (SkillSources.DEFAULT.equals(name) ? "" : name + "/") + slug;
    }

    @Override
    public CatalogPage search(String q, int page, int size) throws IOException, InterruptedException {
        String url = base + "/api/registry/skills?page=" + Math.max(0, page)
            + "&size=" + Math.min(100, Math.max(1, size))
            + (q != null && !q.isBlank()
                ? "&q=" + URLEncoder.encode(q.strip(), StandardCharsets.UTF_8) : "");
        JsonNode root = getJson(url, 10);
        List<CatalogEntry> items = new ArrayList<>();
        root.path("items").forEach(n -> items.add(new CatalogEntry(
            text(n, "slug"),
            text(n, "name"),
            text(n, "description"),
            text(n, "author"),
            textList(n, "tags"),
            text(n, "latestVersion"),
            n.path("totalDownloads").asLong(0),
            n.path("totalInstalls").asLong(0),
            n.path("deprecated").asBoolean(false))));
        return new CatalogPage(items,
            root.path("page").asInt(page),
            root.path("size").asInt(size),
            root.path("total").asLong(items.size()));
    }

    /** Resolve the pinned version (from the skill detail) or the latest version. */
    @Override
    public ResolvedMeta resolve(String slug, String pinnedVersion) throws IOException, InterruptedException {
        if (pinnedVersion != null) {
            JsonNode detail = getJson(base + "/api/registry/skills/" + enc(slug), 15);
            JsonNode found = null;
            for (JsonNode v : detail.path("versions")) {
                if (pinnedVersion.equals(v.path("version").asText(null))) { found = v; break; }
            }
            if (found == null) {
                throw new IllegalArgumentException("注册表 " + name + " 中不存在版本: " + slug + "@" + pinnedVersion);
            }
            boolean deprecated = "deprecated".equals(detail.path("catalog").path("status").asText(""));
            return new ResolvedMeta(slug, pinnedVersion,
                text(found, "sha256"), text(found, "signature"), deprecated,
                base + "/api/registry/skills/" + enc(slug) + "/versions/" + enc(pinnedVersion) + "/download");
        }
        JsonNode latest = getJson(base + "/api/registry/skills/" + enc(slug) + "/latest", 15);
        String v = text(latest, "version");
        if (v == null) {
            throw new IllegalArgumentException("注册表 " + name + " 响应缺少 version 字段: " + slug);
        }
        return new ResolvedMeta(slug, v,
            text(latest, "sha256"), text(latest, "signature"),
            latest.path("deprecated").asBoolean(false),
            base + "/api/registry/skills/" + enc(slug) + "/versions/" + enc(v) + "/download");
    }

    /** Download a package as bytes (separated from metadata so sha256 can be checked pre-extract). */
    @Override
    public byte[] download(String url) throws IOException, InterruptedException {
        HttpRequest req = auth(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120)))
            .GET().build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("下载失败 HTTP " + resp.statusCode() + ": " + url);
        }
        return resp.body();
    }

    /** Fire-and-forget install counter callback; never throws. */
    @Override
    public void notifyInstall(String slug) {
        try {
            HttpRequest req = auth(HttpRequest.newBuilder(
                    URI.create(base + "/api/registry/skills/" + enc(slug) + "/install"))
                .timeout(Duration.ofSeconds(5)))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
            http.sendAsync(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.debug("Registry install callback failed (non-fatal): {}", e.toString());
        }
    }

    private JsonNode getJson(String url, int timeoutSec) throws IOException, InterruptedException {
        HttpRequest req = auth(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeoutSec)))
            .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() == 404) {
            throw new IllegalArgumentException("注册表 " + name + " 中不存在: " + url);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + resp.statusCode() + " from " + url);
        }
        return om.readTree(resp.body());
    }

    private HttpRequest.Builder auth(HttpRequest.Builder b) {
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

    private static List<String> textList(JsonNode n, String field) {
        JsonNode arr = n.path(field);
        if (!arr.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        arr.forEach(x -> {
            String v = x.asText(null);
            if (v != null && !v.isBlank()) out.add(v);
        });
        return out;
    }
}
