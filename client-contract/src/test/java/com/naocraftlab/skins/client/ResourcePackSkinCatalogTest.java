package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ResourcePackSkinCatalogTest {
    @Test
    void groupsEffectiveVariantsAndUsesTheHighestContributingPackForCollectionOrder() {
        var collections = ResourcePackSkinCatalog.build(List.of(
                variant("summer", "surfer", SkinModel.SLIM, "low", 3),
                variant("summer", "beach", SkinModel.SLIM, "high", 0),
                variant("summer", "beach", SkinModel.CLASSIC, "middle", 2),
                variant("classic", "zuri", SkinModel.CLASSIC, "middle", 2),
                variant("unknown", "mystery", SkinModel.CLASSIC, "folder/z", -1),
                variant(PersonalSkinCatalog.COLLECTION_ID, "spoof", SkinModel.CLASSIC, "override", 0),
                variant("minecraft", "steve", SkinModel.CLASSIC, "override", 0)));

        assertEquals(List.of("summer", "classic", "unknown"), collections.stream()
                .map(SkinCatalogSource.CollectionDescriptor::id)
                .toList());
        var summer = collections.get(0);
        assertEquals("high", summer.order().sourceId());
        assertEquals(0, summer.order().sourceOrder());
        assertEquals(List.of("beach", "surfer"), summer.skins().stream()
                .map(SkinCatalogSource.SkinDescriptor::id)
                .toList());
        assertEquals(
                List.of(SkinModel.CLASSIC, SkinModel.SLIM),
                summer.skins().get(0).models());
        assertEquals(
                "nclskins.summer.skin.beach.name",
                summer.skins().get(0).nameText().translationKey().orElseThrow());
        assertEquals(
                CatalogCollectionOrder.Kind.RESOURCE_PACK,
                collections.get(2).order().kind());
        assertEquals(-1, collections.get(2).order().sourceOrder());
    }

    @Test
    void rejectsDuplicateLogicalVariantBecauseInputMustAlreadyBeResourceStackCollapsed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ResourcePackSkinCatalog.build(List.of(
                        variant("summer", "beach", SkinModel.CLASSIC, "low", 2),
                        variant("summer", "beach", SkinModel.CLASSIC, "high", 0))));
    }

    private static ResourcePackSkinCatalog.Variant variant(
            String collection,
            String skin,
            SkinModel model,
            String sourcePack,
            int rank) {
        return new ResourcePackSkinCatalog.Variant(collection, skin, model, sourcePack, rank);
    }
}
