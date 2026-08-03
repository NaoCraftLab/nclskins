package com.naocraftlab.skins.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import com.naocraftlab.skins.core.storage.TextureCache;
import com.naocraftlab.skins.core.test.TestPng;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppearanceMutationServiceTest {
    private static final UUID PROFILE_ID = UUID.fromString("12345678-1234-5678-9abc-def012345678");
    private static final Instant NOW = Instant.parse("2026-07-29T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void initialFullChangeUsesOneProfileGetAndOnlyTheTwoMutations() throws Exception {
        ScriptedApi api = new ScriptedApi(profile(
                PROFILE_ID, "old", SkinVariant.CLASSIC, null, "cape-a"));
        FakeTokenSource tokens = new FakeTokenSource();
        Services services = services(api, new RemoteSessionGate());
        ResolvedSkinAsset skin = resolvedSkin();

        PresetApplicationOutcome outcome =
                services.mutations().applyPreset(tokens, request(skin, "cape-a"));

        assertEquals(MutationResult.APPLIED, outcome.result());
        assertEquals(RemoteAppearanceImpact.CONFIRMED_CHANGED, outcome.remoteAppearanceImpact());
        assertEquals(List.of("GET_PROFILE", "UPLOAD_SKIN", "ACTIVATE_CAPE"), api.operations);
        assertEquals("cape-a", outcome.afterProfile().activeCape().orElseThrow().id());
        assertEquals(skin.sha256(), outcome.appliedAppearance().localSkinSha256().orElseThrow());
        assertEquals(1, tokens.tokenRequests);
        assertFalse(outcome.toString().contains(tokens.token));
    }

    @Test
    void supersededDurableIntentStopsBeforeProfileOrMutationRequests() throws Exception {
        ScriptedApi api = new ScriptedApi(profile(
                PROFILE_ID, "old", SkinVariant.CLASSIC, null, "cape-a"));
        FakeTokenSource tokens = new FakeTokenSource();
        Services services = services(api, new RemoteSessionGate());

        PresetApplicationOutcome outcome = services.mutations().applyPreset(
                tokens, request(resolvedSkin(), "cape-a"), () -> false);

        assertEquals(MutationResult.FAILED, outcome.result());
        assertEquals(RemoteAppearanceImpact.NONE, outcome.remoteAppearanceImpact());
        assertTrue(api.operations.isEmpty());
        assertEquals(0, tokens.tokenRequests);
    }

    @Test
    void capeOnlyChangeFreshlyValidatesThenSendsOnlyCapeMutation() throws Exception {
        ResolvedSkinAsset skin = resolvedSkin();
        RemoteProfile current = profile(
                PROFILE_ID, "current", SkinVariant.SLIM, null, "cape-a");
        ScriptedApi api = new ScriptedApi(current);
        FakeTokenSource tokens = new FakeTokenSource();
        Services services = services(api, new RemoteSessionGate());
        services.sessions().rememberVerifiedProfile(tokens.currentSession(), current);
        cacheActiveSkin(services.storage(), current, skin.pngBytes());

        PresetApplicationOutcome outcome =
                services.mutations().applyPreset(tokens, request(skin, "cape-a"));

        assertEquals(MutationResult.APPLIED, outcome.result());
        assertEquals(RemoteAppearanceImpact.CONFIRMED_CHANGED, outcome.remoteAppearanceImpact());
        assertEquals(List.of("GET_PROFILE", "ACTIVATE_CAPE"), api.operations);
        assertEquals("cape-a", outcome.afterProfile().activeCape().orElseThrow().id());
        assertEquals(skin.sha256(), outcome.appliedAppearance().localSkinSha256().orElseThrow());
    }

    @Test
    void consecutiveCapeChangesSendExactlyOneMutationEach() throws Exception {
        ResolvedSkinAsset skin = resolvedSkin();
        RemoteProfile current = profile(
                PROFILE_ID, "current", SkinVariant.SLIM, null, "cape-a", "cape-b");
        ScriptedApi api = new ScriptedApi(current);
        FakeTokenSource tokens = new FakeTokenSource();
        Services services = services(api, new RemoteSessionGate());
        services.sessions().rememberVerifiedProfile(tokens.currentSession(), current);
        cacheActiveSkin(services.storage(), current, skin.pngBytes());

        PresetApplicationOutcome first =
                services.mutations().applyPreset(tokens, request(skin, "cape-a"));
        PresetApplicationOutcome second =
                services.mutations().applyPreset(tokens, request(skin, "cape-b"));

        assertEquals(MutationResult.APPLIED, first.result());
        assertEquals(MutationResult.APPLIED, second.result());
        assertEquals(
                List.of("GET_PROFILE", "ACTIVATE_CAPE", "GET_PROFILE", "ACTIVATE_CAPE"),
                api.operations);
        assertEquals(List.of("cape-a", "cape-b"), api.activatedCapeIds);
        assertEquals("cape-b", second.afterProfile().activeCape().orElseThrow().id());
    }

    @Test
    void exactNoOpFreshlyValidatesTokenButSendsNoMutation() throws Exception {
        ResolvedSkinAsset skin = resolvedSkin();
        RemoteProfile current = profile(
                PROFILE_ID, "current", SkinVariant.SLIM, "cape-a", "cape-a");
        ScriptedApi api = new ScriptedApi(current);
        FakeTokenSource tokens = new FakeTokenSource();
        Services services = services(api, new RemoteSessionGate());
        services.sessions().rememberVerifiedProfile(tokens.currentSession(), current);
        cacheActiveSkin(services.storage(), current, skin.pngBytes());

        PresetApplicationOutcome outcome =
                services.mutations().applyPreset(tokens, request(skin, "cape-a"));

        assertEquals(MutationResult.APPLIED, outcome.result());
        assertEquals(RemoteAppearanceImpact.NONE, outcome.remoteAppearanceImpact());
        assertEquals(List.of("GET_PROFILE"), api.operations);
        assertEquals(1, tokens.tokenRequests);
        assertTrue(outcome.userMessage().contains("already active"));
    }

    @Test
    void acknowledgedSkinSupersedesTheStaleStartupRemoteSkin() throws Exception {
        ResolvedSkinAsset startupSkin = resolvedSkin();
        ResolvedSkinAsset replacement = resolvedSkin(TestPng.create(64, 32), SkinVariant.CLASSIC);
        RemoteProfile current = profile(
                PROFILE_ID, "startup", SkinVariant.SLIM, null, "cape-a");
        ScriptedApi api = new ScriptedApi(current);
        FakeTokenSource tokens = new FakeTokenSource();
        Services services = services(api, new RemoteSessionGate());
        services.sessions().rememberVerifiedProfile(tokens.currentSession(), current);
        cacheActiveSkin(services.storage(), current, startupSkin.pngBytes());

        PresetApplicationOutcome first = services.mutations().applySkinOnly(tokens, replacement);
        PresetApplicationOutcome restore = services.mutations().applySkinOnly(tokens, startupSkin);

        assertEquals(MutationResult.APPLIED, first.result());
        assertEquals(MutationResult.APPLIED, restore.result());
        assertEquals(
                List.of("GET_PROFILE", "UPLOAD_SKIN", "GET_PROFILE", "UPLOAD_SKIN"),
                api.operations);
    }

    @Test
    void successfulSkinMutationUsesLocalHashAndDoesNotVerifyWithGet() throws Exception {
        ScriptedApi api = new ScriptedApi(profile(
                PROFILE_ID, "old", SkinVariant.CLASSIC, null, "cape-a"));
        Services services = services(api, new RemoteSessionGate());
        ResolvedSkinAsset skin = resolvedSkin();

        PresetApplicationOutcome outcome =
                services.mutations().applySkinOnly(new FakeTokenSource(), skin);

        assertEquals(MutationResult.APPLIED, outcome.result());
        assertEquals(List.of("GET_PROFILE", "UPLOAD_SKIN"), api.operations);
        AppliedAppearance appearance = outcome.optionalAppliedAppearance().orElseThrow();
        assertTrue(appearance.localSkinSha256().isPresent());
        assertTrue(appearance.skinTexture().isEmpty());
        assertEquals(skin.sha256(), appearance.localSkinSha256().orElseThrow());
        assertEquals(SkinVariant.SLIM, appearance.skinVariant().orElseThrow());
    }

    @Test
    void forcedAccountDefaultResetBypassesTheProcessLocalNoOpAcknowledgement() throws Exception {
        ScriptedApi api = new ScriptedApi(profile(
                PROFILE_ID, "custom", SkinVariant.CLASSIC, null, "cape-a"));
        FakeTokenSource tokens = new FakeTokenSource();
        Services services = services(api, new RemoteSessionGate());

        PresetApplicationOutcome first = services.mutations().applySkinOnly(tokens, null);
        PresetApplicationOutcome cachedNoOp = services.mutations().applySkinOnly(tokens, null);
        PresetApplicationOutcome forced = services.mutations().forceApplySkinOnly(tokens, null);

        assertEquals(MutationResult.APPLIED, first.result());
        assertEquals(MutationResult.APPLIED, cachedNoOp.result());
        assertEquals(MutationResult.APPLIED, forced.result());
        assertEquals(RemoteAppearanceImpact.CONFIRMED_CHANGED, first.remoteAppearanceImpact());
        assertEquals(RemoteAppearanceImpact.NONE, cachedNoOp.remoteAppearanceImpact());
        assertEquals(RemoteAppearanceImpact.CONFIRMED_CHANGED, forced.remoteAppearanceImpact());
        assertEquals(
                List.of(
                        "GET_PROFILE",
                        "RESET_SKIN",
                        "GET_PROFILE",
                        "GET_PROFILE",
                        "RESET_SKIN"),
                api.operations);
    }

    @Test
    void uncertainSkinMutationReturnsUnknownWithoutGetOrRetry() throws Exception {
        ScriptedApi api = new ScriptedApi(profile(
                PROFILE_ID, "old", SkinVariant.CLASSIC, null, "cape-a"));
        api.skinFailure = failure(ApiFailureKind.NETWORK, true);
        FakeTokenSource tokens = new FakeTokenSource();
        Services services = services(api, new RemoteSessionGate());
        services.sessions().rememberVerifiedProfile(tokens.currentSession(), api.current);
        ResolvedSkinAsset skin = resolvedSkin();

        PresetApplicationOutcome outcome =
                services.mutations().applyPreset(tokens, request(skin, null));
        PresetApplicationOutcome blockedRepeat =
                services.mutations().applyPreset(tokens, request(skin, null));

        assertEquals(MutationResult.UNKNOWN, outcome.result());
        assertEquals(MutationResult.FAILED, blockedRepeat.result());
        assertEquals(RemoteAppearanceImpact.UNCERTAIN, outcome.remoteAppearanceImpact());
        assertEquals(RemoteAppearanceImpact.NONE, blockedRepeat.remoteAppearanceImpact());
        assertEquals(List.of("GET_PROFILE", "UPLOAD_SKIN"), api.operations);
        assertTrue(outcome.recoveryActions().contains(RecoveryAction.REFRESH_REMOTE_PROFILE));
    }

    @Test
    void uncertainCapeMutationReturnsUnknownWithoutGetOrRetry() throws Exception {
        ResolvedSkinAsset skin = resolvedSkin();
        RemoteProfile current = profile(
                PROFILE_ID, "current", SkinVariant.SLIM, null, "cape-a");
        ScriptedApi api = new ScriptedApi(current);
        api.capeFailure = failure(ApiFailureKind.SERVER_ERROR, true);
        FakeTokenSource tokens = new FakeTokenSource();
        Services services = services(api, new RemoteSessionGate());
        services.sessions().rememberVerifiedProfile(tokens.currentSession(), current);
        cacheActiveSkin(services.storage(), current, skin.pngBytes());

        PresetApplicationOutcome outcome =
                services.mutations().applyPreset(tokens, request(skin, "cape-a"));
        PresetApplicationOutcome blockedRepeat =
                services.mutations().applyPreset(tokens, request(skin, "cape-a"));

        assertEquals(MutationResult.UNKNOWN, outcome.result());
        assertEquals(MutationResult.FAILED, blockedRepeat.result());
        assertEquals(RemoteAppearanceImpact.UNCERTAIN, outcome.remoteAppearanceImpact());
        assertEquals(RemoteAppearanceImpact.NONE, blockedRepeat.remoteAppearanceImpact());
        assertEquals(List.of("GET_PROFILE", "ACTIVATE_CAPE"), api.operations);
        assertTrue(outcome.recoveryActions().contains(RecoveryAction.REFRESH_REMOTE_PROFILE));
    }

    @Test
    void definiteSkinFailureHasNoRemoteAppearanceImpact() throws Exception {
        RemoteProfile current = profile(
                PROFILE_ID, "current", SkinVariant.CLASSIC, null, "cape-a");
        ScriptedApi api = new ScriptedApi(current);
        api.skinFailure = failure(ApiFailureKind.FORBIDDEN, false);
        FakeTokenSource tokens = new FakeTokenSource();
        Services services = services(api, new RemoteSessionGate());
        services.sessions().rememberVerifiedProfile(tokens.currentSession(), current);

        PresetApplicationOutcome outcome =
                services.mutations().applySkinOnly(tokens, resolvedSkin());

        assertEquals(MutationResult.FAILED, outcome.result());
        assertEquals(RemoteAppearanceImpact.NONE, outcome.remoteAppearanceImpact());
        assertEquals(List.of("GET_PROFILE", "UPLOAD_SKIN"), api.operations);
    }

    @Test
    void definiteCapeFailureWithoutSkinChangeHasNoRemoteAppearanceImpact() throws Exception {
        ResolvedSkinAsset skin = resolvedSkin();
        RemoteProfile current = profile(
                PROFILE_ID, "current", SkinVariant.SLIM, null, "cape-a");
        ScriptedApi api = new ScriptedApi(current);
        api.capeFailure = failure(ApiFailureKind.FORBIDDEN, false);
        FakeTokenSource tokens = new FakeTokenSource();
        Services services = services(api, new RemoteSessionGate());
        services.sessions().rememberVerifiedProfile(tokens.currentSession(), current);
        cacheActiveSkin(services.storage(), current, skin.pngBytes());

        PresetApplicationOutcome outcome =
                services.mutations().applyPreset(tokens, request(skin, "cape-a"));

        assertEquals(MutationResult.FAILED, outcome.result());
        assertEquals(RemoteAppearanceImpact.NONE, outcome.remoteAppearanceImpact());
        assertEquals(List.of("GET_PROFILE", "ACTIVATE_CAPE"), api.operations);
    }

    @Test
    void reportsPartialAndOffersCapeOnlyRecovery() throws Exception {
        ScriptedApi api = new ScriptedApi(profile(
                PROFILE_ID, "old", SkinVariant.CLASSIC, null, "cape-a"));
        api.capeFailure = failure(ApiFailureKind.FORBIDDEN, false);
        Services services = services(api, new RemoteSessionGate());

        PresetApplicationOutcome outcome = services
                .mutations()
                .applyPreset(new FakeTokenSource(), request(resolvedSkin(), "cape-a"));

        assertEquals(MutationResult.PARTIAL, outcome.result());
        assertEquals(RemoteAppearanceImpact.CONFIRMED_CHANGED, outcome.remoteAppearanceImpact());
        assertEquals(ApplicationPhase.CAPE_MUTATION, outcome.phase());
        assertEquals(List.of("GET_PROFILE", "UPLOAD_SKIN", "ACTIVATE_CAPE"), api.operations);
        assertEquals(Set.of(RecoveryAction.RETRY_CAPE), outcome.recoveryActions());
    }

    @Test
    void first401BlocksRemoteControlsUntilManualRetryReacquiresToken() throws Exception {
        ScriptedApi api = new ScriptedApi(profile(
                PROFILE_ID, "old", SkinVariant.CLASSIC, null, "cape-a"));
        api.profileFailure = failure(ApiFailureKind.SESSION_EXPIRED, false);
        FakeTokenSource tokens = new FakeTokenSource();
        RemoteSessionGate gate = new RemoteSessionGate();
        Services services = services(api, gate);

        PresetApplicationOutcome first =
                services.mutations().applyPreset(tokens, request(resolvedSkin(), null));
        PresetApplicationOutcome blocked =
                services.mutations().applyPreset(tokens, request(resolvedSkin(), null));

        assertEquals(MutationResult.SESSION_EXPIRED, first.result());
        assertEquals(MutationResult.SESSION_EXPIRED, blocked.result());
        assertEquals(RemoteAppearanceImpact.NONE, first.remoteAppearanceImpact());
        assertEquals(RemoteAppearanceImpact.NONE, blocked.remoteAppearanceImpact());
        assertEquals(1, tokens.tokenRequests);
        assertTrue(gate.remoteControlsBlocked(PROFILE_ID));

        api.profileFailure = null;
        SessionValidation retry = services.sessions().manualRetry(tokens);
        assertTrue(retry.valid());
        assertEquals(2, tokens.tokenRequests);
        assertFalse(gate.remoteControlsBlocked(PROFILE_ID));
    }

    @Test
    void uuidMismatchStopsBeforeAnyMutation() throws Exception {
        ScriptedApi api = new ScriptedApi(profile(
                UUID.randomUUID(), "old", SkinVariant.CLASSIC, null, "cape-a"));
        Services services = services(api, new RemoteSessionGate());

        PresetApplicationOutcome outcome = services
                .mutations()
                .applyPreset(new FakeTokenSource(), request(resolvedSkin(), null));

        assertEquals(MutationResult.FAILED, outcome.result());
        assertEquals(RemoteAppearanceImpact.NONE, outcome.remoteAppearanceImpact());
        assertEquals(ApplicationPhase.VALIDATION, outcome.phase());
        assertEquals(List.of("GET_PROFILE"), api.operations);
    }

    private Services services(ProfileApi api, RemoteSessionGate gate) {
        NclSkinsStorage storage = storage();
        SessionValidationService sessions = new SessionValidationService(api, gate);
        AppearanceMutationService mutations =
                new AppearanceMutationService(api, storage, gate, sessions);
        return new Services(storage, sessions, mutations);
    }

    private NclSkinsStorage storage() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new NclSkinsStorage(
                temporaryDirectory.resolve("nclskins"),
                new PngValidator(),
                clock);
    }

    private static void cacheActiveSkin(
            NclSkinsStorage storage,
            RemoteProfile profile,
            byte[] pngBytes) throws Exception {
        storage.initialize();
        TextureCache cache = new TextureCache(storage);
        Path path = cache.cachePath(profile.activeSkin().orElseThrow().textureUri());
        Files.write(path, pngBytes);
    }

    private static PresetApplicationRequest request(ResolvedSkinAsset skin, String capeId) {
        AppearancePreset preset = new AppearancePreset(
                UUID.randomUUID(),
                "Preset",
                SkinReference.asset(skin.assetId()),
                capeId,
                NOW,
                NOW);
        return new PresetApplicationRequest(preset, skin);
    }

    private static ResolvedSkinAsset resolvedSkin() throws Exception {
        return resolvedSkin(TestPng.create(64, 64), SkinVariant.SLIM);
    }

    private static ResolvedSkinAsset resolvedSkin(byte[] png, SkinVariant variant) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png));
        return new ResolvedSkinAsset(UUID.randomUUID(), hash, variant, png);
    }

    private static RemoteProfile profile(
            UUID id,
            String textureHash,
            SkinVariant variant,
            String activeCape,
            String... capeIds) {
        RemoteSkin skin = new RemoteSkin(
                "skin-id",
                RemoteAssetState.ACTIVE,
                URI.create("https://textures.minecraft.net/texture/" + textureHash),
                variant,
                null);
        List<RemoteCape> capes = new ArrayList<>();
        for (String capeId : capeIds) {
            capes.add(new RemoteCape(
                    capeId,
                    capeId.equals(activeCape) ? RemoteAssetState.ACTIVE : RemoteAssetState.INACTIVE,
                    URI.create("https://textures.minecraft.net/texture/" + capeId),
                    capeId));
        }
        return new RemoteProfile(id, "Player", List.of(skin), capes, Set.of());
    }

    private static RemoteProfile withSkin(
            RemoteProfile profile,
            String textureHash,
            SkinVariant variant) {
        RemoteSkin skin = new RemoteSkin(
                "skin-id",
                RemoteAssetState.ACTIVE,
                URI.create("https://textures.minecraft.net/texture/" + textureHash),
                variant,
                null);
        return new RemoteProfile(
                profile.id(), profile.name(), List.of(skin), profile.capes(), profile.profileActions());
    }

    private static RemoteProfile withActiveCape(RemoteProfile profile, String capeId) {
        List<RemoteCape> capes = new ArrayList<>();
        for (RemoteCape cape : profile.capes()) {
            RemoteAssetState state = capeId != null && cape.id().equals(capeId)
                    ? RemoteAssetState.ACTIVE
                    : RemoteAssetState.INACTIVE;
            capes.add(new RemoteCape(cape.id(), state, cape.textureUri(), cape.alias()));
        }
        return new RemoteProfile(
                profile.id(), profile.name(), profile.skins(), capes, profile.profileActions());
    }

    private static ProfileApiException failure(ApiFailureKind kind, boolean uncertain) {
        return new ProfileApiException(kind, "safe failure", null, null, uncertain);
    }

    private record Services(
            NclSkinsStorage storage,
            SessionValidationService sessions,
            AppearanceMutationService mutations) {}

    private static final class FakeTokenSource implements GameSessionTokenSource {
        private final String token = "session-token-that-must-not-leak";
        private int tokenRequests;

        @Override
        public SessionIdentity currentSession() {
            return new SessionIdentity(PROFILE_ID, "Player");
        }

        @Override
        public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) throws E {
            tokenRequests++;
            return request.execute(token);
        }
    }

    private static final class ScriptedApi implements ProfileApi {
        private final List<String> operations = new ArrayList<>();
        private final List<String> activatedCapeIds = new ArrayList<>();
        private RemoteProfile current;
        private ProfileApiException profileFailure;
        private ProfileApiException skinFailure;
        private ProfileApiException capeFailure;

        private ScriptedApi(RemoteProfile current) {
            this.current = current;
        }

        @Override
        public RemoteProfile getProfile(String accessToken) throws ProfileApiException {
            operations.add("GET_PROFILE");
            if (profileFailure != null) {
                throw profileFailure;
            }
            return current;
        }

        @Override
        public void uploadSkin(
                String accessToken,
                SkinVariant variant,
                byte[] pngBytes) throws ProfileApiException {
            operations.add("UPLOAD_SKIN");
            if (skinFailure != null) {
                throw skinFailure;
            }
            try {
                String hash = HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(pngBytes));
                current = withSkin(current, hash, variant);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public void resetSkin(String accessToken) throws ProfileApiException {
            operations.add("RESET_SKIN");
            if (skinFailure != null) {
                throw skinFailure;
            }
            current = new RemoteProfile(
                    current.id(),
                    current.name(),
                    List.of(),
                    current.capes(),
                    current.profileActions());
        }

        @Override
        public void activateCape(String accessToken, String capeId) throws ProfileApiException {
            operations.add("ACTIVATE_CAPE");
            activatedCapeIds.add(capeId);
            if (capeFailure != null) {
                throw capeFailure;
            }
            current = withActiveCape(current, capeId);
        }

        @Override
        public void deactivateCape(String accessToken) throws ProfileApiException {
            operations.add("DEACTIVATE_CAPE");
            if (capeFailure != null) {
                throw capeFailure;
            }
            current = withActiveCape(current, null);
        }
    }
}
