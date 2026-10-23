package com.naocraftlab.skins.core.png;

import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreparedCapeBackingLeaseTest {
    @TempDir Path directory;

    @Test
    void keepsOpenedBytesAfterAtomicReplacementAndUnlink() throws Exception {
        PreparedCapeAsset asset = prepare("first", 64, 2);
        byte[] expected = asset.readFrame(1);
        long filesBefore;
        try (var files = Files.list(directory)) { filesBefore = files.count(); }
        try (PreparedCapeAsset.BackingLease lease = asset.openBackingLease()) {
            try (var files = Files.list(directory)) { assertEquals(filesBefore, files.count()); }
            Path replacement = Files.write(directory.resolve("replacement.frames"), new byte[(int) asset.backingBytes()]);
            Files.move(replacement, asset.backing(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            assertArrayEquals(expected, lease.readFrame(1));
            assertThrows(IOException.class, asset::openBackingLease);
            Files.delete(asset.backing());
            assertFalse(Files.exists(asset.backing()));
            assertArrayEquals(expected, lease.readFrame(1));
            assertEquals(0xff654321, ImageIO.read(new ByteArrayInputStream(lease.framePng(1))).getRGB(3, 5));
        }
    }

    @Test
    void closeIsIdempotentAndReadsAreIndexedAndBounded() throws Exception {
        PreparedCapeAsset asset = prepare("hd", 2048, 2);
        PreparedCapeAsset.BackingLease lease = asset.openBackingLease();
        assertEquals(8 * 1024 * 1024, lease.readFrame(1).length);
        assertThrows(IndexOutOfBoundsException.class, () -> lease.readFrame(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> lease.readFrame(2));
        lease.close();
        lease.close();
        assertThrows(ClosedChannelException.class, () -> lease.readFrame(0));
        assertThrows(ClosedChannelException.class, () -> lease.framePng(0));
    }

    @Test
    void existingLeaseSurvivesDeletionByAnotherStorageInstance() throws Exception {
        UUID account = new UUID(0, 45);
        NclSkinsStorage writer = new NclSkinsStorage(directory, new PngValidator(), Clock.systemUTC());
        writer.initialize();
        writer.loadOrCreateAccount(account);
        PreparedCapeAsset source = prepare("storage", 64, 2);
        var entry = writer.importPreparedCape(account, "Synthetic", source, current -> {});
        NclSkinsStorage reader = new NclSkinsStorage(directory, new PngValidator(), Clock.systemUTC());
        reader.initialize();
        PreparedCapeAsset asset = reader.readPreparedCapeAsset(account, entry.texture().sha256()).orElseThrow();
        byte[] expected = asset.readFrame(1);
        try (PreparedCapeAsset.BackingLease lease = asset.openBackingLease()) {
            writer.deleteCape(account, entry.texture().entryId());
            assertEquals(0, reader.loadOrCreateAccount(account).personalCapes().size());
            Files.delete(asset.backing());
            assertArrayEquals(expected, lease.readFrame(1));
        }
    }

    @Test
    void detectsMutationOfTheOpenedInodeAndInvalidBackingBeforeUse() throws Exception {
        PreparedCapeAsset asset = prepare("integrity", 64, 2);
        try (PreparedCapeAsset.BackingLease lease = asset.openBackingLease()) {
            byte[] bytes = Files.readAllBytes(asset.backing());
            bytes[3] ^= 1;
            Files.write(asset.backing(), bytes);
            assertThrows(IOException.class, () -> lease.readFrame(0));
        }
        assertThrows(IOException.class, asset::openBackingLease);
    }

    @Test
    void twoStorageReadersPinTheSameObservedBackingUntilBothClose() throws Exception {
        NclSkinsStorage first = new NclSkinsStorage(directory, new PngValidator(), Clock.systemUTC());
        first.initialize();
        PreparedCapeAsset source = prepare("shared-cache", 64, 2);
        String key = first.storeObservedPreparedCape(source);
        NclSkinsStorage second = new NclSkinsStorage(directory, new PngValidator(), Clock.systemUTC());
        second.initialize();
        PreparedCapeAsset left = first.readObservedPreparedCape(key).orElseThrow();
        PreparedCapeAsset right = second.readObservedPreparedCape(key).orElseThrow();
        try (PreparedCapeAsset.BackingLease lease2 = right.openBackingLease()) {
            try (PreparedCapeAsset.BackingLease lease1 = left.openBackingLease()) {
                assertArrayEquals(lease1.readFrame(1), lease2.readFrame(1));
                assertFalse(second.removeObservedPreparedCape(key));
                assertTrue(Files.exists(left.backing()));
            }
            assertFalse(first.removeObservedPreparedCape(key));
        }
        assertTrue(second.removeObservedPreparedCape(key));
        assertFalse(Files.exists(left.backing()));
    }

    @Test
    void aReaderInAnotherJvmPreventsCooperativeCacheEviction() throws Exception {
        NclSkinsStorage storage = new NclSkinsStorage(directory, new PngValidator(), Clock.systemUTC());
        storage.initialize();
        String key = storage.storeObservedPreparedCape(prepare("remote-reader", 64, 2));
        Path backing = storage.layout().textureCache().resolve(key + ".frames");
        Path descriptor = storage.layout().textureCache().resolve(key + ".cape");
        Path ready = directory.resolve("reader-ready");
        Path stop = directory.resolve("reader-stop");
        String classes = Path.of(PreparedCapeBackingLeaseTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toString();
        String main = Path.of(PreparedCapeAsset.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toString();
        Process reader = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classes + java.io.File.pathSeparator + main,
                PinnedReader.class.getName(), descriptor.toString(), backing.toString(),
                ready.toString(), stop.toString())
                .redirectErrorStream(true).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(ready) && reader.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(Files.exists(ready), "Child reader did not acquire its backing lease");
            assertFalse(storage.removeObservedPreparedCape(key));
            assertTrue(Files.exists(descriptor));
            assertTrue(Files.exists(backing));
        } finally {
            Files.createFile(stop);
            if (!reader.waitFor(5, TimeUnit.SECONDS)) reader.destroyForcibly();
        }
        assertEquals(0, reader.exitValue());
        assertTrue(storage.removeObservedPreparedCape(key));
        assertFalse(Files.exists(descriptor));
        assertFalse(Files.exists(backing));
    }

    public static final class PinnedReader {
        private PinnedReader() {}
        public static void main(String[] args) throws Exception {
            PreparedCapeAsset asset = com.naocraftlab.skins.core.png.CapeAssetDecoder.restore(
                    Files.readAllBytes(Path.of(args[0])), Path.of(args[1]));
            try (PreparedCapeAsset.BackingLease lease = asset.openBackingLease()) {
                Files.createFile(Path.of(args[2]));
                while (!Files.exists(Path.of(args[3]))) Thread.sleep(10);
                lease.readFrame(0);
            }
        }
    }

    private PreparedCapeAsset prepare(String name, int width, int frames) throws Exception {
        int height = width / 2;
        BufferedImage image = new BufferedImage(width, height * frames, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(3, 5, 0xff123456);
        image.setRGB(3, height + 5, 0xff654321);
        Path encoded = directory.resolve(name + ".png");
        ImageIO.write(image, "png", encoded.toFile());
        return new CapeAssetDecoder().prepare(encoded, directory.resolve(name + ".frames"));
    }
}
