package com.naocraftlab.skins.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;


public final class ResourcePackCatalogDiscovery {
    public static final String INDEX_NAMESPACE = "nclskins";
    public static final String INDEX_PATH = "collections.json";
    public static final String PLAYER_TEXTURE_ROOT = "textures/entity/player";
    private static final String WIDE_DIRECTORY = PLAYER_TEXTURE_ROOT + "/wide/";
    private static final String SLIM_DIRECTORY = PLAYER_TEXTURE_ROOT + "/slim/";

    private ResourcePackCatalogDiscovery() {
    }


    public static boolean isCandidatePath(String path) {
        Objects.requireNonNull(path, "path");
        return path.endsWith(".png")
                && (path.startsWith(WIDE_DIRECTORY) || path.startsWith(SLIM_DIRECTORY));
    }


    public static String texturePath(String skinId, SkinModel model) {
        Objects.requireNonNull(model, "model");

        CatalogText.skinName("catalog", skinId);
        String directory = model == SkinModel.CLASSIC ? WIDE_DIRECTORY : SLIM_DIRECTORY;
        return directory + skinId + ".png";
    }


    public static Optional<ResourcePackSkinCatalog.Variant> variant(
            String namespace, String path, String sourcePackId, int menuRank) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(sourcePackId, "sourcePackId");
        String directory;
        SkinModel model;
        if (path.startsWith(WIDE_DIRECTORY)) {
            directory = WIDE_DIRECTORY;
            model = SkinModel.CLASSIC;
        } else if (path.startsWith(SLIM_DIRECTORY)) {
            directory = SLIM_DIRECTORY;
            model = SkinModel.SLIM;
        } else {
            return Optional.empty();
        }
        if (!path.endsWith(".png")) {
            return Optional.empty();
        }
        String skinId = path.substring(directory.length(), path.length() - ".png".length());
        if (skinId.isEmpty() || skinId.indexOf('/') >= 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ResourcePackSkinCatalog.Variant(
                    namespace, skinId, model, sourcePackId, menuRank));
        } catch (IllegalArgumentException invalidCatalogId) {
            return Optional.empty();
        }
    }


    public static Map<String, Integer> selectedPackMenuRanks(List<String> lowToHighPackIds) {
        Objects.requireNonNull(lowToHighPackIds, "lowToHighPackIds");
        Map<String, Integer> ranks = new LinkedHashMap<>();
        int rank = 0;
        for (int index = lowToHighPackIds.size() - 1; index >= 0; index--) {
            String packId = Objects.requireNonNull(
                    lowToHighPackIds.get(index), "lowToHighPackIds contains null");
            if (packId.isBlank()) {
                throw new IllegalArgumentException("pack ID must not be blank");
            }
            if (!ranks.containsKey(packId)) {
                ranks.put(packId, rank++);
            }
        }
        return Map.copyOf(ranks);
    }
}
