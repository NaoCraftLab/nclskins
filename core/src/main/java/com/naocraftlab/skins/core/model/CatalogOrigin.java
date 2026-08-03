package com.naocraftlab.skins.core.model;

import java.util.Objects;
import java.util.Optional;


public record CatalogOrigin(
        String sourceId,
        String collectionId,
        String skinId,
        Optional<String> description,
        Optional<String> authors) {
    public CatalogOrigin {
        sourceId = requireId(sourceId, "sourceId");
        collectionId = requireId(collectionId, "collectionId");
        skinId = requireId(skinId, "skinId");
        description = normalize(description, "description", 1024);
        authors = normalize(authors, "authors", 256);
    }

    public CatalogOrigin(String sourceId, String collectionId, String skinId) {
        this(sourceId, collectionId, skinId, Optional.empty(), Optional.empty());
    }

    private static String requireId(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static Optional<String> normalize(Optional<String> value, String field, int maximum) {
        Objects.requireNonNull(value, field);
        return value.map(text -> {
            StringBuilder cleaned = new StringBuilder();
            boolean space = false;
            for (int offset = 0; offset < text.length()
                    && cleaned.codePointCount(0, cleaned.length()) < maximum; ) {
                int codePoint = text.codePointAt(offset);
                offset += Character.charCount(codePoint);
                if (Character.isISOControl(codePoint)
                        || Character.FORMAT == Character.getType(codePoint)) {
                    space = cleaned.length() > 0;
                    continue;
                }
                if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                    space = cleaned.length() > 0;
                    continue;
                }
                if (space) {
                    cleaned.append(' ');
                    space = false;
                }
                cleaned.appendCodePoint(codePoint);
            }
            return cleaned.toString().trim();
        }).filter(text -> !text.isEmpty());
    }
}
