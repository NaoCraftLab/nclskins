package com.naocraftlab.skins.client;

import com.naocraftlab.skins.client.TextureRegistry.TextureKind;
import com.naocraftlab.skins.client.tck.TextureRegistryTck;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void oversizedTextureBytesAreRejectedBeforeTheNativeAdapter() {
        FakeHarness harness = new FakeHarness();

        try (harness) {
            assertThrows(IOException.class, () -> harness.registry.register(
                    TextureKind.PLAYER_SKIN, CONTENT_HASH,
                    new byte[EncodedTextureLimit.MAX_ENCODED_TEXTURE_BYTES + 1]));
            org.junit.jupiter.api.Assertions.assertEquals(0, harness.registry.loadAttempts);
        }
    }

    @Test
    void exactTextureLimitReachesTheNativeAdapterForBothKinds(@TempDir Path temporary) throws IOException {
        Path exact = temporary.resolve("exact.png");
        try (var file = new java.io.RandomAccessFile(exact.toFile(), "rw")) {
            file.setLength(EncodedTextureLimit.MAX_ENCODED_TEXTURE_BYTES);
        }
        FakeHarness harness = new FakeHarness();
        try (harness) {
            assertThrows(IOException.class, () -> harness.registry.register(
                    TextureKind.IMAGE, CONTENT_HASH, exact));
            org.junit.jupiter.api.Assertions.assertEquals(1, harness.registry.loadAttempts);
            IOException invalid = assertThrows(IOException.class, () -> harness.registry.register(
                    TextureKind.PLAYER_SKIN, CONTENT_HASH,
                    new byte[EncodedTextureLimit.MAX_ENCODED_TEXTURE_BYTES]));
            org.junit.jupiter.api.Assertions.assertEquals("Player skin is not a decodable PNG image",
                    invalid.getMessage());
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
