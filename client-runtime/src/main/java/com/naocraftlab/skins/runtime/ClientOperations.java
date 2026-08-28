package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.core.compatibility.SkinFeatureEvidence;
import com.naocraftlab.skins.core.importing.ExternalImportProbe;
import com.naocraftlab.skins.core.importing.ExternalImportSource;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AccountUiPreferences;
import com.naocraftlab.skins.core.model.AddSourceTab;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.model.CatalogOrigin;
import com.naocraftlab.skins.core.model.OwnedCapeInventory;
import com.naocraftlab.skins.core.model.PersonalSkinSource;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.service.AppliedAppearance;
import com.naocraftlab.skins.core.service.PresetApplicationOutcome;
import com.naocraftlab.skins.core.service.SessionValidation;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;


public interface ClientOperations extends AutoCloseable {

    default void verifyStorageAccess() throws Exception {}


    default void warmSession() throws Exception {}


    default Optional<OuterLayerVisibility> warmedOuterLayerVisibility() {
        return Optional.empty();
    }


    default AppearanceSyncStatus warmedAppearanceSyncStatus() {
        return AppearanceSyncStatus.LOCAL_ONLY;
    }


    default boolean warmedReconciliationRecommended() {
        return false;
    }


    default Optional<DurableAppearance> warmedDurableAppearance() {
        return Optional.empty();
    }


    default Optional<InitialData> warmedInitialData() {
        return Optional.empty();
    }


    default boolean reconciliationRecommended(InitialData data) {
        Objects.requireNonNull(data, "data");
        return data.syncStatus() == AppearanceSyncStatus.PENDING
                || data.syncStatus() == AppearanceSyncStatus.ATTEMPTING;
    }

    InitialData initialize() throws Exception;

    default InitialData initializeForGallery() throws Exception {
        return initialize();
    }


    default List<SkinCatalogSource.CollectionDescriptor> catalogCollections() throws Exception {
        return List.of();
    }


    default Map<CatalogVariant, SkinFeatureEvidence> catalogFeatureEvidence() {
        return Map.of();
    }


    default Map<UUID, SkinFeatureEvidence> assetFeatureEvidence() throws Exception {
        return Map.of();
    }


    default boolean supportsAssetFeatureEvidence() {
        return false;
    }


    default byte[] loadCatalogSkin(String collectionId, String skinId, SkinModel model)
            throws Exception {
        throw new UnsupportedOperationException("Skin catalog is unavailable");
    }


    default Optional<UUID> reusableCatalogSkinAsset(
            String collectionId, String skinId, SkinModel model) throws Exception {
        Objects.requireNonNull(collectionId, "collectionId");
        Objects.requireNonNull(skinId, "skinId");
        Objects.requireNonNull(model, "model");
        return Optional.empty();
    }


    record CatalogVariant(String collectionId, String skinId, SkinVariant variant) {
        public CatalogVariant {
            Objects.requireNonNull(collectionId, "collectionId");
            Objects.requireNonNull(skinId, "skinId");
            Objects.requireNonNull(variant, "variant");
        }
    }


    default Optional<AccountUiPreferences> loadUiPreferences() throws Exception {
        return Optional.empty();
    }


    default void setSelectedAddSourceTab(AddSourceTab tab) throws Exception {
        Objects.requireNonNull(tab, "tab");
    }


    default void setCollectionCollapsed(String collectionId, boolean collapsed) throws Exception {
        Objects.requireNonNull(collectionId, "collectionId");
    }


    default void replaceCollapsedCollectionIds(Set<String> collectionIds) throws Exception {
        Objects.requireNonNull(collectionIds, "collectionIds");
        for (String collectionId : collectionIds) {
            if (Objects.requireNonNull(collectionId, "collectionIds contains null").isBlank()) {
                throw new IllegalArgumentException("collectionId must not be blank");
            }
        }
    }


    default void setPreferredSkinVariant(SkinVariant variant) throws Exception {
        Objects.requireNonNull(variant, "variant");
    }

    AccountState importSkin(String name, SkinVariant variant, byte[] normalizedPng) throws Exception;

    default ImportDraft loadPlayerSkin(String playerNameOrUuid) throws Exception {
        throw new UnsupportedOperationException("Public player skin lookup is unavailable");
    }

    default ImportDraft loadUrlSkin(String url) throws Exception {
        throw new UnsupportedOperationException("Remote PNG import is unavailable");
    }

    default ExternalImportProbe probeExternalSource(
            ExternalImportSource source, Optional<Path> selectedRoot) throws Exception {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(selectedRoot, "selectedRoot");
        return ExternalImportProbe.UNAVAILABLE;
    }

    default ExternalImportReview prepareExternalAppearances(
            ExternalImportSource source, Optional<Path> selectedRoot) throws Exception {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(selectedRoot, "selectedRoot");
        throw new UnsupportedOperationException("External appearance preparation is unavailable");
    }

    default ExternalImportResult commitExternalAppearances(
            List<ExternalImportCandidate> selected, int skipped, int warnings) throws Exception {
        Objects.requireNonNull(selected, "selected");
        throw new UnsupportedOperationException("External appearance import is unavailable");
    }

    AccountState renameSkin(UUID skinId, String newName) throws Exception;

    AccountState changeSkinVariant(UUID skinId, SkinVariant variant) throws Exception;

    AccountState duplicateSkin(UUID skinId, String newName) throws Exception;

    AccountState deleteSkin(UUID skinId) throws Exception;


    default AccountState removePersonalSkin(String sha256) throws Exception {
        throw new UnsupportedOperationException("Personal skin catalog is unavailable");
    }


    default AccountState renamePersonalSkin(String sha256, String newName) throws Exception {
        throw new UnsupportedOperationException("Personal skin catalog is unavailable");
    }


    default Optional<OwnedCapeInventory> ownedCapeInventory() throws Exception {
        return Optional.empty();
    }


    default void warmOwnedCapeCache() throws Exception {}

    record ExternalImportResult(
            AccountState account,
            int imported,
            int alreadyPresent,
            int skipped,
            int warnings) {
        public ExternalImportResult {
            Objects.requireNonNull(account, "account");
            if (imported < 0 || alreadyPresent < 0 || skipped < 0 || warnings < 0) {
                throw new IllegalArgumentException("external import counters must not be negative");
            }
        }
    }

    record ExternalImportReview(
            ExternalImportSource source,
            List<ExternalImportCandidate> candidates,
            int skipped,
            int warnings) {
        public ExternalImportReview {
            Objects.requireNonNull(source, "source");
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException("external import review must not be empty");
            }
            if (candidates.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("external import review contains null");
            }
            if (candidates.stream().map(ExternalImportCandidate::id).distinct().count()
                    != candidates.size()) {
                throw new IllegalArgumentException("external import candidate ids must be unique");
            }
            if (skipped < 0 || warnings < 0) {
                throw new IllegalArgumentException("external import counters must not be negative");
            }
        }
    }

    record ExternalImportCandidate(
            String id,
            String displayName,
            SkinVariant variant,
            PersonalSkinSource source,
            byte[] normalizedPng,
            String sha256,
            String capeId,
            int sourceOrder,
            boolean duplicate,
            SkinFeatureEvidence featureEvidence) {
        public ExternalImportCandidate {
            id = Objects.requireNonNull(id, "id");
            displayName = Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(variant, "variant");
            Objects.requireNonNull(source, "source");
            normalizedPng = Objects.requireNonNull(normalizedPng, "normalizedPng").clone();
            sha256 = Objects.requireNonNull(sha256, "sha256");
            featureEvidence = Objects.requireNonNull(featureEvidence, "featureEvidence");
            if (!id.matches("[a-z0-9][a-z0-9_-]{0,127}")) {
                throw new IllegalArgumentException("external import candidate id is invalid");
            }
            if (displayName.isBlank() || displayName.length() > 128) {
                throw new IllegalArgumentException("external import display name is invalid");
            }
            if (normalizedPng.length == 0) {
                throw new IllegalArgumentException("external import PNG must not be empty");
            }
            if (!sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("external import SHA-256 is invalid");
            }
            if (capeId != null && (capeId.isBlank() || capeId.length() > 256)) {
                throw new IllegalArgumentException("external import cape id is invalid");
            }
            if (sourceOrder < 0) {
                throw new IllegalArgumentException("external import source order must not be negative");
            }
        }

        public ExternalImportCandidate(
                String id,
                String displayName,
                SkinVariant variant,
                PersonalSkinSource source,
                byte[] normalizedPng,
                String sha256,
                String capeId,
                int sourceOrder,
                boolean duplicate) {
            this(
                    id,
                    displayName,
                    variant,
                    source,
                    normalizedPng,
                    sha256,
                    capeId,
                    sourceOrder,
                    duplicate,
                    SkinFeatureEvidence.ORDINARY);
        }

        @Override
        public byte[] normalizedPng() {
            return normalizedPng.clone();
        }
    }

    InitialData resetLibrary() throws Exception;

    EditorSave saveEditor(EditorSaveRequest request) throws Exception;

    PresetDelete deletePreset(UUID presetId) throws Exception;

    RemoteResult applyPreset(UUID presetId) throws Exception;


    PresetUse usePreset(UUID presetId) throws Exception;


    default Optional<ReconciliationResult> reconcileAppearance(ReconciliationTrigger trigger)
            throws Exception {
        Objects.requireNonNull(trigger, "trigger");
        return Optional.empty();
    }


    default Optional<ReconciliationResult> reconcileAppearance(
            ReconciliationKey expected, ReconciliationTrigger trigger) throws Exception {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(trigger, "trigger");
        if (!reconciliationKey().filter(expected::equals).isPresent()) {
            return Optional.empty();
        }
        return reconcileAppearance(trigger);
    }


    default Optional<ReconciliationKey> reconciliationKey() throws Exception {
        UUID currentAccountId = sessionIdentity().profileId();
        Optional<DurableAppearance> durable = durableAppearance();
        if (durable.isPresent()
                && !durable.orElseThrow().accountId().equals(currentAccountId)) {
            return Optional.empty();
        }
        return Optional.of(durable
                .map(DurableAppearance::reconciliationKey)
                .orElseGet(() -> new ReconciliationKey(currentAccountId, 0)));
    }


    RemoteResult retryCape(String capeId) throws Exception;


    RemoteResult restorePreviousAppearance(PresetApplicationOutcome outcome) throws Exception;

    byte[] loadSkinPreview(UUID skinId) throws Exception;

    Optional<byte[]> loadCapePreview(String capeId) throws Exception;

    InitialData retrySession() throws Exception;

    boolean rateLimited();

    default Optional<Duration> rateLimitRemaining() {
        return rateLimited()
                ? Optional.of(Duration.ofSeconds(1))
                : Optional.empty();
    }

    GameSessionTokenSource.SessionIdentity sessionIdentity();

    default Optional<AppliedAppearance> acknowledgedAppearance() {
        return Optional.empty();
    }


    default Optional<DurableAppearance> durableAppearance() throws Exception {
        return Optional.empty();
    }


    default void rememberActivePreset(Optional<UUID> presetId) {
        rememberActivePreset(sessionIdentity().profileId(), presetId);
    }


    default void rememberActivePreset(UUID accountId, Optional<UUID> presetId) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(presetId, "presetId");
    }

    @Override
    default void close() {}

    record InitialData(
            AccountState account,
            SessionValidation session,
            Optional<UUID> currentOfficialSkinId,
            Optional<UUID> activePresetId,
            Optional<AppliedAppearance> localAppearance,
            boolean pendingOfficialSync,
            List<String> storageWarnings,
            AccountUiPreferences uiPreferences,
            Optional<OuterLayerVisibility> outerLayerVisibility,
            OwnedCapeInventory ownedCapes,
            long intentRevision,
            AppearanceSyncStatus syncStatus) {
        public InitialData(
                AccountState account,
                SessionValidation session,
                Optional<UUID> currentOfficialSkinId,
                Optional<UUID> activePresetId,
                Optional<AppliedAppearance> localAppearance,
                boolean pendingOfficialSync,
                List<String> storageWarnings) {
            this(
                    account,
                    session,
                    currentOfficialSkinId,
                    activePresetId,
                    localAppearance,
                    pendingOfficialSync,
                    storageWarnings,
                    AccountUiPreferences.defaults(account.accountId()),
                    Optional.empty(),
                    OwnedCapeInventory.empty(account.accountId(), java.time.Instant.EPOCH),
                    0,
                    pendingOfficialSync ? AppearanceSyncStatus.PENDING : AppearanceSyncStatus.LOCAL_ONLY);
        }

        public InitialData(
                AccountState account,
                SessionValidation session,
                Optional<UUID> currentOfficialSkinId,
                Optional<UUID> activePresetId,
                Optional<AppliedAppearance> localAppearance,
                boolean pendingOfficialSync,
                List<String> storageWarnings,
                AccountUiPreferences uiPreferences) {
            this(account, session, currentOfficialSkinId, activePresetId, localAppearance,
                    pendingOfficialSync, storageWarnings, uiPreferences, Optional.empty(),
                    OwnedCapeInventory.empty(account.accountId(), java.time.Instant.EPOCH),
                    0,
                    pendingOfficialSync ? AppearanceSyncStatus.PENDING : AppearanceSyncStatus.LOCAL_ONLY);
        }

        public InitialData(
                AccountState account,
                SessionValidation session,
                Optional<UUID> currentOfficialSkinId,
                Optional<UUID> activePresetId,
                Optional<AppliedAppearance> localAppearance,
                boolean pendingOfficialSync,
                List<String> storageWarnings,
                AccountUiPreferences uiPreferences,
                Optional<OuterLayerVisibility> outerLayerVisibility) {
            this(account, session, currentOfficialSkinId, activePresetId, localAppearance,
                    pendingOfficialSync, storageWarnings, uiPreferences, outerLayerVisibility,
                    OwnedCapeInventory.empty(account.accountId(), java.time.Instant.EPOCH),
                    0,
                    pendingOfficialSync ? AppearanceSyncStatus.PENDING : AppearanceSyncStatus.LOCAL_ONLY);
        }

        public InitialData(
                AccountState account,
                SessionValidation session,
                Optional<UUID> currentOfficialSkinId,
                Optional<UUID> activePresetId,
                Optional<AppliedAppearance> localAppearance,
                boolean pendingOfficialSync,
                List<String> storageWarnings,
                AccountUiPreferences uiPreferences,
                Optional<OuterLayerVisibility> outerLayerVisibility,
                OwnedCapeInventory ownedCapes) {
            this(
                    account,
                    session,
                    currentOfficialSkinId,
                    activePresetId,
                    localAppearance,
                    pendingOfficialSync,
                    storageWarnings,
                    uiPreferences,
                    outerLayerVisibility,
                    ownedCapes,
                    0,
                    pendingOfficialSync ? AppearanceSyncStatus.PENDING : AppearanceSyncStatus.LOCAL_ONLY);
        }

        public InitialData {
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(session, "session");
            currentOfficialSkinId = Objects.requireNonNull(currentOfficialSkinId, "currentOfficialSkinId");
            activePresetId = Objects.requireNonNull(activePresetId, "activePresetId");
            localAppearance = Objects.requireNonNull(localAppearance, "localAppearance");
            outerLayerVisibility = Objects.requireNonNull(outerLayerVisibility, "outerLayerVisibility");
            Objects.requireNonNull(ownedCapes, "ownedCapes");
            Objects.requireNonNull(syncStatus, "syncStatus");
            if (intentRevision < 0) {
                throw new IllegalArgumentException("intentRevision must not be negative");
            }
            storageWarnings = List.copyOf(Objects.requireNonNull(storageWarnings, "storageWarnings"));
            Objects.requireNonNull(uiPreferences, "uiPreferences");
            if (!uiPreferences.accountId().equals(account.accountId())) {
                throw new IllegalArgumentException("UI preferences belong to another account");
            }
            if (!ownedCapes.accountId().equals(account.accountId())) {
                throw new IllegalArgumentException("Owned capes belong to another account");
            }
        }
    }

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
            AppearanceSyncStatus syncStatus) {
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

    enum ReconciliationTrigger {
        LOCAL_INTENT,
        PROCESS_START,
        GALLERY_OPEN,
        RECONNECT,
        RATE_LIMIT_EXPIRED,
        EXPLICIT_RETRY,
        SESSION_REFRESHED
    }

    record ReconciliationKey(UUID accountId, long intentRevision) {
        public ReconciliationKey {
            Objects.requireNonNull(accountId, "accountId");
            if (intentRevision < 0) {
                throw new IllegalArgumentException("intentRevision must not be negative");
            }
        }
    }

    record DurableAppearance(
            UUID accountId,
            long intentRevision,
            AppearanceSyncStatus syncStatus,
            Optional<UUID> activePresetId,
            Optional<AppliedAppearance> localAppearance,
            Optional<OuterLayerVisibility> outerLayerVisibility) {
        public DurableAppearance {
            Objects.requireNonNull(accountId, "accountId");
            if (intentRevision < 0) {
                throw new IllegalArgumentException("intentRevision must not be negative");
            }
            Objects.requireNonNull(syncStatus, "syncStatus");
            activePresetId = Objects.requireNonNull(activePresetId, "activePresetId");
            localAppearance = Objects.requireNonNull(localAppearance, "localAppearance");
            outerLayerVisibility = Objects.requireNonNull(outerLayerVisibility, "outerLayerVisibility");
        }

        public ReconciliationKey reconciliationKey() {
            return new ReconciliationKey(accountId, intentRevision);
        }
    }

    record ReconciliationResult(
            AccountState account,
            SessionValidation session,
            Optional<UUID> currentOfficialSkinId,
            DurableAppearance appearance,
            Optional<PresetApplicationOutcome> outcome) {
        public ReconciliationResult {
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(session, "session");
            currentOfficialSkinId = Objects.requireNonNull(currentOfficialSkinId, "currentOfficialSkinId");
            Objects.requireNonNull(appearance, "appearance");
            outcome = Objects.requireNonNull(outcome, "outcome");
            if (!account.accountId().equals(appearance.accountId())) {
                throw new IllegalArgumentException("reconciliation appearance belongs to another account");
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
            PersonalSkinSource personalSkinSource) {
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

    record ImportDraft(String name, SkinVariant variant, byte[] pngBytes, PersonalSkinSource source) {
        public ImportDraft {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(variant, "variant");
            pngBytes = Objects.requireNonNull(pngBytes, "pngBytes").clone();
            Objects.requireNonNull(source, "source");
        }

        @Override
        public byte[] pngBytes() {
            return pngBytes.clone();
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

    record RemoteResult(
            PresetApplicationOutcome outcome,
            AccountState account,
            SessionValidation session,
            Optional<UUID> currentOfficialSkinId) {
        public RemoteResult {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(session, "session");
            currentOfficialSkinId = Objects.requireNonNull(currentOfficialSkinId, "currentOfficialSkinId");
        }
    }
}
