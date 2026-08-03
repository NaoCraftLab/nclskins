package com.naocraftlab.skins.core.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;


public record SkinAsset(
        UUID id,
        String name,
        String sha256,
        SkinVariant variant,
        SkinSource source,
        Instant createdAt,
        Instant updatedAt,
        Optional<CatalogOrigin> catalogOrigin) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public SkinAsset {
        Objects.requireNonNull(id, "id");
        name = requireName(name);
        sha256 = Objects.requireNonNull(sha256, "sha256");
        if (!SHA_256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("sha256 must contain 64 lowercase hexadecimal characters");
        }
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        catalogOrigin = Objects.requireNonNull(catalogOrigin, "catalogOrigin");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt cannot precede createdAt");
        }
    }


    public SkinAsset(
            UUID id,
            String name,
            String sha256,
            SkinVariant variant,
            SkinSource source,
            Instant createdAt,
            Instant updatedAt) {
        this(id, name, sha256, variant, source, createdAt, updatedAt, Optional.empty());
    }

    public SkinAsset renamed(String newName, Instant now) {
        return new SkinAsset(id, newName, sha256, variant, source, createdAt, now, catalogOrigin);
    }

    public SkinAsset withVariant(SkinVariant newVariant, Instant now) {
        return new SkinAsset(id, name, sha256, newVariant, source, createdAt, now, catalogOrigin);
    }

    public SkinAsset duplicate(UUID newId, String newName, Instant now) {
        return new SkinAsset(
                newId,
                newName,
                sha256,
                variant,
                SkinSource.DUPLICATED,
                now,
                now,
                catalogOrigin);
    }

    private static String requireName(String value) {
        Objects.requireNonNull(value, "name");
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 128) {
            throw new IllegalArgumentException("name must contain between 1 and 128 characters");
        }
        return trimmed;
    }
}
