package com.naocraftlab.skins.client;

import java.io.IOException;
import java.util.List;
import java.util.Objects;


@FunctionalInterface
public interface BundledSkinSource extends SkinCatalogSource {
    byte[] load(SkinModel model) throws IOException;

    @Override
    default byte[] load(String collectionId, String skinId, SkinModel model) throws IOException {
        Objects.requireNonNull(collectionId, "collectionId");
        Objects.requireNonNull(skinId, "skinId");
        Objects.requireNonNull(model, "model");
        if (MinecraftSkinCatalog.COLLECTION_ID.equals(collectionId)
                && MinecraftSkinCatalog.STEVE_SKIN_ID.equals(skinId)
                && model == SkinModel.CLASSIC) {
            return classic();
        }
        if (MinecraftSkinCatalog.COLLECTION_ID.equals(collectionId)
                && MinecraftSkinCatalog.ALEX_SKIN_ID.equals(skinId)
                && model == SkinModel.SLIM) {
            return slim();
        }
        throw new IOException("Legacy bundled skin source does not provide the catalog variant");
    }

    @Override
    default List<CollectionDescriptor> collections() {
        return List.of();
    }

    default byte[] classic() throws IOException {
        return load(SkinModel.CLASSIC);
    }

    default byte[] slim() throws IOException {
        return load(SkinModel.SLIM);
    }


    static byte[] ownedCopy(byte[] pngBytes) {
        Objects.requireNonNull(pngBytes, "pngBytes");
        if (pngBytes.length == 0) {
            throw new IllegalArgumentException("Bundled skin PNG must not be empty");
        }
        return pngBytes.clone();
    }
}
