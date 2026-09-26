package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.core.compatibility.SkinFeatureEvidence;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AccountUiPreferences;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.model.OwnedCapeInventory;
import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.service.AppliedAppearance;
import com.naocraftlab.skins.core.service.PresetApplicationOutcome;
import com.naocraftlab.skins.core.service.SessionValidation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface ClientOperations extends AutoCloseable, CapeObservationPort, CatalogRead, CatalogMaterialization, ImportOperations, LibraryEditorPort, UiPreferencesPort, ProviderOperations, ClientSessionView, PreviewAssetSource {

    void verifyStorageAccess() throws Exception;

    void warmSession() throws Exception;

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

    default Map<UUID, SkinFeatureEvidence> assetFeatureEvidence() throws Exception {
        return Map.of();
    }

    default boolean supportsAssetFeatureEvidence() {
        return false;
    }

    default Optional<OwnedCapeInventory> ownedCapeInventory() throws Exception {
        return Optional.empty();
    }

    void warmOwnedCapeCache() throws Exception;

    InitialData resetLibrary() throws Exception;

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

    InitialData retrySession() throws Exception;

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
            AppearanceSyncStatus syncStatus,
            AppearanceProviders providers) {
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
                OwnedCapeInventory ownedCapes,
                long intentRevision,
                AppearanceSyncStatus syncStatus) {
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
                    intentRevision,
                    syncStatus,
                    AppearanceProviders.initial());
        }

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
            Objects.requireNonNull(providers, "providers");
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

    enum ReconciliationTrigger {
        LOCAL_INTENT,
        PROCESS_START,
        RECONNECT,
        RATE_LIMIT_EXPIRED,
        EXPLICIT_RETRY,
        SESSION_REFRESHED
    }

    record ReconciliationKey(UUID accountId, long intentRevision, long skinActivation, long capeActivation) {
        public ReconciliationKey(UUID accountId, long intentRevision) {
            this(accountId, intentRevision, 0, 0);
        }

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
            Optional<OuterLayerVisibility> outerLayerVisibility,
            AppearanceProviders providers) {
        public DurableAppearance(
                UUID accountId,
                long intentRevision,
                AppearanceSyncStatus syncStatus,
                Optional<UUID> activePresetId,
                Optional<AppliedAppearance> localAppearance,
                Optional<OuterLayerVisibility> outerLayerVisibility) {
            this(
                    accountId,
                    intentRevision,
                    syncStatus,
                    activePresetId,
                    localAppearance,
                    outerLayerVisibility,
                    AppearanceProviders.initial());
        }

        public DurableAppearance {
            Objects.requireNonNull(providers, "providers");
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
            return new ReconciliationKey(accountId, intentRevision,
                    providers.skin().minecraftDelivery().activation(),
                    providers.cape().minecraftDelivery().activation());
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
