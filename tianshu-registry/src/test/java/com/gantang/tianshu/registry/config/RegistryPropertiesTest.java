package com.gantang.tianshu.registry.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-1: the registry must refuse to start with the well-known built-in default
 * publish token ({@code change-me}) or an empty token, so production cannot be
 * left open to unauthenticated publishes. A strong explicit token boots fine.
 */
class RegistryPropertiesTest {

    @Test
    void defaultChangeMeTokenFailsFast() {
        RegistryProperties props = new RegistryProperties(); // publishToken defaults to "change-me"
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            props::requireExplicitPublishToken);
        assertTrue(ex.getMessage().contains("REGISTRY_PUBLISH_TOKEN"),
            "error should point at the env override path");
    }

    @Test
    void explicitChangeMeTokenAlsoFailsFast() {
        RegistryProperties props = new RegistryProperties();
        props.setPublishToken("change-me");
        assertThrows(IllegalStateException.class, props::requireExplicitPublishToken);
    }

    @Test
    void blankOrNullTokenFailsFast() {
        RegistryProperties blank = new RegistryProperties();
        blank.setPublishToken("   ");
        assertThrows(IllegalStateException.class, blank::requireExplicitPublishToken);

        RegistryProperties nul = new RegistryProperties();
        nul.setPublishToken(null);
        assertThrows(IllegalStateException.class, nul::requireExplicitPublishToken);
    }

    @Test
    void strongExplicitTokenPasses() {
        RegistryProperties props = new RegistryProperties();
        props.setPublishToken("prod-9f2c7a1b4e6d8f0a");
        assertDoesNotThrow(props::requireExplicitPublishToken);
    }
}
