package com.naocraftlab.skins.core.service;

import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.GameSessionTokenUnavailableException;
import com.naocraftlab.skins.core.api.ApiFailureKind;
import com.naocraftlab.skins.core.api.ProfileApi;
import com.naocraftlab.skins.core.api.ProfileApiException;
import com.naocraftlab.skins.core.model.RemoteProfile;
import com.naocraftlab.skins.core.model.SkinVariant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;


public final class SessionValidationService {
    private final ProfileApi api;
    private final RemoteSessionGate gate;
    private final Map<UUID, SessionValidation> validationCache = new HashMap<>();
    private final Map<UUID, RemoteProfile> lastVerifiedProfiles = new HashMap<>();
    private final Map<UUID, AcknowledgedSkin> acknowledgedSkins = new HashMap<>();
    private final Map<UUID, Boolean> unknownCapes = new HashMap<>();
    private final Set<UUID> managedAppearances = new HashSet<>();

    public SessionValidationService(ProfileApi api, RemoteSessionGate gate) {
        this.api = Objects.requireNonNull(api, "api");
        this.gate = Objects.requireNonNull(gate, "gate");
    }

    public SessionValidation currentStatus(GameSessionTokenSource tokenSource) {
        Objects.requireNonNull(tokenSource, "tokenSource");
        GameSessionTokenSource.SessionIdentity identity = tokenSource.currentSession();
        if (gate.remoteControlsBlocked(identity.profileId())) {
            return expired(identity);
        }
        SessionValidation cached;
        synchronized (this) {
            cached = validationCache.get(identity.profileId());
        }
        if (cached != null) {
            return cached;
        }
        return withFreshToken(tokenSource, identity, false);
    }


    public synchronized SessionValidation cachedStatus(
            GameSessionTokenSource.SessionIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        if (gate.remoteControlsBlocked(identity.profileId())) {
            return expired(identity);
        }
        SessionValidation cached = validationCache.get(identity.profileId());
        return cached == null ? uncheckedOffline(identity) : cached;
    }


    public synchronized boolean automaticCheckpointMayAcquireToken(
            GameSessionTokenSource.SessionIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        if (gate.remoteControlsBlocked(identity.profileId())) {
            return false;
        }
        SessionValidation cached = validationCache.get(identity.profileId());
        if (cached == null || cached.valid()) {
            return true;
        }
        return cached.failureKind() == ApiFailureKind.NETWORK
                || cached.failureKind() == ApiFailureKind.SERVER_ERROR
                || cached.failureKind() == ApiFailureKind.RATE_LIMITED;
    }


    public SessionValidation retryTransientAtCheckpoint(
            GameSessionTokenSource tokenSource) {
        Objects.requireNonNull(tokenSource, "tokenSource");
        GameSessionTokenSource.SessionIdentity identity = tokenSource.currentSession();
        if (gate.remoteControlsBlocked(identity.profileId())) {
            return expired(identity);
        }
        SessionValidation cached;
        synchronized (this) {
            cached = validationCache.get(identity.profileId());
        }
        if (cached == null || cached.valid()) {


            return withFreshToken(tokenSource, identity, false);
        }
        ApiFailureKind failure = cached.failureKind();
        if (failure != ApiFailureKind.NETWORK
                && failure != ApiFailureKind.SERVER_ERROR
                && failure != ApiFailureKind.RATE_LIMITED) {
            return cached;
        }
        if (failure == ApiFailureKind.RATE_LIMITED && api.rateLimitRemaining().isPresent()) {
            return cached;
        }
        synchronized (this) {
            validationCache.remove(identity.profileId(), cached);
        }
        return withFreshToken(tokenSource, identity, false);
    }


    public SessionValidation observeFreshAtCheckpoint(
            GameSessionTokenSource tokenSource) {
        Objects.requireNonNull(tokenSource, "tokenSource");
        GameSessionTokenSource.SessionIdentity identity = tokenSource.currentSession();
        if (gate.remoteControlsBlocked(identity.profileId())) {
            return expired(identity);
        }
        return withFreshToken(tokenSource, identity, false);
    }


    public SessionValidation manualRetry(GameSessionTokenSource tokenSource) {
        Objects.requireNonNull(tokenSource, "tokenSource");
        GameSessionTokenSource.SessionIdentity identity = tokenSource.currentSession();
        synchronized (this) {
            validationCache.remove(identity.profileId());
        }
        SessionValidation result = withFreshToken(tokenSource, identity, true);
        if (result.valid()) {
            synchronized (this) {
                acknowledgedSkins.remove(identity.profileId());
                unknownCapes.remove(identity.profileId());
            }
            gate.clearAfterSuccessfulManualRetry(identity.profileId());
        }
        return result;
    }

    SessionValidation validateScoped(
            String accessToken,
            GameSessionTokenSource.SessionIdentity identity,
            String requiredCapeId) {
        final RemoteProfile profile;
        try {
            profile = api.getProfile(accessToken);
        } catch (ProfileApiException exception) {
            return remember(apiFailure(identity, null, SessionCheckPhase.PROFILE, exception));
        }
        return validateFreshProfileSnapshot(identity, profile, requiredCapeId);
    }

    private synchronized SessionValidation validateFreshProfileSnapshot(
            GameSessionTokenSource.SessionIdentity identity,
            RemoteProfile profile,
            String requiredCapeId) {
        if (profile.id().equals(identity.profileId())) {


            acknowledgedSkins.remove(identity.profileId());
            unknownCapes.remove(identity.profileId());
        }
        return validateProfileSnapshot(identity, profile, requiredCapeId);
    }


    SessionValidation cachedOrValidateScoped(
            String accessToken,
            GameSessionTokenSource.SessionIdentity identity,
            String requiredCapeId) {
        Objects.requireNonNull(identity, "identity");
        if (gate.remoteControlsBlocked(identity.profileId())) {
            return expired(identity);
        }

        SessionValidation cached;
        synchronized (this) {
            cached = validationCache.get(identity.profileId());
        }
        if (cached == null || !cached.valid() || cached.profile() == null) {
            return validateScoped(accessToken, identity, requiredCapeId);
        }
        return validateProfileSnapshot(identity, cached.profile(), requiredCapeId);
    }

    synchronized SessionValidation cachedScoped(
            GameSessionTokenSource.SessionIdentity identity,
            String requiredCapeId) {
        Objects.requireNonNull(identity, "identity");
        if (gate.remoteControlsBlocked(identity.profileId())) {
            return expired(identity);
        }
        SessionValidation cached = validationCache.get(identity.profileId());
        if (cached == null || !cached.valid() || cached.profile() == null) {
            return null;
        }
        return validateProfileSnapshot(identity, cached.profile(), requiredCapeId);
    }

    private synchronized SessionValidation validateProfileSnapshot(
            GameSessionTokenSource.SessionIdentity identity,
            RemoteProfile profile,
            String requiredCapeId) {
        if (!profile.id().equals(identity.profileId())) {
            SessionFailureContext context = new SessionFailureContext(
                    SessionCheckPhase.PROFILE, ApiFailureKind.INVALID_SESSION, null);
            return remember(new SessionValidation(
                    SessionStatus.UUID_MISMATCH,
                    identity,
                    profile,
                    context,
                    withDiagnostic(
                            context,
                            "Minecraft profile UUID does not match the running game session.")));
        }
        if (!profile.profileActions().isEmpty()) {
            SessionFailureContext context = new SessionFailureContext(
                    SessionCheckPhase.PROFILE, ApiFailureKind.FORBIDDEN, null);
            return remember(new SessionValidation(
                    SessionStatus.PROFILE_RESTRICTED,
                    identity,
                    profile,
                    context,
                    withDiagnostic(
                            context,
                            "Minecraft profile actions currently prohibit appearance changes.")));
        }
        if (requiredCapeId != null && !profile.ownsCape(requiredCapeId)) {
            SessionFailureContext context = new SessionFailureContext(
                    SessionCheckPhase.PROFILE, ApiFailureKind.FORBIDDEN, null);
            return new SessionValidation(
                    SessionStatus.PROFILE_RESTRICTED,
                    identity,
                    profile,
                    context,
                    withDiagnostic(
                            context,
                            "The selected cape is not present in the Minecraft profile snapshot."));
        }
        return remember(new SessionValidation(
                SessionStatus.VALID,
                identity,
                profile,
                (SessionFailureContext) null,
                "Minecraft session is valid."));
    }

    private SessionValidation withFreshToken(
            GameSessionTokenSource tokenSource,
            GameSessionTokenSource.SessionIdentity identity,
            boolean manualRetry) {
        try {
            SessionValidation result = tokenSource.withAccessToken(token -> validateScoped(token, identity, null));
            if (manualRetry && result.valid()) {
                gate.clearAfterSuccessfulManualRetry(identity.profileId());
            }
            return result;
        } catch (GameSessionTokenUnavailableException unavailable) {
            return rememberTokenUnavailable(identity);
        } catch (RuntimeException exception) {
            return rememberTokenSourceFailure(identity);
        }
    }


    public synchronized SessionValidation rememberTokenUnavailable(
            GameSessionTokenSource.SessionIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        SessionFailureContext context = new SessionFailureContext(
                SessionCheckPhase.TOKEN_SOURCE, ApiFailureKind.TOKEN_UNAVAILABLE, null);
        return remember(new SessionValidation(
                SessionStatus.OFFLINE_OR_INVALID,
                identity,
                null,
                context,
                withDiagnostic(
                        context,
                        "The running Minecraft session has no access token.")));
    }


    public synchronized SessionValidation rememberTokenSourceFailure(
            GameSessionTokenSource.SessionIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        SessionFailureContext context = new SessionFailureContext(
                SessionCheckPhase.TOKEN_SOURCE, ApiFailureKind.INVALID_SESSION, null);
        return remember(new SessionValidation(
                SessionStatus.OFFLINE_OR_INVALID,
                identity,
                null,
                context,
                withDiagnostic(
                        context,
                        "The running Minecraft session could not provide credentials.")));
    }

    private SessionValidation apiFailure(
            GameSessionTokenSource.SessionIdentity identity,
            RemoteProfile verifiedProfile,
            SessionCheckPhase phase,
            ProfileApiException exception) {
        Integer status = exception.statusCode().isPresent()
                ? exception.statusCode().getAsInt()
                : null;
        SessionFailureContext context = new SessionFailureContext(
                phase,
                exception.kind(),
                status,
                exception.responseSchemaCode().orElse(null),
                exception.retryAfter().orElse(null));
        if (exception.sessionExpired()) {
            gate.block(identity.profileId());
            return expired(identity, verifiedProfile, context);
        }
        return new SessionValidation(
                SessionStatus.OFFLINE_OR_INVALID,
                identity,
                verifiedProfile,
                context,
                withDiagnostic(
                        context,
                        phaseDisplayName(phase) + " check failed: " + exception.getMessage()));
    }

    synchronized void rememberVerifiedProfile(
            GameSessionTokenSource.SessionIdentity identity,
            RemoteProfile profile) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(profile, "profile");
        if (!identity.profileId().equals(profile.id())
                || gate.remoteControlsBlocked(identity.profileId())) {
            return;
        }
        remember(new SessionValidation(
                SessionStatus.VALID,
                identity,
                profile,
                (SessionFailureContext) null,
                "Minecraft session is valid."));
    }

    synchronized boolean acknowledgedSkinMatches(UUID profileId, ResolvedSkinAsset skin) {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(skin, "skin");
        AcknowledgedSkin acknowledged = acknowledgedSkins.get(profileId);
        return acknowledged != null
                && acknowledged.kind() == AcknowledgedSkinKind.LOCAL_ASSET
                && acknowledged.sha256().equals(skin.sha256())
                && acknowledged.variant() == skin.variant();
    }

    synchronized boolean hasAcknowledgedSkinState(UUID profileId) {
        return acknowledgedSkins.containsKey(Objects.requireNonNull(profileId, "profileId"));
    }

    synchronized boolean accountDefaultSkinAcknowledged(UUID profileId) {
        Objects.requireNonNull(profileId, "profileId");
        AcknowledgedSkin acknowledged = acknowledgedSkins.get(profileId);
        return acknowledged != null && acknowledged.kind() == AcknowledgedSkinKind.ACCOUNT_DEFAULT;
    }

    synchronized boolean skinStateUnknown(UUID profileId) {
        Objects.requireNonNull(profileId, "profileId");
        AcknowledgedSkin acknowledged = acknowledgedSkins.get(profileId);
        return acknowledged != null && acknowledged.kind() == AcknowledgedSkinKind.UNKNOWN;
    }

    synchronized boolean capeStateUnknown(UUID profileId) {
        return Boolean.TRUE.equals(unknownCapes.get(Objects.requireNonNull(profileId, "profileId")));
    }

    synchronized void rememberAppliedSkin(UUID profileId, ResolvedSkinAsset skin) {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(skin, "skin");
        acknowledgedSkins.put(
                profileId,
                new AcknowledgedSkin(AcknowledgedSkinKind.LOCAL_ASSET, skin.sha256(), skin.variant()));
    }

    synchronized void rememberAccountDefaultSkin(UUID profileId) {
        acknowledgedSkins.put(
                Objects.requireNonNull(profileId, "profileId"),
                new AcknowledgedSkin(AcknowledgedSkinKind.ACCOUNT_DEFAULT, null, null));
    }

    synchronized void rememberUnknownSkin(UUID profileId) {
        acknowledgedSkins.put(
                Objects.requireNonNull(profileId, "profileId"),
                new AcknowledgedSkin(AcknowledgedSkinKind.UNKNOWN, null, null));
    }

    synchronized void rememberKnownCape(UUID profileId) {
        unknownCapes.remove(Objects.requireNonNull(profileId, "profileId"));
    }

    synchronized void rememberManagedAppearance(UUID profileId) {
        managedAppearances.add(Objects.requireNonNull(profileId, "profileId"));
    }

    synchronized void rememberUnknownCape(UUID profileId) {
        unknownCapes.put(Objects.requireNonNull(profileId, "profileId"), Boolean.TRUE);
    }

    synchronized AppliedAppearance currentAppliedAppearance(UUID profileId, RemoteProfile profile) {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(profile, "profile");
        if (!profileId.equals(profile.id())) {
            throw new IllegalArgumentException("Profile snapshot UUID does not match the session");
        }
        AcknowledgedSkin acknowledged = acknowledgedSkins.get(profileId);
        if (acknowledged == null) {
            return AppliedAppearance.fromProfile(profile);
        }
        var capeTexture = profile.activeCape().map(com.naocraftlab.skins.core.model.RemoteCape::textureUri);
        return switch (acknowledged.kind()) {
            case LOCAL_ASSET -> AppliedAppearance.localSkin(
                    profileId,
                    acknowledged.sha256(),
                    acknowledged.variant(),
                    capeTexture);
            case ACCOUNT_DEFAULT -> AppliedAppearance.accountDefault(profileId, capeTexture);
            case UNKNOWN -> throw new IllegalStateException("Cannot materialize an unknown skin state");
        };
    }


    public synchronized AppliedAppearance currentAppliedAppearance(RemoteProfile profile) {
        Objects.requireNonNull(profile, "profile");
        return currentAppliedAppearance(profile.id(), profile);
    }


    public synchronized Optional<AppliedAppearance> acknowledgedAppearance(
            GameSessionTokenSource.SessionIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        UUID profileId = identity.profileId();
        if (!managedAppearances.contains(profileId)
                || skinStateUnknown(profileId)
                || capeStateUnknown(profileId)) {
            return Optional.empty();
        }
        RemoteProfile verifiedProfile = lastVerifiedProfiles.get(profileId);
        if (verifiedProfile == null || !profileId.equals(verifiedProfile.id())) {
            return Optional.empty();
        }
        return Optional.of(currentAppliedAppearance(profileId, verifiedProfile));
    }

    private synchronized SessionValidation remember(SessionValidation validation) {
        validationCache.put(validation.sessionIdentity().profileId(), validation);
        if (validation.valid()
                && validation.profile() != null
                && validation.sessionIdentity().profileId().equals(validation.profile().id())) {
            lastVerifiedProfiles.put(validation.sessionIdentity().profileId(), validation.profile());
        }
        return validation;
    }

    private static String phaseDisplayName(SessionCheckPhase phase) {
        return switch (phase) {
            case PROFILE -> "Minecraft profile";
            case TOKEN_SOURCE -> "Minecraft token source";
            case UNKNOWN -> "Minecraft session";
        };
    }

    private static String withDiagnostic(SessionFailureContext context, String message) {
        return "[" + context.safeDiagnostic() + "] " + message;
    }

    private static SessionValidation expired(GameSessionTokenSource.SessionIdentity identity) {
        SessionFailureContext context = new SessionFailureContext(
                SessionCheckPhase.UNKNOWN, ApiFailureKind.SESSION_EXPIRED, null);
        return new SessionValidation(
                SessionStatus.EXPIRED,
                identity,
                null,
                context,
                "Minecraft session expired. Restart the game through a licensed launcher.");
    }

    private static SessionValidation uncheckedOffline(
            GameSessionTokenSource.SessionIdentity identity) {
        SessionFailureContext context = new SessionFailureContext(
                SessionCheckPhase.TOKEN_SOURCE, ApiFailureKind.INVALID_SESSION, null);
        return new SessionValidation(
                SessionStatus.OFFLINE_OR_INVALID,
                identity,
                null,
                context,
                withDiagnostic(
                        context,
                        "Minecraft credentials have not been checked in this process."));
    }

    private static SessionValidation expired(
            GameSessionTokenSource.SessionIdentity identity,
            RemoteProfile verifiedProfile,
            SessionFailureContext context) {
        return new SessionValidation(
                SessionStatus.EXPIRED,
                identity,
                verifiedProfile,
                context,
                withDiagnostic(
                        context,
                        "Minecraft session expired. Restart the game through a licensed launcher."));
    }

    private enum AcknowledgedSkinKind {
        LOCAL_ASSET,
        ACCOUNT_DEFAULT,
        UNKNOWN
    }

    private record AcknowledgedSkin(
            AcknowledgedSkinKind kind,
            String sha256,
            SkinVariant variant) {
        private AcknowledgedSkin {
            Objects.requireNonNull(kind, "kind");
            if (kind == AcknowledgedSkinKind.LOCAL_ASSET) {
                Objects.requireNonNull(sha256, "sha256");
                Objects.requireNonNull(variant, "variant");
            } else if (sha256 != null || variant != null) {
                throw new IllegalArgumentException("Only a local acknowledged skin carries content metadata");
            }
        }
    }
}
