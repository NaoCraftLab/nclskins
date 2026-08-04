package com.naocraftlab.skins.core.service;

import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.core.model.AccountAppearanceState;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.model.CatalogOrigin;
import com.naocraftlab.skins.core.model.PersonalSkinSource;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.model.SkinSource;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import com.naocraftlab.skins.core.test.TestPng;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-29T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void supportsCompleteSkinAndPresetCrud() throws Exception {
        LibraryService library = library();
        UUID accountId = UUID.randomUUID();
        ImportedSkin imported = library.importSkin(
                accountId,
                "Original",
                SkinVariant.CLASSIC,
                SkinSource.IMPORTED,
                TestPng.create(64, 64));
        UUID skinId = imported.asset().id();

        AccountState renamed = library.renameSkin(accountId, skinId, "Renamed");
        assertEquals("Renamed", library.findSkin(renamed, skinId).name());
        AccountState slim = library.changeSkinVariant(accountId, skinId, SkinVariant.SLIM);
        assertEquals(SkinVariant.SLIM, library.findSkin(slim, skinId).variant());
        AccountState duplicated = library.duplicateSkin(accountId, skinId, "Copy");
        assertEquals(2, duplicated.skinAssets().size());
        assertEquals(duplicated.skinAssets().get(0).sha256(), duplicated.skinAssets().get(1).sha256());

        AccountState withPreset = library.createPreset(
                accountId,
                "Look",
                SkinReference.asset(skinId),
                "cape-id");
        UUID presetId = withPreset.presets().get(0).id();
        LibraryOperationException inUse = assertThrows(
                LibraryOperationException.class,
                () -> library.deleteSkin(accountId, skinId));
        assertEquals(LibraryOperationException.Code.SKIN_IN_USE, inUse.code());

        AccountState updated = library.updatePreset(
                accountId,
                presetId,
                "Default",
                SkinReference.accountDefault(),
                null);
        assertTrue(library.findPreset(updated, presetId).skin().optionalAssetId().isEmpty());
        LibraryService.PresetDeletion deletion = library.deletePreset(
                accountId,
                presetId,
                (account, appearance, revision) -> pendingDefault(accountId, revision));
        assertTrue(deletion.appearanceUpdated());
        AccountState withoutPreset = deletion.state();
        assertTrue(withoutPreset.presets().isEmpty());
        AccountState withoutSkin = library.deleteSkin(accountId, skinId);
        assertEquals(1, withoutSkin.skinAssets().size());
        assertTrue(library.resetLibrary(accountId).skinAssets().isEmpty());
    }

    @Test
    void accountRevisionAdvancesAcrossSameClockEmptyCreateDeleteAba() throws Exception {
        LibraryService library = library();
        UUID accountId = UUID.randomUUID();
        AccountState initial = library.load(accountId);

        AccountState created = library.createPreset(
                accountId, "Transient", SkinReference.accountDefault(), null);
        UUID presetId = created.presets().get(0).id();
        LibraryService.PresetDeletion deletion = library.deletePreset(
                accountId,
                presetId,
                (account, appearance, revision) -> pendingDefault(accountId, revision));
        assertTrue(deletion.appearanceUpdated());
        AccountState emptyAgain = deletion.state();

        assertTrue(created.updatedAt().isAfter(initial.updatedAt()));
        assertTrue(emptyAgain.updatedAt().isAfter(created.updatedAt()));
        assertTrue(emptyAgain.presets().isEmpty());
    }

    @Test
    void managedDefaultsAreIdempotentAcrossConcurrentClients() throws Exception {
        UUID accountId = UUID.randomUUID();
        byte[] steve = TestPng.create(64, 64);
        CountDownLatch start = new CountDownLatch(1);
        Callable<ImportedSkin> importDefault = () -> {
            start.await();
            return library().importSkin(
                    accountId, "Steve", SkinVariant.CLASSIC, SkinSource.VANILLA_DEFAULT, steve);
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var results = List.of(executor.submit(importDefault), executor.submit(importDefault));
            start.countDown();
            ImportedSkin first = results.get(0).get();
            ImportedSkin second = results.get(1).get();

            assertEquals(first.asset().id(), second.asset().id());
            assertEquals(1, library().load(accountId).skinAssets().size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void initialPresetEmptyCheckAndCreateAreAtomicAcrossConcurrentClients() throws Exception {
        UUID accountId = UUID.randomUUID();
        UUID skinId = library().importSkin(
                        accountId,
                        "Current official",
                        SkinVariant.CLASSIC,
                        SkinSource.CURRENT_OFFICIAL,
                        TestPng.create(64, 64))
                .asset()
                .id();
        Instant expectedRevision = library().load(accountId).updatedAt();
        CountDownLatch start = new CountDownLatch(1);
        Callable<LibraryService.InitialPresetCreation> createInitial = () -> {
            start.await();
            return library().createInitialPresetIfEmpty(
                    accountId,
                    Thread.currentThread().getName(),
                    SkinReference.asset(skinId),
                    "active-cape",
                    expectedRevision);
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var results = List.of(executor.submit(createInitial), executor.submit(createInitial));
            start.countDown();
            LibraryService.InitialPresetCreation first = results.get(0).get();
            LibraryService.InitialPresetCreation second = results.get(1).get();

            AccountState stored = library().load(accountId);
            assertEquals(1, stored.presets().size());
            assertEquals(SkinReference.asset(skinId), stored.presets().get(0).skin());
            assertEquals("active-cape", stored.presets().get(0).capeId());
            assertEquals(1, List.of(first, second).stream()
                    .filter(LibraryService.InitialPresetCreation::created)
                    .count());
            assertEquals(1, List.of(first, second).stream()
                    .filter(result -> !result.revisionMatched())
                    .count());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentDeletesReachEmptyOnlyAfterPublishingAccountDefault() throws Exception {
        UUID accountId = UUID.randomUUID();
        UUID skinId = library().importSkin(
                        accountId,
                        "Skin",
                        SkinVariant.CLASSIC,
                        SkinSource.IMPORTED,
                        TestPng.create(64, 64))
                .asset()
                .id();
        AccountState first = library().createPreset(
                accountId, "First", SkinReference.asset(skinId), null);
        UUID firstId = first.presets().get(0).id();
        AccountState second = library().createPreset(
                accountId, "Second", SkinReference.asset(skinId), null);
        UUID secondId = second.presets().stream()
                .map(preset -> preset.id())
                .filter(id -> !id.equals(firstId))
                .findFirst()
                .orElseThrow();
        CountDownLatch start = new CountDownLatch(1);
        Callable<LibraryService.PresetDeletion> deleteFirst = () -> {
            start.await();
            return library().deletePreset(
                    accountId,
                    firstId,
                    (account, appearance, revision) -> pendingDefault(accountId, revision));
        };
        Callable<LibraryService.PresetDeletion> deleteSecond = () -> {
            start.await();
            return library().deletePreset(
                    accountId,
                    secondId,
                    (account, appearance, revision) -> pendingDefault(accountId, revision));
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var firstFuture = executor.submit(deleteFirst);
            var secondFuture = executor.submit(deleteSecond);
            start.countDown();
            LibraryService.PresetDeletion firstResult = firstFuture.get();
            LibraryService.PresetDeletion secondResult = secondFuture.get();

            assertEquals(1, List.of(firstResult, secondResult).stream()
                    .filter(LibraryService.PresetDeletion::appearanceUpdated)
                    .count());
            assertTrue(library().load(accountId).presets().isEmpty());
            AccountAppearanceState appearance = storage().loadAppearance(accountId);
            assertEquals(AppearanceSyncStatus.PENDING, appearance.syncStatus());
            assertTrue(appearance.activePresetId() == null);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void importedHdSkinResolvesAsApiCompatibleAsset() throws Exception {
        LibraryService library = library();
        UUID accountId = UUID.randomUUID();

        ImportedSkin imported = library.importSkin(
                accountId,
                "HD",
                SkinVariant.SLIM,
                SkinSource.IMPORTED,
                TestPng.create(256, 256));
        ResolvedSkinAsset resolved = library.resolveSkin(imported.state(), imported.asset().id());
        var info = new PngValidator().validate(resolved.pngBytes());

        assertEquals(64, info.width());
        assertEquals(64, info.height());
        assertEquals(imported.storedAsset().sha256(), resolved.sha256());
        assertEquals(SkinVariant.SLIM, resolved.variant());
    }

    @Test
    void personalSkinSaveDeduplicatesByNormalizedHashAndUnionsVariants() throws Exception {
        LibraryService library = library();
        UUID accountId = UUID.randomUUID();
        byte[] png = TestPng.create(64, 64);

        SavedPersonalSkinPreset first = library.createPresetFromPersonalSkin(
                accountId,
                "First preset",
                "First file",
                SkinVariant.CLASSIC,
                PersonalSkinSource.FILE,
                png,
                null);
        SavedPersonalSkinPreset second = library.createPresetFromPersonalSkin(
                accountId,
                "Second preset",
                "Ignored repeated name",
                SkinVariant.SLIM,
                PersonalSkinSource.URL,
                png,
                "cape");
        SavedPersonalSkinPreset repeated = library.createPresetFromPersonalSkin(
                accountId,
                "Third preset",
                "Also ignored",
                SkinVariant.CLASSIC,
                PersonalSkinSource.PLAYER_NAME,
                png,
                null);

        AccountState stored = library.load(accountId);
        assertEquals(1, stored.personalSkins().size());
        assertEquals(2, stored.skinAssets().size());
        assertEquals(3, stored.presets().size());
        var personal = stored.personalSkins().get(0);
        assertEquals("First file", personal.displayName());
        assertEquals(PersonalSkinSource.FILE, personal.source());
        assertEquals(first.asset().id(), personal.optionalAssetId(SkinVariant.CLASSIC).orElseThrow());
        assertEquals(second.asset().id(), personal.optionalAssetId(SkinVariant.SLIM).orElseThrow());
        assertEquals(first.asset().id(), repeated.asset().id());
        assertTrue(personal.updatedAt().isAfter(personal.addedAt()));
        assertEquals(stored, repeated.state());
    }

    @Test
    void hidingPersonalSkinKeepsAssetsAndPresetsAndExplicitImportRestoresIt() throws Exception {
        LibraryService library = library();
        UUID accountId = UUID.randomUUID();
        byte[] png = TestPng.create(64, 64);
        SavedPersonalSkinPreset saved = library.createPresetFromPersonalSkin(
                accountId,
                "Preset",
                "Original file",
                SkinVariant.CLASSIC,
                PersonalSkinSource.FILE,
                png,
                null);

        AccountState hidden = library.hidePersonalSkin(accountId, saved.personalSkin().sha256());

        assertFalse(hidden.personalSkins().get(0).visible());
        assertEquals(saved.state().skinAssets(), hidden.skinAssets());
        assertEquals(saved.state().presets(), hidden.presets());
        assertTrue(library.findVisiblePersonalSkinAsset(
                        hidden, saved.personalSkin().sha256(), SkinVariant.CLASSIC)
                .isEmpty());

        SavedPersonalSkinPreset restored = library.createPresetFromPersonalSkin(
                accountId,
                "Restored preset",
                "Restored URL skin",
                SkinVariant.CLASSIC,
                PersonalSkinSource.URL,
                png,
                null);

        assertTrue(restored.personalSkin().visible());
        assertEquals("Restored URL skin", restored.personalSkin().displayName());
        assertEquals(PersonalSkinSource.URL, restored.personalSkin().source());
        assertTrue(restored.personalSkin().addedAt().isAfter(
                hidden.personalSkins().get(0).updatedAt()));
        assertEquals(saved.asset().id(), restored.asset().id());
        assertEquals(1, restored.state().skinAssets().size());
        assertEquals(2, restored.state().presets().size());
        assertEquals(
                saved.asset().id(),
                library.findVisiblePersonalSkinAsset(
                                accountId,
                                saved.personalSkin().sha256(),
                                SkinVariant.CLASSIC)
                        .orElseThrow()
                        .id());
    }

    @Test
    void renamingPersonalSkinChangesOnlyItsDisplayName() throws Exception {
        LibraryService library = library();
        UUID accountId = UUID.randomUUID();
        SavedPersonalSkinPreset saved = library.createPresetFromPersonalSkin(
                accountId,
                "Preset",
                "Original file",
                SkinVariant.CLASSIC,
                PersonalSkinSource.FILE,
                TestPng.create(64, 64),
                null);

        AccountState renamed = library.renamePersonalSkin(
                accountId, saved.personalSkin().sha256(), "My renamed skin");

        assertEquals("My renamed skin", renamed.personalSkins().get(0).displayName());
        assertEquals(saved.state().skinAssets(), renamed.skinAssets());
        assertEquals(saved.state().presets(), renamed.presets());
        assertEquals(saved.personalSkin().sha256(), renamed.personalSkins().get(0).sha256());
        assertEquals("My renamed skin", library.load(accountId).personalSkins().get(0).displayName());
    }

    @Test
    void updateFromFilePublishesPresetAndPersonalEntryInOneAccountTransaction() throws Exception {
        LibraryService library = library();
        UUID accountId = UUID.randomUUID();
        AccountState initial = library.createPreset(
                accountId, "Initial", SkinReference.accountDefault(), null);
        UUID presetId = initial.presets().get(0).id();

        SavedPersonalSkinPreset updated = library.updatePresetFromPersonalSkin(
                accountId,
                presetId,
                "Updated",
                "new-file",
                SkinVariant.SLIM,
                PersonalSkinSource.FILE,
                TestPng.create(128, 128),
                "cape");

        assertEquals(presetId, updated.preset().id());
        assertEquals("Updated", updated.preset().name());
        assertEquals(SkinReference.asset(updated.asset().id()), updated.preset().skin());
        assertEquals("cape", updated.preset().capeId());
        assertEquals(1, updated.state().personalSkins().size());
        assertEquals(updated.state(), library.load(accountId));
    }

    @Test
    void failedPresetUpdateDoesNotPublishPersonalMetadata() throws Exception {
        LibraryService library = library();
        UUID accountId = UUID.randomUUID();
        AccountState before = library.load(accountId);

        LibraryOperationException failure = assertThrows(
                LibraryOperationException.class,
                () -> library.updatePresetFromPersonalSkin(
                        accountId,
                        UUID.randomUUID(),
                        "Missing",
                        "orphan-file",
                        SkinVariant.CLASSIC,
                        PersonalSkinSource.FILE,
                        TestPng.create(64, 64),
                        null));

        assertEquals(LibraryOperationException.Code.PRESET_NOT_FOUND, failure.code());
        assertEquals(before, library.load(accountId));
    }

    @Test
    void catalogPresetCreateAtomicallyPublishesOriginAssetAndPreservesPersonalSkins()
            throws Exception {
        LibraryService library = library();
        UUID accountId = UUID.randomUUID();
        SavedPersonalSkinPreset personal = library.createPresetFromPersonalSkin(
                accountId,
                "Personal preset",
                "personal-file",
                SkinVariant.CLASSIC,
                PersonalSkinSource.FILE,
                TestPng.create(64, 64),
                null);
        CatalogOrigin origin = new CatalogOrigin("pack", "heroes", "catalog-alex");

        SavedImportedPreset saved = library.savePresetWithImportedSkin(
                accountId,
                Optional.empty(),
                "Catalog preset",
                "Catalog Alex",
                SkinVariant.SLIM,
                SkinSource.IMPORTED,
                TestPng.create(128, 128),
                origin,
                "catalog-cape");

        assertEquals(origin, saved.asset().catalogOrigin().orElseThrow());
        assertEquals(SkinReference.asset(saved.asset().id()), saved.preset().skin());
        assertEquals("catalog-cape", saved.preset().capeId());
        assertEquals(2, saved.state().skinAssets().size());
        assertEquals(2, saved.state().presets().size());
        assertEquals(personal.state().personalSkins(), saved.state().personalSkins());
        assertEquals(saved.state(), library.load(accountId));
    }

    @Test
    void catalogPresetUpdateAtomicallyReplacesPresetAssetAndPreservesPersonalSkins()
            throws Exception {
        LibraryService library = library();
        UUID accountId = UUID.randomUUID();
        SavedPersonalSkinPreset personal = library.createPresetFromPersonalSkin(
                accountId,
                "Original preset",
                "persistent-personal-skin",
                SkinVariant.CLASSIC,
                PersonalSkinSource.FILE,
                TestPng.create(64, 64),
                null);
        CatalogOrigin origin = new CatalogOrigin("pack", "villains", "catalog-slim");

        SavedImportedPreset updated = library.savePresetWithImportedSkin(
                accountId,
                Optional.of(personal.preset().id()),
                "Catalog replacement",
                "Catalog Slim",
                SkinVariant.SLIM,
                SkinSource.IMPORTED,
                TestPng.create(128, 128),
                origin,
                "replacement-cape");

        assertEquals(personal.preset().id(), updated.preset().id());
        assertEquals(personal.preset().createdAt(), updated.preset().createdAt());
        assertEquals("Catalog replacement", updated.preset().name());
        assertEquals(SkinReference.asset(updated.asset().id()), updated.preset().skin());
        assertEquals(origin, updated.asset().catalogOrigin().orElseThrow());
        assertEquals(personal.state().personalSkins(), updated.state().personalSkins());
        assertEquals(personal.asset(), library.findSkin(updated.state(), personal.asset().id()));
        assertEquals(2, updated.state().skinAssets().size());
        assertEquals(1, updated.state().presets().size());
        assertEquals(updated.state(), library.load(accountId));
    }

    @Test
    void failedCatalogPresetUpdatePublishesNoAssetOrAccountMetadata() throws Exception {
        LibraryService library = library();
        UUID accountId = UUID.randomUUID();
        AccountState before = library.createPresetFromPersonalSkin(
                        accountId,
                        "Personal preset",
                        "personal-file",
                        SkinVariant.CLASSIC,
                        PersonalSkinSource.FILE,
                        TestPng.create(64, 64),
                        null)
                .state();

        LibraryOperationException failure = assertThrows(
                LibraryOperationException.class,
                () -> library.savePresetWithImportedSkin(
                        accountId,
                        Optional.of(UUID.randomUUID()),
                        "Missing preset",
                        "Orphaned catalog bytes",
                        SkinVariant.SLIM,
                        SkinSource.IMPORTED,
                        TestPng.create(128, 128),
                        new CatalogOrigin("pack", "collection", "missing"),
                        null));

        assertEquals(LibraryOperationException.Code.PRESET_NOT_FOUND, failure.code());
        AccountState after = library.load(accountId);
        assertEquals(before, after);
        assertEquals(before.skinAssets(), after.skinAssets());
        assertEquals(before.personalSkins(), after.personalSkins());
        assertEquals(before.presets(), after.presets());
    }

    private LibraryService library() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new LibraryService(storage(), clock);
    }

    private NclSkinsStorage storage() {
        return new NclSkinsStorage(
                temporaryDirectory.resolve("nclskins"),
                new PngValidator(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static AccountAppearanceState pendingDefault(UUID accountId, long revision) {
        return new AccountAppearanceState(
                AccountAppearanceState.CURRENT_SCHEMA_VERSION,
                accountId,
                revision,
                null,
                null,
                null,
                null,
                OuterLayerVisibility.allVisible(),
                AppearanceSyncStatus.PENDING,
                0,
                NOW);
    }
}
