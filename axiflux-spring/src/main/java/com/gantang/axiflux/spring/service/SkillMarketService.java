package com.gantang.axiflux.spring.service;

import com.gantang.axiflux.spring.config.props.SkillsProperties;
import com.gantang.axiflux.storage.entity.SkillEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Market proxy with federated source fan-out (Sprint E + ClawHub): searches every
 * configured {@link SkillSource} — axiflux-registry endpoints and the ClawHub
 * public marketplace adapter — in parallel, and merges the catalog entries with
 * local install state from the ledger (installed / installedVersion / updateAvailable).
 *
 * <p>Source outages degrade per source: an unreachable source is reported in the
 * source-status list and its results are skipped, while the other sources and
 * locally installed skills keep working. Only when <b>all</b> sources fail does
 * the endpoint return 503.
 */
public class SkillMarketService {

    private static final Logger log = LoggerFactory.getLogger(SkillMarketService.class);

    private final SkillsProperties skillsProps;
    private final ObjectProvider<SkillLedgerService> ledgerProvider;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "skill-source-search");
        t.setDaemon(true);
        return t;
    });

    public SkillMarketService(SkillsProperties skillsProps,
                              ObjectProvider<SkillLedgerService> ledgerProvider) {
        this.skillsProps = skillsProps;
        this.ledgerProvider = ledgerProvider;
    }

    public record MarketItem(String source, String slug, String name, String author, String description,
                             List<String> tags, String latestVersion, long totalDownloads, long totalInstalls,
                             boolean deprecated, String installSource, String trust,
                             boolean installed, String installedName, String installedVersion,
                             boolean installedEnabled, boolean updateAvailable) {}

    public record SourceStatus(String name, String url, boolean available, String error) {}

    public record MarketResult(List<MarketItem> items, List<SourceStatus> sources, int page, int size, long total) {}

    /** One source's search outcome (results or the error that killed it). */
    private record SourceOutcome(SkillSource source, SkillSource.CatalogPage results, Throwable error) {}

    public MarketResult search(String q, int page, int size) {
        SkillSources sources = SkillSources.from(skillsProps);
        if (sources.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "未配置技能来源（axiflux.skills.registries / clawhub / registry-url）");
        }

        List<CompletableFuture<SourceOutcome>> futures = sources.all().stream()
            .map(s -> CompletableFuture.supplyAsync(() -> {
                try {
                    return new SourceOutcome(s, s.search(q, page, size), null);
                } catch (Throwable e) {
                    return new SourceOutcome(s, null, e);
                }
            }, pool))
            .toList();

        // Local install state, keyed by "<source>/<slug>" and by skill name.
        String defaultName = sources.defaultRegistry() != null ? sources.defaultRegistry().name() : SkillSources.DEFAULT;
        Map<String, SkillEntity> bySourceSlug = new LinkedHashMap<>();
        Map<String, SkillEntity> byName = new LinkedHashMap<>();
        SkillLedgerService ledger = ledgerProvider.getIfAvailable();
        if (ledger != null) {
            for (SkillEntity row : ledger.ledgerByName().values()) {
                byName.put(row.getName(), row);
                String[] parts = parseInstallOrigin(row.getSource());
                if (parts != null) {
                    String srcName = parts[0] != null ? parts[0] : defaultName;
                    bySourceSlug.put(srcName + "/" + parts[1], row);
                }
            }
        }

        List<MarketItem> items = new ArrayList<>();
        List<SourceStatus> statuses = new ArrayList<>();
        long total = 0;
        int available = 0;

        for (CompletableFuture<SourceOutcome> f : futures) {
            SourceOutcome oc;
            try {
                oc = f.get(25, TimeUnit.SECONDS);
            } catch (Exception e) {
                oc = null;
            }
            if (oc == null || oc.error() != null) {
                String name = oc != null ? oc.source().name() : "?";
                String url = oc != null ? oc.source().base() : "";
                Throwable cause = oc != null ? oc.error() : null;
                String err = cause != null
                    ? (cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName())
                    : "查询超时";
                log.warn("Skill source {} unavailable: {}", name, err);
                statuses.add(new SourceStatus(name, url, false, err));
                continue;
            }
            available++;
            SkillSource src = oc.source();
            statuses.add(new SourceStatus(src.name(), src.base(), true, null));
            SkillSource.CatalogPage res = oc.results();
            total += res.total();

            for (SkillSource.CatalogEntry e : res.items()) {
                String slug = e.slug();
                String latest = e.latestVersion();
                SkillEntity local = bySourceSlug.get(src.name() + "/" + slug);
                if (local == null) {
                    // Slug vs SKILL.md name can differ; match by name but ONLY when the
                    // installed skill actually came from THIS source (no cross-source
                    // "installed" false positives, e.g. clawhub/calendar vs registry/calendar).
                    SkillEntity cand = byName.get(slug);
                    if (cand == null && e.name() != null) cand = byName.get(e.name());
                    if (cand != null) {
                        String[] p = parseInstallOrigin(cand.getSource());
                        String effSource = p != null ? (p[0] != null ? p[0] : defaultName) : null;
                        if (p != null && src.name().equals(effSource)) local = cand;
                    }
                }

                boolean installed = local != null;
                // Some sources' search results omit the latest version (e.g. ClawHub /search);
                // for installed skills we still need it to flag updateAvailable — resolve lazily.
                if (installed && (latest == null || latest.isBlank())) {
                    try {
                        SkillSource.ResolvedMeta rm = src.resolve(slug, null);
                        if (rm != null && rm.version() != null) latest = rm.version();
                    } catch (Exception resolveEx) {
                        log.debug("Latest-version enrichment failed for {}/{}: {}",
                            src.name(), slug, resolveEx.toString());
                    }
                }
                String installedVersion = installed ? local.getVersion() : null;
                boolean updateAvailable = installed
                    && installedVersion != null && !installedVersion.isBlank()
                    && latest != null && compareVersions(latest, installedVersion) > 0;
                // Deprecated/delisted skills must not be flagged "update available".
                if (e.deprecated()) updateAvailable = false;

                // Trust model per source: registries with a pinned Ed25519 key verify every
                // package ("signed"); ClawHub has no signatures, its trust comes from platform
                // security scans ("scanned"); a registry with no key configured is "unsigned".
                String trust = "clawhub".equals(src.scheme()) ? "scanned"
                    : (src.publicKey() != null && !src.publicKey().isBlank()) ? "signed" : "unsigned";

                items.add(new MarketItem(
                    src.name(),
                    slug,
                    e.name(),
                    e.author(),
                    e.summary(),
                    e.tags() != null ? e.tags() : List.of(),
                    latest,
                    e.totalDownloads(),
                    e.totalInstalls(),
                    e.deprecated(),
                    src.installSpec(slug),
                    trust,
                    installed,
                    installed ? local.getName() : null,
                    installedVersion,
                    installed && Boolean.TRUE.equals(local.getEnabled()),
                    updateAvailable));
            }
        }

        if (available == 0) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "所有技能来源均不可用：" + statuses.stream()
                    .map(s -> s.name() + "(" + s.error() + ")")
                    .reduce((a, b) -> a + "；" + b).orElse(""));
        }
        return new MarketResult(items, statuses, page, size, total);
    }

    /**
     * Parse a ledger origin of form {@code registry:[<source>/]<slug>[@version]} or
     * {@code clawhub:<slug>[@version]} into {@code [sourceName-or-null, slug]};
     * null for other (git/zip/local) origins.
     */
    static String[] parseInstallOrigin(String source) {
        if (source == null) return null;
        if (source.startsWith("clawhub:")) {
            String rest = source.substring("clawhub:".length()).trim();
            int at = rest.indexOf('@');
            if (at >= 0) rest = rest.substring(0, at);
            rest = rest.trim();
            return rest.isEmpty() ? null : new String[]{ClawHubSource.NAME, rest};
        }
        return parseRegistryOrigin(source);
    }

    /**
     * Parse a registry ledger origin of form {@code registry:[<source>/]<slug>[@version]}
     * into {@code [sourceName-or-null, slug]}; null for non-registry origins.
     */
    static String[] parseRegistryOrigin(String source) {
        if (source == null || !source.startsWith("registry:")) return null;
        String rest = source.substring("registry:".length()).trim();
        String sourceName = null;
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            sourceName = rest.substring(0, slash).trim();
            rest = rest.substring(slash + 1).trim();
            if (sourceName.isEmpty()) sourceName = null;
        }
        int at = rest.indexOf('@');
        if (at >= 0) rest = rest.substring(0, at);
        rest = rest.trim();
        if (rest.isEmpty()) return null;
        return new String[]{sourceName, rest};
    }

    /** Numeric semver-ish compare (mirrors registry PublishService); non-numeric parts fall back to string order. */
    static int compareVersions(String a, String b) {
        String[] pa = a.split("[+.\\-]");
        String[] pb = b.split("[+.\\-]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            String x = i < pa.length ? pa[i] : "0";
            String y = i < pb.length ? pb[i] : "0";
            int cmp;
            try {
                cmp = Integer.compare(Integer.parseInt(x), Integer.parseInt(y));
            } catch (NumberFormatException e) {
                cmp = x.compareTo(y);
            }
            if (cmp != 0) return cmp;
        }
        return 0;
    }
}
