package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.client.ExpectedAppearance;
import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.GameSessionTokenUnavailableException;
import com.naocraftlab.skins.client.MinecraftSkinCatalog;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.PersonalSkinCatalog;
import com.naocraftlab.skins.client.PlayerAppearanceSink;
import com.naocraftlab.skins.client.ResourcePackCapeCatalog;
import com.naocraftlab.skins.client.ResourcePackSkinCatalog;
import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.core.api.ApiFailureKind;
import com.naocraftlab.skins.core.api.ProfileApi;
import com.naocraftlab.skins.core.api.ProfileApiException;
import com.naocraftlab.skins.core.compatibility.SkinConflictReason;
import com.naocraftlab.skins.core.compatibility.SkinFeatureEvidence;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AddSourceTab;
import com.naocraftlab.skins.core.model.AppearancePreset;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.model.CatalogOrigin;
import com.naocraftlab.skins.core.model.EditorTab;
import com.naocraftlab.skins.core.model.MutationResult;
import com.naocraftlab.skins.core.model.PersonalSkinEntry;
import com.naocraftlab.skins.core.model.PersonalSkinSource;
import com.naocraftlab.skins.core.model.RemoteAssetState;
import com.naocraftlab.skins.core.model.RemoteCape;
import com.naocraftlab.skins.core.model.RemoteProfile;
import com.naocraftlab.skins.core.model.RemoteSkin;
import com.naocraftlab.skins.core.model.SkinAsset;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.model.SkinSource;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.provider.BuiltinProvider;
import com.naocraftlab.skins.core.provider.ProviderDelivery;
import com.naocraftlab.skins.core.provider.ProviderSkin;
import com.naocraftlab.skins.core.service.ApplicationPhase;
import com.naocraftlab.skins.core.service.AppliedAppearance;
import com.naocraftlab.skins.core.service.LibraryOperationException;
import com.naocraftlab.skins.core.service.LibraryService;
import com.naocraftlab.skins.core.service.PresetApplicationOutcome;
import com.naocraftlab.skins.core.service.RemoteAppearanceImpact;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import com.naocraftlab.skins.diagnostics.DiagnosticSinks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DefaultClientOperationsTest {
    @TempDir
    java.nio.file.Path temporaryDirectory;

    @Test
    void allScreenEntriesReadLocallyAfterOneStartupCheckAndKeepExplicitRefresh() throws Exception {
        AtomicInteger tokenRequests = new AtomicInteger();
        StubProfileApi api = new StubProfileApi();
        DefaultClientOperations operations = new DefaultClientOperations(
                countingTokens(tokenRequests), api, storage(), ignored -> skinPng(0xFF224488), fixedClock());
        ClientExecutor client = new ClientExecutor() {
            @Override public boolean isClientThread() { return true; }
            @Override public void execute(Runnable action) { action.run(); }
        };
        ClientRuntime runtime = new ClientRuntime(operations, client,
                () -> java.util.concurrent.CompletableFuture.completedFuture(Optional.empty()),
                Runnable::run, UiMessage::key, Optional.empty(), DiagnosticSinks.discarding());
        runtime.warmSession();
        runtime.warmSession();
        assertEquals(1, api.profileGets.get());
        assertEquals(1, tokenRequests.get());
        var initial = operations.initialize();
        var saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(), "Local", SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC, SkinVariant.CLASSIC, Optional.empty(), Optional.empty()));
        for (boolean pending : List.of(false, true)) {
            if (pending) operations.usePreset(saved.presetId());
            for (int repeat = 0; repeat < 3; repeat++) {
                for (var destination : com.naocraftlab.skins.client.ScreenDestination.values()) {
                    runtime.reopen(destination);
                    runtime.view(854, 480, 0, 0);
                    runtime.closeScreen();
                }
                runtime.initialize();
                runtime.closeScreen();
            }
            assertEquals(1, api.profileGets.get());
            assertEquals(1, tokenRequests.get());
            assertEquals(0, api.skinUploads.get());
            assertEquals(0, api.skinResets.get());
            assertEquals(0, api.capeActivations.get());
            assertEquals(0, api.capeDeactivations.get());
        }
        operations.refreshProviders(AppearanceProviders.Component.SKIN);
        assertEquals(2, api.profileGets.get());
        assertEquals(2, tokenRequests.get());
        runtime.close();
    }

    private static byte[] customCapePng() throws Exception {
        var image = new java.awt.image.BufferedImage(64, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.setRGB(3, 5, 0xff123456);
        var out = new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(image, "PNG", out); return out.toByteArray();
    }

    @Test
    void failedStartupIsNotRetriedByLocalScreenInitialization() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        StubProfileApi api = new StubProfileApi();
        api.profileFailure = new ProfileApiException(ApiFailureKind.NETWORK, "offline", null, null, false);
        DefaultClientOperations operations = new DefaultClientOperations(
                countingTokens(requests), api, storage(), ignored -> skinPng(0xFF224488), fixedClock());
        operations.warmSession();
        assertFalse(operations.warmedReconciliationRecommended());
        for (int repeat = 0; repeat < 8; repeat++) {
            assertEquals(ApiFailureKind.NETWORK, operations.initialize().session().failureKind());
        }
        assertEquals(1, requests.get());
        assertEquals(1, api.profileGets.get());
        api.profileFailure = null;
        assertTrue(operations.retrySession().session().valid());
        assertEquals(2, api.profileGets.get());
    }

    @Test
    void startupPendingDeliveryReusesItsSingleFreshProfileCheck() throws Exception {
        StubProfileApi api = new StubProfileApi();
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, storage(), ignored -> skinPng(0xFF224488), fixedClock());
        var initial = operations.initialize();
        var saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(), "Pending", SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC, SkinVariant.CLASSIC, Optional.empty(), Optional.empty()));
        operations.usePreset(saved.presetId());
        operations.warmSession();
        assertTrue(operations.warmedReconciliationRecommended());
        operations.reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START).orElseThrow();
        assertEquals(1, api.profileGets.get());
        assertEquals(1, api.skinUploads.get());
    }

    @Test
    void startupWithMinecraftDisabledDoesNotAcquireCredentials() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        StubProfileApi api = new StubProfileApi();
        DefaultClientOperations operations = new DefaultClientOperations(
                countingTokens(requests), api, storage(), ignored -> skinPng(0xFF224488), fixedClock());
        operations.disableProvider(AppearanceProviders.Component.SKIN, BuiltinProvider.MINECRAFT);
        operations.disableProvider(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT);
        operations.warmSession();
        operations.initialize();
        assertEquals(0, requests.get());
        assertEquals(0, api.profileGets.get());
    }

    @Test
    void enablingSkinDoesNotRewriteAnAlreadyConfirmedCapeDestination() throws Exception {
        byte[] skin = skinPng(0xFF41677A);
        StubProfileApi api = new StubProfileApi();
        DefaultClientOperations operations = new DefaultClientOperations(tokens(), api, storage(), ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        operations.disableProvider(AppearanceProviders.Component.SKIN, BuiltinProvider.MINECRAFT);
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(), "Offline skin", SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC, SkinVariant.CLASSIC, Optional.empty(), Optional.empty()));
        operations.usePreset(saved.presetId());
        operations.reconcileAppearance(ClientOperations.ReconciliationTrigger.LOCAL_INTENT);
        api.profile = new RemoteProfile(TestFixtures.ACCOUNT_ID, "Player", List.of(),
                List.of(new RemoteCape("observed-cape", RemoteAssetState.ACTIVE,
                        URI.create("https://textures.minecraft.net/texture/provider-observed-cape"), "Observed")), Set.of());
        operations.refreshProviders(AppearanceProviders.Component.CAPE);
        ClientOperations.DurableAppearance activated = operations.enableProvider(AppearanceProviders.Component.SKIN, BuiltinProvider.MINECRAFT);
        operations.reconcileAppearance(activated.reconciliationKey(), ClientOperations.ReconciliationTrigger.LOCAL_INTENT);
        assertEquals(1, api.skinUploads.get());
        assertEquals(0, api.capeActivations.get());
        assertEquals(0, api.capeDeactivations.get());
        assertEquals("observed-cape", operations.loadProviders().cape().minecraft().value().id());
    }

    @Test
    void ownedCapeLossPreservesOfflineTextureAcrossRestart() throws Exception {
        byte[] png = skinPng(0xFF315B72);
        NclSkinsStorage shared = storage();
        StubProfileApi api = new StubProfileApi();
        URI capeUri = URI.create("https://textures.minecraft.net/texture/provider-cape-fixture");
        api.profile = new RemoteProfile(TestFixtures.ACCOUNT_ID, "Player", List.of(),
                List.of(new RemoteCape("cape-owned", RemoteAssetState.INACTIVE, capeUri, "Cape")), Set.of());
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, shared, ignored -> png.clone(), fixedClock());
        operations.initialize();
        com.naocraftlab.skins.core.storage.TextureCache cache =
                new com.naocraftlab.skins.core.storage.TextureCache(shared);
        Files.write(cache.cachePath(capeUri), png);
        operations.refreshProviders(AppearanceProviders.Component.CAPE);
        var custom = shared.importCape(TestFixtures.ACCOUNT_ID, "Personal", customCapePng());
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(), "Cape choice", SkinReference.accountDefault(), SkinVariant.CLASSIC,
                SkinVariant.CLASSIC, Optional.of("cape-owned"), Optional.empty()).withOfflineCape(custom.texture()));
        ClientOperations.PresetUse selected = operations.usePreset(saved.presetId());
        String key = custom.texture().sha256();
        assertEquals(Optional.of(key), selected.localAppearance().orElseThrow().localCapeCacheKey());
        api.profile = new RemoteProfile(TestFixtures.ACCOUNT_ID, "Player", List.of(), List.of(), Set.of());
        ClientOperations.DurableAppearance refreshed = operations.refreshProviders(AppearanceProviders.Component.CAPE);
        assertTrue(shared.loadOwnedCapes(TestFixtures.ACCOUNT_ID).capes().isEmpty());
        assertNull(refreshed.providers().cape().minecraft().value());
        assertEquals(key, refreshed.providers().cape().offline().value().textureCacheKey());
        assertEquals(Optional.of(key), refreshed.localAppearance().orElseThrow().localCapeCacheKey());
        operations.disableProvider(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT);
        DefaultClientOperations reopened = new DefaultClientOperations(
                tokens(), api, storage(), ignored -> png.clone(), fixedClock());
        assertEquals(Optional.of(key), reopened.initialize().localAppearance().orElseThrow().localCapeCacheKey());
        assertTrue(cache.readIfCached(key).isPresent());
        ClientOperations.PresetUse selectedAgain = operations.usePreset(saved.presetId());
        assertEquals(Optional.of(key), selectedAgain.localAppearance().orElseThrow().localCapeCacheKey());
        assertNull(shared.loadAppearance(TestFixtures.ACCOUNT_ID).capeId());
        assertEquals(key, shared.loadAppearance(TestFixtures.ACCOUNT_ID).providers().cape().offline().value().textureCacheKey());
    }

    @Test
    void providerRefreshReadsOnlyRequestedListWithoutCreatingIntentOrMutation() throws Exception {
        byte[] skin = skinPng(0xFF315B72);
        NclSkinsStorage shared = storage();
        StubProfileApi api = new StubProfileApi();
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, shared, ignored -> skin.clone(), fixedClock());
        operations.initialize();
        ClientOperations.DurableAppearance refreshed = operations.refreshProviders(AppearanceProviders.Component.CAPE);
        assertEquals(1, api.profileGets.get());
        assertEquals(0, api.skinUploads.get());
        assertEquals(0, api.capeActivations.get());
        assertEquals(0, api.capeDeactivations.get());
        assertEquals(0, refreshed.intentRevision());
        assertEquals(AppearanceSyncStatus.LOCAL_ONLY, refreshed.syncStatus());
        assertTrue(refreshed.providers().cape().minecraft().known());
        assertFalse(refreshed.providers().skin().minecraft().known());
        operations.disableProvider(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT);
        operations.refreshProviders(AppearanceProviders.Component.CAPE);
        assertEquals(1, api.profileGets.get());
    }

    @Test
    void settledActiveSavePreservesMinecraftDeliveriesAndSkipsCheckpoint() throws Exception {
        byte[] skin = skinPng(0xFF315B72);
        StubProfileApi api = new StubProfileApi();
        api.profile = new RemoteProfile(TestFixtures.ACCOUNT_ID, "Player", List.of(),
                List.of(new RemoteCape(
                        "cape-owned",
                        RemoteAssetState.ACTIVE,
                        URI.create("https://textures.minecraft.net/texture/active-cape"),
                        "Owned cape")), Set.of());
        AtomicInteger tokenRequests = new AtomicInteger();
        GameSessionTokenSource countingTokens = new GameSessionTokenSource() {
            @Override
            public SessionIdentity currentSession() {
                return new SessionIdentity(TestFixtures.ACCOUNT_ID, "Player");
            }

            @Override
            public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) throws E {
                tokenRequests.incrementAndGet();
                return request.execute("scoped-token");
            }
        };
        NclSkinsStorage storage = storage();
        DefaultClientOperations operations = new DefaultClientOperations(
                countingTokens, api, storage, ignored -> skin.clone(), fixedClock());
        operations.initialize();
        operations.refreshProviders(AppearanceProviders.Component.CAPE);
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Settled",
                SkinReference.accountDefault(),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.of("cape-owned"),
                Optional.empty()));
        operations.usePreset(saved.presetId());
        ClientOperations.DurableAppearance settled = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow()
                .appearance();
        var deliveries = operations.loadProviders();
        int tokenRequestsBeforeSave = tokenRequests.get();
        int profileGetsBeforeSave = api.profileGets.get();
        int skinUploadsBeforeSave = api.skinUploads.get();
        int capeActivationsBeforeSave = api.capeActivations.get();
        var localCape = storage.importCape(TestFixtures.ACCOUNT_ID, "Offline", customCapePng()).texture();

        ClientOperations.EditorSave edited = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.of(saved.presetId()),
                "Settled renamed",
                SkinReference.accountDefault(),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.of("cape-owned"),
                OuterLayerVisibility.noneVisible(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                PersonalSkinSource.FILE).withOfflineCape(localCape));

        assertEquals(AppearanceSyncStatus.OFFICIAL, edited.reappliedAppearance().orElseThrow().syncStatus());
        assertEquals(deliveries.skin().minecraftDelivery(),
                operations.loadProviders().skin().minecraftDelivery());
        assertEquals(deliveries.cape().minecraftDelivery(),
                operations.loadProviders().cape().minecraftDelivery());
        ClientOperations.ReconciliationResult checkpoint = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();
        assertEquals(AppearanceSyncStatus.OFFICIAL, checkpoint.appearance().syncStatus());
        assertEquals(tokenRequestsBeforeSave, tokenRequests.get());
        assertEquals(profileGetsBeforeSave, api.profileGets.get());
        assertEquals(skinUploadsBeforeSave, api.skinUploads.get());
        assertEquals(capeActivationsBeforeSave, api.capeActivations.get());
        assertEquals(settled.intentRevision() + 1, checkpoint.appearance().intentRevision());
    }

    @Test
    void galleryApplyWithMatchingObservedMinecraftComponentsSkipsRemoteCheckpoint() throws Exception {
        byte[] skin = skinPng(0xFF315B72);
        StubProfileApi api = new StubProfileApi();
        api.profile = new RemoteProfile(TestFixtures.ACCOUNT_ID, "Player", List.of(),
                List.of(new RemoteCape("cape-owned", RemoteAssetState.ACTIVE,
                        URI.create("https://textures.minecraft.net/texture/active-cape"), "Owned cape")), Set.of());
        AtomicInteger tokens = new AtomicInteger();
        NclSkinsStorage shared = storage();
        DefaultClientOperations operations = new DefaultClientOperations(
                countingTokens(tokens), api, shared, ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        SkinReference reference = SkinReference.asset(initial.account().skinAssets().get(0).id());
        ClientOperations.EditorSave first = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(), "First", reference, SkinVariant.CLASSIC, SkinVariant.CLASSIC,
                Optional.of("cape-owned"), Optional.empty()));
        operations.applyPreset(first.presetId());
        var offlineCape = shared.importCape(TestFixtures.ACCOUNT_ID, "Offline", customCapePng()).texture();
        ClientOperations.EditorSave second = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(), "Second", reference, SkinVariant.CLASSIC, SkinVariant.CLASSIC,
                Optional.of("cape-owned"), OuterLayerVisibility.noneVisible(),
                Optional.empty(), Optional.empty(), Optional.empty()).withOfflineCape(offlineCape));
        DefaultClientOperations restarted = new DefaultClientOperations(
                countingTokens(tokens), api, shared, ignored -> skin.clone(), fixedClock());
        restarted.initialize();
        long previousRevision = shared.loadAppearance(TestFixtures.ACCOUNT_ID).intentRevision();
        int previousTokens = tokens.get();
        int previousGets = api.profileGets.get();
        int previousUploads = api.skinUploads.get();
        int previousResets = api.skinResets.get();
        int previousActivations = api.capeActivations.get();
        int previousDeactivations = api.capeDeactivations.get();

        ClientOperations.RemoteResult result = restarted.applyPreset(second.presetId());

        ClientOperations.DurableAppearance applied = restarted.durableAppearance().orElseThrow();
        assertEquals(previousRevision + 1, applied.intentRevision());
        assertEquals(Optional.of(second.presetId()), applied.activePresetId());
        assertEquals(AppearanceSyncStatus.OFFICIAL, applied.syncStatus());
        assertEquals(OuterLayerVisibility.noneVisible(), applied.outerLayerVisibility().orElseThrow());
        assertEquals(offlineCape.sha256(), applied.providers().cape().offline().value().textureCacheKey());
        assertEquals(previousTokens, tokens.get());
        assertEquals(previousGets, api.profileGets.get());
        assertEquals(previousUploads, api.skinUploads.get());
        assertEquals(previousResets, api.skinResets.get());
        assertEquals(previousActivations, api.capeActivations.get());
        assertEquals(previousDeactivations, api.capeDeactivations.get());
        assertEquals(RemoteAppearanceImpact.NONE, result.outcome().remoteAppearanceImpact());

        shared.updateAppearance(TestFixtures.ACCOUNT_ID, current -> current.withProviders(
                new AppearanceProviders(current.providers().skin(),
                        current.providers().cape().observeMinecraft(null))));
        restarted.applyPreset(second.presetId());
        assertEquals(previousGets + 1, api.profileGets.get());
        assertEquals(previousActivations, api.capeActivations.get());
        assertEquals(AppearanceSyncStatus.OFFICIAL, restarted.durableAppearance().orElseThrow().syncStatus());

        long priorActivation = restarted.loadProviders().cape().minecraftDelivery().activation();
        restarted.disableProvider(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT);
        restarted.enableProvider(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT);
        assertTrue(restarted.loadProviders().cape().minecraftDelivery().activation() > priorActivation);
        int getsBeforeActivationApply = api.profileGets.get();
        restarted.applyPreset(second.presetId());
        assertEquals(getsBeforeActivationApply + 1, api.profileGets.get());

        ClientOperations.PresetUse stale = restarted.usePreset(second.presetId());
        ClientOperations.PresetUse latest = restarted.usePreset(second.presetId());
        assertEquals(AppearanceSyncStatus.OFFICIAL, latest.syncStatus());
        ClientOperations.ReconciliationKey staleKey = new ClientOperations.ReconciliationKey(
                TestFixtures.ACCOUNT_ID, stale.intentRevision(),
                stale.providers().skin().minecraftDelivery().activation(),
                stale.providers().cape().minecraftDelivery().activation());
        int getsBeforeStale = api.profileGets.get();
        assertTrue(restarted.reconcileAppearance(staleKey,
                ClientOperations.ReconciliationTrigger.EXPLICIT_RETRY).isEmpty());
        assertEquals(getsBeforeStale, api.profileGets.get());
    }

    @Test
    void galleryApplyWithChangedSkinPreservesConfirmedCape() throws Exception {
        byte[] skin = skinPng(0xFF315B72);
        StubProfileApi api = new StubProfileApi();
        api.profile = new RemoteProfile(TestFixtures.ACCOUNT_ID, "Player", List.of(),
                List.of(new RemoteCape("cape-owned", RemoteAssetState.ACTIVE,
                        URI.create("https://textures.minecraft.net/texture/active-cape"), "Owned cape")), Set.of());
        NclSkinsStorage shared = storage();
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, shared, ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        SkinReference firstSkin = SkinReference.asset(initial.account().skinAssets().get(0).id());
        var changed = new LibraryService(shared, fixedClock()).importSkin(
                TestFixtures.ACCOUNT_ID, "Changed", SkinVariant.CLASSIC,
                SkinSource.IMPORTED, skinPng(0xFF785A37));
        SkinReference secondSkin = SkinReference.asset(changed.asset().id());
        ClientOperations.EditorSave first = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(), "First", firstSkin, SkinVariant.CLASSIC, SkinVariant.CLASSIC,
                Optional.of("cape-owned"), Optional.empty()));
        operations.applyPreset(first.presetId());
        ProviderDelivery confirmedCape = operations.loadProviders().cape().minecraftDelivery();
        int previousGets = api.profileGets.get();
        int previousUploads = api.skinUploads.get();
        int previousCapeActivations = api.capeActivations.get();
        ClientOperations.EditorSave second = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(), "Second", secondSkin, SkinVariant.CLASSIC, SkinVariant.CLASSIC,
                Optional.of("cape-owned"), Optional.empty()));

        operations.applyPreset(second.presetId());

        assertEquals(AppearanceSyncStatus.OFFICIAL, operations.durableAppearance().orElseThrow().syncStatus());
        assertEquals(confirmedCape, operations.loadProviders().cape().minecraftDelivery());
        assertEquals(previousGets + 1, api.profileGets.get());
        assertEquals(previousUploads + 1, api.skinUploads.get());
        assertEquals(previousCapeActivations, api.capeActivations.get());
        assertEquals(0, api.capeDeactivations.get());
    }

    @Test
    void galleryApplyWithModelOnlyChangePreservesConfirmedCape() throws Exception {
        byte[] skin = skinPng(0xFF315B72);
        StubProfileApi api = new StubProfileApi();
        api.profile = new RemoteProfile(TestFixtures.ACCOUNT_ID, "Player", List.of(),
                List.of(new RemoteCape("cape-owned", RemoteAssetState.ACTIVE,
                        URI.create("https://textures.minecraft.net/texture/active-cape"), "Owned cape")), Set.of());
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, storage(), ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        SkinReference reference = SkinReference.asset(initial.account().skinAssets().get(0).id());
        ClientOperations.EditorSave first = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(), "Classic", reference, SkinVariant.CLASSIC, SkinVariant.CLASSIC,
                Optional.of("cape-owned"), Optional.empty()));
        operations.applyPreset(first.presetId());
        ProviderDelivery confirmedCape = operations.loadProviders().cape().minecraftDelivery();
        int uploads = api.skinUploads.get();
        int activations = api.capeActivations.get();
        ClientOperations.EditorSave second = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(), "Slim", reference, SkinVariant.SLIM, SkinVariant.SLIM,
                Optional.of("cape-owned"), Optional.empty()));

        operations.applyPreset(second.presetId());

        assertEquals(AppearanceSyncStatus.OFFICIAL, operations.durableAppearance().orElseThrow().syncStatus());
        assertEquals(confirmedCape, operations.loadProviders().cape().minecraftDelivery());
        assertEquals(uploads + 1, api.skinUploads.get());
        assertEquals(activations, api.capeActivations.get());
    }

    @Test
    void galleryApplyWithCapeOnlyChangePreservesConfirmedSkin() throws Exception {
        byte[] skin = skinPng(0xFF315B72);
        StubProfileApi api = new StubProfileApi();
        api.profile = new RemoteProfile(TestFixtures.ACCOUNT_ID, "Player", List.of(), List.of(
                new RemoteCape("cape-first", RemoteAssetState.ACTIVE,
                        URI.create("https://textures.minecraft.net/texture/first-cape"), "First cape"),
                new RemoteCape("cape-second", RemoteAssetState.INACTIVE,
                        URI.create("https://textures.minecraft.net/texture/second-cape"), "Second cape")), Set.of());
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, storage(), ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        SkinReference reference = SkinReference.asset(initial.account().skinAssets().get(0).id());
        ClientOperations.EditorSave first = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(), "First", reference, SkinVariant.CLASSIC, SkinVariant.CLASSIC,
                Optional.of("cape-first"), Optional.empty()));
        operations.applyPreset(first.presetId());
        ProviderDelivery confirmedSkin = operations.loadProviders().skin().minecraftDelivery();
        int uploads = api.skinUploads.get();
        int activations = api.capeActivations.get();
        ClientOperations.EditorSave second = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(), "Second", reference, SkinVariant.CLASSIC, SkinVariant.CLASSIC,
                Optional.of("cape-second"), Optional.empty()));

        operations.applyPreset(second.presetId());

        assertEquals(AppearanceSyncStatus.OFFICIAL, operations.durableAppearance().orElseThrow().syncStatus());
        assertEquals(confirmedSkin, operations.loadProviders().skin().minecraftDelivery());
        assertEquals(uploads, api.skinUploads.get());
        assertEquals(activations + 1, api.capeActivations.get());
    }

    @Test
    void galleryApplyKeepsExplicitRecoveryWhenSkinIsUnknownOrPartialAndCapeIsConfirmed() throws Exception {
        SettledActiveFixture fixture = settledActiveFixture();
        fixture.storage().updateAppearance(TestFixtures.ACCOUNT_ID, current -> {
            var skin = current.providers().skin();
            var unknownSkin = skin.settle(skin.minecraftDelivery(), ProviderDelivery.Status.UNKNOWN, null);
            return new com.naocraftlab.skins.core.model.AccountAppearanceState(
                    current.schemaVersion(), current.accountId(), current.intentRevision(), current.activePresetId(),
                    current.skinSha256(), current.skinVariant(), current.capeId(), current.outerLayerVisibility(),
                    AppearanceSyncStatus.UNKNOWN, current.settledRevision(), current.updatedAt(),
                    new AppearanceProviders(unknownSkin, current.providers().cape()));
        });
        int previousGets = fixture.api().profileGets.get();
        int previousActivations = fixture.api().capeActivations.get();
        ProviderDelivery confirmedCape = fixture.operations().loadProviders().cape().minecraftDelivery();

        fixture.operations().applyPreset(fixture.presetId());

        assertEquals(previousGets + 1, fixture.api().profileGets.get());
        assertEquals(previousActivations, fixture.api().capeActivations.get());
        assertEquals(confirmedCape, fixture.operations().loadProviders().cape().minecraftDelivery());
        assertEquals(AppearanceSyncStatus.OFFICIAL,
                fixture.operations().durableAppearance().orElseThrow().syncStatus());

        fixture.storage().updateAppearance(TestFixtures.ACCOUNT_ID, current -> {
            var skin = current.providers().skin();
            var pendingSkin = skin.settle(skin.minecraftDelivery(), ProviderDelivery.Status.PENDING, null);
            return new com.naocraftlab.skins.core.model.AccountAppearanceState(
                    current.schemaVersion(), current.accountId(), current.intentRevision(), current.activePresetId(),
                    current.skinSha256(), current.skinVariant(), current.capeId(), current.outerLayerVisibility(),
                    AppearanceSyncStatus.PARTIAL, current.settledRevision(), current.updatedAt(),
                    new AppearanceProviders(pendingSkin, current.providers().cape()));
        });
        fixture.operations().applyPreset(fixture.presetId());
        assertEquals(previousGets + 2, fixture.api().profileGets.get());
        assertEquals(previousActivations, fixture.api().capeActivations.get());
        assertEquals(confirmedCape, fixture.operations().loadProviders().cape().minecraftDelivery());
    }

    @Test
    void galleryApplyRecoversCapeAfterRealPartialMutationWithoutReuploadingConfirmedSkin() throws Exception {
        byte[] skin = skinPng(0xFF395D7B);
        URI skinUri = URI.create("https://textures.minecraft.net/texture/gallery-partial-skin");
        URI capeUri = URI.create("https://textures.minecraft.net/texture/gallery-partial-cape");
        StubProfileApi api = new StubProfileApi();
        api.profile = new RemoteProfile(TestFixtures.ACCOUNT_ID, "Player", List.of(),
                List.of(new RemoteCape("cape-owned", RemoteAssetState.INACTIVE, capeUri, "Owned cape")), Set.of());
        api.capeFailure = new ProfileApiException(
                ApiFailureKind.RATE_LIMITED, "cape rate limited", 429, Duration.ofSeconds(60), false);
        NclSkinsStorage shared = storage();
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, shared, ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(), "Partial", SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC, SkinVariant.CLASSIC, Optional.of("cape-owned"), Optional.empty()));
        operations.usePreset(saved.presetId());
        ClientOperations.ReconciliationResult partial = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.LOCAL_INTENT).orElseThrow();
        assertEquals(AppearanceSyncStatus.PARTIAL, partial.appearance().syncStatus());
        ProviderDelivery confirmedSkin = partial.appearance().providers().skin().minecraftDelivery();
        assertEquals(ProviderDelivery.Status.CONFIRMED, confirmedSkin.status());
        int gets = api.profileGets.get();
        int uploads = api.skinUploads.get();
        int activations = api.capeActivations.get();

        Files.write(new com.naocraftlab.skins.core.storage.TextureCache(shared).cachePath(skinUri), skin);
        api.profile = new RemoteProfile(TestFixtures.ACCOUNT_ID, "Player",
                List.of(new RemoteSkin("matching-skin", RemoteAssetState.ACTIVE,
                        skinUri, SkinVariant.CLASSIC, "Matching skin")),
                List.of(new RemoteCape("cape-owned", RemoteAssetState.INACTIVE, capeUri, "Owned cape")), Set.of());
        api.capeFailure = null;
        api.rateLimitRemaining = Optional.empty();

        operations.applyPreset(saved.presetId());

        assertEquals(AppearanceSyncStatus.OFFICIAL, operations.durableAppearance().orElseThrow().syncStatus());
        assertEquals(confirmedSkin, operations.loadProviders().skin().minecraftDelivery());
        assertEquals(gets + 1, api.profileGets.get());
        assertEquals(uploads, api.skinUploads.get());
        assertEquals(activations + 1, api.capeActivations.get());
    }

    @Test
    void settledActiveNoOpSavePreservesDeliveriesAndSkipsCheckpoint() throws Exception {
        SettledActiveFixture fixture = settledActiveFixture();

        assertSettledLocalOnlySave(fixture, new ClientOperations.EditorSaveRequest(
                Optional.of(fixture.presetId()),
                "Settled",
                SkinReference.accountDefault(),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.of("cape-owned"),
                Optional.empty()));
    }

    @Test
    void settledActiveNameOnlySavePreservesDeliveriesAndSkipsCheckpoint() throws Exception {
        SettledActiveFixture fixture = settledActiveFixture();

        assertSettledLocalOnlySave(fixture, new ClientOperations.EditorSaveRequest(
                Optional.of(fixture.presetId()),
                "Settled renamed",
                SkinReference.accountDefault(),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.of("cape-owned"),
                Optional.empty()));
    }

    @Test
    void settledActiveLayersOnlySavePreservesDeliveriesAndSkipsCheckpoint() throws Exception {
        SettledActiveFixture fixture = settledActiveFixture();

        assertSettledLocalOnlySave(fixture, new ClientOperations.EditorSaveRequest(
                Optional.of(fixture.presetId()),
                "Settled",
                SkinReference.accountDefault(),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.of("cape-owned"),
                OuterLayerVisibility.noneVisible(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));
    }

    @Test
    void settledActiveOfflineCapeOnlySavePreservesDeliveriesAndSkipsCheckpoint() throws Exception {
        SettledActiveFixture fixture = settledActiveFixture();
        var offlineCape = fixture.storage().importCape(
                TestFixtures.ACCOUNT_ID, "Offline", customCapePng()).texture();

        ClientOperations.EditorSave edited = fixture.operations().saveEditor(
                new ClientOperations.EditorSaveRequest(
                        Optional.of(fixture.presetId()),
                        "Settled",
                        SkinReference.accountDefault(),
                        SkinVariant.CLASSIC,
                        SkinVariant.CLASSIC,
                        Optional.of("cape-owned"),
                        OuterLayerVisibility.allVisible(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()).withOfflineCape(offlineCape));

        assertEquals(AppearanceSyncStatus.OFFICIAL,
                edited.reappliedAppearance().orElseThrow().syncStatus());
        assertEquals(fixture.skinDelivery(),
                edited.reappliedAppearance().orElseThrow().providers().skin().minecraftDelivery());
        assertEquals(fixture.capeDelivery(),
                edited.reappliedAppearance().orElseThrow().providers().cape().minecraftDelivery());
        assertEquals(offlineCape.sha256(),
                edited.reappliedAppearance().orElseThrow().providers().cape().offline().value().textureCacheKey());
        assertNoRemoteCheckpoint(fixture, edited);
    }

    @Test
    void activeSaveWithSkinChangeAssignsOnlySkinDelivery() throws Exception {
        byte[] skin = skinPng(0xFF315B72);
        StubProfileApi api = new StubProfileApi();
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, storage(), ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Account default",
                SkinReference.accountDefault(),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        operations.usePreset(saved.presetId());
        ClientOperations.DurableAppearance settled = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow()
                .appearance();
        var capeDelivery = settled.providers().cape().minecraftDelivery();

        ClientOperations.EditorSave edited = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.of(saved.presetId()),
                "Skin changed",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));

        ClientOperations.DurableAppearance pending = edited.reappliedAppearance().orElseThrow();
        assertEquals(AppearanceSyncStatus.PENDING, pending.syncStatus());
        assertEquals(capeDelivery, pending.providers().cape().minecraftDelivery());
        assertEquals(ProviderDelivery.Status.CONFIRMED,
                pending.providers().cape().minecraftDelivery().status());
        ClientOperations.ReconciliationResult reconciled = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();
        assertEquals(AppearanceSyncStatus.OFFICIAL, reconciled.appearance().syncStatus());
        assertEquals(1, api.skinUploads.get());
        assertEquals(0, api.capeActivations.get());
        assertEquals(0, api.capeDeactivations.get());
    }

    @Test
    void activeSaveSkinToAccountDefaultResetsOnlySkin() throws Exception {
        byte[] skin = skinPng(0xFF315B72);
        StubProfileApi api = new StubProfileApi();
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, storage(), ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Skin preset",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        operations.usePreset(saved.presetId());
        operations.reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();
        ProviderDelivery capeDelivery = operations.loadProviders().cape().minecraftDelivery();
        int uploadsBeforeReset = api.skinUploads.get();
        api.profile = new RemoteProfile(
                TestFixtures.ACCOUNT_ID,
                "Player",
                List.of(new RemoteSkin(
                        "remote-skin",
                        RemoteAssetState.ACTIVE,
                        URI.create("https://textures.minecraft.net/texture/remote-skin"),
                        SkinVariant.CLASSIC,
                        "Remote skin")),
                List.of(),
                Set.of());

        ClientOperations.EditorSave edited = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.of(saved.presetId()),
                "Skin preset reset",
                SkinReference.accountDefault(),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        ClientOperations.ReconciliationResult reset = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();

        assertEquals(ProviderDelivery.Status.PENDING,
                edited.reappliedAppearance().orElseThrow().providers().skin().minecraftDelivery().status());
        assertEquals(capeDelivery,
                edited.reappliedAppearance().orElseThrow().providers().cape().minecraftDelivery());
        assertEquals(AppearanceSyncStatus.OFFICIAL, reset.appearance().syncStatus());
        assertEquals(uploadsBeforeReset, api.skinUploads.get());
        assertEquals(1, api.skinResets.get());
        assertEquals(0, api.capeActivations.get());
        assertEquals(0, api.capeDeactivations.get());
    }

    @Test
    void activeSaveKeepsLocalOnlyStatusWhenOnlyDisabledMinecraftDestinationChanges() throws Exception {
        byte[] skin = skinPng(0xFF315B72);
        StubProfileApi api = new StubProfileApi();
        NclSkinsStorage shared = storage();
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, shared, ignored -> skin.clone(), fixedClock());
        operations.initialize();
        operations.disableProvider(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT);
        ClientOperations.InitialData initial = operations.initialize();
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Disabled cape",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        operations.usePreset(saved.presetId());
        shared.updateAppearance(TestFixtures.ACCOUNT_ID, current -> new com.naocraftlab.skins.core.model.AccountAppearanceState(
                current.schemaVersion(),
                current.accountId(),
                current.intentRevision(),
                current.activePresetId(),
                current.skinSha256(),
                current.skinVariant(),
                current.capeId(),
                current.outerLayerVisibility(),
                AppearanceSyncStatus.LOCAL_ONLY,
                current.settledRevision(),
                current.updatedAt(),
                current.providers()));
        var delivery = shared.loadAppearance(TestFixtures.ACCOUNT_ID)
                .providers().cape().minecraftDelivery();
        int profileGets = api.profileGets.get();
        int skinUploads = api.skinUploads.get();

        ClientOperations.EditorSave edited = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.of(saved.presetId()),
                "Disabled cape changed",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.of("future-cape"),
                Optional.empty()));

        assertEquals(AppearanceSyncStatus.LOCAL_ONLY,
                edited.reappliedAppearance().orElseThrow().syncStatus());
        assertEquals(delivery,
                edited.reappliedAppearance().orElseThrow().providers().cape().minecraftDelivery());
        assertEquals("future-cape",
                edited.reappliedAppearance().orElseThrow().providers().cape().desired().id());
        assertEquals(profileGets, api.profileGets.get());
        assertEquals(skinUploads, api.skinUploads.get());
        assertEquals(0, api.capeActivations.get());
    }

    @Test
    void activeSaveCapeToNoCapeDeactivatesOnlyCape() throws Exception {
        byte[] skin = skinPng(0xFF315B72);
        StubProfileApi api = new StubProfileApi();
        api.profile = new RemoteProfile(
                TestFixtures.ACCOUNT_ID,
                "Player",
                List.of(),
                List.of(new RemoteCape(
                        "cape-a",
                        RemoteAssetState.ACTIVE,
                        URI.create("https://textures.minecraft.net/texture/cape-a"),
                        "Cape A")),
                Set.of());
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, storage(), ignored -> skin.clone(), fixedClock());
        operations.initialize();
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Cape preset",
                SkinReference.accountDefault(),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.of("cape-a"),
                Optional.empty()));
        operations.usePreset(saved.presetId());
        operations.reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();
        int skinUploadsBeforeReset = api.skinUploads.get();

        ClientOperations.EditorSave edited = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.of(saved.presetId()),
                "Cape preset cleared",
                SkinReference.accountDefault(),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        ClientOperations.ReconciliationResult reset = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();

        assertEquals(ProviderDelivery.Status.CONFIRMED,
                edited.reappliedAppearance().orElseThrow().providers().skin().minecraftDelivery().status());
        assertEquals(AppearanceSyncStatus.OFFICIAL, reset.appearance().syncStatus());
        assertEquals(skinUploadsBeforeReset, api.skinUploads.get());
        assertEquals(0, api.skinResets.get());
        assertEquals(0, api.capeActivations.get());
        assertEquals(1, api.capeDeactivations.get());
    }

    @Test
    void activeSaveWithBothChangedComponentsWritesBothDeliveries() throws Exception {
        byte[] skin = skinPng(0xFF315B72);
        StubProfileApi api = new StubProfileApi();
        api.profile = new RemoteProfile(
                TestFixtures.ACCOUNT_ID,
                "Player",
                List.of(),
                List.of(
                        new RemoteCape("cape-a", RemoteAssetState.ACTIVE,
                                URI.create("https://textures.minecraft.net/texture/cape-a"), "Cape A"),
                        new RemoteCape("cape-b", RemoteAssetState.INACTIVE,
                                URI.create("https://textures.minecraft.net/texture/cape-b"), "Cape B")),
                Set.of());
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, storage(), ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Both A",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.of("cape-a"),
                Optional.empty()));
        operations.usePreset(saved.presetId());
        operations.reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();
        int uploadsBeforeEdit = api.skinUploads.get();
        int activationsBeforeEdit = api.capeActivations.get();

        ClientOperations.EditorSave edited = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.of(saved.presetId()),
                "Both B",
                SkinReference.asset(initial.account().skinAssets().get(1).id()),
                SkinVariant.SLIM,
                SkinVariant.SLIM,
                Optional.of("cape-b"),
                Optional.empty()));
        ClientOperations.ReconciliationResult changed = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();

        assertEquals(AppearanceSyncStatus.PENDING, edited.reappliedAppearance().orElseThrow().syncStatus());
        assertEquals(AppearanceSyncStatus.OFFICIAL, changed.appearance().syncStatus());
        assertEquals(uploadsBeforeEdit + 1, api.skinUploads.get());
        assertEquals(activationsBeforeEdit + 1, api.capeActivations.get());
    }

    @Test
    void activeSkinSaveDoesNotRewriteExternallyChangedConfirmedCape() throws Exception {
        byte[] skin = skinPng(0xFF315B72);
        StubProfileApi api = new StubProfileApi();
        api.profile = new RemoteProfile(
                TestFixtures.ACCOUNT_ID,
                "Player",
                List.of(),
                List.of(
                        new RemoteCape("cape-a", RemoteAssetState.ACTIVE,
                                URI.create("https://textures.minecraft.net/texture/cape-a"), "Cape A"),
                        new RemoteCape("cape-b", RemoteAssetState.INACTIVE,
                                URI.create("https://textures.minecraft.net/texture/cape-b"), "Cape B")),
                Set.of());
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, storage(), ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Confirmed cape",
                SkinReference.accountDefault(),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.of("cape-a"),
                Optional.empty()));
        operations.usePreset(saved.presetId());
        operations.reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();
        api.profile = new RemoteProfile(
                TestFixtures.ACCOUNT_ID,
                "Player",
                List.of(),
                List.of(
                        new RemoteCape("cape-b", RemoteAssetState.ACTIVE,
                                URI.create("https://textures.minecraft.net/texture/cape-b"), "Cape B"),
                        new RemoteCape("cape-a", RemoteAssetState.INACTIVE,
                                URI.create("https://textures.minecraft.net/texture/cape-a"), "Cape A")),
                Set.of());
        int capeActivationsBeforeEdit = api.capeActivations.get();
        int capeDeactivationsBeforeEdit = api.capeDeactivations.get();

        ClientOperations.EditorSave edited = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.of(saved.presetId()),
                "Skin changed",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.of("cape-a"),
                Optional.empty()));
        ClientOperations.ReconciliationResult changed = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();

        assertEquals(AppearanceSyncStatus.PENDING, edited.reappliedAppearance().orElseThrow().syncStatus());
        assertEquals(AppearanceSyncStatus.OFFICIAL, changed.appearance().syncStatus());
        assertEquals(1, api.skinUploads.get());
        assertEquals(capeActivationsBeforeEdit, api.capeActivations.get());
        assertEquals(capeDeactivationsBeforeEdit, api.capeDeactivations.get());
    }

    @Test
    void capeOnlyDeliveryDoesNotReadAnUnrelatedMissingSkinAsset() throws Exception {
        byte[] skin = skinPng(0xFF315B72);
        NclSkinsStorage shared = storage();
        StubProfileApi api = new StubProfileApi();
        DefaultClientOperations first = new DefaultClientOperations(
                tokens(), api, shared, ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = first.initialize();
        SkinAsset selectedSkin = initial.account().skinAssets().get(0);
        ClientOperations.EditorSave saved = first.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Missing skin asset later",
                SkinReference.asset(selectedSkin.id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        first.usePreset(saved.presetId());
        first.reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START).orElseThrow();
        assertEquals(1, api.skinUploads.get());
        Files.delete(shared.assetPath(selectedSkin.sha256()));

        api.profile = new RemoteProfile(
                TestFixtures.ACCOUNT_ID,
                "Player",
                List.of(new RemoteSkin(
                        "uncached-skin",
                        RemoteAssetState.ACTIVE,
                        URI.create("https://textures.minecraft.net/texture/uncached-skin"),
                        SkinVariant.CLASSIC,
                        "Uncached skin")),
                List.of(new RemoteCape(
                        "cape-owned",
                        RemoteAssetState.INACTIVE,
                        URI.create("https://textures.minecraft.net/texture/cape-owned"),
                        "Owned cape")),
                Set.of());
        DefaultClientOperations second = new DefaultClientOperations(
                tokens(), api, shared, ignored -> skin.clone(), fixedClock());
        second.initialize();
        second.refreshProviders(AppearanceProviders.Component.CAPE);
        ClientOperations.EditorSave edited = second.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.of(saved.presetId()),
                "Cape only",
                SkinReference.asset(selectedSkin.id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.of("cape-owned"),
                Optional.empty()));

        assertEquals(ProviderDelivery.Status.CONFIRMED,
                edited.reappliedAppearance().orElseThrow().providers().skin().minecraftDelivery().status());
        ClientOperations.ReconciliationResult reconciled = second
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();

        assertEquals(AppearanceSyncStatus.OFFICIAL, reconciled.appearance().syncStatus());
        assertEquals(1, api.skinUploads.get());
        assertEquals(1, api.capeActivations.get());
    }

    @Test
    void enablingMinecraftAfterOfflineSettlementDeliversSameIntentWithNewActivation() throws Exception {
        byte[] skin = skinPng(0xFF315B72);
        NclSkinsStorage shared = storage();
        StubProfileApi api = new StubProfileApi();
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, shared, ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        operations.disableProvider(AppearanceProviders.Component.SKIN, BuiltinProvider.MINECRAFT);
        operations.disableProvider(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT);
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(), "Offline choice", SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC, SkinVariant.CLASSIC, Optional.empty(), Optional.empty()));
        ClientOperations.PresetUse selected = operations.usePreset(saved.presetId());
        ClientOperations.DurableAppearance offline = operations.reconcileAppearance(
                ClientOperations.ReconciliationTrigger.LOCAL_INTENT).orElseThrow().appearance();
        assertEquals(0, api.profileGets.get());
        assertEquals(0, api.skinUploads.get());
        assertEquals(AppearanceSyncStatus.OFFICIAL, offline.syncStatus());
        ClientOperations.DurableAppearance activated = operations.enableProvider(
                AppearanceProviders.Component.SKIN, BuiltinProvider.MINECRAFT);
        assertEquals(selected.intentRevision(), activated.intentRevision());
        assertNotEquals(offline.reconciliationKey(), activated.reconciliationKey());
        assertEquals(AppearanceSyncStatus.PENDING, activated.syncStatus());
        assertEquals(0, api.profileGets.get());
        assertTrue(operations.reconcileAppearance(offline.reconciliationKey(),
                ClientOperations.ReconciliationTrigger.LOCAL_INTENT).isEmpty());
        ClientOperations.ReconciliationResult result = operations.reconcileAppearance(activated.reconciliationKey(),
                ClientOperations.ReconciliationTrigger.LOCAL_INTENT).orElseThrow();
        assertEquals(AppearanceSyncStatus.OFFICIAL, result.appearance().syncStatus());
        assertEquals(1, api.skinUploads.get());
        assertEquals(0, api.capeActivations.get());
    }

    @Test
    void providerConfigurationIsLocalAndSurvivesRestartWithoutLosingObservations() throws Exception {
        byte[] skin = skinPng(0xFF315B72);
        NclSkinsStorage shared = storage();
        StubProfileApi api = new StubProfileApi();
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, shared, ignored -> skin.clone(), fixedClock());
        operations.initialize();
        operations.moveProvider(AppearanceProviders.Component.SKIN, BuiltinProvider.MINECRAFT, -1);
        operations.disableProvider(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT);
        operations.disableProvider(AppearanceProviders.Component.CAPE, BuiltinProvider.OFFLINE);
        AppearanceProviders expected = operations.loadProviders();
        DefaultClientOperations reopened = new DefaultClientOperations(
                tokens(), api, storage(), ignored -> skin.clone(), fixedClock());
        assertEquals(expected, reopened.loadProviders());
        assertEquals(expected, reopened.reloadProviders().providers());
        assertFalse(expected.galleryAvailable());
        assertEquals(List.of(BuiltinProvider.MINECRAFT, BuiltinProvider.OFFLINE), expected.skin().order());
        assertEquals(0, api.profileGets.get());
    }

    @Test
    void offlineSelectionReopensInAnotherInstanceAndWarmSessionSynchronizesOnceOnline() throws Exception {
        byte[] skin = skinPng(0xFF315B72);
        byte[] nextSkin = skinPng(0xFF426C83);
        NclSkinsStorage shared = storage();
        StubProfileApi onlineApi = new StubProfileApi();
        DefaultClientOperations onlineBeforeOffline = new DefaultClientOperations(
                tokens(), onlineApi, shared, ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = onlineBeforeOffline.initialize();
        ClientOperations.EditorSave confirmedA = onlineBeforeOffline.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Online A",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        ClientOperations.PresetUse appliedA = onlineBeforeOffline.usePreset(confirmedA.presetId());
        ClientOperations.ReconciliationResult confirmed = onlineBeforeOffline
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.LOCAL_INTENT)
                .orElseThrow();
        assertEquals(AppearanceSyncStatus.OFFICIAL, confirmed.appearance().syncStatus());
        var observedA = confirmed.appearance().providers().skin().minecraft();

        var importedB = new LibraryService(shared, fixedClock()).importSkin(
                TestFixtures.ACCOUNT_ID, "Offline B", SkinVariant.SLIM, SkinSource.IMPORTED, nextSkin);
        AtomicInteger offlineTokenRequests = new AtomicInteger();
        GameSessionTokenSource offlineTokens = new GameSessionTokenSource() {
            @Override
            public SessionIdentity currentSession() {
                return new SessionIdentity(TestFixtures.ACCOUNT_ID, "Offline");
            }

            @Override
            public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) {
                offlineTokenRequests.incrementAndGet();
                throw new GameSessionTokenUnavailableException();
            }
        };
        DefaultClientOperations offline = new DefaultClientOperations(
                offlineTokens, onlineApi, shared, ignored -> nextSkin.clone(), fixedClock());
        offline.initialize();
        ClientOperations.EditorSave saved = offline.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Offline choice",
                SkinReference.asset(importedB.asset().id()),
                SkinVariant.SLIM,
                SkinVariant.SLIM,
                Optional.empty(),
                Optional.empty()));

        ClientOperations.PresetUse selected = offline.usePreset(saved.presetId());
        assertTrue(selected.remoteResult().isEmpty());
        assertTrue(selected.pendingOfficialSync());
        assertEquals(AppearanceSyncStatus.PENDING, selected.syncStatus());
        long pendingRevision = selected.intentRevision();
        long pendingActivation = selected.providers().skin().minecraftDelivery().activation();
        assertTrue(selected.intentRevision() > 0);
        assertTrue(selected.localAppearance().isPresent());
        assertEquals(observedA, selected.providers().skin().minecraft());
        assertEquals(0, onlineApi.profileGets.get() - 1);
        assertEquals(0, offlineTokenRequests.get());
        assertEquals(1, onlineApi.skinUploads.get());
        assertEquals(Optional.of(saved.presetId()), selected.account().presets().stream()
                .filter(preset -> preset.id().equals(saved.presetId()))
                .map(preset -> preset.id())
                .findFirst());

        DefaultClientOperations anotherOffline = new DefaultClientOperations(
                offlineTokens, onlineApi, shared, ignored -> nextSkin.clone(), fixedClock());
        ClientOperations.InitialData reopenedOffline = anotherOffline.initialize();
        assertEquals(Optional.of(saved.presetId()), reopenedOffline.activePresetId());
        assertTrue(reopenedOffline.pendingOfficialSync());
        assertTrue(reopenedOffline.localAppearance().isPresent());

        DefaultClientOperations online = new DefaultClientOperations(
                tokens(), onlineApi, shared, ignored -> nextSkin.clone(), fixedClock());
        ClientRuntime runtime = new ClientRuntime(online,
                new ClientExecutor() {
                    @Override public boolean isClientThread() { return true; }
                    @Override public void execute(Runnable action) { action.run(); }
                },
                () -> java.util.concurrent.CompletableFuture.completedFuture(Optional.empty()),
                Runnable::run, UiMessage::key, Optional.empty(), DiagnosticSinks.discarding());
        runtime.warmSession();
        runtime.warmSession();

        ClientOperations.InitialData synchronizedOnline = online.initialize();
        assertEquals(Optional.of(saved.presetId()), synchronizedOnline.activePresetId());
        assertEquals(AppearanceSyncStatus.OFFICIAL, synchronizedOnline.syncStatus());
        assertEquals(pendingRevision, synchronizedOnline.intentRevision());
        assertEquals(pendingActivation,
                synchronizedOnline.providers().skin().minecraftDelivery().activation());
        assertEquals(2, onlineApi.skinUploads.get());
        assertEquals(2, onlineApi.profileGets.get());
        runtime.close();
    }

    @Test
    void newInactivePresetSaveHasNoFallibleAppearanceFollowUp() throws Exception {
        byte[] skin = skinPng(0xFF315C73);
        NclSkinsStorage storage = storage();
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(),
                new StubProfileApi(),
                storage,
                ignored -> skin.clone(),
                fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        Files.createDirectory(storage.layout().accountAppearance(TestFixtures.ACCOUNT_ID));

        ClientOperations.EditorSave saved = operations.saveEditor(
                new ClientOperations.EditorSaveRequest(
                        Optional.empty(),
                        "Duplicate draft",
                        SkinReference.asset(initial.account().skinAssets().get(0).id()),
                        SkinVariant.CLASSIC,
                        SkinVariant.CLASSIC,
                        Optional.empty(),
                        Optional.empty()));

        assertTrue(saved.reappliedAppearance().isEmpty());
        assertEquals(1, saved.account().presets().size());
        assertEquals(saved.presetId(), saved.account().presets().get(0).id());
        assertEquals(
                saved.account().presets(),
                new LibraryService(storage, fixedClock())
                        .load(TestFixtures.ACCOUNT_ID)
                        .presets());
    }

    @Test
    void exactUuidSharesOneAccountAcrossNicknameChanges() throws Exception {
        byte[] skin = skinPng(0xFF6A3C55);
        UUID accountId = UUID.randomUUID();
        StubProfileApi firstApi = new StubProfileApi();
        firstApi.profileFailure = new ProfileApiException(
                ApiFailureKind.NETWORK, "offline", null, null, false);
        DefaultClientOperations first = new DefaultClientOperations(
                tokens(accountId, "OriginalName"), firstApi, storage(), ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = first.initialize();
        ClientOperations.EditorSave saved = first.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Exact UUID preset",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        first.usePreset(saved.presetId());

        StubProfileApi renamedApi = new StubProfileApi();
        renamedApi.profileFailure = new ProfileApiException(
                ApiFailureKind.NETWORK, "offline", null, null, false);
        DefaultClientOperations renamed = new DefaultClientOperations(
                tokens(accountId, "CompletelyDifferentName"),
                renamedApi,
                storage(),
                ignored -> skin.clone(),
                fixedClock());
        ClientOperations.InitialData reopened = renamed.initialize();

        assertEquals(accountId, reopened.account().accountId());
        assertEquals(Optional.of(saved.presetId()), reopened.activePresetId());
        assertTrue(reopened.account().presets().stream()
                .anyMatch(preset -> preset.name().equals("Exact UUID preset")));
        assertTrue(reopened.pendingOfficialSync());
    }

    @Test
    void sameNicknameWithDifferentUuidsKeepsAccountsIsolated() throws Exception {
        byte[] skin = skinPng(0xFF6A4C2D);
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        StubProfileApi unavailable = new StubProfileApi();
        unavailable.profileFailure = new ProfileApiException(
                ApiFailureKind.NETWORK, "offline", null, null, false);
        DefaultClientOperations first = new DefaultClientOperations(
                tokens(firstId, "SameName"), unavailable, storage(), ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = first.initialize();
        ClientOperations.EditorSave saved = first.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Only first UUID",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        first.usePreset(saved.presetId());

        StubProfileApi secondUnavailable = new StubProfileApi();
        secondUnavailable.profileFailure = new ProfileApiException(
                ApiFailureKind.NETWORK, "offline", null, null, false);
        DefaultClientOperations second = new DefaultClientOperations(
                tokens(secondId, "SameName"),
                secondUnavailable,
                storage(),
                ignored -> skin.clone(),
                fixedClock());
        ClientOperations.InitialData isolated = second.initialize();

        assertEquals(secondId, isolated.account().accountId());
        assertTrue(isolated.account().presets().isEmpty());
        assertTrue(isolated.activePresetId().isEmpty());
        assertTrue(Files.isRegularFile(storage().layout().accountState(firstId)));
        assertTrue(Files.isRegularFile(storage().layout().accountState(secondId)));
    }

    @Test
    void reconciliationAcceptsAtomicTokenIdentityWithSameUuidAndDifferentNickname()
            throws Exception {
        byte[] skin = skinPng(0xFF6D4F38);
        var pinned = new GameSessionTokenSource.SessionIdentity(
                TestFixtures.ACCOUNT_ID, "OriginalName");
        SwitchingTokenSource tokens = new SwitchingTokenSource(pinned);
        tokens.atomicIdentity = new GameSessionTokenSource.SessionIdentity(
                TestFixtures.ACCOUNT_ID, "RenamedAccount");
        StubProfileApi api = new StubProfileApi();
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens, api, storage(), ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Same UUID",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        operations.usePreset(saved.presetId());

        ClientOperations.ReconciliationResult reconciled = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();

        assertEquals(AppearanceSyncStatus.OFFICIAL, reconciled.appearance().syncStatus());
        assertEquals(TestFixtures.ACCOUNT_ID, reconciled.account().accountId());
        assertEquals(1, api.profileGets.get());
        assertEquals(1, api.skinUploads.get());
    }

    @Test
    void galleryReopenAdoptsAnotherOnlineInstancesLibraryAndOfficialSelectionWithoutMutation()
            throws Exception {
        byte[] skin = skinPng(0xFF425D78);
        StubProfileApi writerApi = new StubProfileApi();
        StubProfileApi readerApi = new StubProfileApi();
        DefaultClientOperations writer = new DefaultClientOperations(
                tokens(), writerApi, storage(), ignored -> skin.clone(), fixedClock());
        DefaultClientOperations reader = new DefaultClientOperations(
                tokens(), readerApi, storage(), ignored -> skin.clone(), fixedClock());
        reader.initialize();
        ClientOperations.InitialData writerInitial = writer.initialize();
        ClientOperations.EditorSave saved = writer.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Shared selection",
                SkinReference.asset(writerInitial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        writer.usePreset(saved.presetId());

        ClientOperations.InitialData reopened = reader.initialize();

        assertTrue(reopened.account().presets().stream()
                .anyMatch(preset -> preset.id().equals(saved.presetId())));
        assertEquals(Optional.of(saved.presetId()), reopened.activePresetId());
        assertTrue(reopened.localAppearance().isPresent());
        assertTrue(reopened.pendingOfficialSync());
        assertEquals(AppearanceSyncStatus.PENDING, reopened.syncStatus());
        assertEquals(0, readerApi.skinUploads.get());
    }

    @Test
    void sessionRefreshKeepsPendingBWithOneFreshValidationAndMutatesOnce() throws Exception {
        byte[] skin = skinPng(0xFF47637D);
        URI officialUri = URI.create("https://textures.minecraft.net/texture/official-a");
        StubProfileApi api = new StubProfileApi();
        api.profile = profileWithActiveAppearance(officialUri, null);
        NclSkinsStorage shared = storage();
        shared.initialize();
        Files.write(new com.naocraftlab.skins.core.storage.TextureCache(shared).cachePath(officialUri), skin);
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, shared, ignored -> skin.clone(), fixedClock());
        operations.initialize();
        ClientOperations.ReconciliationResult initial = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.EXPLICIT_RETRY)
                .orElseThrow();
        UUID officialPreset = initial.appearance().activePresetId().orElseThrow();
        ClientOperations.EditorSave pendingB = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Pending B",
                SkinReference.asset(initial.account().skinAssets().get(1).id()),
                SkinVariant.SLIM,
                SkinVariant.SLIM,
                Optional.empty(),
                Optional.empty()));
        ClientOperations.PresetUse selected = operations.usePreset(pendingB.presetId());
        assertNotEquals(officialPreset, selected.activePresetId());
        assertEquals(0, api.skinUploads.get());
        int profileGetsBeforeRefresh = api.profileGets.get();

        ClientOperations.InitialData refreshed = operations.retrySession();
        assertEquals(Optional.of(pendingB.presetId()), refreshed.activePresetId());
        assertEquals(AppearanceSyncStatus.PENDING, refreshed.syncStatus());
        assertTrue(refreshed.session().valid());
        assertEquals(profileGetsBeforeRefresh + 1, api.profileGets.get());
        ClientOperations.ReconciliationResult reconciled = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.SESSION_REFRESHED)
                .orElseThrow();

        assertEquals(Optional.of(pendingB.presetId()), reconciled.appearance().activePresetId());
        assertEquals(AppearanceSyncStatus.OFFICIAL, reconciled.appearance().syncStatus());
        assertEquals(profileGetsBeforeRefresh + 1, api.profileGets.get());
        assertEquals(1, api.skinUploads.get());
    }

    @Test
    void ownsCoreServicesSeedsDefaultsReconcilesActiveAndAvoidsMutationProfileGet() throws Exception {
        byte[] classic = skinPng(0xFFFF0000);
        byte[] slim = skinPng(0xFF00FF00);
        SkinCatalogSource bundled = (collectionId, skinId, model) ->
                model == SkinModel.SLIM ? slim.clone() : classic.clone();
        StubProfileApi api = new StubProfileApi();
        GameSessionTokenSource tokens = new GameSessionTokenSource() {
            @Override
            public SessionIdentity currentSession() {
                return new SessionIdentity(TestFixtures.ACCOUNT_ID, "Player");
            }

            @Override
            public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) throws E {
                return request.execute("scoped-token");
            }
        };
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens,
                api,
                new NclSkinsStorage(
                        temporaryDirectory,
                        new PngValidator(),
                        Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)),
                bundled,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

        ClientOperations.InitialData initial = operations.initialize();
        assertEquals(2, initial.account().skinAssets().size());
        assertTrue(initial.account().presets().isEmpty());
        assertTrue(initial.activePresetId().isEmpty());
        assertEquals(0, api.profileGets.get());
        assertArrayEquals(
                new PngValidator().normalizeSkin(classic),
                operations.loadSkinPreview(initial.account().skinAssets().get(0).id()));

        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Local",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        ClientOperations.RemoteResult applied = operations.applyPreset(saved.presetId());
        assertTrue(applied.outcome()
                .optionalAppliedAppearance()
                .orElseThrow()
                .localSkinSha256()
                .isPresent());
        assertEquals(1, api.profileGets.get());
        assertEquals(1, api.skinUploads.get());
        var appliedAppearance = applied.outcome().optionalAppliedAppearance().orElseThrow();
        var resolvedLocal = operations.deterministicAppearanceResolver(Runnable::run)
                .resolve(new ExpectedAppearance(
                        appliedAppearance.profileId(),
                        appliedAppearance.skinTexture(),
                        appliedAppearance.localSkinSha256(),
                        Optional.of(SkinModel.CLASSIC),
                        appliedAppearance.capeTexture()))
                .join()
                .orElseThrow()
                .platformProfile();
        assertEquals(
                appliedAppearance.localSkinSha256().orElseThrow(),
                resolvedLocal.skin().orElseThrow().sha256());
        assertTrue(Files.isRegularFile(resolvedLocal.skin().orElseThrow().path()));
        assertEquals(1, api.profileGets.get());
        operations.rememberActivePreset(Optional.of(saved.presetId()));

        ClientOperations.InitialData reopened = operations.initialize();
        assertEquals(2, reopened.account().skinAssets().size());
        assertEquals(Optional.of(saved.presetId()), reopened.activePresetId());
        assertEquals(1, api.profileGets.get());
        assertTrue(operations.acknowledgedAppearance().isPresent());

        int profileGetsBeforeRefresh = api.profileGets.get();
        operations.retrySession();
        assertEquals(profileGetsBeforeRefresh + 1, api.profileGets.get());
    }

    @Test
    void catalogBytesPreferencesAndOriginCrossTheOperationsBoundary() throws Exception {
        byte[] classic = skinPng(0xFF112233);
        byte[] slim = skinPng(0xFF445566);
        SkinCatalogSource catalog = (collectionId, skinId, model) ->
                model == SkinModel.SLIM ? slim.clone() : classic.clone();
        NclSkinsStorage shared = storage();
        DefaultClientOperations first = new DefaultClientOperations(
                tokens(), new StubProfileApi(), shared, catalog, fixedClock());

        ClientOperations.InitialData initial = first.initialize();
        assertEquals(AddSourceTab.CATALOG, initial.uiPreferences().selectedAddSourceTab());
        assertEquals(EditorTab.APPEARANCE, initial.uiPreferences().selectedEditorTab());
        assertTrue(initial.uiPreferences().preferredSkinVariant().isEmpty());
        first.setSelectedAddSourceTab(AddSourceTab.CATALOG);
        first.setSelectedEditorTab(initial.account().accountId(), EditorTab.CAPE);
        first.setCollectionCollapsed(MinecraftSkinCatalog.COLLECTION_ID, true);
        first.replaceCollapsedCollectionIds(Set.of(
                MinecraftSkinCatalog.COLLECTION_ID,
                "pack:secondary"));
        first.setPreferredSkinVariant(SkinVariant.SLIM);
        assertArrayEquals(
                new PngValidator().normalizeSkin(slim),
                first.loadCatalogSkin(
                        MinecraftSkinCatalog.COLLECTION_ID,
                        MinecraftSkinCatalog.STEVE_SKIN_ID,
                        SkinModel.SLIM));

        CatalogOrigin origin = new CatalogOrigin(
                MinecraftSkinCatalog.SOURCE_ID,
                MinecraftSkinCatalog.COLLECTION_ID,
                MinecraftSkinCatalog.STEVE_SKIN_ID);
        ClientOperations.EditorSave saved = first.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Catalog Steve",
                SkinReference.accountDefault(),
                SkinVariant.SLIM,
                SkinVariant.SLIM,
                Optional.empty(),
                Optional.of(slim),
                Optional.of(origin)));
        var savedPreset = saved.account().presets().stream()
                .filter(preset -> preset.id().equals(saved.presetId()))
                .findFirst()
                .orElseThrow();
        assertEquals(
                Optional.of(origin),
                saved.account().skinAssets().stream()
                        .filter(asset -> asset.id().equals(savedPreset.skin().assetId()))
                        .findFirst()
                        .orElseThrow()
                        .catalogOrigin());

        DefaultClientOperations second = new DefaultClientOperations(
                tokens(), new StubProfileApi(), shared, catalog, fixedClock());
        ClientOperations.InitialData reopened = second.initialize();
        assertEquals(AddSourceTab.CATALOG, reopened.uiPreferences().selectedAddSourceTab());
        assertEquals(EditorTab.CAPE, reopened.uiPreferences().selectedEditorTab());
        assertEquals(Optional.of(SkinVariant.SLIM), reopened.uiPreferences().preferredSkinVariant());
        assertTrue(reopened.uiPreferences()
                .collapsedCollectionIds()
                .contains(MinecraftSkinCatalog.COLLECTION_ID));
        assertTrue(reopened.uiPreferences()
                .collapsedCollectionIds()
                .contains("pack:secondary"));
        assertEquals(Optional.of(reopened.uiPreferences()), second.loadUiPreferences());
    }

    @Test
    void personalCatalogPersistsDeduplicatesReusesAndOnlyManualRemovalHidesIt()
            throws Exception {
        byte[] png = skinPng(0xFF285A7C);
        NclSkinsStorage shared = storage();
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), new StubProfileApi(), shared, ignored -> png.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        int baselineAssets = initial.account().skinAssets().size();

        ClientOperations.EditorSave classic = operations.saveEditor(
                new ClientOperations.EditorSaveRequest(
                        Optional.empty(),
                        "Classic preset",
                        SkinReference.accountDefault(),
                        SkinVariant.CLASSIC,
                        SkinVariant.CLASSIC,
                        Optional.empty(),
                        Optional.of(png),
                        Optional.empty(),
                        Optional.of("first-file")));
        ClientOperations.EditorSave slim = operations.saveEditor(
                new ClientOperations.EditorSaveRequest(
                        Optional.empty(),
                        "Slim preset",
                        SkinReference.accountDefault(),
                        SkinVariant.SLIM,
                        SkinVariant.SLIM,
                        Optional.empty(),
                        Optional.of(png),
                        Optional.empty(),
                        Optional.of("second-name")));

        assertEquals(1, slim.account().personalSkins().size());
        var entry = slim.account().personalSkins().get(0);
        assertEquals("first-file", entry.displayName());
        assertEquals(Set.of(SkinVariant.CLASSIC, SkinVariant.SLIM), entry.variantAssetIds().keySet());
        assertEquals(baselineAssets + 2, slim.account().skinAssets().size());
        assertEquals(2, slim.account().presets().size());

        var personal = operations.catalogCollections().get(0);
        assertEquals(PersonalSkinCatalog.COLLECTION_ID, personal.id());
        assertEquals(List.of(SkinModel.CLASSIC, SkinModel.SLIM), personal.skins().get(0).models());
        String hash = personal.skins().get(0).id();
        UUID reusable = operations.reusableCatalogSkinAsset(
                        personal.id(), hash, SkinModel.CLASSIC)
                .orElseThrow();
        assertArrayEquals(png, operations.loadCatalogSkin(personal.id(), hash, SkinModel.CLASSIC));

        ClientOperations.EditorSave reused = operations.saveEditor(
                new ClientOperations.EditorSaveRequest(
                        Optional.empty(),
                        "Reused",
                        SkinReference.asset(reusable),
                        SkinVariant.CLASSIC,
                        SkinVariant.CLASSIC,
                        Optional.empty(),
                        Optional.empty()));
        assertEquals(baselineAssets + 2, reused.account().skinAssets().size());
        assertEquals(3, reused.account().presets().size());

        AccountState hidden = operations.removePersonalSkin(hash);
        assertFalse(hidden.personalSkins().get(0).visible());
        assertEquals(3, hidden.presets().size());
        assertEquals(baselineAssets + 2, hidden.skinAssets().size());

        assertArrayEquals(png, operations.loadCatalogSkin(personal.id(), hash, SkinModel.CLASSIC));
        assertEquals(Optional.of(reusable), operations.reusableCatalogSkinAsset(
                personal.id(), hash, SkinModel.CLASSIC));
        assertFalse(operations.catalogCollections().stream()
                .anyMatch(collection -> PersonalSkinCatalog.isCollection(collection.id())));

        ClientOperations.EditorSave restored = operations.saveEditor(
                new ClientOperations.EditorSaveRequest(
                        Optional.empty(),
                        "Restored",
                        SkinReference.accountDefault(),
                        SkinVariant.CLASSIC,
                        SkinVariant.CLASSIC,
                        Optional.empty(),
                        Optional.of(png),
                        Optional.empty(),
                        Optional.of("restored-file")));
        assertTrue(restored.account().personalSkins().get(0).visible());
        assertEquals("restored-file", restored.account().personalSkins().get(0).displayName());
        assertEquals(4, restored.account().presets().size());
        assertEquals(PersonalSkinCatalog.COLLECTION_ID, operations.catalogCollections().get(0).id());
        assertNotEquals(classic.presetId(), slim.presetId());
    }

    @Test
    void duplicateSaveRoundTripsOfflineCapeWithoutChangingActiveAppearance() throws Exception {
        NclSkinsStorage shared = storage();
        StubProfileApi api = new StubProfileApi();
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, shared, ignored -> skinPng(0xFF315B72), fixedClock());
        operations.initialize();
        var localCape = shared.importCape(
                TestFixtures.ACCOUNT_ID, "Saved cape", customCapePng()).texture();
        ClientOperations.EditorSave source = operations.saveEditor(
                new ClientOperations.EditorSaveRequest(
                        Optional.empty(),
                        "Source",
                        SkinReference.accountDefault(),
                        SkinVariant.CLASSIC,
                        SkinVariant.CLASSIC,
                        Optional.empty(),
                        Optional.empty()).withOfflineCape(localCape));
        operations.usePreset(source.presetId());
        var before = shared.loadAppearance(TestFixtures.ACCOUNT_ID);
        int profileGets = api.profileGets.get();
        int skinUploads = api.skinUploads.get();
        int capeActivations = api.capeActivations.get();

        ClientOperations.EditorSave duplicate = operations.saveEditor(
                new ClientOperations.EditorSaveRequest(
                        Optional.empty(),
                        "Copy of Source",
                        SkinReference.accountDefault(),
                        SkinVariant.CLASSIC,
                        SkinVariant.CLASSIC,
                        Optional.empty(),
                        Optional.empty()).withOfflineCape(localCape));

        NclSkinsStorage reopenedStorage = new NclSkinsStorage(
                temporaryDirectory, new PngValidator(), fixedClock());
        var reloaded = reopenedStorage.loadOrCreateAccount(TestFixtures.ACCOUNT_ID);
        var sourcePreset = reloaded.presets().stream()
                .filter(preset -> preset.id().equals(source.presetId()))
                .findFirst()
                .orElseThrow();
        var duplicatePreset = reloaded.presets().stream()
                .filter(preset -> preset.id().equals(duplicate.presetId()))
                .findFirst()
                .orElseThrow();
        var after = reopenedStorage.loadAppearance(TestFixtures.ACCOUNT_ID);

        assertNotEquals(source.presetId(), duplicate.presetId());
        assertEquals(localCape, sourcePreset.offlineCape());
        assertEquals(localCape, duplicatePreset.offlineCape());
        assertEquals(1, reloaded.personalCapes().size());
        assertEquals(before.activePresetId(), after.activePresetId());
        assertEquals(before.providers(), after.providers());
        assertEquals(before.intentRevision(), after.intentRevision());
        assertEquals(profileGets, api.profileGets.get());
        assertEquals(skinUploads, api.skinUploads.get());
        assertEquals(capeActivations, api.capeActivations.get());
    }

    @Test
    void storedLegacyEvidenceDiffersFromCleanImportAcrossAssetsAndPersonalCatalog()
            throws Exception {
        NclSkinsStorage shared = storage();
        LibraryService library = new LibraryService(shared, fixedClock());
        PngValidator validator = new PngValidator();
        byte[] rawLegacy = skinPng(64, 32, 0xFF285A7C);
        byte[] cleanImport = validator.projectImport(rawLegacy).pngBytes();
        shared.initialize();
        String rawSha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(rawLegacy));
        Files.write(shared.assetPath(rawSha256), rawLegacy);
        UUID rawAssetId = UUID.randomUUID();
        Instant now = fixedClock().instant();
        shared.updateAccount(TestFixtures.ACCOUNT_ID, current -> new AccountState(
                AccountState.CURRENT_SCHEMA_VERSION,
                TestFixtures.ACCOUNT_ID,
                List.of(new SkinAsset(
                        rawAssetId, "Raw legacy", rawSha256, SkinVariant.CLASSIC,
                        SkinSource.IMPORTED, now, now)),
                List.of(new PersonalSkinEntry(
                        rawSha256, "Raw legacy", PersonalSkinSource.PLAYER_NAME,
                        now, now, Map.of(SkinVariant.CLASSIC, rawAssetId), true)),
                List.of(new AppearancePreset(
                        UUID.randomUUID(), "Raw", SkinReference.asset(rawAssetId),
                        null, now, now)),
                now));
        var clean = library.createPresetFromPersonalSkin(
                TestFixtures.ACCOUNT_ID, "Clean", "Clean import", SkinVariant.CLASSIC,
                PersonalSkinSource.FILE, cleanImport, null);
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), new StubProfileApi(), shared, ignored -> cleanImport.clone(), fixedClock());
        operations.initialize();

        assertEquals(
                List.of(SkinConflictReason.MALFORMED_EXPRESSIVE_DATA),
                operations.assetFeatureEvidence().get(rawAssetId).potentialConflicts());
        assertEquals(
                SkinFeatureEvidence.ORDINARY,
                operations.assetFeatureEvidence().get(clean.asset().id()));

        operations.catalogCollections();
        assertEquals(
                List.of(SkinConflictReason.MALFORMED_EXPRESSIVE_DATA),
                operations.catalogFeatureEvidence().get(new ClientOperations.CatalogVariant(
                        PersonalSkinCatalog.OTHER_PLAYERS_COLLECTION_ID,
                        rawSha256,
                        SkinVariant.CLASSIC)).potentialConflicts());
        assertEquals(
                SkinFeatureEvidence.ORDINARY,
                operations.catalogFeatureEvidence().get(new ClientOperations.CatalogVariant(
                        PersonalSkinCatalog.COLLECTION_ID,
                        clean.personalSkin().sha256(),
                        SkinVariant.CLASSIC)));
    }

    @Test
    void playerImportsUseTheirOwnCollectionAndPermanentlyPromoteMatchingContent()
            throws Exception {
        byte[] localPng = skinPng(0xFF285A7C);
        byte[] playerPng = skinPng(0xFF7C5A28);
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), new StubProfileApi(), storage(), ignored -> localPng.clone(), fixedClock());
        operations.initialize();

        savePersonal(operations, localPng, "Local", PersonalSkinSource.FILE);
        savePersonal(operations, playerPng, "Player", PersonalSkinSource.PLAYER_NAME);

        assertEquals(
                List.of(
                        PersonalSkinCatalog.COLLECTION_ID,
                        PersonalSkinCatalog.OTHER_PLAYERS_COLLECTION_ID),
                operations.catalogCollections().stream()
                        .filter(collection -> PersonalSkinCatalog.isCollection(collection.id()))
                        .map(SkinCatalogSource.CollectionDescriptor::id)
                        .toList());

        savePersonal(operations, localPng, "Promoted", PersonalSkinSource.PLAYER_NAME);
        assertEquals(
                List.of(PersonalSkinCatalog.OTHER_PLAYERS_COLLECTION_ID),
                operations.catalogCollections().stream()
                        .filter(collection -> PersonalSkinCatalog.isCollection(collection.id()))
                        .map(SkinCatalogSource.CollectionDescriptor::id)
                        .toList());

        savePersonal(operations, localPng, "Still player", PersonalSkinSource.FILE);
        assertTrue(operations.catalogCollections().stream()
                .filter(collection -> PersonalSkinCatalog.isCollection(collection.id()))
                .allMatch(collection ->
                        collection.id().equals(PersonalSkinCatalog.OTHER_PLAYERS_COLLECTION_ID)));
    }

    @Test
    void catalogDiscoveryHidesOnlyTheUnavailableOrInvalidModelVariant() throws Exception {
        byte[] valid = skinPng(0xFF335577);
        SkinCatalogSource partial = (collectionId, skinId, model) -> {
            if (MinecraftSkinCatalog.STEVE_SKIN_ID.equals(skinId)
                    && model == SkinModel.CLASSIC) {
                return valid.clone();
            }
            if (MinecraftSkinCatalog.ALEX_SKIN_ID.equals(skinId)
                    && model == SkinModel.SLIM) {
                return valid.clone();
            }
            if (MinecraftSkinCatalog.ALEX_SKIN_ID.equals(skinId)
                    && model == SkinModel.CLASSIC) {
                return new byte[] {1, 2, 3};
            }
            throw new IOException("variant missing");
        };
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), new StubProfileApi(), storage(), partial, fixedClock());

        var collection = operations.catalogCollections().get(0);
        assertEquals(
                List.of(MinecraftSkinCatalog.STEVE_SKIN_ID, MinecraftSkinCatalog.ALEX_SKIN_ID),
                collection.skins().stream().map(SkinCatalogSource.SkinDescriptor::id).toList());
        assertEquals(List.of(SkinModel.CLASSIC), collection.skins().get(0).models());
        assertEquals(List.of(SkinModel.SLIM), collection.skins().get(1).models());
    }

    @Test
    void catalogDiscoveryReadsTheCurrentAtomicResourcePackSnapshotOnEveryAddOpen()
            throws Exception {
        byte[] valid = skinPng(0xFF557799);
        AtomicReference<List<SkinCatalogSource.CollectionDescriptor>> snapshot =
                new AtomicReference<>(ResourcePackSkinCatalog.build(List.of(
                        new ResourcePackSkinCatalog.Variant(
                                "alpha", "first", SkinModel.CLASSIC, "pack-a", 0))));
        SkinCatalogSource reloadable = new SkinCatalogSource() {
            @Override
            public byte[] load(String collectionId, String skinId, SkinModel model) {
                return valid.clone();
            }

            @Override
            public List<SkinCatalogSource.CollectionDescriptor> collections() {
                return snapshot.get();
            }
        };
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), new StubProfileApi(), storage(), reloadable, fixedClock());

        assertEquals("alpha", operations.catalogCollections().get(0).id());
        snapshot.set(ResourcePackSkinCatalog.build(List.of(
                new ResourcePackSkinCatalog.Variant(
                        "beta", "second", SkinModel.SLIM, "pack-b", 1))));

        var reloaded = operations.catalogCollections().get(0);
        assertEquals("beta", reloaded.id());
        assertEquals(List.of(SkinModel.SLIM), reloaded.skins().get(0).models());
        assertEquals("nclskins.beta.name", reloaded.nameText().translationKey().orElseThrow());
        assertEquals("pack-b", reloaded.sourceId());
    }

    @Test
    void catalogDiscoveryReusesValidatedVariantsUntilResourceGenerationChanges()
            throws Exception {
        byte[] valid = skinPng(0xFF557799);
        AtomicInteger generation = new AtomicInteger(1);
        AtomicInteger loads = new AtomicInteger();
        List<SkinCatalogSource.CollectionDescriptor> collections =
                ResourcePackSkinCatalog.build(List.of(new ResourcePackSkinCatalog.Variant(
                        "event", "hero", SkinModel.CLASSIC, "pack", 0)));
        SkinCatalogSource source = new SkinCatalogSource() {
            @Override
            public byte[] load(String collectionId, String skinId, SkinModel model) {
                loads.incrementAndGet();
                return valid.clone();
            }

            @Override
            public List<SkinCatalogSource.CollectionDescriptor> collections() {
                return collections;
            }

            @Override
            public long generation() {
                return generation.get();
            }
        };
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), new StubProfileApi(), storage(), source, fixedClock());

        operations.catalogCollections();
        operations.catalogCollections();
        assertEquals(1, loads.get());

        generation.incrementAndGet();
        operations.catalogCollections();
        assertEquals(2, loads.get());
    }

    @Test
    void resourceCapeDiscoveryValidatesCachesPreviewsAndMaterializesByFrozenIdentity()
            throws Exception {
        byte[] valid = customCapePng();
        byte[] invalidHd = skinPng(128, 64, 0xFF557799);
        AtomicInteger generation = new AtomicInteger(4);
        AtomicInteger capeLoads = new AtomicInteger();
        AtomicReference<byte[]> active = new AtomicReference<>(valid);
        List<com.naocraftlab.skins.client.CapeCatalogSource.CollectionDescriptor> capes =
                ResourcePackCapeCatalog.build(List.of(
                        new ResourcePackCapeCatalog.Variant(
                                "event", "hero", "file/event.zip", 0, "1".repeat(64)),
                        new ResourcePackCapeCatalog.Variant(
                                "broken", "hd", "file/event.zip", 0, "2".repeat(64))));
        SkinCatalogSource source = new SkinCatalogSource() {
            @Override
            public byte[] load(String collectionId, String skinId, SkinModel model) {
                return valid.clone();
            }

            @Override
            public List<com.naocraftlab.skins.client.CapeCatalogSource.CollectionDescriptor>
                    capeCollections() {
                return capes;
            }

            @Override
            public byte[] loadCape(String collectionId, String capeId) {
                capeLoads.incrementAndGet();
                return "hero".equals(capeId) ? active.get().clone() : invalidHd.clone();
            }

            @Override
            public long capeGeneration() {
                return generation.get();
            }
        };
        NclSkinsStorage shared = storage();
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), new StubProfileApi(), shared, source, fixedClock());
        operations.warmResourceCapeCatalog(generation.get());
        assertEquals(2, capeLoads.get());
        operations.initialize();
        assertTrue(operations.warmedCapeEditorData(TestFixtures.ACCOUNT_ID).isPresent());

        ClientOperations.CapeEditorData first =
                operations.loadCapeEditorData(TestFixtures.ACCOUNT_ID);
        assertEquals(List.of("event"), first.resourceCollections().stream()
                .map(com.naocraftlab.skins.client.CapeCatalogSource.CollectionDescriptor::id)
                .toList());
        assertEquals(2, capeLoads.get());
        ClientOperations.CapeEditorData cached =
                operations.loadCapeEditorData(TestFixtures.ACCOUNT_ID);
        assertEquals(first.resourceCollections(), cached.resourceCollections());
        assertEquals(2, capeLoads.get());

        var descriptor = first.resourceCollections().get(0).capes().get(0);
        var key = new ClientOperations.ResourceCapeKey("event", "hero");
        var selection = new ClientOperations.ResourceCapeSelection(
                "event", "hero", "Hero cape", descriptor.contentIdentity(),
                first.sourceHashes().get(key), first.resourceGeneration(),
                descriptor.renderSupport()
                        == com.naocraftlab.skins.client.CapeCatalogSource.RenderSupport.CAPE_AND_ELYTRA);
        assertArrayEquals(valid,
                operations.loadResourceCapePreview(TestFixtures.ACCOUNT_ID, selection)
                        .orElseThrow());
        var materialized = operations.materializeResourceCape(
                TestFixtures.ACCOUNT_ID, selection);
        var reused = operations.materializeResourceCape(TestFixtures.ACCOUNT_ID, selection);
        assertEquals(materialized.texture().entryId(), reused.texture().entryId());
        assertEquals(descriptor.contentIdentity(), materialized.renderSha256());
        assertEquals(1, shared.loadOrCreateAccount(TestFixtures.ACCOUNT_ID).personalCapes().size());

        active.set(skinPng(64, 32, 0xFF7799BB));
        assertArrayEquals(valid,
                operations.loadResourceCapePreview(TestFixtures.ACCOUNT_ID, selection)
                        .orElseThrow());
        assertEquals(materialized.texture().entryId(), operations.materializeResourceCape(
                TestFixtures.ACCOUNT_ID, selection).texture().entryId());

        generation.incrementAndGet();
        assertTrue(operations.loadResourceCapePreview(TestFixtures.ACCOUNT_ID, selection).isEmpty());
        int loadsBeforeReload = capeLoads.get();
        ClientOperations.CapeEditorData reloaded =
                operations.loadCapeEditorData(TestFixtures.ACCOUNT_ID);
        assertEquals(5, reloaded.resourceGeneration());
        assertEquals(loadsBeforeReload + 2, capeLoads.get());
    }

    @Test
    void personalCapeMutationsDoNotReindexKnownResourceGeneration() throws Exception {
        byte[] valid = customCapePng();
        AtomicInteger generation = new AtomicInteger(8);
        AtomicInteger collectionReads = new AtomicInteger();
        AtomicInteger resourceLoads = new AtomicInteger();
        SkinCatalogSource source = new SkinCatalogSource() {
            @Override
            public byte[] load(String collectionId, String skinId, SkinModel model) {
                return valid.clone();
            }

            @Override
            public List<com.naocraftlab.skins.client.CapeCatalogSource.CollectionDescriptor>
                    capeCollections() {
                collectionReads.incrementAndGet();
                return ResourcePackCapeCatalog.build(List.of(
                        new ResourcePackCapeCatalog.Variant(
                                "event", "hero", "fixture", 0, "1".repeat(64))));
            }

            @Override
            public byte[] loadCape(String collectionId, String capeId) {
                resourceLoads.incrementAndGet();
                return valid.clone();
            }

            @Override
            public long capeGeneration() {
                return generation.get();
            }
        };
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), new StubProfileApi(), storage(), source, fixedClock());
        operations.initialize();
        operations.loadCapeEditorData(TestFixtures.ACCOUNT_ID);

        Path importPath = temporaryDirectory.resolve("same-cape.png");
        Files.write(importPath, valid);
        var imported = operations.importCape(
                TestFixtures.ACCOUNT_ID, importPath, "Cape");
        operations.loadCapeEditorData(TestFixtures.ACCOUNT_ID);
        operations.renameCape(
                TestFixtures.ACCOUNT_ID, imported.texture().entryId(), "Renamed");
        operations.loadCapeEditorData(TestFixtures.ACCOUNT_ID);
        operations.deleteCape(TestFixtures.ACCOUNT_ID, imported.texture().entryId());
        operations.loadCapeEditorData(TestFixtures.ACCOUNT_ID);
        assertEquals(1, collectionReads.get());
        assertEquals(1, resourceLoads.get());

        generation.incrementAndGet();
        operations.loadCapeEditorData(TestFixtures.ACCOUNT_ID);
        assertEquals(2, collectionReads.get());
        assertEquals(2, resourceLoads.get());
    }

    @Test
    void unknownResourceCapeGenerationNeverCachesDiscovery() throws Exception {
        byte[] valid = customCapePng();
        AtomicInteger collectionReads = new AtomicInteger();
        SkinCatalogSource source = new SkinCatalogSource() {
            @Override
            public byte[] load(String collectionId, String skinId, SkinModel model) {
                return valid.clone();
            }

            @Override
            public List<com.naocraftlab.skins.client.CapeCatalogSource.CollectionDescriptor>
                    capeCollections() {
                collectionReads.incrementAndGet();
                return ResourcePackCapeCatalog.build(List.of(
                        new ResourcePackCapeCatalog.Variant(
                                "event", "hero", "fixture", 0, "1".repeat(64))));
            }

            @Override
            public byte[] loadCape(String collectionId, String capeId) {
                return valid.clone();
            }

            @Override
            public long capeGeneration() {
                return Long.MIN_VALUE;
            }
        };
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), new StubProfileApi(), storage(), source, fixedClock());
        operations.initialize();

        operations.loadCapeEditorData(TestFixtures.ACCOUNT_ID);
        operations.loadCapeEditorData(TestFixtures.ACCOUNT_ID);

        assertEquals(2, collectionReads.get());
    }

    @Test
    void catalogDiscoveryDropsOnlyAResourceCollectionWhoseEveryVariantIsInvalid()
            throws Exception {
        byte[] valid = skinPng(0xFF779955);
        List<SkinCatalogSource.CollectionDescriptor> collections =
                ResourcePackSkinCatalog.build(List.of(
                        new ResourcePackSkinCatalog.Variant(
                                "broken", "bad", SkinModel.CLASSIC, "pack", 0),
                        new ResourcePackSkinCatalog.Variant(
                                "working", "good", SkinModel.SLIM, "pack", 0)));
        SkinCatalogSource source = new SkinCatalogSource() {
            @Override
            public byte[] load(String collectionId, String skinId, SkinModel model) {
                return "working".equals(collectionId) ? valid.clone() : new byte[0];
            }

            @Override
            public List<SkinCatalogSource.CollectionDescriptor> collections() {
                return collections;
            }
        };
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), new StubProfileApi(), storage(), source, fixedClock());

        assertEquals(
                List.of("working"),
                operations.catalogCollections().stream()
                        .map(SkinCatalogSource.CollectionDescriptor::id)
                        .toList());
    }

    @Test
    void catalogLoadRejectsBytesFromAResourceGenerationNewerThanTheOpenCatalog()
            throws Exception {
        byte[] first = skinPng(0xFF224466);
        byte[] reloaded = skinPng(0xFF6688AA);
        AtomicReference<byte[]> bytes = new AtomicReference<>(first);
        List<SkinCatalogSource.CollectionDescriptor> collections =
                ResourcePackSkinCatalog.build(List.of(
                        new ResourcePackSkinCatalog.Variant(
                                "event", "hero", SkinModel.CLASSIC, "file/event.zip", 0)));
        SkinCatalogSource source = new SkinCatalogSource() {
            @Override
            public byte[] load(String collectionId, String skinId, SkinModel model) {
                return bytes.get().clone();
            }

            @Override
            public List<SkinCatalogSource.CollectionDescriptor> collections() {
                return collections;
            }
        };
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), new StubProfileApi(), storage(), source, fixedClock());

        operations.catalogCollections();
        assertArrayEquals(
                first, operations.loadCatalogSkin("event", "hero", SkinModel.CLASSIC));
        bytes.set(reloaded);

        assertThrows(
                IOException.class,
                () -> operations.loadCatalogSkin("event", "hero", SkinModel.CLASSIC));

        operations.catalogCollections();
        assertArrayEquals(
                reloaded, operations.loadCatalogSkin("event", "hero", SkinModel.CLASSIC));
    }

    @Test
    void legacyRecoveryMethodsCannotBypassTheDurableAppearanceIntent()
            throws Exception {
        byte[] skin = skinPng(0xFF426A8C);
        StubProfileApi api = new StubProfileApi();
        NclSkinsStorage storage = storage();
        AtomicInteger tokenRequests = new AtomicInteger();
        GameSessionTokenSource countingTokens = new GameSessionTokenSource() {
            @Override
            public SessionIdentity currentSession() {
                return new SessionIdentity(TestFixtures.ACCOUNT_ID, "Player");
            }

            @Override
            public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) throws E {
                tokenRequests.incrementAndGet();
                return request.execute("must-not-be-requested");
            }
        };
        DefaultClientOperations operations = new DefaultClientOperations(
                countingTokens, api, storage, ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        UUID skinId = initial.account().skinAssets().get(0).id();
        ClientOperations.EditorSave saved = operations.saveEditor(
                new ClientOperations.EditorSaveRequest(
                        Optional.empty(),
                        "Remote mutation",
                        SkinReference.asset(skinId),
                        SkinVariant.CLASSIC,
                        SkinVariant.CLASSIC,
                        Optional.of("cape-owned"),
                        Optional.empty()));
        PresetApplicationOutcome legacySnapshot = new PresetApplicationOutcome(
                MutationResult.FAILED,
                ApplicationPhase.VALIDATION,
                null,
                null,
                null,
                ApiFailureKind.NETWORK,
                Set.of(),
                RemoteAppearanceImpact.NONE,
                "legacy snapshot");

        operations.rememberActivePreset(TestFixtures.ACCOUNT_ID, Optional.of(saved.presetId()));
        assertFalse(storage.loadAppearance(TestFixtures.ACCOUNT_ID).hasIntent());
        assertThrows(
                UnsupportedOperationException.class,
                () -> operations.restorePreviousAppearance(legacySnapshot));
        assertThrows(IllegalStateException.class, () -> operations.retryCape("cape-owned"));

        ClientOperations.PresetUse selected = operations.usePreset(saved.presetId());
        assertThrows(IllegalStateException.class, () -> operations.retryCape("cape-owned"));

        assertEquals(AppearanceSyncStatus.PENDING, selected.syncStatus());
        assertEquals(0, tokenRequests.get());
        assertEquals(0, api.profileGets.get());
        assertEquals(0, api.skinUploads.get());
        assertEquals(0, api.skinResets.get());
        assertEquals(0, api.capeActivations.get());
        assertEquals(0, api.capeDeactivations.get());
    }

    @Test
    void galleryInitializationReusesStartupSessionWithoutFreshProfileRequest()
            throws Exception {
        StubProfileApi api = new StubProfileApi();
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, storage(), ignored -> skinPng(0xFF224488), fixedClock());

        operations.warmSession();
        assertTrue(operations.warmedInitialData().orElseThrow().session().valid());

        ClientOperations.InitialData initialized = operations.initialize();

        assertTrue(initialized.session().valid());
        assertEquals(1, api.profileGets.get());
    }

    @Test
    void explicitSessionRetryClassifiesOfflineAccountWithoutProfileRequest()
            throws Exception {
        StubProfileApi api = new StubProfileApi();
        GameSessionTokenSource offlineTokens = new GameSessionTokenSource() {
            @Override
            public SessionIdentity currentSession() {
                return new SessionIdentity(TestFixtures.ACCOUNT_ID, "Offline Player");
            }

            @Override
            public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) {
                throw new GameSessionTokenUnavailableException();
            }
        };
        DefaultClientOperations operations = new DefaultClientOperations(
                offlineTokens, api, storage(), ignored -> skinPng(0xFF224488), fixedClock());

        ClientOperations.InitialData initialized = operations.retrySession();

        assertFalse(initialized.session().valid());
        assertEquals(ApiFailureKind.TOKEN_UNAVAILABLE, initialized.session().failureKind());
        assertEquals(0, api.profileGets.get());
    }

    @Test
    void explicitSessionRetryPublishesConfirmedRecoverableSessionLoss()
            throws Exception {
        StubProfileApi api = new StubProfileApi();
        api.profileFailure = new ProfileApiException(
                ApiFailureKind.NETWORK, "offline", null, null, false);
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, storage(), ignored -> skinPng(0xFF224488), fixedClock());

        ClientOperations.InitialData initialized = operations.retrySession();

        assertFalse(initialized.session().valid());
        assertEquals(ApiFailureKind.NETWORK, initialized.session().failureKind());
        assertEquals(1, api.profileGets.get());
    }

    @Test
    void startupWarmupCreatesOneActivePlayerNamedPresetFromTheCurrentOfficialSkin()
            throws Exception {
        byte[] classic = skinPng(0xFF336699);
        byte[] slim = skinPng(0xFF669933);
        URI skinUri = URI.create("https://textures.minecraft.net/texture/bootstrap-skin");
        URI capeUri = URI.create("https://textures.minecraft.net/texture/bootstrap-cape");
        StubProfileApi api = new StubProfileApi();
        api.profile = profileWithActiveAppearance(skinUri, capeUri);
        NclSkinsStorage storage = storage();
        storage.initialize();
        Path cachedSkin = new com.naocraftlab.skins.core.storage.TextureCache(storage)
                .cachePath(skinUri);
        Files.write(cachedSkin, classic);
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(),
                api,
                storage,
                model -> model == SkinModel.SLIM ? slim.clone() : classic.clone(),
                fixedClock());

        operations.warmSession();
        ClientOperations.InitialData initial = operations.initialize();

        assertEquals(1, api.profileGets.get());
        assertEquals(3, initial.account().skinAssets().size());
        assertEquals(1, initial.account().presets().size());
        var preset = initial.account().presets().get(0);
        assertEquals("Player", preset.name());
        assertEquals("cape-active", preset.capeId());
        assertEquals(initial.currentOfficialSkinId().orElseThrow(), preset.skin().assetId());
        assertEquals(SkinSource.CURRENT_OFFICIAL, initial.account().skinAssets().stream()
                .filter(asset -> asset.id().equals(preset.skin().assetId()))
                .findFirst()
                .orElseThrow()
                .source());
        assertEquals(Optional.of(preset.id()), initial.activePresetId());

        ClientOperations.InitialData reopened = operations.initialize();
        assertEquals(1, reopened.account().presets().size());
        assertEquals(Optional.of(preset.id()), reopened.activePresetId());
        assertEquals(1, api.profileGets.get());

        ClientOperations.InitialData reset = operations.resetLibrary();
        assertTrue(reset.account().presets().isEmpty());
        assertTrue(reset.activePresetId().isEmpty());
        assertTrue(reset.pendingOfficialSync());
        assertEquals(AppearanceSyncStatus.PENDING, reset.syncStatus());
        assertEquals(initial.localAppearance().orElseThrow().localSkinSha256(),
                reset.localAppearance().orElseThrow().localSkinSha256());
        assertFalse(storage.loadAppearance(TestFixtures.ACCOUNT_ID).providers().skin()
                .offline().optionalValue().isPresent());
        assertEquals(1, api.profileGets.get());
        assertEquals(0, api.skinResets.get());
    }

    @Test
    void galleryInitializationReloadsLibraryChangesMadeAfterStartupWarmup() throws Exception {
        byte[] classic = skinPng(0xFF224488);
        NclSkinsStorage storage = storage();
        StubProfileApi api = new StubProfileApi();
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(),
                api,
                storage,
                ignored -> classic.clone(),
                fixedClock());

        operations.warmSession();
        ClientOperations.InitialData warmedSeed = operations.warmedInitialData().orElseThrow();
        assertTrue(warmedSeed.account().presets().isEmpty());
        LibraryService otherProcess = new LibraryService(storage, fixedClock());
        AccountState warmed = otherProcess.load(TestFixtures.ACCOUNT_ID);
        UUID classicId = warmed.skinAssets().stream()
                .filter(asset -> asset.source() == SkinSource.VANILLA_DEFAULT)
                .findFirst()
                .orElseThrow()
                .id();
        AccountState changed = otherProcess.createPreset(
                TestFixtures.ACCOUNT_ID,
                "Created elsewhere",
                SkinReference.asset(classicId),
                null);

        ClientOperations.InitialData initialized = operations.initialize();

        assertEquals(changed.presets(), initialized.account().presets());
        assertEquals("Created elsewhere", initialized.account().presets().get(0).name());
        assertEquals(1, api.profileGets.get());
        assertTrue(operations.warmedInitialData().isEmpty(),
                "the startup seed must not replace a fresh gallery reload");
    }

    @Test
    void warmedInitialDataDoesNotWaitForStartupWarmupMonitor() throws Exception {
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(),
                new StubProfileApi(),
                storage(),
                ignored -> skinPng(0xFF224488),
                fixedClock());
        CountDownLatch monitorHeld = new CountDownLatch(1);
        CountDownLatch releaseMonitor = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> holder = executor.submit(() -> {
            synchronized (operations) {
                monitorHeld.countDown();
                try {
                    assertTrue(releaseMonitor.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }
        });

        try {
            assertTrue(monitorHeld.await(5, TimeUnit.SECONDS));
            Future<Optional<ClientOperations.InitialData>> seed =
                    executor.submit(operations::warmedInitialData);
            assertTrue(seed.get(500, TimeUnit.MILLISECONDS).isEmpty(),
                    "reading the optional startup seed must never block the client thread");
        } finally {
            releaseMonitor.countDown();
            holder.get(5, TimeUnit.SECONDS);
            executor.shutdownNow();
        }
    }

    @Test
    void activeDeleteFallsThroughToObservedMinecraftUntilResetAcknowledgement()
            throws Exception {
        byte[] skin = skinPng(0xFF42688A);
        URI skinUri = URI.create("https://textures.minecraft.net/texture/stale-warm-profile");
        StubProfileApi api = new StubProfileApi();
        api.profile = profileWithActiveAppearance(skinUri, null);
        NclSkinsStorage storage = storage();
        storage.initialize();
        Files.write(
                new com.naocraftlab.skins.core.storage.TextureCache(storage).cachePath(skinUri),
                skin);
        DefaultClientOperations firstClient = new DefaultClientOperations(
                tokens(), api, storage, ignored -> skin.clone(), fixedClock());
        firstClient.initialize();
        ClientOperations.ReconciliationResult firstOpen = firstClient
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();
        UUID presetId = firstOpen.appearance().activePresetId().orElseThrow();
        assertEquals(1, api.profileGets.get());

        ClientOperations.PresetDelete deletion = firstClient.deletePreset(presetId);

        assertTrue(deletion.account().presets().isEmpty());
        assertTrue(deletion.remoteReset().isEmpty());
        ClientOperations.DurableAppearance localDefault = deletion.appearance().orElseThrow();
        assertEquals(AppearanceSyncStatus.PENDING, localDefault.syncStatus());
        assertTrue(localDefault.activePresetId().isEmpty());
        assertEquals(firstOpen.appearance().localAppearance().orElseThrow().localSkinSha256(),
                localDefault.localAppearance().orElseThrow().localSkinSha256());
        assertEquals(0, api.skinResets.get());

        DefaultClientOperations reopenedClient = new DefaultClientOperations(
                tokens(), api, storage, ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData reopened = reopenedClient.initialize();
        assertTrue(reopened.account().presets().isEmpty());
        assertTrue(reopened.activePresetId().isEmpty());
        assertTrue(reopened.pendingOfficialSync());
        assertEquals(AppearanceSyncStatus.PENDING, reopened.syncStatus());
        assertEquals(localDefault.localAppearance().orElseThrow().localSkinSha256(),
                reopened.localAppearance().orElseThrow().localSkinSha256());
        assertEquals(0, api.skinResets.get());
    }

    @Test
    void emptyCreateDeleteRevisionIsObservedEvenWhenBothSnapshotsAreEmpty() throws Exception {
        byte[] skin = skinPng(0xFF386A5B);
        StubProfileApi api = new StubProfileApi();
        NclSkinsStorage storage = storage();
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, storage, ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        assertTrue(initial.account().presets().isEmpty());
        assertEquals(0, api.profileGets.get());
        assertTrue(operations.initialize().account().presets().isEmpty());
        assertEquals(0, api.profileGets.get());

        LibraryService otherClient = new LibraryService(storage, fixedClock());
        UUID classicId = initial.account().skinAssets().stream()
                .filter(asset -> asset.source() == SkinSource.VANILLA_DEFAULT)
                .findFirst()
                .orElseThrow()
                .id();
        AccountState created = otherClient.createPreset(
                TestFixtures.ACCOUNT_ID,
                "Transient",
                SkinReference.asset(classicId),
                null);
        UUID transientPreset = created.presets().get(0).id();
        DefaultClientOperations otherOperations = new DefaultClientOperations(
                tokens(), api, storage(), ignored -> skin.clone(), fixedClock());
        AccountState externallyEmpty = otherOperations.deletePreset(transientPreset).account();
        assertTrue(externallyEmpty.presets().isEmpty());
        assertNotEquals(initial.account().updatedAt(), externallyEmpty.updatedAt());

        ClientOperations.InitialData refreshed = operations.initialize();
        assertTrue(refreshed.account().presets().isEmpty());
        assertEquals(0, api.profileGets.get());

        ClientOperations.InitialData unchanged = operations.initialize();
        assertTrue(unchanged.account().presets().isEmpty());
        assertEquals(0, api.profileGets.get());
    }

    @Test
    void inactiveDeleteIsPurelyLocalAndDoesNotReplaceTheActiveIntent() throws Exception {
        byte[] skin = skinPng(0xFF274F68);
        StubProfileApi api = new StubProfileApi();
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, storage(), ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        ClientOperations.EditorSave active = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Active",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        ClientOperations.EditorSave inactive = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Inactive",
                SkinReference.asset(initial.account().skinAssets().get(1).id()),
                SkinVariant.SLIM,
                SkinVariant.SLIM,
                Optional.empty(),
                Optional.empty()));
        ClientOperations.PresetUse selected = operations.usePreset(active.presetId());

        ClientOperations.PresetDelete deletion = operations.deletePreset(inactive.presetId());

        assertEquals(List.of(active.presetId()), deletion.account().presets().stream()
                .map(preset -> preset.id())
                .toList());
        assertTrue(deletion.remoteReset().isEmpty());
        assertTrue(deletion.appearance().isEmpty());
        ClientOperations.DurableAppearance durable = operations.durableAppearance().orElseThrow();
        assertEquals(Optional.of(active.presetId()), durable.activePresetId());
        assertEquals(selected.intentRevision(), durable.intentRevision());
        assertEquals(AppearanceSyncStatus.PENDING, durable.syncStatus());
        assertEquals(0, api.skinUploads.get());
        assertEquals(0, api.skinResets.get());
    }

    @Test
    void deletingLastInactivePresetPublishesAFreshAccountDefaultIntent() throws Exception {
        byte[] skin = skinPng(0xFF284F69);
        StubProfileApi api = new StubProfileApi();
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, storage(), ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        ClientOperations.EditorSave active = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Active",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        ClientOperations.EditorSave inactive = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Inactive",
                SkinReference.asset(initial.account().skinAssets().get(1).id()),
                SkinVariant.SLIM,
                SkinVariant.SLIM,
                Optional.empty(),
                Optional.empty()));
        operations.usePreset(active.presetId());

        ClientOperations.DurableAppearance firstDefault = operations
                .deletePreset(active.presetId())
                .appearance()
                .orElseThrow();
        ClientOperations.PresetDelete finalDeletion = operations.deletePreset(inactive.presetId());
        ClientOperations.DurableAppearance finalDefault = finalDeletion.appearance().orElseThrow();

        assertTrue(finalDeletion.account().presets().isEmpty());
        assertTrue(finalDefault.activePresetId().isEmpty());
        assertTrue(finalDefault.localAppearance().orElseThrow().usesAccountDefaultSkin());
        assertEquals(AppearanceSyncStatus.PENDING, finalDefault.syncStatus());
        assertTrue(finalDefault.intentRevision() > firstDefault.intentRevision());
        assertEquals(0, api.profileGets.get());
        assertEquals(0, api.skinResets.get());
    }

    @Test
    void failedAccountReplaceLeavesPendingDefaultAndRetryCompletesDeletionWithAFreshIntent()
            throws Exception {
        byte[] skin = skinPng(0xFF2A4F6B);
        StubProfileApi api = new StubProfileApi();
        NclSkinsStorage shared = storage();
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, shared, ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Final",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        operations.usePreset(saved.presetId());
        Path blockedBackup = shared.layout().accountBackup(TestFixtures.ACCOUNT_ID);
        Files.deleteIfExists(blockedBackup);
        Files.createDirectories(blockedBackup);

        assertThrows(IOException.class, () -> operations.deletePreset(saved.presetId()));

        assertEquals(List.of(saved.presetId()), shared.loadOrCreateAccount(TestFixtures.ACCOUNT_ID)
                .presets()
                .stream()
                .map(preset -> preset.id())
                .toList());
        var pendingDefault = shared.loadAppearance(TestFixtures.ACCOUNT_ID);
        assertEquals(AppearanceSyncStatus.PENDING, pendingDefault.syncStatus());
        assertNull(pendingDefault.activePresetId());
        assertNull(pendingDefault.skinSha256());
        Files.delete(blockedBackup);

        ClientOperations.PresetDelete retry = operations.deletePreset(saved.presetId());

        assertTrue(retry.account().presets().isEmpty());
        assertTrue(retry.appearance().orElseThrow().intentRevision()
                > pendingDefault.intentRevision());
        assertEquals(0, api.profileGets.get());
        assertEquals(0, api.skinResets.get());
    }

    @Test
    void failedAppearanceReplaceDoesNotRemoveTheFinalPresetAndRetryIsSafe() throws Exception {
        byte[] skin = skinPng(0xFF2C4F6D);
        StubProfileApi api = new StubProfileApi();
        NclSkinsStorage shared = storage();
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, shared, ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Final",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        operations.usePreset(saved.presetId());
        Path blockedAppearance = shared.layout().accountAppearance(TestFixtures.ACCOUNT_ID);
        Files.delete(blockedAppearance);
        Files.createDirectories(blockedAppearance);

        assertThrows(IOException.class, () -> operations.deletePreset(saved.presetId()));

        assertEquals(List.of(saved.presetId()), shared.loadOrCreateAccount(TestFixtures.ACCOUNT_ID)
                .presets()
                .stream()
                .map(preset -> preset.id())
                .toList());
        Files.delete(blockedAppearance);

        ClientOperations.PresetDelete retry = operations.deletePreset(saved.presetId());

        assertTrue(retry.account().presets().isEmpty());
        assertTrue(retry.appearance().orElseThrow().activePresetId().isEmpty());
        assertEquals(AppearanceSyncStatus.PENDING, retry.appearance().orElseThrow().syncStatus());
        assertEquals(0, api.profileGets.get());
        assertEquals(0, api.skinResets.get());
    }

    @Test
    void deletedPresetCannotPublishANewAppearanceIntent() throws Exception {
        byte[] skin = skinPng(0xFF294F6A);
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), new StubProfileApi(), storage(), ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        ClientOperations.EditorSave kept = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Kept",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        ClientOperations.EditorSave removed = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Removed",
                SkinReference.asset(initial.account().skinAssets().get(1).id()),
                SkinVariant.SLIM,
                SkinVariant.SLIM,
                Optional.empty(),
                Optional.empty()));
        ClientOperations.PresetUse selected = operations.usePreset(kept.presetId());
        operations.deletePreset(removed.presetId());

        LibraryOperationException failure = assertThrows(
                LibraryOperationException.class,
                () -> operations.usePreset(removed.presetId()));

        assertEquals(LibraryOperationException.Code.PRESET_NOT_FOUND, failure.code());
        assertEquals(selected.intentRevision(), operations.durableAppearance().orElseThrow().intentRevision());
        assertEquals(Optional.of(kept.presetId()), operations.durableAppearance().orElseThrow().activePresetId());
    }

    @Test
    void concurrentDeleteAndApplyNeverLeaveTheDeletedPresetAsDurableActive() throws Exception {
        byte[] skin = skinPng(0xFF2B4F6C);
        StubProfileApi api = new StubProfileApi();
        NclSkinsStorage shared = storage();
        DefaultClientOperations first = new DefaultClientOperations(
                tokens(), api, shared, ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = first.initialize();
        ClientOperations.EditorSave kept = first.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Kept",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        ClientOperations.EditorSave raced = first.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Raced",
                SkinReference.asset(initial.account().skinAssets().get(1).id()),
                SkinVariant.SLIM,
                SkinVariant.SLIM,
                Optional.empty(),
                Optional.empty()));
        first.usePreset(kept.presetId());
        DefaultClientOperations second = new DefaultClientOperations(
                tokens(), api, shared, ignored -> skin.clone(), fixedClock());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ClientOperations.PresetDelete> deletion = executor.submit(() -> {
                start.await();
                return first.deletePreset(raced.presetId());
            });
            Future<Boolean> selection = executor.submit(() -> {
                start.await();
                try {
                    second.usePreset(raced.presetId());
                    return true;
                } catch (LibraryOperationException deletedFirst) {
                    return false;
                }
            });
            start.countDown();

            assertTrue(deletion.get(2, TimeUnit.SECONDS).account().presets().stream()
                    .noneMatch(preset -> preset.id().equals(raced.presetId())));
            selection.get(2, TimeUnit.SECONDS);
            AccountState stored = shared.loadOrCreateAccount(TestFixtures.ACCOUNT_ID);
            var appearance = shared.loadAppearance(TestFixtures.ACCOUNT_ID);
            assertTrue(stored.presets().stream()
                    .noneMatch(preset -> preset.id().equals(raced.presetId())));
            assertFalse(raced.presetId().equals(appearance.activePresetId()));
            assertTrue(appearance.activePresetId() == null
                    || kept.presetId().equals(appearance.activePresetId()));
            assertEquals(AppearanceSyncStatus.PENDING, appearance.syncStatus());
            assertEquals(0, api.profileGets.get());
            assertEquals(0, api.skinResets.get());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void deletingActivePresetDefersAccountDefaultResetUntilReconciliation()
            throws Exception {
        byte[] classic = skinPng(0xFF112233);
        byte[] slim = skinPng(0xFF332211);
        URI skinUri = URI.create("https://textures.minecraft.net/texture/delete-last-skin");
        StubProfileApi api = new StubProfileApi();
        api.profile = profileWithActiveAppearance(skinUri, null);
        NclSkinsStorage storage = storage();
        storage.initialize();
        Files.write(new com.naocraftlab.skins.core.storage.TextureCache(storage).cachePath(skinUri), classic);
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(),
                api,
                storage,
                model -> model == SkinModel.SLIM ? slim.clone() : classic.clone(),
                fixedClock());
        operations.initialize();
        ClientOperations.ReconciliationResult initial = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();
        UUID presetId = initial.account().presets().get(0).id();

        ClientOperations.PresetDelete deleted = operations.deletePreset(presetId);

        assertEquals(0, api.skinResets.get());
        assertTrue(deleted.account().presets().isEmpty());
        assertTrue(deleted.remoteReset().isEmpty());
        assertEquals(initial.appearance().localAppearance().orElseThrow().localSkinSha256(),
                deleted.appearance().orElseThrow().localAppearance().orElseThrow().localSkinSha256());

        ClientOperations.ReconciliationResult reset = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();

        assertEquals(MutationResult.APPLIED, reset.outcome().orElseThrow().result());
        assertEquals(AppearanceSyncStatus.OFFICIAL, reset.appearance().syncStatus());
        assertTrue(reset.appearance().activePresetId().isEmpty());
        assertEquals(1, api.skinResets.get());

        ClientOperations.InitialData reopened = operations.initialize();
        assertTrue(reopened.account().presets().isEmpty());
        assertTrue(reopened.activePresetId().isEmpty());
        assertEquals(AppearanceSyncStatus.OFFICIAL, reopened.syncStatus());
        assertEquals(2, api.profileGets.get());
    }

    @Test
    void anotherInstanceReopensDeletedActivePresetAsAccountDefaultPending()
            throws Exception {
        byte[] skin = skinPng(0xFF315A79);
        StubProfileApi api = new StubProfileApi();
        NclSkinsStorage storage = storage();
        DefaultClientOperations firstClient = new DefaultClientOperations(
                tokens(), api, storage, ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = firstClient.initialize();
        ClientOperations.EditorSave preset = firstClient.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Active",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        firstClient.usePreset(preset.presetId());
        firstClient.deletePreset(preset.presetId());

        DefaultClientOperations secondClient = new DefaultClientOperations(
                tokens(), api, storage, ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData reopened = secondClient.initialize();

        assertTrue(reopened.account().presets().isEmpty());
        assertTrue(reopened.activePresetId().isEmpty());
        assertEquals(AppearanceSyncStatus.PENDING, reopened.syncStatus());
        assertTrue(reopened.localAppearance().orElseThrow().usesAccountDefaultSkin());
        assertEquals(0, api.skinResets.get());
    }

    @Test
    void freshAccountDefaultProfileSettlesResetIntentWithoutAnyMutation() throws Exception {
        byte[] skin = skinPng(0xFF4A6278);
        StubProfileApi api = new StubProfileApi();
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, storage(), ignored -> skin.clone(), fixedClock());
        operations.initialize();
        ClientOperations.InitialData reset = operations.resetLibrary();

        ClientOperations.ReconciliationResult reconciled = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();

        assertEquals(AppearanceSyncStatus.PENDING, reset.syncStatus());
        assertEquals(AppearanceSyncStatus.OFFICIAL, reconciled.appearance().syncStatus());
        assertTrue(reconciled.appearance().activePresetId().isEmpty());
        assertTrue(reconciled.outcome().isEmpty());
        assertEquals(1, api.profileGets.get());
        assertEquals(0, api.skinUploads.get());
        assertEquals(0, api.skinResets.get());
        assertEquals(0, api.capeActivations.get());
        assertEquals(0, api.capeDeactivations.get());
    }

    @Test
    void accountDefaultWithDifferentCapeMutatesOnlyCapeAndReportsConfirmedImpact()
            throws Exception {
        byte[] skin = skinPng(0xFF6C4B73);
        URI capeUri = URI.create("https://textures.minecraft.net/texture/account-default-cape");
        StubProfileApi api = new StubProfileApi();
        api.profile = new RemoteProfile(
                TestFixtures.ACCOUNT_ID,
                "Player",
                List.of(),
                List.of(new RemoteCape(
                        "cape-owned", RemoteAssetState.INACTIVE, capeUri, "Owned cape")),
                Set.of());
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, storage(), ignored -> skin.clone(), fixedClock());
        operations.initialize();
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Default with cape",
                SkinReference.accountDefault(),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.of("cape-owned"),
                Optional.empty()));
        operations.usePreset(saved.presetId());

        ClientOperations.ReconciliationResult reconciled = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();
        PresetApplicationOutcome outcome = reconciled.outcome().orElseThrow();

        assertEquals(AppearanceSyncStatus.OFFICIAL, reconciled.appearance().syncStatus());
        assertEquals(MutationResult.APPLIED, outcome.result());
        assertEquals(RemoteAppearanceImpact.CONFIRMED_CHANGED, outcome.remoteAppearanceImpact());
        assertEquals(1, api.profileGets.get());
        assertEquals(0, api.skinUploads.get());
        assertEquals(0, api.skinResets.get());
        assertEquals(1, api.capeActivations.get());
        assertEquals(0, api.capeDeactivations.get());
    }

    @Test
    void ownedCapeWarmupAndPreviewWithoutValidatedCheckpointDoNotAcquireTokenOrUseApi()
            throws Exception {
        byte[] skin = skinPng(0xFF375E79);
        URI capeUri = URI.create("https://textures.minecraft.net/texture/no-checkpoint-warmup");
        NclSkinsStorage storage = storage();
        storage.saveOwnedCapes(new com.naocraftlab.skins.core.model.OwnedCapeInventory(
                com.naocraftlab.skins.core.model.OwnedCapeInventory.CURRENT_SCHEMA_VERSION,
                TestFixtures.ACCOUNT_ID,
                List.of(new com.naocraftlab.skins.core.model.OwnedCapeEntry(
                        "cape-owned", "Owned cape", RemoteAssetState.INACTIVE, null)),
                fixedClock().instant()));
        StubProfileApi api = new StubProfileApi();
        api.profile = new RemoteProfile(
                TestFixtures.ACCOUNT_ID,
                "Player",
                List.of(),
                List.of(new RemoteCape(
                        "cape-owned", RemoteAssetState.INACTIVE, capeUri, "Owned cape")),
                Set.of());
        AtomicInteger tokenRequests = new AtomicInteger();
        GameSessionTokenSource countingTokens = new GameSessionTokenSource() {
            @Override
            public SessionIdentity currentSession() {
                return new SessionIdentity(TestFixtures.ACCOUNT_ID, "Player");
            }

            @Override
            public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) throws E {
                tokenRequests.incrementAndGet();
                return request.execute("scoped-token");
            }
        };
        DefaultClientOperations operations = new DefaultClientOperations(
                countingTokens, api, storage, ignored -> skin.clone(), fixedClock());
        operations.initialize();

        operations.warmOwnedCapeCache();
        assertTrue(operations.loadCapePreview("cape-owned").isEmpty());

        assertEquals(0, tokenRequests.get());
        assertEquals(0, api.profileGets.get());
        assertFalse(Files.exists(new com.naocraftlab.skins.core.storage.TextureCache(storage)
                .cachePath(capeUri)));
    }

    @Test
    void missingTokenKeepsPendingDeliveryAndSessionRefreshCanRecover() throws Exception {
        byte[] classic = skinPng(0xFF445566);
        StubProfileApi api = new StubProfileApi();
        AtomicInteger tokenRequests = new AtomicInteger();
        AtomicReference<String> availableToken = new AtomicReference<>();
        GameSessionTokenSource missingToken = new GameSessionTokenSource() {
            @Override
            public SessionIdentity currentSession() {
                return new SessionIdentity(TestFixtures.ACCOUNT_ID, "Offline");
            }

            @Override
            public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) throws E {
                tokenRequests.incrementAndGet();
                String token = availableToken.get();
                if (token == null) {
                    throw new GameSessionTokenUnavailableException();
                }
                return request.execute(token);
            }
        };
        DefaultClientOperations operations = new DefaultClientOperations(
                missingToken,
                api,
                storage(),
                ignored -> classic.clone(),
                fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Offline",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));

        ClientOperations.PresetUse selected = operations.usePreset(saved.presetId());
        ClientOperations.ReconciliationResult checkpoint = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();
        ClientOperations.ReconciliationResult automatic = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.RECONNECT)
                .orElseThrow();

        assertTrue(selected.localAppearance().isPresent());
        assertEquals(AppearanceSyncStatus.PENDING, checkpoint.appearance().syncStatus());
        assertEquals(AppearanceSyncStatus.PENDING, automatic.appearance().syncStatus());
        assertEquals(selected.intentRevision(), automatic.appearance().intentRevision());
        assertEquals(ProviderDelivery.Status.PENDING,
                automatic.appearance().providers().skin().minecraftDelivery().status());
        assertEquals(2, tokenRequests.get());
        assertEquals(0, api.profileGets.get());
        assertEquals(0, api.skinUploads.get());
        assertEquals(0, api.skinResets.get());

        availableToken.set("restored-token");
        ClientOperations.ReconciliationResult explicit = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.SESSION_REFRESHED)
                .orElseThrow();

        assertEquals(AppearanceSyncStatus.OFFICIAL, explicit.appearance().syncStatus());
        assertEquals(3, tokenRequests.get());
        assertEquals(1, api.profileGets.get());
        assertEquals(1, api.skinUploads.get());
    }

    @Test
    void missingTokenWithoutIntentIsAttemptedOnceUntilExplicitRetry() throws Exception {
        byte[] classic = skinPng(0xFF345678);
        StubProfileApi api = new StubProfileApi();
        AtomicInteger tokenRequests = new AtomicInteger();
        AtomicReference<String> availableToken = new AtomicReference<>();
        GameSessionTokenSource missingToken = new GameSessionTokenSource() {
            @Override
            public SessionIdentity currentSession() {
                return new SessionIdentity(TestFixtures.ACCOUNT_ID, "Offline");
            }

            @Override
            public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) throws E {
                tokenRequests.incrementAndGet();
                String token = availableToken.get();
                if (token == null) {
                    throw new IllegalStateException("no active token");
                }
                return request.execute(token);
            }
        };
        DefaultClientOperations operations = new DefaultClientOperations(
                missingToken,
                api,
                storage(),
                ignored -> classic.clone(),
                fixedClock());
        ClientOperations.InitialData initial = operations.initialize();

        ClientOperations.ReconciliationResult first = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();
        ClientOperations.ReconciliationResult reopen = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();

        assertEquals(0, first.appearance().intentRevision());
        assertEquals(0, reopen.appearance().intentRevision());
        assertTrue(first.appearance().activePresetId().isEmpty());
        assertTrue(reopen.appearance().activePresetId().isEmpty());
        assertEquals(1, tokenRequests.get());
        assertEquals(0, api.profileGets.get());

        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Pending after missing token",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        operations.usePreset(saved.presetId());
        ClientOperations.ReconciliationResult blockedIntent = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.LOCAL_INTENT)
                .orElseThrow();

        assertEquals(AppearanceSyncStatus.PENDING, blockedIntent.appearance().syncStatus());
        assertEquals(1, tokenRequests.get());
        assertEquals(0, api.profileGets.get());
        assertEquals(0, api.skinUploads.get());

        availableToken.set("restored-token");
        ClientOperations.ReconciliationResult explicit = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.EXPLICIT_RETRY)
                .orElseThrow();

        assertTrue(explicit.session().valid());
        assertEquals(AppearanceSyncStatus.OFFICIAL, explicit.appearance().syncStatus());
        assertEquals(2, tokenRequests.get());
        assertEquals(1, api.profileGets.get());
        assertEquals(1, api.skinUploads.get());
    }

    @Test
    void revisionZeroObservationDoesNotConsumeConcurrentLocalIntent() throws Exception {
        byte[] skin = skinPng(0xFF426A83);
        StubProfileApi api = new StubProfileApi();
        CountDownLatch profileStarted = new CountDownLatch(1);
        CountDownLatch releaseProfile = new CountDownLatch(1);
        api.beforeProfileGet = () -> {
            profileStarted.countDown();
            try {
                assertTrue(releaseProfile.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        };
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, storage(), ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ClientOperations.ReconciliationResult> revisionZero = pool.submit(() -> operations
                    .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                    .orElseThrow());
            assertTrue(profileStarted.await(5, TimeUnit.SECONDS));

            ClientOperations.EditorSave saved = operations.saveEditor(
                    new ClientOperations.EditorSaveRequest(
                            Optional.empty(),
                            "Concurrent local intent",
                            SkinReference.asset(initial.account().skinAssets().get(0).id()),
                            SkinVariant.CLASSIC,
                            SkinVariant.CLASSIC,
                            Optional.empty(),
                            Optional.empty()));
            ClientOperations.PresetUse selected = operations.usePreset(saved.presetId());
            releaseProfile.countDown();

            ClientOperations.ReconciliationResult observed = revisionZero.get();
            assertEquals(selected.intentRevision(), observed.appearance().intentRevision());
            assertEquals(AppearanceSyncStatus.PENDING, observed.appearance().syncStatus());
            assertEquals(Optional.of(saved.presetId()), observed.appearance().activePresetId());
            assertEquals(1, api.profileGets.get());
            assertEquals(0, api.skinUploads.get());
            assertEquals(0, api.skinResets.get());
        } finally {
            releaseProfile.countDown();
            pool.shutdownNow();
        }

        api.beforeProfileGet = null;
        ClientOperations.ReconciliationResult synchronizedIntent = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.LOCAL_INTENT)
                .orElseThrow();

        assertEquals(AppearanceSyncStatus.OFFICIAL, synchronizedIntent.appearance().syncStatus());
        assertEquals(2, api.profileGets.get());
        assertEquals(1, api.skinUploads.get());
        assertEquals(0, api.skinResets.get());
    }

    @Test
    void profileGetAndMutationShareOneAtomicTokenCallback()
            throws Exception {
        byte[] skin = skinPng(0xFF536779);
        StubProfileApi api = new StubProfileApi();
        AtomicInteger tokenRequests = new AtomicInteger();
        GameSessionTokenSource intermittentToken = new GameSessionTokenSource() {
            @Override
            public SessionIdentity currentSession() {
                return new SessionIdentity(TestFixtures.ACCOUNT_ID, "Player");
            }

            @Override
            public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) throws E {
                int invocation = tokenRequests.incrementAndGet();
                if (invocation == 2) {
                    throw new IllegalStateException("token disappeared before mutation");
                }
                return request.execute("scoped-token");
            }
        };
        DefaultClientOperations operations = new DefaultClientOperations(
                intermittentToken,
                api,
                storage(),
                ignored -> skin.clone(),
                fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Intermittent token",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        operations.usePreset(saved.presetId());

        ClientOperations.ReconciliationResult reconciled = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();

        assertEquals(AppearanceSyncStatus.OFFICIAL, reconciled.appearance().syncStatus());
        assertEquals(MutationResult.APPLIED, reconciled.outcome().orElseThrow().result());
        assertEquals(1, tokenRequests.get());
        assertEquals(1, api.profileGets.get());
        assertEquals(1, api.skinUploads.get());
    }

    @Test
    void providerRefreshDetectsExternalSkinChangeBeforeReapplyingIntent()
            throws Exception {
        byte[] desired = skinPng(0xFF536779);
        byte[] changedElsewhere = skinPng(0xFF795347);
        StubProfileApi api = new StubProfileApi();
        NclSkinsStorage storage = storage();
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(),
                api,
                storage,
                ignored -> desired.clone(),
                fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Desired X",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));

        operations.usePreset(saved.presetId());
        ClientOperations.ReconciliationResult first = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.LOCAL_INTENT)
                .orElseThrow();
        assertEquals(AppearanceSyncStatus.OFFICIAL, first.appearance().syncStatus());
        assertEquals(1, api.skinUploads.get());

        URI changedUri = URI.create("https://textures.minecraft.net/texture/changed-by-other-instance");
        api.profile = profileWithActiveAppearance(changedUri, null);
        storage.initialize();
        Files.write(
                new com.naocraftlab.skins.core.storage.TextureCache(storage).cachePath(changedUri),
                changedElsewhere);

        ClientOperations.DurableAppearance refreshed = operations.refreshProviders(AppearanceProviders.Component.SKIN);
        assertEquals(2, api.profileGets.get());
        assertTrue(refreshed.providers().skin().minecraft().known());
        assertNotEquals(first.appearance().providers().skin().minecraft().value(),
                refreshed.providers().skin().minecraft().value());

        operations.usePreset(saved.presetId());
        ClientOperations.ReconciliationResult reapplied = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.LOCAL_INTENT)
                .orElseThrow();

        assertEquals(AppearanceSyncStatus.OFFICIAL, reapplied.appearance().syncStatus());
        assertEquals(MutationResult.APPLIED, reapplied.outcome().orElseThrow().result());
        assertEquals(3, api.profileGets.get());
        assertEquals(2, api.skinUploads.get());
    }

    @Test
    void uuidMismatchSettlesUnknownAndSessionRefreshedCanRecoverWithoutSecondValidation()
            throws Exception {
        byte[] skin = skinPng(0xFF7A4C91);
        StubProfileApi api = new StubProfileApi();
        api.profile = new RemoteProfile(UUID.randomUUID(), "Other", List.of(), List.of(), Set.of());
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, storage(), ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Pending mismatch",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        operations.usePreset(saved.presetId());

        ClientOperations.ReconciliationResult first = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();
        ClientOperations.ReconciliationResult second = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.RECONNECT)
                .orElseThrow();

        assertEquals(AppearanceSyncStatus.UNKNOWN, first.appearance().syncStatus());
        assertEquals(AppearanceSyncStatus.UNKNOWN, second.appearance().syncStatus());
        assertEquals(1, api.profileGets.get());
        assertEquals(0, api.skinUploads.get());

        api.profile = new RemoteProfile(
                TestFixtures.ACCOUNT_ID, "Player", List.of(), List.of(), Set.of());
        ClientOperations.InitialData refreshed = operations.retrySession();
        assertTrue(refreshed.session().valid());
        assertEquals(2, api.profileGets.get());
        ClientOperations.ReconciliationResult recovered = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.SESSION_REFRESHED)
                .orElseThrow();

        assertEquals(AppearanceSyncStatus.OFFICIAL, recovered.appearance().syncStatus());
        assertEquals(2, api.profileGets.get());
        assertEquals(1, api.skinUploads.get());
    }

    @Test
    void transientNetworkFailureRetriesAtTheNextSelectedCheckpoint() throws Exception {
        byte[] skin = skinPng(0xFF667788);
        StubProfileApi api = new StubProfileApi();
        api.profileFailure = new ProfileApiException(
                ApiFailureKind.NETWORK, "offline", null, null, false);
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, storage(), ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Pending network",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        operations.usePreset(saved.presetId());
        ClientOperations.ReconciliationResult failed = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();
        assertEquals(AppearanceSyncStatus.PENDING, failed.appearance().syncStatus());
        api.profileFailure = null;

        ClientOperations.ReconciliationResult reconnect = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.RECONNECT)
                .orElseThrow();
        assertEquals(AppearanceSyncStatus.OFFICIAL, reconnect.appearance().syncStatus());
        assertEquals(2, api.profileGets.get());
        assertEquals(1, api.skinUploads.get());

        ClientOperations.ReconciliationResult settled = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();
        assertEquals(AppearanceSyncStatus.OFFICIAL, settled.appearance().syncStatus());
        assertEquals(2, api.profileGets.get());
        assertEquals(1, api.skinUploads.get());
    }

    @Test
    void reconnectWithPendingIntentRefreshesAnEarlierValidProfile() throws Exception {
        byte[] skin = skinPng(0xFF6688AA);
        StubProfileApi api = new StubProfileApi();
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, storage(), ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        operations.warmSession();
        assertEquals(1, api.profileGets.get());

        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(), "Reconnect pending",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC, SkinVariant.CLASSIC,
                Optional.empty(), Optional.empty()));
        operations.usePreset(saved.presetId());

        ClientOperations.ReconciliationResult reconciled = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.RECONNECT)
                .orElseThrow();

        assertEquals(AppearanceSyncStatus.OFFICIAL, reconciled.appearance().syncStatus());
        assertEquals(2, api.profileGets.get());
        assertEquals(1, api.skinUploads.get());
    }

    @Test
    void firstUnauthorizedProfileResponseSettlesUnknownUntilExplicitRetry()
            throws Exception {
        byte[] skin = skinPng(0xFF6A526F);
        StubProfileApi api = new StubProfileApi();
        api.profileFailure = new ProfileApiException(
                ApiFailureKind.SESSION_EXPIRED, "expired", 401, null, false);
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, storage(), ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Expired session",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        operations.usePreset(saved.presetId());

        ClientOperations.ReconciliationResult first = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();
        ClientOperations.ReconciliationResult automatic = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.RECONNECT)
                .orElseThrow();

        assertEquals(AppearanceSyncStatus.UNKNOWN, first.appearance().syncStatus());
        assertEquals(AppearanceSyncStatus.UNKNOWN, automatic.appearance().syncStatus());
        assertEquals(1, api.profileGets.get());
        assertEquals(0, api.skinUploads.get());

        api.profileFailure = null;
        ClientOperations.ReconciliationResult explicit = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.EXPLICIT_RETRY)
                .orElseThrow();

        assertEquals(AppearanceSyncStatus.OFFICIAL, explicit.appearance().syncStatus());
        assertEquals(2, api.profileGets.get());
        assertEquals(1, api.skinUploads.get());
    }

    @Test
    void definiteMutationFailureSettlesUnknownAndOnlyExplicitRetryMutatesAgain()
            throws Exception {
        byte[] skin = skinPng(0xFF71543C);
        StubProfileApi api = new StubProfileApi();
        api.skinFailure = new ProfileApiException(
                ApiFailureKind.FORBIDDEN, "denied", 403, null, false);
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, storage(), ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Denied mutation",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        operations.usePreset(saved.presetId());

        ClientOperations.ReconciliationResult failed = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();
        ClientOperations.ReconciliationResult automatic = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.RECONNECT)
                .orElseThrow();

        assertEquals(AppearanceSyncStatus.UNKNOWN, failed.appearance().syncStatus());
        assertEquals(AppearanceSyncStatus.UNKNOWN, automatic.appearance().syncStatus());
        assertEquals(ApiFailureKind.FORBIDDEN, failed.outcome().orElseThrow().failureKind());
        assertEquals(1, api.profileGets.get());
        assertEquals(1, api.skinUploads.get());

        api.skinFailure = null;
        ClientOperations.ReconciliationResult explicit = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.EXPLICIT_RETRY)
                .orElseThrow();

        assertEquals(AppearanceSyncStatus.OFFICIAL, explicit.appearance().syncStatus());
        assertEquals(2, api.profileGets.get());
        assertEquals(2, api.skinUploads.get());
    }

    @Test
    void activeSavePreservesUnknownForLocalAndChangedSiblingEdits() throws Exception {
        byte[] skin = skinPng(0xFF71543C);
        StubProfileApi api = new StubProfileApi();
        api.skinFailure = new ProfileApiException(
                ApiFailureKind.FORBIDDEN, "denied", 403, null, false);
        NclSkinsStorage shared = storage();
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, shared, ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Unknown",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        operations.usePreset(saved.presetId());
        ClientOperations.ReconciliationResult failed = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();
        assertEquals(AppearanceSyncStatus.UNKNOWN, failed.appearance().syncStatus());
        ProviderDelivery unknownSkin = failed.appearance().providers().skin().minecraftDelivery();
        ProviderDelivery unknownCape = failed.appearance().providers().cape().minecraftDelivery();
        int profileGetsAfterFailure = api.profileGets.get();
        int skinUploadsAfterFailure = api.skinUploads.get();

        ClientOperations.EditorSave localEdit = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.of(saved.presetId()),
                "Unknown local edit",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        assertEquals(AppearanceSyncStatus.UNKNOWN,
                localEdit.reappliedAppearance().orElseThrow().syncStatus());
        assertEquals(unknownSkin,
                localEdit.reappliedAppearance().orElseThrow().providers().skin().minecraftDelivery());
        assertEquals(unknownCape,
                localEdit.reappliedAppearance().orElseThrow().providers().cape().minecraftDelivery());

        ClientOperations.EditorSave changedSibling = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.of(saved.presetId()),
                "Unknown cape edit",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.of("unavailable-cape"),
                Optional.empty()));
        assertEquals(AppearanceSyncStatus.UNKNOWN,
                changedSibling.reappliedAppearance().orElseThrow().syncStatus());
        assertEquals(unknownSkin,
                changedSibling.reappliedAppearance().orElseThrow().providers().skin().minecraftDelivery());
        assertEquals(ProviderDelivery.Status.UNKNOWN,
                changedSibling.reappliedAppearance().orElseThrow().providers().cape().minecraftDelivery().status());

        ClientOperations.ReconciliationResult automatic = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.RECONNECT)
                .orElseThrow();
        assertEquals(AppearanceSyncStatus.UNKNOWN, automatic.appearance().syncStatus());
        assertEquals(profileGetsAfterFailure, api.profileGets.get());
        assertEquals(skinUploadsAfterFailure, api.skinUploads.get());
        assertEquals(0, api.capeActivations.get());
    }

    @Test
    void explicitApplyRecoversUnknownWithTheSamePresetValues() throws Exception {
        byte[] skin = skinPng(0xFF71543C);
        StubProfileApi api = new StubProfileApi();
        api.skinFailure = new ProfileApiException(
                ApiFailureKind.FORBIDDEN, "denied", 403, null, false);
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, storage(), ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Explicit recovery",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        operations.usePreset(saved.presetId());
        ClientOperations.ReconciliationResult failed = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();
        assertEquals(AppearanceSyncStatus.UNKNOWN, failed.appearance().syncStatus());
        api.skinFailure = null;
        int profileGetsBeforeApply = api.profileGets.get();
        int uploadsBeforeApply = api.skinUploads.get();

        ClientOperations.RemoteResult applied = operations.applyPreset(saved.presetId());

        assertEquals(MutationResult.APPLIED, applied.outcome().result());
        assertEquals(AppearanceSyncStatus.OFFICIAL,
                operations.durableAppearance().orElseThrow().syncStatus());
        assertEquals(profileGetsBeforeApply + 1, api.profileGets.get());
        assertEquals(uploadsBeforeApply + 1, api.skinUploads.get());
    }

    @Test
    void activeSavePreservesPendingDesiredWhenObservedValueIsOlder() throws Exception {
        ObservedSkinFixture fixture = observedSkinFixture();
        ProviderDelivery pendingDelivery = fixture.operations().loadProviders()
                .skin().minecraftDelivery();
        long settledRevision = fixture.storage().loadAppearance(TestFixtures.ACCOUNT_ID).settledRevision();
        int profileGetsBeforeSave = fixture.api().profileGets.get();
        int uploadsBeforeSave = fixture.api().skinUploads.get();

        ClientOperations.EditorSave edited = fixture.operations().saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.of(fixture.presetId()),
                "Pending B local edit",
                SkinReference.asset(fixture.skinB()),
                fixture.skinBVariant(),
                fixture.skinBVariant(),
                Optional.empty(),
                Optional.empty()));

        assertEquals(AppearanceSyncStatus.PENDING,
                edited.reappliedAppearance().orElseThrow().syncStatus());
        assertEquals(pendingDelivery,
                edited.reappliedAppearance().orElseThrow().providers().skin().minecraftDelivery());
        assertEquals(settledRevision,
                fixture.storage().loadAppearance(TestFixtures.ACCOUNT_ID).settledRevision());

        ClientOperations.ReconciliationResult reconciled = fixture.operations()
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();

        assertEquals(AppearanceSyncStatus.OFFICIAL, reconciled.appearance().syncStatus());
        assertEquals(profileGetsBeforeSave + 1, fixture.api().profileGets.get());
        assertEquals(uploadsBeforeSave + 1, fixture.api().skinUploads.get());
    }

    @Test
    void activeSaveReturningPendingDesiredToObservedValueSettlesWithoutMutation() throws Exception {
        ObservedSkinFixture fixture = observedSkinFixture();
        int profileGetsBeforeSave = fixture.api().profileGets.get();
        int uploadsBeforeSave = fixture.api().skinUploads.get();

        ClientOperations.EditorSave edited = fixture.operations().saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.of(fixture.presetId()),
                "Pending A local edit",
                SkinReference.asset(fixture.skinA()),
                fixture.skinAVariant(),
                fixture.skinAVariant(),
                Optional.empty(),
                Optional.empty()));

        assertEquals(AppearanceSyncStatus.PENDING,
                edited.reappliedAppearance().orElseThrow().syncStatus());
        ClientOperations.ReconciliationResult reconciled = fixture.operations()
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();

        assertEquals(AppearanceSyncStatus.OFFICIAL, reconciled.appearance().syncStatus());
        assertEquals(profileGetsBeforeSave + 1, fixture.api().profileGets.get());
        assertEquals(uploadsBeforeSave, fixture.api().skinUploads.get());
    }

    @Test
    void mutationRateLimitRemainsPendingAndSkipsCheckpointsUntilCooldownExpires()
            throws Exception {
        byte[] skin = skinPng(0xFF3E647A);
        StubProfileApi api = new StubProfileApi();
        api.skinFailure = new ProfileApiException(
                ApiFailureKind.RATE_LIMITED,
                "rate limited",
                429,
                Duration.ofSeconds(60),
                false);
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, storage(), ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Rate limited",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        operations.usePreset(saved.presetId());

        ClientOperations.ReconciliationResult limited = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();
        assertEquals(AppearanceSyncStatus.PENDING, limited.appearance().syncStatus());
        assertEquals(ApiFailureKind.RATE_LIMITED, limited.outcome().orElseThrow().failureKind());
        assertEquals(1, api.profileGets.get());
        assertEquals(1, api.skinUploads.get());

        api.skinFailure = null;
        api.rateLimitRemaining = Optional.of(Duration.ofSeconds(60));
        long previousRevision = limited.appearance().intentRevision();
        operations.applyPreset(saved.presetId());
        ClientOperations.DurableAppearance duringCooldown = operations.durableAppearance().orElseThrow();
        assertEquals(previousRevision + 1, duringCooldown.intentRevision());
        assertEquals(AppearanceSyncStatus.PENDING, duringCooldown.syncStatus());
        assertEquals(1, api.profileGets.get());
        assertEquals(1, api.skinUploads.get());

        ClientOperations.EditorSave latest = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(), "Latest", SkinReference.asset(initial.account().skinAssets().get(1).id()),
                SkinVariant.SLIM, SkinVariant.SLIM, Optional.empty(), Optional.empty()));
        operations.applyPreset(latest.presetId());
        ClientOperations.DurableAppearance latestDuringCooldown = operations.durableAppearance().orElseThrow();
        assertEquals(previousRevision + 2, latestDuringCooldown.intentRevision());
        assertEquals(Optional.of(latest.presetId()), latestDuringCooldown.activePresetId());
        assertEquals(AppearanceSyncStatus.PENDING, latestDuringCooldown.syncStatus());
        assertEquals(1, api.profileGets.get());
        assertEquals(1, api.skinUploads.get());

        api.rateLimitRemaining = Optional.empty();
        ClientOperations.ReconciliationResult afterCooldown = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.RATE_LIMIT_EXPIRED)
                .orElseThrow();
        assertEquals(AppearanceSyncStatus.OFFICIAL, afterCooldown.appearance().syncStatus());
        assertEquals(2, api.profileGets.get());
        assertEquals(2, api.skinUploads.get());
        assertEquals(Optional.of(latest.presetId()), afterCooldown.appearance().activePresetId());
        assertEquals(SkinVariant.SLIM, api.lastUploadedVariant);
        assertEquals(afterCooldown.appearance().providers().skin().desired().sha256(),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(api.lastUploadedSkin)));
    }

    @Test
    void activeSavesAfterRateLimitKeepOneCooldownAndOnlyLatestIntent() throws Exception {
        byte[] skin = skinPng(0xFF3E647A);
        StubProfileApi api = new StubProfileApi();
        api.skinFailure = new ProfileApiException(
                ApiFailureKind.RATE_LIMITED,
                "rate limited",
                429,
                Duration.ofSeconds(60),
                false);
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, storage(), ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Rate limited active",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        operations.usePreset(saved.presetId());
        ClientOperations.ReconciliationResult limited = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();
        assertEquals(AppearanceSyncStatus.PENDING, limited.appearance().syncStatus());
        int profileGetsAfterLimit = api.profileGets.get();
        int uploadsAfterLimit = api.skinUploads.get();

        api.rateLimitRemaining = Optional.of(Duration.ofSeconds(60));
        ClientOperations.EditorSave localEdit = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.of(saved.presetId()),
                "Rate limited local edit",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        ClientOperations.EditorSave changed = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.of(saved.presetId()),
                "Rate limited changed",
                SkinReference.asset(initial.account().skinAssets().get(1).id()),
                SkinVariant.SLIM,
                SkinVariant.SLIM,
                Optional.empty(),
                Optional.empty()));
        ClientOperations.EditorSave latest = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.of(saved.presetId()),
                "Rate limited latest",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));

        assertEquals(AppearanceSyncStatus.PENDING, localEdit.reappliedAppearance().orElseThrow().syncStatus());
        assertEquals(AppearanceSyncStatus.PENDING, changed.reappliedAppearance().orElseThrow().syncStatus());
        assertEquals(AppearanceSyncStatus.PENDING, latest.reappliedAppearance().orElseThrow().syncStatus());
        assertEquals(latest.reappliedAppearance().orElseThrow().intentRevision(),
                latest.reappliedAppearance().orElseThrow().providers().skin().minecraftDelivery().intentRevision());

        ClientOperations.ReconciliationResult duringCooldown = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.RECONNECT)
                .orElseThrow();
        assertEquals(AppearanceSyncStatus.PENDING, duringCooldown.appearance().syncStatus());
        assertEquals(profileGetsAfterLimit, api.profileGets.get());
        assertEquals(uploadsAfterLimit, api.skinUploads.get());

        api.skinFailure = null;
        api.rateLimitRemaining = Optional.empty();
        ClientOperations.ReconciliationResult recovered = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.RATE_LIMIT_EXPIRED)
                .orElseThrow();
        assertEquals(AppearanceSyncStatus.OFFICIAL, recovered.appearance().syncStatus());
        assertEquals(profileGetsAfterLimit + 1, api.profileGets.get());
        assertEquals(uploadsAfterLimit + 1, api.skinUploads.get());
    }

    @Test
    void capeRateLimitRecoversOnlyCapeAfterCooldownAndKeepsItsRevision() throws Exception {
        byte[] skin = skinPng(0xFF395D7B);
        URI skinUri = URI.create("https://textures.minecraft.net/texture/partial-matching-skin");
        URI capeUri = URI.create("https://textures.minecraft.net/texture/partial-owned-cape");
        StubProfileApi api = new StubProfileApi();
        api.profile = new RemoteProfile(
                TestFixtures.ACCOUNT_ID,
                "Player",
                List.of(),
                List.of(new RemoteCape(
                        "cape-owned", RemoteAssetState.INACTIVE, capeUri, "Owned cape")),
                Set.of());
        api.capeFailure = new ProfileApiException(
                ApiFailureKind.RATE_LIMITED,
                "cape rate limited",
                429,
                Duration.ofSeconds(60),
                false);
        NclSkinsStorage storage = storage();
        storage.initialize();
        Files.write(
                new com.naocraftlab.skins.core.storage.TextureCache(storage).cachePath(skinUri),
                skin);
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, storage, ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Partial",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.of("cape-owned"),
                Optional.empty()));
        ClientOperations.PresetUse selected = operations.usePreset(saved.presetId());

        ClientOperations.ReconciliationResult partial = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.LOCAL_INTENT)
                .orElseThrow();

        assertEquals(AppearanceSyncStatus.PARTIAL, partial.appearance().syncStatus());
        assertEquals(selected.intentRevision(), partial.appearance().intentRevision());
        assertEquals(MutationResult.PARTIAL, partial.outcome().orElseThrow().result());
        assertEquals(1, api.profileGets.get());
        assertEquals(1, api.skinUploads.get());
        assertEquals(1, api.capeActivations.get());

        ProviderDelivery partialSkinDelivery = partial.appearance().providers().skin().minecraftDelivery();
        ProviderDelivery partialCapeDelivery = partial.appearance().providers().cape().minecraftDelivery();
        long partialSettledRevision = storage.loadAppearance(TestFixtures.ACCOUNT_ID).settledRevision();
        int profileGetsAfterPartial = api.profileGets.get();
        int skinUploadsAfterPartial = api.skinUploads.get();
        int capeActivationsAfterPartial = api.capeActivations.get();
        ClientOperations.EditorSave localEdit = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.of(saved.presetId()),
                "Partial local edit",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.of("cape-owned"),
                Optional.empty()));
        assertEquals(AppearanceSyncStatus.PARTIAL,
                localEdit.reappliedAppearance().orElseThrow().syncStatus());
        assertEquals(partialSkinDelivery,
                localEdit.reappliedAppearance().orElseThrow().providers().skin().minecraftDelivery());
        assertEquals(partialCapeDelivery,
                localEdit.reappliedAppearance().orElseThrow().providers().cape().minecraftDelivery());
        assertEquals(partialSettledRevision,
                storage.loadAppearance(TestFixtures.ACCOUNT_ID).settledRevision());

        api.rateLimitRemaining = Optional.of(Duration.ofSeconds(60));
        ClientOperations.ReconciliationResult automatic = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.RECONNECT)
                .orElseThrow();
        assertEquals(AppearanceSyncStatus.PARTIAL, automatic.appearance().syncStatus());
        assertEquals(localEdit.reappliedAppearance().orElseThrow().intentRevision(),
                automatic.appearance().intentRevision());
        assertTrue(automatic.outcome().isEmpty());
        assertEquals(profileGetsAfterPartial, api.profileGets.get());
        assertEquals(skinUploadsAfterPartial, api.skinUploads.get());
        assertEquals(capeActivationsAfterPartial, api.capeActivations.get());

        api.capeFailure = null;
        api.rateLimitRemaining = Optional.empty();
        api.profile = new RemoteProfile(
                TestFixtures.ACCOUNT_ID,
                "Renamed remotely",
                List.of(new RemoteSkin(
                        "matching-skin",
                        RemoteAssetState.ACTIVE,
                        skinUri,
                        SkinVariant.CLASSIC,
                        "Matching skin")),
                List.of(new RemoteCape(
                        "cape-owned", RemoteAssetState.INACTIVE, capeUri, "Owned cape")),
                Set.of());

        ClientOperations.ReconciliationResult recovered = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.RATE_LIMIT_EXPIRED)
                .orElseThrow();

        assertEquals(AppearanceSyncStatus.OFFICIAL, recovered.appearance().syncStatus());
        assertEquals(localEdit.reappliedAppearance().orElseThrow().intentRevision(),
                recovered.appearance().intentRevision());
        assertEquals(MutationResult.APPLIED, recovered.outcome().orElseThrow().result());
        assertEquals(2, api.profileGets.get());
        assertEquals(1, api.skinUploads.get());
        assertEquals(2, api.capeActivations.get());
    }

    @Test
    void unownedCapeCreatesOneEffectiveRevisionWithoutChangingThePreset() throws Exception {
        byte[] skin = skinPng(0xFF416785);
        StubProfileApi api = new StubProfileApi();
        NclSkinsStorage storage = storage();
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, storage, ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        var offlineCape = storage.importCape(
                TestFixtures.ACCOUNT_ID, "Keep offline cape", customCapePng()).texture();
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Keep stale cape in preset",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.of("no-longer-owned"),
                Optional.empty()).withOfflineCape(offlineCape));
        ClientOperations.PresetUse selected = operations.usePreset(saved.presetId());

        ClientOperations.ReconciliationResult reconciled = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.LOCAL_INTENT)
                .orElseThrow();

        assertEquals(selected.intentRevision() + 1, reconciled.appearance().intentRevision());
        assertEquals(AppearanceSyncStatus.OFFICIAL, reconciled.appearance().syncStatus());
        assertNull(storage.loadAppearance(TestFixtures.ACCOUNT_ID).capeId());
        assertEquals(
                "no-longer-owned",
                reconciled.account().presets().stream()
                        .filter(preset -> preset.id().equals(saved.presetId()))
                        .findFirst()
                        .orElseThrow()
                        .capeId());
        var normalized = storage.loadAppearance(TestFixtures.ACCOUNT_ID);
        assertNull(normalized.providers().cape().desired());
        assertEquals(offlineCape.sha256(), normalized.providers().cape().offlineDesired().textureCacheKey());
        assertEquals(offlineCape.sha256(), normalized.providers().cape().offline().value().textureCacheKey());
        var normalizedDelivery = normalized.providers().cape().minecraftDelivery();
        ClientOperations.EditorSave repeated = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.of(saved.presetId()),
                "Keep stale cape in preset again",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.of("no-longer-owned"),
                Optional.empty()).withOfflineCape(offlineCape));
        assertEquals(reconciled.appearance().intentRevision() + 1,
                repeated.reappliedAppearance().orElseThrow().intentRevision());
        assertEquals(AppearanceSyncStatus.OFFICIAL,
                repeated.reappliedAppearance().orElseThrow().syncStatus());
        assertEquals(normalizedDelivery,
                repeated.reappliedAppearance().orElseThrow().providers().cape().minecraftDelivery());
        DefaultClientOperations restarted = new DefaultClientOperations(
                tokens(), api, storage, ignored -> skin.clone(), fixedClock());
        assertNull(restarted.loadProviders().cape().desired());
        assertEquals(offlineCape.sha256(), restarted.loadProviders().cape().offlineDesired().textureCacheKey());
        assertEquals(1, api.profileGets.get());
        assertEquals(1, api.skinUploads.get());
        assertEquals(0, api.capeActivations.get());
        assertEquals(0, api.capeDeactivations.get());
    }

    @Test
    void unknownOwnershipNormalizationPreservesOfflineCapeAndConfirmedSkin() throws Exception {
        byte[] skin = skinPng(0xFF416785);
        StubProfileApi api = new StubProfileApi();
        NclSkinsStorage shared = storage();
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, shared, ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        var offlineCape = shared.importCape(
                TestFixtures.ACCOUNT_ID, "Offline while unknown", customCapePng()).texture();
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Unknown stale cape",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.of("unowned-cape"),
                Optional.empty()).withOfflineCape(offlineCape));
        operations.usePreset(saved.presetId());
        shared.updateAppearance(TestFixtures.ACCOUNT_ID, current -> {
            AppearanceProviders providers = current.providers();
            AppearanceProviders uncertain = new AppearanceProviders(
                    providers.skin().settle(
                            providers.skin().minecraftDelivery(),
                            ProviderDelivery.Status.CONFIRMED,
                            providers.skin().desired()),
                    providers.cape().settle(
                            providers.cape().minecraftDelivery(),
                            ProviderDelivery.Status.UNKNOWN,
                            null));
            return new com.naocraftlab.skins.core.model.AccountAppearanceState(
                    current.schemaVersion(), current.accountId(), current.intentRevision(),
                    current.activePresetId(), current.skinSha256(), current.skinVariant(),
                    current.capeId(), current.outerLayerVisibility(), AppearanceSyncStatus.UNKNOWN,
                    current.settledRevision(), current.updatedAt(), uncertain);
        });
        int capeActivationsBeforeRecovery = api.capeActivations.get();
        int capeDeactivationsBeforeRecovery = api.capeDeactivations.get();

        ClientOperations.ReconciliationResult recovered = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.EXPLICIT_RETRY)
                .orElseThrow();

        assertEquals(AppearanceSyncStatus.OFFICIAL, recovered.appearance().syncStatus());
        assertEquals(ProviderDelivery.Status.CONFIRMED,
                recovered.appearance().providers().skin().minecraftDelivery().status());
        assertEquals(ProviderDelivery.Status.CONFIRMED,
                recovered.appearance().providers().cape().minecraftDelivery().status());
        assertNull(recovered.appearance().providers().cape().desired());
        assertEquals(offlineCape.sha256(),
                recovered.appearance().providers().cape().offline().value().textureCacheKey());
        assertNull(shared.loadAppearance(TestFixtures.ACCOUNT_ID).capeId());
        assertEquals(offlineCape.sha256(),
                shared.loadAppearance(TestFixtures.ACCOUNT_ID)
                        .providers().cape().offlineDesired().textureCacheKey());
        assertEquals(capeActivationsBeforeRecovery, api.capeActivations.get());
        assertEquals(capeDeactivationsBeforeRecovery, api.capeDeactivations.get());

        ProviderDelivery normalizedDelivery = recovered.appearance().providers().cape().minecraftDelivery();
        ClientOperations.EditorSave repeated = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.of(saved.presetId()),
                "Unknown stale cape again",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.of("unowned-cape"),
                Optional.empty()).withOfflineCape(offlineCape));
        assertEquals(AppearanceSyncStatus.OFFICIAL,
                repeated.reappliedAppearance().orElseThrow().syncStatus());
        assertEquals(normalizedDelivery,
                repeated.reappliedAppearance().orElseThrow().providers().cape().minecraftDelivery());

        DefaultClientOperations restarted = new DefaultClientOperations(
                tokens(), api, shared, ignored -> skin.clone(), fixedClock());
        assertNull(restarted.loadProviders().cape().desired());
        assertEquals(offlineCape.sha256(),
                restarted.loadProviders().cape().offlineDesired().textureCacheKey());
    }

    @Test
    void localAssetFailureBeforeMutationLeavesTheIntentPending() throws Exception {
        byte[] skin = skinPng(0xFF385F79);
        StubProfileApi api = new StubProfileApi();
        NclSkinsStorage storage = storage();
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, storage, ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Missing immutable asset",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        ClientOperations.PresetUse selected = operations.usePreset(saved.presetId());
        String hash = storage.loadAppearance(TestFixtures.ACCOUNT_ID).skinSha256();
        Files.delete(storage.assetPath(hash));

        assertThrows(
                IOException.class,
                () -> operations.reconcileAppearance(
                        ClientOperations.ReconciliationTrigger.LOCAL_INTENT));

        var durable = storage.loadAppearance(TestFixtures.ACCOUNT_ID);
        assertEquals(selected.intentRevision(), durable.intentRevision());
        assertEquals(AppearanceSyncStatus.PENDING, durable.syncStatus());
        assertEquals(0, api.skinUploads.get());
        assertEquals(0, api.skinResets.get());
    }

    @Test
    void confirmedMutationSettlementFailureCarriesConfirmedServerImpact() throws Exception {
        byte[] skin = skinPng(0xFF42627F);
        StubProfileApi api = new StubProfileApi();
        NclSkinsStorage storage = storage();
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, storage, ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Settlement failure",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        operations.usePreset(saved.presetId());
        Path appearancePath = storage.layout().accountAppearance(TestFixtures.ACCOUNT_ID);
        api.afterSkinUpload = () -> {
            try {
                Files.delete(appearancePath);
                Files.createDirectory(appearancePath);
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
        };

        RemoteMutationSettlementException failure = assertThrows(
                RemoteMutationSettlementException.class,
                () -> operations.reconcileAppearance(
                        ClientOperations.ReconciliationTrigger.LOCAL_INTENT));

        assertEquals(RemoteAppearanceImpact.CONFIRMED_CHANGED, failure.remoteAppearanceImpact());
        assertEquals(1, api.skinUploads.get());
    }

    @Test
    void explicitRetryRefreshesSessionEvenWhenDurableAppearanceIsOfficial() throws Exception {
        byte[] skin = skinPng(0xFF4B6984);
        StubProfileApi api = new StubProfileApi();
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, storage(), ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Official before retry",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        operations.usePreset(saved.presetId());
        ClientOperations.ReconciliationResult official = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.LOCAL_INTENT)
                .orElseThrow();
        assertEquals(AppearanceSyncStatus.OFFICIAL, official.appearance().syncStatus());
        int getsBeforeRetry = api.profileGets.get();
        api.profileFailure = new ProfileApiException(
                ApiFailureKind.SESSION_EXPIRED,
                "expired",
                401,
                null,
                false);

        ClientOperations.ReconciliationResult failedRetry = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.EXPLICIT_RETRY)
                .orElseThrow();

        assertEquals(AppearanceSyncStatus.OFFICIAL, failedRetry.appearance().syncStatus());
        assertFalse(failedRetry.session().valid());
        assertEquals(getsBeforeRetry + 1, api.profileGets.get());
        api.profileFailure = null;

        ClientOperations.ReconciliationResult recovered = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.EXPLICIT_RETRY)
                .orElseThrow();

        assertTrue(recovered.session().valid());
        assertEquals(AppearanceSyncStatus.OFFICIAL, recovered.appearance().syncStatus());
        assertEquals(getsBeforeRetry + 2, api.profileGets.get());
    }

    @Test
    void twoInstancesReconcileOneRevisionWithOneMutationAndLoserKeepsOfficial()
            throws Exception {
        byte[] skin = skinPng(0xFF3A5876);
        NclSkinsStorage shared = storage();
        StubProfileApi api = new StubProfileApi();
        DefaultClientOperations writer = new DefaultClientOperations(
                tokens(), api, shared, ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = writer.initialize();
        ClientOperations.EditorSave saved = writer.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Concurrent",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        writer.usePreset(saved.presetId());
        DefaultClientOperations contender = new DefaultClientOperations(
                tokens(), api, shared, ignored -> skin.clone(), fixedClock());

        ClientOperations.ReconciliationResult firstResult;
        ClientOperations.ReconciliationResult secondResult;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ClientOperations.ReconciliationResult> first = pool.submit(() -> writer
                    .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                    .orElseThrow());
            Future<ClientOperations.ReconciliationResult> second = pool.submit(() -> contender
                    .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                    .orElseThrow());

            firstResult = first.get();
            secondResult = second.get();
            assertEquals(AppearanceSyncStatus.OFFICIAL, firstResult.appearance().syncStatus());
            assertEquals(AppearanceSyncStatus.OFFICIAL, secondResult.appearance().syncStatus());
        } finally {
            pool.shutdownNow();
        }
        assertNotEquals(firstResult.outcome().isPresent(), secondResult.outcome().isPresent());
        assertEquals(1, api.profileGets.get());
        assertEquals(1, api.skinUploads.get());
        assertEquals(
                AppearanceSyncStatus.OFFICIAL,
                shared.loadAppearance(TestFixtures.ACCOUNT_ID).syncStatus());
    }

    @Test
    void newerRevisionPublishedDuringMutationRemainsPending() throws Exception {
        byte[] skin = skinPng(0xFF4A6682);
        StubProfileApi api = new StubProfileApi();
        CountDownLatch mutationStarted = new CountDownLatch(1);
        CountDownLatch releaseMutation = new CountDownLatch(1);
        api.beforeSkinUpload = () -> {
            mutationStarted.countDown();
            try {
                assertTrue(releaseMutation.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        };
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, storage(), ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        ClientOperations.EditorSave firstPreset = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(), "First", SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC, SkinVariant.CLASSIC, Optional.empty(), Optional.empty()));
        ClientOperations.EditorSave secondPreset = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(), "Second", SkinReference.asset(initial.account().skinAssets().get(1).id()),
                SkinVariant.SLIM, SkinVariant.SLIM, Optional.empty(), Optional.empty()));
        operations.usePreset(firstPreset.presetId());

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ClientOperations.ReconciliationResult> firstAttempt = pool.submit(() -> operations
                    .reconcileAppearance(ClientOperations.ReconciliationTrigger.LOCAL_INTENT)
                    .orElseThrow());
            assertTrue(mutationStarted.await(5, TimeUnit.SECONDS));
            ClientOperations.PresetUse newer = operations.usePreset(secondPreset.presetId());
            releaseMutation.countDown();

            ClientOperations.ReconciliationResult completed = firstAttempt.get();
            assertEquals(newer.intentRevision(), completed.appearance().intentRevision());
            assertEquals(AppearanceSyncStatus.PENDING, completed.appearance().syncStatus());
            assertEquals(Optional.of(secondPreset.presetId()), completed.appearance().activePresetId());
        } finally {
            releaseMutation.countDown();
            pool.shutdownNow();
        }
        assertEquals(1, api.skinUploads.get());
    }

    @Test
    void activeSaveDuringAttemptingUsesFreshObservationToSettleMatchingIntent() throws Exception {
        byte[] skin = skinPng(0xFF4A6682);
        NclSkinsStorage shared = storage();
        StubProfileApi api = new StubProfileApi();
        CountDownLatch mutationStarted = new CountDownLatch(1);
        CountDownLatch releaseMutation = new CountDownLatch(1);
        api.beforeSkinUpload = () -> {
            mutationStarted.countDown();
            try {
                assertTrue(releaseMutation.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        };
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, shared, ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        SkinAsset selectedSkin = initial.account().skinAssets().get(0);
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(), "Attempting", SkinReference.asset(selectedSkin.id()),
                SkinVariant.CLASSIC, SkinVariant.CLASSIC, Optional.empty(), Optional.empty()));
        ClientOperations.PresetUse selected = operations.usePreset(saved.presetId());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ClientOperations.ReconciliationResult> mutation = pool.submit(() -> operations
                    .reconcileAppearance(ClientOperations.ReconciliationTrigger.LOCAL_INTENT)
                    .orElseThrow());
            assertTrue(mutationStarted.await(5, TimeUnit.SECONDS));
            Future<ClientOperations.EditorSave> localSave = pool.submit(() -> operations.saveEditor(
                    new ClientOperations.EditorSaveRequest(
                            Optional.of(saved.presetId()), "Attempting local edit",
                            SkinReference.asset(selectedSkin.id()), SkinVariant.CLASSIC,
                            SkinVariant.CLASSIC, Optional.empty(), Optional.empty())));
            ClientOperations.EditorSave edited = localSave.get(5, TimeUnit.SECONDS);
            assertEquals(AppearanceSyncStatus.ATTEMPTING,
                    edited.reappliedAppearance().orElseThrow().syncStatus());
            assertEquals(selected.intentRevision() + 1,
                    edited.reappliedAppearance().orElseThrow().intentRevision());

            releaseMutation.countDown();
            ClientOperations.ReconciliationResult stale = mutation.get(5, TimeUnit.SECONDS);
            assertEquals(AppearanceSyncStatus.ATTEMPTING, stale.appearance().syncStatus());
            assertEquals(1, api.skinUploads.get());

            URI skinUri = URI.create("https://textures.minecraft.net/texture/matching-attempting");
            Files.write(new com.naocraftlab.skins.core.storage.TextureCache(shared).cachePath(skinUri),
                    Files.readAllBytes(shared.assetPath(selectedSkin.sha256())));
            api.profile = new RemoteProfile(
                    TestFixtures.ACCOUNT_ID,
                    "Player",
                    List.of(new RemoteSkin("matching", RemoteAssetState.ACTIVE, skinUri,
                            SkinVariant.CLASSIC, "Matching")),
                    List.of(),
                    Set.of());

            ClientOperations.ReconciliationResult observed = operations
                    .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                    .orElseThrow();
            assertEquals(AppearanceSyncStatus.OFFICIAL, observed.appearance().syncStatus());
            assertTrue(observed.outcome().isEmpty());
            assertEquals(1, api.skinUploads.get());
        } finally {
            releaseMutation.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void activeSaveDuringAttemptingUsesFreshObservationToPreserveUnknownMismatch() throws Exception {
        byte[] skin = skinPng(0xFF4A6682);
        NclSkinsStorage shared = storage();
        StubProfileApi api = new StubProfileApi();
        CountDownLatch mutationStarted = new CountDownLatch(1);
        CountDownLatch releaseMutation = new CountDownLatch(1);
        api.beforeSkinUpload = () -> {
            mutationStarted.countDown();
            try {
                assertTrue(releaseMutation.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        };
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, shared, ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        SkinAsset selectedSkin = initial.account().skinAssets().get(0);
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(), "Attempting mismatch", SkinReference.asset(selectedSkin.id()),
                SkinVariant.CLASSIC, SkinVariant.CLASSIC, Optional.empty(), Optional.empty()));
        operations.usePreset(saved.presetId());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ClientOperations.ReconciliationResult> mutation = pool.submit(() -> operations
                    .reconcileAppearance(ClientOperations.ReconciliationTrigger.LOCAL_INTENT)
                    .orElseThrow());
            assertTrue(mutationStarted.await(5, TimeUnit.SECONDS));
            ClientOperations.EditorSave edited = pool.submit(() -> operations.saveEditor(
                    new ClientOperations.EditorSaveRequest(
                            Optional.of(saved.presetId()), "Attempting mismatch local edit",
                            SkinReference.asset(selectedSkin.id()), SkinVariant.CLASSIC,
                            SkinVariant.CLASSIC, Optional.empty(), Optional.empty()))).get(5, TimeUnit.SECONDS);
            assertEquals(AppearanceSyncStatus.ATTEMPTING,
                    edited.reappliedAppearance().orElseThrow().syncStatus());
            releaseMutation.countDown();
            ClientOperations.ReconciliationResult stale = mutation.get(5, TimeUnit.SECONDS);
            assertEquals(AppearanceSyncStatus.ATTEMPTING, stale.appearance().syncStatus());

            ClientOperations.ReconciliationResult observed = operations
                    .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                    .orElseThrow();
            assertEquals(AppearanceSyncStatus.UNKNOWN, observed.appearance().syncStatus());
            assertTrue(observed.outcome().isEmpty());
            assertEquals(1, api.skinUploads.get());
        } finally {
            releaseMutation.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void supersedingCapeSaveDoesNotReopenConfirmedSkinSibling() throws Exception {
        byte[] skin = skinPng(0xFF4A6682);
        NclSkinsStorage shared = storage();
        StubProfileApi api = new StubProfileApi();
        api.profile = new RemoteProfile(
                TestFixtures.ACCOUNT_ID,
                "Player",
                List.of(),
                List.of(
                        new RemoteCape("cape-a", RemoteAssetState.ACTIVE,
                                URI.create("https://textures.minecraft.net/texture/cape-a"), "A"),
                        new RemoteCape("cape-b", RemoteAssetState.INACTIVE,
                                URI.create("https://textures.minecraft.net/texture/cape-b"), "B"),
                        new RemoteCape("cape-c", RemoteAssetState.INACTIVE,
                                URI.create("https://textures.minecraft.net/texture/cape-c"), "C")),
                Set.of());
        CountDownLatch mutationStarted = new CountDownLatch(1);
        CountDownLatch releaseMutation = new CountDownLatch(1);
        api.beforeCapeActivation = () -> {
            mutationStarted.countDown();
            try {
                assertTrue(releaseMutation.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        };
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, shared, ignored -> skin.clone(), fixedClock());
        operations.initialize();
        operations.refreshProviders(AppearanceProviders.Component.CAPE);
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(), "Cape A", SkinReference.accountDefault(), SkinVariant.CLASSIC,
                SkinVariant.CLASSIC, Optional.of("cape-a"), Optional.empty()));
        operations.usePreset(saved.presetId());
        ClientOperations.DurableAppearance settled = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow()
                .appearance();
        assertEquals(AppearanceSyncStatus.OFFICIAL, settled.syncStatus());

        ClientOperations.EditorSave firstEdit = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.of(saved.presetId()), "Cape B", SkinReference.accountDefault(), SkinVariant.CLASSIC,
                SkinVariant.CLASSIC, Optional.of("cape-b"), Optional.empty()));
        assertEquals(ProviderDelivery.Status.CONFIRMED,
                firstEdit.reappliedAppearance().orElseThrow().providers().skin().minecraftDelivery().status());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ClientOperations.ReconciliationResult> mutation = pool.submit(() -> operations
                    .reconcileAppearance(ClientOperations.ReconciliationTrigger.LOCAL_INTENT)
                    .orElseThrow());
            assertTrue(mutationStarted.await(5, TimeUnit.SECONDS));
            ClientOperations.EditorSave secondEdit = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                    Optional.of(saved.presetId()), "Cape C", SkinReference.accountDefault(), SkinVariant.CLASSIC,
                    SkinVariant.CLASSIC, Optional.of("cape-c"), Optional.empty()));
            assertEquals(AppearanceSyncStatus.UNKNOWN,
                    secondEdit.reappliedAppearance().orElseThrow().syncStatus());
            releaseMutation.countDown();

            ClientOperations.ReconciliationResult completed = mutation.get(5, TimeUnit.SECONDS);
            assertEquals(AppearanceSyncStatus.PENDING, completed.appearance().syncStatus());
            assertEquals(ProviderDelivery.Status.CONFIRMED,
                    completed.appearance().providers().skin().minecraftDelivery().status());
            assertEquals(ProviderDelivery.Status.PENDING,
                    completed.appearance().providers().cape().minecraftDelivery().status());
            assertEquals("cape-c", completed.appearance().providers().cape().desired().id());
        } finally {
            releaseMutation.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void staleCompletionDoesNotResumeAnIndependentUnknownSibling() throws Exception {
        InFlightSkinFixture fixture = inFlightSkinFixture();
        fixture.storage().updateAppearance(TestFixtures.ACCOUNT_ID, current -> {
            AppearanceProviders providers = current.providers();
            AppearanceProviders uncertain = new AppearanceProviders(
                    providers.skin(),
                    providers.cape().settle(
                            providers.cape().minecraftDelivery(),
                            ProviderDelivery.Status.UNKNOWN,
                            null));
            return current.withProviders(uncertain);
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ClientOperations.ReconciliationResult> mutation = pool.submit(() -> fixture.operations()
                    .reconcileAppearance(ClientOperations.ReconciliationTrigger.LOCAL_INTENT)
                    .orElseThrow());
            assertTrue(fixture.mutationStarted().await(5, TimeUnit.SECONDS));
            ClientOperations.EditorSave changed = fixture.saveNewSkin("Unknown sibling");
            assertEquals(AppearanceSyncStatus.UNKNOWN,
                    changed.reappliedAppearance().orElseThrow().syncStatus());
            fixture.releaseMutation().countDown();

            ClientOperations.ReconciliationResult completed = mutation.get(5, TimeUnit.SECONDS);
            assertEquals(AppearanceSyncStatus.UNKNOWN, completed.appearance().syncStatus());
            assertEquals(ProviderDelivery.Status.PENDING,
                    completed.appearance().providers().skin().minecraftDelivery().status());
            assertEquals(ProviderDelivery.Status.UNKNOWN,
                    completed.appearance().providers().cape().minecraftDelivery().status());
            assertEquals(1, fixture.api().skinUploads.get());
            assertEquals(0, fixture.api().capeActivations.get());
            assertEquals(0, fixture.api().capeDeactivations.get());
        } finally {
            fixture.releaseMutation().countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void staleCompletionDoesNotResumeAnUnchangedUnknownDelivery() throws Exception {
        InFlightSkinFixture fixture = inFlightSkinFixture();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ClientOperations.ReconciliationResult> mutation = pool.submit(() -> fixture.operations()
                    .reconcileAppearance(ClientOperations.ReconciliationTrigger.LOCAL_INTENT)
                    .orElseThrow());
            assertTrue(fixture.mutationStarted().await(5, TimeUnit.SECONDS));
            fixture.storage().updateAppearance(TestFixtures.ACCOUNT_ID, current -> {
                AppearanceProviders providers = current.providers();
                AppearanceProviders uncertain = new AppearanceProviders(
                        providers.skin().settle(
                                providers.skin().minecraftDelivery(),
                                ProviderDelivery.Status.UNKNOWN,
                                null),
                        providers.cape());
                return new com.naocraftlab.skins.core.model.AccountAppearanceState(
                        current.schemaVersion(), current.accountId(),
                        Math.incrementExact(current.intentRevision()),
                        current.activePresetId(), current.skinSha256(), current.skinVariant(),
                        current.capeId(), current.outerLayerVisibility(),
                        AppearanceSyncStatus.UNKNOWN, current.settledRevision(),
                        current.updatedAt(), uncertain);
            });
            fixture.releaseMutation().countDown();

            ClientOperations.ReconciliationResult completed = mutation.get(5, TimeUnit.SECONDS);
            assertEquals(AppearanceSyncStatus.UNKNOWN, completed.appearance().syncStatus());
            assertEquals(ProviderDelivery.Status.UNKNOWN,
                    completed.appearance().providers().skin().minecraftDelivery().status());
            assertEquals(1, fixture.api().skinUploads.get());
            assertEquals(0, fixture.api().capeActivations.get());
        } finally {
            fixture.releaseMutation().countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void staleCompletionDoesNotResumeADisabledDestination() throws Exception {
        InFlightSkinFixture fixture = inFlightSkinFixture();
        fixture.storage().updateAppearance(TestFixtures.ACCOUNT_ID,
                current -> current.withProviders(current.providers().disable(
                        AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT)));
        ProviderDelivery disabledDelivery = fixture.storage().loadAppearance(TestFixtures.ACCOUNT_ID)
                .providers().cape().minecraftDelivery();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ClientOperations.ReconciliationResult> mutation = pool.submit(() -> fixture.operations()
                    .reconcileAppearance(ClientOperations.ReconciliationTrigger.LOCAL_INTENT)
                    .orElseThrow());
            assertTrue(fixture.mutationStarted().await(5, TimeUnit.SECONDS));
            ClientOperations.EditorSave changed = fixture.saveNewSkin("Disabled sibling");
            assertEquals(AppearanceSyncStatus.UNKNOWN,
                    changed.reappliedAppearance().orElseThrow().syncStatus());
            fixture.releaseMutation().countDown();

            ClientOperations.ReconciliationResult completed = mutation.get(5, TimeUnit.SECONDS);
            assertEquals(AppearanceSyncStatus.PENDING, completed.appearance().syncStatus());
            assertFalse(completed.appearance().providers().cape().enabled(BuiltinProvider.MINECRAFT));
            assertEquals(disabledDelivery,
                    completed.appearance().providers().cape().minecraftDelivery());
            assertEquals(1, fixture.api().skinUploads.get());
            assertEquals(0, fixture.api().capeActivations.get());
            assertEquals(0, fixture.api().capeDeactivations.get());
        } finally {
            fixture.releaseMutation().countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void staleCompletionDoesNotResumeAReactivatedDestination() throws Exception {
        InFlightSkinFixture fixture = inFlightSkinFixture();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ClientOperations.ReconciliationResult> mutation = pool.submit(() -> fixture.operations()
                    .reconcileAppearance(ClientOperations.ReconciliationTrigger.LOCAL_INTENT)
                    .orElseThrow());
            assertTrue(fixture.mutationStarted().await(5, TimeUnit.SECONDS));
            fixture.operations().disableProvider(
                    AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT);
            ClientOperations.DurableAppearance reenabled = fixture.operations().enableProvider(
                    AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT);
            long reactivated = reenabled.providers().cape().minecraftDelivery().activation();
            ClientOperations.EditorSave changed = fixture.saveNewSkin("Reactivated sibling");
            assertEquals(AppearanceSyncStatus.UNKNOWN,
                    changed.reappliedAppearance().orElseThrow().syncStatus());
            fixture.releaseMutation().countDown();

            ClientOperations.ReconciliationResult completed = mutation.get(5, TimeUnit.SECONDS);
            assertEquals(AppearanceSyncStatus.UNKNOWN, completed.appearance().syncStatus());
            assertTrue(completed.appearance().providers().cape().enabled(BuiltinProvider.MINECRAFT));
            assertEquals(reactivated,
                    completed.appearance().providers().cape().minecraftDelivery().activation());
            assertEquals(ProviderDelivery.Status.UNKNOWN,
                    completed.appearance().providers().cape().minecraftDelivery().status());
            assertEquals(1, fixture.api().skinUploads.get());
            assertEquals(0, fixture.api().capeActivations.get());
            assertEquals(0, fixture.api().capeDeactivations.get());
        } finally {
            fixture.releaseMutation().countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void matchingOrphanedAttemptingSettlesOfficialAfterOneObservationWithoutMutation()
            throws Exception {
        byte[] skin = skinPng(0xFF4F718D);
        URI skinUri = URI.create("https://textures.minecraft.net/texture/matching-orphan");
        NclSkinsStorage shared = storage();
        shared.initialize();
        Files.write(
                new com.naocraftlab.skins.core.storage.TextureCache(shared).cachePath(skinUri),
                skin);
        StubProfileApi api = new StubProfileApi();
        api.profile = profileWithActiveAppearance(skinUri, null);
        DefaultClientOperations firstProcess = new DefaultClientOperations(
                tokens(), api, shared, ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = firstProcess.initialize();
        ClientOperations.EditorSave saved = firstProcess.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Matching orphan",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        ClientOperations.PresetUse selected = firstProcess.usePreset(saved.presetId());
        shared.updateAppearance(TestFixtures.ACCOUNT_ID, current ->
                new com.naocraftlab.skins.core.model.AccountAppearanceState(
                        current.schemaVersion(),
                        current.accountId(),
                        current.intentRevision(),
                        current.activePresetId(),
                        current.skinSha256(),
                        current.skinVariant(),
                        current.capeId(),
                        current.outerLayerVisibility(),
                        AppearanceSyncStatus.ATTEMPTING,
                        current.settledRevision(),
                        current.updatedAt()));

        DefaultClientOperations restarted = new DefaultClientOperations(
                tokens(), api, shared, ignored -> skin.clone(), fixedClock());
        ClientOperations.ReconciliationResult recovered = restarted
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();

        assertEquals(selected.intentRevision(), recovered.appearance().intentRevision());
        assertEquals(AppearanceSyncStatus.OFFICIAL, recovered.appearance().syncStatus());
        assertTrue(recovered.outcome().isEmpty());
        assertEquals(1, api.profileGets.get());
        assertEquals(0, api.skinUploads.get());
        assertEquals(0, api.skinResets.get());
        assertEquals(0, api.capeActivations.get());
        assertEquals(0, api.capeDeactivations.get());
    }

    @Test
    void orphanedAttemptingObservesOnceBecomesUnknownAndOnlyExplicitRetryMutates()
            throws Exception {
        byte[] skin = skinPng(0xFF526E8A);
        NclSkinsStorage shared = storage();
        StubProfileApi api = new StubProfileApi();
        DefaultClientOperations firstProcess = new DefaultClientOperations(
                tokens(), api, shared, ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = firstProcess.initialize();
        ClientOperations.EditorSave saved = firstProcess.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(), "Crash", SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC, SkinVariant.CLASSIC, Optional.empty(), Optional.empty()));
        ClientOperations.PresetUse selected = firstProcess.usePreset(saved.presetId());
        shared.updateAppearance(TestFixtures.ACCOUNT_ID, current -> new com.naocraftlab.skins.core.model.AccountAppearanceState(
                current.schemaVersion(), current.accountId(), current.intentRevision(), current.activePresetId(),
                current.skinSha256(), current.skinVariant(), current.capeId(), current.outerLayerVisibility(),
                AppearanceSyncStatus.ATTEMPTING, current.settledRevision(), current.updatedAt()));

        DefaultClientOperations restarted = new DefaultClientOperations(
                tokens(), api, shared, ignored -> skin.clone(), fixedClock());
        ClientOperations.ReconciliationResult observed = restarted
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();
        assertEquals(selected.intentRevision(), observed.appearance().intentRevision());
        assertEquals(AppearanceSyncStatus.UNKNOWN, observed.appearance().syncStatus());
        assertEquals(1, api.profileGets.get());
        assertEquals(0, api.skinUploads.get());

        ClientOperations.ReconciliationResult automatic = restarted
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.RECONNECT)
                .orElseThrow();
        assertEquals(AppearanceSyncStatus.UNKNOWN, automatic.appearance().syncStatus());
        assertEquals(1, api.profileGets.get());
        assertEquals(0, api.skinUploads.get());

        ClientOperations.ReconciliationResult explicit = restarted
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.EXPLICIT_RETRY)
                .orElseThrow();
        assertEquals(AppearanceSyncStatus.OFFICIAL, explicit.appearance().syncStatus());
        assertEquals(2, api.profileGets.get());
        assertEquals(1, api.skinUploads.get());
    }

    @Test
    void unavailableNewOfficialTextureNeverBootstrapsAStaleOfficialAsset() throws Exception {
        byte[] stale = skinPng(0xFFAA3300);
        byte[] bundled = skinPng(0xFF0033AA);
        NclSkinsStorage storage = storage();
        storage.initialize();
        var staleImport = new LibraryService(storage, fixedClock()).importSkin(
                TestFixtures.ACCOUNT_ID,
                "Stale official",
                SkinVariant.CLASSIC,
                SkinSource.CURRENT_OFFICIAL,
                stale);
        UUID staleId = staleImport.asset().id();
        StubProfileApi api = new StubProfileApi();
        api.profile = profileWithActiveAppearance(
                URI.create("https://textures.minecraft.net/texture/new-unavailable-skin"), null);
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(),
                api,
                storage,
                ignored -> bundled.clone(),
                fixedClock(),
                ignored -> {
                    throw new IOException("new official texture unavailable");
                });

        ClientOperations.InitialData initial = operations.initialize();

        assertTrue(initial.account().presets().isEmpty());
        assertTrue(initial.activePresetId().isEmpty());
        assertEquals(Optional.of(staleId), initial.currentOfficialSkinId());
        assertEquals(1, initial.account().skinAssets().stream()
                .filter(asset -> asset.source() == SkinSource.CURRENT_OFFICIAL)
                .count());
    }

    @Test
    void localDeleteUsesExactCurrentAccountWithoutConsultingAtomicTokenSnapshot()
            throws Exception {
        UUID otherAccount = UUID.fromString("00000000-0000-0000-0000-000000000099");
        var accountA = new GameSessionTokenSource.SessionIdentity(TestFixtures.ACCOUNT_ID, "Player");
        var accountB = new GameSessionTokenSource.SessionIdentity(otherAccount, "Other");
        SwitchingTokenSource tokens = new SwitchingTokenSource(accountA);
        byte[] skin = skinPng(0xFF778899);
        URI skinUri = URI.create("https://textures.minecraft.net/texture/pinned-account-skin");
        StubProfileApi api = new StubProfileApi();
        api.profile = profileWithActiveAppearance(skinUri, null);
        NclSkinsStorage storage = storage();
        storage.initialize();
        Files.write(new com.naocraftlab.skins.core.storage.TextureCache(storage).cachePath(skinUri), skin);
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens, api, storage, ignored -> skin.clone(), fixedClock());
        operations.initialize();
        UUID presetId = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow()
                .account()
                .presets()
                .get(0)
                .id();


        tokens.atomicIdentity = accountB;
        ClientOperations.PresetDelete deletion = operations.deletePreset(presetId);

        assertTrue(deletion.account().presets().isEmpty());
        assertTrue(deletion.remoteReset().isEmpty());
        assertEquals(AppearanceSyncStatus.PENDING, deletion.appearance().orElseThrow().syncStatus());
        assertEquals(0, api.skinResets.get());
        assertFalse(Files.exists(storage.layout().accountState(otherAccount)));
        assertTrue(Files.isRegularFile(storage.layout().accountState(TestFixtures.ACCOUNT_ID)));
    }

    @Test
    void reconciliationBlocksAtomicTokenIdentityWithAnotherUuidWithoutCrossAccountWrites()
            throws Exception {
        UUID otherAccount = UUID.fromString("00000000-0000-0000-0000-000000000098");
        var accountA = new GameSessionTokenSource.SessionIdentity(TestFixtures.ACCOUNT_ID, "Player");
        var accountB = new GameSessionTokenSource.SessionIdentity(otherAccount, "Other");
        SwitchingTokenSource tokens = new SwitchingTokenSource(accountA);
        tokens.atomicIdentity = accountB;
        NclSkinsStorage storage = storage();
        StubProfileApi api = new StubProfileApi();
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens, api, storage, ignored -> skinPng(0xFF556677), fixedClock());

        ClientOperations.InitialData initial = operations.initialize();
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Pinned to A",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        operations.usePreset(saved.presetId());

        ClientOperations.ReconciliationResult blocked = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();

        assertEquals(TestFixtures.ACCOUNT_ID, initial.account().accountId());
        assertFalse(initial.session().valid());
        assertFalse(blocked.session().valid());
        assertEquals(AppearanceSyncStatus.UNKNOWN, blocked.appearance().syncStatus());
        assertEquals(0, api.profileGets.get());
        assertEquals(0, api.skinUploads.get());
        assertTrue(Files.isRegularFile(storage.layout().accountState(TestFixtures.ACCOUNT_ID)));
        assertFalse(Files.exists(storage.layout().accountState(otherAccount)));
    }

    @Test
    void queuedReconciliationKeyCannotCrossToAnotherAccountWithTheSameRevision()
            throws Exception {
        UUID accountB = UUID.fromString("00000000-0000-0000-0000-000000000097");
        byte[] skin = skinPng(0xFF476985);
        NclSkinsStorage storage = storage();
        StubProfileApi api = new StubProfileApi();

        DefaultClientOperations accountAOperations = new DefaultClientOperations(
                tokens(), api, storage, ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData accountAInitial = accountAOperations.initialize();
        ClientOperations.EditorSave accountAPreset = accountAOperations.saveEditor(
                new ClientOperations.EditorSaveRequest(
                        Optional.empty(),
                        "Account A",
                        SkinReference.asset(accountAInitial.account().skinAssets().get(0).id()),
                        SkinVariant.CLASSIC,
                        SkinVariant.CLASSIC,
                        Optional.empty(),
                        Optional.empty()));
        ClientOperations.PresetUse accountAIntent = accountAOperations.usePreset(accountAPreset.presetId());

        AtomicInteger tokenRequests = new AtomicInteger();
        GameSessionTokenSource accountBTokens = new GameSessionTokenSource() {
            @Override
            public SessionIdentity currentSession() {
                return new SessionIdentity(accountB, "Other");
            }

            @Override
            public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) throws E {
                tokenRequests.incrementAndGet();
                return request.execute("must-not-be-requested");
            }
        };
        DefaultClientOperations accountBOperations = new DefaultClientOperations(
                accountBTokens, api, storage, ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData accountBInitial = accountBOperations.initialize();
        ClientOperations.EditorSave accountBPreset = accountBOperations.saveEditor(
                new ClientOperations.EditorSaveRequest(
                        Optional.empty(),
                        "Account B",
                        SkinReference.asset(accountBInitial.account().skinAssets().get(0).id()),
                        SkinVariant.CLASSIC,
                        SkinVariant.CLASSIC,
                        Optional.empty(),
                        Optional.empty()));
        ClientOperations.PresetUse accountBIntent = accountBOperations.usePreset(accountBPreset.presetId());
        assertEquals(accountAIntent.intentRevision(), accountBIntent.intentRevision());

        Optional<ClientOperations.ReconciliationResult> crossed = accountBOperations.reconcileAppearance(
                new ClientOperations.ReconciliationKey(
                        TestFixtures.ACCOUNT_ID, accountAIntent.intentRevision()),
                ClientOperations.ReconciliationTrigger.PROCESS_START);
        Optional<ClientOperations.ReconciliationResult> stale = accountBOperations.reconcileAppearance(
                new ClientOperations.ReconciliationKey(
                        accountB, accountBIntent.intentRevision() - 1),
                ClientOperations.ReconciliationTrigger.PROCESS_START);

        assertTrue(crossed.isEmpty());
        assertTrue(stale.isEmpty());
        assertEquals(0, tokenRequests.get());
        assertEquals(0, api.profileGets.get());
        assertEquals(0, api.skinUploads.get());
        assertEquals(0, api.skinResets.get());
        assertEquals(0, api.capeActivations.get());
        assertEquals(0, api.capeDeactivations.get());
        assertEquals(AppearanceSyncStatus.PENDING, storage.loadAppearance(accountB).syncStatus());
        assertEquals(accountBIntent.intentRevision(), storage.loadAppearance(accountB).intentRevision());
    }

    @Test
    void deterministicResolverHandlesRemoteAssetsResetAndAccountMismatchWithoutProfileGet()
            throws Exception {
        byte[] classic = skinPng(0xFF224466);
        byte[] slim = skinPng(0xFF664422);
        SkinCatalogSource bundled = (collectionId, skinId, model) ->
                model == SkinModel.SLIM ? slim.clone() : classic.clone();
        StubProfileApi api = new StubProfileApi();
        GameSessionTokenSource tokens = tokens();
        NclSkinsStorage storage = new NclSkinsStorage(
                temporaryDirectory,
                new PngValidator(),
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens,
                api,
                storage,
                bundled,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        storage.initialize();

        URI skinUri = URI.create("https://textures.minecraft.net/texture/runtime-skin");
        URI capeUri = URI.create("https://textures.minecraft.net/texture/runtime-cape");
        var cacheLayout = new com.naocraftlab.skins.core.storage.TextureCache(storage);
        Path skinPath = cacheLayout.cachePath(skinUri);
        Path capePath = cacheLayout.cachePath(capeUri);
        Files.write(skinPath, classic);
        Files.write(capePath, slim);

        DeterministicAppearanceAssetResolver resolver =
                operations.deterministicAppearanceResolver(Runnable::run);
        var remote = resolver.resolve(new ExpectedAppearance(
                        TestFixtures.ACCOUNT_ID,
                        Optional.of(skinUri),
                        Optional.empty(),
                        Optional.of(SkinModel.CLASSIC),
                        Optional.of(capeUri)))
                .join()
                .orElseThrow()
                .platformProfile();
        assertEquals(skinPath, remote.skin().orElseThrow().path());
        assertEquals(capePath, remote.cape().orElseThrow().path());

        var reset = resolver.resolve(new ExpectedAppearance(
                        TestFixtures.ACCOUNT_ID,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()))
                .join()
                .orElseThrow()
                .platformProfile();
        assertTrue(reset.skin().isEmpty());
        assertTrue(reset.cape().isEmpty());

        assertFalse(resolver.resolve(new ExpectedAppearance(
                        TestFixtures.ACCOUNT_ID,
                        Optional.empty(),
                        Optional.of("f".repeat(64)),
                        Optional.of(SkinModel.SLIM),
                        Optional.empty()))
                .join()
                .isPresent());
        assertFalse(resolver.resolve(new ExpectedAppearance(
                        UUID.randomUUID(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()))
                .join()
                .isPresent());
        assertEquals(0, api.profileGets.get());
    }

    @Test
    void editorSaveReappliesOnlyThePresetActiveUnderTheExactAccountLock()
            throws Exception {
        byte[] skin = skinPng(0xFF315A72);
        StubProfileApi api = new StubProfileApi();
        NclSkinsStorage shared = storage();
        DefaultClientOperations first = new DefaultClientOperations(
                tokens(), api, shared, ignored -> skin.clone(), fixedClock());
        DefaultClientOperations second = new DefaultClientOperations(
                tokens(), api, shared, ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = first.initialize();
        UUID skinId = initial.account().skinAssets().get(0).id();
        ClientOperations.EditorSave presetA = first.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Preset A",
                SkinReference.asset(skinId),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));
        ClientOperations.EditorSave presetB = first.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Preset B",
                SkinReference.asset(skinId),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));

        first.usePreset(presetA.presetId());
        ClientOperations.PresetUse switchedToB = second.usePreset(presetB.presetId());
        ClientOperations.EditorSave inactiveEdit = first.saveEditor(
                new ClientOperations.EditorSaveRequest(
                        Optional.of(presetA.presetId()),
                        "Preset A edited while inactive",
                        SkinReference.asset(skinId),
                        SkinVariant.CLASSIC,
                        SkinVariant.CLASSIC,
                        Optional.empty(),
                        Optional.empty()));

        assertTrue(inactiveEdit.reappliedAppearance().isEmpty());
        ClientOperations.DurableAppearance stillB = first.durableAppearance().orElseThrow();
        assertEquals(Optional.of(presetB.presetId()), stillB.activePresetId());
        assertEquals(switchedToB.intentRevision(), stillB.intentRevision());

        ClientOperations.PresetUse switchedBackToA = second.usePreset(presetA.presetId());
        ClientOperations.EditorSave activeEdit = first.saveEditor(
                new ClientOperations.EditorSaveRequest(
                        Optional.of(presetA.presetId()),
                        "Preset A edited while active",
                        SkinReference.asset(skinId),
                        SkinVariant.CLASSIC,
                        SkinVariant.CLASSIC,
                        Optional.empty(),
                        Optional.empty()));

        ClientOperations.DurableAppearance reapplied =
                activeEdit.reappliedAppearance().orElseThrow();
        assertEquals(Optional.of(presetA.presetId()), reapplied.activePresetId());
        assertEquals(switchedBackToA.intentRevision() + 1, reapplied.intentRevision());
        assertEquals(AppearanceSyncStatus.PENDING, reapplied.syncStatus());
        assertTrue(reapplied.localAppearance().isPresent());
        assertEquals(0, api.profileGets.get());
        assertEquals(0, api.skinUploads.get());
    }

    @Test
    void localApplyAndActiveSaveReapplyNeverDownloadCapeFromCachedProfile()
            throws Exception {
        byte[] skin = skinPng(0xFF416783);
        byte[] cape = skinPng(0xFF6D482F);
        URI capeUri = URI.create("https://textures.minecraft.net/texture/local-only-cape");
        StubProfileApi api = new StubProfileApi();
        api.profile = new RemoteProfile(
                TestFixtures.ACCOUNT_ID,
                "Player",
                List.of(),
                List.of(new RemoteCape(
                        "cape-owned", RemoteAssetState.INACTIVE, capeUri, "Owned cape")),
                Set.of());
        NclSkinsStorage storage = storage();
        var cache = new com.naocraftlab.skins.core.storage.TextureCache(storage);
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, storage, ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        operations.reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();
        UUID skinId = initial.account().skinAssets().get(0).id();
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Cached-profile cape",
                SkinReference.asset(skinId),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.of("cape-owned"),
                Optional.empty()));
        ClientOperations.PresetUse selected = operations.usePreset(saved.presetId());
        AtomicInteger remoteCalls = new AtomicInteger();
        DeterministicAppearanceAssetResolver resolver = new DeterministicAppearanceAssetResolver(
                tokens(), storage, cache, Runnable::run, uri -> {
                    remoteCalls.incrementAndGet();
                    throw new AssertionError("local materialization must not download cape texture");
                });

        AppliedAppearance missingCape = selected.localAppearance().orElseThrow();
        var resolvedMiss = resolver.resolve(expectedAppearance(missingCape)).join().orElseThrow();

        assertTrue(missingCape.capeTexture().isEmpty());
        assertTrue(missingCape.localCapeCacheKey().isEmpty());
        assertTrue(resolvedMiss.platformProfile().cape().isEmpty());
        assertEquals(0, remoteCalls.get());

        Files.write(cache.cachePath(capeUri), cape);
        var custom = storage.importCape(TestFixtures.ACCOUNT_ID, "Personal", customCapePng());
        ClientOperations.EditorSave edited = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.of(saved.presetId()),
                "Cached-profile cape edited",
                SkinReference.asset(skinId),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.of("cape-owned"),
                Optional.empty()).withOfflineCape(custom.texture()));
        AppliedAppearance cachedCape = edited.reappliedAppearance()
                .orElseThrow()
                .localAppearance()
                .orElseThrow();
        var resolvedCached = resolver.resolve(expectedAppearance(cachedCape)).join().orElseThrow();

        assertTrue(cachedCape.capeTexture().isEmpty());
        assertEquals(
                Optional.of(custom.texture().sha256()),
                cachedCape.localCapeCacheKey());
        assertTrue(resolvedCached.platformProfile().cape().isPresent());
        assertEquals(0, remoteCalls.get());
        assertEquals(1, api.profileGets.get());
        assertEquals(0, api.skinUploads.get());
        assertEquals(0, api.capeActivations.get());
    }

    @Test
    void accountDefaultResetRemainsInstallableWhenAcknowledgedCapeCannotResolve()
            throws Exception {
        NclSkinsStorage storage = storage();
        storage.initialize();
        var cache = new com.naocraftlab.skins.core.storage.TextureCache(storage);
        DeterministicAppearanceAssetResolver resolver = new DeterministicAppearanceAssetResolver(
                tokens(),
                storage,
                cache,
                Runnable::run,
                ignored -> {
                    throw new IOException("cape unavailable");
                });
        URI capeUri = URI.create("https://textures.minecraft.net/texture/unavailable-reset-cape");
        AppliedAppearance reset = AppliedAppearance.accountDefault(
                TestFixtures.ACCOUNT_ID, Optional.of(capeUri));
        AtomicReference<com.naocraftlab.skins.client.SignedProfileResolver.ResolvedProfile<
                        AcknowledgedAppearanceAssets>>
                installed = new AtomicReference<>();
        ClientExecutor directClient = new ClientExecutor() {
            @Override
            public boolean isClientThread() {
                return true;
            }

            @Override
            public void execute(Runnable action) {
                action.run();
            }
        };
        PlayerAppearanceSink<AcknowledgedAppearanceAssets> sink = resolved -> {
            installed.set(resolved);
            return PlayerAppearanceSink.ApplyResult.UPDATED;
        };
        AppearanceRefreshCoordinator<AcknowledgedAppearanceAssets> coordinator =
                new AppearanceRefreshCoordinator<>(
                        directClient, resolver, sink, DiagnosticSinks.discarding());

        assertEquals(
                AppearanceRefreshCoordinator.Result.UPDATED,
                coordinator.afterReconnect(reset, ignored -> {}).join());
        var resolved = installed.get();
        assertTrue(resolved.platformProfile().skin().isEmpty());
        assertTrue(resolved.platformProfile().cape().isEmpty());
        assertTrue(resolved.expectedAppearance().skinTexture().isEmpty());
        assertTrue(resolved.expectedAppearance().skinModel().isEmpty());
        assertTrue(resolved.expectedAppearance().capeTexture().isEmpty());
    }

    @Test
    void acknowledgedOfflineCapeResolvesFromOpaqueAccountCacheKeyWithoutNetwork()
            throws Exception {
        NclSkinsStorage storage = storage();
        storage.initialize();
        var cache = new com.naocraftlab.skins.core.storage.TextureCache(storage);
        String key = "c".repeat(64);
        Files.write(cache.cachePath(key), skinPng(0xFF224466));
        AtomicInteger remoteCalls = new AtomicInteger();
        DeterministicAppearanceAssetResolver resolver = new DeterministicAppearanceAssetResolver(
                tokens(), storage, cache, Runnable::run, uri -> {
                    remoteCalls.incrementAndGet();
                    throw new AssertionError("offline cape must not request its old URL");
                });
        ExpectedAppearance expected = new ExpectedAppearance(
                TestFixtures.ACCOUNT_ID,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(key));

        var resolved = resolver.resolve(expected).join().orElseThrow();

        assertEquals(key, resolved.platformProfile().cape().orElseThrow().sha256());
        assertEquals(cache.cachePath(key), resolved.platformProfile().cape().orElseThrow().path());
        assertEquals(0, remoteCalls.get());
    }

    private SettledActiveFixture settledActiveFixture() throws Exception {
        StubProfileApi api = new StubProfileApi();
        api.profile = new RemoteProfile(
                TestFixtures.ACCOUNT_ID,
                "Player",
                List.of(),
                List.of(new RemoteCape(
                        "cape-owned",
                        RemoteAssetState.ACTIVE,
                        URI.create("https://textures.minecraft.net/texture/settled-cape"),
                        "Settled cape")),
                Set.of());
        AtomicInteger tokenRequests = new AtomicInteger();
        NclSkinsStorage shared = storage();
        DefaultClientOperations operations = new DefaultClientOperations(
                countingTokens(tokenRequests), api, shared, ignored -> skinPng(0xFF315B72), fixedClock());
        operations.initialize();
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Settled",
                SkinReference.accountDefault(),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.of("cape-owned"),
                Optional.empty()));
        operations.usePreset(saved.presetId());
        ClientOperations.DurableAppearance settled = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow()
                .appearance();
        assertEquals(AppearanceSyncStatus.OFFICIAL, settled.syncStatus());
        return new SettledActiveFixture(
                operations,
                api,
                shared,
                tokenRequests,
                tokenRequests.get(),
                saved.presetId(),
                settled.providers().skin().minecraftDelivery(),
                settled.providers().cape().minecraftDelivery(),
                api.profileGets.get(),
                api.skinUploads.get(),
                api.capeActivations.get(),
                api.capeDeactivations.get());
    }

    private InFlightSkinFixture inFlightSkinFixture() throws Exception {
        byte[] skin = skinPng(0xFF4A6682);
        StubProfileApi api = new StubProfileApi();
        CountDownLatch mutationStarted = new CountDownLatch(1);
        CountDownLatch releaseMutation = new CountDownLatch(1);
        api.beforeSkinUpload = () -> {
            mutationStarted.countDown();
            try {
                assertTrue(releaseMutation.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        };
        NclSkinsStorage shared = storage();
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, shared, ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = operations.initialize();
        SkinAsset firstSkin = initial.account().skinAssets().get(0);
        SkinAsset replacementSkin = initial.account().skinAssets().get(1);
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "In flight",
                SkinReference.asset(firstSkin.id()),
                firstSkin.variant(),
                firstSkin.variant(),
                Optional.empty(),
                Optional.empty()));
        operations.usePreset(saved.presetId());
        return new InFlightSkinFixture(
                operations,
                api,
                shared,
                saved.presetId(),
                replacementSkin.id(),
                replacementSkin.variant(),
                mutationStarted,
                releaseMutation);
    }

    private ObservedSkinFixture observedSkinFixture() throws Exception {
        byte[] skin = skinPng(0xFF315B72);
        URI officialUri = URI.create("https://textures.minecraft.net/texture/observed-a");
        StubProfileApi api = new StubProfileApi();
        api.profile = profileWithActiveAppearance(officialUri, null);
        NclSkinsStorage shared = storage();
        shared.initialize();
        Files.write(new com.naocraftlab.skins.core.storage.TextureCache(shared).cachePath(officialUri), skin);
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, shared, ignored -> skin.clone(), fixedClock());
        operations.initialize();
        ClientOperations.ReconciliationResult official = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();
        ProviderSkin observed = official.appearance().providers().skin().desired();
        SkinAsset skinA = official.account().skinAssets().stream()
                .filter(asset -> asset.sha256().equals(observed.sha256()))
                .findFirst()
                .orElseThrow();
        SkinAsset skinB = official.account().skinAssets().stream()
                .filter(asset -> !asset.id().equals(skinA.id()))
                .filter(asset -> asset.variant() == SkinVariant.SLIM)
                .findFirst()
                .orElseGet(() -> official.account().skinAssets().stream()
                        .filter(asset -> !asset.id().equals(skinA.id()))
                        .findFirst()
                        .orElseThrow());
        ClientOperations.EditorSave pending = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Pending B",
                SkinReference.asset(skinB.id()),
                skinB.variant(),
                skinB.variant(),
                Optional.empty(),
                Optional.empty()));
        operations.usePreset(pending.presetId());
        return new ObservedSkinFixture(
                operations,
                api,
                shared,
                pending.presetId(),
                skinA.id(),
                skinA.variant(),
                skinB.id(),
                skinB.variant());
    }

    private void assertSettledLocalOnlySave(
            SettledActiveFixture fixture,
            ClientOperations.EditorSaveRequest request) throws Exception {
        ClientOperations.EditorSave edited = fixture.operations().saveEditor(request);

        assertEquals(AppearanceSyncStatus.OFFICIAL,
                edited.reappliedAppearance().orElseThrow().syncStatus());
        assertEquals(fixture.skinDelivery(),
                edited.reappliedAppearance().orElseThrow().providers().skin().minecraftDelivery());
        assertEquals(fixture.capeDelivery(),
                edited.reappliedAppearance().orElseThrow().providers().cape().minecraftDelivery());
        assertNoRemoteCheckpoint(fixture, edited);
    }

    private static void assertNoRemoteCheckpoint(
            SettledActiveFixture fixture,
            ClientOperations.EditorSave edited) throws Exception {
        ClientOperations.ReconciliationResult checkpoint = fixture.operations()
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();
        assertEquals(AppearanceSyncStatus.OFFICIAL, checkpoint.appearance().syncStatus());
        assertEquals(edited.reappliedAppearance().orElseThrow().intentRevision(),
                checkpoint.appearance().intentRevision());
        assertEquals(fixture.tokenRequests(), fixture.tokenCounter().get());
        assertEquals(fixture.profileGets(), fixture.api().profileGets.get());
        assertEquals(fixture.skinUploads(), fixture.api().skinUploads.get());
        assertEquals(fixture.capeActivations(), fixture.api().capeActivations.get());
        assertEquals(fixture.capeDeactivations(), fixture.api().capeDeactivations.get());
    }

    private static void savePersonal(
            DefaultClientOperations operations,
            byte[] png,
            String name,
            PersonalSkinSource source) throws Exception {
        operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                name,
                SkinReference.accountDefault(),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                OuterLayerVisibility.allVisible(),
                Optional.of(png),
                Optional.empty(),
                Optional.of(name),
                source));
    }

    private static GameSessionTokenSource tokens() {
        return tokens(TestFixtures.ACCOUNT_ID, "Player");
    }

    private static GameSessionTokenSource tokens(UUID profileId, String profileName) {
        return new GameSessionTokenSource() {
            @Override
            public SessionIdentity currentSession() {
                return new SessionIdentity(profileId, profileName);
            }

            @Override
            public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) throws E {
                return request.execute("scoped-token");
            }
        };
    }

    private static GameSessionTokenSource countingTokens(AtomicInteger requests) {
        return new GameSessionTokenSource() {
            @Override
            public SessionIdentity currentSession() {
                return new SessionIdentity(TestFixtures.ACCOUNT_ID, "Player");
            }

            @Override
            public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) throws E {
                requests.incrementAndGet();
                return request.execute("scoped-token");
            }
        };
    }

    @Test
    void assignedDefaultFallsThroughAfterRefreshAndRestartWithoutChangingOfflineOrCape() throws Exception {
        byte[] custom = skinPng(0xff135724);
        byte[] assigned = skinPng(0xff987654);
        URI skinUri = URI.create("https://textures.minecraft.net/texture/" + "a".repeat(64));
        URI capeUri = URI.create("https://textures.minecraft.net/texture/" + "b".repeat(64));
        StubProfileApi api = new StubProfileApi();
        api.profile = profileWithActiveAppearance(skinUri, capeUri);
        NclSkinsStorage shared = storage();
        SkinCatalogSource source = (collection, name, model) -> assigned.clone();
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, shared, source, fixedClock(), ignored -> custom.clone());
        var initial = operations.retrySession();
        assertEquals(1, initial.account().presets().size());
        var offline = operations.loadProviders().skin().offline();
        operations.moveProvider(AppearanceProviders.Component.SKIN, BuiltinProvider.MINECRAFT, -1);
        api.profile = new RemoteProfile(TestFixtures.ACCOUNT_ID, "Player",
                List.of(new RemoteSkin("default", RemoteAssetState.ACTIVE, skinUri, SkinVariant.SLIM, null)),
                api.profile.capes(), Set.of());
        DefaultClientOperations restarted = new DefaultClientOperations(
                tokens(), api, shared, source, fixedClock(), ignored -> assigned.clone());
        var refreshed = restarted.refreshProviders(AppearanceProviders.Component.SKIN);
        assertTrue(refreshed.providers().skin().minecraft().known());
        assertNull(refreshed.providers().skin().minecraft().value());
        assertEquals(offline, refreshed.providers().skin().offline());
        assertEquals(BuiltinProvider.OFFLINE, refreshed.providers().skin().resolve().orElseThrow().provider());
        assertEquals(offline.value().sha256(), refreshed.localAppearance().orElseThrow().localSkinSha256().orElseThrow());
        assertEquals("cape-active", refreshed.providers().cape().minecraft().value().id());
        assertEquals(1, shared.loadOrCreateAccount(TestFixtures.ACCOUNT_ID).presets().size());
        var again = new DefaultClientOperations(tokens(), api, shared, source, fixedClock(), ignored -> assigned.clone())
                .retrySession();
        assertEquals(offline.value().sha256(), again.localAppearance().orElseThrow().localSkinSha256().orElseThrow());
        assertEquals(0, api.skinUploads.get());
        assertEquals(0, api.skinResets.get());
        assertEquals(0, api.capeActivations.get());
    }

    @Test
    void assignedDefaultDoesNotBootstrapAndMalformedObservationPreservesKnownSkin() throws Exception {
        byte[] assigned = skinPng(0xff987654);
        URI skinUri = URI.create("https://textures.minecraft.net/texture/" + "a".repeat(64));
        StubProfileApi api = new StubProfileApi();
        api.profile = new RemoteProfile(TestFixtures.ACCOUNT_ID, "Player",
                List.of(new RemoteSkin("default", RemoteAssetState.ACTIVE, skinUri, SkinVariant.SLIM, null)),
                List.of(), Set.of());
        NclSkinsStorage shared = storage();
        DefaultClientOperations operations = new DefaultClientOperations(tokens(), api, shared,
                (collection, name, model) -> assigned.clone(), fixedClock(), ignored -> assigned.clone());
        var initial = operations.retrySession();
        assertTrue(initial.account().presets().isEmpty());
        assertTrue(initial.currentOfficialSkinId().isEmpty());
        assertTrue(operations.loadProviders().skin().minecraft().known());
        assertNull(operations.loadProviders().skin().minecraft().value());
        assertNull(operations.loadProviders().skin().offline().value());
        api.profile = profileWithActiveAppearance(skinUri, null);
        var custom = operations.refreshProviders(AppearanceProviders.Component.SKIN);
        assertNotNull(custom.providers().skin().minecraft().value());
        api.profile = new RemoteProfile(TestFixtures.ACCOUNT_ID, "Player", List.of(), List.of(), Set.of(), false);
        var malformed = operations.refreshProviders(AppearanceProviders.Component.SKIN);
        assertEquals(custom.providers().skin().minecraft(), malformed.providers().skin().minecraft());
        assertEquals(0, api.skinUploads.get());
        assertEquals(0, api.skinResets.get());
    }

    @Test
    void uploadingAssignedDefaultConfirmsDeliveryWithoutARepeatedUpload() throws Exception {
        byte[] assigned = skinPng(0xff987654);
        StubProfileApi api = new StubProfileApi();
        NclSkinsStorage shared = storage();
        SkinCatalogSource source = (collection, name, model) -> assigned.clone();
        DefaultClientOperations operations = new DefaultClientOperations(
                tokens(), api, shared, source, fixedClock(), ignored -> assigned.clone());
        operations.retrySession();
        var imported = new LibraryService(shared, fixedClock()).importSkin(TestFixtures.ACCOUNT_ID,
                "Manual", SkinVariant.SLIM, SkinSource.IMPORTED, assigned);
        var saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(), "Manual default", SkinReference.asset(imported.asset().id()),
                SkinVariant.SLIM, SkinVariant.SLIM, Optional.empty(), Optional.empty()));
        operations.usePreset(saved.presetId());
        var applied = operations.reconcileAppearance(ClientOperations.ReconciliationTrigger.LOCAL_INTENT).orElseThrow();
        assertEquals(AppearanceSyncStatus.OFFICIAL, applied.appearance().syncStatus());
        assertEquals(1, api.skinUploads.get());
        assertTrue(applied.appearance().providers().skin().minecraft().known());
        assertNull(applied.appearance().providers().skin().minecraft().value());
        assertNotNull(applied.appearance().providers().skin().offline().value());
        operations.reconcileAppearance(ClientOperations.ReconciliationTrigger.LOCAL_INTENT);
        assertEquals(1, api.skinUploads.get());
        assertEquals(0, api.skinResets.get());
    }

    private NclSkinsStorage storage() {
        return new NclSkinsStorage(temporaryDirectory, new PngValidator(), fixedClock());
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    }

    private static ExpectedAppearance expectedAppearance(AppliedAppearance appearance) {
        return new ExpectedAppearance(
                appearance.profileId(),
                appearance.skinTexture(),
                appearance.localSkinSha256(),
                appearance.skinVariant().map(variant -> switch (variant) {
                    case CLASSIC -> SkinModel.CLASSIC;
                    case SLIM -> SkinModel.SLIM;
                }),
                appearance.capeTexture(),
                appearance.localCapeCacheKey());
    }

    private static RemoteProfile profileWithActiveAppearance(URI skinUri, URI capeUri) {
        List<RemoteCape> capes = capeUri == null
                ? List.of()
                : List.of(new RemoteCape("cape-active", RemoteAssetState.ACTIVE, capeUri, "Cape"));
        return new RemoteProfile(
                TestFixtures.ACCOUNT_ID,
                "Player",
                List.of(new RemoteSkin(
                        "skin-active", RemoteAssetState.ACTIVE, skinUri, SkinVariant.CLASSIC, "Skin")),
                capes,
                Set.of());
    }

    private static byte[] skinPng(int color) throws IOException {
        return skinPng(64, 64, color);
    }

    private static byte[] skinPng(int width, int height, int color) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, color);
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static final class StubProfileApi implements ProfileApi {
        private final AtomicInteger profileGets = new AtomicInteger();
        private final AtomicInteger skinUploads = new AtomicInteger();
        private final AtomicInteger skinResets = new AtomicInteger();
        private final AtomicInteger capeActivations = new AtomicInteger();
        private final AtomicInteger capeDeactivations = new AtomicInteger();
        private RemoteProfile profile = new RemoteProfile(
                TestFixtures.ACCOUNT_ID, "Player", List.of(), List.of(), Set.of());
        private Runnable beforeProfileGet;
        private Runnable afterSkinUpload;
        private Runnable beforeSkinUpload;
        private Runnable beforeCapeActivation;
        private ProfileApiException profileFailure;
        private ProfileApiException skinFailure;
        private ProfileApiException capeFailure;
        private Optional<Duration> rateLimitRemaining = Optional.empty();
        private SkinVariant lastUploadedVariant;
        private byte[] lastUploadedSkin;

        @Override
        public RemoteProfile getProfile(String accessToken) throws ProfileApiException {
            profileGets.incrementAndGet();
            if (profileFailure != null) {
                throw profileFailure;
            }
            if (beforeProfileGet != null) {
                beforeProfileGet.run();
            }
            return profile;
        }

        @Override
        public void uploadSkin(String accessToken, SkinVariant variant, byte[] pngBytes)
                throws ProfileApiException {
            skinUploads.incrementAndGet();
            lastUploadedVariant = variant;
            lastUploadedSkin = pngBytes.clone();
            if (skinFailure != null) {
                throw skinFailure;
            }
            if (beforeSkinUpload != null) {
                beforeSkinUpload.run();
            }
            if (afterSkinUpload != null) {
                afterSkinUpload.run();
            }
        }

        @Override
        public void resetSkin(String accessToken) {
            skinResets.incrementAndGet();
        }

        @Override
        public void activateCape(String accessToken, String capeId) throws ProfileApiException {
            capeActivations.incrementAndGet();
            if (beforeCapeActivation != null) {
                beforeCapeActivation.run();
            }
            if (capeFailure != null) {
                throw capeFailure;
            }
        }

        @Override
        public void deactivateCape(String accessToken) throws ProfileApiException {
            capeDeactivations.incrementAndGet();
            if (capeFailure != null) {
                throw capeFailure;
            }
        }

        @Override
        public Optional<Duration> rateLimitRemaining() {
            return rateLimitRemaining;
        }
    }

    private record SettledActiveFixture(
            DefaultClientOperations operations,
            StubProfileApi api,
            NclSkinsStorage storage,
            AtomicInteger tokenCounter,
            int tokenRequests,
            UUID presetId,
            ProviderDelivery skinDelivery,
            ProviderDelivery capeDelivery,
            int profileGets,
            int skinUploads,
            int capeActivations,
            int capeDeactivations) {
    }

    private record ObservedSkinFixture(
            DefaultClientOperations operations,
            StubProfileApi api,
            NclSkinsStorage storage,
            UUID presetId,
            UUID skinA,
            SkinVariant skinAVariant,
            UUID skinB,
            SkinVariant skinBVariant) {
    }

    private record InFlightSkinFixture(
            DefaultClientOperations operations,
            StubProfileApi api,
            NclSkinsStorage storage,
            UUID presetId,
            UUID replacementSkin,
            SkinVariant replacementVariant,
            CountDownLatch mutationStarted,
            CountDownLatch releaseMutation) {
        private ClientOperations.EditorSave saveNewSkin(String name) throws Exception {
            return operations.saveEditor(new ClientOperations.EditorSaveRequest(
                    Optional.of(presetId),
                    name,
                    SkinReference.asset(replacementSkin),
                    replacementVariant,
                    replacementVariant,
                    Optional.empty(),
                    Optional.empty()));
        }
    }

    private static final class SwitchingTokenSource implements GameSessionTokenSource {
        private final SessionIdentity currentIdentity;
        private SessionIdentity atomicIdentity;

        private SwitchingTokenSource(SessionIdentity identity) {
            this.currentIdentity = identity;
            this.atomicIdentity = identity;
        }

        @Override
        public SessionIdentity currentSession() {
            return currentIdentity;
        }

        @Override
        public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) throws E {
            return request.execute("atomic-token");
        }

        @Override
        public <T, E extends Exception> T withSession(SessionRequest<T, E> request) throws E {
            return request.execute(atomicIdentity, "atomic-token");
        }
    }
}
