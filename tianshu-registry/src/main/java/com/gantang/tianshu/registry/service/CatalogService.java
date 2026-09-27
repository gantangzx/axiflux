package com.gantang.tianshu.registry.service;

import com.gantang.tianshu.registry.entity.SkillCatalog;
import com.gantang.tianshu.registry.entity.SkillVersion;
import com.gantang.tianshu.registry.repo.SkillCatalogRepository;
import com.gantang.tianshu.registry.repo.SkillVersionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/** Read side: search/detail/latest + blob download with counter increment. */
@Service
public class CatalogService {

    private final SkillCatalogRepository catalogRepo;
    private final SkillVersionRepository versionRepo;

    public CatalogService(SkillCatalogRepository catalogRepo, SkillVersionRepository versionRepo) {
        this.catalogRepo = catalogRepo;
        this.versionRepo = versionRepo;
    }

    public record Download(byte[] bytes, String slug, String version, String sha256,
                           String signature, String filename) {}

    public Page<SkillCatalog> search(String q, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)));
        String query = (q == null || q.isBlank()) ? null : q.strip();
        return catalogRepo.search(query, pageable);
    }

    public SkillCatalog requireCatalog(String slug) {
        return catalogRepo.findById(slug)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "技能不存在: " + slug));
    }

    public List<SkillVersion> versions(String slug) {
        requireCatalog(slug);
        return versionRepo.findBySlugOrderByPublishedAtDesc(slug);
    }

    public SkillVersion latestVersion(String slug) {
        requireCatalog(slug);
        return versionRepo.findFirstBySlugOrderByPublishedAtDesc(slug)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "技能无版本记录: " + slug));
    }

    public SkillVersion requireVersion(String slug, String version) {
        requireCatalog(slug);
        return versionRepo.findBySlugAndVersion(slug, version)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "版本不存在: " + slug + "@" + version));
    }

    /** Set lifecycle status (published | deprecated). Deprecated stays downloadable
     *  so existing installs can pin/fetch, but clients warn on update checks. */
    @Transactional
    public SkillCatalog setStatus(String slug, String status) {
        if (!"published".equals(status) && !"deprecated".equals(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "status 只支持 published | deprecated: " + status);
        }
        SkillCatalog c = requireCatalog(slug);
        c.setStatus(status);
        return catalogRepo.save(c);
    }

    /** Client install callback (fire-and-forget from clients); bumps total installs. */
    @Transactional
    public SkillCatalog recordInstall(String slug) {
        SkillCatalog c = requireCatalog(slug);
        c.setTotalInstalls(c.getTotalInstalls() + 1);
        return catalogRepo.save(c);
    }

    /** Read blob bytes and bump download counters (version row + catalog total). */
    @Transactional
    public Download download(String slug, String version) {
        SkillVersion v = requireVersion(slug, version);
        Path blob = Paths.get(v.getBlobPath());
        if (!Files.isRegularFile(blob)) {
            throw new ResponseStatusException(HttpStatus.GONE,
                "blob 文件已丢失: " + slug + "@" + version);
        }
        try {
            byte[] bytes = Files.readAllBytes(blob);
            v.setDownloads(v.getDownloads() + 1);
            versionRepo.save(v);
            SkillCatalog c = v.getSlug() != null ? catalogRepo.findById(slug).orElse(null) : null;
            if (c != null) {
                c.setTotalDownloads(c.getTotalDownloads() + 1);
                catalogRepo.save(c);
            }
            String filename = slug + "-" + version + ".zip";
            return new Download(bytes, slug, version, v.getSha256(), v.getSignature(), filename);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "blob 读取失败: " + e.getMessage());
        }
    }
}
