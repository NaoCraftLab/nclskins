package com.naocraftlab.skins.core.model;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;


public record PersonalSkinEntry(
        String sha256,
        String displayName,
        PersonalSkinSource source,
        Instant addedAt,
        Instant updatedAt,
        Map<SkinVariant, UUID> variantAssetIds,
        boolean visible) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public PersonalSkinEntry {
        sha256 = Objects.requireNonNull(sha256, "sha256");
        if (!SHA_256.matcher(sha256).matches()) {
            throw new IllegalArgumentException(
                    "sha256 must contain 64 lowercase hexadecimal characters");
        }
        displayName = requireDisplayName(displayName);
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(addedAt, "addedAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(addedAt)) {
            throw new IllegalArgumentException("updatedAt cannot precede addedAt");
        }
        Objects.requireNonNull(variantAssetIds, "variantAssetIds");
        EnumMap<SkinVariant, UUID> copiedVariants = new EnumMap<>(SkinVariant.class);
        variantAssetIds.forEach((variant, assetId) -> copiedVariants.put(
                Objects.requireNonNull(variant, "variant"),
                Objects.requireNonNull(assetId, "assetId")));
        if (copiedVariants.isEmpty()) {
            throw new IllegalArgumentException("personal skin must reference at least one variant");
        }
        variantAssetIds = Map.copyOf(copiedVariants);
    }

    public Optional<UUID> optionalAssetId(SkinVariant variant) {
        return Optional.ofNullable(variantAssetIds.get(Objects.requireNonNull(variant, "variant")));
    }


    public PersonalSkinEntry withVariant(SkinVariant variant, UUID assetId, Instant now) {
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(assetId, "assetId");
        Objects.requireNonNull(now, "now");
        UUID existing = variantAssetIds.get(variant);
        if (assetId.equals(existing)) {
            return this;
        }
        EnumMap<SkinVariant, UUID> variants = new EnumMap<>(SkinVariant.class);
        variants.putAll(variantAssetIds);
        variants.put(variant, assetId);
        return new PersonalSkinEntry(
                sha256, displayName, source, addedAt, now, variants, visible);
    }


    public PersonalSkinEntry hidden(Instant now) {
        Objects.requireNonNull(now, "now");
        if (!visible) {
            return this;
        }
        return new PersonalSkinEntry(
                sha256, displayName, source, addedAt, now, variantAssetIds, false);
    }


    public PersonalSkinEntry restored(
            String newDisplayName,
            PersonalSkinSource newSource,
            SkinVariant variant,
            UUID assetId,
            Instant now) {
        EnumMap<SkinVariant, UUID> variants = new EnumMap<>(SkinVariant.class);
        variants.putAll(variantAssetIds);
        variants.put(Objects.requireNonNull(variant, "variant"), Objects.requireNonNull(assetId, "assetId"));
        return new PersonalSkinEntry(
                sha256,
                newDisplayName,
                Objects.requireNonNull(newSource, "newSource"),
                now,
                now,
                variants,
                true);
    }


    public PersonalSkinEntry renamed(String newDisplayName, Instant now) {
        Objects.requireNonNull(now, "now");
        return new PersonalSkinEntry(
                sha256,
                newDisplayName,
                source,
                addedAt,
                now,
                variantAssetIds,
                visible);
    }

    private static String requireDisplayName(String value) {
        Objects.requireNonNull(value, "displayName");
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 128) {
            throw new IllegalArgumentException(
                    "displayName must contain between 1 and 128 characters");
        }
        return trimmed;
    }
}
