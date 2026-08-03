package com.naocraftlab.skins.core.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;


public record AccountState(
        int schemaVersion,
        UUID accountId,
        List<SkinAsset> skinAssets,
        List<PersonalSkinEntry> personalSkins,
        List<AppearancePreset> presets,
        Instant updatedAt) {
    public static final int CURRENT_SCHEMA_VERSION = 4;

    public AccountState {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported account state schema: " + schemaVersion);
        }
        Objects.requireNonNull(accountId, "accountId");
        skinAssets = List.copyOf(Objects.requireNonNull(skinAssets, "skinAssets"));
        personalSkins = List.copyOf(Objects.requireNonNull(personalSkins, "personalSkins"));
        presets = List.copyOf(Objects.requireNonNull(presets, "presets"));
        Objects.requireNonNull(updatedAt, "updatedAt");
        ensureUniqueIds(skinAssets, presets);
        Map<UUID, SkinAsset> assetsById = new HashMap<>();
        skinAssets.forEach(asset -> assetsById.put(asset.id(), asset));
        for (AppearancePreset preset : presets) {
            preset.skin().optionalAssetId().ifPresent(assetId -> {
                if (!assetsById.containsKey(assetId)) {
                    throw new IllegalArgumentException("preset references missing skin asset");
                }
            });
        }
        Set<String> personalHashes = new HashSet<>();
        for (PersonalSkinEntry personalSkin : personalSkins) {
            if (!personalHashes.add(personalSkin.sha256())) {
                throw new IllegalArgumentException("duplicate personal skin hash");
            }
            personalSkin.variantAssetIds().forEach((variant, assetId) -> {
                SkinAsset asset = assetsById.get(assetId);
                if (asset == null) {
                    throw new IllegalArgumentException(
                            "personal skin references missing skin asset");
                }
                if (!asset.sha256().equals(personalSkin.sha256())) {
                    throw new IllegalArgumentException(
                            "personal skin asset hash does not match its entry");
                }
                if (asset.variant() != variant) {
                    throw new IllegalArgumentException(
                            "personal skin asset variant does not match its entry");
                }
            });
        }
    }


    public AccountState(
            int schemaVersion,
            UUID accountId,
            List<SkinAsset> skinAssets,
            List<AppearancePreset> presets,
            Instant updatedAt) {
        this(schemaVersion, accountId, skinAssets, List.of(), presets, updatedAt);
    }

    public static AccountState empty(UUID accountId, Instant now) {
        return new AccountState(
                CURRENT_SCHEMA_VERSION, accountId, List.of(), List.of(), List.of(), now);
    }

    private static void ensureUniqueIds(List<SkinAsset> assets, List<AppearancePreset> presets) {
        Set<UUID> assetIds = new HashSet<>();
        if (assets.stream().anyMatch(asset -> !assetIds.add(asset.id()))) {
            throw new IllegalArgumentException("duplicate skin asset id");
        }
        Set<UUID> presetIds = new HashSet<>();
        if (presets.stream().anyMatch(preset -> !presetIds.add(preset.id()))) {
            throw new IllegalArgumentException("duplicate preset id");
        }
    }
}
