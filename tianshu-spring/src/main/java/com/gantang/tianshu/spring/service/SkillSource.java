package com.gantang.tianshu.spring.service;

import java.io.IOException;
import java.util.List;

/**
 * A federated skill source (Sprint E + ClawHub adapter).
 *
 * <p>Two flavours exist today:
 * <ul>
 *   <li>{@link RegistrySource} — speaks the tianshu-registry HTTP protocol
 *       (search/resolve/download + Ed25519 signatures). Any self-hosted or
 *       third-party registry implementing that protocol is added via
 *       {@code tianshu.skills.registries} with zero code.</li>
 *   <li>{@link ClawHubSource} — adapts the public ClawHub marketplace
 *       (clawhub.ai), which has its own HTTP API and trust model (server-side
 *       security scans instead of Ed25519 signatures).</li>
 * </ul>
 *
 * The market service fans {@link #search} out over every source in parallel;
 * the install service uses {@link #resolve} + {@link #download} to fetch a
 * verified package. Trust is per-source: {@link #publicKey()} empty means the
 * source is treated as unsigned (its packages are not signature-checked).
 */
public interface SkillSource {

    /** Unique short source name (also the install namespace). */
    String name();

    /** Base URL (no trailing slash), shown in the source-status UI. */
    String base();

    /** Install-scheme prefix: {@code registry} or {@code clawhub}. */
    String scheme();

    /** Pinned Ed25519 public key (X.509 base64); empty means signatures are not verified. */
    String publicKey();

    /** Canonical install spec for a catalog slug, e.g. {@code registry:hub2/foo} or {@code clawhub:bar}. */
    String installSpec(String slug);

    /** One catalog entry, mapped to a source-neutral shape for the market page. */
    record CatalogEntry(String slug, String name, String summary, String author,
                        List<String> tags, String latestVersion,
                        long totalDownloads, long totalInstalls, boolean deprecated) {}

    /** A page of catalog entries (federated merge is page-agnostic; sources return their first page). */
    record CatalogPage(List<CatalogEntry> items, int page, int size, long total) {}

    /** Browse (q null/blank) or relevance-search this source. */
    CatalogPage search(String q, int page, int size) throws IOException, InterruptedException;

    /** Resolved package metadata needed for a verified install. */
    record ResolvedMeta(String slug, String version, String sha256, String signature,
                        boolean deprecated, String downloadUrl) {}

    /** Resolve the pinned version (or the latest) and return download + verification metadata. */
    ResolvedMeta resolve(String slug, String pinnedVersion) throws IOException, InterruptedException;

    /** Download a package as bytes (separated from metadata so sha256 can be checked pre-extract). */
    byte[] download(String url) throws IOException, InterruptedException;

    /** Best-effort install counter callback; must never throw. */
    void notifyInstall(String slug);
}
