package com.naocraftlab.skins.core.service;

import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.core.api.ApiFailureKind;
import com.naocraftlab.skins.core.api.ProfileApi;
import com.naocraftlab.skins.core.api.ProfileApiException;
import com.naocraftlab.skins.core.model.AppearancePreset;
import com.naocraftlab.skins.core.model.MutationResult;
import com.naocraftlab.skins.core.model.RemoteAssetState;
import com.naocraftlab.skins.core.model.RemoteCape;
import com.naocraftlab.skins.core.model.RemoteProfile;
import com.naocraftlab.skins.core.model.RemoteSkin;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import com.naocraftlab.skins.core.storage.ProcessFileLock;
import com.naocraftlab.skins.core.storage.TextureCache;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;


public final class AppearanceMutationService {
    private final ProfileApi api;
    private final NclSkinsStorage storage;
    private final TextureCache textureCache;
    private final RemoteSessionGate gate;
    private final SessionValidationService validationService;


    public AppearanceMutationService(
            ProfileApi api,
            NclSkinsStorage storage,
            RemoteSessionGate gate,
            SessionValidationService validationService) {
        this.api = Objects.requireNonNull(api, "api");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.textureCache = new TextureCache(storage);
        this.gate = Objects.requireNonNull(gate, "gate");
        this.validationService = Objects.requireNonNull(validationService, "validationService");
    }

    @SuppressWarnings("try")
    public PresetApplicationOutcome applyPreset(
            GameSessionTokenSource tokenSource,
            PresetApplicationRequest request) {
        return applyPreset(tokenSource, request, () -> true);
    }


    @SuppressWarnings("try")
    public PresetApplicationOutcome applyPreset(
            GameSessionTokenSource tokenSource,
            PresetApplicationRequest request,
            BooleanSupplier stillCurrent) {
        Objects.requireNonNull(tokenSource, "tokenSource");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(stillCurrent, "stillCurrent");
        GameSessionTokenSource.SessionIdentity identity = currentIdentity(tokenSource);
        if (identity == null) {
            return localFailure("The running Minecraft session is unavailable.");
        }
        if (gate.remoteControlsBlocked(identity.profileId())) {
            return sessionExpired(ApplicationPhase.VALIDATION, null, null, null, false);
        }

        try (ProcessFileLock ignored = storage.acquireRemoteMutationLock(identity.profileId())) {
            return applyPresetWhileLocked(tokenSource, request, stillCurrent);
        } catch (IOException exception) {
            return localFailure("The per-account mutation lock could not be acquired.");
        }
    }


    public PresetApplicationOutcome applyPresetWhileLocked(
            GameSessionTokenSource tokenSource,
            PresetApplicationRequest request,
            BooleanSupplier stillCurrent) {
        return applyPresetWhileLocked(tokenSource, request, stillCurrent, false);
    }


    public PresetApplicationOutcome applyPresetWhileLockedAfterSameTokenValidation(
            GameSessionTokenSource tokenSource,
            PresetApplicationRequest request,
            BooleanSupplier stillCurrent) {
        return applyPresetWhileLocked(tokenSource, request, stillCurrent, true);
    }

    private PresetApplicationOutcome applyPresetWhileLocked(
            GameSessionTokenSource tokenSource,
            PresetApplicationRequest request,
            BooleanSupplier stillCurrent,
            boolean sameTokenProfileValidated) {
        Objects.requireNonNull(tokenSource, "tokenSource");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(stillCurrent, "stillCurrent");
        GameSessionTokenSource.SessionIdentity identity = currentIdentity(tokenSource);
        if (identity == null) {
            return localFailure("The running Minecraft session is unavailable.");
        }
        if (gate.remoteControlsBlocked(identity.profileId())) {
            return sessionExpired(ApplicationPhase.VALIDATION, null, null, null, false);
        }
        if (!stillCurrent.getAsBoolean()) {
            return localFailure("A newer local appearance superseded this request.");
        }
        PresetApplicationOutcome unresolved = unresolvedSnapshot(identity);
        if (unresolved != null) {
            return unresolved;
        }
        try {
            return tokenSource.withAccessToken(accessToken -> applyScoped(
                    accessToken, identity, request, sameTokenProfileValidated));
        } catch (RuntimeException exception) {
            return credentialFailure("The running Minecraft session could not provide credentials.");
        }
    }


    @SuppressWarnings("try")
    public PresetApplicationOutcome retryCape(GameSessionTokenSource tokenSource, String capeId) {
        Objects.requireNonNull(tokenSource, "tokenSource");
        if (capeId != null && (capeId.isBlank() || capeId.length() > 256)) {
            throw new IllegalArgumentException("capeId is invalid");
        }
        GameSessionTokenSource.SessionIdentity identity = currentIdentity(tokenSource);
        if (identity == null) {
            return localFailure("The running Minecraft session is unavailable.");
        }
        if (gate.remoteControlsBlocked(identity.profileId())) {
            return sessionExpired(ApplicationPhase.VALIDATION, null, null, null, false);
        }
        try (ProcessFileLock ignored = storage.acquireRemoteMutationLock(identity.profileId())) {
            if (gate.remoteControlsBlocked(identity.profileId())) {
                return sessionExpired(ApplicationPhase.VALIDATION, null, null, null, false);
            }
            return tokenSource.withAccessToken(accessToken -> retryCapeScoped(accessToken, identity, capeId));
        } catch (IOException exception) {
            return localFailure("The per-account mutation lock could not be acquired.");
        } catch (RuntimeException exception) {
            return localFailure("The running Minecraft session could not provide credentials.");
        }
    }


    public PresetApplicationOutcome retryCapeWhileLocked(
            GameSessionTokenSource tokenSource,
            String capeId,
            BooleanSupplier stillCurrent) {
        return retryCapeWhileLocked(tokenSource, capeId, stillCurrent, false);
    }


    public PresetApplicationOutcome retryCapeWhileLockedAfterSameTokenValidation(
            GameSessionTokenSource tokenSource,
            String capeId,
            BooleanSupplier stillCurrent) {
        return retryCapeWhileLocked(tokenSource, capeId, stillCurrent, true);
    }

    private PresetApplicationOutcome retryCapeWhileLocked(
            GameSessionTokenSource tokenSource,
            String capeId,
            BooleanSupplier stillCurrent,
            boolean sameTokenProfileValidated) {
        Objects.requireNonNull(tokenSource, "tokenSource");
        Objects.requireNonNull(stillCurrent, "stillCurrent");
        if (capeId != null && (capeId.isBlank() || capeId.length() > 256)) {
            throw new IllegalArgumentException("capeId is invalid");
        }
        GameSessionTokenSource.SessionIdentity identity = currentIdentity(tokenSource);
        if (identity == null) {
            return localFailure("The running Minecraft session is unavailable.");
        }
        if (gate.remoteControlsBlocked(identity.profileId())) {
            return sessionExpired(ApplicationPhase.VALIDATION, null, null, null, false);
        }
        if (!stillCurrent.getAsBoolean()) {
            return localFailure("A newer local appearance superseded this request.");
        }
        PresetApplicationOutcome unresolved = unresolvedSnapshot(identity);
        if (unresolved != null) {
            return unresolved;
        }
        try {
            return tokenSource.withAccessToken(accessToken ->
                    retryCapeScoped(
                            accessToken, identity, capeId, sameTokenProfileValidated));
        } catch (RuntimeException exception) {
            return credentialFailure("The running Minecraft session could not provide credentials.");
        }
    }


    @SuppressWarnings("try")
    public PresetApplicationOutcome applySkinOnly(
            GameSessionTokenSource tokenSource,
            ResolvedSkinAsset resolvedSkin) {
        return applySkinOnly(tokenSource, resolvedSkin, false);
    }


    @SuppressWarnings("try")
    public PresetApplicationOutcome forceApplySkinOnly(
            GameSessionTokenSource tokenSource,
            ResolvedSkinAsset resolvedSkin) {
        return applySkinOnly(tokenSource, resolvedSkin, true);
    }

    @SuppressWarnings("try")
    private PresetApplicationOutcome applySkinOnly(
            GameSessionTokenSource tokenSource,
            ResolvedSkinAsset resolvedSkin,
            boolean forceMutation) {
        Objects.requireNonNull(tokenSource, "tokenSource");
        GameSessionTokenSource.SessionIdentity identity = currentIdentity(tokenSource);
        if (identity == null) {
            return localFailure("The running Minecraft session is unavailable.");
        }
        if (gate.remoteControlsBlocked(identity.profileId())) {
            return sessionExpired(ApplicationPhase.VALIDATION, null, null, null, false);
        }
        try (ProcessFileLock ignored = storage.acquireRemoteMutationLock(identity.profileId())) {
            if (gate.remoteControlsBlocked(identity.profileId())) {
                return sessionExpired(ApplicationPhase.VALIDATION, null, null, null, false);
            }
            return tokenSource.withAccessToken(accessToken ->
                    applySkinOnlyScoped(accessToken, identity, resolvedSkin, forceMutation));
        } catch (IOException exception) {
            return localFailure("The per-account mutation lock could not be acquired.");
        } catch (RuntimeException exception) {
            return localFailure("The running Minecraft session could not provide credentials.");
        }
    }

    private PresetApplicationOutcome applyScoped(
            String accessToken,
            GameSessionTokenSource.SessionIdentity identity,
            PresetApplicationRequest request) {
        return applyScoped(accessToken, identity, request, false);
    }

    private PresetApplicationOutcome applyScoped(
            String accessToken,
            GameSessionTokenSource.SessionIdentity identity,
            PresetApplicationRequest request,
            boolean sameTokenProfileValidated) {
        String requestedCape = request.preset().capeId();


        SessionValidation validation = sameTokenProfileValidated
                ? validationService.cachedOrValidateScoped(accessToken, identity, requestedCape)
                : validationService.validateScoped(accessToken, identity, requestedCape);
        if (!validation.valid()) {
            return fromValidation(validation);
        }
        RemoteProfile before = validation.profile();
        if (validationService.skinStateUnknown(identity.profileId())
                || validationService.capeStateUnknown(identity.profileId())) {
            return unknownSnapshot(before);
        }

        SkinStep skinStep = mutateSkin(accessToken, identity, request, before);
        if (!skinStep.applied()) {
            return skinFailureOutcome(skinStep, before);
        }

        CapeStep capeStep = mutateCape(accessToken, identity, requestedCape, skinStep.profile());
        if (!capeStep.applied()) {
            AppliedAppearance applied = skinStep.changed()
                    ? validationService.currentAppliedAppearance(identity.profileId(), skinStep.profile())
                    : null;
            return capeFailureOutcome(capeStep, before, skinStep.profile(), skinStep.changed(), applied);
        }
        AppliedAppearance appearance = validationService.currentAppliedAppearance(
                identity.profileId(), capeStep.profile());
        String message = !skinStep.changed() && !capeStep.changed()
                ? "This skin and cape preset is already active."
                : "Skin and cape preset was applied.";
        return appliedOutcome(
                before,
                capeStep.profile(),
                appearance,
                skinStep.changed() || capeStep.changed(),
                message);
    }

    private PresetApplicationOutcome unresolvedSnapshot(
            GameSessionTokenSource.SessionIdentity identity) {
        if (!validationService.skinStateUnknown(identity.profileId())
                && !validationService.capeStateUnknown(identity.profileId())) {
            return null;
        }
        SessionValidation cached = validationService.cachedScoped(identity, null);
        return unknownSnapshot(cached == null ? null : cached.profile());
    }

    private PresetApplicationOutcome retryCapeScoped(
            String accessToken,
            GameSessionTokenSource.SessionIdentity identity,
            String capeId) {
        return retryCapeScoped(accessToken, identity, capeId, false);
    }

    private PresetApplicationOutcome retryCapeScoped(
            String accessToken,
            GameSessionTokenSource.SessionIdentity identity,
            String capeId,
            boolean sameTokenProfileValidated) {
        SessionValidation validation = sameTokenProfileValidated
                ? validationService.cachedOrValidateScoped(accessToken, identity, capeId)
                : validationService.validateScoped(accessToken, identity, capeId);
        if (!validation.valid()) {
            return fromValidation(validation);
        }
        RemoteProfile before = validation.profile();
        if (validationService.capeStateUnknown(identity.profileId())) {
            return unknownSnapshot(before);
        }
        CapeStep capeStep = mutateCape(accessToken, identity, capeId, before);
        if (!capeStep.applied()) {
            return capeFailureOutcome(capeStep, before, before, false, null);
        }
        AppliedAppearance appearance = validationService.currentAppliedAppearance(
                identity.profileId(), capeStep.profile());
        return appliedOutcome(
                before,
                capeStep.profile(),
                appearance,
                capeStep.changed(),
                capeStep.changed() ? "Cape selection was applied." : "This cape selection is already active.");
    }

    private PresetApplicationOutcome applySkinOnlyScoped(
            String accessToken,
            GameSessionTokenSource.SessionIdentity identity,
            ResolvedSkinAsset resolvedSkin,
            boolean forceMutation) {
        SessionValidation validation = validationService.validateScoped(accessToken, identity, null);
        if (!validation.valid()) {
            return fromValidation(validation);
        }
        RemoteProfile before = validation.profile();
        if (validationService.skinStateUnknown(identity.profileId())) {
            return unknownSnapshot(before);
        }
        Instant now = Instant.now();
        SkinReference reference = resolvedSkin == null
                ? SkinReference.accountDefault()
                : SkinReference.asset(resolvedSkin.assetId());
        AppearancePreset transientPreset = new AppearancePreset(
                UUID.randomUUID(), "Save & Use", reference, null, now, now);
        SkinStep skinStep = mutateSkin(
                accessToken,
                identity,
                new PresetApplicationRequest(transientPreset, resolvedSkin),
                before,
                forceMutation);
        if (!skinStep.applied()) {
            return skinFailureOutcome(skinStep, before);
        }
        AppliedAppearance appearance = validationService.currentAppliedAppearance(
                identity.profileId(), skinStep.profile());
        String message;
        if (!skinStep.changed()) {
            message = "This skin selection is already active.";
        } else if (resolvedSkin == null) {
            message = "Official skin was reset to the account default.";
        } else {
            message = "Skin was saved and activated.";
        }
        return appliedOutcome(before, skinStep.profile(), appearance, skinStep.changed(), message);
    }

    private SkinStep mutateSkin(
            String accessToken,
            GameSessionTokenSource.SessionIdentity identity,
            PresetApplicationRequest request,
            RemoteProfile before) {
        return mutateSkin(accessToken, identity, request, before, false);
    }

    private SkinStep mutateSkin(
            String accessToken,
            GameSessionTokenSource.SessionIdentity identity,
            PresetApplicationRequest request,
            RemoteProfile before,
            boolean forceMutation) {
        boolean accountDefault = request.preset().skin().kind() == SkinReference.Kind.ACCOUNT_DEFAULT;
        if (!forceMutation && skinMatchesAcknowledged(identity.profileId(), request, before)) {
            return SkinStep.applied(before, false);
        }

        try {
            if (accountDefault) {
                api.resetSkin(accessToken);
            } else {
                ResolvedSkinAsset resolved = request.resolvedSkin();
                api.uploadSkin(accessToken, resolved.variant(), resolved.pngBytes());
            }
        } catch (ProfileApiException exception) {
            if (exception.sessionExpired()) {
                gate.block(identity.profileId());
                return SkinStep.failed(ApplicationPhase.SKIN_MUTATION, exception.kind(), false, before);
            }
            if (exception.mutationMayHaveApplied()) {
                validationService.rememberUnknownSkin(identity.profileId());
                return SkinStep.failed(ApplicationPhase.SKIN_MUTATION, exception.kind(), true, before);
            }
            return SkinStep.failed(ApplicationPhase.SKIN_MUTATION, exception.kind(), false, before);
        }

        if (accountDefault) {
            validationService.rememberAccountDefaultSkin(identity.profileId());
        } else {
            validationService.rememberAppliedSkin(identity.profileId(), request.resolvedSkin());
        }
        validationService.rememberManagedAppearance(identity.profileId());
        return SkinStep.applied(before, true);
    }

    private CapeStep mutateCape(
            String accessToken,
            GameSessionTokenSource.SessionIdentity identity,
            String capeId,
            RemoteProfile skinProfile) {
        if (capeMatches(skinProfile, capeId)) {
            return CapeStep.applied(skinProfile, false);
        }
        try {
            if (capeId == null) {
                api.deactivateCape(accessToken);
            } else {
                api.activateCape(accessToken, capeId);
            }
        } catch (ProfileApiException exception) {
            if (exception.sessionExpired()) {
                gate.block(identity.profileId());
                return CapeStep.failed(ApplicationPhase.CAPE_MUTATION, exception.kind(), false, skinProfile);
            }
            if (exception.mutationMayHaveApplied()) {
                validationService.rememberUnknownCape(identity.profileId());
                return CapeStep.failed(ApplicationPhase.CAPE_MUTATION, exception.kind(), true, skinProfile);
            }
            return CapeStep.failed(ApplicationPhase.CAPE_MUTATION, exception.kind(), false, skinProfile);
        }

        RemoteProfile updated = withActiveCape(skinProfile, capeId);
        validationService.rememberKnownCape(identity.profileId());
        validationService.rememberManagedAppearance(identity.profileId());
        validationService.rememberVerifiedProfile(identity, updated);
        return CapeStep.applied(updated, true);
    }

    private boolean skinMatchesAcknowledged(
            UUID profileId,
            PresetApplicationRequest request,
            RemoteProfile profile) {
        if (request.preset().skin().kind() == SkinReference.Kind.ACCOUNT_DEFAULT) {
            return profile.activeSkin().isEmpty()
                    || validationService.accountDefaultSkinAcknowledged(profileId);
        }
        ResolvedSkinAsset requested = request.resolvedSkin();
        if (validationService.acknowledgedSkinMatches(profileId, requested)) {
            return true;
        }


        if (validationService.hasAcknowledgedSkinState(profileId)) {
            return false;
        }
        RemoteSkin active = profile.activeSkin().orElse(null);
        if (active == null || active.variant() != requested.variant()) {
            return false;
        }
        try {
            Path cached = textureCache.cachePath(active.textureUri());
            if (!Files.isRegularFile(cached) || !requested.sha256().equals(sha256(cached))) {
                return false;
            }
            validationService.rememberAppliedSkin(profileId, requested);
            return true;
        } catch (IOException | RuntimeException unavailableCache) {
            return false;
        }
    }

    private static RemoteProfile withActiveCape(RemoteProfile profile, String capeId) {
        List<RemoteCape> capes = new ArrayList<>(profile.capes().size());
        for (RemoteCape cape : profile.capes()) {
            RemoteAssetState state = capeId != null && cape.id().equals(capeId)
                    ? RemoteAssetState.ACTIVE
                    : RemoteAssetState.INACTIVE;
            capes.add(new RemoteCape(cape.id(), state, cape.textureUri(), cape.alias()));
        }
        return new RemoteProfile(profile.id(), profile.name(), profile.skins(), capes, profile.profileActions());
    }

    private static boolean capeMatches(RemoteProfile profile, String capeId) {
        RemoteCape active = profile.activeCape().orElse(null);
        return capeId == null ? active == null : active != null && active.id().equals(capeId);
    }

    private PresetApplicationOutcome fromValidation(SessionValidation validation) {
        if (validation.status() == SessionStatus.EXPIRED) {
            return sessionExpired(ApplicationPhase.VALIDATION, validation.profile(), null, null, false);
        }
        return new PresetApplicationOutcome(
                MutationResult.FAILED,
                ApplicationPhase.VALIDATION,
                validation.profile(),
                null,
                null,
                validation.failureKind(),
                Set.of(RecoveryAction.MANUAL_SESSION_RETRY),
                RemoteAppearanceImpact.NONE,
                validation.userMessage());
    }

    private PresetApplicationOutcome skinFailureOutcome(SkinStep step, RemoteProfile before) {
        if (step.failureKind() == ApiFailureKind.SESSION_EXPIRED && !step.uncertain()) {
            return sessionExpired(step.phase(), before, step.profile(), null, false);
        }
        MutationResult result = step.uncertain() ? MutationResult.UNKNOWN : MutationResult.FAILED;
        Set<RecoveryAction> actions = step.failureKind() == ApiFailureKind.SESSION_EXPIRED
                ? Set.of(RecoveryAction.RESTART_GAME, RecoveryAction.REFRESH_REMOTE_PROFILE)
                : Set.of(RecoveryAction.REFRESH_REMOTE_PROFILE);
        return new PresetApplicationOutcome(
                result,
                step.phase(),
                before,
                step.profile(),
                null,
                step.failureKind(),
                actions,
                step.uncertain()
                        ? RemoteAppearanceImpact.UNCERTAIN
                        : RemoteAppearanceImpact.NONE,
                step.uncertain()
                        ? "Skin mutation may have been applied; refresh the profile before another change."
                        : "Skin mutation failed before the cape was changed.");
    }

    private PresetApplicationOutcome capeFailureOutcome(
            CapeStep step,
            RemoteProfile before,
            RemoteProfile afterSkin,
            boolean skinChanged,
            AppliedAppearance appliedAppearance) {
        if (step.failureKind() == ApiFailureKind.SESSION_EXPIRED && !step.uncertain()) {
            return sessionExpired(step.phase(), before, afterSkin, appliedAppearance, skinChanged);
        }
        if (step.uncertain()) {
            Set<RecoveryAction> actions = step.failureKind() == ApiFailureKind.SESSION_EXPIRED
                    ? Set.of(RecoveryAction.RESTART_GAME, RecoveryAction.REFRESH_REMOTE_PROFILE)
                    : Set.of(RecoveryAction.REFRESH_REMOTE_PROFILE);
            return new PresetApplicationOutcome(
                    MutationResult.UNKNOWN,
                    step.phase(),
                    before,
                    afterSkin,
                    appliedAppearance,
                    step.failureKind(),
                    actions,
                    RemoteAppearanceImpact.UNCERTAIN,
                    "Cape mutation may have been applied; refresh the profile before another change.");
        }
        Set<RecoveryAction> actions = step.failureKind() == ApiFailureKind.SESSION_EXPIRED
                ? Set.of(RecoveryAction.RESTART_GAME)
                : Set.of(RecoveryAction.RETRY_CAPE);
        return new PresetApplicationOutcome(
                skinChanged ? MutationResult.PARTIAL : MutationResult.FAILED,
                step.phase(),
                before,
                afterSkin,
                appliedAppearance,
                step.failureKind(),
                actions,
                skinChanged
                        ? RemoteAppearanceImpact.CONFIRMED_CHANGED
                        : RemoteAppearanceImpact.NONE,
                skinChanged
                        ? "Skin changed, but the cape operation failed."
                        : "The cape operation failed; the skin was not changed.");
    }

    private static PresetApplicationOutcome appliedOutcome(
            RemoteProfile before,
            RemoteProfile after,
            AppliedAppearance appearance,
            boolean remoteMutationPerformed,
            String message) {
        return new PresetApplicationOutcome(
                MutationResult.APPLIED,
                ApplicationPhase.COMPLETE,
                before,
                after,
                appearance,
                null,
                Set.of(),
                remoteMutationPerformed
                        ? RemoteAppearanceImpact.CONFIRMED_CHANGED
                        : RemoteAppearanceImpact.NONE,
                message);
    }

    private static PresetApplicationOutcome unknownSnapshot(RemoteProfile before) {
        return new PresetApplicationOutcome(
                MutationResult.FAILED,
                ApplicationPhase.VALIDATION,
                before,
                null,
                null,
                ApiFailureKind.INVALID_RESPONSE,
                Set.of(RecoveryAction.REFRESH_REMOTE_PROFILE),
                RemoteAppearanceImpact.NONE,
                "A previous mutation has an unknown result. Refresh the Minecraft profile before another change.");
    }

    private static PresetApplicationOutcome sessionExpired(
            ApplicationPhase phase,
            RemoteProfile before,
            RemoteProfile after,
            AppliedAppearance appliedAppearance,
            boolean partial) {
        return new PresetApplicationOutcome(
                partial ? MutationResult.PARTIAL : MutationResult.SESSION_EXPIRED,
                phase,
                before,
                after,
                appliedAppearance,
                ApiFailureKind.SESSION_EXPIRED,
                Set.of(RecoveryAction.RESTART_GAME),
                partial
                        ? RemoteAppearanceImpact.CONFIRMED_CHANGED
                        : RemoteAppearanceImpact.NONE,
                "Minecraft session expired. Restart the game through a licensed launcher.");
    }

    private static PresetApplicationOutcome localFailure(String message) {
        return new PresetApplicationOutcome(
                MutationResult.FAILED,
                ApplicationPhase.VALIDATION,
                null,
                null,
                null,
                ApiFailureKind.NETWORK,
                Set.of(),
                RemoteAppearanceImpact.NONE,
                message);
    }

    private static PresetApplicationOutcome credentialFailure(String message) {
        return new PresetApplicationOutcome(
                MutationResult.FAILED,
                ApplicationPhase.VALIDATION,
                null,
                null,
                null,
                ApiFailureKind.INVALID_SESSION,
                Set.of(RecoveryAction.MANUAL_SESSION_RETRY),
                RemoteAppearanceImpact.NONE,
                message);
    }

    private static GameSessionTokenSource.SessionIdentity currentIdentity(GameSessionTokenSource tokenSource) {
        try {
            return Objects.requireNonNull(tokenSource.currentSession(), "currentSession");
        } catch (RuntimeException unavailableSession) {
            return null;
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM does not provide SHA-256", impossible);
        }
    }

    private record SkinStep(
            boolean applied,
            boolean changed,
            ApplicationPhase phase,
            ApiFailureKind failureKind,
            boolean uncertain,
            RemoteProfile profile) {
        private static SkinStep applied(RemoteProfile profile, boolean changed) {
            return new SkinStep(true, changed, ApplicationPhase.SKIN_MUTATION, null, false, profile);
        }

        private static SkinStep failed(
                ApplicationPhase phase,
                ApiFailureKind failureKind,
                boolean uncertain,
                RemoteProfile profile) {
            return new SkinStep(false, false, phase, failureKind, uncertain, profile);
        }
    }

    private record CapeStep(
            boolean applied,
            boolean changed,
            ApplicationPhase phase,
            ApiFailureKind failureKind,
            boolean uncertain,
            RemoteProfile profile) {
        private static CapeStep applied(RemoteProfile profile, boolean changed) {
            return new CapeStep(true, changed, ApplicationPhase.CAPE_MUTATION, null, false, profile);
        }

        private static CapeStep failed(
                ApplicationPhase phase,
                ApiFailureKind failureKind,
                boolean uncertain,
                RemoteProfile profile) {
            return new CapeStep(false, false, phase, failureKind, uncertain, profile);
        }
    }

}
