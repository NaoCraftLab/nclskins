package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePackCatalogDiscoveryTest {
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

    @Test
    void parsesOnlyDirectCapeTexturesWithIndependentLocalization() {
        var cape = ResourcePackCatalogDiscovery.capeVariant(
                "event_pack",
                "textures/entity/cape/hero.png",
                "file/event.zip",
                1).orElseThrow();

        assertEquals("hero", cape.capeId());
        assertEquals("event_pack:textures/entity/cape/hero.png", cape.contentIdentity());
        assertTrue(ResourcePackCatalogDiscovery.isCapeCandidatePath("textures/entity/cape/hero.jpg"));
        assertTrue(ResourcePackCatalogDiscovery.isCapeCandidatePath("textures/entity/cape/hero.jpeg"));
        assertEquals("event_pack:textures/entity/cape/hero.jpg",
                ResourcePackCatalogDiscovery.capeVariant("event_pack",
                        "textures/entity/cape/hero.jpg", "file/event.zip", 1)
                        .orElseThrow().contentIdentity());
        assertEquals(
                "nclskins.event_pack.cape.hero.name",
                CatalogText.capeName("event_pack", "hero").translationKey().orElseThrow());
        assertFalse(ResourcePackCatalogDiscovery.capeVariant(
                "event_pack", "textures/entity/cape/nested/hero.png", "file/event.zip", 1).isPresent());
        assertFalse(ResourcePackCatalogDiscovery.capeVariant(
                "event_pack", "textures/entity/cape/nested/hero.jpeg", "file/event.zip", 1).isPresent());
        assertFalse(ResourcePackCatalogDiscovery.capeVariant(
                "event_pack", "textures/entity/player/wide/hero.png", "file/event.zip", 1).isPresent());
        assertFalse(ResourcePackCatalogDiscovery.capeVariant(
                "event_pack", "textures/entity/cape/hero.png", "file/event.zip", 1, true).isPresent());
    }

    @Test
    void snapshotCacheIndexesKnownGenerationOnceAndNeverCachesUnknownGeneration() {
        var cache = new ResourcePackCatalogDiscovery.SnapshotCache();
        AtomicInteger indexes = new AtomicInteger();
        java.util.function.Supplier<ResourcePackCatalogDiscovery.Snapshot> indexer = () -> {
            indexes.incrementAndGet();
            return new ResourcePackCatalogDiscovery.Snapshot(List.of(), List.of());
        };

        var first = cache.get(7, indexer);
        var repeated = cache.get(7, indexer);
        var reloaded = cache.get(8, indexer);
        var unknown = cache.get(Long.MIN_VALUE, indexer);
        var repeatedUnknown = cache.get(Long.MIN_VALUE, indexer);

        assertTrue(first == repeated);
        assertFalse(first == reloaded);
        assertFalse(unknown == repeatedUnknown);
        assertEquals(4, indexes.get());
    }
}
