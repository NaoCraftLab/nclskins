package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AppearancePreset;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.model.RemoteProfile;
import com.naocraftlab.skins.core.model.SkinAsset;
import com.naocraftlab.skins.core.service.PresetApplicationOutcome;
import com.naocraftlab.skins.core.service.RecoveryAction;
import com.naocraftlab.skins.core.service.SessionValidation;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;


public record ClientSnapshot(
        Lifecycle lifecycle,
        Optional<AccountState> account,
        Optional<SessionValidation> session,
        Optional<RemoteProfile> remoteProfile,
        Optional<PresetApplicationOutcome> lastMutation,
        Optional<UUID> selectedSkinId,
        Optional<UUID> selectedPresetId,
        Optional<String> selectedCapeId,
        Optional<UUID> currentOfficialSkinId,
        Optional<UUID> activePresetId,
        Optional<PresetEditorModel> editor,
        Optional<AddSourceModel> addSource,
        UiMessage status,
        boolean busy,
        boolean rateLimited,
        int galleryOffset,
        long generation,
        long intentRevision,
        AppearanceSyncStatus syncStatus,
        boolean syncInProgress) {
    public ClientSnapshot(
            Lifecycle lifecycle,
            Optional<AccountState> account,
            Optional<SessionValidation> session,
            Optional<RemoteProfile> remoteProfile,
            Optional<PresetApplicationOutcome> lastMutation,
            Optional<UUID> selectedSkinId,
            Optional<UUID> selectedPresetId,
            Optional<String> selectedCapeId,
            Optional<UUID> currentOfficialSkinId,
            Optional<UUID> activePresetId,
            Optional<PresetEditorModel> editor,
            UiMessage status,
            boolean busy,
            boolean rateLimited,
            int galleryOffset,
            long generation) {
        this(
                lifecycle,
                account,
                session,
                remoteProfile,
                lastMutation,
                selectedSkinId,
                selectedPresetId,
                selectedCapeId,
                currentOfficialSkinId,
                activePresetId,
                editor,
                Optional.empty(),
                status,
                busy,
                rateLimited,
                galleryOffset,
                generation,
                0,
                AppearanceSyncStatus.LOCAL_ONLY,
                false);
    }

    public ClientSnapshot(
            Lifecycle lifecycle,
            Optional<AccountState> account,
            Optional<SessionValidation> session,
            Optional<RemoteProfile> remoteProfile,
            Optional<PresetApplicationOutcome> lastMutation,
            Optional<UUID> selectedSkinId,
            Optional<UUID> selectedPresetId,
            Optional<String> selectedCapeId,
            Optional<UUID> currentOfficialSkinId,
            Optional<UUID> activePresetId,
            Optional<PresetEditorModel> editor,
            Optional<AddSourceModel> addSource,
            UiMessage status,
            boolean busy,
            boolean rateLimited,
            int galleryOffset,
            long generation) {
        this(
                lifecycle,
                account,
                session,
                remoteProfile,
                lastMutation,
                selectedSkinId,
                selectedPresetId,
                selectedCapeId,
                currentOfficialSkinId,
                activePresetId,
                editor,
                addSource,
                status,
                busy,
                rateLimited,
                galleryOffset,
                generation,
                0,
                AppearanceSyncStatus.LOCAL_ONLY,
                false);
    }

    public enum Lifecycle {
        NEW,
        INITIALIZING,
        READY,
        CLOSED
    }

    public ClientSnapshot {
        Objects.requireNonNull(lifecycle, "lifecycle");
        account = Objects.requireNonNull(account, "account");
        session = Objects.requireNonNull(session, "session");
        remoteProfile = Objects.requireNonNull(remoteProfile, "remoteProfile");
        lastMutation = Objects.requireNonNull(lastMutation, "lastMutation");
        selectedSkinId = Objects.requireNonNull(selectedSkinId, "selectedSkinId");
        selectedPresetId = Objects.requireNonNull(selectedPresetId, "selectedPresetId");
        selectedCapeId = Objects.requireNonNull(selectedCapeId, "selectedCapeId");
        currentOfficialSkinId = Objects.requireNonNull(currentOfficialSkinId, "currentOfficialSkinId");
        activePresetId = Objects.requireNonNull(activePresetId, "activePresetId");
        editor = Objects.requireNonNull(editor, "editor");
        addSource = Objects.requireNonNull(addSource, "addSource");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(syncStatus, "syncStatus");
        if (galleryOffset < 0 || generation < 0 || intentRevision < 0) {
            throw new IllegalArgumentException("offset and revisions must not be negative");
        }
    }

    public static ClientSnapshot initial() {
        return new ClientSnapshot(
                Lifecycle.NEW,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                UiMessage.info("nclskins.status.loading"),
                false,
                false,
                0,
                0);
    }

    public Optional<SkinAsset> selectedSkin() {
        if (account.isEmpty() || selectedSkinId.isEmpty()) {
            return Optional.empty();
        }
        UUID id = selectedSkinId.orElseThrow();
        return account.orElseThrow().skinAssets().stream().filter(asset -> asset.id().equals(id)).findFirst();
    }

    public Optional<AppearancePreset> selectedPreset() {
        if (account.isEmpty() || selectedPresetId.isEmpty()) {
            return Optional.empty();
        }
        UUID id = selectedPresetId.orElseThrow();
        return account.orElseThrow().presets().stream().filter(preset -> preset.id().equals(id)).findFirst();
    }

    public Set<RecoveryAction> recoveryActions() {
        EnumSet<RecoveryAction> actions = EnumSet.noneOf(RecoveryAction.class);
        lastMutation.map(PresetApplicationOutcome::recoveryActions).ifPresent(actions::addAll);
        actions.remove(RecoveryAction.RESTORE_PREVIOUS_APPEARANCE);
        if (syncStatus == AppearanceSyncStatus.PARTIAL) {
            actions.add(RecoveryAction.RETRY_CAPE);
        } else if (syncStatus == AppearanceSyncStatus.UNKNOWN) {
            actions.add(RecoveryAction.REFRESH_REMOTE_PROFILE);
        }
        return Set.copyOf(actions);
    }

    public boolean remoteControlsBlocked() {
        return rateLimited || session.isEmpty() || !session.orElseThrow().valid();
    }
}
