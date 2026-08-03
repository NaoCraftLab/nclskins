package com.naocraftlab.skins.client;

import com.naocraftlab.skins.client.SkinCatalogSource.CollectionDescriptor;
import com.naocraftlab.skins.client.SkinCatalogSource.SkinDescriptor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkinCatalogSourceTest {
    @Test
    void minecraftCatalogDeclaresNineSkinsWithBothModelsInStableOrder() {
        var collections = MinecraftSkinCatalog.collections();

        assertEquals("minecraft-vanilla", MinecraftSkinCatalog.SOURCE_ID);
        assertEquals(1, collections.size());
        assertEquals(MinecraftSkinCatalog.COLLECTION_ID, collections.get(0).id());
        assertEquals(CatalogCollectionOrder.Kind.VANILLA, collections.get(0).order().kind());
        assertEquals(MinecraftSkinCatalog.SOURCE_ID, collections.get(0).sourceId());
        assertEquals(
                List.of("Steve", "Alex", "Ari", "Efe", "Kai", "Makena", "Noor", "Sunny", "Zuri"),
                collections.get(0).skins().stream().map(SkinDescriptor::name).toList());
        collections.get(0).skins().forEach(skin -> {
            assertEquals(List.of(SkinModel.CLASSIC, SkinModel.SLIM), skin.models());
            assertEquals(
                    "textures/entity/player/wide/" + skin.id() + ".png",
                    MinecraftSkinCatalog.texturePath(
                            collections.get(0).id(), skin.id(), SkinModel.CLASSIC));
            assertEquals(
                    "textures/entity/player/slim/" + skin.id() + ".png",
                    MinecraftSkinCatalog.texturePath(
                            collections.get(0).id(), skin.id(), SkinModel.SLIM));
        });
    }

    @Test
    void bootstrapMethodsUseStableSteveClassicAndAlexSlimIds() throws Exception {
        AtomicReference<String> selection = new AtomicReference<>();
        SkinCatalogSource source = (collectionId, skinId, model) -> {
            selection.set(collectionId + "/" + skinId + "/" + model);
            return new byte[]{(byte) model.ordinal()};
        };

        assertArrayEquals(new byte[]{0}, source.classic());
        assertEquals("minecraft/steve/CLASSIC", selection.get());
        assertArrayEquals(new byte[]{1}, source.slim());
        assertEquals("minecraft/alex/SLIM", selection.get());
    }

    @Test
    void legacyBootstrapSourceDoesNotPretendToProvideTheFullCatalog() throws Exception {
        BundledSkinSource legacy = model -> new byte[]{(byte) model.ordinal()};

        assertEquals(List.of(), legacy.collections());
        assertArrayEquals(new byte[]{0}, legacy.classic());
        assertArrayEquals(new byte[]{1}, legacy.slim());
        assertThrows(
                java.io.IOException.class,
                () -> legacy.load("minecraft", "ari", SkinModel.CLASSIC));
    }

    @Test
    void minecraftPathsRejectUnknownIdsAndNeverFallbackAcrossModels() {
        assertEquals(
                "textures/entity/player/wide/steve.png",
                MinecraftSkinCatalog.texturePath("minecraft", "steve", SkinModel.CLASSIC));
        assertEquals(
                "textures/entity/player/slim/steve.png",
                MinecraftSkinCatalog.texturePath("minecraft", "steve", SkinModel.SLIM));
        assertThrows(
                IllegalArgumentException.class,
                () -> MinecraftSkinCatalog.texturePath("minecraft", "missing", SkinModel.CLASSIC));
        assertThrows(
                IllegalArgumentException.class,
                () -> MinecraftSkinCatalog.texturePath("other", "steve", SkinModel.CLASSIC));
    }

    @Test
    void descriptorsAndReturnedBytesAreDefensivelyOwned() {
        var mutableModels = new ArrayList<>(List.of(SkinModel.CLASSIC));
        var skin = new SkinDescriptor(
                "custom", "Custom", Optional.empty(), Optional.empty(), mutableModels);
        mutableModels.add(SkinModel.SLIM);
        assertEquals(List.of(SkinModel.CLASSIC), skin.models());
        assertThrows(UnsupportedOperationException.class, () -> skin.models().add(SkinModel.SLIM));

        byte[] supplied = {1, 2, 3};
        byte[] owned = SkinCatalogSource.ownedCopy(supplied);
        supplied[0] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, owned);
        assertNotSame(supplied, owned);
        assertThrows(IllegalArgumentException.class, () -> SkinCatalogSource.ownedCopy(new byte[0]));
    }

    @Test
    void descriptorsRejectAmbiguousOrBlankMetadata() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SkinDescriptor(
                        "skin",
                        "Skin",
                        Optional.empty(),
                        Optional.empty(),
                        List.of(SkinModel.CLASSIC, SkinModel.CLASSIC)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SkinDescriptor(
                        " ", "Skin", Optional.empty(), Optional.empty(), List.of(SkinModel.CLASSIC)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SkinDescriptor(
                        "Uppercase",
                        "Skin",
                        Optional.empty(),
                        Optional.empty(),
                        List.of(SkinModel.CLASSIC)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SkinDescriptor(
                        "skin", "Skin", Optional.of(" "), Optional.empty(), List.of(SkinModel.CLASSIC)));
    }

    @Test
    void catalogTranslationFactoriesUseGlobalKeysAndHumanizedFallbacks() {
        assertEquals(
                CatalogText.translated("nclskins.halloween_2025.name", "Halloween 2025"),
                CatalogText.collectionName("halloween_2025"));
        assertEquals(
                CatalogText.translated(
                        "nclskins.halloween_2025.skin.zombie-villager.name",
                        "Zombie Villager"),
                CatalogText.skinName("halloween_2025", "zombie-villager"));
        assertEquals(
                "nclskins.halloween_2025.description",
                CatalogText.collectionDescription("halloween_2025")
                        .translationKey()
                        .orElseThrow());
        assertEquals(
                "nclskins.halloween_2025.skin.zombie-villager.authors",
                CatalogText.skinAuthors("halloween_2025", "zombie-villager")
                        .translationKey()
                        .orElseThrow());
        assertEquals("Migration Cape", CatalogText.humanizeId("migration_cape"));
        assertThrows(IllegalArgumentException.class, () -> CatalogText.collectionName("Uppercase"));
        assertThrows(
                IllegalArgumentException.class,
                () -> CatalogText.collectionName("foo.skin.bar"));
    }

    @Test
    void layeredCatalogRoutesResourceCollectionsBeforeTheReservedVanillaCollection()
            throws Exception {
        var event = new CollectionDescriptor(
                "earth",
                CatalogText.collectionName("earth"),
                Optional.of(CatalogText.collectionDescription("earth")),
                Optional.of(CatalogText.collectionAuthors("earth")),
                List.of(new SkinDescriptor(
                        "bee",
                        CatalogText.skinName("earth", "bee"),
                        Optional.empty(),
                        Optional.empty(),
                        List.of(SkinModel.SLIM))),
                CatalogCollectionOrder.resourcePack("file/mojang.zip", 0));
        SkinCatalogSource packs = new SkinCatalogSource() {
            @Override
            public byte[] load(String collectionId, String skinId, SkinModel model) {
                return new byte[]{1};
            }

            @Override
            public List<CollectionDescriptor> collections() {
                return List.of(event, MinecraftSkinCatalog.collections().get(0));
            }
        };
        SkinCatalogSource vanilla = (collectionId, skinId, model) -> new byte[]{2};

        SkinCatalogSource layered = SkinCatalogSource.resourcePacksBeforeVanilla(packs, vanilla);

        assertEquals(
                List.of("earth", "minecraft"),
                layered.collections().stream().map(CollectionDescriptor::id).toList());
        assertArrayEquals(new byte[]{1}, layered.load("earth", "bee", SkinModel.SLIM));
        assertArrayEquals(new byte[]{2}, layered.load("minecraft", "steve", SkinModel.CLASSIC));
        assertTrue(layered.collections().get(1).order().kind()
                == CatalogCollectionOrder.Kind.VANILLA);
    }
}
