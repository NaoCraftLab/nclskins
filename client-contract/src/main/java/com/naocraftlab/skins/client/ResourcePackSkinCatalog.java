package com.naocraftlab.skins.client;

import com.naocraftlab.skins.client.SkinCatalogSource.CollectionDescriptor;
import com.naocraftlab.skins.client.SkinCatalogSource.SkinDescriptor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;


public final class ResourcePackSkinCatalog {
    private ResourcePackSkinCatalog() {
    }


    public record Variant(
            String collectionId,
            String skinId,
            SkinModel model,
            String sourcePackId,
            int menuRank) {
        public Variant {

            CatalogText.skinName(collectionId, skinId);
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(sourcePackId, "sourcePackId");
            if (sourcePackId.isBlank()) {
                throw new IllegalArgumentException("sourcePackId must not be blank");
            }
            if (menuRank < CatalogCollectionOrder.UNKNOWN_SOURCE_ORDER) {
                throw new IllegalArgumentException("menuRank must be non-negative or unknown");
            }
        }

        public boolean menuRankKnown() {
            return menuRank != CatalogCollectionOrder.UNKNOWN_SOURCE_ORDER;
        }
    }


    public static List<CollectionDescriptor> build(Collection<Variant> effectiveVariants) {
        Objects.requireNonNull(effectiveVariants, "effectiveVariants");
        Map<String, CollectionBuilder> collections = new TreeMap<>();
        Set<VariantKey> seen = new HashSet<>();
        for (Variant variant : effectiveVariants) {
            Objects.requireNonNull(variant, "effectiveVariants contains null");
            if (MinecraftSkinCatalog.COLLECTION_ID.equals(variant.collectionId())
                    || PersonalSkinCatalog.isCollection(variant.collectionId())) {
                continue;
            }
            VariantKey key = new VariantKey(
                    variant.collectionId(), variant.skinId(), variant.model());
            if (!seen.add(key)) {
                throw new IllegalArgumentException(
                        "Duplicate effective catalog variant: "
                                + variant.collectionId()
                                + "/"
                                + variant.skinId()
                                + "/"
                                + variant.model());
            }
            collections
                    .computeIfAbsent(variant.collectionId(), CollectionBuilder::new)
                    .add(variant);
        }

        return collections.values().stream()
                .map(CollectionBuilder::build)
                .sorted(Comparator
                        .comparingInt(ResourcePackSkinCatalog::orderGroup)
                        .thenComparingInt(collection -> collection.order().sourceOrderKnown()
                                ? collection.order().sourceOrder()
                                : Integer.MAX_VALUE)
                        .thenComparing(CollectionDescriptor::id))
                .toList();
    }

    private static int orderGroup(CollectionDescriptor collection) {
        return collection.order().sourceOrderKnown() ? 0 : 1;
    }

    private record VariantKey(String collectionId, String skinId, SkinModel model) {
    }

    private static final class CollectionBuilder {
        private final String id;
        private final Map<String, Set<SkinModel>> modelsBySkin = new TreeMap<>();
        private final List<Variant> contributors = new ArrayList<>();

        private CollectionBuilder(String id) {
            this.id = id;
        }

        private void add(Variant variant) {
            contributors.add(variant);
            modelsBySkin
                    .computeIfAbsent(variant.skinId(), ignored -> new HashSet<>())
                    .add(variant.model());
        }

        private CollectionDescriptor build() {
            List<SkinDescriptor> skins = modelsBySkin.entrySet().stream()
                    .map(entry -> new SkinDescriptor(
                            entry.getKey(),
                            CatalogText.skinName(id, entry.getKey()),
                            Optional.of(CatalogText.skinDescription(id, entry.getKey())),
                            Optional.of(CatalogText.skinAuthors(id, entry.getKey())),
                            orderedModels(entry.getValue())))
                    .toList();
            return new CollectionDescriptor(
                    id,
                    CatalogText.collectionName(id),
                    Optional.of(CatalogText.collectionDescription(id)),
                    Optional.of(CatalogText.collectionAuthors(id)),
                    skins,
                    collectionOrder(contributors));
        }
    }

    private static CatalogCollectionOrder collectionOrder(List<Variant> contributors) {
        Variant bestKnown = contributors.stream()
                .filter(Variant::menuRankKnown)
                .min(Comparator.comparingInt(Variant::menuRank)
                        .thenComparing(Variant::sourcePackId))
                .orElse(null);
        if (bestKnown != null) {
            return CatalogCollectionOrder.resourcePack(
                    bestKnown.sourcePackId(), bestKnown.menuRank());
        }
        String stablePackId = contributors.stream()
                .map(Variant::sourcePackId)
                .min(String::compareTo)
                .orElseThrow();
        return CatalogCollectionOrder.unknownResourcePack(stablePackId);
    }

    private static List<SkinModel> orderedModels(Set<SkinModel> models) {
        List<SkinModel> ordered = new ArrayList<>(2);
        if (models.contains(SkinModel.CLASSIC)) {
            ordered.add(SkinModel.CLASSIC);
        }
        if (models.contains(SkinModel.SLIM)) {
            ordered.add(SkinModel.SLIM);
        }
        return List.copyOf(ordered);
    }
}
