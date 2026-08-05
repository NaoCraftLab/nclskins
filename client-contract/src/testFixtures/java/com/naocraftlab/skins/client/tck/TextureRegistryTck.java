package com.naocraftlab.skins.client.tck;

import com.naocraftlab.skins.client.TextureRegistry;
import com.naocraftlab.skins.client.TextureRegistry.TextureHandle;
import com.naocraftlab.skins.client.TextureRegistry.TextureKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


public interface TextureRegistryTck {
    String CONTENT_HASH = "0123456789abcdef".repeat(4);

    RegistryHarness createRegistryHarness();

    @Test
    default void pathAndBytesShareOneReferenceCountedEntry(@TempDir Path directory)
            throws IOException {
        try (RegistryHarness harness = createRegistryHarness()) {
            byte[] png = imagePng(2, 3);
            Path path = directory.resolve("image.png");
            Files.write(path, png);

            TextureHandle fromPath = harness.registry().register(TextureKind.IMAGE, CONTENT_HASH, path);
            TextureHandle fromBytes = harness.registry().register(TextureKind.IMAGE, CONTENT_HASH, png);

            assertEquals(fromPath, fromBytes);
            assertEquals(1, harness.liveNativeTextureCount());
            harness.registry().release(fromPath);
            assertEquals(1, harness.liveNativeTextureCount());
            harness.registry().release(fromBytes);
            assertEquals(0, harness.liveNativeTextureCount());
            assertEquals(1, harness.unloadCount());
        }
    }

    @Test
    default void textureKindIsPartOfTheOwnershipKey() throws IOException {
        try (RegistryHarness harness = createRegistryHarness()) {
            byte[] legacySkin = imagePng(64, 32);

            TextureHandle image = harness.registry()
                    .register(TextureKind.IMAGE, CONTENT_HASH, legacySkin);
            TextureHandle playerSkin = harness.registry()
                    .register(TextureKind.PLAYER_SKIN, CONTENT_HASH, legacySkin);

            assertNotEquals(image, playerSkin);
            assertEquals(64, playerSkin.width());
            assertEquals(32, playerSkin.height());
            assertEquals(2, harness.liveNativeTextureCount());
        }
    }

    @Test
    default void genericAndPlayerSkinModesHaveOnePlayerCacheKey() throws IOException {
        try (RegistryHarness harness = createRegistryHarness()) {
            byte[] png = imagePng(64, 64);
            TextureHandle generic = harness.registry()
                    .register(TextureKind.IMAGE, CONTENT_HASH, png);
            TextureHandle ordinary = harness.registry()
                    .register(TextureKind.PLAYER_SKIN, CONTENT_HASH, png);
            TextureHandle featurePreserving = harness.registry().register(
                    TextureKind.PLAYER_SKIN, CONTENT_HASH, png);

            assertNotEquals(generic, ordinary);
            assertEquals(ordinary, featurePreserving);
            assertNotEquals(generic, featurePreserving);
            assertEquals(2, harness.liveNativeTextureCount());
        }
    }

    @Test
    default void closeReleasesEverythingAndPermanentlyClosesTheRegistry() throws IOException {
        RegistryHarness harness = createRegistryHarness();
        byte[] first = imagePng(1, 1);
        byte[] second = imagePng(2, 1);
        harness.registry().register(TextureKind.IMAGE, CONTENT_HASH, first);
        harness.registry().register(TextureKind.IMAGE, "f".repeat(64), second);

        harness.registry().close();

        assertEquals(0, harness.liveNativeTextureCount());
        assertEquals(2, harness.unloadCount());
        assertDoesNotThrow(harness.registry()::close);
        assertEquals(2, harness.unloadCount());
        assertThrows(
                IllegalStateException.class,
                () -> harness.registry().register(TextureKind.IMAGE, "e".repeat(64), first));
        harness.close();
    }

    @Test
    default void invalidKeysAndUnknownHandlesDoNotChangeOwnership() throws IOException {
        try (RegistryHarness harness = createRegistryHarness()) {
            byte[] png = imagePng(1, 1);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> harness.registry().register(TextureKind.IMAGE, "A".repeat(64), png));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> harness.registry().register(TextureKind.IMAGE, "short", png));

            assertDoesNotThrow(() -> harness.registry()
                    .release(new TextureHandle("unowned:texture", 1, 1)));
            assertEquals(0, harness.liveNativeTextureCount());
            assertEquals(0, harness.unloadCount());
        }
    }

    static byte[] imagePng(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, 0x80000000 | x << 8 | y);
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("No PNG writer is available for the texture-registry TCK");
        }
        return output.toByteArray();
    }

    interface RegistryHarness extends AutoCloseable {
        TextureRegistry registry();

        int liveNativeTextureCount();

        int unloadCount();

        @Override
        default void close() {
            registry().close();
        }
    }
}
