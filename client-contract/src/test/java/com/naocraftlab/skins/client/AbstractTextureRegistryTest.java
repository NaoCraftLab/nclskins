package com.naocraftlab.skins.client;

import com.naocraftlab.skins.client.TextureRegistry.TextureKind;
import com.naocraftlab.skins.client.TextureRegistry.TextureHandle;
import com.naocraftlab.skins.client.tck.TextureRegistryTck;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractTextureRegistryTest implements TextureRegistryTck {
    @Override
    public RegistryHarness createRegistryHarness() {
        return new FakeHarness();
    }

    @Test
    void byteArrayRegistrationDoesNotExposeTheCallersArrayToTheAdapter() throws IOException {
        FakeHarness harness = new FakeHarness();
        try (harness) {
            byte[] png = TextureRegistryTck.imagePng(1, 1);
            byte[] original = png.clone();

            harness.registry.register(TextureKind.IMAGE, CONTENT_HASH, png);

            assertArrayEquals(original, png);
        }
    }

    @Test
    void oversizedTextureFileIsRejectedByTheReadItself(@TempDir Path temporary) throws IOException {
        Path oversized = temporary.resolve("oversized.png");
        try (var file = new java.io.RandomAccessFile(oversized.toFile(), "rw")) {
            file.setLength(EncodedTextureLimit.MAX_ENCODED_TEXTURE_BYTES + 1L);
        }
        FakeHarness harness = new FakeHarness();

        try (harness) {
            assertThrows(IOException.class, () -> harness.registry.register(
                    TextureKind.PLAYER_SKIN, CONTENT_HASH, oversized));
            org.junit.jupiter.api.Assertions.assertEquals(0, harness.registry.loadAttempts);
        }
    }

    @Test
    void malformedLargeTextureIsRejectedBeforeTheNativeAdapter(@TempDir Path temporary) throws IOException {
        Path exact = temporary.resolve("exact.png");
        try (var file = new java.io.RandomAccessFile(exact.toFile(), "rw")) {
            file.setLength(EncodedTextureLimit.MAX_ENCODED_TEXTURE_BYTES);
        }
        FakeHarness harness = new FakeHarness();
        try (harness) {
            assertThrows(IOException.class, () -> harness.registry.register(
                    TextureKind.IMAGE, CONTENT_HASH, exact));
            org.junit.jupiter.api.Assertions.assertEquals(0, harness.registry.loadAttempts);
        }
    }

    @Test
    void oversizedSheetGeometryIsRejectedBeforeLargePngRasterDecode(@TempDir Path temporary)
            throws IOException {
        byte[] header = TextureRegistryTck.imagePng(1, 1);
        ByteBuffer.wrap(header, 16, 8).putInt(2048).putInt(65_536);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(header, 12, 17);
        ByteBuffer.wrap(header, 29, 4).putInt((int) crc.getValue());
        Path sheet = temporary.resolve("sheet.png");
        Files.write(sheet, header);
        try (var file = new java.io.RandomAccessFile(sheet.toFile(), "rw")) {
            file.setLength(65L * 1024 * 1024 + 1);
        }
        FakeHarness harness = new FakeHarness();
        try (harness) {
            IOException failure = assertThrows(IOException.class, () -> harness.registry.register(
                    TextureKind.IMAGE, CONTENT_HASH, sheet));
            assertTrue(failure.getMessage().contains("dimensions exceed"));
            assertEquals(0, harness.registry.loadAttempts);
        }
    }

    @Test
    void imageInputRemainsAtTheOpenedFileLengthWhenFileGrows(@TempDir Path temporary) throws IOException {
        Path image = temporary.resolve("growing.png");
        Files.write(image, new byte[]{1, 2, 3, 4});
        try (FileChannel channel = FileChannel.open(image, StandardOpenOption.READ, StandardOpenOption.WRITE);
                var input = new AbstractTextureRegistry.BoundedChannelImageInputStream(channel, channel.size())) {
            channel.write(ByteBuffer.wrap(new byte[]{5, 6, 7, 8}), 4);
            byte[] opened = new byte[4];
            input.readFully(opened);
            assertArrayEquals(new byte[]{1, 2, 3, 4}, opened);
            assertEquals(-1, input.read());
            assertEquals(8, channel.size());
        }
    }

    @Test
    void rawFrameUsesTheSameOwnedHandleAndValidatesGeometry() throws IOException {
        FakeHarness harness = new FakeHarness();
        try (harness) {
            byte[] rgba = {(byte) 255, 0, 0, (byte) 255};
            java.util.concurrent.atomic.AtomicBoolean firstReleased = new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicBoolean duplicateReleased = new java.util.concurrent.atomic.AtomicBoolean();
            TextureHandle first = harness.registry.registerRgba(TextureKind.IMAGE, CONTENT_HASH,
                    1, 1, rgba, () -> firstReleased.set(true));
            TextureHandle second = harness.registry.registerRgba(TextureKind.IMAGE, CONTENT_HASH,
                    1, 1, rgba, () -> duplicateReleased.set(true));
            assertEquals(first, second);
            assertEquals(1, harness.registry.loadAttempts);
            assertTrue(duplicateReleased.get());
            assertFalse(firstReleased.get());
            assertThrows(IOException.class, () -> harness.registry.registerRgba(
                    TextureKind.IMAGE, "f".repeat(64), 2, 1, rgba, () -> {}));
            harness.registry.release(first);
            assertEquals(0, harness.registry.unloads.get());
            harness.registry.release(second);
            assertEquals(1, harness.registry.unloads.get());
            assertTrue(firstReleased.get());
        }
    }

    @Test
    void rawRegistrationPromotesAnExistingPngEntryWithoutASecondPhysicalLoad() throws IOException {
        FakeHarness harness = new FakeHarness();
        try (harness) {
            TextureHandle png = harness.registry.register(TextureKind.IMAGE, CONTENT_HASH,
                    TextureRegistryTck.imagePng(1, 1));
            java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
            TextureHandle raw = harness.registry.registerRgba(TextureKind.IMAGE, CONTENT_HASH,
                    1, 1, new byte[]{1, 2, 3, 4}, () -> released.set(true));
            assertEquals(png, raw);
            assertEquals(1, harness.registry.loadAttempts);
            harness.registry.release(png);
            assertFalse(released.get());
            harness.registry.release(raw);
            assertTrue(released.get());
        }
    }

    @Test
    void rawGpuBudgetFollowsPhysicalEntriesThroughRelease() throws IOException {
        FakeHarness harness = new FakeHarness();
        try (harness) {
            byte[] frame = new byte[2048 * 1024 * 4];
            TextureHandle[] handles = new TextureHandle[32];
            for (int index = 0; index < handles.length; index++) {
                handles[index] = harness.registry.registerRgba(TextureKind.IMAGE,
                        "%064x".formatted(index + 1), 2048, 1024, frame, () -> {});
            }
            java.util.concurrent.atomic.AtomicBoolean rejectedLeaseClosed =
                    new java.util.concurrent.atomic.AtomicBoolean();
            assertThrows(IOException.class, () -> harness.registry.registerRgba(TextureKind.IMAGE,
                    "%064x".formatted(33), 2048, 1024, frame,
                    () -> rejectedLeaseClosed.set(true)));
            assertTrue(rejectedLeaseClosed.get());
            assertEquals(32, harness.registry.liveCount());
            harness.registry.release(handles[0]);
            TextureHandle successor = harness.registry.registerRgba(TextureKind.IMAGE,
                    "%064x".formatted(33), 2048, 1024, frame, () -> {});
            for (int index = 1; index < handles.length; index++) harness.registry.release(handles[index]);
            harness.registry.release(successor);
            assertEquals(0, harness.registry.liveCount());
        }
    }

    @Test
    void fifteenThumbnailEntriesRetainFirstFrameAcrossAnimatedReplacement() throws IOException {
        FakeHarness harness = new FakeHarness();
        try (harness) {
            byte[] thumbnail = new byte[512 * 256 * 4];
            TextureHandle[] first = new TextureHandle[15];
            for (int index = 0; index < first.length; index++) {
                first[index] = harness.registry.registerRgba(TextureKind.IMAGE,
                        "%064x".formatted(index + 1), 512, 256, thumbnail, () -> {});
            }
            TextureHandle player = harness.registry.registerRgba(TextureKind.IMAGE,
                    "%064x".formatted(100), 2048, 1024, new byte[2048 * 1024 * 4], () -> {});
            TextureHandle animated = harness.registry.registerRgba(TextureKind.IMAGE,
                    "%064x".formatted(101), 512, 256, thumbnail, () -> {});
            assertEquals(17, harness.registry.liveCount());
            assertEquals(17, harness.registry.loadAttempts);
            harness.registry.release(animated);
            assertEquals(16, harness.registry.liveCount());
            TextureHandle returned = harness.registry.registerRgba(TextureKind.IMAGE,
                    "%064x".formatted(1), 512, 256, thumbnail, () -> {});
            assertEquals(first[0], returned);
            assertEquals(16, harness.registry.liveCount(), "returning to frame zero reuses its physical GPU entry");
            harness.registry.release(returned);
            assertEquals(16, harness.registry.liveCount(), "the retained first-frame reference stays live");
            for (TextureHandle handle : first) harness.registry.release(handle);
            harness.registry.release(player);
            assertEquals(0, harness.registry.liveCount());
        }
    }

    @Test
    void earsLikeMagicAndArbitraryAlphaReachTheNativeAdapterWithoutRasterRewriting() throws IOException {
        FakeHarness harness = new FakeHarness();
        try (harness) {
            BufferedImage source = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            source.setRGB(8, 0, 0x12123456);
            source.setRGB(0, 32, 0xFF3F23D8);
            source.setRGB(56, 20, 0x00112233);
            source.setRGB(57, 20, 0x7F445566);
            source.setRGB(1, 16, 0xFF0000FF);
            source.setRGB(0, 16, 0xFF00007F);
            source.setRGB(0, 17, 0xFF0000FF);
            source.setRGB(2, 16, 0xFF00FF00);
            source.setRGB(3, 16, 0xFF007F00);
            source.setRGB(3, 17, 0xFF00FF00);
            source.setRGB(0, 18, 0xFFFF0000);
            source.setRGB(0, 19, 0xFF7F0000);
            source.setRGB(1, 19, 0xFFFF0000);
            source.setRGB(2, 19, 0xFFFFFFFF);
            source.setRGB(3, 18, 0xFFFFFFFF);
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            ImageIO.write(source, "png", output);
            byte[] png = output.toByteArray();

            harness.registry.register(
                    TextureKind.PLAYER_SKIN, CONTENT_HASH, png);

            assertArrayEquals(png, harness.registry.lastLoadedBytes);
        }
    }

    @Test
    void ordinaryPlayerSkinBytesReachTheNativeAdapterWithoutPresentationRewrite() throws IOException {
        FakeHarness harness = new FakeHarness();
        try (harness) {
            BufferedImage source = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            source.setRGB(8, 0, 0x12123456);
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            ImageIO.write(source, "png", output);
            byte[] png = output.toByteArray();

            harness.registry.register(TextureKind.PLAYER_SKIN, CONTENT_HASH, png);

            assertArrayEquals(png, harness.registry.lastLoadedBytes);
        }
    }

    private static final class FakeHarness implements RegistryHarness {
        private final FakeTextureRegistry registry = new FakeTextureRegistry();

        @Override
        public TextureRegistry registry() {
            return registry;
        }

        @Override
        public int liveNativeTextureCount() {
            return registry.liveCount();
        }

        @Override
        public int unloadCount() {
            return registry.unloads.get();
        }
    }

    private static final class FakeTextureRegistry extends AbstractTextureRegistry<String> {
        private final AtomicInteger unloads = new AtomicInteger();
        private byte[] lastLoadedBytes;
        private int loadAttempts;

        @Override
        protected LoadedTexture<String> load(TextureKind kind, String sha256, byte[] pngBytes)
                throws IOException {
            loadAttempts++;
            lastLoadedBytes = pngBytes.clone();
            BufferedImage image;
            try (ByteArrayInputStream input = new ByteArrayInputStream(pngBytes)) {
                image = ImageIO.read(input);
            }
            if (image == null) {
                throw new IOException("Invalid PNG");
            }
            pngBytes[0] = 0;
            String location = "test:" + kind.name().toLowerCase(java.util.Locale.ROOT) + '/' + sha256;
            return new LoadedTexture<>(
                    new TextureHandle(location, image.getWidth(), image.getHeight()), location);
        }

        @Override
        protected LoadedTexture<String> loadRgba(String sha256, int width, int height, byte[] rgba8) {
            loadAttempts++;
            lastLoadedBytes = rgba8.clone();
            String location = "test:image/" + sha256;
            return new LoadedTexture<>(new TextureHandle(location, width, height), location);
        }

        @Override
        protected void unload(String resource) {
            unloads.incrementAndGet();
        }

        @Override
        protected void checkClientThread() {
        }

        private int liveCount() {
            return liveTextureCount();
        }
    }
}
