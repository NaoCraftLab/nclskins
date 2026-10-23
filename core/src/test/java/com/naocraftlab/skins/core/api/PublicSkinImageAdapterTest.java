package com.naocraftlab.skins.core.api;

import com.naocraftlab.skins.core.importing.PublicProfileLookup;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import com.naocraftlab.skins.core.storage.TextureCache;
import com.naocraftlab.skins.core.storage.TextureCacheException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.time.Clock;
import static org.junit.jupiter.api.Assertions.*;

final class PublicSkinImageAdapterTest {
    @Test
    void explicitModelUrlPreservesActualContainerInsteadOfRequiringPng(@TempDir Path directory) throws Exception {
        byte[] model = Files.readAllBytes(Path.of("../gradle/build-logic/src/cpmComposition/fixtures/model-conversion/classic-embedded.cpmmodel"));
        var transport = new PinnedHttpsTransport() {
            @Override FileResponse getToFile(URI uri, String host, java.util.List<java.net.InetAddress> addresses,
                    java.time.Duration timeout, long maximum, Path destination) throws java.io.IOException {
                Files.write(destination, model);
                return new FileResponse(200, uri, java.util.Map.of("content-type", java.util.List.of("image/png")), model.length);
            }
            @Override <T> StreamResponse<T> getStreaming(URI uri, String host, java.util.List<java.net.InetAddress> addresses,
                    java.time.Duration timeout, long maximum, PublicHttpsImageFetcher.BodyReader<T> reader) throws java.io.IOException {
                assertEquals("example.com", host);
                assertEquals(java.util.List.of(java.net.InetAddress.getByName("8.8.8.8")), addresses);
                return new StreamResponse<>(200, uri, java.util.Map.of("content-type", java.util.List.of("text/plain")),
                        reader.read(new java.io.ByteArrayInputStream(model)));
            }
        };
        var remote = new SafeRemotePngFetcher(transport, new PngValidator(),
                host -> new java.net.InetAddress[]{java.net.InetAddress.getByName("8.8.8.8")});
        var storage = new NclSkinsStorage(directory, new PngValidator(), Clock.systemUTC());
        var adapter = new PublicSkinImageAdapter(new TextureCache(storage), remote, new PngValidator());
        var acquired = assertInstanceOf(com.naocraftlab.skins.core.importing.PublicSkinImageSource.ModelInput.class,
                adapter.acquireUrl("https://example.com/not-a-model.PNG"));
        try (var input = acquired.input(); var reader = input.open()) {
            assertEquals(com.naocraftlab.skins.client.ImportInput.Kind.MODEL, input.kind());
            assertArrayEquals(model, reader.readAllBytes());
        }
    }

    @Test
    void modelFrameIsOwnedUnchangedUntilNativeParser(@TempDir Path directory) throws Exception {
        byte[] valid = Files.readAllBytes(Path.of("../gradle/build-logic/src/cpmComposition/fixtures/model-conversion/classic-embedded.cpmmodel"));
        byte[] invalid = valid.clone(); invalid[invalid.length - 1] ^= 1;
        var accepted = assertInstanceOf(com.naocraftlab.skins.core.importing.PublicSkinImageSource.ModelInput.class,
                PublicSkinImageAdapter.acquireLocal(Files.write(directory.resolve("valid.bin"), valid)));
        accepted.input().close();
        var pending = assertInstanceOf(com.naocraftlab.skins.core.importing.PublicSkinImageSource.ModelInput.class,
                PublicSkinImageAdapter.acquireLocal(Files.write(directory.resolve("invalid.bin"), invalid)));
        try (var frozen = pending.input(); var reader = frozen.open()) {
            assertArrayEquals(invalid, reader.readAllBytes());
        }
    }

    @Test
    void verifiedLookupUsesExistingCacheAndPreservesNormalizedBytes(@TempDir Path directory) throws Exception {
        String hash = "a".repeat(64);
        String payload = Base64.getEncoder().encodeToString(("""
                {"profileId":"0123456789abcdef0123456789abcdef","profileName":"Player",
                 "textures":{"SKIN":{"url":"http://textures.minecraft.net/texture/%s"}}}
                """).formatted(hash).getBytes(StandardCharsets.UTF_8));
        var validator = new PngValidator();
        var storage = new NclSkinsStorage(directory, validator, Clock.systemUTC());
        storage.initialize();
        var textures = new TextureCache(storage);
        byte[] png = com.naocraftlab.skins.core.test.TestPng.create(64, 64);
        Files.write(textures.cachePath(URI.create("https://textures.minecraft.net/texture/" + hash)), png);
        try (var fixture = new PublicPlayerSkinClientTest.Fixture(payload)) {
            var observation = fixture.client((value, signature) -> Optional.of(value)).lookup("Player");
            var image = new PublicSkinImageAdapter(textures).loadVerified(observation.texture().orElseThrow());
            assertArrayEquals(validator.normalizeSkinWithVariant(png).pngBytes(), image.pngBytes());
            assertEquals(validator.normalizeSkinWithVariant(png).detectedVariant(), image.detectedVariant());
            Files.write(textures.cachePath(URI.create("https://textures.minecraft.net/texture/" + hash)),
                    com.naocraftlab.skins.core.test.TestPng.create(32, 32));
            assertEquals(PublicSkinImportException.Code.PROFILE_REJECTED,
                    assertThrows(PublicSkinImportException.class, () -> new PublicSkinImageAdapter(textures)
                            .loadVerified(observation.texture().orElseThrow())).code());
        }
    }

    @Test
    void forgedReferenceAndUnsafeUrlCannotReachOfficialCache(@TempDir Path directory) {
        var storage = new NclSkinsStorage(directory, new PngValidator(), Clock.systemUTC());
        var adapter = new PublicSkinImageAdapter(new TextureCache(storage));
        assertEquals(PublicSkinImportException.Code.PROFILE_REJECTED,
                assertThrows(PublicSkinImportException.class,
                        () -> adapter.loadVerified(new PublicProfileLookup.VerifiedTexture() {})).code());
        assertEquals(PublicSkinImportException.Code.UNSAFE_URL,
                assertThrows(PublicSkinImportException.class,
                        () -> adapter.loadUntrusted("file:///skin.png")).code());
    }

    @Test
    void mapsEveryCacheFailureWithoutSensitiveDetail() {
        for (var code : TextureCacheException.Code.values()) {
            var expected = switch (code) {
                case NETWORK_FAILURE, HTTP_FAILURE -> PublicSkinImportException.Code.NETWORK_FAILURE;
                case OVERSIZED -> PublicSkinImportException.Code.OVERSIZED;
                case HOST_NOT_ALLOWLISTED, REDIRECT_REJECTED, INVALID_TEXTURE -> PublicSkinImportException.Code.PROFILE_REJECTED;
            };
            var failure = PublicSkinImageAdapter.playerTextureFailure(new TextureCacheException(code, "sensitive detail"));
            assertEquals(expected, failure.code());
            assertFalse(failure.getMessage().contains("sensitive detail"));
            assertNull(failure.getCause());
        }
    }
}
