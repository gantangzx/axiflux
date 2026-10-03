package com.gantang.axiflux.spring.service;

import com.gantang.reaxon.api.skill.SkillExecutor;
import com.gantang.reaxon.api.skill.SkillReloadResult;
import com.gantang.axiflux.spring.config.props.SkillsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jgit.api.Git;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installs skills from Git repositories ({@code git:owner/repo[@ref]}, GitHub
 * URLs), ZIP archives (download URL, e.g. a GitHub codeload/archive link) or
 * local directories.
 *
 * <p>All sources materialise into a temp directory first; every directory
 * containing a {@code SKILL.md} (searched up to depth 2, so bundle repos with
 * {@code skills/<name>/SKILL.md} work) is copied into
 * {@code <skillsRoot>/<skillName>}, where {@code skillName} comes from the
 * SKILL.md frontmatter (falling back to the directory name). After copying,
 * {@link SkillExecutor#reloadSkills()} reconciles the registry — the directory
 * watcher would pick the change up too, but the explicit reload makes the
 * install response deterministic.
 */
public class SkillInstallService {

    private static final Logger log = LoggerFactory.getLogger(SkillInstallService.class);
    private static final Pattern VALID_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]*");
    private static final Pattern FRONTMATTER_NAME = Pattern.compile("(?m)^name:\\s*['\"]?([^'\"\\s#]+)");

    private final SkillsProperties skillsProps;
    private final SkillExecutor executor;
    private final ObjectMapper om = new ObjectMapper();
    private final HttpClient http;

    public SkillInstallService(SkillsProperties skillsProps, SkillExecutor executor) {
        this(skillsProps, executor, null);
    }

    public SkillInstallService(SkillsProperties skillsProps, SkillExecutor executor,
                               org.springframework.beans.factory.ObjectProvider<SkillLedgerService> ledgerProvider) {
        this(skillsProps, executor, ledgerProvider, null);
    }

    /**
     * @param proxy optional egress {@link java.net.ProxySelector} (P1-5); non-null
     *              routes zip-download calls through the forward proxy.
     */
    public SkillInstallService(SkillsProperties skillsProps, SkillExecutor executor,
                               org.springframework.beans.factory.ObjectProvider<SkillLedgerService> ledgerProvider,
                               java.net.ProxySelector proxy) {
        this.skillsProps = Objects.requireNonNull(skillsProps);
        this.executor = Objects.requireNonNull(executor);
        this.ledger = ledgerProvider != null ? ledgerProvider.getIfAvailable() : null;
        this.egressProxy = proxy;
        this.http = com.gantang.reaxon.impl.tool.support.EgressProxy.applyTo(
                HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(10)),
                proxy)
            .build();
    }

    private final SkillLedgerService ledger;
    /** Egress proxy applied to federated sources built on demand (P1-5); null = direct. */
    private final java.net.ProxySelector egressProxy;

    public record InstalledSkill(String name, String path, String source) {}

    public record InstallResult(List<InstalledSkill> installed, SkillReloadResult reload) {}

    public record UpdateItem(String name, String source, boolean updated, String detail) {}

    public record UpdateAllResult(List<UpdateItem> items, int updated, int skipped, int failed) {}

    /**
     * Batch-update all ledger-tracked skills from an updatable, unpinned source (D2).
     * Builtin/local/pinned (registry:slug@v / git:..@ref) sources are skipped; deprecated
     * skills are reported as skipped with the registry's reason.
     */
    public UpdateAllResult updateAll() {
        if (ledger == null) {
            throw new IllegalStateException("技能台账不可用（SkillLedgerService 未启用）");
        }
        Map<String, String> sources = ledger.sourceBySkill();
        List<UpdateItem> items = new ArrayList<>();
        int updated = 0, skipped = 0, failed = 0;
        for (Map.Entry<String, String> e : sources.entrySet()) {
            String name = e.getKey();
            String source = e.getValue();
            if (source == null || source.isBlank() || source.startsWith("builtin")
                || source.startsWith("local:")) {
                items.add(new UpdateItem(name, source == null ? "builtin" : source, false, "非在线来源，跳过"));
                skipped++;
                continue;
            }
            // Pinned versions must not silently move (registry: or clawhub:).
            boolean pinned = (source.startsWith("registry:") || source.startsWith("clawhub:"))
                && source.contains("@");
            if (pinned) {
                items.add(new UpdateItem(name, source, false, "钉版本，跳过"));
                skipped++;
                continue;
            }
            try {
                InstallResult r = install(source, true);
                String names = r.installed().stream().map(InstalledSkill::name).reduce((a, b) -> a + "," + b).orElse(name);
                items.add(new UpdateItem(names, source, true, "已更新到最新版本"));
                updated++;
            } catch (IllegalArgumentException iae) {
                // Registry deprecation / 404 / signature failure etc. — reported, not fatal.
                items.add(new UpdateItem(name, source, false, iae.getMessage()));
                skipped++;
            } catch (Exception ex) {
                items.add(new UpdateItem(name, source, false, "更新失败: " + ex.getMessage()));
                failed++;
            }
        }
        return new UpdateAllResult(List.copyOf(items), updated, skipped, failed);
    }

    /**
     * Drop a version pin from a federated source spec for an <b>explicit</b> single-skill
     * update, so "更新" moves to the latest release and records an unpinned origin. Git
     * refs ({@code @branch}) are left untouched — they are deliberate checkout targets.
     * Batch {@link #updateAll()} still skips pinned skills entirely.
     */
    public static String unpinFederated(String source) {
        if (source == null) return null;
        if (source.startsWith("registry:") || source.startsWith("clawhub:")) {
            int at = source.indexOf('@');
            if (at >= 0) return source.substring(0, at);
        }
        return source;
    }

    /** Install one source. {@code force} overwrites an existing same-named skill directory. */
    public InstallResult install(String source, boolean force) throws IOException, InterruptedException {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source is required");
        }
        String src = source.trim();
        Path root = skillsRoot();
        Path tmp = Files.createTempDirectory("oc-skill-install-");
        try {
            String origin;
            String originVersion = null;
            SkillSource sourceForCallback = null;
            String slugForCallback = null;
            if (src.startsWith("git:") || isGitUrl(src)) {
                GitSpec spec = parseGitSpec(src);
                cloneGit(spec, tmp);
                origin = "git:" + spec.repo + (spec.ref != null ? "@" + spec.ref : "");
            } else if (src.startsWith("registry:") || src.startsWith("clawhub:")) {
                SourceSpec spec = resolveSourceSpec(src);
                FetchedPackage fetched = fetchSourcePackage(spec, tmp);
                SkillSource theSource = fetched.source();
                originVersion = fetched.meta().version();
                if (theSource instanceof ClawHubSource) {
                    origin = "clawhub:" + spec.slug() + (spec.pinned() ? "@" + fetched.meta().version() : "");
                } else {
                    // Default source keeps the legacy origin shape (registry:slug[@v]);
                    // federated sources are namespaced (registry:<name>/slug[@v]).
                    boolean namespaced = !SkillSources.DEFAULT.equals(theSource.name()) || spec.sourceName() != null;
                    origin = "registry:" + (namespaced ? theSource.name() + "/" : "") + spec.slug()
                        + (spec.pinned() ? "@" + fetched.meta().version() : "");
                }
                sourceForCallback = theSource;
                slugForCallback = spec.slug();
            } else if (isZipUrl(src)) {
                downloadAndUnzip(src, tmp);
                origin = src;
            } else if (src.startsWith("http://") || src.startsWith("https://")) {
                // Bare GitHub web URL → treat as git clone.
                cloneGit(new GitSpec(stripTrailingSlash(src), null), tmp);
                origin = "git:" + src;
            } else {
                Path local = Paths.get(src).toAbsolutePath().normalize();
                if (!Files.isDirectory(local)) {
                    throw new IllegalArgumentException("无法识别的来源（不是 git/zip/本地目录）: " + src);
                }
                Path dest = tmp.resolve(local.getFileName().toString());
                copyTree(local, dest);
                origin = "local:" + local;
            }

            List<Path> skillDirs = findSkillDirs(tmp);
            if (skillDirs.isEmpty()) {
                throw new IllegalArgumentException("来源中未找到 SKILL.md（根目录或两级子目录内）");
            }
            List<InstalledSkill> out = new ArrayList<>();
            for (Path dir : skillDirs) {
                String name = readSkillName(dir).orElse(dir.getFileName().toString());
                if (!VALID_NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException("非法技能名: " + name);
                }
                Path target = root.resolve(name).normalize();
                if (!target.startsWith(root)) {
                    throw new IllegalArgumentException("非法技能路径: " + name);
                }
                if (Files.exists(target)) {
                    // Cross-source clash gets a precise “uninstall first” message, with or without force.
                    assertSameRegistrySource(name, target, origin);
                    if (!force) {
                        throw new IllegalArgumentException("技能已存在: " + name + "（勾选「覆盖同名技能」可强制重装）");
                    }
                    deleteRecursively(target);
                }
                copyTree(dir, target);
                writeOrigin(target, origin, originVersion);
                log.info("Skill installed: {} <- {}", name, origin);
                out.add(new InstalledSkill(name, target.toString(), origin));
            }
            SkillReloadResult reload = executor.reloadSkills();
            reconcileLedger();
            if (sourceForCallback != null && slugForCallback != null) {
                sourceForCallback.notifyInstall(slugForCallback);
            }
            return new InstallResult(List.copyOf(out), reload);
        } finally {
            deleteRecursively(tmp);
        }
    }

    /** Remove an installed skill directory; the registry reconcile happens via reload. */
    public SkillReloadResult uninstall(String name) throws IOException {
        if (name == null || !VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("非法技能名: " + name);
        }
        Path root = skillsRoot();
        Path target = root.resolve(name).normalize();
        if (!target.startsWith(root) || !Files.isDirectory(target)) {
            throw new IllegalArgumentException("技能不存在: " + name);
        }
        deleteRecursively(target);
        log.info("Skill uninstalled: {}", name);
        SkillReloadResult reload = executor.reloadSkills();
        reconcileLedger();
        return reload;
    }

    /** Align the DB ledger with the post-change filesystem (best-effort). */
    private void reconcileLedger() {
        if (ledger == null) return;
        try {
            ledger.reconcile();
        } catch (Exception e) {
            log.warn("Skill ledger reconcile failed (filesystem change already applied): {}", e.toString());
        }
    }

    /* -------------------------------------------------------------- */

    private Path skillsRoot() throws IOException {
        String dir = skillsProps.getRootDir();
        Path root = Paths.get(dir != null && !dir.isBlank() ? dir : "./skills").toAbsolutePath().normalize();
        Files.createDirectories(root);
        return root;
    }

    private record GitSpec(String repo, String ref) {}

    /**
     * {@code registry:slug[@version]} routes to the default source;
     * {@code registry:<source>/slug[@version]} routes to a named federated source.
     */
    private record RegistrySpec(String source, String slug, String version, boolean pinned) {}

    private RegistrySpec parseRegistrySpec(String src) {
        String rest = src.substring("registry:".length()).trim();
        if (rest.isEmpty()) {
            throw new IllegalArgumentException("registry 来源应为 registry:[<来源>/]<slug>[@<version>]: " + src);
        }
        String sourceName = null;
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            sourceName = rest.substring(0, slash).trim();
            rest = rest.substring(slash + 1).trim();
            if (sourceName.isEmpty()) sourceName = null;
        }
        String slug = rest;
        String version = null;
        int at = rest.indexOf('@');
        if (at > 0) {
            slug = rest.substring(0, at);
            version = rest.substring(at + 1);
        }
        slug = stripTrailingSlash(slug);
        if (!slug.matches("[a-z0-9][a-z0-9-]{0,62}[a-z0-9]|[a-z0-9]")) {
            throw new IllegalArgumentException("非法注册表技能 slug: " + slug);
        }
        return new RegistrySpec(sourceName, slug, version, version != null);
    }

    /** A routed federated source request (registry or clawhub). */
    private record SourceSpec(SkillSource source, String sourceName, String slug, String version, boolean pinned) {}

    /** Route a {@code registry:} or {@code clawhub:} install spec to its configured source. */
    private SourceSpec resolveSourceSpec(String src) {
        SkillSources sources = SkillSources.from(skillsProps, egressProxy);
        if (src.startsWith("clawhub:")) {
            String rest = src.substring("clawhub:".length()).trim();
            if (rest.isEmpty()) {
                throw new IllegalArgumentException("clawhub 来源应为 clawhub:<slug>[@version]: " + src);
            }
            String slug = rest;
            String version = null;
            int at = rest.indexOf('@');
            if (at > 0) {
                slug = rest.substring(0, at);
                version = rest.substring(at + 1);
            }
            slug = stripTrailingSlash(slug);
            if (!slug.matches("[a-z0-9][a-z0-9-]{0,62}[a-z0-9]|[a-z0-9]")) {
                throw new IllegalArgumentException("非法 clawhub 技能 slug: " + slug);
            }
            ClawHubSource ch = sources.clawhub();
            if (ch == null) {
                throw new IllegalArgumentException("ClawHub 来源未启用（axiflux.skills.clawhub.enabled=false）");
            }
            return new SourceSpec(ch, ClawHubSource.NAME, slug, version, version != null);
        }
        RegistrySpec spec = parseRegistrySpec(src);
        RegistrySource rs = sources.requireRegistry(spec.source());
        return new SourceSpec(rs, spec.source(), spec.slug(), spec.version(), spec.pinned());
    }

    /** A resolved federated package plus the source it came from. */
    private record FetchedPackage(SkillSource source, SkillSource.ResolvedMeta meta) {}

    /** Resolve metadata, download bytes, verify sha256 (+ Ed25519 signature for registry sources), and unzip into tmp. */
    private FetchedPackage fetchSourcePackage(SourceSpec spec, Path tmp) throws IOException {
        SkillSource source = spec.source();
        SkillSource.ResolvedMeta meta;
        try {
            meta = source.resolve(spec.slug(), spec.version());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("无法连接技能来源 " + source.name() + " (" + source.base() + "): "
                + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("技能来源查询被中断", e);
        }
        byte[] zip;
        try {
            zip = source.download(meta.downloadUrl());
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("下载技能包失败: "
                + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("技能包下载被中断", e);
        }
        String actualSha = sha256Hex(zip);
        String expectedSha = meta.sha256() != null ? meta.sha256() : actualSha;
        if (!expectedSha.equalsIgnoreCase(actualSha)) {
            throw new IllegalArgumentException(
                "技能包校验失败：sha256 不一致（期望 " + expectedSha.substring(0, Math.min(12, expectedSha.length()))
                    + "，实际 " + actualSha.substring(0, 12) + "），包可能被篡改或传输损坏");
        }
        verifySignature(source, spec.slug(), expectedSha, meta.signature());
        if (meta.deprecated()) {
            throw new IllegalArgumentException("技能 " + spec.slug() + " 已在来源 " + source.name()
                + " 下架（deprecated），无法安装/更新；已安装的本地副本可继续使用，或卸载后改用其他技能");
        }
        unzipBytes(zip, tmp);
        return new FetchedPackage(source, meta);
    }

    /**
     * Refuse to silently overwrite a skill installed from a DIFFERENT federated source
     * (name clash across registries or vs ClawHub, e.g. {@code registry:hub2/pdf} vs
     * {@code clawhub:pdf}). Same-source reinstall/update with force is allowed;
     * git/zip/local sources are untouched.
     */
    private void assertSameRegistrySource(String skillName, Path existingTarget, String newOrigin) {
        String newName = originSourceName(newOrigin);
        if (newName == null) return; // git/zip/local: no federated-source conflict check
        try {
            Path originFile = existingTarget.resolve(".skill-origin.json");
            if (!Files.exists(originFile)) return;
            JsonNode old = om.readTree(Files.readString(originFile, StandardCharsets.UTF_8));
            String oldSource = old.path("source").asText(null);
            String oldName = originSourceName(oldSource);
            if (oldName != null && !Objects.equals(oldName, newName)) {
                throw new IllegalArgumentException("技能 " + skillName + " 已从来源「" + oldName
                    + "」安装，不能被来源「" + newName + "」覆盖；请先卸载该技能再安装");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.debug("Cross-source conflict check failed for {} (non-fatal): {}", skillName, e.toString());
        }
    }

    /** Federated source display name for an origin string; null for git/zip/local origins. */
    private String originSourceName(String origin) {
        if (origin == null) return null;
        if (origin.startsWith("clawhub:")) return ClawHubSource.NAME;
        if (origin.startsWith("registry:")) {
            try {
                RegistrySpec s = parseRegistrySpec(origin);
                return s.source() != null ? s.source() : SkillSources.DEFAULT;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /** Extract an in-memory zip to dest (zip-slip guarded). */
    private void unzipBytes(byte[] zip, Path dest) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path out = dest.resolve(entry.getName()).normalize();
                if (!out.startsWith(dest)) {
                    throw new IllegalArgumentException("zip 条目越界（zip-slip）: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Verify a source's Ed25519 signature (fail-closed by default, P1-2).
     *
     * <p>Two gates:
     * <ul>
     *   <li>A pinned key REQUIRES every package from that source to carry a valid
     *       signature (per-source fail-closed, as before).</li>
     *   <li>When {@code axiflux.skills.require-signature=true} (default), a source
     *       WITHOUT a pinned key is refused outright — sha256 only proves the bytes
     *       survived transit, not that the source is trusted (the hash and the zip
     *       come from the same, possibly hostile, origin). Set the flag to
     *       {@code false} to keep the legacy opt-in behaviour for local dev.
     * </ul>
     */
    void verifySignature(SkillSource source, String slug, String sha256Hex, String signatureB64) {
        String pubB64 = source.publicKey();
        if (pubB64 == null || pubB64.isBlank()) {
            if (skillsProps.isRequireSignature()) {
                throw new IllegalArgumentException("技能 " + slug + " 的来源 " + source.name()
                    + " 未配置 Ed25519 签名公钥，且 axiflux.skills.require-signature=true（默认），"
                    + "拒绝安装未签名的技能包（sha256 只能证明传输未损坏，不能证明来源可信）。"
                    + "如需本地开发临时放行，可显式设置 axiflux.skills.require-signature=false");
            }
            return; // legacy opt-in: verification only for sources that pin a key
        }
        if (signatureB64 == null || signatureB64.isBlank()) {
            throw new IllegalArgumentException("技能 " + slug + " 的注册表包缺少数字签名，而来源 " + source.name()
                + " 已配置签名公钥，拒绝安装（fail-closed）");
        }
        try {
            byte[] pubBytes = java.util.Base64.getDecoder().decode(pubB64.strip());
            java.security.PublicKey pub = java.security.KeyFactory.getInstance("Ed25519")
                .generatePublic(new java.security.spec.X509EncodedKeySpec(pubBytes));
            java.security.Signature sig = java.security.Signature.getInstance("Ed25519");
            sig.initVerify(pub);
            sig.update(sha256Hex.getBytes(StandardCharsets.UTF_8));
            if (!sig.verify(java.util.Base64.getDecoder().decode(signatureB64.strip()))) {
                throw new IllegalArgumentException("技能 " + slug + " 的签名验证失败（Ed25519，来源 " + source.name()
                    + "），包可能已被篡改，拒绝安装");
            }
            log.info("Registry signature verified for {} from {} (Ed25519)", slug, source.name());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("技能 " + slug + " 签名校验异常: " + e.getMessage(), e);
        }
    }

    private GitSpec parseGitSpec(String src) {
        String rest = src.startsWith("git:") ? src.substring(4) : src;
        String repo = rest;
        String ref = null;
        int at = rest.indexOf('@');
        if (at > 0) {
            repo = rest.substring(0, at);
            ref = rest.substring(at + 1);
        }
        repo = stripTrailingSlash(repo);
        if (!repo.contains("/")) {
            throw new IllegalArgumentException("git 来源应为 owner/repo 或完整仓库 URL: " + src);
        }
        // Shorthand owner/repo → GitHub HTTPS.
        if (!repo.startsWith("http://") && !repo.startsWith("https://") && !repo.startsWith("git@")) {
            repo = "https://github.com/" + repo + ".git";
        }
        return new GitSpec(repo, ref);
    }

    private boolean isGitUrl(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        return l.endsWith(".git") || l.contains("github.com/") || l.contains("gitee.com/")
            || l.startsWith("git@") || l.contains(".git@");
    }

    private boolean isZipUrl(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        return l.endsWith(".zip") || l.contains("/archive/") || l.contains("codeload.")
            || l.contains("?format=zip");
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private void cloneGit(GitSpec spec, Path dest) {
        try {
            var cmd = Git.cloneRepository().setURI(spec.repo()).setDirectory(dest.toFile());
            if (spec.ref() == null) {
                cmd.setDepth(1); // shallow clone of default branch
            }
            try (Git git = cmd.call()) {
                if (spec.ref() != null) {
                    git.checkout().setName(spec.ref()).setForced(true).call();
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("git 克隆失败: " + spec.repo() + " — " + e.getMessage(), e);
        }
    }

    private void downloadAndUnzip(String url, Path dest) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(120))
            .GET()
            .build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalArgumentException("下载失败 HTTP " + resp.statusCode() + ": " + url);
        }
        try (ZipInputStream zis = new ZipInputStream(resp.body(), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path out = dest.resolve(entry.getName()).normalize();
                if (!out.startsWith(dest)) {
                    throw new IllegalArgumentException("zip 条目越界（zip-slip）: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Directories containing SKILL.md, searched up to depth 3 (bundle repos supported). */
    private List<Path> findSkillDirs(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root, 3)) {
            return walk
                .filter(p -> !p.toString().contains(".git"))
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().equals("SKILL.md"))
                .map(Path::getParent)
                .distinct()
                .sorted()
                .toList();
        }
    }

    private Optional<String> readSkillName(Path skillDir) {
        Path md = skillDir.resolve("SKILL.md");
        if (!Files.isRegularFile(md)) return Optional.empty();
        try {
            String content = Files.readString(md, StandardCharsets.UTF_8);
            var m = FRONTMATTER_NAME.matcher(content);
            if (m.find()) return Optional.of(m.group(1).trim());
        } catch (IOException e) {
            log.warn("读取 SKILL.md 失败: {}", md, e);
        }
        return Optional.empty();
    }

    private void writeOrigin(Path skillDir, String source, String version) {
        String json = "{\"source\":\"" + source.replace("\"", "\\\"")
            + "\",\"installedAt\":\"" + Instant.now() + "\""
            + (version != null ? ",\"version\":\"" + version.replace("\"", "\\\"") + "\"" : "")
            + "}\n";
        try {
            Files.writeString(skillDir.resolve(".skill-origin.json"), json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("写入 .skill-origin.json 失败: {}", skillDir, e);
        }
    }

    private void copyTree(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(from) && dir.getFileName().toString().equals(".git")) {
                    return FileVisitResult.SKIP_SUBTREE; // never ship VCS metadata
                }
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path target = to.resolve(from.relativize(file).toString());
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        Files.walkFileTree(p, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                try {
                    file.toFile().setWritable(true); // JGit pack files are read-only on Windows
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    log.warn("删除临时文件失败（可忽略）: {}", file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                try {
                    Files.deleteIfExists(dir);
                } catch (IOException e) {
                    log.warn("删除临时目录失败（可忽略）: {}", dir);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
