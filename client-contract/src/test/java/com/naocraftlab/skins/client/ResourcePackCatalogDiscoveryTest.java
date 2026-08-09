package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePackCatalogDiscoveryTest {
    @Test
    void usesOneStableResourceStackLocationForCollectionIndexes() {
        assertEquals("nclskins", ResourcePackCatalogDiscovery.INDEX_NAMESPACE);
        assertEquals("collections.json", ResourcePackCatalogDiscovery.INDEX_PATH);
    }

    @Test
    void parsesOnlyDirectWideAndSlimVanillaPlayerPaths() {
        var wide = ResourcePackCatalogDiscovery.variant(
                        "event_pack",
                        "textures/entity/player/wide/hero.png",
                        "file/event.zip",
                        2)
                .orElseThrow();
        var slim = ResourcePackCatalogDiscovery.variant(
                        "event_pack",
                        "textures/entity/player/slim/hero.png",
                        "file/event.zip",
                        2)
                .orElseThrow();

        assertEquals(SkinModel.CLASSIC, wide.model());
        assertEquals(SkinModel.SLIM, slim.model());
        assertEquals("hero", slim.skinId());
        assertFalse(ResourcePackCatalogDiscovery.variant(
                        "event_pack",
                        "textures/entity/player/wide/nested/hero.png",
                        "file/event.zip",
                        0)
                .isPresent());
        assertFalse(ResourcePackCatalogDiscovery.variant(
                        "event_pack",
                        "textures/entity/player/wide/Uppercase.png",
                        "file/event.zip",
                        0)
                .isPresent());
        assertFalse(ResourcePackCatalogDiscovery.variant(
                        "event.pack",
                        "textures/entity/player/wide/hero.png",
                        "file/event.zip",
                        0)
                .isPresent());
        assertFalse(ResourcePackCatalogDiscovery.variant(
                        "event_pack",
                        "textures/entity/player/wide/red.fox.png",
                        "file/event.zip",
                        0)
                .isPresent());
        assertFalse(ResourcePackCatalogDiscovery.variant(
                        "event_pack", "textures/entity/player/hero.png", "file/event.zip", 0)
                .isPresent());
    }

    @Test
    void mapsLowToHighVisibleSelectionToVanillaMenuOrder() {
        var ranks = ResourcePackCatalogDiscovery.selectedPackMenuRanks(
                List.of("vanilla", "mod_resources", "file/lower.zip", "file/top.zip"));

        assertEquals(0, ranks.get("file/top.zip"));
        assertEquals(1, ranks.get("file/lower.zip"));
        assertEquals(3, ranks.get("vanilla"));
        assertFalse(ranks.containsKey("mod/hidden_child"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ResourcePackCatalogDiscovery.selectedPackMenuRanks(List.of("vanilla", "")));
    }

    @Test
    void candidatePredicateIsBroadButStillLimitedToPlayerModelDirectories() {
        assertTrue(ResourcePackCatalogDiscovery.isCandidatePath(
                "textures/entity/player/wide/hero.png"));
        assertTrue(ResourcePackCatalogDiscovery.isCandidatePath(
                "textures/entity/player/slim/nested/hero.png"));
        assertFalse(ResourcePackCatalogDiscovery.isCandidatePath(
                "textures/entity/player/wide/hero.png.mcmeta"));
        assertFalse(ResourcePackCatalogDiscovery.isCandidatePath(
                "textures/entity/zombie/wide/hero.png"));
        assertEquals(
                "textures/entity/player/slim/hero.png",
                ResourcePackCatalogDiscovery.texturePath("hero", SkinModel.SLIM));
    }
}
