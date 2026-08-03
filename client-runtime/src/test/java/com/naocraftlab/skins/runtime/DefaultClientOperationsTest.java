package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.client.ExpectedAppearance;
import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.MinecraftSkinCatalog;
import com.naocraftlab.skins.client.PersonalSkinCatalog;
import com.naocraftlab.skins.client.PlayerAppearanceSink;
import com.naocraftlab.skins.client.ResourcePackSkinCatalog;
import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.core.api.ApiFailureKind;
import com.naocraftlab.skins.core.api.ProfileApi;
import com.naocraftlab.skins.core.api.ProfileApiException;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AddSourceTab;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.model.CatalogOrigin;
import com.naocraftlab.skins.core.model.MutationResult;
import com.naocraftlab.skins.core.model.RemoteAssetState;
import com.naocraftlab.skins.core.model.RemoteCape;
import com.naocraftlab.skins.core.model.RemoteProfile;
import com.naocraftlab.skins.core.model.RemoteSkin;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.model.SkinSource;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.service.ApplicationPhase;
import com.naocraftlab.skins.core.service.AppliedAppearance;
import com.naocraftlab.skins.core.service.LibraryService;
import com.naocraftlab.skins.core.service.PresetApplicationOutcome;
import com.naocraftlab.skins.core.service.RemoteAppearanceImpact;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DefaultClientOperationsTest {
    @TempDir
    java.nio.file.Path temporaryDirectory;

    @Test
    void offlineSelectionReopensInAnotherInstanceAndSynchronizesOnceOnline() throws Exception {
        byte[] skin = skinPng(0xFF315B72);
        NclSkinsStorage shared = storage();
        StubProfileApi offlineApi = new StubProfileApi();
        offlineApi.profileFailure = new ProfileApiException(
                ApiFailureKind.NETWORK, "offline", null, null, false);
        DefaultClientOperations offline = new DefaultClientOperations(
                tokens(), offlineApi, shared, ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData initial = offline.initialize();
        ClientOperations.EditorSave saved = offline.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Offline choice",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.empty(),
                Optional.empty()));

        ClientOperations.PresetUse selected = offline.usePreset(saved.presetId());
        assertTrue(selected.remoteResult().isEmpty());
        assertTrue(selected.pendingOfficialSync());
        assertEquals(AppearanceSyncStatus.PENDING, selected.syncStatus());
        assertTrue(selected.intentRevision() > 0);
        assertTrue(selected.localAppearance().isPresent());
        assertEquals(0, offlineApi.profileGets.get());
        assertEquals(0, offlineApi.skinUploads.get());
        assertEquals(Optional.of(saved.presetId()), selected.account().presets().stream()
                .filter(preset -> preset.id().equals(saved.presetId()))
                .map(preset -> preset.id())
                .findFirst());

        DefaultClientOperations anotherOffline = new DefaultClientOperations(
                tokens(), offlineApi, storage(), ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData reopenedOffline = anotherOffline.initialize();
        assertEquals(Optional.of(saved.presetId()), reopenedOffline.activePresetId());
        assertTrue(reopenedOffline.pendingOfficialSync());
        assertTrue(reopenedOffline.localAppearance().isPresent());

        StubProfileApi onlineApi = new StubProfileApi();
        DefaultClientOperations online = new DefaultClientOperations(
                tokens(), onlineApi, storage(), ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData stillPending = online.initialize();
        assertEquals(Optional.of(saved.presetId()), stillPending.activePresetId());
        assertTrue(stillPending.pendingOfficialSync());
        assertEquals(0, onlineApi.skinUploads.get());

        ClientOperations.ReconciliationResult synchronizedOnline = online
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.GALLERY_OPEN)
                .orElseThrow();

        assertEquals(Optional.of(saved.presetId()), synchronizedOnline.appearance().activePresetId());
        assertEquals(AppearanceSyncStatus.OFFICIAL, synchronizedOnline.appearance().syncStatus());
        assertEquals(1, onlineApi.skinUploads.get());
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
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.GALLERY_OPEN)
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
        assertTrue(initial.uiPreferences().preferredSkinVariant().isEmpty());
        first.setSelectedAddSourceTab(AddSourceTab.CATALOG);
        first.setCollectionCollapsed(MinecraftSkinCatalog.COLLECTION_ID, true);
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
        assertEquals(Optional.of(SkinVariant.SLIM), reopened.uiPreferences().preferredSkinVariant());
        assertTrue(reopened.uiPreferences()
                .collapsedCollectionIds()
                .contains(MinecraftSkinCatalog.COLLECTION_ID));
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
        assertEquals(
                new ClientOperations.ReconciliationKey(TestFixtures.ACCOUNT_ID, 0),
                operations.warmedDurableAppearance().orElseThrow().reconciliationKey());
        assertEquals(
                new ClientOperations.ReconciliationKey(TestFixtures.ACCOUNT_ID, 0),
                operations.reconciliationKey().orElseThrow());
        ClientOperations.InitialData cached = operations.initialize();
        assertEquals(0, api.profileGets.get());
        assertTrue(cached.account().presets().isEmpty());

        ClientOperations.ReconciliationResult initial = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.PROCESS_START)
                .orElseThrow();

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
        assertEquals(Optional.of(preset.id()), initial.appearance().activePresetId());

        ClientOperations.InitialData reopened = operations.initialize();
        assertEquals(1, reopened.account().presets().size());
        assertEquals(Optional.of(preset.id()), reopened.activePresetId());
        assertEquals(1, api.profileGets.get());

        ClientOperations.InitialData reset = operations.resetLibrary();
        assertTrue(reset.account().presets().isEmpty());
        assertTrue(reset.activePresetId().isEmpty());
        assertTrue(reset.pendingOfficialSync());
        assertEquals(AppearanceSyncStatus.PENDING, reset.syncStatus());
        assertTrue(reset.localAppearance().orElseThrow().usesAccountDefaultSkin());
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
        assertEquals(0, api.profileGets.get());
    }

    @Test
    void activeDeleteIsImmediateAccountDefaultPendingAndReopensWithoutRemoteTraffic()
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
        assertTrue(localDefault.localAppearance().orElseThrow().usesAccountDefaultSkin());
        assertEquals(0, api.skinResets.get());

        DefaultClientOperations reopenedClient = new DefaultClientOperations(
                tokens(), api, storage, ignored -> skin.clone(), fixedClock());
        ClientOperations.InitialData reopened = reopenedClient.initialize();
        assertTrue(reopened.account().presets().isEmpty());
        assertTrue(reopened.activePresetId().isEmpty());
        assertTrue(reopened.pendingOfficialSync());
        assertEquals(AppearanceSyncStatus.PENDING, reopened.syncStatus());
        assertTrue(reopened.localAppearance().orElseThrow().usesAccountDefaultSkin());
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
        AccountState externallyEmpty = otherClient.completeAcknowledgedFinalPresetDeletion(
                TestFixtures.ACCOUNT_ID, transientPreset);
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
        assertTrue(deleted.appearance().orElseThrow().localAppearance()
                .orElseThrow()
                .usesAccountDefaultSkin());

        ClientOperations.ReconciliationResult reset = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.GALLERY_OPEN)
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
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.GALLERY_OPEN)
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
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.GALLERY_OPEN)
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
    void missingTokenSettlesUnknownWithoutApiAndExplicitRetryCanRecover() throws Exception {
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
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.GALLERY_OPEN)
                .orElseThrow();
        ClientOperations.ReconciliationResult automatic = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.RECONNECT)
                .orElseThrow();

        assertTrue(selected.localAppearance().isPresent());
        assertEquals(AppearanceSyncStatus.UNKNOWN, checkpoint.appearance().syncStatus());
        assertEquals(AppearanceSyncStatus.UNKNOWN, automatic.appearance().syncStatus());
        assertEquals(1, tokenRequests.get());
        assertEquals(0, api.profileGets.get());
        assertEquals(0, api.skinUploads.get());
        assertEquals(0, api.skinResets.get());

        availableToken.set("restored-token");
        ClientOperations.ReconciliationResult explicit = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.EXPLICIT_RETRY)
                .orElseThrow();

        assertEquals(AppearanceSyncStatus.OFFICIAL, explicit.appearance().syncStatus());
        assertEquals(2, tokenRequests.get());
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
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.GALLERY_OPEN)
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

        assertEquals(AppearanceSyncStatus.UNKNOWN, blockedIntent.appearance().syncStatus());
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
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.GALLERY_OPEN)
                .orElseThrow();

        assertEquals(AppearanceSyncStatus.OFFICIAL, reconciled.appearance().syncStatus());
        assertEquals(MutationResult.APPLIED, reconciled.outcome().orElseThrow().result());
        assertEquals(1, tokenRequests.get());
        assertEquals(1, api.profileGets.get());
        assertEquals(1, api.skinUploads.get());
    }

    @Test
    void freshProfileSupersedesProcessAcknowledgementBeforeComparingANewIntent()
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

        operations.usePreset(saved.presetId());
        ClientOperations.ReconciliationResult reapplied = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.LOCAL_INTENT)
                .orElseThrow();

        assertEquals(AppearanceSyncStatus.OFFICIAL, reapplied.appearance().syncStatus());
        assertEquals(MutationResult.APPLIED, reapplied.outcome().orElseThrow().result());
        assertEquals(2, api.profileGets.get());
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
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.GALLERY_OPEN)
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
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.GALLERY_OPEN)
                .orElseThrow();
        assertEquals(AppearanceSyncStatus.PENDING, failed.appearance().syncStatus());
        api.profileFailure = null;

        ClientOperations.ReconciliationResult reopen = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.GALLERY_OPEN)
                .orElseThrow();
        assertEquals(AppearanceSyncStatus.OFFICIAL, reopen.appearance().syncStatus());
        assertEquals(2, api.profileGets.get());
        assertEquals(1, api.skinUploads.get());

        ClientOperations.ReconciliationResult reconnect = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.RECONNECT)
                .orElseThrow();
        assertEquals(AppearanceSyncStatus.OFFICIAL, reconnect.appearance().syncStatus());
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
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.GALLERY_OPEN)
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
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.GALLERY_OPEN)
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
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.GALLERY_OPEN)
                .orElseThrow();
        assertEquals(AppearanceSyncStatus.PENDING, limited.appearance().syncStatus());
        assertEquals(ApiFailureKind.RATE_LIMITED, limited.outcome().orElseThrow().failureKind());
        assertEquals(1, api.profileGets.get());
        assertEquals(1, api.skinUploads.get());

        api.skinFailure = null;
        api.rateLimitRemaining = Optional.of(Duration.ofSeconds(60));
        ClientOperations.ReconciliationResult duringCooldown = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.RECONNECT)
                .orElseThrow();
        assertEquals(AppearanceSyncStatus.PENDING, duringCooldown.appearance().syncStatus());
        assertEquals(1, api.profileGets.get());
        assertEquals(1, api.skinUploads.get());

        api.rateLimitRemaining = Optional.empty();
        ClientOperations.ReconciliationResult afterCooldown = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.RECONNECT)
                .orElseThrow();
        assertEquals(AppearanceSyncStatus.OFFICIAL, afterCooldown.appearance().syncStatus());
        assertEquals(2, api.profileGets.get());
        assertEquals(2, api.skinUploads.get());
    }

    @Test
    void partialIntentWaitsForSessionRefreshedCapeOnlyRecoveryAndKeepsItsRevision() throws Exception {
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
                ApiFailureKind.FORBIDDEN, "cape denied", 403, null, false);
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

        ClientOperations.ReconciliationResult automatic = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.RECONNECT)
                .orElseThrow();
        assertEquals(AppearanceSyncStatus.PARTIAL, automatic.appearance().syncStatus());
        assertEquals(selected.intentRevision(), automatic.appearance().intentRevision());
        assertTrue(automatic.outcome().isEmpty());
        assertEquals(1, api.profileGets.get());
        assertEquals(1, api.skinUploads.get());
        assertEquals(1, api.capeActivations.get());

        api.capeFailure = null;
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

        ClientOperations.InitialData refreshed = operations.retrySession();
        assertTrue(refreshed.session().valid());
        assertEquals(2, api.profileGets.get());
        ClientOperations.ReconciliationResult recovered = operations
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.SESSION_REFRESHED)
                .orElseThrow();

        assertEquals(AppearanceSyncStatus.OFFICIAL, recovered.appearance().syncStatus());
        assertEquals(selected.intentRevision(), recovered.appearance().intentRevision());
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
        ClientOperations.EditorSave saved = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.empty(),
                "Keep stale cape in preset",
                SkinReference.asset(initial.account().skinAssets().get(0).id()),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.of("no-longer-owned"),
                Optional.empty()));
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
        assertEquals(1, api.profileGets.get());
        assertEquals(1, api.skinUploads.get());
        assertEquals(0, api.capeActivations.get());
        assertEquals(0, api.capeDeactivations.get());
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
                    .reconcileAppearance(ClientOperations.ReconciliationTrigger.GALLERY_OPEN)
                    .orElseThrow());
            Future<ClientOperations.ReconciliationResult> second = pool.submit(() -> contender
                    .reconcileAppearance(ClientOperations.ReconciliationTrigger.GALLERY_OPEN)
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
                .reconcileAppearance(ClientOperations.ReconciliationTrigger.GALLERY_OPEN)
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
                ClientOperations.ReconciliationTrigger.GALLERY_OPEN);
        Optional<ClientOperations.ReconciliationResult> stale = accountBOperations.reconcileAppearance(
                new ClientOperations.ReconciliationKey(
                        accountB, accountBIntent.intentRevision() - 1),
                ClientOperations.ReconciliationTrigger.GALLERY_OPEN);

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
        ClientOperations.EditorSave edited = operations.saveEditor(new ClientOperations.EditorSaveRequest(
                Optional.of(saved.presetId()),
                "Cached-profile cape edited",
                SkinReference.asset(skinId),
                SkinVariant.CLASSIC,
                SkinVariant.CLASSIC,
                Optional.of("cape-owned"),
                Optional.empty()));
        AppliedAppearance cachedCape = edited.reappliedAppearance()
                .orElseThrow()
                .localAppearance()
                .orElseThrow();
        var resolvedCached = resolver.resolve(expectedAppearance(cachedCape)).join().orElseThrow();

        assertTrue(cachedCape.capeTexture().isEmpty());
        assertEquals(
                Optional.of(com.naocraftlab.skins.core.storage.TextureCache.cacheKey(capeUri)),
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
                new AppearanceRefreshCoordinator<>(directClient, resolver, sink);

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
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
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
        private ProfileApiException profileFailure;
        private ProfileApiException skinFailure;
        private ProfileApiException capeFailure;
        private Optional<Duration> rateLimitRemaining = Optional.empty();

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
