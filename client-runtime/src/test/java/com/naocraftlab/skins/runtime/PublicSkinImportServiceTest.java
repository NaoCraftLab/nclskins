package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.core.importing.PublicProfileLookup;
import com.naocraftlab.skins.core.importing.PublicSkinImageSource;
import com.naocraftlab.skins.core.png.NormalizedSkin;
import com.naocraftlab.skins.core.api.PublicSkinImportException;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.png.PngValidator;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class PublicSkinImportServiceTest {
    @Test
    void customPlayerTextureRasterOverridesClassicProfileMetadata() throws Exception {
        byte[] slim = skinPng(true);
        var reference = new PublicProfileLookup.VerifiedTexture() {};
        var result = new PublicProfileLookup.Observation("RasterSlim", Optional.of(reference),
                SkinVariant.CLASSIC, Optional.empty());
        var service = new PublicSkinImportService(Optional.of(query -> result), new PublicSkinImageSource() {
            @Override public NormalizedSkin loadVerified(PublicProfileLookup.VerifiedTexture texture) throws Exception {
                assertEquals(reference, texture);
                return new PngValidator().normalizeSkinWithVariant(slim);
            }
            @Override public NormalizedSkin loadUntrusted(String url) { throw new AssertionError(); }
        }, (collection, skin, model) -> { throw new AssertionError(); });

        ClientOperations.ImportDraft draft = service.loadPlayer("Player");

        assertEquals("RasterSlim", draft.name());
        assertEquals(com.naocraftlab.skins.core.model.PersonalSkinSource.PLAYER_NAME, draft.source());
        assertEquals(SkinVariant.SLIM, draft.variant());
        assertArrayEquals(slim, draft.pngBytes());
    }

    @Test
    void bundledDefaultRasterOverridesSlimProfileSelection() throws Exception {
        AtomicReference<SkinModel> requestedModel = new AtomicReference<>();
        byte[] classic = skinPng(false);
        var result = new PublicProfileLookup.Observation("RasterClassic", Optional.empty(),
                SkinVariant.SLIM, Optional.of("ari"));
        PublicSkinImportService service = new PublicSkinImportService(
                Optional.of(query -> result), unusedImages(),
                (collection, skin, model) -> {
                    assertEquals(com.naocraftlab.skins.client.MinecraftSkinCatalog.COLLECTION_ID, collection);
                    assertEquals("ari", skin);
                    requestedModel.set(model);
                    return classic;
                });
        ClientOperations.ImportDraft draft = service.loadPlayer("01234567-89ab-cdef-0123-456789abcdef");

        assertEquals(SkinModel.SLIM, requestedModel.get());
        assertEquals(SkinVariant.CLASSIC, draft.variant());
        assertArrayEquals(classic, draft.pngBytes());
    }

    @Test
    void disabledLookupAndMissingDefaultRemainDistinct() throws Exception {
        var service = new PublicSkinImportService(Optional.empty(), unusedImages(),
                (collection, skin, model) -> { throw new IOException("missing default"); });
        assertThrows(UnsupportedOperationException.class, () -> service.loadPlayer("Player"));
        var result = new PublicProfileLookup.Observation("Player", Optional.empty(),
                SkinVariant.CLASSIC, Optional.of("steve"));
        var missing = new PublicSkinImportService(Optional.of(query -> result), unusedImages(),
                (collection, skin, model) -> { throw new IOException("missing default"); });
        assertThrows(IOException.class, () -> missing.loadPlayer("Player"));
    }

    @Test
    void mapsEveryPngFailure() {
        for (var reason : PngValidationException.Reason.values()) {
            assertEquals(reason == PngValidationException.Reason.OVERSIZED
                            ? PublicSkinImportException.Code.OVERSIZED : PublicSkinImportException.Code.PROFILE_REJECTED,
                    PublicSkinImportService.playerTextureFailure(new PngValidationException(reason, "rejected")).code());
        }
    }

    @Test
    void urlDraftKeepsSanitizedNameBytesAndDetectedModel() throws Exception {
        byte[] png = skinPng(true);
        var normalized = new PngValidator().normalizeSkinWithVariant(png);
        AtomicReference<String> requested = new AtomicReference<>();
        var service = new PublicSkinImportService(Optional.empty(), new PublicSkinImageSource() {
            @Override public NormalizedSkin loadVerified(PublicProfileLookup.VerifiedTexture texture) { throw new AssertionError(); }
            @Override public NormalizedSkin loadUntrusted(String url) {
                requested.set(url);
                return normalized;
            }
        }, (collection, skin, model) -> { throw new AssertionError(); });
        for (String[] item : new String[][] {
                {"https://example.com/Cape.PNG", "Cape"},
                {"https://example.com/", "Imported URL skin"},
                {"https://example.com/%E2%80%AEName.png", "Name"}}) {
            var draft = service.loadUrl(item[0]);
            assertEquals(item[0], requested.get());
            assertEquals(item[1], draft.name());
            assertEquals(SkinVariant.SLIM, draft.variant());
            assertEquals(com.naocraftlab.skins.core.model.PersonalSkinSource.URL, draft.source());
            assertArrayEquals(png, draft.pngBytes());
        }
        byte[] exposed = normalized.pngBytes();
        exposed[0] = 0;
        assertArrayEquals(png, normalized.pngBytes());
        byte[] supplied = png.clone();
        var copied = new NormalizedSkin(supplied, normalized.detectedVariant(), normalized.featureEvidence());
        supplied[0] = 0;
        assertArrayEquals(png, copied.pngBytes());
    }

    @Test
    void typedLookupAndImageFailuresAreNotCollapsed() throws Exception {
        var reference = new PublicProfileLookup.VerifiedTexture() {};
        var observation = new PublicProfileLookup.Observation("Player", Optional.of(reference),
                SkinVariant.CLASSIC, Optional.empty());
        for (var code : PublicSkinImportException.Code.values()) {
            var failure = new PublicSkinImportException(code, "unavailable");
            var lookupFailure = new PublicSkinImportService(Optional.of(query -> { throw failure; }),
                    unusedImages(), (collection, skin, model) -> { throw new AssertionError(); });
            assertEquals(failure, assertThrows(PublicSkinImportException.class,
                    () -> lookupFailure.loadPlayer("Player")));
            var images = new PublicSkinImageSource() {
                @Override public NormalizedSkin loadVerified(PublicProfileLookup.VerifiedTexture texture)
                        throws Exception { throw failure; }
                @Override public NormalizedSkin loadUntrusted(String url) throws Exception { throw failure; }
            };
            var imageFailure = new PublicSkinImportService(Optional.of(query -> observation), images,
                    (collection, skin, model) -> { throw new AssertionError(); });
            assertEquals(failure, assertThrows(PublicSkinImportException.class,
                    () -> imageFailure.loadPlayer("Player")));
            assertEquals(failure, assertThrows(PublicSkinImportException.class,
                    () -> imageFailure.loadUrl("https://example.com/skin.png")));
        }
    }

    @Test
    void invalidDefaultPngIsRejectedWithoutImageIo() {
        var observation = new PublicProfileLookup.Observation("Player", Optional.empty(),
                SkinVariant.CLASSIC, Optional.of("steve"));
        var service = new PublicSkinImportService(Optional.of(query -> observation), unusedImages(),
                (collection, skin, model) -> new byte[]{1, 2, 3});
        assertEquals(PublicSkinImportException.Code.PROFILE_REJECTED,
                assertThrows(PublicSkinImportException.class, () -> service.loadPlayer("Player")).code());
    }

    private static PublicSkinImageSource unusedImages() {
        return new PublicSkinImageSource() {
            @Override public NormalizedSkin loadVerified(PublicProfileLookup.VerifiedTexture texture) { throw new AssertionError(); }
            @Override public NormalizedSkin loadUntrusted(String url) { throw new AssertionError(); }
        };
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
