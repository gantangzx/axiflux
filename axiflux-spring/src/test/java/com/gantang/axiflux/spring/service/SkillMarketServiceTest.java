package com.gantang.axiflux.spring.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SkillMarketServiceTest {

    @Test
    void registryOriginParsesSourceAndSlug() {
        // Default source (no namespace) → source name null, slug extracted.
        assertArrayEquals(new String[]{null, "pdf-tools"},
            SkillMarketService.parseRegistryOrigin("registry:pdf-tools"));
        assertArrayEquals(new String[]{null, "pdf-tools"},
            SkillMarketService.parseRegistryOrigin("registry:pdf-tools@1.2.0"));
        // Federated named source.
        assertArrayEquals(new String[]{"clawhub", "pdf-tools"},
            SkillMarketService.parseRegistryOrigin("registry:clawhub/pdf-tools@1.2.0"));
        assertArrayEquals(new String[]{"official", "hello"},
            SkillMarketService.parseRegistryOrigin("registry:official/hello"));
        // Non-registry origins.
        assertNull(SkillMarketService.parseRegistryOrigin("git:owner/repo"));
        assertNull(SkillMarketService.parseRegistryOrigin("local:/tmp/x"));
        assertNull(SkillMarketService.parseRegistryOrigin(null));
        assertNull(SkillMarketService.parseRegistryOrigin("registry:"));
        assertNull(SkillMarketService.parseRegistryOrigin("registry:clawhub/"));
    }

    @Test
    void unpinFederatedStripsVersionOnlyForFederatedSchemes() {
        assertEquals("clawhub:caldav-calendar",
            SkillInstallService.unpinFederated("clawhub:caldav-calendar@1.0.0"));
        assertEquals("registry:hub2/hello-skill",
            SkillInstallService.unpinFederated("registry:hub2/hello-skill@9.9.9"));
        assertEquals("registry:pdf-tools",
            SkillInstallService.unpinFederated("registry:pdf-tools@1.2.0"));
        // No pin → unchanged.
        assertEquals("clawhub:gifgrep", SkillInstallService.unpinFederated("clawhub:gifgrep"));
        // Git refs are checkout targets, not version pins → untouched.
        assertEquals("git:owner/repo@dev", SkillInstallService.unpinFederated("git:owner/repo@dev"));
        assertEquals("local:/tmp/x", SkillInstallService.unpinFederated("local:/tmp/x"));
        assertNull(SkillInstallService.unpinFederated(null));
    }

    @Test
    void versionCompareDrivesUpdateBadge() {
        assertTrue(SkillMarketService.compareVersions("1.2.0", "1.1.9") > 0);
        assertTrue(SkillMarketService.compareVersions("1.0.1", "1.0.2") < 0);
        assertEquals(0, SkillMarketService.compareVersions("2.0.0", "2.0.0"));
        // updateAvailable semantics: latest newer than installed
        assertTrue(SkillMarketService.compareVersions("2.1.0", "2.0.4") > 0);
    }

    @Test
    void installOriginParsesClawhubAndRegistry() {
        // ClawHub origins always route to the "clawhub" source, version stripped.
        assertArrayEquals(new String[]{"clawhub", "caldav-calendar"},
            SkillMarketService.parseInstallOrigin("clawhub:caldav-calendar"));
        assertArrayEquals(new String[]{"clawhub", "gifgrep"},
            SkillMarketService.parseInstallOrigin("clawhub:gifgrep@1.2.3"));
        // Registry origins still parse through to source/slug.
        assertArrayEquals(new String[]{null, "pdf-tools"},
            SkillMarketService.parseInstallOrigin("registry:pdf-tools"));
        assertArrayEquals(new String[]{"hub2", "hello-skill"},
            SkillMarketService.parseInstallOrigin("registry:hub2/hello-skill@9.9.9"));
        // Git/local are not federated sources.
        assertNull(SkillMarketService.parseInstallOrigin("git:owner/repo"));
        assertNull(SkillMarketService.parseInstallOrigin("local:/tmp/x"));
        assertNull(SkillMarketService.parseInstallOrigin(null));
    }
}
