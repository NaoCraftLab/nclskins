package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BuiltInClientPackDescriptorTest {
    @Test
    void mojangCollectionsHasCanonicalRegistrationSemantics() {
        BuiltInClientPackDescriptor descriptor =
                BuiltInClientPackDescriptor.MOJANG_COLLECTIONS;

        assertEquals("nclskins:mojang_collections", descriptor.packId());
        assertEquals("resourcepacks/mojang_collections", descriptor.nestedSource());
        assertEquals(
                "pack.nclskins.mojang_collections.name",
                descriptor.displayTranslationKey());
        assertEquals("nclskins:cape_catalog_reload", descriptor.reloadListenerId());
        assertEquals(
                BuiltInClientPackDescriptor.Activation.DEFAULT_ENABLED,
                descriptor.activation());
        assertEquals(BuiltInClientPackDescriptor.Position.BOTTOM, descriptor.position());
    }

    @Test
    void invalidIdentifierPartsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BuiltInClientPackDescriptor(
                "NCL Skins",
                "pack",
                "resourcepacks/pack",
                "pack.name",
                "reload",
                BuiltInClientPackDescriptor.Activation.DEFAULT_ENABLED,
                BuiltInClientPackDescriptor.Position.BOTTOM));
    }
}
