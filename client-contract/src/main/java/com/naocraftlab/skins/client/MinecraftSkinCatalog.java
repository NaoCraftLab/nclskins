package com.naocraftlab.skins.client;

import com.naocraftlab.skins.client.SkinCatalogSource.CollectionDescriptor;
import com.naocraftlab.skins.client.SkinCatalogSource.SkinDescriptor;

import java.util.List;
import java.util.Objects;
import java.util.Optional;


public final class MinecraftSkinCatalog {
    public static final String SOURCE_ID = "minecraft-vanilla";
    public static final String COLLECTION_ID = "minecraft";
    public static final String STEVE_SKIN_ID = "steve";
    public static final String ALEX_SKIN_ID = "alex";
    public static final String ARI_SKIN_ID = "ari";
    public static final String EFE_SKIN_ID = "efe";
    public static final String KAI_SKIN_ID = "kai";
    public static final String MAKENA_SKIN_ID = "makena";
    public static final String NOOR_SKIN_ID = "noor";
    public static final String SUNNY_SKIN_ID = "sunny";
    public static final String ZURI_SKIN_ID = "zuri";

    private static final List<SkinModel> BOTH_MODELS =
            List.of(SkinModel.CLASSIC, SkinModel.SLIM);
    private static final List<SkinDescriptor> SKINS = List.of(
            skin(STEVE_SKIN_ID, "Steve"),
            skin(ALEX_SKIN_ID, "Alex"),
            skin(ARI_SKIN_ID, "Ari"),
            skin(EFE_SKIN_ID, "Efe"),
            skin(KAI_SKIN_ID, "Kai"),
            skin(MAKENA_SKIN_ID, "Makena"),
            skin(NOOR_SKIN_ID, "Noor"),
            skin(SUNNY_SKIN_ID, "Sunny"),
            skin(ZURI_SKIN_ID, "Zuri"));
    private static final CollectionDescriptor COLLECTION = new CollectionDescriptor(
            COLLECTION_ID,
            CatalogText.translated("nclskins.standard_skins.name", "Standard"),
            Optional.of(CatalogText.translated(
                    "nclskins.standard_skins.description",
                    "Standard Minecraft Java Edition skins")),
            Optional.of(CatalogText.translated(
                    "nclskins.standard_skins.authors", "Mojang Studios")),
            SKINS,
            CatalogCollectionOrder.vanilla(SOURCE_ID));
    private static final List<CollectionDescriptor> COLLECTIONS = List.of(COLLECTION);

    private MinecraftSkinCatalog() {
    }

    public static List<CollectionDescriptor> collections() {
        return COLLECTIONS;
    }


    public static String texturePath(String collectionId, String skinId, SkinModel model) {
        Objects.requireNonNull(collectionId, "collectionId");
        Objects.requireNonNull(skinId, "skinId");
        Objects.requireNonNull(model, "model");
        if (!COLLECTION_ID.equals(collectionId) || !isKnownSkin(skinId)) {
            throw new IllegalArgumentException(
                    "Unknown Minecraft catalog skin: " + collectionId + "/" + skinId);
        }

        String modelDirectory = model == SkinModel.CLASSIC ? "wide" : "slim";
        return "textures/entity/player/" + modelDirectory + "/" + skinId + ".png";
    }

    private static SkinDescriptor skin(String id, String name) {
        return new SkinDescriptor(
                id,
                CatalogText.literal(name),
                Optional.empty(),
                Optional.empty(),
                BOTH_MODELS);
    }

    private static boolean isKnownSkin(String skinId) {
        return switch (skinId) {
            case STEVE_SKIN_ID,
                 ALEX_SKIN_ID,
                 ARI_SKIN_ID,
                 EFE_SKIN_ID,
                 KAI_SKIN_ID,
                 MAKENA_SKIN_ID,
                 NOOR_SKIN_ID,
                 SUNNY_SKIN_ID,
                 ZURI_SKIN_ID -> true;
            default -> false;
        };
    }
}
