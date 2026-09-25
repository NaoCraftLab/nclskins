package com.naocraftlab.skins.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
        Map<Key, Variant> selected = new TreeMap<>(Comparator
                .comparing(Key::collectionId).thenComparing(Key::capeId));
        for (Variant variant : effectiveVariants) {
            Objects.requireNonNull(variant, "effectiveVariants contains null");
            if (CapeCatalogSource.PERSONAL_COLLECTION_ID.equals(variant.collectionId())
                    || CapeCatalogSource.MINECRAFT_COLLECTION_ID.equals(variant.collectionId())) {
                continue;
            }
            Key key = new Key(variant.collectionId(), variant.capeId());
            Variant previous = selected.get(key);
            if (previous != null && previous.sourcePackId().equals(variant.sourcePackId())
                    && previous.contentIdentity().equals(variant.contentIdentity())) {
                throw new IllegalArgumentException("Duplicate effective catalog cape: "
                        + variant.collectionId() + "/" + variant.capeId());
            }
            if (previous == null || compareSource(variant, previous) < 0) {
                selected.put(key, variant);
            }
        }
        selected.values().forEach(variant -> collections
                .computeIfAbsent(variant.collectionId(), CollectionBuilder::new).add(variant));
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

    private static int compareSource(Variant first, Variant second) {
        if (first.menuRankKnown() != second.menuRankKnown()) {
            return first.menuRankKnown() ? -1 : 1;
        }
        if (first.menuRankKnown() && first.menuRank() != second.menuRank()) {
            return Integer.compare(first.menuRank(), second.menuRank());
        }
        int pack = first.sourcePackId().compareTo(second.sourcePackId());
        if (pack != 0) return pack;
        int extension = Integer.compare(extensionPriority(first.contentIdentity()),
                extensionPriority(second.contentIdentity()));
        return extension != 0 ? extension
                : first.contentIdentity().compareTo(second.contentIdentity());
    }

    private static int extensionPriority(String identity) {
        if (identity.endsWith(".png")) return 0;
        if (identity.endsWith(".jpg")) return 1;
        if (identity.endsWith(".jpeg")) return 2;
        return 3;
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
