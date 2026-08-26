package com.naocraftlab.skins.core.service;

import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.GameSessionTokenUnavailableException;
import com.naocraftlab.skins.core.api.ApiFailureKind;
import com.naocraftlab.skins.core.api.ProfileApi;
import com.naocraftlab.skins.core.api.ProfileApiException;
import com.naocraftlab.skins.core.api.ResponseSchemaCode;
import com.naocraftlab.skins.core.model.RemoteAssetState;
import com.naocraftlab.skins.core.model.RemoteCape;
import com.naocraftlab.skins.core.model.RemoteProfile;
import com.naocraftlab.skins.core.model.SkinVariant;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionValidationServiceTest {
    private static final UUID ID = UUID.fromString("12345678-1234-5678-9abc-def012345678");
    private static final String SECRET = "secret-session-value";

    @Test
    void validatesMatchingProfileAndNeverPlacesTokenInResult() {
        StubApi api = new StubApi(profile(ID));
        StubTokens tokens = new StubTokens(SECRET);
        SessionValidation result = new SessionValidationService(api, new RemoteSessionGate()).currentStatus(tokens);

        assertEquals(SessionStatus.VALID, result.status());
        assertEquals(1, tokens.calls);
        assertEquals(1, api.profileCalls);
        assertFalse(result.toString().contains(SECRET));
    }

    @Test
    void reusesProcessValidationAcrossScreenReopens() {
        StubApi api = new StubApi(profile(ID));
        StubTokens tokens = new StubTokens(SECRET);
        SessionValidationService service = new SessionValidationService(api, new RemoteSessionGate());

        assertTrue(service.currentStatus(tokens).valid());
        assertTrue(service.currentStatus(tokens).valid());

        assertEquals(1, tokens.calls);
        assertEquals(1, api.profileCalls);
    }

    @Test
    void cachedStatusNeverRequestsTokenOrProfileWhenNoValidationExists() {
        StubApi api = new StubApi(profile(ID));
        StubTokens tokens = new StubTokens(SECRET);
        SessionValidationService service = new SessionValidationService(api, new RemoteSessionGate());

        SessionValidation cached = service.cachedStatus(tokens.currentSession());

        assertFalse(cached.valid());
        assertEquals(SessionStatus.OFFLINE_OR_INVALID, cached.status());
        assertEquals(0, tokens.calls);
        assertEquals(0, api.profileCalls);
    }

    @Test
    void transientFailureRetriesOnceAtCheckpoint() {
        StubApi api = new StubApi(profile(ID));
        api.profileFailure = new ProfileApiException(
                ApiFailureKind.NETWORK, "offline", null, null, false);
        StubTokens tokens = new StubTokens(SECRET);
        SessionValidationService service = new SessionValidationService(api, new RemoteSessionGate());
        assertFalse(service.currentStatus(tokens).valid());
        api.profileFailure = null;

        SessionValidation recovered = service.retryTransientAtCheckpoint(tokens);

        assertTrue(recovered.valid());
        assertEquals(2, tokens.calls);
        assertEquals(2, api.profileCalls);
    }

    @Test
    void validCacheIsObservedFreshAtAutomaticCheckpoint() {
        StubApi api = new StubApi(profile(ID));
        StubTokens tokens = new StubTokens(SECRET);
        SessionValidationService service = new SessionValidationService(api, new RemoteSessionGate());
        assertTrue(service.currentStatus(tokens).valid());

        SessionValidation checkpoint = service.retryTransientAtCheckpoint(tokens);

        assertTrue(checkpoint.valid());
        assertEquals(2, tokens.calls);
        assertEquals(2, api.profileCalls);
    }

    @Test
    void terminalUuidMismatchDoesNotRetryAtAutomaticCheckpoint() {
        StubApi api = new StubApi(profile(UUID.randomUUID()));
        StubTokens tokens = new StubTokens(SECRET);
        SessionValidationService service = new SessionValidationService(api, new RemoteSessionGate());
        assertEquals(SessionStatus.UUID_MISMATCH, service.currentStatus(tokens).status());

        SessionValidation repeated = service.retryTransientAtCheckpoint(tokens);

        assertEquals(SessionStatus.UUID_MISMATCH, repeated.status());
        assertEquals(1, tokens.calls);
        assertEquals(1, api.profileCalls);
    }

    @Test
    void mutationPreflightReusesValidProfileSnapshot() {
        StubApi api = new StubApi(profile(ID));
        StubTokens tokens = new StubTokens(SECRET);
        SessionValidationService service = new SessionValidationService(api, new RemoteSessionGate());

        assertTrue(service.currentStatus(tokens).valid());
        assertTrue(service.cachedOrValidateScoped(SECRET, tokens.currentSession(), null).valid());

        assertEquals(1, api.profileCalls);
    }

    @Test
    void cachedMutationValidationFetchesProfileOnlyWhenSnapshotIsMissing() {
        StubApi api = new StubApi(profile(ID));
        StubTokens tokens = new StubTokens(SECRET);
        SessionValidationService service = new SessionValidationService(api, new RemoteSessionGate());

        assertTrue(service.cachedOrValidateScoped(SECRET, tokens.currentSession(), null).valid());
        assertTrue(service.cachedOrValidateScoped(SECRET, tokens.currentSession(), null).valid());

        assertEquals(1, api.profileCalls);
    }

    @Test
    void cachedMutationValidationChecksCapeOwnershipWithoutNetwork() {
        StubApi api = new StubApi(profile(ID));
        StubTokens tokens = new StubTokens(SECRET);
        SessionValidationService service = new SessionValidationService(api, new RemoteSessionGate());
        service.rememberVerifiedProfile(tokens.currentSession(), profile(ID));

        SessionValidation result =
                service.cachedOrValidateScoped(SECRET, tokens.currentSession(), "not-owned");

        assertEquals(SessionStatus.PROFILE_RESTRICTED, result.status());
        assertEquals(0, api.profileCalls);
        assertFalse(result.toString().contains(SECRET));
    }

    @Test
    void cachedMutationValidationAcceptsOwnedCapeWithoutNetwork() {
        RemoteProfile profile = profileWithCape(ID, "owned-cape");
        StubApi api = new StubApi(profile);
        StubTokens tokens = new StubTokens(SECRET);
        SessionValidationService service = new SessionValidationService(api, new RemoteSessionGate());
        service.rememberVerifiedProfile(tokens.currentSession(), profile);

        SessionValidation result =
                service.cachedOrValidateScoped(SECRET, tokens.currentSession(), "owned-cape");

        assertTrue(result.valid());
        assertEquals(0, api.profileCalls);
        assertFalse(result.toString().contains(SECRET));
    }

    @Test
    void manualRetryForcesOnlyProfileRefresh() {
        StubApi api = new StubApi(profile(ID));
        StubTokens tokens = new StubTokens(SECRET);
        SessionValidationService service = new SessionValidationService(api, new RemoteSessionGate());

        assertTrue(service.currentStatus(tokens).valid());
        assertTrue(service.manualRetry(tokens).valid());

        assertEquals(2, api.profileCalls);
    }

    @Test
    void failedManualRetryPreservesAcknowledgedAppearanceCache() {
        RemoteProfile verified = profileWithCape(ID, "owned-cape");
        StubApi api = new StubApi(verified);
        StubTokens tokens = new StubTokens(SECRET);
        SessionValidationService service = new SessionValidationService(api, new RemoteSessionGate());
        assertTrue(service.currentStatus(tokens).valid());
        service.rememberManagedAppearance(ID);
        assertTrue(service.acknowledgedAppearance(tokens.currentSession()).isPresent());
        api.profileFailure = new ProfileApiException(
                ApiFailureKind.NETWORK, "still offline", null, null, false);

        assertFalse(service.manualRetry(tokens).valid());

        assertTrue(service.acknowledgedAppearance(tokens.currentSession()).isPresent());
        assertEquals(2, api.profileCalls);
    }

    @Test
    void unavailableTokenSourceNeverCallsProfileApi() {
        StubApi api = new StubApi(profile(ID));
        GameSessionTokenSource missing = new GameSessionTokenSource() {
            @Override
            public SessionIdentity currentSession() {
                return new SessionIdentity(ID, "Player");
            }

            @Override
            public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) {
                throw new GameSessionTokenUnavailableException();
            }
        };

        SessionValidation result =
                new SessionValidationService(api, new RemoteSessionGate()).currentStatus(missing);

        assertEquals(SessionStatus.OFFLINE_OR_INVALID, result.status());
        assertEquals(ApiFailureKind.TOKEN_UNAVAILABLE, result.failureKind());
        assertTrue(result.tokenUnavailable());
        assertTrue(result.restartRequired());
        assertEquals(0, api.profileCalls);
    }

    @Test
    void offlineLauncherSentinelNeverCallsProfileApi() {
        StubApi api = new StubApi(profile(ID));
        StubTokens offline = new StubTokens("0");

        SessionValidation result =
                new SessionValidationService(api, new RemoteSessionGate()).currentStatus(offline);

        assertEquals(SessionStatus.OFFLINE_OR_INVALID, result.status());
        assertEquals(ApiFailureKind.TOKEN_UNAVAILABLE, result.failureKind());
        assertTrue(result.tokenUnavailable());
        assertEquals(1, offline.calls);
        assertEquals(0, api.profileCalls);
    }

    @Test
    void reportsEmptyTokenAsOfflineInvalidWithoutEchoingIt() {
        StubApi api = new StubApi(profile(ID));
        StubTokens tokens = new StubTokens("");
        SessionValidation result = new SessionValidationService(api, new RemoteSessionGate()).currentStatus(tokens);

        assertEquals(SessionStatus.OFFLINE_OR_INVALID, result.status());
        assertEquals(ApiFailureKind.INVALID_SESSION, result.failureKind());
    }

    @Test
    void reportsUuidMismatchAfterProfileLookup() {
        StubApi api = new StubApi(profile(UUID.randomUUID()));
        SessionValidation result = new SessionValidationService(api, new RemoteSessionGate())
                .currentStatus(new StubTokens(SECRET));

        assertEquals(SessionStatus.UUID_MISMATCH, result.status());
    }

    @Test
    void expiredGateDoesNotRequestTokenAgainUntilManualRetry() {
        StubApi api = new StubApi(profile(ID));
        api.profileFailure = new ProfileApiException(
                ApiFailureKind.SESSION_EXPIRED,
                "session expired",
                401,
                null,
                false);
        StubTokens tokens = new StubTokens(SECRET);
        RemoteSessionGate gate = new RemoteSessionGate();
        SessionValidationService service = new SessionValidationService(api, gate);

        assertEquals(SessionStatus.EXPIRED, service.currentStatus(tokens).status());
        assertTrue(service.currentStatus(tokens).restartRequired());
        assertEquals(1, tokens.calls);

        api.profileFailure = null;
        assertTrue(service.manualRetry(tokens).valid());
        assertEquals(2, tokens.calls);
        assertFalse(gate.remoteControlsBlocked(ID));
    }

    @Test
    void reportsSafeProfilePhaseKindAndStatusWithoutCredentialMaterial() {
        StubApi api = new StubApi(profile(ID));
        api.profileFailure = new ProfileApiException(
                ApiFailureKind.FORBIDDEN,
                "Minecraft profile service denied this operation.",
                403,
                null,
                false);

        SessionValidation result = new SessionValidationService(api, new RemoteSessionGate())
                .currentStatus(new StubTokens(SECRET));

        assertFalse(result.valid());
        SessionFailureContext context = result.failureContext();
        assertNotNull(context);
        assertEquals(SessionCheckPhase.PROFILE, context.phase());
        assertEquals(ApiFailureKind.FORBIDDEN, context.kind());
        assertEquals(403, context.httpStatusCode());
        assertTrue(result.userMessage().startsWith("[phase=profile"));
        assertTrue(result.userMessage().contains("kind=FORBIDDEN"));
        assertTrue(result.userMessage().contains("http=403"));
        assertFalse(result.userMessage().contains(SECRET));
        assertFalse(result.toString().contains(SECRET));
    }

    @Test
    void propagatesAllowlistedSchemaCodeWithoutResponseOrCredentialValues() {
        StubApi api = new StubApi(profile(ID));
        api.profileFailure = new ProfileApiException(
                ApiFailureKind.INVALID_RESPONSE,
                "Minecraft profile response is invalid.",
                null,
                null,
                false,
                ResponseSchemaCode.PROFILE_ACTIONS);

        SessionValidation result = new SessionValidationService(api, new RemoteSessionGate())
                .currentStatus(new StubTokens(SECRET));

        SessionFailureContext context = result.failureContext();
        assertNotNull(context);
        assertEquals(
                ResponseSchemaCode.PROFILE_ACTIONS,
                context.responseSchemaCode());
        assertEquals(
                "phase=profile, kind=INVALID_RESPONSE, http=none, schema=profile.profile-actions",
                context.safeDiagnostic());
        assertTrue(result.userMessage().startsWith("[phase=profile"));
        assertTrue(result.userMessage().contains("schema=profile.profile-actions"));
        assertFalse(result.userMessage().contains(SECRET));
        assertFalse(result.toString().contains(SECRET));
    }

    @Test
    void preservesRateLimitCooldownWithoutCredentialMaterial() {
        StubApi api = new StubApi(profile(ID));
        api.profileFailure = new ProfileApiException(
                ApiFailureKind.RATE_LIMITED,
                "rate limited",
                429,
                Duration.ofSeconds(60),
                false);

        SessionValidationService service = new SessionValidationService(api, new RemoteSessionGate());
        StubTokens tokens = new StubTokens(SECRET);
        SessionValidation result = service.currentStatus(tokens);
        SessionValidation repeatedOpen = service.currentStatus(tokens);

        SessionFailureContext context = result.failureContext();
        assertNotNull(context);
        assertEquals(Duration.ofSeconds(60), context.retryAfter());
        assertTrue(context.safeDiagnostic().contains("retry_after_seconds=60"));
        assertEquals(result, repeatedOpen);
        assertEquals(1, api.profileCalls);
        assertEquals(1, tokens.calls);
        assertFalse(result.toString().contains(SECRET));
    }

    @Test
    void automaticCheckpointWaitsUntilRateLimitCooldownExpires() {
        StubApi api = new StubApi(profile(ID));
        api.profileFailure = new ProfileApiException(
                ApiFailureKind.RATE_LIMITED,
                "rate limited",
                429,
                Duration.ofSeconds(60),
                false);
        api.rateLimitRemaining = Optional.of(Duration.ofSeconds(60));
        StubTokens tokens = new StubTokens(SECRET);
        SessionValidationService service = new SessionValidationService(api, new RemoteSessionGate());
        assertFalse(service.currentStatus(tokens).valid());

        assertFalse(service.retryTransientAtCheckpoint(tokens).valid());
        assertEquals(1, tokens.calls);
        assertEquals(1, api.profileCalls);

        api.rateLimitRemaining = Optional.empty();
        api.profileFailure = null;
        assertTrue(service.retryTransientAtCheckpoint(tokens).valid());
        assertEquals(2, tokens.calls);
        assertEquals(2, api.profileCalls);
    }

    @Test
    void rememberVerifiedProfileSeedsValidationCache() {
        RemoteProfile verified = profile(ID);
        StubApi api = new StubApi(verified);
        RemoteSessionGate gate = new RemoteSessionGate();
        SessionValidationService service = new SessionValidationService(api, gate);
        StubTokens tokens = new StubTokens(SECRET);

        service.rememberVerifiedProfile(tokens.currentSession(), verified);
        SessionValidation result = service.currentStatus(tokens);

        assertTrue(result.valid());
        assertEquals(verified, result.profile());
        assertEquals(0, tokens.calls);
        assertEquals(0, api.profileCalls);
        assertFalse(result.toString().contains(SECRET));
    }

    @Test
    void reconnectAppearanceIsCacheOnlyAndRequiresAnAcknowledgedMutation() {
        RemoteProfile verified = profileWithCape(ID, "owned-cape");
        StubApi api = new StubApi(verified);
        SessionValidationService service = new SessionValidationService(api, new RemoteSessionGate());
        StubTokens tokens = new StubTokens(SECRET);

        assertTrue(service.currentStatus(tokens).valid());
        assertTrue(service.acknowledgedAppearance(tokens.currentSession()).isEmpty());

        service.rememberManagedAppearance(ID);
        AppliedAppearance appearance =
                service.acknowledgedAppearance(tokens.currentSession()).orElseThrow();

        assertEquals(verified.activeCape().orElseThrow().textureUri(), appearance.capeTexture().orElseThrow());
        assertEquals(1, api.profileCalls);
        assertEquals(1, tokens.calls);
    }

    @Test
    void reconnectAppearanceWaitsForManualResolutionOfAnUnknownMutation() {
        StubApi api = new StubApi(profile(ID));
        SessionValidationService service = new SessionValidationService(api, new RemoteSessionGate());
        StubTokens tokens = new StubTokens(SECRET);
        assertTrue(service.currentStatus(tokens).valid());

        service.rememberManagedAppearance(ID);
        service.rememberUnknownCape(ID);
        assertTrue(service.acknowledgedAppearance(tokens.currentSession()).isEmpty());

        assertTrue(service.manualRetry(tokens).valid());
        assertTrue(service.acknowledgedAppearance(tokens.currentSession()).isPresent());
        assertEquals(2, api.profileCalls);
    }

    private static RemoteProfile profile(UUID id) {
        return new RemoteProfile(id, "Player", List.of(), List.of(), Set.of());
    }

    private static RemoteProfile profileWithCape(UUID id, String capeId) {
        RemoteCape cape = new RemoteCape(
                capeId,
                RemoteAssetState.ACTIVE,
                URI.create("https://textures.minecraft.net/texture/cape"),
                null);
        return new RemoteProfile(id, "Player", List.of(), List.of(cape), Set.of());
    }

    private static final class StubTokens implements GameSessionTokenSource {
        private final String token;
        private int calls;

        private StubTokens(String token) {
            this.token = token;
        }

        @Override
        public SessionIdentity currentSession() {
            return new SessionIdentity(ID, "Player");
        }

        @Override
        public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) throws E {
            calls++;
            return request.execute(token);
        }
    }

    private static final class StubApi implements ProfileApi {
        private final RemoteProfile profile;
        private ProfileApiException profileFailure;
        private Optional<Duration> rateLimitRemaining = Optional.empty();
        private int profileCalls;

        private StubApi(RemoteProfile profile) {
            this.profile = profile;
        }

        @Override
        public RemoteProfile getProfile(String accessToken) throws ProfileApiException {
            profileCalls++;
            rejectEmpty(accessToken);
            if (profileFailure != null) {
                throw profileFailure;
            }
            return profile;
        }

        @Override
        public void uploadSkin(String accessToken, SkinVariant variant, byte[] pngBytes) {}

        @Override
        public void resetSkin(String accessToken) {}

        @Override
        public void activateCape(String accessToken, String capeId) {}

        @Override
        public void deactivateCape(String accessToken) {}

        @Override
        public Optional<Duration> rateLimitRemaining() {
            return rateLimitRemaining;
        }

        private static void rejectEmpty(String token) throws ProfileApiException {
            if (token == null || token.isBlank()) {
                throw new ProfileApiException(
                        ApiFailureKind.INVALID_SESSION,
                        "no token",
                        null,
                        null,
                        false);
            }
        }
    }
}
