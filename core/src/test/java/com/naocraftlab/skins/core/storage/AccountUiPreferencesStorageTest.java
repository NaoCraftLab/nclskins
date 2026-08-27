package com.naocraftlab.skins.core.storage;

import com.naocraftlab.skins.core.model.AccountUiPreferences;
import com.naocraftlab.skins.core.model.AddSourceTab;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.service.LibraryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountUiPreferencesStorageTest {
    private static final Instant NOW = Instant.parse("2026-07-31T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    @Test
    void defaultsToCatalogWithNoCollapsedCollectionsWithoutCreatingState() throws Exception {
        NclSkinsStorage storage = storage();
        UUID accountId = UUID.randomUUID();

        AccountUiPreferencesResult result = storage.loadUiPreferences(accountId);

        assertEquals(AddSourceTab.CATALOG, result.preferences().selectedAddSourceTab());
        assertTrue(result.preferences().preferredSkinVariant().isEmpty());
        assertTrue(result.preferences().collapsedCollectionIds().isEmpty());
        assertTrue(result.warnings().isEmpty());
        assertFalse(Files.exists(storage.layout().accountUiPreferences(accountId)));
    }

    @Test
    void persistsPreferencesAcrossInstancesAndPreservesUnknownCollectionIds() throws Exception {
        NclSkinsStorage first = storage();
        NclSkinsStorage second = storage();
        UUID accountId = UUID.randomUUID();

        first.setCollectionCollapsed(accountId, "future-pack:unknown-collection", true);
        second.setSelectedAddSourceTab(accountId, AddSourceTab.CATALOG);

        AccountUiPreferences reopened = first.loadUiPreferences(accountId).preferences();
        assertEquals(AddSourceTab.CATALOG, reopened.selectedAddSourceTab());
        assertEquals(Set.of("future-pack:unknown-collection"), reopened.collapsedCollectionIds());
        assertTrue(Files.isRegularFile(first.layout().accountUiPreferences(accountId)));
    }

    @Test
    void concurrentFieldUpdatesMergeAfterReReadingUnderTheAccountLock() throws Exception {
        NclSkinsStorage first = storage();
        NclSkinsStorage second = storage();
        NclSkinsStorage third = storage();
        UUID accountId = UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(3);
        try {
            Future<?> tabUpdate = executor.submit(() -> {
                ready.countDown();
                start.await();
                first.setSelectedAddSourceTab(accountId, AddSourceTab.CATALOG);
                return null;
            });
            Future<?> collectionUpdate = executor.submit(() -> {
                ready.countDown();
                start.await();
                second.replaceCollapsedCollectionIds(
                        accountId, Set.of("minecraft:defaults"));
                return null;
            });
            Future<?> variantUpdate = executor.submit(() -> {
                ready.countDown();
                start.await();
                third.setPreferredSkinVariant(accountId, SkinVariant.SLIM);
                return null;
            });
            ready.await();
            start.countDown();
            tabUpdate.get();
            collectionUpdate.get();
            variantUpdate.get();
        } finally {
            executor.shutdownNow();
        }

        AccountUiPreferences merged = first.loadUiPreferences(accountId).preferences();
        assertEquals(AddSourceTab.CATALOG, merged.selectedAddSourceTab());
        assertEquals(SkinVariant.SLIM, merged.preferredSkinVariant().orElseThrow());
        assertEquals(Set.of("minecraft:defaults"), merged.collapsedCollectionIds());
    }

    @Test
    void schemaOneFileWithoutPreferredVariantStaysReadableUntilFirstExplicitChoice() throws Exception {
        NclSkinsStorage storage = storage();
        UUID accountId = UUID.randomUUID();
        storage.initialize();
        Path path = storage.layout().accountUiPreferences(accountId);
        Files.createDirectories(path.getParent());
        Files.writeString(path, """
                {
                  "schemaVersion": 1,
                  "accountId": "%s",
                  "selectedAddSourceTab": "CATALOG",
                  "collapsedCollectionIds": ["minecraft:defaults"]
                }
                """.formatted(accountId), StandardCharsets.UTF_8);

        AccountUiPreferences decoded = storage.loadUiPreferences(accountId).preferences();

        assertEquals(AddSourceTab.CATALOG, decoded.selectedAddSourceTab());
        assertTrue(decoded.preferredSkinVariant().isEmpty());
        storage.setPreferredSkinVariant(accountId, SkinVariant.CLASSIC);
        assertEquals(
                SkinVariant.CLASSIC,
                storage.loadUiPreferences(accountId).preferences().preferredSkinVariant().orElseThrow());
        assertTrue(Files.readString(path, StandardCharsets.UTF_8)
                .contains("\"preferredSkinVariant\": \"CLASSIC\""));
    }

    @Test
    void collectionUpdatesPreserveOtherIdsAndCanExpandOneCollection() throws Exception {
        NclSkinsStorage storage = storage();
        UUID accountId = UUID.randomUUID();

        storage.setCollectionCollapsed(accountId, "minecraft:defaults", true);
        storage.setCollectionCollapsed(accountId, "pack:other", true);
        storage.setCollectionCollapsed(accountId, "minecraft:defaults", false);

        assertEquals(
                Set.of("pack:other"),
                storage.loadUiPreferences(accountId).preferences().collapsedCollectionIds());
    }

    @Test
    void replacementUpdatesTheWholeCollapsedSetWithoutChangingOtherPreferences() throws Exception {
        NclSkinsStorage storage = storage();
        UUID accountId = UUID.randomUUID();
        storage.setPreferredSkinVariant(accountId, SkinVariant.SLIM);
        storage.setCollectionCollapsed(accountId, "stale:collection", true);

        AccountUiPreferencesResult replaced = storage.replaceCollapsedCollectionIds(
                accountId, Set.of("pack:first", "pack:second"));

        assertEquals(Set.of("pack:first", "pack:second"),
                replaced.preferences().collapsedCollectionIds());
        assertEquals(Optional.of(SkinVariant.SLIM),
                replaced.preferences().preferredSkinVariant());
        assertEquals(replaced.preferences(), storage.loadUiPreferences(accountId).preferences());
    }

    @Test
    void invalidReplacementDoesNotOverwriteTheExistingPreferencesFile() throws Exception {
        NclSkinsStorage storage = storage();
        UUID accountId = UUID.randomUUID();
        storage.replaceCollapsedCollectionIds(accountId, Set.of("pack:stable"));

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> storage.replaceCollapsedCollectionIds(accountId, Set.of("")));

        assertEquals(Set.of("pack:stable"),
                storage.loadUiPreferences(accountId).preferences().collapsedCollectionIds());
    }

    @Test
    void malformedUiFileFallsBackWithWarningAndDoesNotBlockLibraryState() throws Exception {
        NclSkinsStorage storage = storage();
        UUID accountId = UUID.randomUUID();
        storage.initialize();
        Path path = storage.layout().accountUiPreferences(accountId);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{broken", StandardCharsets.UTF_8);

        AccountUiPreferencesResult recovered = storage.loadUiPreferences(accountId);
        LibraryService library = new LibraryService(storage, CLOCK);
        library.createPreset(accountId, "Still works", SkinReference.accountDefault(), null);

        assertEquals(AccountUiPreferences.defaults(accountId), recovered.preferences());
        assertEquals(1, recovered.warnings().size());
        assertEquals(1, library.load(accountId).presets().size());

        AccountUiPreferencesResult replacement =
                storage.setSelectedAddSourceTab(accountId, AddSourceTab.CATALOG);
        assertEquals(1, replacement.warnings().size());
        assertEquals(AddSourceTab.CATALOG, replacement.preferences().selectedAddSourceTab());
        assertTrue(storage.loadUiPreferences(accountId).warnings().isEmpty());
    }

    @Test
    void accountIdMismatchIsNonFatalAndUsesRequestedAccountDefaults() throws Exception {
        NclSkinsStorage storage = storage();
        UUID requestedAccountId = UUID.randomUUID();
        UUID foreignAccountId = UUID.randomUUID();
        storage.initialize();
        Path path = storage.layout().accountUiPreferences(requestedAccountId);
        Files.createDirectories(path.getParent());
        Files.writeString(path, """
                {
                  "schemaVersion": 1,
                  "accountId": "%s",
                  "selectedAddSourceTab": "CATALOG",
                  "collapsedCollectionIds": ["minecraft:defaults"]
                }
                """.formatted(foreignAccountId), StandardCharsets.UTF_8);

        AccountUiPreferencesResult recovered = storage.loadUiPreferences(requestedAccountId);

        assertEquals(AccountUiPreferences.defaults(requestedAccountId), recovered.preferences());
        assertEquals(1, recovered.warnings().size());
    }

    private NclSkinsStorage storage() {
        return new NclSkinsStorage(
                temporaryDirectory.resolve("nclskins"),
                new PngValidator(),
                CLOCK);
    }
}
