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
        Files.write(oversized, new byte[1024 * 1024 + 1]);
        FakeHarness harness = new FakeHarness();

        try (harness) {
            assertThrows(IOException.class, () -> harness.registry.register(
                    TextureKind.PLAYER_SKIN, CONTENT_HASH, oversized));
        }
    }

    @Test
    void oversizedTextureBytesAreRejectedBeforeTheNativeAdapter() {
        FakeHarness harness = new FakeHarness();

        try (harness) {
            assertThrows(IOException.class, () -> harness.registry.register(
                    TextureKind.PLAYER_SKIN, CONTENT_HASH, new byte[1024 * 1024 + 1]));
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

        @Override
        protected LoadedTexture<String> load(TextureKind kind, String sha256, byte[] pngBytes)
                throws IOException {
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
