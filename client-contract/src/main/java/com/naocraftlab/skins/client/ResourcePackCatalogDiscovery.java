package com.naocraftlab.skins.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;


public final class ResourcePackCatalogDiscovery {
    public static final String PLAYER_TEXTURE_ROOT = "textures/entity/player";
    public static final String CAPE_TEXTURE_ROOT = CapeCatalogSource.CAPE_TEXTURE_ROOT;
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

    public static boolean isCapeCandidatePath(String path) {
        Objects.requireNonNull(path, "path");
        return path.startsWith(CAPE_TEXTURE_ROOT + "/") && path.endsWith(".png");
    }

    public static Optional<ResourcePackCapeCatalog.Variant> capeVariant(
            String namespace, String path, String sourcePackId, int menuRank) {
        return capeVariant(namespace, path, sourcePackId, menuRank, false);
    }

    public static Optional<ResourcePackCapeCatalog.Variant> capeVariant(
            String namespace,
            String path,
            String sourcePackId,
            int menuRank,
            boolean hasAnimationMetadata) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(sourcePackId, "sourcePackId");
        if (hasAnimationMetadata || !isCapeCandidatePath(path)) {
            return Optional.empty();
        }
        String capeId = path.substring(
                CAPE_TEXTURE_ROOT.length() + 1, path.length() - ".png".length());
        if (capeId.isEmpty() || capeId.indexOf('/') >= 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ResourcePackCapeCatalog.Variant(
                    namespace,
                    capeId,
                    sourcePackId,
                    menuRank,
                    namespace + ":" + path));
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

    public record Snapshot(
            List<SkinCatalogSource.CollectionDescriptor> skinCollections,
            List<CapeCatalogSource.CollectionDescriptor> capeCollections) {
        public Snapshot {
            skinCollections = List.copyOf(Objects.requireNonNull(
                    skinCollections, "skinCollections"));
            capeCollections = List.copyOf(Objects.requireNonNull(
                    capeCollections, "capeCollections"));
        }
    }

    public static final class SnapshotCache {
        private long generation = Long.MIN_VALUE;
        private Snapshot snapshot;

        public synchronized Snapshot get(long currentGeneration, Supplier<Snapshot> indexer) {
            Objects.requireNonNull(indexer, "indexer");
            if (currentGeneration == Long.MIN_VALUE) {
                return Objects.requireNonNull(indexer.get(), "indexer returned null");
            }
            if (snapshot == null || generation != currentGeneration) {
                snapshot = Objects.requireNonNull(indexer.get(), "indexer returned null");
                generation = currentGeneration;
            }
            return snapshot;
        }
    }
}
