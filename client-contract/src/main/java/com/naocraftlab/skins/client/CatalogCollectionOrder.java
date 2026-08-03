package com.naocraftlab.skins.client;

import java.util.Objects;


public record CatalogCollectionOrder(Kind kind, String sourceId, int sourceOrder) {
    public static final int UNKNOWN_SOURCE_ORDER = -1;

    public enum Kind {
        PERSONAL,
        RESOURCE_PACK,
        UNSPECIFIED,
        VANILLA
    }

    public CatalogCollectionOrder {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(sourceId, "sourceId");
        if (sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        if (sourceOrder < UNKNOWN_SOURCE_ORDER) {
            throw new IllegalArgumentException("sourceOrder must be non-negative or unknown");
        }
        if (kind != Kind.RESOURCE_PACK && sourceOrder != UNKNOWN_SOURCE_ORDER) {
            throw new IllegalArgumentException("only resource-pack collections have a source order");
        }
    }


    public static CatalogCollectionOrder resourcePack(String packId, int sourceOrder) {
        if (sourceOrder < 0) {
            throw new IllegalArgumentException("sourceOrder must not be negative");
        }
        return new CatalogCollectionOrder(Kind.RESOURCE_PACK, packId, sourceOrder);
    }


    public static CatalogCollectionOrder personal(String sourceId) {
        return new CatalogCollectionOrder(Kind.PERSONAL, sourceId, UNKNOWN_SOURCE_ORDER);
    }

    public static CatalogCollectionOrder unknownResourcePack(String packId) {
        return new CatalogCollectionOrder(Kind.RESOURCE_PACK, packId, UNKNOWN_SOURCE_ORDER);
    }

    public static CatalogCollectionOrder vanilla(String sourceId) {
        return new CatalogCollectionOrder(Kind.VANILLA, sourceId, UNKNOWN_SOURCE_ORDER);
    }

    public static CatalogCollectionOrder unspecified() {
        return new CatalogCollectionOrder(Kind.UNSPECIFIED, "catalog", UNKNOWN_SOURCE_ORDER);
    }

    public boolean sourceOrderKnown() {
        return sourceOrder != UNKNOWN_SOURCE_ORDER;
    }
}
