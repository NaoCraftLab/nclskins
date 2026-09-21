package com.naocraftlab.skins.client;

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

public final class ResourcePackCapeCatalog {
    private ResourcePackCapeCatalog() {
    }

    public record Variant(
            String collectionId,
            String capeId,
            String sourcePackId,
            int menuRank,
            String contentIdentity) {
        public Variant {
            CatalogText.capeName(collectionId, capeId);
            sourcePackId = Objects.requireNonNull(sourcePackId, "sourcePackId");
            contentIdentity = Objects.requireNonNull(contentIdentity, "contentIdentity");
            if (sourcePackId.isBlank() || contentIdentity.isBlank()) {
                throw new IllegalArgumentException("cape source identity must not be blank");
            }
            if (menuRank < CatalogCollectionOrder.UNKNOWN_SOURCE_ORDER) {
                throw new IllegalArgumentException("menuRank must be non-negative or unknown");
            }
        }

        public boolean menuRankKnown() {
            return menuRank != CatalogCollectionOrder.UNKNOWN_SOURCE_ORDER;
        }
    }

    public static List<CapeCatalogSource.CollectionDescriptor> build(
            Collection<Variant> effectiveVariants) {
        Objects.requireNonNull(effectiveVariants, "effectiveVariants");
        Map<String, CollectionBuilder> collections = new TreeMap<>();
        Set<Key> seen = new HashSet<>();
        for (Variant variant : effectiveVariants) {
            Objects.requireNonNull(variant, "effectiveVariants contains null");
            if (CapeCatalogSource.PERSONAL_COLLECTION_ID.equals(variant.collectionId())
                    || CapeCatalogSource.MINECRAFT_COLLECTION_ID.equals(variant.collectionId())) {
                continue;
            }
            if (!seen.add(new Key(variant.collectionId(), variant.capeId()))) {
                throw new IllegalArgumentException(
                        "Duplicate effective catalog cape: "
                                + variant.collectionId() + "/" + variant.capeId());
            }
            collections.computeIfAbsent(variant.collectionId(), CollectionBuilder::new).add(variant);
        }
        return collections.values().stream()
                .map(CollectionBuilder::build)
                .sorted(Comparator
                        .comparingInt(ResourcePackCapeCatalog::orderGroup)
                        .thenComparingInt(value -> value.order().sourceOrderKnown()
                                ? value.order().sourceOrder() : Integer.MAX_VALUE)
                        .thenComparing(CapeCatalogSource.CollectionDescriptor::id))
                .toList();
    }

    private static int orderGroup(CapeCatalogSource.CollectionDescriptor value) {
        return value.order().sourceOrderKnown() ? 0 : 1;
    }

    private record Key(String collectionId, String capeId) {
    }

    private static final class CollectionBuilder {
        private final String id;
        private final Map<String, Variant> capes = new TreeMap<>();
        private final List<Variant> contributors = new ArrayList<>();

        private CollectionBuilder(String id) {
            this.id = id;
        }

        private void add(Variant variant) {
            contributors.add(variant);
            capes.put(variant.capeId(), variant);
        }

        private CapeCatalogSource.CollectionDescriptor build() {
            return new CapeCatalogSource.CollectionDescriptor(
                    id,
                    CatalogText.collectionName(id),
                    Optional.of(CatalogText.collectionDescription(id)),
                    Optional.of(CatalogText.collectionAuthors(id)),
                    capes.values().stream().map(variant -> new CapeCatalogSource.CapeDescriptor(
                            variant.capeId(),
                            CatalogText.capeName(id, variant.capeId()),
                            Optional.of(CatalogText.capeDescription(id, variant.capeId())),
                            Optional.of(CatalogText.capeAuthors(id, variant.capeId())),
                            variant.contentIdentity(),
                            CapeCatalogSource.RenderSupport.CAPE_AND_ELYTRA)).toList(),
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
}
