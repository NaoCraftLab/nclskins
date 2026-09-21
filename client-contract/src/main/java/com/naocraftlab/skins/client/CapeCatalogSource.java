package com.naocraftlab.skins.client;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public interface CapeCatalogSource {
    String CAPE_TEXTURE_ROOT = "textures/entity/cape";
    String PERSONAL_COLLECTION_ID = "personal";
    String MINECRAFT_COLLECTION_ID = "minecraft";

    enum RenderSupport {
        CAPE_ONLY,
        CAPE_AND_ELYTRA
    }

    record CapeDescriptor(
            String id,
            CatalogText nameText,
            Optional<CatalogText> descriptionText,
            Optional<CatalogText> authorsText,
            String contentIdentity,
            RenderSupport renderSupport) {
        public CapeDescriptor {
            id = stableId(id, "id");
            nameText = Objects.requireNonNull(nameText, "nameText");
            descriptionText = Objects.requireNonNull(descriptionText, "descriptionText");
            authorsText = Objects.requireNonNull(authorsText, "authorsText");
            contentIdentity = Objects.requireNonNull(contentIdentity, "contentIdentity");
            if (contentIdentity.isBlank() || contentIdentity.length() > 256) {
                throw new IllegalArgumentException("contentIdentity is invalid");
            }
            renderSupport = Objects.requireNonNull(renderSupport, "renderSupport");
        }
    }

    record CollectionDescriptor(
            String id,
            CatalogText nameText,
            Optional<CatalogText> descriptionText,
            Optional<CatalogText> authorsText,
            List<CapeDescriptor> capes,
            CatalogCollectionOrder order) {
        public CollectionDescriptor {
            id = stableId(id, "id");
            nameText = Objects.requireNonNull(nameText, "nameText");
            descriptionText = Objects.requireNonNull(descriptionText, "descriptionText");
            authorsText = Objects.requireNonNull(authorsText, "authorsText");
            capes = List.copyOf(Objects.requireNonNull(capes, "capes"));
            order = Objects.requireNonNull(order, "order");
            Set<String> ids = new HashSet<>();
            for (CapeDescriptor cape : capes) {
                if (!ids.add(Objects.requireNonNull(cape, "capes contains null").id())) {
                    throw new IllegalArgumentException("Duplicate catalog cape id: " + cape.id());
                }
            }
        }
    }

    static String texturePath(String capeId) {
        return CAPE_TEXTURE_ROOT + "/" + stableId(capeId, "capeId") + ".png";
    }

    private static String stableId(String value, String field) {
        Objects.requireNonNull(value, field);
        if (!value.matches("[a-z0-9][a-z0-9_-]{0,127}")) {
            throw new IllegalArgumentException(field + " must be a stable catalog id");
        }
        return value;
    }

    byte[] loadCape(String collectionId, String capeId) throws IOException;

    List<CollectionDescriptor> capeCollections();

    long capeGeneration();
}
