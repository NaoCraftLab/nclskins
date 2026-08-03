package com.naocraftlab.skins.core.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AccountAppearanceState;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.model.PersonalSkinSource;
import com.naocraftlab.skins.core.model.OwnedCapeEntry;
import com.naocraftlab.skins.core.model.OwnedCapeInventory;
import com.naocraftlab.skins.core.model.RemoteAssetState;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.core.model.SkinAsset;
import com.naocraftlab.skins.core.model.SkinSource;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.service.LibraryService;
import com.naocraftlab.skins.core.test.TestPng;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NclSkinsStorageTest {
    private static final Instant NOW = Instant.parse("2026-07-29T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    @Test
    void initializesExactLayoutAndPreflightsItIdempotently() throws Exception {
        NclSkinsStorage storage = storage();

        StorageInitialization first = storage.initialize();
        StorageInitialization second = storage.initialize();

        assertEquals(temporaryDirectory.resolve("nclskins").toAbsolutePath(), first.root());
        assertTrue(first.warnings().isEmpty());
        assertTrue(Files.isDirectory(storage.layout().accounts()));
        assertTrue(Files.isDirectory(storage.layout().assetsSha256()));
        assertTrue(Files.isDirectory(storage.layout().textureCache()));
        assertTrue(Files.isDirectory(storage.layout().locks()));
        assertTrue(Files.isDirectory(storage.layout().backups()));
        assertTrue(Files.isRegularFile(storage.layout().locks().resolve("storage-preflight.lock")));
        try (var entries = Files.list(storage.layout().root())) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().startsWith(".storage-preflight-")));
        }
        assertTrue(second.warnings().isEmpty());
    }

    @Test
    void resolvesPlatformSpecificDefaultRoots() {
        assertEquals(
                Path.of("/windows/AppData/Roaming/NaoCraftLab/Skins"),
                NclSkinsStorage.defaultRoot(
                        "Windows 11", "/windows/Users/Player", "/windows/AppData/Roaming", null));
        assertEquals(
                Path.of("/windows/Users/Player/AppData/Roaming/NaoCraftLab/Skins"),
                NclSkinsStorage.defaultRoot(
                        "Windows 11", "/windows/Users/Player", " ", "/ignored/xdg"));
        assertEquals(
                Path.of("/windows/Users/Player/AppData/Roaming/NaoCraftLab/Skins"),
                NclSkinsStorage.defaultRoot(
                        "Windows 11", "/windows/Users/Player", null, "/ignored/xdg"));
        assertEquals(
                Path.of("/windows/Users/Player/AppData/Roaming/NaoCraftLab/Skins"),
                NclSkinsStorage.defaultRoot(
                        "Windows 11", "/windows/Users/Player", "relative/appdata", null));
        assertEquals(
                Path.of("/Users/player/Library/Application Support/NaoCraftLab/Skins"),
                NclSkinsStorage.defaultRoot("Mac OS X", "/Users/player", null, "/ignored/xdg"));
        assertEquals(
                Path.of("/Users/player/Library/Application Support/NaoCraftLab/Skins"),
                NclSkinsStorage.defaultRoot("Darwin", "/Users/player", null, null));
        assertEquals(
                Path.of("/xdg/data/NaoCraftLab/Skins"),
                NclSkinsStorage.defaultRoot("Linux", "/home/player", null, "/xdg/data"));
        assertEquals(
                Path.of("/home/player/.local/share/NaoCraftLab/Skins"),
                NclSkinsStorage.defaultRoot("Linux", "/home/player", null, null));
        assertEquals(
                Path.of("/home/player/.local/share/NaoCraftLab/Skins"),
                NclSkinsStorage.defaultRoot("Linux", "/home/player", null, " "));
        assertEquals(
                Path.of("/home/player/.local/share/NaoCraftLab/Skins"),
                NclSkinsStorage.defaultRoot("Linux", "/home/player", null, "relative/xdg"));
        assertEquals(
                Path.of("/bsd/data/NaoCraftLab/Skins"),
                NclSkinsStorage.defaultRoot("FreeBSD", null, null, "/bsd/data"));
    }

    @Test
    void requiresAnAbsolutePlatformDirectoryOrAbsoluteUserHome() {
        IllegalStateException macFailure = assertThrows(
                IllegalStateException.class,
                () -> NclSkinsStorage.defaultRoot("Mac OS X", null, null, null));
        assertEquals(
                "NCL Skins (nclskins) cannot resolve a per-user directory for its state because "
                        + "neither the platform data directory nor user.home is available.",
                macFailure.getMessage());

        assertThrows(
                IllegalStateException.class,
                () -> NclSkinsStorage.defaultRoot("Linux", "relative/home", null, null));
        assertThrows(
                IllegalStateException.class,
                () -> NclSkinsStorage.defaultRoot("Windows 11", null, null, null));
        assertEquals(
                Path.of("/windows/AppData/Roaming/NaoCraftLab/Skins"),
                NclSkinsStorage.defaultRoot("Windows 11", null, "/windows/AppData/Roaming", null));
    }

    @Test
    void ignoresLegacyRootsAndStartsWithTheNewLayout() throws Exception {
        Path home = temporaryDirectory.resolve("home").toAbsolutePath();
        Path legacy = home.resolve(".ncl-skin");
        Files.createDirectories(legacy);
        Path marker = legacy.resolve("legacy-state.json");
        Files.writeString(marker, "legacy remains untouched", StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(marker);

        Path root = NclSkinsStorage.defaultRoot("Mac OS X", home.toString(), null, null);
        NclSkinsStorage storage = new NclSkinsStorage(root, new PngValidator(), CLOCK);
        storage.initialize();

        assertArrayEquals(before, Files.readAllBytes(marker));
        assertFalse(Files.exists(root.resolve(marker.getFileName())));
        assertTrue(Files.isDirectory(root.resolve("accounts")));
    }

    @Test
    void ignoresTheLegacyWindowsAppDataRoot() throws Exception {
        Path appData = temporaryDirectory.resolve("appdata").toAbsolutePath();
        Path legacy = appData.resolve("NCL Skin");
        Files.createDirectories(legacy);
        Path marker = legacy.resolve("legacy-state.json");
        Files.writeString(marker, "legacy remains untouched", StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(marker);

        Path root = NclSkinsStorage.defaultRoot("Windows 11", null, appData.toString(), null);
        new NclSkinsStorage(root, new PngValidator(), CLOCK).initialize();

        assertArrayEquals(before, Files.readAllBytes(marker));
        assertFalse(Files.exists(root.resolve(marker.getFileName())));
        assertTrue(Files.isDirectory(root.resolve("accounts")));
    }

    @Test
    void doesNotFallbackWhenTheSelectedPlatformRootCannotBeUsed() throws Exception {
        Path appDataFile = temporaryDirectory.resolve("selected-appdata").toAbsolutePath();
        Files.writeString(appDataFile, "not a directory", StandardCharsets.UTF_8);
        Path home = temporaryDirectory.resolve("fallback-home").toAbsolutePath();
        Path selected = appDataFile.resolve("NaoCraftLab").resolve("Skins");
        NclSkinsStorage storage = new NclSkinsStorage(
                NclSkinsStorage.defaultRoot("Windows 11", home.toString(), appDataFile.toString(), null),
                new PngValidator(),
                CLOCK);

        StorageAccessException failure = assertThrows(StorageAccessException.class, storage::initialize);

        assertTrue(failure.getMessage().contains(selected.toString()));
        assertFalse(Files.exists(home.resolve("AppData").resolve("Roaming")));
    }

    @Test
    void preservesExistingPosixDirectoryPermissions() throws Exception {
        Path root = temporaryDirectory.resolve("existing-root");
        Files.createDirectory(root);
        if (!Files.getFileStore(root).supportsFileAttributeView("posix")) {
            return;
        }
        Set<PosixFilePermission> expected = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE);
        Files.setPosixFilePermissions(root, expected);

        new NclSkinsStorage(root, new PngValidator(), CLOCK).initialize();

        assertEquals(expected, Files.getPosixFilePermissions(root));
    }

    @Test
    void createsNewPosixStorageDirectoriesOwnerOnly() throws Exception {
        Path root = temporaryDirectory.resolve("private-root");
        NclSkinsStorage storage = new NclSkinsStorage(root, new PngValidator(), CLOCK);
        storage.initialize();
        if (!Files.getFileStore(root).supportsFileAttributeView("posix")) {
            return;
        }
        Set<PosixFilePermission> ownerOnly = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE);
        assertEquals(ownerOnly, Files.getPosixFilePermissions(root));
        assertEquals(ownerOnly, Files.getPosixFilePermissions(storage.layout().accounts()));
    }

    @Test
    void reportsTheExactFatalMessageWhenTheRootCannotBeCreated() throws Exception {
        Path root = temporaryDirectory.resolve("not-a-directory").toAbsolutePath();
        Files.writeString(root, "file collision", StandardCharsets.UTF_8);
        NclSkinsStorage storage = new NclSkinsStorage(root, new PngValidator(), CLOCK);

        StorageAccessException failure = assertThrows(StorageAccessException.class, storage::initialize);

        assertEquals(
                "NCL Skins (nclskins) requires read and write access to \""
                        + root
                        + "\" to store its state. Check the directory permissions and restart Minecraft.",
                failure.getMessage());
        assertTrue(failure.getCause() instanceof java.nio.file.FileAlreadyExistsException);
    }

    @Test
    void rejectsAFileSystemWithoutTheRequiredStorageSemantics() {
        Path root = temporaryDirectory.resolve("unsupported-semantics");
        AtomicMoveNotSupportedException unsupported = new AtomicMoveNotSupportedException(
                "probe.tmp", "probe.moved", "atomic move unavailable");
        NclSkinsStorage storage = new NclSkinsStorage(
                root,
                new PngValidator(),
                CLOCK,
                (layout, locks) -> {
                    throw unsupported;
                });

        StorageAccessException failure = assertThrows(StorageAccessException.class, storage::initialize);

        assertEquals(unsupported, failure.getCause());
        assertFalse(Files.exists(root.resolve("accounts").resolve("state-v1.json")));
    }

    @Test
    void reportsReadWriteDeleteAndLockPreflightFailuresWithoutFallback() throws Exception {
        assertInjectedPreflightFailure(
                "write-denied",
                new AccessDeniedException("preflight.tmp", null, "write denied"));
        assertInjectedPreflightFailure(
                "read-denied",
                new AccessDeniedException("preflight.tmp", null, "read denied"));
        assertInjectedPreflightFailure(
                "delete-denied",
                new AccessDeniedException("preflight.moved", null, "delete denied"));

        Path root = temporaryDirectory.resolve("lock-denied");
        Files.createDirectories(root.resolve("locks").resolve("storage-preflight.lock"));
        NclSkinsStorage storage = new NclSkinsStorage(root, new PngValidator(), CLOCK);

        StorageAccessException failure = assertThrows(StorageAccessException.class, storage::initialize);

        assertNotNull(failure.getCause());
        assertTrue(failure.getMessage().contains(root.toAbsolutePath().toString()));
    }

    @Test
    void storesAssetsByContentAndToleratesConcurrentAlreadyExists() throws Exception {
        NclSkinsStorage storage = storage();
        byte[] png = TestPng.create(64, 64);

        StoredAsset first = storage.storeAsset(png);
        StoredAsset second = storage.storeAsset(png);

        assertFalse(first.alreadyPresent());
        assertTrue(second.alreadyPresent());
        assertEquals(first.path(), second.path());
        assertArrayEquals(png, storage.readAsset(first.sha256()));
    }

    @Test
    void storesScaledSkinsOnlyAfterApiCompatibleNormalization() throws Exception {
        NclSkinsStorage storage = storage();
        byte[] hdPng = TestPng.create(128, 128);

        StoredAsset stored = storage.storeAsset(hdPng);
        byte[] persisted = storage.readAsset(stored.sha256());

        assertEquals(64, stored.pngInfo().width());
        assertEquals(64, stored.pngInfo().height());
        assertFalse(java.util.Arrays.equals(hdPng, persisted));
    }

    @Test
    void rejectsAnAssetThatGrowsBeyondTheReadLimit() throws Exception {
        NclSkinsStorage storage = storage();
        StoredAsset stored = storage.storeAsset(TestPng.create(64, 64));
        Files.write(stored.path(), new byte[PngValidator.DEFAULT_MAX_BYTES + 1]);

        StorageException exception = assertThrows(
                StorageException.class, () -> storage.readAsset(stored.sha256()));

        assertEquals(StorageException.Code.ASSET_INTEGRITY_FAILURE, exception.code());
    }

    @Test
    void restoresLastValidBackupAfterInterruptedOrCorruptReplacement() throws Exception {
        NclSkinsStorage storage = storage();
        UUID accountId = UUID.randomUUID();
        AccountState first = AccountState.empty(accountId, NOW);
        storage.updateAccount(accountId, ignored -> first);
        SkinAsset asset = new SkinAsset(
                UUID.randomUUID(),
                "Second",
                "a".repeat(64),
                SkinVariant.CLASSIC,
                SkinSource.IMPORTED,
                NOW,
                NOW);
        AccountState second = new AccountState(
                AccountState.CURRENT_SCHEMA_VERSION,
                accountId,
                List.of(asset),
                List.of(),
                NOW.plusSeconds(1));
        storage.updateAccount(accountId, ignored -> second);
        Files.writeString(storage.layout().accountState(accountId), "{broken", StandardCharsets.UTF_8);

        AccountState recovered = storage.loadOrCreateAccount(accountId);

        assertEquals(first, recovered);
        assertEquals(first, storage.loadOrCreateAccount(accountId));
    }

    @Test
    void migratesLegacyVersionAndSkinsMemberToCanonicalV4() throws Exception {
        NclSkinsStorage storage = storage();
        storage.initialize();
        UUID accountId = UUID.randomUUID();
        Path statePath = storage.layout().accountState(accountId);
        Files.createDirectories(statePath.getParent());
        String legacy = """
                {
                  "version": 1,
                  "accountId": "%s",
                  "updatedAt": "%s",
                  "skins": [],
                  "presets": []
                }
                """.formatted(accountId, NOW);
        Files.writeString(statePath, legacy, StandardCharsets.UTF_8);

        AccountState loaded = storage.loadOrCreateAccount(accountId);
        String rewritten = Files.readString(statePath, StandardCharsets.UTF_8);

        assertEquals(accountId, loaded.accountId());
        assertEquals(AccountState.CURRENT_SCHEMA_VERSION, loaded.schemaVersion());
        assertTrue(rewritten.contains("\"schemaVersion\": 4"));
        assertTrue(rewritten.contains("\"skinAssets\""));
        assertTrue(rewritten.contains("\"personalSkins\""));
        assertFalse(rewritten.contains("\"version\""));
    }

    @Test
    void migratesOnlyOriginFreeImportedAssetsAndUnionsVariantsDeterministically() throws Exception {
        NclSkinsStorage storage = storage();
        storage.initialize();
        UUID accountId = UUID.randomUUID();
        UUID classicId = new UUID(0L, 2L);
        UUID slimId = new UUID(0L, 1L);
        UUID managedId = new UUID(0L, 3L);
        UUID catalogId = new UUID(0L, 4L);
        String personalHash = "a".repeat(64);
        String legacy = """
                {
                  "schemaVersion": 1,
                  "accountId": "%s",
                  "updatedAt": "%s",
                  "skinAssets": [
                    {
                      "id": "%s",
                      "name": "Later name",
                      "sha256": "%s",
                      "variant": "CLASSIC",
                      "source": "IMPORTED",
                      "createdAt": "%s",
                      "updatedAt": "%s"
                    },
                    {
                      "id": "%s",
                      "name": "Earliest name",
                      "sha256": "%s",
                      "variant": "SLIM",
                      "source": "DUPLICATED",
                      "createdAt": "%s",
                      "updatedAt": "%s"
                    },
                    {
                      "id": "%s",
                      "name": "Managed",
                      "sha256": "%s",
                      "variant": "CLASSIC",
                      "source": "VANILLA_DEFAULT",
                      "createdAt": "%s",
                      "updatedAt": "%s"
                    },
                    {
                      "id": "%s",
                      "name": "Catalog",
                      "sha256": "%s",
                      "variant": "CLASSIC",
                      "source": "IMPORTED",
                      "catalogOrigin": {
                        "sourceId": "pack",
                        "collectionId": "collection",
                        "skinId": "catalog"
                      },
                      "createdAt": "%s",
                      "updatedAt": "%s"
                    }
                  ],
                  "presets": []
                }
                """.formatted(
                accountId,
                NOW.plusSeconds(20),
                classicId,
                personalHash,
                NOW.plusSeconds(2),
                NOW.plusSeconds(10),
                slimId,
                personalHash,
                NOW,
                NOW.plusSeconds(5),
                managedId,
                "b".repeat(64),
                NOW,
                NOW,
                catalogId,
                "c".repeat(64),
                NOW,
                NOW);
        Path statePath = storage.layout().accountState(accountId);
        Files.createDirectories(statePath.getParent());
        Files.writeString(statePath, legacy, StandardCharsets.UTF_8);

        AccountState migrated = storage.loadOrCreateAccount(accountId);

        assertEquals(1, migrated.personalSkins().size());
        var personal = migrated.personalSkins().get(0);
        assertEquals(personalHash, personal.sha256());
        assertEquals("Earliest name", personal.displayName());
        assertEquals(PersonalSkinSource.FILE, personal.source());
        assertEquals(NOW, personal.addedAt());
        assertEquals(NOW.plusSeconds(10), personal.updatedAt());
        assertEquals(classicId, personal.optionalAssetId(SkinVariant.CLASSIC).orElseThrow());
        assertEquals(slimId, personal.optionalAssetId(SkinVariant.SLIM).orElseThrow());
        assertTrue(personal.visible());
        assertEquals(migrated, storage.loadOrCreateAccount(accountId));
    }

    @Test
    void serializesConcurrentAccountUpdatesWithoutLostWrites() throws Exception {
        NclSkinsStorage storage = storage();
        LibraryService library = new LibraryService(storage, CLOCK);
        UUID accountId = UUID.randomUUID();
        int updateCount = 16;
        var executor = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int index = 0; index < updateCount; index++) {
                int presetNumber = index;
                tasks.add(() -> {
                    library.createPreset(
                            accountId,
                            "Preset " + presetNumber,
                            com.naocraftlab.skins.core.model.SkinReference.accountDefault(),
                            null);
                    return null;
                });
            }
            List<Future<Void>> futures = executor.invokeAll(tasks);
            for (Future<Void> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(updateCount, library.load(accountId).presets().size());
    }

    @Test
    @SuppressWarnings("try")
    void mutationLockSerializesSeparateStorageInstancesForSameUuid() throws Exception {
        NclSkinsStorage firstInstance = storage();
        NclSkinsStorage secondInstance = storage();
        UUID accountId = UUID.randomUUID();
        CountDownLatch contenderStarted = new CountDownLatch(1);
        CountDownLatch contenderAcquired = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try (ProcessFileLock firstLock = firstInstance.acquireRemoteMutationLock(accountId)) {
            Future<Void> contender = executor.submit(() -> {
                contenderStarted.countDown();
                try (ProcessFileLock ignored = secondInstance.acquireRemoteMutationLock(accountId)) {
                    contenderAcquired.countDown();
                }
                return null;
            });
            assertTrue(contenderStarted.await(1, TimeUnit.SECONDS));
            assertFalse(contenderAcquired.await(75, TimeUnit.MILLISECONDS));
            firstLock.close();
            assertTrue(contenderAcquired.await(1, TimeUnit.SECONDS));
            contender.get();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void persistsAppearanceSelectionAcrossStorageInstances() throws Exception {
        NclSkinsStorage first = storage();
        NclSkinsStorage second = storage();
        UUID accountId = UUID.randomUUID();
        UUID presetId = UUID.randomUUID();

        AccountAppearanceState saved = first.updateAppearanceIntent(accountId, (ignored, revision) ->
                new AccountAppearanceState(
                        AccountAppearanceState.CURRENT_SCHEMA_VERSION,
                        accountId,
                        revision,
                        presetId,
                        "a".repeat(64),
                        SkinVariant.CLASSIC,
                        null,
                        AppearanceSyncStatus.PENDING,
                        0,
                        NOW));

        AccountAppearanceState reopened = second.loadAppearance(accountId);
        assertEquals(presetId, reopened.activePresetId());
        assertEquals(saved.intentRevision(), reopened.intentRevision());
        assertTrue(reopened.pendingOfficialSync());
        assertEquals(OuterLayerVisibility.allVisible(), reopened.outerLayerVisibility());
    }

    @Test
    void legacyAppearanceIntentDefaultsEveryOuterLayerPartToVisible() throws Exception {
        NclSkinsStorage storage = storage();
        storage.initialize();
        UUID accountId = UUID.randomUUID();
        UUID presetId = UUID.randomUUID();
        Path path = storage.layout().accountAppearance(accountId);
        Files.createDirectories(path.getParent());
        Files.writeString(path, """
                {
                  "schemaVersion": 1,
                  "accountId": "%s",
                  "intentRevision": 7,
                  "activePresetId": "%s",
                  "skinSha256": "%s",
                  "skinVariant": "CLASSIC",
                  "syncStatus": "PENDING",
                  "settledRevision": 0,
                  "updatedAt": "%s"
                }
                """.formatted(accountId, presetId, "a".repeat(64), NOW), StandardCharsets.UTF_8);

        AccountAppearanceState migrated = storage.loadAppearance(accountId);

        assertEquals(AccountAppearanceState.CURRENT_SCHEMA_VERSION, migrated.schemaVersion());
        assertEquals(OuterLayerVisibility.allVisible(), migrated.outerLayerVisibility());
        String rewritten = Files.readString(path, StandardCharsets.UTF_8);
        assertTrue(rewritten.contains("\"schemaVersion\": 2"));
        assertTrue(rewritten.contains("\"outerLayer\""));
    }

    @Test
    void appearanceIntentRevisionIsAtomicAndAccountScopedAcrossInstances() throws Exception {
        NclSkinsStorage first = storage();
        NclSkinsStorage second = storage();
        UUID accountId = UUID.randomUUID();
        UUID otherAccountId = UUID.randomUUID();
        int updateCount = 24;
        var executor = Executors.newFixedThreadPool(6);
        List<Callable<Long>> tasks = new ArrayList<>();
        for (int index = 0; index < updateCount; index++) {
            NclSkinsStorage instance = index % 2 == 0 ? first : second;
            tasks.add(() -> instance.updateAppearanceIntent(accountId, (current, revision) ->
                    pendingAppearance(accountId, revision, UUID.randomUUID())).intentRevision());
        }

        List<Long> revisions = new ArrayList<>();
        try {
            for (Future<Long> future : executor.invokeAll(tasks)) {
                revisions.add(future.get());
            }
        } finally {
            executor.shutdownNow();
        }
        revisions.sort(Long::compareTo);

        assertEquals(
                java.util.stream.LongStream.rangeClosed(1, updateCount).boxed().toList(),
                revisions);
        assertEquals(updateCount, first.loadAppearance(accountId).intentRevision());
        assertEquals(
                1,
                second.updateAppearanceIntent(otherAccountId, (current, revision) ->
                        pendingAppearance(otherAccountId, revision, UUID.randomUUID())).intentRevision());
        assertFalse(Files.exists(first.layout().intentSequence()));
    }

    @Test
    void conditionalEffectiveIntentReportsLostRaceAndPreservesNewerPendingRevision()
            throws Exception {
        NclSkinsStorage storage = storage();
        UUID accountId = UUID.randomUUID();
        UUID oldPreset = UUID.randomUUID();
        UUID newerPreset = UUID.randomUUID();
        AccountAppearanceState original = storage.updateAppearanceIntent(
                accountId,
                (current, revision) -> pendingAppearance(accountId, revision, oldPreset));
        CountDownLatch newerWriterHasAccountLock = new CountDownLatch(1);
        CountDownLatch releaseNewerWriter = new CountDownLatch(1);
        CountDownLatch staleCheckpointStarted = new CountDownLatch(1);
        AtomicInteger staleUpdateCalls = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<AccountAppearanceState> newer = executor.submit(() -> storage.updateAppearance(
                    accountId,
                    current -> {
                        newerWriterHasAccountLock.countDown();
                        try {
                            if (!releaseNewerWriter.await(1, TimeUnit.SECONDS)) {
                                throw new AssertionError("newer writer was not released");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                        return pendingAppearance(
                                accountId, current.intentRevision() + 1, newerPreset);
                    }));
            assertTrue(newerWriterHasAccountLock.await(1, TimeUnit.SECONDS));

            Future<NclSkinsStorage.AppearanceIntentUpdate> stale = executor.submit(() -> {
                staleCheckpointStarted.countDown();
                return storage.updateAppearanceIntentIfCurrent(
                        accountId,
                        original.intentRevision(),
                        AppearanceSyncStatus.PENDING,
                        (current, revision) -> {
                            staleUpdateCalls.incrementAndGet();
                            return pendingAppearance(accountId, revision, oldPreset);
                        });
            });
            assertTrue(staleCheckpointStarted.await(1, TimeUnit.SECONDS));
            releaseNewerWriter.countDown();

            AccountAppearanceState newerState = newer.get(1, TimeUnit.SECONDS);
            NclSkinsStorage.AppearanceIntentUpdate staleResult = stale.get(1, TimeUnit.SECONDS);

            assertFalse(staleResult.updated());
            assertEquals(0, staleUpdateCalls.get());
            assertEquals(newerState, staleResult.state());
            assertEquals(newerPreset, staleResult.state().activePresetId());
            assertEquals(AppearanceSyncStatus.PENDING, staleResult.state().syncStatus());
            assertEquals(newerState, storage.loadAppearance(accountId));
        } finally {
            releaseNewerWriter.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void ownedCapeInventoryIsAccountScopedAndPersistsOnlyOpaqueCacheKeys() throws Exception {
        NclSkinsStorage storage = storage();
        UUID accountId = UUID.randomUUID();
        String cacheKey = "a".repeat(64);
        OwnedCapeInventory saved = new OwnedCapeInventory(
                OwnedCapeInventory.CURRENT_SCHEMA_VERSION,
                accountId,
                List.of(new OwnedCapeEntry(
                        "migrator", "Migrator", RemoteAssetState.ACTIVE, cacheKey)),
                NOW);

        storage.saveOwnedCapes(saved);

        assertEquals(saved, storage.loadOwnedCapes(accountId));
        String json = Files.readString(storage.layout().accountOwnedCapes(accountId));
        assertTrue(json.contains(cacheKey));
        assertFalse(json.contains("textures.minecraft.net"));
        assertFalse(json.toLowerCase(java.util.Locale.ROOT).contains("token"));
        assertTrue(storage.loadOwnedCapes(UUID.randomUUID()).capes().isEmpty());
    }

    @Test
    void sameUuidWithAnyNameAlwaysUsesTheSameExactAccount() throws Exception {
        NclSkinsStorage storage = storage();
        UUID profileId = UUID.randomUUID();

        NclSkinsStorage.IdentityResolution beforeRename = storage.observeIdentity(profileId, "Player");
        NclSkinsStorage.IdentityResolution afterRename = storage.observeIdentity(profileId, "Renamed_Player");

        assertEquals(profileId, beforeRename.canonicalAccountId());
        assertEquals(profileId, afterRename.canonicalAccountId());
        assertEquals(
                storage.layout().accountState(beforeRename.canonicalAccountId()),
                storage.layout().accountState(afterRename.canonicalAccountId()));
        assertFalse(Files.exists(storage.layout().identityRegistry()));
    }

    @Test
    void differentUuidsWithTheSameNameStayIsolated() throws Exception {
        NclSkinsStorage storage = storage();
        UUID firstProfileId = UUID.randomUUID();
        UUID secondProfileId = UUID.randomUUID();

        NclSkinsStorage.IdentityResolution first = storage.observeIdentity(firstProfileId, "Player");
        NclSkinsStorage.IdentityResolution second = storage.observeIdentity(secondProfileId, "player");

        assertEquals(firstProfileId, first.canonicalAccountId());
        assertEquals(secondProfileId, second.canonicalAccountId());
        assertFalse(first.canonicalAccountId().equals(second.canonicalAccountId()));
        assertFalse(storage.layout().accountState(firstProfileId)
                .equals(storage.layout().accountState(secondProfileId)));
        assertTrue(storage.planVerifiedIdentity(secondProfileId, "PLAYER").sourceAccountIds().isEmpty());
        assertFalse(Files.exists(storage.layout().identityRegistry()));
    }

    @Test
    void legacyIdentityRegistryIsIgnoredAndLeftByteForByteUnchanged() throws Exception {
        NclSkinsStorage storage = storage();
        storage.initialize();
        UUID observedProfileId = UUID.randomUUID();
        UUID oldCanonicalId = UUID.randomUUID();
        Path registry = storage.layout().identityRegistry();
        byte[] legacyBytes = ("""
                {
                  "schemaVersion": 1,
                  "updatedAt": "%s",
                  "observedNames": {"%s": "Player"},
                  "uuidAliases": {"%s": "%s"},
                  "nameAliases": {"player": "%s"},
                  "verifiedAccounts": {"%s": true}
                }
                """.formatted(
                        NOW,
                        observedProfileId,
                        observedProfileId,
                        oldCanonicalId,
                        oldCanonicalId,
                        oldCanonicalId)).getBytes(StandardCharsets.UTF_8);
        Files.write(registry, legacyBytes);
        FileTime marker = FileTime.from(Instant.parse("2000-01-01T00:00:00Z"));
        Files.setLastModifiedTime(registry, marker);

        NclSkinsStorage.IdentityResolution resolved = storage.observeIdentity(observedProfileId, "Player");
        NclSkinsStorage.VerifiedIdentityPlan plan = storage.planVerifiedIdentity(observedProfileId, "Player");
        storage.completeVerifiedIdentity(plan, "Player");

        assertEquals(observedProfileId, resolved.canonicalAccountId());
        assertTrue(plan.sourceAccountIds().isEmpty());
        assertFalse(plan.conflict());
        assertArrayEquals(legacyBytes, Files.readAllBytes(registry));
        assertEquals(marker, Files.getLastModifiedTime(registry));
    }

    private static AccountAppearanceState pendingAppearance(UUID accountId, long revision, UUID presetId) {
        return new AccountAppearanceState(
                AccountAppearanceState.CURRENT_SCHEMA_VERSION,
                accountId,
                revision,
                presetId,
                "a".repeat(64),
                SkinVariant.CLASSIC,
                null,
                AppearanceSyncStatus.PENDING,
                0,
                NOW);
    }

    private void assertInjectedPreflightFailure(String rootName, java.io.IOException expected) {
        Path root = temporaryDirectory.resolve(rootName);
        NclSkinsStorage storage = new NclSkinsStorage(
                root,
                new PngValidator(),
                CLOCK,
                (layout, locks) -> {
                    throw expected;
                });

        StorageAccessException failure = assertThrows(StorageAccessException.class, storage::initialize);

        assertSame(expected, failure.getCause());
        assertTrue(failure.getMessage().contains(root.toAbsolutePath().toString()));
    }

    private NclSkinsStorage storage() {
        return new NclSkinsStorage(
                temporaryDirectory.resolve("nclskins"),
                new PngValidator(),
                CLOCK);
    }
}
