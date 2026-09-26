package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.model.CatalogOrigin;
import com.naocraftlab.skins.core.model.PersonalSkinSource;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.service.AppliedAppearance;
import com.naocraftlab.skins.core.service.SessionValidation;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.naocraftlab.skins.runtime.AccountReconciliationPort.DurableAppearance;
import com.naocraftlab.skins.runtime.ClientOperations.RemoteResult;
import com.naocraftlab.skins.runtime.CatalogMaterialization.FrozenCatalogSelection;

public interface LibraryEditorPort {
    Optional<AccountState> reloadEditorAccount(UUID accountId) throws Exception;

    Optional<AccountState> discardCapeIfUnreferenced(UUID accountId, UUID entryId)
            throws Exception;

    AccountState importSkin(String name, SkinVariant variant, byte[] normalizedPng) throws Exception;

    AccountState renameSkin(UUID skinId, String newName) throws Exception;

    AccountState changeSkinVariant(UUID skinId, SkinVariant variant) throws Exception;

    AccountState duplicateSkin(UUID skinId, String newName) throws Exception;

    AccountState deleteSkin(UUID skinId) throws Exception;

    AccountState removePersonalSkin(String sha256) throws Exception;

    AccountState renamePersonalSkin(String sha256, String newName) throws Exception;

    com.naocraftlab.skins.core.model.PersonalCapeEntry importCape(UUID accountId, java.nio.file.Path path, String fallbackName) throws Exception;

    AccountState renameCape(UUID accountId, UUID entryId, String name) throws Exception;

    CapeDeletion deleteCape(UUID accountId, UUID entryId) throws Exception;

    com.naocraftlab.skins.core.model.PersonalCapeEntry importCape(java.nio.file.Path path) throws Exception;

    AccountState renameCape(UUID entryId, String name) throws Exception;

    CapeDeletion deleteCape(UUID entryId) throws Exception;

    record CapeDeletion(AccountState account, DurableAppearance appearance) {}

    EditorSave saveEditor(EditorSaveRequest request) throws Exception;

    PresetDelete deletePreset(UUID presetId) throws Exception;

    RemoteResult applyPreset(UUID presetId) throws Exception;

    PresetUse usePreset(UUID presetId) throws Exception;

    record PresetUse(
            AccountState account,
            SessionValidation session,
            UUID activePresetId,
            Optional<AppliedAppearance> localAppearance,
            Optional<RemoteResult> remoteResult,
            boolean pendingOfficialSync,
            boolean durableSelection,
            Optional<OuterLayerVisibility> outerLayerVisibility,
            long intentRevision,
            AppearanceSyncStatus syncStatus,
            AppearanceProviders providers) {
        public PresetUse(
                AccountState account,
                SessionValidation session,
                UUID activePresetId,
                Optional<AppliedAppearance> localAppearance,
                Optional<RemoteResult> remoteResult,
                boolean pendingOfficialSync,
                boolean durableSelection,
                Optional<OuterLayerVisibility> outerLayerVisibility,
                long intentRevision,
                AppearanceSyncStatus syncStatus) {
            this(
                    account,
                    session,
                    activePresetId,
                    localAppearance,
                    remoteResult,
                    pendingOfficialSync,
                    durableSelection,
                    outerLayerVisibility,
                    intentRevision,
                    syncStatus,
                    AppearanceProviders.initial());
        }

        public PresetUse(
                AccountState account,
                SessionValidation session,
                UUID activePresetId,
                Optional<AppliedAppearance> localAppearance,
                Optional<RemoteResult> remoteResult,
                boolean pendingOfficialSync,
                boolean durableSelection) {
            this(account, session, activePresetId, localAppearance, remoteResult,
                    pendingOfficialSync, durableSelection, Optional.empty(), 0,
                    pendingOfficialSync ? AppearanceSyncStatus.PENDING : AppearanceSyncStatus.LOCAL_ONLY);
        }

        public PresetUse(
                AccountState account,
                SessionValidation session,
                UUID activePresetId,
                Optional<AppliedAppearance> localAppearance,
                Optional<RemoteResult> remoteResult,
                boolean pendingOfficialSync,
                boolean durableSelection,
                Optional<OuterLayerVisibility> outerLayerVisibility) {
            this(account, session, activePresetId, localAppearance, remoteResult,
                    pendingOfficialSync, durableSelection, outerLayerVisibility, 0,
                    pendingOfficialSync ? AppearanceSyncStatus.PENDING : AppearanceSyncStatus.LOCAL_ONLY);
        }

        public PresetUse {
            Objects.requireNonNull(providers, "providers");
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(activePresetId, "activePresetId");
            localAppearance = Objects.requireNonNull(localAppearance, "localAppearance");
            remoteResult = Objects.requireNonNull(remoteResult, "remoteResult");
            outerLayerVisibility = Objects.requireNonNull(outerLayerVisibility, "outerLayerVisibility");
            Objects.requireNonNull(syncStatus, "syncStatus");
            if (intentRevision < 0) {
                throw new IllegalArgumentException("intentRevision must not be negative");
            }
        }
    }

    record EditorSaveRequest(
            Optional<UUID> originalPresetId,
            String name,
            SkinReference skin,
            SkinVariant initialVariant,
            SkinVariant variant,
            Optional<String> capeId,
            OuterLayerVisibility outerLayerVisibility,
            Optional<byte[]> pngBytes,
            Optional<CatalogOrigin> catalogOrigin,
            Optional<String> personalSkinName,
            PersonalSkinSource personalSkinSource,
            com.naocraftlab.skins.core.model.LocalCapeReference offlineCape,
            Optional<FrozenCatalogSelection> frozenCatalogSelection) {
        public EditorSaveRequest(Optional<UUID> originalPresetId, String name, SkinReference skin,
                SkinVariant initialVariant, SkinVariant variant, Optional<String> capeId,
                OuterLayerVisibility outerLayerVisibility, Optional<byte[]> pngBytes,
                Optional<CatalogOrigin> catalogOrigin, Optional<String> personalSkinName,
                PersonalSkinSource personalSkinSource, com.naocraftlab.skins.core.model.LocalCapeReference offlineCape) {
            this(originalPresetId, name, skin, initialVariant, variant, capeId, outerLayerVisibility,
                    pngBytes, catalogOrigin, personalSkinName, personalSkinSource, offlineCape, Optional.empty());
        }

        public EditorSaveRequest withFrozenCatalogSelection(Optional<FrozenCatalogSelection> frozen) {
            return new EditorSaveRequest(originalPresetId, name, skin, initialVariant, variant, capeId,
                    outerLayerVisibility, pngBytes, catalogOrigin, personalSkinName, personalSkinSource, offlineCape, frozen);
        }

        public EditorSaveRequest(Optional<UUID> originalPresetId, String name, SkinReference skin,
                SkinVariant initialVariant, SkinVariant variant, Optional<String> capeId,
                OuterLayerVisibility outerLayerVisibility, Optional<byte[]> pngBytes,
                Optional<CatalogOrigin> catalogOrigin, Optional<String> personalSkinName,
                PersonalSkinSource personalSkinSource) {
            this(originalPresetId, name, skin, initialVariant, variant, capeId, outerLayerVisibility,
                    pngBytes, catalogOrigin, personalSkinName, personalSkinSource, null);
        }

        public EditorSaveRequest withOfflineCape(com.naocraftlab.skins.core.model.LocalCapeReference value) {
            return new EditorSaveRequest(originalPresetId, name, skin, initialVariant, variant, capeId,
                    outerLayerVisibility, pngBytes, catalogOrigin, personalSkinName, personalSkinSource, value, frozenCatalogSelection);
        }

        public EditorSaveRequest(
                Optional<UUID> originalPresetId,
                String name,
                SkinReference skin,
                SkinVariant initialVariant,
                SkinVariant variant,
                Optional<String> capeId,
                Optional<byte[]> pngBytes) {
            this(
                    originalPresetId,
                    name,
                    skin,
                    initialVariant,
                    variant,
                    capeId,
                    OuterLayerVisibility.allVisible(),
                    pngBytes,
                    Optional.empty(),
                    Optional.empty(),
                    PersonalSkinSource.FILE);
        }

        public EditorSaveRequest(
                Optional<UUID> originalPresetId,
                String name,
                SkinReference skin,
                SkinVariant initialVariant,
                SkinVariant variant,
                Optional<String> capeId,
                Optional<byte[]> pngBytes,
                Optional<CatalogOrigin> catalogOrigin) {
            this(
                    originalPresetId,
                    name,
                    skin,
                    initialVariant,
                    variant,
                    capeId,
                    OuterLayerVisibility.allVisible(),
                    pngBytes,
                    catalogOrigin,
                    Optional.empty(),
                    PersonalSkinSource.FILE);
        }

        public EditorSaveRequest(
                Optional<UUID> originalPresetId,
                String name,
                SkinReference skin,
                SkinVariant initialVariant,
                SkinVariant variant,
                Optional<String> capeId,
                Optional<byte[]> pngBytes,
                Optional<CatalogOrigin> catalogOrigin,
                Optional<String> personalSkinName) {
            this(originalPresetId, name, skin, initialVariant, variant, capeId,
                    OuterLayerVisibility.allVisible(), pngBytes,
                    catalogOrigin, personalSkinName, PersonalSkinSource.FILE);
        }

        public EditorSaveRequest(
                Optional<UUID> originalPresetId,
                String name,
                SkinReference skin,
                SkinVariant initialVariant,
                SkinVariant variant,
                Optional<String> capeId,
                OuterLayerVisibility outerLayerVisibility,
                Optional<byte[]> pngBytes,
                Optional<CatalogOrigin> catalogOrigin,
                Optional<String> personalSkinName) {
            this(originalPresetId, name, skin, initialVariant, variant, capeId,
                    outerLayerVisibility, pngBytes, catalogOrigin, personalSkinName,
                    PersonalSkinSource.FILE);
        }

        public EditorSaveRequest {
            frozenCatalogSelection = Objects.requireNonNull(frozenCatalogSelection, "frozenCatalogSelection");
            originalPresetId = Objects.requireNonNull(originalPresetId, "originalPresetId");
            Objects.requireNonNull(name, "name");
            name = name.trim();
            if (name.isEmpty() || name.length() > 128) {
                throw new IllegalArgumentException("name must contain between 1 and 128 characters");
            }
            Objects.requireNonNull(skin, "skin");
            Objects.requireNonNull(initialVariant, "initialVariant");
            Objects.requireNonNull(variant, "variant");
            capeId = Objects.requireNonNull(capeId, "capeId");
            Objects.requireNonNull(outerLayerVisibility, "outerLayerVisibility");
            pngBytes = Objects.requireNonNull(pngBytes, "pngBytes").map(byte[]::clone);
            catalogOrigin = Objects.requireNonNull(catalogOrigin, "catalogOrigin");
            personalSkinName = Objects.requireNonNull(personalSkinName, "personalSkinName")
                    .map(String::trim);
            Objects.requireNonNull(personalSkinSource, "personalSkinSource");
            if (catalogOrigin.isPresent() && pngBytes.isEmpty()) {
                throw new IllegalArgumentException("catalog origin requires copied PNG bytes");
            }
            if (personalSkinName.filter(String::isEmpty).isPresent()
                    || personalSkinName.filter(value -> value.length() > 128).isPresent()) {
                throw new IllegalArgumentException(
                        "personal skin name must contain between 1 and 128 characters");
            }
            if (personalSkinName.isPresent() && pngBytes.isEmpty()) {
                throw new IllegalArgumentException("personal skin name requires copied PNG bytes");
            }
            if (personalSkinName.isPresent() && catalogOrigin.isPresent()) {
                throw new IllegalArgumentException("personal and external catalog origins are exclusive");
            }
        }

        @Override
        public Optional<byte[]> pngBytes() {
            return pngBytes.map(byte[]::clone);
        }
    }

    record EditorSave(
            AccountState account,
            UUID presetId,
            Optional<DurableAppearance> reappliedAppearance) {
        public EditorSave(AccountState account, UUID presetId) {
            this(account, presetId, Optional.empty());
        }

        public EditorSave {
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(presetId, "presetId");
            reappliedAppearance = Objects.requireNonNull(
                    reappliedAppearance, "reappliedAppearance");
            reappliedAppearance.ifPresent(appearance -> {
                if (!account.accountId().equals(appearance.accountId())) {
                    throw new IllegalArgumentException(
                            "Reapplied editor appearance belongs to another account");
                }
                if (appearance.activePresetId().filter(presetId::equals).isEmpty()) {
                    throw new IllegalArgumentException(
                            "Reapplied editor appearance does not select the saved preset");
                }
            });
        }
    }

    record PresetDelete(
            AccountState account,
            Optional<RemoteResult> remoteReset,
            List<String> cleanupWarnings,
            Optional<DurableAppearance> appearance) {
        public PresetDelete(
                AccountState account,
                Optional<RemoteResult> remoteReset,
                List<String> cleanupWarnings) {
            this(account, remoteReset, cleanupWarnings, Optional.empty());
        }

        public PresetDelete {
            Objects.requireNonNull(account, "account");
            remoteReset = Objects.requireNonNull(remoteReset, "remoteReset");
            cleanupWarnings = List.copyOf(Objects.requireNonNull(cleanupWarnings, "cleanupWarnings"));
            appearance = Objects.requireNonNull(appearance, "appearance");
            remoteReset.ifPresent(result -> {
                if (!result.account().equals(account)) {
                    throw new IllegalArgumentException("remote reset and delete account states differ");
                }
            });
        }

        public static PresetDelete local(AccountState account) {
            return new PresetDelete(account, Optional.empty(), List.of(), Optional.empty());
        }

        public static PresetDelete local(AccountState account, DurableAppearance appearance) {
            return new PresetDelete(
                    account, Optional.empty(), List.of(), Optional.of(appearance));
        }

        public static PresetDelete withRemoteReset(RemoteResult result) {
            return withRemoteReset(result, List.of());
        }

        public static PresetDelete withRemoteReset(
                RemoteResult result, List<String> cleanupWarnings) {
            Objects.requireNonNull(result, "result");
            return new PresetDelete(
                    result.account(), Optional.of(result), cleanupWarnings, Optional.empty());
        }
    }
}
