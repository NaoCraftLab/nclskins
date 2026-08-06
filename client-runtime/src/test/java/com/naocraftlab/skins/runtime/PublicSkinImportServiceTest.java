package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.core.api.PublicPlayerSkinClient;
import com.naocraftlab.skins.core.api.PublicSkinImportException;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import com.naocraftlab.skins.core.storage.TextureCache;
import com.naocraftlab.skins.core.storage.TextureCacheException;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class PublicSkinImportServiceTest {
    @Test
    void customPlayerTextureRasterOverridesClassicProfileMetadata(@TempDir Path directory) throws Exception {
        PngValidator validator = new PngValidator();
        NclSkinsStorage storage = new NclSkinsStorage(directory, validator, Clock.systemUTC());
        storage.initialize();
        TextureCache textures = new TextureCache(storage);
        URI texture = URI.create("https://textures.minecraft.net/texture/"
                + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        byte[] slim = skinPng(true);
        Files.write(textures.cachePath(texture), slim);
        PublicSkinImportService service = new PublicSkinImportService(
                textures, (collection, skin, model) -> skinPng(false));
        PublicPlayerSkinClient.Result result = new PublicPlayerSkinClient.Result(
                UUID.randomUUID(), "RasterSlim", Optional.of(texture), SkinVariant.CLASSIC, Optional.empty());

        ClientOperations.ImportDraft draft = service.loadResolvedPlayer(result);

        assertEquals(SkinVariant.SLIM, draft.variant());
        assertArrayEquals(slim, draft.pngBytes());
    }

    @Test
    void bundledDefaultRasterOverridesSlimProfileSelection(@TempDir Path directory) throws Exception {
        PngValidator validator = new PngValidator();
        NclSkinsStorage storage = new NclSkinsStorage(directory, validator, Clock.systemUTC());
        AtomicReference<SkinModel> requestedModel = new AtomicReference<>();
        byte[] classic = skinPng(false);
        PublicSkinImportService service = new PublicSkinImportService(
                new TextureCache(storage),
                (collection, skin, model) -> {
                    requestedModel.set(model);
                    return classic;
                });
        PublicPlayerSkinClient.Result result = new PublicPlayerSkinClient.Result(
                UUID.randomUUID(), "RasterClassic", Optional.empty(), SkinVariant.SLIM, Optional.of("ari"));

        ClientOperations.ImportDraft draft = service.loadResolvedPlayer(result);

        assertEquals(SkinModel.SLIM, requestedModel.get());
        assertEquals(SkinVariant.CLASSIC, draft.variant());
        assertArrayEquals(classic, draft.pngBytes());
    }

    @Test
    void mapsPlayerTextureFailuresToSafeTypedReasons() {
        assertEquals(
                PublicSkinImportException.Code.NETWORK_FAILURE,
                PublicSkinImportService.playerTextureFailure(textureFailure(
                        TextureCacheException.Code.NETWORK_FAILURE)).code());
        assertEquals(
                PublicSkinImportException.Code.PROFILE_REJECTED,
                PublicSkinImportService.playerTextureFailure(textureFailure(
                        TextureCacheException.Code.REDIRECT_REJECTED)).code());
        assertEquals(
                PublicSkinImportException.Code.OVERSIZED,
                PublicSkinImportService.playerTextureFailure(textureFailure(
                        TextureCacheException.Code.OVERSIZED)).code());
        assertEquals(
                PublicSkinImportException.Code.OVERSIZED,
                PublicSkinImportService.playerTextureFailure(new PngValidationException(
                        PngValidationException.Reason.OVERSIZED, "sensitive detail")).code());
        assertEquals(
                PublicSkinImportException.Code.PROFILE_REJECTED,
                PublicSkinImportService.playerTextureFailure(new PngValidationException(
                        PngValidationException.Reason.BAD_CHECKSUM, "sensitive detail")).code());
    }

    private static TextureCacheException textureFailure(TextureCacheException.Code code) {
        return new TextureCacheException(code, "sensitive detail");
    }

    private static byte[] skinPng(boolean slim) throws IOException {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                image.setRGB(x, y, 0xff42627f);
            }
        }
        if (slim) {
            image.setRGB(50, 16, 0x0042627f);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
