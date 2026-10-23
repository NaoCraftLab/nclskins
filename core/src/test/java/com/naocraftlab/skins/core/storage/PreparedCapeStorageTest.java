package com.naocraftlab.skins.core.storage;

import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.png.CapeAssetDecoder;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.png.PreparedCapeAsset;
import com.naocraftlab.skins.core.service.LibraryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreparedCapeStorageTest {
    @TempDir Path root;
    private final UUID account = new UUID(0, 71);

    @Test void animatedImportPersistsCompleteDescriptorAndFramesWithoutSource() throws Exception {
        NclSkinsStorage storage = storage();
        Path source = strip(root.resolve("source.png"));
        var first = storage.importCape(account, "First", source);
        var duplicate = storage.importCape(account, "Different", source);
        assertEquals(first, duplicate);
        String hash = first.texture().sha256();
        Path directory = storage.capeAssetPath(account, hash).getParent();
        assertTrue(Files.isRegularFile(directory.resolve(hash + ".cape")));
        assertTrue(Files.isRegularFile(directory.resolve(hash + ".frames")));
        assertFalse(Files.exists(directory.resolve(hash + ".png")));
        assertTrue(Files.size(directory.resolve(hash + ".cape")) <= 64 * 1024);
        PreparedCapeAsset prepared = storage.readPreparedCapeAsset(account, hash).orElseThrow();
        assertEquals(prepared.visualSha256(), new ProviderTextureStorageAdapter(storage, new TextureCache(storage))
                .readLocalPreparedCape(account, hash).orElseThrow().visualSha256());
        assertEquals(2, prepared.frameCount());
        assertEquals(List.of(100, 100), prepared.delaysMillis());
        assertEquals(first.renderSha256(), prepared.visualSha256());
        assertArrayEquals(prepared.readFrame(1), com.naocraftlab.skins.core.png.CapeAssetDecoder.restore(
                prepared.descriptorBytes(), prepared.backing()).readFrame(1));
        Files.delete(source);
        NclSkinsStorage reopened = new NclSkinsStorage(root, new com.naocraftlab.skins.core.png.PngValidator(), Clock.systemUTC());
        assertEquals(List.of(first), reopened.loadOrCreateAccount(account).personalCapes());
        assertArrayEquals(prepared.readFrame(1), reopened.readPreparedCapeAsset(account, hash).orElseThrow().readFrame(1));
    }

    @Test void descriptorHashAndBoundedSizeKeepStorageCodeWithTypedIntegrityCause() throws Exception {
        NclSkinsStorage storage = storage();
        var entry = storage.importCape(account, "Animated", strip(root.resolve("typed.png")));
        String hash = entry.texture().sha256();
        Path descriptor = storage.capeAssetPath(account, hash).resolveSibling(hash + ".cape");
        byte[] original = Files.readAllBytes(descriptor);
        byte[] malformed = original.clone();
        malformed[8] ^= 1;
        Files.write(descriptor, malformed);
        for (boolean thumbnail : new boolean[]{false, true}) {
            StorageException failure = assertThrows(StorageException.class, () -> {
                if (thumbnail) storage.readPreparedCapeThumbnail(account, hash);
                else storage.readPreparedCapeAsset(account, hash);
            });
            assertEquals(StorageException.Code.ASSET_INTEGRITY_FAILURE, failure.code());
            assertTrue(com.naocraftlab.skins.core.png.CapeIntegrityException.isIntegrityFailure(
                    new java.util.concurrent.CompletionException(failure)));
        }
        Files.write(descriptor, new byte[64 * 1024 + 1]);
        StorageException oversized = assertThrows(StorageException.class, () -> storage.readPreparedCapeThumbnail(account, hash));
        assertTrue(com.naocraftlab.skins.core.png.CapeIntegrityException.isIntegrityFailure(oversized));
        Files.write(descriptor, original);
        assertEquals(entry.renderSha256(), storage.readPreparedCapeAsset(account, hash).orElseThrow().visualSha256());
        assertFalse(com.naocraftlab.skins.core.png.CapeIntegrityException.isIntegrityFailure(
                new java.util.concurrent.CompletionException(new StorageException(
                        StorageException.Code.ASSET_INTEGRITY_FAILURE, "transient wrapper", new java.io.IOException("transient")))));
    }

    @Test void corruptDescriptorOrBackingNeverFallsBackToLegacyPng() throws Exception {
        NclSkinsStorage storage = storage();
        var entry = storage.importCape(account, "Animated", strip(root.resolve("source.png")));
        String hash = entry.texture().sha256();
        Path directory = storage.capeAssetPath(account, hash).getParent();
        Path backing = directory.resolve(hash + ".frames");
        Files.write(storage.capeAssetPath(account, hash), new byte[] {1, 2, 3});
        assertTrue(new ProviderTextureStorageAdapter(storage, new TextureCache(storage))
                .readLocalCape(account, hash).isEmpty());
        Files.write(backing, new byte[] {1, 2, 3, 4}, java.nio.file.StandardOpenOption.WRITE);
        assertThrows(StorageException.class, () -> storage.readPreparedCapeAsset(account, hash));
        try (var thumbnail = storage.readPreparedCapeThumbnail(account, hash).orElseThrow()
                .openThumbnailLease()) {
            assertThrows(java.io.IOException.class, () -> thumbnail.readThumbnail(0));
        }
        assertThrows(StorageException.class, () -> storage.readCapeAsset(account, hash));
        Files.delete(backing);
        assertThrows(StorageException.class, () -> storage.readPreparedCapeAsset(account, hash));
        assertThrows(StorageException.class, () -> storage.readPreparedCapeThumbnail(account, hash));
    }

    @Test void partialPublicationDoesNotCreateLibraryEntryAndLaterImportRecovers() throws Exception {
        NclSkinsStorage storage = storage();
        Path source = strip(root.resolve("source.png"));
        Path staged = root.resolve("staged.frames");
        PreparedCapeAsset prepared = new CapeAssetDecoder().prepare(source, staged);
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest
                .getInstance("SHA-256").digest(prepared.descriptorBytes()));
        Path directory = storage.capeAssetPath(account, hash).getParent();
        Files.createDirectories(directory);
        Files.copy(staged, directory.resolve(hash + ".frames"));
        assertTrue(storage.loadOrCreateAccount(account).personalCapes().isEmpty());
        Files.write(directory.resolve(hash + ".cape"), prepared.descriptorBytes());
        assertTrue(storage.loadOrCreateAccount(account).personalCapes().isEmpty());
        var entry = storage.importCape(account, "Recovered", source);
        assertEquals(hash, entry.texture().sha256());
        assertEquals(List.of(entry), storage.loadOrCreateAccount(account).personalCapes());
    }

    @Test void injectedCommitFailureAfterAssetPublicationLeavesNoLibraryReferenceAndRetryRecovers() throws Exception {
        NclSkinsStorage storage = storage();
        Path source = strip(root.resolve("commit-failure-source.png"));
        PreparedCapeAsset prepared = new CapeAssetDecoder().prepare(source, root.resolve("commit-failure.frames"));
        AtomicInteger checks = new AtomicInteger();
        assertThrows(java.nio.file.FileSystemException.class, () -> storage.importPreparedCape(
                account, "Interrupted", prepared, current -> {
                    if (checks.incrementAndGet() == 2) {
                        throw new java.nio.file.FileSystemException("account.json", null, "No space left on device");
                    }
                }));
        assertEquals(2, checks.get());
        assertTrue(storage.loadOrCreateAccount(account).personalCapes().isEmpty());
        var recovered = storage.importPreparedCape(account, "Recovered", prepared, current -> {});
        assertEquals(List.of(recovered), storage.loadOrCreateAccount(account).personalCapes());
        assertEquals(prepared.visualSha256(), storage.readPreparedCapeAsset(account,
                recovered.texture().sha256()).orElseThrow().visualSha256());
    }

    @Test void backingPublicationIoFailureCannotCreateAVisibleLibraryEntry() throws Exception {
        NclSkinsStorage storage = storage();
        PreparedCapeAsset prepared = new CapeAssetDecoder().prepare(
                strip(root.resolve("blocked-source.png")), root.resolve("blocked.frames"));
        Path directory = storage.capeAssetPath(account, "0".repeat(64)).getParent();
        Files.createDirectories(directory.getParent());
        Files.write(directory, new byte[] {1});
        assertThrows(java.io.IOException.class, () -> storage.importPreparedCape(
                account, "Blocked", prepared, current -> {}));
        assertTrue(storage.loadOrCreateAccount(account).personalCapes().isEmpty());
        Files.delete(directory);
        var entry = storage.importPreparedCape(account, "Recovered", prepared, current -> {});
        assertEquals(List.of(entry), storage.loadOrCreateAccount(account).personalCapes());
    }

    @Test void observedCacheUsesVisualIdentityWithoutAccountReference() throws Exception {
        NclSkinsStorage storage = storage();
        Path source = strip(root.resolve("source.png"));
        PreparedCapeAsset prepared = new CapeAssetDecoder().prepare(source, root.resolve("observed.frames"));
        String key = storage.storeObservedPreparedCape(prepared);
        assertEquals(prepared.visualSha256(), key);
        assertEquals(key, storage.storeObservedPreparedCape(prepared));
        assertArrayEquals(prepared.readFrame(1), storage.readObservedPreparedCape(key).orElseThrow().readFrame(1));
        assertTrue(storage.loadOrCreateAccount(account).personalCapes().isEmpty());
        Path backing = storage.layout().textureCache().resolve(key + ".frames");
        Files.write(backing, new byte[] {1, 2, 3, 4}, java.nio.file.StandardOpenOption.WRITE);
        assertThrows(StorageException.class, () -> storage.readObservedPreparedCape(key));
    }

    @Test void observedCacheInventoryCountsOrphansAndRemovesUnderSharedLock() throws Exception {
        NclSkinsStorage storage = storage();
        PreparedCapeAsset prepared = new CapeAssetDecoder().prepare(
                strip(root.resolve("inventory-source.png")), root.resolve("inventory.frames"));
        String key = storage.storeObservedPreparedCape(prepared);
        Path descriptor = storage.layout().textureCache().resolve(key + ".cape");
        Path backing = storage.layout().textureCache().resolve(key + ".frames");
        var entry = storage.listObservedPreparedCapes().stream()
                .filter(candidate -> candidate.visualSha256().equals(key)).findFirst().orElseThrow();
        assertEquals(Files.size(backing) + Files.size(descriptor), entry.storedBytes());
        assertEquals(backing.toRealPath(), entry.backing());
        assertTrue(entry.lastModifiedMillis() > 0);

        Files.delete(descriptor);
        assertEquals(Files.size(backing), storage.listObservedPreparedCapes().stream()
                .filter(candidate -> candidate.visualSha256().equals(key)).findFirst().orElseThrow().storedBytes());
        var anotherProcessView = new NclSkinsStorage(root, new PngValidator(), Clock.systemUTC());
        assertTrue(anotherProcessView.removeObservedPreparedCape(key));
        assertTrue(anotherProcessView.removeObservedPreparedCape(key));
        assertFalse(Files.exists(backing));
        assertTrue(storage.listObservedPreparedCapes().stream()
                .noneMatch(candidate -> candidate.visualSha256().equals(key)));
    }

    @Test void concurrentImportsAndPublished120ReferenceFieldsSurviveUnrelatedRewrite() throws Exception {
        NclSkinsStorage storage = storage();
        Path source = strip(root.resolve("source.png"));
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> storage.importCape(account, "A", source));
            var second = executor.submit(() -> new NclSkinsStorage(root,
                    new com.naocraftlab.skins.core.png.PngValidator(), Clock.systemUTC())
                    .importCape(account, "B", source));
            assertEquals(first.get(), second.get());
        } finally { executor.shutdownNow(); }
        var entry = storage.loadOrCreateAccount(account).personalCapes().get(0);
        var library = new LibraryService(new LibraryStorageAdapter(storage),
                new LibraryStorageAdapter(storage), Clock.systemUTC());
        var preset = library.createPreset(account, "Published", SkinReference.accountDefault(),
                OuterLayerVisibility.allVisible(), null, entry.texture()).presets().get(0);
        byte[] oldShape = Files.readAllBytes(storage.layout().accountState(account));
        var publishedWriter = new AccountStateJson();
        var decoded = publishedWriter.decode(oldShape).state();
        Files.write(storage.layout().accountState(account), publishedWriter.encode(decoded));
        storage.renameCape(account, entry.texture().entryId(), "Renamed");
        NclSkinsStorage reopened = new NclSkinsStorage(root, new com.naocraftlab.skins.core.png.PngValidator(),
                Clock.systemUTC());
        var state = reopened.loadOrCreateAccount(account);
        assertEquals(1, state.personalCapes().size());
        assertEquals("Renamed", state.personalCapes().get(0).name());
        assertEquals(entry.texture(), state.personalCapes().get(0).texture());
        assertNotEquals(entry.name(), state.personalCapes().get(0).name());
        assertEquals(entry.texture(), state.presets().stream()
                .filter(value -> value.id().equals(preset.id())).findFirst().orElseThrow().offlineCape());
        assertEquals(entry.renderSha256(), reopened.readPreparedCapeAsset(account,
                entry.texture().sha256()).orElseThrow().visualSha256());
    }

    private NclSkinsStorage storage() throws Exception {
        NclSkinsStorage storage = new NclSkinsStorage(root,
                new com.naocraftlab.skins.core.png.PngValidator(), Clock.systemUTC());
        storage.initialize();
        storage.loadOrCreateAccount(account);
        return storage;
    }

    private static Path strip(Path path) throws Exception {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(3, 5, 0xff123456);
        image.setRGB(25, 32, 0xffabcdef);
        ImageIO.write(image, "png", path.toFile());
        return path;
    }
}
