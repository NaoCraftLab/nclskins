package com.naocraftlab.skins.core.provider;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.naocraftlab.skins.core.provider.AppearanceProviders.Component.CAPE;
import static com.naocraftlab.skins.core.provider.AppearanceProviders.Component.SKIN;
import static com.naocraftlab.skins.core.provider.ProviderCapability.DISTRIBUTION;
import static com.naocraftlab.skins.core.provider.ProviderCapability.LIMITED_WRITE;
import static com.naocraftlab.skins.core.provider.ProviderCapability.WRITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderCapabilityTest {
    @Test
    void capabilitiesAreComponentSpecificAndImmutable() {
        assertEquals(Set.of(WRITE), BuiltinProvider.OFFLINE.capabilities(SKIN));
        assertEquals(Set.of(WRITE), BuiltinProvider.OFFLINE.capabilities(CAPE));
        assertEquals(Set.of(WRITE, DISTRIBUTION), BuiltinProvider.MINECRAFT.capabilities(SKIN));
        assertEquals(Set.of(LIMITED_WRITE, DISTRIBUTION), BuiltinProvider.MINECRAFT.capabilities(CAPE));
        for (BuiltinProvider provider : Set.of(BuiltinProvider.OPTIFINE,
                BuiltinProvider.SKINMC, BuiltinProvider.SNEAKY)) {
            assertFalse(provider.supportsSkin());
            assertEquals(Set.of(), provider.capabilities(SKIN));
            assertEquals(Set.of(DISTRIBUTION), provider.capabilities(CAPE));
            assertFalse(provider.canWrite(CAPE));
        }
        for (BuiltinProvider provider : BuiltinProvider.values()) {
            for (var component : AppearanceProviders.Component.values()) {
                var capabilities = provider.capabilities(component);
                assertFalse(capabilities.contains(WRITE) && capabilities.contains(LIMITED_WRITE));
                assertEquals(capabilities.contains(WRITE) || capabilities.contains(LIMITED_WRITE),
                        provider.canWrite(component));
                assertThrows(UnsupportedOperationException.class,
                        () -> capabilities.add(DISTRIBUTION));
            }
        }
        assertTrue(BuiltinProvider.MINECRAFT.canWrite(CAPE));
        assertFalse(BuiltinProvider.OFFLINE.capabilities(CAPE).contains(DISTRIBUTION));
    }
}
