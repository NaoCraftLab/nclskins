package com.naocraftlab.skins.server.plugin.bukkit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;


final class ExactAuthlibSignatureVerifierTest {
    @Test
    void resolvesAuthlib10SessionServiceThroughMinecraftServices() throws Exception {
        assertNotNull(ExactAuthlibSignatureVerifier.resolve(
                getClass().getClassLoader(),
                "com.naocraftlab.skins.server.plugin.bukkit.fixture",
                "authlib-v10",
                false));
    }

    @Test
    void rejectsUnknownAuthlibFamilyWithoutShapeFallback() {
        assertThrows(IllegalArgumentException.class, () ->
                ExactAuthlibSignatureVerifier.resolve(
                        getClass().getClassLoader(),
                        "com.naocraftlab.skins.server.plugin.bukkit.fixture",
                        "authlib-v11",
                        false));
    }
}
