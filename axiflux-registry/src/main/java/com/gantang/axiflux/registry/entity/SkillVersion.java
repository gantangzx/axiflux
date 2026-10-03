package com.gantang.axiflux.registry.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

/** One published version of a skill (content-addressed zip blob). */
@Entity
@Table(name = "skill_version")
public class SkillVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String slug;

    @Column(nullable = false, length = 32)
    private String version;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "blob_path", nullable = false, columnDefinition = "TEXT")
    private String blobPath;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private List<String> triggers;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "required_tools", columnDefinition = "text[]")
    private List<String> requiredTools;

    @Column(nullable = false)
    private long downloads;

    /** Base64 Ed25519 signature over the sha256 hex; null when signing is disabled. */
    @Column(columnDefinition = "TEXT")
    private String signature;

    @Column(name = "published_at", nullable = false, updatable = false)
    private Instant publishedAt;

    @PrePersist
    protected void onCreate() {
        publishedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getBlobPath() { return blobPath; }
    public void setBlobPath(String blobPath) { this.blobPath = blobPath; }
    public List<String> getTriggers() { return triggers; }
    public void setTriggers(List<String> triggers) { this.triggers = triggers; }
    public List<String> getRequiredTools() { return requiredTools; }
    public void setRequiredTools(List<String> requiredTools) { this.requiredTools = requiredTools; }
    public long getDownloads() { return downloads; }
    public void setDownloads(long downloads) { this.downloads = downloads; }
    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }
    public Instant getPublishedAt() { return publishedAt; }
}
