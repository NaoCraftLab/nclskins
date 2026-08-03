package com.naocraftlab.skins.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


public record CatalogText(Optional<String> translationKey, String fallback) {
    public CatalogText {
        translationKey = Objects.requireNonNull(translationKey, "translationKey");
        translationKey.ifPresent(key -> requireNonBlank(key, "translationKey"));
        fallback = Objects.requireNonNull(fallback, "fallback");
    }

    public static CatalogText translated(String translationKey, String fallback) {
        return new CatalogText(Optional.of(translationKey), fallback);
    }

    public static CatalogText literal(String value) {
        return new CatalogText(Optional.empty(), Objects.requireNonNull(value, "value"));
    }

    public static CatalogText collectionName(String collectionId) {
        String id = requireStableId(collectionId, "collectionId");
        return translated("nclskins." + id + ".name", humanizeId(id));
    }

    public static CatalogText collectionDescription(String collectionId) {
        String id = requireStableId(collectionId, "collectionId");
        return translated("nclskins." + id + ".description", "");
    }

    public static CatalogText collectionAuthors(String collectionId) {
        String id = requireStableId(collectionId, "collectionId");
        return translated("nclskins." + id + ".authors", "");
    }

    public static CatalogText skinName(String collectionId, String skinId) {
        String collection = requireStableId(collectionId, "collectionId");
        String skin = requireStableId(skinId, "skinId");
        return translated(
                "nclskins." + collection + ".skin." + skin + ".name",
                humanizeId(skin));
    }

    public static CatalogText skinDescription(String collectionId, String skinId) {
        String collection = requireStableId(collectionId, "collectionId");
        String skin = requireStableId(skinId, "skinId");
        return translated(
                "nclskins." + collection + ".skin." + skin + ".description", "");
    }

    public static CatalogText skinAuthors(String collectionId, String skinId) {
        String collection = requireStableId(collectionId, "collectionId");
        String skin = requireStableId(skinId, "skinId");
        return translated(
                "nclskins." + collection + ".skin." + skin + ".authors", "");
    }


    public static String humanizeId(String id) {
        String stableId = requireStableId(id, "id");
        List<String> words = new ArrayList<>();
        for (String word : stableId.split("[_-]+")) {
            if (!word.isEmpty()) {
                words.add(Character.toUpperCase(word.charAt(0)) + word.substring(1));
            }
        }
        return String.join(" ", words);
    }

    private static String requireStableId(String value, String field) {
        requireNonBlank(value, field);
        if (!value.matches("[a-z0-9][a-z0-9_-]{0,127}")) {
            throw new IllegalArgumentException(field + " must be a stable catalog id");
        }
        return value;
    }

    private static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
