package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourcePackCapeCatalogTest {
    @Test
    void ordersCollectionsByBestContributingPackAndCapesByStableId() {
        var collections = ResourcePackCapeCatalog.build(List.of(
                variant("lower", "zeta", "lower-pack", 2),
                variant("top", "beta", "top-pack", 0),
                variant("top", "alpha", "lower-pack", 2)));

        assertEquals(List.of("top", "lower"), collections.stream()
                .map(CapeCatalogSource.CollectionDescriptor::id).toList());
        assertEquals(List.of("alpha", "beta"), collections.get(0).capes().stream()
                .map(CapeCatalogSource.CapeDescriptor::id).toList());
    }

    @Test
    void rejectsDuplicateEffectiveCapeButAllowsSameIdInSkinCatalog() {
        var variant = variant("event", "hero", "pack", 0);
        assertThrows(
                IllegalArgumentException.class,
                () -> ResourcePackCapeCatalog.build(List.of(variant, variant)));

        var skin = new ResourcePackSkinCatalog.Variant(
                "event", "hero", SkinModel.CLASSIC, "pack", 0);
        assertEquals(1, ResourcePackSkinCatalog.build(List.of(skin)).size());
        assertEquals(1, ResourcePackCapeCatalog.build(List.of(variant)).size());
    }

    @Test
    void excludesReservedPersonalAndMinecraftCollectionIds() {
        assertEquals(List.of(), ResourcePackCapeCatalog.build(List.of(
                variant(CapeCatalogSource.PERSONAL_COLLECTION_ID, "one", "pack", 0),
                variant(CapeCatalogSource.MINECRAFT_COLLECTION_ID, "two", "pack", 0))));
    }

    private static ResourcePackCapeCatalog.Variant variant(
            String collectionId, String capeId, String packId, int rank) {
        return new ResourcePackCapeCatalog.Variant(
                collectionId,
                capeId,
                packId,
                rank,
                collectionId + ":" + CapeCatalogSource.texturePath(capeId));
    }
}
