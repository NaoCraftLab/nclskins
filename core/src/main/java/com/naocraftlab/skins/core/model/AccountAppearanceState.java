package com.naocraftlab.skins.core.model;

import com.naocraftlab.skins.client.OuterLayerVisibility;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;


public record AccountAppearanceState(
        int schemaVersion,
        UUID accountId,
        long intentRevision,
        UUID activePresetId,
        String skinSha256,
        SkinVariant skinVariant,
        String capeId,
        OuterLayerVisibility outerLayerVisibility,
        AppearanceSyncStatus syncStatus,
        long settledRevision,
        Instant updatedAt) {
    public static final int CURRENT_SCHEMA_VERSION = 2;

    public AccountAppearanceState {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported appearance state schema: " + schemaVersion);
        }
        Objects.requireNonNull(accountId, "accountId");
        if (intentRevision < 0 || settledRevision < 0 || settledRevision > intentRevision) {
            throw new IllegalArgumentException("appearance revisions are invalid");
        }
        if ((skinSha256 == null) != (skinVariant == null)) {
            throw new IllegalArgumentException("skin hash and variant must be present together");
        }
        if (skinSha256 != null && !skinSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("skinSha256 is invalid");
        }
        capeId = normalizeCapeId(capeId);
        if (intentRevision == 0) {
            if (outerLayerVisibility != null) {
                throw new IllegalArgumentException("empty appearance state cannot contain outer-layer visibility");
            }
        } else {
            Objects.requireNonNull(outerLayerVisibility, "outerLayerVisibility");
        }
        Objects.requireNonNull(syncStatus, "syncStatus");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (intentRevision == 0) {
            if (activePresetId != null || skinSha256 != null || capeId != null || settledRevision != 0) {
                throw new IllegalArgumentException("empty appearance state cannot contain an intent");
            }
        } else if (syncStatus == AppearanceSyncStatus.OFFICIAL && settledRevision != intentRevision) {
            throw new IllegalArgumentException("official appearance must settle the current intent");
        }
    }


    public AccountAppearanceState(
            int schemaVersion,
            UUID accountId,
            long intentRevision,
            UUID activePresetId,
            String skinSha256,
            SkinVariant skinVariant,
            String capeId,
            AppearanceSyncStatus syncStatus,
            long settledRevision,
            Instant updatedAt) {
        this(
                schemaVersion,
                accountId,
                intentRevision,
                activePresetId,
                skinSha256,
                skinVariant,
                capeId,
                intentRevision == 0 ? null : OuterLayerVisibility.allVisible(),
                syncStatus,
                settledRevision,
                updatedAt);
    }

    public static AccountAppearanceState empty(UUID accountId, Instant now) {
        return new AccountAppearanceState(
                CURRENT_SCHEMA_VERSION,
                accountId,
                0,
                null,
                null,
                null,
                null,
                null,
                AppearanceSyncStatus.LOCAL_ONLY,
                0,
                now);
    }

    public Optional<UUID> optionalActivePresetId() {
        return Optional.ofNullable(activePresetId);
    }

    public Optional<String> optionalSkinSha256() {
        return Optional.ofNullable(skinSha256);
    }

    public Optional<SkinVariant> optionalSkinVariant() {
        return Optional.ofNullable(skinVariant);
    }

    public Optional<String> optionalCapeId() {
        return Optional.ofNullable(capeId);
    }

    public Optional<OuterLayerVisibility> optionalOuterLayerVisibility() {
        return Optional.ofNullable(outerLayerVisibility);
    }

    public boolean hasIntent() {
        return intentRevision > 0;
    }

    public boolean pendingOfficialSync() {
        return hasIntent()
                && settledRevision < intentRevision
                && syncStatus == AppearanceSyncStatus.PENDING;
    }

    private static String normalizeCapeId(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 256) {
            throw new IllegalArgumentException("capeId must contain between 1 and 256 characters");
        }
        return trimmed;
    }
}
