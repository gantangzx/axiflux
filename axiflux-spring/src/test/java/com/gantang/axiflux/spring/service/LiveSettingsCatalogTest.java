package com.gantang.axiflux.spring.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the single-source-of-truth contract for the hot-settings catalogue:
 * the persistence layer's applicable-key set and the console catalogue must
 * never drift apart again (they used to be two hand-maintained lists).
 */
class LiveSettingsCatalogTest {

    @Test
    void catalogueKeysAreUniqueAndNonBlank() {
        var keys = LiveSettingsCatalog.allKeys();
        assertEquals(LiveSettingsCatalog.SETTINGS.size(), keys.size(),
            "duplicate keys in catalogue");
        assertTrue(keys.stream().allMatch(k -> k != null && !k.isBlank()));
    }

    @Test
    void hubKeysCoverEveryCatalogueEntryExceptRouterOwnedOnes() {
        // The router default-provider is applied through ModelRouter, never via
        // the LiveSettings hub; every other entry must be hub-applicable.
        assertTrue(LiveSettingsCatalog.hubKeys().contains("tools.policy-mode"));
        assertTrue(LiveSettingsCatalog.hubKeys().contains("agent.max-iterations"));
        assertFalse(LiveSettingsCatalog.hubKeys().contains(LiveSettingsCatalog.KEY_DEFAULT_PROVIDER));
        assertEquals(LiveSettingsCatalog.allKeys().size() - 1,
            LiveSettingsCatalog.hubKeys().size());
    }

    @Test
    void runtimeConfigLiveKeysDeriveFromCatalogue() {
        assertEquals(LiveSettingsCatalog.hubKeys(), RuntimeConfigService.LIVE_KEYS);
    }

    @Test
    void aliasesResolveToFullKeysAndUnknownPassThrough() {
        assertEquals(LiveSettingsCatalog.KEY_DEFAULT_PROVIDER,
            LiveSettingsCatalog.canonicalKey("default-provider"));
        assertEquals(LiveSettingsCatalog.KEY_MAX_ITERATIONS,
            LiveSettingsCatalog.canonicalKey("max-iterations"));
        assertEquals("tools.policy-mode",
            LiveSettingsCatalog.canonicalKey("tools.policy-mode"));
    }

    @Test
    void everyEntryIsKnownAndMetaLookupWorks() {
        for (var meta : LiveSettingsCatalog.SETTINGS) {
            assertTrue(LiveSettingsCatalog.isKnown(meta.key()));
            assertEquals(meta.key(), LiveSettingsCatalog.meta(meta.key()).orElseThrow().key());
            assertTrue(meta.hotReload(), "catalogue entries are hot-reloadable by definition");
        }
        assertFalse(LiveSettingsCatalog.isKnown("nope"));
        assertFalse(LiveSettingsCatalog.isKnown(null));
    }
}
