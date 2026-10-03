package com.gantang.axiflux.registry.service;

import com.gantang.axiflux.registry.config.RegistryProperties;
import com.gantang.axiflux.registry.entity.SkillCatalog;
import com.gantang.axiflux.registry.entity.SkillVersion;
import com.gantang.axiflux.registry.repo.SkillCatalogRepository;
import com.gantang.axiflux.registry.repo.SkillVersionRepository;
import com.gantang.axiflux.registry.skill.SkillFrontmatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Validate and ingest a skill zip: exactly one SKILL.md, frontmatter parsed,
 * content-addressed blob stored, catalog + version rows upserted.
 */
@Service
public class PublishService {

    private static final Logger log = LoggerFactory.getLogger(PublishService.class);
    private static final Pattern SLUG_OK = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$");
    private static final Pattern SEMVER_OK = Pattern.compile("^\\d+\\.\\d+\\.\\d+([+.\\-][0-9A-Za-z.\\-]+)*$");

    private final SkillCatalogRepository catalogRepo;
    private final SkillVersionRepository versionRepo;
    private final RegistryProperties props;
    private final SigningService signing;

    public PublishService(SkillCatalogRepository catalogRepo,
                          SkillVersionRepository versionRepo,
                          RegistryProperties props,
                          SigningService signing) {
        this.catalogRepo = catalogRepo;
        this.versionRepo = versionRepo;
        this.props = props;
        this.signing = signing;
    }

    public record Published(String slug, String name, String version, String sha256,
                            long sizeBytes, boolean alreadyExisted, boolean signed) {}

    @Transactional
    public Published publish(byte[] zipBytes) {
        if (zipBytes == null || zipBytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上传内容为空");
        }
        if (zipBytes.length > props.getMaxZipBytes()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                "zip 超过大小上限 " + props.getMaxZipBytes() + " 字节");
        }

        String sha256 = sha256Hex(zipBytes);
        String skillMd = scanAndReadSkillMd(zipBytes);

        Map<String, Object> fm = SkillFrontmatter.parse(skillMd);
        String name = SkillFrontmatter.str(fm, "name");
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SKILL.md frontmatter 缺少 name 字段");
        }
        String slug = slugify(name);
        String version = Optional.ofNullable(SkillFrontmatter.str(fm, "version")).orElse("0.1.0");
        if (!SEMVER_OK.matcher(version).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "version 不符合语义化版本（如 1.2.3）: " + version);
        }
        String description = SkillFrontmatter.str(fm, "description");
        String author = SkillFrontmatter.str(fm, "author");
        List<String> tags = SkillFrontmatter.list(fm, "tags");
        List<String> triggers = SkillFrontmatter.list(fm, "triggers", "trigger_words");
        List<String> requiredTools = SkillFrontmatter.list(fm, "required_tools", "requiredTools");

        // Idempotency / conflict.
        Optional<SkillVersion> existing = versionRepo.findBySlugAndVersion(slug, version);
        if (existing.isPresent()) {
            SkillVersion ev = existing.get();
            if (ev.getSha256().equals(sha256)) {
                return new Published(slug, name, version, sha256, zipBytes.length, true,
                    ev.getSignature() != null);
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "技能 " + slug + " 版本 " + version + " 已存在且内容不同（sha256 不一致），请升版本号");
        }

        Path blob = storeBlob(zipBytes, sha256);

        // New publish of an existing slug keeps its lifecycle status (stays published /
        // deprecated) — re-publishing a deprecated skill must not silently resurrect it.
        boolean deprecated = catalogRepo.findById(slug)
            .map(c -> "deprecated".equals(c.getStatus())).orElse(false);

        // Catalog row must exist before the version (FK). Upsert first.
        SkillCatalog c = catalogRepo.findById(slug).orElseGet(() -> {
            SkillCatalog n = new SkillCatalog();
            n.setSlug(slug);
            n.setTotalDownloads(0);
            n.setTotalInstalls(0);
            n.setStatus("published");
            return n;
        });
        c.setName(name);
        if (author != null) c.setAuthor(author);
        if (description != null) c.setDescription(description);
        if (!tags.isEmpty()) c.setTags(tags);
        if (c.getLatestVersion() == null || compareVersions(version, c.getLatestVersion()) > 0) {
            c.setLatestVersion(version);
        }
        catalogRepo.save(c);

        SkillVersion v = new SkillVersion();
        v.setSlug(slug);
        v.setVersion(version);
        v.setSha256(sha256);
        v.setSizeBytes(zipBytes.length);
        v.setBlobPath(blob.toString());
        v.setTriggers(emptyToNull(triggers));
        v.setRequiredTools(emptyToNull(requiredTools));
        v.setDownloads(0);
        v.setSignature(signing.signSha256(sha256));
        versionRepo.save(v);

        log.info("Skill published: {}@{} sha={} size={} signed={}{}", slug, version,
            sha256.substring(0, 12), zipBytes.length, v.getSignature() != null,
            deprecated ? " (slug currently deprecated)" : "");
        return new Published(slug, name, version, sha256, zipBytes.length, false,
            v.getSignature() != null);
    }

    /** Validate the zip and extract the single SKILL.md content. */
    private String scanAndReadSkillMd(byte[] zipBytes) {
        String mdPath = null;
        String mdContent = null;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName().replace('\\', '/');
                // Reject absolute paths and traversal (zip-slip).
                if (entryName.startsWith("/") || entryName.contains("..")) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "zip 包含非法路径条目: " + entryName);
                }
                if (!entry.isDirectory() && isSkillMd(entryName)) {
                    if (mdPath != null) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "zip 内包含多个 SKILL.md（" + mdPath + " 与 " + entryName + "），一个包只能发布一个技能");
                    }
                    mdPath = entryName;
                    mdContent = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法解析 zip 文件: " + e.getMessage());
        }
        if (mdPath == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "zip 中未找到 SKILL.md（需位于包根目录或唯一一级子目录）");
        }
        return mdContent;
    }

    private static boolean isSkillMd(String entryName) {
        return entryName.equals("SKILL.md") || entryName.endsWith("/SKILL.md");
    }

    /** Content-addressed storage: blobDir/ab/cd/<sha>.zip (writes atomically). */
    private Path storeBlob(byte[] zipBytes, String sha256) {
        Path dir = props.getBlobDir().toAbsolutePath().normalize()
            .resolve(sha256.substring(0, 2)).resolve(sha256.substring(2, 4));
        Path target = dir.resolve(sha256 + ".zip");
        try {
            Files.createDirectories(dir);
            if (!Files.exists(target)) {
                Path tmp = Files.createTempFile(dir, "upload-", ".part");
                try {
                    Files.copy(new ByteArrayInputStream(zipBytes), tmp, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(tmp);
                }
            }
            return target;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "blob 写入失败: " + e.getMessage());
        }
    }

    /** Normalize a skill name to a registry slug: [a-z0-9-], 2-64 chars. */
    public static String slugify(String name) {
        String slug = name.strip().toLowerCase()
            .replace('_', '-')
            .replaceAll("[^a-z0-9-]+", "-")
            .replaceAll("-{2,}", "-")
            .replaceAll("^-+|-+$", "");
        if (!SLUG_OK.matcher(slug).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "技能名无法生成合法 slug（需含字母/数字，2-64 字符）: " + name);
        }
        return slug;
    }

    /** Numeric semver-ish compare; non-numeric suffixes fall back to string order. */
    public static int compareVersions(String a, String b) {
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

    private static List<String> emptyToNull(List<String> l) {
        return (l == null || l.isEmpty()) ? null : l;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
