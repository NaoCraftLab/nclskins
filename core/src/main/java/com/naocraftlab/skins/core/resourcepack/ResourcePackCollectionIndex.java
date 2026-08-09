package com.naocraftlab.skins.core.resourcepack;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.naocraftlab.skins.client.CatalogText;
import com.naocraftlab.skins.client.MinecraftSkinCatalog;
import com.naocraftlab.skins.client.PersonalSkinCatalog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;


public final class ResourcePackCollectionIndex {
    public static final int MAX_BYTES = 64 * 1024;
    public static final int MAX_COLLECTIONS = 1024;

    private ResourcePackCollectionIndex() {
    }


    public static List<String> read(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        byte[] bytes = input.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) {
            throw new IOException("Resource-pack collection index is too large");
        }
        if (bytes.length == 0) {
            throw new IOException("Resource-pack collection index is empty");
        }

        try {
            JsonElement root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!root.isJsonArray()) {
                throw new IOException("Resource-pack collection index must be a JSON array");
            }
            if (root.getAsJsonArray().size() > MAX_COLLECTIONS) {
                throw new IOException("Resource-pack collection index has too many collections");
            }

            LinkedHashSet<String> collections = new LinkedHashSet<>();
            for (JsonElement element : root.getAsJsonArray()) {
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                    throw new IOException("Resource-pack collection IDs must be strings");
                }
                String collectionId = element.getAsString();
                try {
                    CatalogText.collectionName(collectionId);
                } catch (IllegalArgumentException invalid) {
                    throw new IOException("Invalid resource-pack collection ID: " + collectionId, invalid);
                }
                if (MinecraftSkinCatalog.COLLECTION_ID.equals(collectionId)
                        || PersonalSkinCatalog.isCollection(collectionId)) {
                    throw new IOException("Reserved resource-pack collection ID: " + collectionId);
                }
                if (!collections.add(collectionId)) {
                    throw new IOException("Duplicate resource-pack collection ID: " + collectionId);
                }
            }
            return List.copyOf(collections);
        } catch (IOException failure) {
            throw failure;
        } catch (RuntimeException malformed) {
            throw new IOException("Resource-pack collection index is malformed", malformed);
        }
    }
}
